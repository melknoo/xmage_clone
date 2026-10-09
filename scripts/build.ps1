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
# java -version schreibt nach stderr; ueber cmd umleiten, sonst bricht PowerShell 5.1 ab
$javaVersion = [string](cmd /c "java -version 2>&1" | Select-Object -First 1)
Write-Host "Java: $javaVersion"
if ($javaVersion -match 'version "(1\.)?(\d+)') {
    if ([int]$Matches[2] -lt 17) {
        Write-Host 'Java 17 oder neuer wird benoetigt (winget install EclipseAdoptium.Temurin.21.JDK)' -ForegroundColor Red
        exit 1
    }
}
Write-Host "Node: $(& node --version)"

# Forge neu importieren, wenn er fehlt oder nicht zum gepinnten Commit passt (vendor/forge/FORGE_COMMIT)
$forgeOk = $false
$manifest = Join-Path $root 'vendor\forge\manifest.json'
if ((Test-Path $manifest) -and (Test-Path (Join-Path $root 'vendor\forge\res\cardsfolder\cardsfolder.zip'))) {
    $want = (Get-Content (Join-Path $root 'vendor\forge\FORGE_COMMIT') -Raw).Trim()
    $have = (Get-Content $manifest -Raw | ConvertFrom-Json).commit
    $forgeOk = ($have -eq $want)
    if (-not $forgeOk) { Write-Host "Forge-Stand $have passt nicht zu FORGE_COMMIT $want" }
}
if (-not $forgeOk) {
    Write-Host '== Forge bauen und importieren (braucht Git und JDK 17+, Maven wird selbst geladen; erster Lauf einige Minuten)'
    & (Join-Path $PSScriptRoot 'import-forge.ps1')
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
