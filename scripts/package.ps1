<#
  Baut den Windows-Installer: desktop\dist\MageLite-Setup-<version>.exe
  Enthaelt Electron, UI, Engine (inkl. Forge-Jars), Forge-Daten (vendor\forge\res) und eine per jlink erzeugte
  Java-Laufzeit. Auf dem Ziel-PC muss nichts vorinstalliert sein.
  Voraussetzung zum Bauen: JDK 17+ (mit jlink/jdeps) und Node.js; vendor\forge kommt aus scripts\import-forge.ps1
  (build.ps1 ruft es bei Bedarf selbst auf).
  Ablauf: build.ps1 -> Forge-Daten pruefen -> SOURCE.txt -> jlink -> Smoke-Start der Engine mit der neuen Laufzeit
  -> electron-builder. Lizenz/Quellcode-Hinweise (LICENSE, LICENSES, SOURCE.txt) liegen im Setup unter resources\.
#>
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$desktop = Join-Path $root 'desktop'
$engineLib = Join-Path $root 'engine\build\install\magelite-engine\lib'
$jreOut = Join-Path $desktop 'out\jre'
$forgeDir = Join-Path $root 'vendor\forge'
$sourceTxt = Join-Path $desktop 'out\SOURCE.txt'

# Module, die jdeps nicht sieht (Reflection, ServiceLoader, TLS, CPU-Messung in GameHost)
$extraModules = @(
    'java.desktop', 'java.logging', 'java.management', 'java.naming', 'java.net.http', 'java.prefs',
    'java.scripting', 'java.sql', 'java.xml', 'jdk.charsets', 'jdk.crypto.ec', 'jdk.localedata',
    'jdk.management', 'jdk.unsupported', 'jdk.zipfs'
)

# Startet ein Programm direkt (Pfade mit Leerzeichen ok). stderr darf PowerShell 5.1 nicht abbrechen lassen.
function Invoke-Native([string]$exe, [string[]]$argv, [switch]$NoStderr) {
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if ($NoStderr) { & $exe @argv 2>$null | ForEach-Object { "$_" } }
        else { & $exe @argv 2>&1 | ForEach-Object { "$_" } }
    } finally { $ErrorActionPreference = $old }
}

Write-Host '== Build (UI, Engine, Desktop-Deps)'
& (Join-Path $PSScriptRoot 'build.ps1')
if ($LASTEXITCODE -ne 0) { throw 'build.ps1 fehlgeschlagen' }

Write-Host '== Forge-Daten pruefen'
# electron-builder ueberspringt fehlende extraResources nur mit Hinweis - ein Setup ohne Kartenskripte waere nutzlos
$cards = Join-Path $forgeDir 'res\cardsfolder\cardsfolder.zip'
if (-not (Test-Path $cards)) {
    Write-Host "FEHLT: $cards" -ForegroundColor Red
    Write-Host 'Forge ist nicht importiert - erst scripts\import-forge.ps1 ausfuehren (powershell -ExecutionPolicy Bypass -File scripts\import-forge.ps1).' -ForegroundColor Red
    exit 1
}
foreach ($f in @('manifest.json', 'forge.profile.properties', 'LICENSE-Forge.txt', 'FORGE_COMMIT')) {
    if (-not (Test-Path (Join-Path $forgeDir $f))) {
        Write-Host "FEHLT: $(Join-Path $forgeDir $f) - scripts\import-forge.ps1 erneut ausfuehren" -ForegroundColor Red
        exit 1
    }
}
$forgeCommit = (Get-Content (Join-Path $forgeDir 'FORGE_COMMIT') -Raw).Trim()
Write-Host "Forge: $forgeCommit"

Write-Host '== SOURCE.txt (GPL-Quellcode-Hinweis)'
# Quellstand dieses Builds: MageLite-Commit (+ Hinweis auf uncommittete Aenderungen) und Forge-Commit.
$appVersion = (Get-Content (Join-Path $desktop 'package.json') -Raw | ConvertFrom-Json).version
$gitHead = 'unbekannt (kein git)'
$gitState = 'unbekannt'
try {
    $h = @(Invoke-Native 'git' @('-C', $root, 'rev-parse', 'HEAD') -NoStderr)
    if ($LASTEXITCODE -eq 0 -and $h.Count -gt 0 -and "$($h[0])".Trim()) { $gitHead = "$($h[0])".Trim() }
    $changes = @(Invoke-Native 'git' @('-C', $root, 'status', '--porcelain') -NoStderr | Where-Object { $_ })
    if ($LASTEXITCODE -eq 0) {
        if ($changes.Count -eq 0) { $gitState = 'sauber (Setup entspricht dem Commit)' }
        else { $gitState = "UNCOMMITTETE AENDERUNGEN/neue Dateien ($($changes.Count)) - dieses Setup entspricht NICHT exakt dem Commit" }
    }
} catch { Write-Host "WARNUNG: git nicht nutzbar ($($_.Exception.Message)) - SOURCE.txt ohne Commit" -ForegroundColor Yellow }
$srcLines = @(
    "MageLite $appVersion - Quellcode-Hinweis (GPL-3.0)",
    '=================================================',
    '',
    'MageLite ist freie Software unter der GNU General Public License, Version 3 oder (nach Wahl) neuer',
    '(GPL-3.0-or-later). Der Lizenztext steht in LICENSE.txt; die Lizenzen der mitgelieferten Komponenten liegen im',
    'Ordner licenses\ sowie unter forge\ (LICENSE-Forge.txt, res\licenses\).',
    '',
    'Quellcode dieses Programms (MageLite: Engine-Anbindung, UI, Desktop-App)',
    '  Repository:   https://github.com/melknoo/xmage_clone',
    "  Commit:       $gitHead",
    "  Arbeitsbaum:  $gitState",
    "  Gebaut am:    $((Get-Date).ToString('yyyy-MM-dd HH:mm'))",
    '',
    'Forge (Regel-Engine, KI und Kartenskripte; GPL-3.0)',
    '  Repository:   https://github.com/Card-Forge/forge',
    "  Commit:       $forgeCommit",
    '  Forge-Build:  scripts/import-forge.ps1 im MageLite-Repository holt Forge an diesem Commit, baut es mit Maven',
    '                und legt die Jars (engine\lib) und die Daten (forge\res) ab.',
    '',
    'Wer dieses Programm erhaelt, darf den zugehoerigen Quellcode ueber die obigen Adressen beziehen, aendern und',
    'weitergeben (GPL-3.0). Es gibt keine Gewaehrleistung, soweit gesetzlich zulaessig.',
    ''
)
New-Item -ItemType Directory -Force (Split-Path -Parent $sourceTxt) | Out-Null
[IO.File]::WriteAllText($sourceTxt, ($srcLines -join "`r`n"), [Text.Encoding]::ASCII)
Write-Host "SOURCE.txt: MageLite $($gitHead.Substring(0, [Math]::Min(12, $gitHead.Length))) / Forge $($forgeCommit.Substring(0, [Math]::Min(12, $forgeCommit.Length))) - Arbeitsbaum: $gitState"

Write-Host '== JDK suchen'
$jlink = $null
$cmd = Get-Command jlink -ErrorAction SilentlyContinue
if ($cmd) { $jlink = $cmd.Source }
elseif ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\jlink.exe'))) { $jlink = Join-Path $env:JAVA_HOME 'bin\jlink.exe' }
if (-not $jlink) {
    Write-Host 'FEHLT: jlink - ein JDK (nicht nur JRE) wird benoetigt, z.B.: winget install EclipseAdoptium.Temurin.21.JDK' -ForegroundColor Red
    exit 1
}
$jdkBin = Split-Path -Parent $jlink
$java = Join-Path $jdkBin 'java.exe'
$jdeps = Join-Path $jdkBin 'jdeps.exe'
$versionLine = [string](Invoke-Native $java @('-version') | Select-Object -First 1)
Write-Host "JDK: $versionLine ($jdkBin)"
$major = 0
if ($versionLine -match 'version "(1\.)?(\d+)') { $major = [int]$Matches[2] }
if ($major -lt 17) { throw 'JDK 17 oder neuer wird benoetigt' }

Write-Host '== Java-Module ermitteln'
$available = @(Invoke-Native $java @('--list-modules') -NoStderr | ForEach-Object { ($_ -split '@')[0].Trim() })
$modules = @('java.base')
Push-Location $engineLib
try {
    # Nur magelite-engine.jar als Wurzel, alle anderen Jars rekursiv ueber den Klassenpfad: mit allen Jars als Argument
    # baut jdeps ein Modulgraph und bricht an Forges Abhaengigkeiten ab (jgrapht verlangt Modul org.jheaps).
    $found = (Invoke-Native $jdeps @('-R', '--class-path', '*', '--print-module-deps', '--ignore-missing-deps',
        '--multi-release', "$major", 'magelite-engine.jar') -NoStderr) -join ''
    if ($LASTEXITCODE -eq 0 -and $found.Trim()) {
        $modules += $found.Trim() -split ','
    } else {
        Write-Host 'WARNUNG: jdeps ohne Ergebnis - nutze nur die feste Modulliste' -ForegroundColor Yellow
    }
} finally { Pop-Location }
$modules += $extraModules
$modules = $modules | ForEach-Object { $_.Trim() } | Where-Object { $_ -and ($available -contains $_) } | Sort-Object -Unique
Write-Host "Module: $($modules -join ',')"

Write-Host '== Java-Laufzeit (jlink)'
if (Test-Path $jreOut) { Remove-Item -Recurse -Force $jreOut }
$compress = if ($major -ge 21) { 'zip-6' } else { '2' }
Invoke-Native $jlink @('--add-modules', ($modules -join ','), '--strip-debug', '--no-header-files', '--no-man-pages',
    "--compress=$compress", '--output', $jreOut) | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) { throw 'jlink fehlgeschlagen' }

Write-Host '== Smoke-Test: Engine mit der neuen Laufzeit starten'
# Fehlt der jlink-Laufzeit ein Modul, faellt das erst beim Start des Setups auf - deshalb hier einmal booten
# (Forge laedt alle Karten, Javalin/SQLite starten). Wie engine.cjs: Arbeitsverzeichnis = Datenordner.
$smokeJava = Join-Path $jreOut 'bin\java.exe'
$smokeRoot = Join-Path ([IO.Path]::GetTempPath()) ('magelite-smoke-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
$smokeData = Join-Path $smokeRoot 'data'
$smokeOut = Join-Path $smokeRoot 'stdout.txt'
$smokeErr = Join-Path $smokeRoot 'stderr.txt'
New-Item -ItemType Directory -Force $smokeData | Out-Null
$smokeArgs = '-Xmx3g -XX:+UseG1GC -Djava.awt.headless=true -Dfile.encoding=UTF-8 -cp "{0}\magelite-engine.jar;{0}\*" dev.magelite.Main --port=0 "--data={1}" "--forge={2}" --dev' -f $engineLib, $smokeData, $forgeDir
$smokeLimit = 180
$smokeReady = $null
$smokeProc = Start-Process -FilePath $smokeJava -ArgumentList $smokeArgs -WorkingDirectory $smokeData `
    -RedirectStandardOutput $smokeOut -RedirectStandardError $smokeErr -WindowStyle Hidden -PassThru
$null = $smokeProc.Handle   # Handle festhalten, sonst fehlt HasExited/ExitCode
$smokeClock = [Diagnostics.Stopwatch]::StartNew()
try {
    while ($smokeClock.Elapsed.TotalSeconds -lt $smokeLimit) {
        if (Test-Path $smokeOut) {
            $smokeReady = @(Get-Content $smokeOut -ErrorAction SilentlyContinue | Where-Object { "$_" -like 'MAGELITE_READY*' }) | Select-Object -First 1
        }
        if ($smokeReady -or $smokeProc.HasExited) { break }
        Start-Sleep -Milliseconds 500
    }
} finally {
    # Prozessbaum beenden (die Engine hat hier keinen --parent-pid-Waechter)
    if (-not $smokeProc.HasExited) { $null = cmd /c "taskkill /PID $($smokeProc.Id) /T /F 2>&1" }
    $null = $smokeProc.WaitForExit(15000)
}
if ($smokeReady) {
    Write-Host "Smoke OK: $smokeReady ($([math]::Round($smokeClock.Elapsed.TotalSeconds, 1)) s bis READY)" -ForegroundColor Green
    Start-Sleep -Milliseconds 500
    Remove-Item -Recurse -Force $smokeRoot -ErrorAction SilentlyContinue
} else {
    $why = if ($smokeClock.Elapsed.TotalSeconds -ge $smokeLimit) { "kein MAGELITE_READY nach $smokeLimit s" } else { "Engine beendet (Exit $($smokeProc.ExitCode)) ohne MAGELITE_READY" }
    Write-Host "Smoke FEHLGESCHLAGEN: $why" -ForegroundColor Red
    foreach ($f in @((Join-Path $smokeData 'logs\engine.log'), $smokeErr, $smokeOut)) {
        if ((Test-Path $f) -and (Get-Item $f).Length -gt 0) {
            Write-Host "--- letzte Zeilen: $f" -ForegroundColor Yellow
            Get-Content $f -Tail 40 -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "  $_" }
        }
    }
    Write-Host "Logs und Daten des Smoke-Starts: $smokeRoot (fehlt ein Java-Modul: NoClassDefFoundError -> `$extraModules ergaenzen)" -ForegroundColor Red
    exit 1
}

Write-Host '== Installer (electron-builder)'
# electron-builder ueberspringt fehlende extraResources nur mit Hinweis - hier hart pruefen
foreach ($p in @($engineLib, (Join-Path $jreOut 'bin\java.exe'), (Join-Path $root 'ui\dist\index.html'),
        $sourceTxt, (Join-Path $root 'LICENSE'), (Join-Path $root 'LICENSES'), $cards, (Join-Path $forgeDir 'manifest.json'))) {
    if (-not (Test-Path $p)) { throw "Fehlt: $p" }
}
Push-Location $desktop
try {
    Remove-Item Env:ELECTRON_RUN_AS_NODE -ErrorAction SilentlyContinue
    # build.ps1 installiert nur, wenn node_modules ganz fehlt - aeltere Klone haben electron-builder noch nicht
    if (-not (Test-Path 'node_modules\electron-builder')) {
        npm install
        if ($LASTEXITCODE -ne 0) { throw 'npm install (desktop) fehlgeschlagen' }
    }
    # Keine Code-Signatur: kein Zertifikat suchen
    $env:CSC_IDENTITY_AUTO_DISCOVERY = 'false'
    npm run dist
    if ($LASTEXITCODE -ne 0) { throw 'electron-builder fehlgeschlagen' }
} finally { Pop-Location }

$setup = Get-ChildItem (Join-Path $desktop 'dist') -Filter 'MageLite-Setup-*.exe' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
Write-Host ''
Write-Host "Fertig: $($setup.FullName) ($([math]::Round($setup.Length / 1MB)) MB)" -ForegroundColor Green
Write-Host 'Die EXE ist nicht signiert: Windows SmartScreen -> "Weitere Informationen" -> "Trotzdem ausfuehren".'
