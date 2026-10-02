<#
  Baut alles fuer den lokalen Start: UI (Vite), Engine (Gradle installDist), Electron-Abhaengigkeiten.
  Danach: MageLite.cmd doppelklicken.
#>
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

function Require([string]$cmd, [string]$hint) {
    if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) {
        Write-Host "FEHLT: $cmd - $hint" -ForegroundColor Red
        exit 1
    }
}

Write-Host '== Voraussetzungen'
Require 'java' 'Java 17 oder neuer installieren, z.B.: winget install EclipseAdoptium.Temurin.21.JDK'
Require 'npm' 'Node.js installieren, z.B.: winget install OpenJS.NodeJS.LTS'
$javaVersion = (& java -version 2>&1 | Select-Object -First 1).ToString()
Write-Host "Java: $javaVersion"
if ($javaVersion -match 'version "(1\.)?(\d+)') {
    if ([int]$Matches[2] -lt 17) {
        Write-Host 'Java 17 oder neuer wird benoetigt (winget install EclipseAdoptium.Temurin.21.JDK)' -ForegroundColor Red
        exit 1
    }
}
Write-Host "Node: $(& node --version)"

if (-not (Test-Path (Join-Path $root 'vendor\xmage\lib'))) {
    Write-Host '== XMage-Dateien importieren'
    & (Join-Path $PSScriptRoot 'import-xmage.ps1')
}

Write-Host '== UI'
Push-Location (Join-Path $root 'ui')
try {
    if (-not (Test-Path node_modules)) { npm install; if ($LASTEXITCODE -ne 0) { throw 'npm install (ui) fehlgeschlagen' } }
    npm run build
    if ($LASTEXITCODE -ne 0) { throw 'UI-Build fehlgeschlagen' }
} finally { Pop-Location }

Write-Host '== Engine (beim ersten Mal wird Gradle heruntergeladen)'
Push-Location (Join-Path $root 'engine')
try {
    .\gradlew.bat installDist --no-daemon -q
    if ($LASTEXITCODE -ne 0) { throw 'Engine-Build fehlgeschlagen' }
} finally { Pop-Location }

Write-Host '== Desktop'
Push-Location (Join-Path $root 'desktop')
try {
    if (-not (Test-Path node_modules)) {
        # VS Code setzt diese Variable - sie stoert die Electron-Installation nicht, aber den Start
        Remove-Item Env:ELECTRON_RUN_AS_NODE -ErrorAction SilentlyContinue
        npm install
        if ($LASTEXITCODE -ne 0) { throw 'npm install (desktop) fehlgeschlagen' }
    }
} finally { Pop-Location }

Write-Host ''
Write-Host 'Fertig. Start: MageLite.cmd (Doppelklick)' -ForegroundColor Green
Write-Host 'Der allererste Start baut die Kartendatenbank auf und dauert 1-2 Minuten.'
