<#
  Baut den Windows-Installer: desktop\dist\MageLite-Setup-<version>.exe
  Enthaelt Electron, UI, Engine, XMage-Dateien und eine per jlink erzeugte Java-Laufzeit.
  Auf dem Ziel-PC muss nichts vorinstalliert sein.
  Voraussetzung zum Bauen: JDK 17+ (mit jlink/jdeps) und Node.js.
#>
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$desktop = Join-Path $root 'desktop'
$engineLib = Join-Path $root 'engine\build\install\magelite-engine\lib'
$jreOut = Join-Path $desktop 'out\jre'

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
    $jars = @(Get-ChildItem -Filter *.jar | ForEach-Object { $_.Name })
    $found = (Invoke-Native $jdeps (@('--print-module-deps', '--ignore-missing-deps', '--multi-release', "$major") + $jars) -NoStderr) -join ''
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

Write-Host '== Installer (electron-builder)'
# electron-builder ueberspringt fehlende extraResources nur mit Hinweis - hier hart pruefen
foreach ($p in @($engineLib, (Join-Path $jreOut 'bin\java.exe'), (Join-Path $root 'ui\dist\index.html'))) {
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
