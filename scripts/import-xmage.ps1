<#
  Kopiert die benoetigten Teile einer XMage-Distribution nach vendor\xmage\.
  Aufruf: powershell -ExecutionPolicy Bypass -File scripts\import-xmage.ps1 [-XmageDir <pfad>]
#>
param(
    [string]$XmageDir = "$env:USERPROFILE\Downloads\mage-full_1.4.60-dev_2026-07-11_16-06\xmage"
)
$ErrorActionPreference = 'Stop'

$root   = Split-Path -Parent $PSScriptRoot
$vendor = Join-Path $root 'vendor\xmage'
$server = Join-Path $XmageDir 'mage-server'
$client = Join-Path $XmageDir 'mage-client'

if (-not (Test-Path (Join-Path $server 'lib'))) { throw "Kein XMage-Server unter $server gefunden" }

$libDir   = Join-Path $vendor 'lib'
$toolsDir = Join-Path $vendor 'tools-lib'
foreach ($d in @($libDir, $toolsDir, (Join-Path $vendor 'db'), (Join-Path $vendor 'sample-decks'), (Join-Path $vendor 'sounds'))) {
    New-Item -ItemType Directory -Force $d | Out-Null
}

# Jars die NICHT gebraucht werden (Netzwerk, Server, Auth, Mail, ...)
$excludePatterns = @(
    'mage-server-*', 'jboss-*', 'shiro-*', 'jersey-*', 'jsr311-*', 'mail-*', 'mimepull-*', 'activation-*',
    'jaxb-*', 'jakarta.*', 'jspf-*', 'prettytime-*', 'sqlite-jdbc-*', 'concurrent-*',
    'mage-tournament-*', 'mage-player-ai-draftbot-*', 'mage-deck-limited-*'
)
# Von den Spieltypen nur Commander FFA
$gameKeep = @('mage-game-commanderfreeforall-*')

function Test-Excluded([string]$name) {
    foreach ($p in $excludePatterns) { if ($name -like $p) { return $true } }
    if ($name -like 'mage-game-*') {
        foreach ($k in $gameKeep) { if ($name -like $k) { return $false } }
        return $true
    }
    return $false
}

$jars = @(Get-ChildItem (Join-Path $server 'lib') -Filter *.jar) + @(Get-ChildItem (Join-Path $server 'plugins') -Filter *.jar)
$copied = @()
foreach ($j in $jars) {
    if (Test-Excluded $j.Name) { continue }
    Copy-Item $j.FullName (Join-Path $libDir $j.Name) -Force
    $copied += $j.Name
}
Write-Host "Jars kopiert: $($copied.Count)"

# Client-Jar nur fuer Build-Tools (Scryfall-Mappings), nicht zur Laufzeit
Copy-Item (Join-Path $client 'lib\mage-client-*.jar') $toolsDir -Force

# Karten-DB (nur .mv.db; Lock-Datei NICHT kopieren)
$javaRunning = Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.ProcessName -match '^javaw?$' }
if ($javaRunning) { Write-Warning 'Es laufen Java-Prozesse - falls der XMage-Server laeuft, bitte vorher beenden.' }
Copy-Item (Join-Path $server 'db\cards.h2.mv.db') (Join-Path $vendor 'db\cards.h2.mv.db') -Force
Write-Host 'Karten-DB kopiert'

# Sample-Commander-Decks + Sounds
Copy-Item (Join-Path $client 'sample-decks\Commander\*') (Join-Path $vendor 'sample-decks') -Recurse -Force
Copy-Item (Join-Path $client 'sounds\*') (Join-Path $vendor 'sounds') -Recurse -Force
Copy-Item (Join-Path $server 'LICENSE.txt') (Join-Path $vendor 'LICENSE-XMage.txt') -Force

# Manifest mit Hashes der Kern-Jars (fuer DB-Marker)
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Get-BuildTime([string]$jarPath) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
    try {
        $entry = $zip.GetEntry('META-INF/MANIFEST.MF')
        $reader = New-Object System.IO.StreamReader($entry.Open())
        $text = $reader.ReadToEnd(); $reader.Close()
        if ($text -match 'Build-Time:\s*(\S+)') { return $Matches[1] }
        return $null
    } finally { $zip.Dispose() }
}
$core = Get-ChildItem $libDir -Filter 'mage-1*.jar' | Select-Object -First 1
$sets = Get-ChildItem $libDir -Filter 'mage-sets-*.jar' | Select-Object -First 1
$manifest = [ordered]@{
    source    = (Split-Path -Leaf (Split-Path -Parent $XmageDir))
    importedAt = (Get-Date).ToString('o')
    buildTime = Get-BuildTime $core.FullName
    coreJar   = @{ name = $core.Name; sha256 = (Get-FileHash $core.FullName -Algorithm SHA256).Hash }
    setsJar   = @{ name = $sets.Name; sha256 = (Get-FileHash $sets.FullName -Algorithm SHA256).Hash }
    jars      = $copied
}
$manifest | ConvertTo-Json -Depth 4 | Out-File (Join-Path $vendor 'manifest.json') -Encoding utf8
Write-Host "Fertig: $vendor"
