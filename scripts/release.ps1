<#
  Neue Version bauen und installieren - ein Aufruf fuer alles:
    1. Version erhoehen (desktop/package.json = einzige Quelle; Engine, Installer und /api/health lesen sie)
    2. package.ps1: build.ps1 (danach startet MageLite.cmd den neuen Stand; holt bei Bedarf Forge per import-forge.ps1)
       + Java-Laufzeit + Smoke-Start der Engine (Forge-Boot) + Setup-EXE
    3. Setup still installieren (Benutzerdaten in %APPDATA%\MageLite bleiben) und installierte Version pruefen
    4. optional: Setup als GitHub-Release veroeffentlichen und auf fly.io deployen (-Fly)

  Aufruf:  powershell -ExecutionPolicy Bypass -File scripts\release.ps1 [-Bump patch|minor|major|none]
           [-NoInstall] [-Fly] [-Force] [-AllowDirty]
    -Bump        Versionsteil, der erhoeht wird (Standard patch; none = Version behalten)
    -NoInstall   nur bauen, nicht installieren
    -Fly         danach publish-setup.ps1 + deploy-fly.ps1 (braucht committeten Stand, ausser -AllowDirty; gh angemeldet)
    -Force       an deploy-fly.ps1 weitergeben (deployt auch, wenn gerade ein Spiel laeuft)
    -AllowDirty  uncommittete Aenderungen erlauben (auch fuer -Fly)
  Mit -Fly committet das Skript die 4 Versionsdateien (package.json/-lock.json in desktop und ui) selbst, damit
  der deployte Stand einem Commit entspricht (kein Push). Ohne -Fly committet es nichts (Hinweis am Ende).
#>
param(
    [ValidateSet('patch', 'minor', 'major', 'none')]
    [string]$Bump = 'patch',
    [switch]$NoInstall,
    [switch]$Fly,
    [switch]$Force,
    [switch]$AllowDirty
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$desktop = Join-Path $root 'desktop'
$ui = Join-Path $root 'ui'
# Versionsdateien: aendert das Skript selbst, mit -Fly committet es sie
$versionFiles = @('desktop/package.json', 'desktop/package-lock.json', 'ui/package.json', 'ui/package-lock.json')

function Step([string]$text) { Write-Host "== $text" -ForegroundColor Cyan }
function Fail([string]$text) { Write-Host $text -ForegroundColor Red; exit 1 }

# Startet ein Programm ueber cmd (stderr darf PowerShell 5.1 nicht abbrechen lassen) und gibt die Ausgabe zurueck
function Invoke-Cmd([string]$line) {
    $out = cmd /c "$line 2>&1"
    return @{ Code = $LASTEXITCODE; Out = ($out | ForEach-Object { "$_" }) }
}

function Get-AppVersion {
    return (Get-Content (Join-Path $desktop 'package.json') -Raw | ConvertFrom-Json).version
}

function Get-InstalledVersion {
    $keys = @(Get-ChildItem 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall' -ErrorAction SilentlyContinue)
    foreach ($k in $keys) {
        $p = Get-ItemProperty $k.PSPath -ErrorAction SilentlyContinue
        if ($p -and $p.DisplayName -like 'MageLite*') { return $p }
    }
    return $null
}

# ---------------------------------------------------------------- 0. Vorpruefungen
Step 'Vorpruefung'
Push-Location $root
try {
    # ungetrackte Dateien und die Versionsdateien (z. B. von einem abgebrochenen Lauf) zaehlen nicht
    $dirty = @((Invoke-Cmd 'git status --porcelain').Out | Where-Object {
        $_ -and $_ -notmatch '^\?\? ' -and ($versionFiles -notcontains $_.Substring(3).Trim()) })
} finally { Pop-Location }
if ($dirty.Count -gt 0) {
    if ($Fly -and -not $AllowDirty) {
        $dirty | Select-Object -First 10 | ForEach-Object { Write-Host "  $_" }
        Fail 'Uncommittete Aenderungen - fly wuerde einen nicht reproduzierbaren Stand deployen. Erst committen (oder -AllowDirty).'
    }
    Write-Host "Hinweis: $($dirty.Count) uncommittete Aenderung(en) - der Build enthaelt sie." -ForegroundColor Yellow
}

# Laufende App sperrt die Engine-Jars (installDist) bzw. die Installation
$running = @(Get-Process -Name 'MageLite' -ErrorAction SilentlyContinue)
$engines = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -and $_.CommandLine -like '*engine\build\install\magelite-engine*' })
if ($running.Count -gt 0 -or $engines.Count -gt 0) {
    Fail 'MageLite laeuft noch (installierte App oder MageLite.cmd). Bitte schliessen und das Skript erneut starten.'
}

# ---------------------------------------------------------------- 1. Version
$old = Get-AppVersion
if ($Bump -ne 'none') {
    Step "Version erhoehen ($Bump)"
    Push-Location $desktop
    try {
        $r = Invoke-Cmd "npm version $Bump --no-git-tag-version"
        if ($r.Code -ne 0) { $r.Out | ForEach-Object { Write-Host $_ }; Fail 'npm version (desktop) fehlgeschlagen' }
    } finally { Pop-Location }
}
$version = Get-AppVersion
Push-Location $ui
try {
    # UI-Version mitziehen (nur Ordnung; angezeigt wird die Engine-Version aus /api/health)
    $r = Invoke-Cmd "npm version $version --no-git-tag-version --allow-same-version"
    if ($r.Code -ne 0) { $r.Out | ForEach-Object { Write-Host $_ }; Fail 'npm version (ui) fehlgeschlagen' }
} finally { Pop-Location }
Write-Host "Version: $old -> $version"

# ---------------------------------------------------------------- 2. Bauen + Setup
Step 'Bauen und Setup erzeugen (package.ps1)'
& (Join-Path $PSScriptRoot 'package.ps1')
if (-not $?) { Fail 'package.ps1 fehlgeschlagen' }
$setup = Join-Path $desktop "dist\MageLite-Setup-$version.exe"
if (-not (Test-Path $setup)) { Fail "Setup fehlt: $setup" }

# alte Setups aufraeumen (je ~205 MB), die neuesten 2 bleiben
Get-ChildItem (Join-Path $desktop 'dist') -Filter 'MageLite-Setup-*.exe' | Sort-Object LastWriteTime -Descending |
    Select-Object -Skip 2 | ForEach-Object {
        Write-Host "Entferne altes Setup: $($_.Name)"
        Remove-Item $_.FullName -Force
        $map = "$($_.FullName).blockmap"
        if (Test-Path $map) { Remove-Item $map -Force }
    }

# ---------------------------------------------------------------- 3. Installieren
$installed = $null
if (-not $NoInstall) {
    Step "Installieren ($([IO.Path]::GetFileName($setup)), still)"
    $p = Start-Process -FilePath $setup -ArgumentList '/S' -Wait -PassThru
    if ($p.ExitCode -ne 0) { Fail "Setup endete mit Exit $($p.ExitCode)" }
    $installed = Get-InstalledVersion
    if (-not $installed) { Fail 'Installation nicht gefunden (Registry HKCU Uninstall)' }
    if ($installed.DisplayVersion -ne $version) {
        Fail "Installiert ist $($installed.DisplayVersion), erwartet $version"
    }
    $where = $installed.InstallLocation
    if (-not $where -and $installed.DisplayIcon) { $where = Split-Path -Parent (($installed.DisplayIcon -split ',')[0].Trim('"')) }
    Write-Host "Installiert: MageLite $($installed.DisplayVersion) in $where" -ForegroundColor Green
}

# ---------------------------------------------------------------- 4. fly.io
$committed = $false
if ($Fly) {
    # Versionserhoehung committen (nur diese Dateien), sonst bricht deploy-fly.ps1 wegen uncommitteter Aenderungen ab
    Push-Location $root
    try {
        $changed = @((Invoke-Cmd ("git status --porcelain -- " + ($versionFiles -join ' '))).Out | Where-Object { $_ })
        if ($changed.Count -gt 0) {
            Step "Versionsdateien committen (Version $version)"
            $r = Invoke-Cmd ("git add -- " + ($versionFiles -join ' '))
            if ($r.Code -ne 0) { $r.Out | ForEach-Object { Write-Host $_ }; Fail 'git add fehlgeschlagen' }
            $r = Invoke-Cmd ("git commit -m `"Version $version`" -- " + ($versionFiles -join ' '))
            if ($r.Code -ne 0) { $r.Out | ForEach-Object { Write-Host $_ }; Fail 'git commit fehlgeschlagen' }
            $committed = $true
        }
    } finally { Pop-Location }

    # Setup vor dem Deploy veroeffentlichen: die neue Version verlinkt sofort auf ihr eigenes Setup (GitHub-Release)
    Step 'Setup veroeffentlichen (publish-setup.ps1)'
    & (Join-Path $PSScriptRoot 'publish-setup.ps1') -Setup $setup
    if (-not $?) { Fail 'publish-setup.ps1 fehlgeschlagen' }

    Step 'Deploy auf fly.io'
    $flyArgs = @{}
    if ($Force) { $flyArgs.Force = $true }
    if ($AllowDirty) { $flyArgs.AllowDirty = $true }
    & (Join-Path $PSScriptRoot 'deploy-fly.ps1') @flyArgs
    if (-not $?) { Fail 'deploy-fly.ps1 fehlgeschlagen' }

}

# ---------------------------------------------------------------- Zusammenfassung
Write-Host ''
Write-Host "Fertig: MageLite $version" -ForegroundColor Green
Write-Host "  Setup:        $setup"
Write-Host '  Lokal (Repo): MageLite.cmd startet jetzt diesen Stand'
if ($installed) { Write-Host '  Installiert:  Startmenue / Desktop-Verknuepfung "MageLite"' }
if ($committed) {
    Write-Host "  Commit:       `"Version $version`" (nicht gepusht: git push)"
} elseif ($Bump -ne 'none') {
    Write-Host ''
    Write-Host 'Versionsdateien committen:'
    Write-Host "  git add desktop/package.json desktop/package-lock.json ui/package.json ui/package-lock.json"
    Write-Host "  git commit -m `"Version $version`""
}
