<#
  Holt Forge (Card-Forge/forge, GPL-3.0) am gepinnten Commit, baut forge-core/-game/-ai/-gui mit Maven
  und legt Jars + res nach vendor\forge ab. Nur dieses Skript erzeugt vendor\forge (Plan 5.1 / 5.11).

  Aufruf: powershell -ExecutionPolicy Bypass -File scripts\import-forge.ps1 [-Commit <sha>] [-Scratch <dir>] [-FullRes] [-KeepScratch]

    -Commit       40-hex-SHA (Default: Inhalt von vendor\forge\FORGE_COMMIT). Ist der Wert ein anderer als der
                  Pin, wird FORGE_COMMIT nach dem Erfolg auf den neuen Wert gesetzt (= Bump).
    -Scratch      Arbeitskopie (Default %LOCALAPPDATA%\MageLite-build\forge-src, ausserhalb des Repos)
    -FullRes      komplettes res\ uebernehmen statt der Allow-Liste (cardsfolder/tokenscripts werden trotzdem gezippt)
    -KeepScratch  Arbeitskopie nach Erfolg behalten (naechster Lauf mit gleichem Commit fetcht nicht neu)

  Ergebnis: vendor\forge\{FORGE_COMMIT, manifest.json, forge.profile.properties, LICENSE-Forge.txt, lib\*.jar, res\}
  Karten-Fixes: vendor\forge-overrides\cardsfolder\<x>\<name>.txt werden ins cardsfolder.zip eingebacken.
#>
param(
    [string]$Commit,
    [string]$Scratch,
    [switch]$FullRes,
    [switch]$KeepScratch
)
$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem

$clock       = [Diagnostics.Stopwatch]::StartNew()
$root        = Split-Path -Parent $PSScriptRoot
$vendorRoot  = Join-Path $root 'vendor'
$forgeDir    = Join-Path $vendorRoot 'forge'
$tmpDir      = Join-Path $vendorRoot 'forge.tmp'
$oldDir      = Join-Path $vendorRoot 'forge.old'
$overrideDir = Join-Path $vendorRoot 'forge-overrides'
$pinFile     = Join-Path $forgeDir 'FORGE_COMMIT'
$buildRoot   = Join-Path $env:LOCALAPPDATA 'MageLite-build'
if (-not $Scratch) { $Scratch = Join-Path $buildRoot 'forge-src' }
$Scratch = [IO.Path]::GetFullPath($Scratch)

# Maven-Bootstrap (kein winget): gepinnte 3.9.x-Distribution, SHA-512 aus der offiziellen .sha512 (archive.apache.org)
$mavenVersion = '3.9.16'
$mavenSha512  = 'ed41650d42485cfc243fad22158caf9cbb5dc408ce7a09ddb94dd42a019de929ca43065bfa450612cf12bf78b5cafa3884b96c090de326ff590448c933454af3'
$mavenUrl     = "https://archive.apache.org/dist/maven/maven-3/$mavenVersion/binaries/apache-maven-$mavenVersion-bin.zip"
$mavenMinimum = [version]'3.8.1'     # Enforcer im Forge-Root-POM
$depPlugin    = 'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies'

# Jars, die NICHT nach vendor\forge\lib kommen (Plan F1/F2/5.11 Regel 5):
#   jetty-*, javax.servlet-api*, org.jupnp.support*  = LAN/UPnP-Spiel von forge-gui; Javalin bringt eigenes Jetty 11 (gleiche Packages)
#   slf4j-tinylog*, slf4j-api*                       = zweiter slf4j-Provider; MageLite loggt ueber reload4j
# BLEIBT: org.jupnp-<v>.jar (nur org.jupnp.*, 0,7 MB, kein Package-Konflikt). IGuiBase.getUpnpPlatformService() hat
# org.jupnp.UpnpServiceConfiguration in der Signatur: ohne das Jar laesst sich keine IGuiBase-Implementierung
# (boot/HeadlessGui) kompilieren und Proxy/Reflection auf IGuiBase wirft NoClassDefFoundError. Wird nie instanziiert.
$excludeJarPatterns = @('jetty-*', 'javax.servlet-api*', 'org.jupnp.support*', 'slf4j-tinylog*', 'slf4j-api*')

# res\-Allow-Liste (Verzeichnisse komplett). Begruendung, geprueft am gepinnten Commit
# (FModel#initialize, ForgeConstants, CardStorageReader, StaticData, Localizer, CardTranslation, AiProfileUtil):
#   ai           AiProfileUtil.loadAllProfiles(AI_PROFILE_DIR) beim Initialisieren
#   blockdata    StaticData: blocks.txt, starters.txt, boosters-special.txt, printsheets.txt (+ CardBlock.Reader)
#   defaults     ForgeConstants-FileLocation/NO_CARD_FILE; headless ungenutzt, aber winzig (lieber eine Datei zu viel)
#   editions     CardEdition.Reader: alle Drucke/Sets
#   formats      GameFormat.Reader: Commander-Bannliste = StaticData.getCommanderPredicate()
#   licenses     GPL-Pflichtangaben (Installer)
#   lists        TypeLists.txt + NonStackingKWList.txt (FModel.loadDynamicGamedata), Bracket-Listen (Commander), token-images
#   setlookup    StaticData: Set-Lookup-Tabellen fuer Kartenbilder
#   languages    NUR *.properties: Localizer.initialize braucht en-US.properties (+ Fallback); die cardnames-*.txt
#                (63 MB) liest CardTranslation nur bei UI-Sprache != en-US
#   quest\commanderprecons  ~250 Commander-Precons (optionale Bot-Decks, Plan F4)
#   cardsfolder / tokenscripts  werden als cardsfolder.zip gebaut (CardStorageReader sucht <dir>\cardsfolder.zip
#                auch fuer tokenscripts: Pfad enthaelt "token" => Token-Modus)
$resKeepDirs = @('ai', 'blockdata', 'defaults', 'editions', 'formats', 'licenses', 'lists', 'setlookup')

function Write-Step([string]$n, [string]$text) { Write-Host ("[{0}/9] {1}" -f $n, $text) -ForegroundColor Cyan }

function Remove-Tree([string]$path) {
    if (-not (Test-Path -LiteralPath $path)) { return }
    $null = & cmd /c "rd /s /q `"$path`" 2>&1"
    if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Recurse -Force }
}

# Native Programme immer ueber cmd /c "... 2>&1" (stderr wuerde unter Stop sonst zum Abbruch), Exit-Code pruefen.
function Invoke-Native {
    param([string]$Line, [string]$Dir, [string]$Log, [string]$Show, [switch]$Quiet)
    $lines = New-Object 'System.Collections.Generic.List[string]'
    if ($Dir) { Push-Location $Dir }
    try {
        & cmd /c "$Line 2>&1" | ForEach-Object {
            $s = [string]$_
            $lines.Add($s)
            if (-not $Quiet) {
                if ($Show) { if ($s -match $Show) { Write-Host $s } } else { Write-Host $s }
            }
        }
        $code = $LASTEXITCODE
    } finally {
        if ($Dir) { Pop-Location }
    }
    if ($Log) { [IO.File]::WriteAllLines($Log, $lines.ToArray()) }
    if ($code -ne 0) {
        Write-Host (($lines | Select-Object -Last 60) -join "`n")
        throw "Befehl fehlgeschlagen (Exit $code): $Line"
    }
    return $lines.ToArray()
}

function Get-JavaMajor([string]$javacExe) {
    $text = (@(Invoke-Native "`"$javacExe`" -version" -Quiet)) -join ' '
    if ($text -match 'javac\s+(\d+)\.(\d+)') {
        if ([int]$Matches[1] -eq 1) { return [int]$Matches[2] }
        return [int]$Matches[1]
    }
    if ($text -match 'javac\s+(\d+)') { return [int]$Matches[1] }
    throw "javac-Version nicht lesbar: $text"
}

function Get-MavenVersion([string]$mvnCmd) {
    try {
        $text = (@(Invoke-Native "`"$mvnCmd`" -v" -Quiet)) -join ' '
    } catch { return $null }
    if ($text -match 'Apache Maven (\d+\.\d+\.\d+)') { return [version]$Matches[1] }
    return $null
}

# Zip mit Forward-Slash-Eintraegen (wie "find . -name '*.txt' | zip" in cardsfolder\mkzip.sh). Overrides ersetzen den
# Eintrag gleichen Pfads (sonst gleichen Dateinamens), neue Dateien werden angehaengt.
function New-ScriptZip {
    param([string]$SrcDir, [string]$DestZip, [string]$OverrideSub, [DateTimeOffset]$Stamp)
    $map = New-Object 'System.Collections.Generic.SortedDictionary[string,string]' ([StringComparer]::Ordinal)
    $srcFull = [IO.Path]::GetFullPath($SrcDir).TrimEnd('\')
    foreach ($f in [IO.Directory]::EnumerateFiles($srcFull, '*', [IO.SearchOption]::AllDirectories)) {
        if (-not $f.EndsWith('.txt', [StringComparison]::OrdinalIgnoreCase)) { continue }
        $rel = $f.Substring($srcFull.Length + 1).Replace('\', '/')
        if ($rel -match '(^|/)\.') { continue }
        $map[$rel] = $f
    }
    $applied = New-Object 'System.Collections.Generic.List[string]'
    $ovRoot = $null
    if ($OverrideSub) { $ovRoot = Join-Path $overrideDir $OverrideSub }
    if ($ovRoot -and (Test-Path -LiteralPath $ovRoot)) {
        $ovFull = [IO.Path]::GetFullPath($ovRoot).TrimEnd('\')
        foreach ($f in [IO.Directory]::EnumerateFiles($ovFull, '*', [IO.SearchOption]::AllDirectories)) {
            if (-not $f.EndsWith('.txt', [StringComparison]::OrdinalIgnoreCase)) { continue }
            $rel = $f.Substring($ovFull.Length + 1).Replace('\', '/')
            if (-not ([IO.File]::ReadAllText($f) -match '(?m)^#\s*MageLite:')) {
                throw "Override ohne Kommentarzeile '# MageLite: <Grund>': $f"
            }
            $target = $rel
            if (-not $map.ContainsKey($rel)) {
                $name = [IO.Path]::GetFileName($rel)
                $hit = @($map.Keys | Where-Object { $_ -eq $name -or $_.EndsWith('/' + $name) })
                if ($hit.Count -eq 1) {
                    $target = $hit[0]
                    Write-Warning "Override $rel liegt in Forge unter $target - ersetzt diesen Eintrag."
                } elseif ($hit.Count -gt 1) {
                    throw "Override $rel ist mehrdeutig (Treffer: $($hit -join ', '))."
                } else {
                    Write-Warning "Override $rel hat kein Gegenstueck in Forge - wird als neue Datei angehaengt."
                }
            }
            $map[$target] = $f
            $applied.Add("$OverrideSub/$rel")
        }
    }
    $partial = "$DestZip.partial"
    if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial -Force }
    $fs = [IO.File]::Create($partial)
    try {
        $zip = New-Object IO.Compression.ZipArchive($fs, [IO.Compression.ZipArchiveMode]::Create, $false)
        try {
            foreach ($kv in $map.GetEnumerator()) {
                $entry = $zip.CreateEntry($kv.Key, [IO.Compression.CompressionLevel]::Optimal)
                $entry.LastWriteTime = $Stamp
                $in = [IO.File]::OpenRead($kv.Value)
                $out = $entry.Open()
                try { $in.CopyTo($out) } finally { $out.Dispose(); $in.Dispose() }
            }
        } finally { $zip.Dispose() }
    } finally { $fs.Dispose() }
    Move-Item -LiteralPath $partial -Destination $DestZip -Force
    return [pscustomobject]@{ Count = $map.Count; Overrides = $applied.ToArray() }
}

# ---------------------------------------------------------------------------------------------------------------
# Commit bestimmen
$pinned = $null
if (Test-Path -LiteralPath $pinFile) { $pinned = (Get-Content -Raw -LiteralPath $pinFile).Trim().ToLowerInvariant() }
if (-not $Commit) {
    if (-not $pinned) { throw "Weder -Commit noch $pinFile vorhanden." }
    $Commit = $pinned
}
$Commit = $Commit.Trim().ToLowerInvariant()
if ($Commit -notmatch '^[0-9a-f]{40}$') { throw "Kein 40-stelliger Commit-SHA: '$Commit'" }
Write-Host "Forge-Commit: $Commit"
Write-Host "Scratch:      $Scratch"

# ---------------------------------------------------------------------------------------------------------------
Write-Step 1 'Voraussetzungen (git, JDK >= 17, Maven >= 3.8.1)'

if (-not (Get-Command git -ErrorAction SilentlyContinue)) { throw 'git nicht gefunden (PATH).' }

# JAVA_HOME: vorhandenen Wert nur behalten, wenn dort ein JDK >= 17 liegt; sonst aus dem Pfad von javac ableiten
$javac = $null
if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
    $javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
    if ((Get-JavaMajor $javac) -lt 17) { $javac = $null }
}
if (-not $javac) {
    $cmd = Get-Command javac -ErrorAction SilentlyContinue
    if (-not $cmd) { throw 'javac nicht gefunden - JDK 17+ installieren (PATH oder JAVA_HOME).' }
    $javac = $cmd.Source
    if ((Get-JavaMajor $javac) -lt 17) { throw "JDK >= 17 noetig, gefunden: $javac" }
    $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $javac)
    Write-Host "JAVA_HOME gesetzt: $env:JAVA_HOME"
}
$jdkText = ((@(Invoke-Native "`"$javac`" -version" -Quiet)) -join ' ').Trim()
Write-Host "JDK:   $jdkText"

# Maven: vorhandenes >= 3.8.1 nehmen, sonst gepinnte Distribution holen
$mvn = $null
$mvnVer = $null
$found = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if ($found) {
    $v = Get-MavenVersion $found.Source
    if ($v -and $v -ge $mavenMinimum) { $mvn = $found.Source; $mvnVer = $v }
}
if (-not $mvn) {
    $mvnRoot = Join-Path $buildRoot "maven-$mavenVersion"
    $mvn = Join-Path $mvnRoot "apache-maven-$mavenVersion\bin\mvn.cmd"
    if (-not (Test-Path -LiteralPath $mvn)) {
        Write-Host "Lade Maven $mavenVersion ..."
        New-Item -ItemType Directory -Force $buildRoot | Out-Null
        $zipPath = Join-Path $buildRoot "apache-maven-$mavenVersion-bin.zip"
        Invoke-WebRequest $mavenUrl -OutFile $zipPath -UseBasicParsing
        $hash = (Get-FileHash $zipPath -Algorithm SHA512).Hash.ToLowerInvariant()
        if ($hash -ne $mavenSha512) {
            Remove-Item $zipPath -Force
            throw "SHA-512 von Maven $mavenVersion stimmt nicht (erwartet $mavenSha512, ist $hash)."
        }
        $part = "$mvnRoot.partial"
        Remove-Tree $part
        [IO.Compression.ZipFile]::ExtractToDirectory($zipPath, $part)
        Remove-Tree $mvnRoot
        [IO.Directory]::Move($part, $mvnRoot)
        Remove-Item $zipPath -Force
    }
    $mvnVer = Get-MavenVersion $mvn
    if (-not $mvnVer -or $mvnVer -lt $mavenMinimum) { throw "Maven unter $mvn nicht lauffaehig oder zu alt." }
}
Write-Host "Maven: $mvnVer ($mvn)"

# Java-Prozesse, die vendor\forge halten, blockieren das Ersetzen am Ende (die installierte App sperrt vendor nicht)
$locking = @()
try {
    $locking = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" |
        Where-Object { $_.CommandLine -match 'vendor[\\/]forge[\\/]' })
} catch { Write-Warning "Prozessliste nicht lesbar: $($_.Exception.Message)" }
if ($locking.Count -gt 0) {
    throw ("Java-Prozess nutzt vendor\forge (PID $($locking[0].ProcessId)) - bitte beenden: " + $locking[0].CommandLine)
}

# ---------------------------------------------------------------------------------------------------------------
Write-Step 2 'Forge holen (sparse + shallow)'

$patterns = @('/pom.xml', '/checkstyle.xml', '/LICENSE*', '/forge-core/', '/forge-game/', '/forge-ai/',
              '/forge-gui/pom.xml', '/forge-gui/src/main/')
if ($FullRes) {
    $patterns += '/forge-gui/res/'
} else {
    foreach ($d in $resKeepDirs) { $patterns += "/forge-gui/res/$d/" }
    $patterns += '/forge-gui/res/languages/*.properties'
    $patterns += '/forge-gui/res/quest/commanderprecons/'
    $patterns += '/forge-gui/res/cardsfolder/'
    $patterns += '/forge-gui/res/tokenscripts/'
}
$patternText = ($patterns -join "`n") + "`n"
$sha = [Security.Cryptography.SHA256]::Create()
$patternHash = ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::ASCII.GetBytes($patternText)))).Replace('-', '').Substring(0, 16)
$stampWanted = "$Commit|$patternHash"
$stampFile = Join-Path $Scratch '.magelite-stamp'

$reuse = $false
if ((Test-Path -LiteralPath (Join-Path $Scratch '.git')) -and (Test-Path -LiteralPath $stampFile)) {
    if ((Get-Content -Raw -LiteralPath $stampFile).Trim() -eq $stampWanted) {
        try {
            $head = (@(Invoke-Native "git -C `"$Scratch`" rev-parse HEAD" -Quiet))[0].Trim()
            if ($head -eq $Commit) { $reuse = $true }
        } catch { $reuse = $false }
    }
}
if ($reuse) {
    Write-Host 'Scratch passt (gleicher Commit + Pfadliste) - kein neuer Fetch.'
    Invoke-Native "git -C `"$Scratch`" reset -q --hard HEAD" -Quiet | Out-Null
    foreach ($m in 'forge-core', 'forge-game', 'forge-ai', 'forge-gui') { Remove-Tree (Join-Path $Scratch "$m\target") }
} else {
    Remove-Tree $Scratch
    New-Item -ItemType Directory -Force $Scratch | Out-Null
    Invoke-Native "git -C `"$Scratch`" init -q ." -Quiet | Out-Null
    Invoke-Native "git -C `"$Scratch`" config core.longpaths true" -Quiet | Out-Null
    Invoke-Native "git -C `"$Scratch`" config core.autocrlf false" -Quiet | Out-Null
    Invoke-Native "git -C `"$Scratch`" remote add origin https://github.com/Card-Forge/forge" -Quiet | Out-Null
    Invoke-Native "git -C `"$Scratch`" sparse-checkout init --no-cone" -Quiet | Out-Null
    [IO.File]::WriteAllText((Join-Path $Scratch '.git\info\sparse-checkout'), $patternText, [Text.Encoding]::ASCII)
    Write-Host 'Fetch (shallow, blobless) ...'
    Invoke-Native "git -C `"$Scratch`" fetch -q --depth 1 --filter=blob:none origin $Commit" | Out-Null
    Write-Host 'Checkout (laedt die Blobs der Pfadliste) ...'
    Invoke-Native "git -C `"$Scratch`" checkout -q -f FETCH_HEAD" | Out-Null
    $head = (@(Invoke-Native "git -C `"$Scratch`" rev-parse HEAD" -Quiet))[0].Trim()
    if ($head -ne $Commit) { throw "Checkout liefert $head statt $Commit" }
    [IO.File]::WriteAllText($stampFile, $stampWanted + "`n", [Text.Encoding]::ASCII)
}
$commitDate = (@(Invoke-Native "git -C `"$Scratch`" log -1 --format=%cI HEAD" -Quiet))[0].Trim()
Write-Host "Commit-Datum: $commitDate"
$resSrc = Join-Path $Scratch 'forge-gui\res'

# ---------------------------------------------------------------------------------------------------------------
Write-Step 3 'Wachen (Sentry, Kartenskripte)'

$sentryHits = @(Get-ChildItem -Path (Join-Path $Scratch 'forge-core\src'), (Join-Path $Scratch 'forge-game\src'),
                              (Join-Path $Scratch 'forge-ai\src'), (Join-Path $Scratch 'forge-gui\src') `
                -Recurse -Filter *.java -File |
        Select-String -SimpleMatch 'Sentry.init' | Select-Object -First 3)
if ($sentryHits.Count -gt 0) {
    throw ("Sentry.init in den Forge-Modulen gefunden (Plan F9): " + $sentryHits[0].Path + ':' + $sentryHits[0].LineNumber)
}
$cardFiles = @(Get-ChildItem -Path (Join-Path $resSrc 'cardsfolder') -Recurse -Filter *.txt -File).Count
Write-Host "cardsfolder: $cardFiles Skripte"
if ($cardFiles -lt 25000) { throw "cardsfolder hat nur $cardFiles Skripte (< 25000) - Checkout unvollstaendig?" }

# ---------------------------------------------------------------------------------------------------------------
Write-Step 4 'Reactor kuerzen (4 Module)'

$pomPath = Join-Path $Scratch 'pom.xml'
$pomText = [IO.File]::ReadAllText($pomPath)
$modRe = New-Object Text.RegularExpressions.Regex('<modules>.*?</modules>', [Text.RegularExpressions.RegexOptions]::Singleline)
if (-not $modRe.IsMatch($pomText)) { throw 'Kein <modules>-Block im Root-POM.' }
$modNew = "<modules>`n        <module>forge-core</module>`n        <module>forge-game</module>`n        <module>forge-ai</module>`n        <module>forge-gui</module>`n    </modules>"
$pomText = $modRe.Replace($pomText, $modNew, 1)
[IO.File]::WriteAllText($pomPath, $pomText, (New-Object Text.UTF8Encoding($false)))
$forgeVersion = $null
if ($pomText -match '<versionCode>([^<]+)</versionCode>') {
    $forgeVersion = $Matches[1]
    if ($pomText -match '<snapshotName>([^<]*)</snapshotName>') { $forgeVersion += $Matches[1] }
}
Write-Host "Forge-Version laut POM: $forgeVersion"

# ---------------------------------------------------------------------------------------------------------------
Write-Step 5 'Bauen (Maven; erster Lauf laedt viele Abhaengigkeiten, 5-15 min)'

$mvnLog = Join-Path $Scratch 'mvn-build.log'
$mvnLine = "`"$mvn`" -B -ntp -pl forge-gui -am -DskipTests -Dmaven.test.skip=true -Dcheckstyle.skip=true package $depPlugin -DincludeScope=runtime"
$script:dlCount = 0
$mvnClock = [Diagnostics.Stopwatch]::StartNew()
$lines = New-Object 'System.Collections.Generic.List[string]'
Push-Location $Scratch
try {
    & cmd /c "$mvnLine 2>&1" | ForEach-Object {
        $s = [string]$_
        $lines.Add($s)
        if ($s -match '^Downloaded from') {
            $script:dlCount++
            if ($script:dlCount % 50 -eq 0) { Write-Host ("  ... {0} Downloads" -f $script:dlCount) }
        } elseif ($s -match 'Building (forge|Forge)|BUILD (SUCCESS|FAILURE)|Reactor Summary|\[ERROR\]|Forge .* (SUCCESS|FAILURE)') {
            Write-Host $s
        }
    }
    $mvnCode = $LASTEXITCODE
} finally { Pop-Location }
[IO.File]::WriteAllLines($mvnLog, $lines.ToArray())
if ($mvnCode -ne 0) {
    Write-Host (($lines | Select-Object -Last 60) -join "`n")
    throw "Maven-Build fehlgeschlagen (Exit $mvnCode), Log: $mvnLog"
}
Write-Host ("Maven fertig in {0:n0} s (Log: {1})" -f $mvnClock.Elapsed.TotalSeconds, $mvnLog)

# ---------------------------------------------------------------------------------------------------------------
Write-Step 6 'Jars einsammeln'

Remove-Tree $tmpDir
Remove-Tree $oldDir
$libDir = Join-Path $tmpDir 'lib'
$resDir = Join-Path $tmpDir 'res'
New-Item -ItemType Directory -Force $libDir, $resDir | Out-Null

function Test-JarExcluded([string]$name) {
    foreach ($p in $excludeJarPatterns) { if ($name -like $p) { return $true } }
    return $false
}
$jarCandidates = @()
$guiJar = @(Get-ChildItem (Join-Path $Scratch 'forge-gui\target') -Filter 'forge-gui-*.jar' -File |
    Where-Object { $_.Name -notmatch '-(sources|javadoc|tests)\.jar$' })
if ($guiJar.Count -ne 1) { throw "forge-gui-*.jar nicht eindeutig in forge-gui\target ($($guiJar.Count) Treffer)." }
$jarCandidates += $guiJar[0]
$depDir = Join-Path $Scratch 'forge-gui\target\dependency'
if (-not (Test-Path -LiteralPath $depDir)) { throw "$depDir fehlt - copy-dependencies lief nicht." }
$jarCandidates += @(Get-ChildItem $depDir -Filter *.jar -File)

$excludedJars = @()
$copiedJars = @()
foreach ($j in $jarCandidates) {
    if (Test-JarExcluded $j.Name) { $excludedJars += $j.Name; continue }
    Copy-Item -LiteralPath $j.FullName -Destination (Join-Path $libDir $j.Name) -Force
    $copiedJars += $j.Name
}
# Geschwister-Module: falls copy-dependencies sie im Reactor nicht als Jar fand, direkt aus target\ nehmen
foreach ($m in 'forge-core', 'forge-game', 'forge-ai') {
    if (-not (Get-ChildItem $libDir -Filter "$m-*.jar" -File)) {
        $own = @(Get-ChildItem (Join-Path $Scratch "$m\target") -Filter "$m-*.jar" -File |
            Where-Object { $_.Name -notmatch '-(sources|javadoc|tests)\.jar$' })
        if ($own.Count -ne 1) { throw "$m-Jar nicht auffindbar." }
        Copy-Item -LiteralPath $own[0].FullName -Destination (Join-Path $libDir $own[0].Name) -Force
        $copiedJars += $own[0].Name
        Write-Warning "$m-Jar kam nicht ueber copy-dependencies, direkt aus target\ uebernommen."
    }
}
$libJars = @(Get-ChildItem $libDir -Filter *.jar -File | Sort-Object Name)
$libBytes = ($libJars | Measure-Object Length -Sum).Sum
Write-Host ("lib: {0} Jars, {1:n1} MB; ausgeschlossen: {2}" -f $libJars.Count, ($libBytes / 1MB), $excludedJars.Count)
if ($excludedJars.Count -gt 0) { Write-Host ('  ' + (($excludedJars | Sort-Object) -join ', ')) }
foreach ($m in 'forge-core', 'forge-game', 'forge-ai', 'forge-gui') {
    if (-not ($libJars | Where-Object { $_.Name -like "$m-*" })) { throw "$m-Jar fehlt in lib." }
}

# ---------------------------------------------------------------------------------------------------------------
Write-Step 7 'res zusammenstellen'

$stamp = [DateTimeOffset]::Parse($commitDate)
$resDirs = New-Object 'System.Collections.Generic.List[string]'
if ($FullRes) {
    foreach ($item in Get-ChildItem -LiteralPath $resSrc -Force) {
        if ($item.PSIsContainer -and ($item.Name -eq 'cardsfolder' -or $item.Name -eq 'tokenscripts')) { continue }
        Copy-Item -LiteralPath $item.FullName -Destination $resDir -Recurse -Force
        if ($item.PSIsContainer) { $resDirs.Add($item.Name) }
    }
} else {
    foreach ($d in $resKeepDirs) {
        Copy-Item -LiteralPath (Join-Path $resSrc $d) -Destination $resDir -Recurse -Force
        $resDirs.Add($d)
    }
    New-Item -ItemType Directory -Force (Join-Path $resDir 'languages') | Out-Null
    Copy-Item (Join-Path $resSrc 'languages\*.properties') (Join-Path $resDir 'languages') -Force
    $resDirs.Add('languages')
    New-Item -ItemType Directory -Force (Join-Path $resDir 'quest') | Out-Null
    Copy-Item -LiteralPath (Join-Path $resSrc 'quest\commanderprecons') -Destination (Join-Path $resDir 'quest') -Recurse -Force
    $resDirs.Add('quest/commanderprecons')
}

Write-Host 'cardsfolder.zip bauen (inkl. vendor\forge-overrides\cardsfolder) ...'
New-Item -ItemType Directory -Force (Join-Path $resDir 'cardsfolder') | Out-Null
$cardZip = New-ScriptZip -SrcDir (Join-Path $resSrc 'cardsfolder') -DestZip (Join-Path $resDir 'cardsfolder\cardsfolder.zip') `
    -OverrideSub 'cardsfolder' -Stamp $stamp
$resDirs.Add('cardsfolder (zip)')
Write-Host ("  {0} Karten-Skripte, {1} Overrides" -f $cardZip.Count, $cardZip.Overrides.Count)

Write-Host 'tokenscripts\cardsfolder.zip bauen ...'
New-Item -ItemType Directory -Force (Join-Path $resDir 'tokenscripts') | Out-Null
$tokZip = New-ScriptZip -SrcDir (Join-Path $resSrc 'tokenscripts') -DestZip (Join-Path $resDir 'tokenscripts\cardsfolder.zip') `
    -OverrideSub '' -Stamp $stamp
$resDirs.Add('tokenscripts (zip)')
Write-Host ("  {0} Token-Skripte" -f $tokZip.Count)

$editionFiles = @(Get-ChildItem (Join-Path $resDir 'editions') -Recurse -File -Filter *.txt).Count
$resBytes = (Get-ChildItem $resDir -Recurse -File | Measure-Object Length -Sum).Sum
Write-Host ("res: {0:n1} MB, {1} Editionsdateien" -f ($resBytes / 1MB), $editionFiles)
if ($editionFiles -lt 500) { throw "Nur $editionFiles Editionsdateien (< 500) - res\editions unvollstaendig?" }

# ---------------------------------------------------------------------------------------------------------------
Write-Step 8 'Metadaten'

$ascii = [Text.Encoding]::ASCII
$utf8 = New-Object Text.UTF8Encoding($false)

# Schluesselnamen aus ForgeProfileProperties (userDir, cacheDir, cardPicsDir, decksDir); relative Pfade loesen gegen
# das Arbeitsverzeichnis (= MageLite-Datenordner) auf. Wird von ForgeConstants.PROFILE_FILE = <assetsDir>forge.profile.properties gelesen.
$profile = @(
    '# MageLite: Forge-Profil. Pfade relativ zum Arbeitsverzeichnis (= Datenordner der Engine).',
    'userDir=forge-data/user',
    'cacheDir=forge-data/cache',
    'cardPicsDir=forge-data/cache/pics/cards',
    'decksDir=forge-data/user/decks'
) -join "`n"
[IO.File]::WriteAllText((Join-Path $tmpDir 'forge.profile.properties'), $profile + "`n", $ascii)
[IO.File]::WriteAllText((Join-Path $tmpDir 'FORGE_COMMIT'), $Commit + "`n", $ascii)
Copy-Item -LiteralPath (Join-Path $Scratch 'LICENSE') -Destination (Join-Path $tmpDir 'LICENSE-Forge.txt') -Force

$jarInfo = @()
foreach ($j in $libJars) {
    $jarInfo += [ordered]@{ name = $j.Name; sha256 = (Get-FileHash $j.FullName -Algorithm SHA256).Hash.ToLowerInvariant(); bytes = [long]$j.Length }
}
$manifest = [ordered]@{
    commit       = $Commit
    commitDate   = $commitDate
    forgeVersion = $forgeVersion
    importedAt   = (Get-Date).ToString('o')
    maven        = [string]$mvnVer
    jdk          = $jdkText
    fullRes      = [bool]$FullRes
    jars         = $jarInfo
    excludedJars = @($excludedJars | Sort-Object)
    res          = [ordered]@{
        dirs         = @($resDirs.ToArray())
        bytes        = [long]$resBytes
        cardScripts  = [int]$cardZip.Count
        tokenScripts = [int]$tokZip.Count
        editions     = [int]$editionFiles
    }
    overrides    = @($cardZip.Overrides)
}
[IO.File]::WriteAllText((Join-Path $tmpDir 'manifest.json'), ($manifest | ConvertTo-Json -Depth 5), $utf8)

# ---------------------------------------------------------------------------------------------------------------
Write-Step 9 'Tausch vendor\forge.tmp -> vendor\forge'

$hadOld = Test-Path -LiteralPath $forgeDir
if ($hadOld) { [IO.Directory]::Move($forgeDir, $oldDir) }
try {
    [IO.Directory]::Move($tmpDir, $forgeDir)
} catch {
    if ($hadOld) { [IO.Directory]::Move($oldDir, $forgeDir) }
    throw
}
Remove-Tree $oldDir
if (Test-Path -LiteralPath $oldDir) { Write-Warning "$oldDir konnte nicht geloescht werden - bitte von Hand entfernen." }
if ($pinned -and $pinned -ne $Commit) { Write-Host "FORGE_COMMIT auf $Commit gesetzt (war $pinned)." }

if (-not $KeepScratch) { Remove-Tree $Scratch }

Write-Host ''
Write-Host ("Fertig: {0}  ({1:n0} s)" -f $forgeDir, $clock.Elapsed.TotalSeconds) -ForegroundColor Green
Write-Host ("  Forge {0} @ {1}" -f $forgeVersion, $Commit.Substring(0, 12))
Write-Host ("  {0} Jars ({1:n1} MB), res {2:n1} MB, {3} Karten, {4} Token, {5} Editionen, {6} Overrides" -f `
    $libJars.Count, ($libBytes / 1MB), ($resBytes / 1MB), $cardZip.Count, $tokZip.Count, $editionFiles, $cardZip.Overrides.Count)
