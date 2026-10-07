# Deploy auf fly.io. Bricht ab, wenn gerade ein Spiel laeuft (ausser -Force) oder uncommittete Aenderungen
# vorliegen (ausser -AllowDirty): fly deploy laedt das Arbeitsverzeichnis hoch, der Live-Stand muss aus einem
# Commit reproduzierbar sein.
# Aufruf: powershell -ExecutionPolicy Bypass -File scripts\deploy-fly.ps1 [-Force] [-AllowDirty] [-App <name>]
param(
    [switch]$Force,
    [switch]$AllowDirty,
    [string]$App = ""
)
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

# flyctl finden: PATH, sonst winget-Paket, sonst Installer-Skript (~\.fly\bin)
$fly = $null
foreach ($name in @("fly", "flyctl")) {
    $c = Get-Command $name -ErrorAction SilentlyContinue
    if ($c) { $fly = $c.Source; break }
}
if (-not $fly) {
    $cands = @()
    $winget = Join-Path $env:LOCALAPPDATA "Microsoft\WinGet\Packages"
    if (Test-Path $winget) {
        $cands += @(Get-ChildItem $winget -Directory -Filter "Fly-io.flyctl*" -ErrorAction SilentlyContinue |
            ForEach-Object { Join-Path $_.FullName "flyctl.exe" })
    }
    $cands += Join-Path $env:USERPROFILE ".fly\bin\flyctl.exe"
    $fly = $cands | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $fly) {
    Write-Error "flyctl nicht gefunden. Installieren: winget install Fly-io.flyctl (dann 'fly auth login')."
}
Write-Host "flyctl: $fly"

if (-not $AllowDirty) {
    $dirty = @(cmd /c "git status --porcelain 2>&1" | Where-Object { $_ -and $_ -notmatch '^\?\? ' })
    if ($dirty.Count -gt 0) {
        $dirty | Select-Object -First 10 | ForEach-Object { Write-Host "  $_" }
        Write-Error "Uncommittete Aenderungen ($($dirty.Count)). Erst committen oder -AllowDirty."
    }
}

if ($App -eq "") {
    $line = Get-Content fly.toml | Where-Object { $_ -match '^\s*app\s*=' } | Select-Object -First 1
    if ($line -match '"([^"]+)"') { $App = $Matches[1] }
}
if ($App -eq "") { Write-Error "App-Name nicht gefunden (fly.toml)." }

$url = "https://$App.fly.dev/api/health"
try {
    $health = Invoke-RestMethod -Uri $url -TimeoutSec 60
    Write-Host ("Server: Version {0}, Modus {1}, laufende Spiele: {2}" -f $health.version, $health.mode, $health.games)
    if ($health.games -gt 0 -and -not $Force) {
        Write-Error "Es laeuft gerade ein Spiel. Deploy wuerde es abbrechen. Mit -Force trotzdem deployen."
    }
} catch {
    if ($_.Exception.Message -match "laeuft gerade") { throw }
    Write-Host "Health-Check nicht erreichbar ($($_.Exception.Message)) - Maschine vermutlich gestoppt, deploye."
}

$want = (Get-Content (Join-Path "desktop" "package.json") -Raw | ConvertFrom-Json).version
# --ha=false: genau eine Maschine (Standard waere 2 fuer Hochverfuegbarkeit = doppelte Kosten, zwei getrennte DBs)
& $fly deploy --app $App --ha=false
if ($LASTEXITCODE -ne 0) { Write-Error "fly deploy fehlgeschlagen (Exit $LASTEXITCODE)." }

# Neue Version abwarten (Start inkl. Karten-DB dauert einige Sekunden)
$live = $null
for ($i = 0; $i -lt 24; $i++) {
    try {
        $h = Invoke-RestMethod -Uri $url -TimeoutSec 20
        $live = $h.version
        if ($live -eq $want) { break }
    } catch { }
    Start-Sleep -Seconds 5
}
if ($live -eq $want) {
    Write-Host "Fertig: https://$App.fly.dev (Version $live)" -ForegroundColor Green
} else {
    Write-Host "Deploy durch, aber Health meldet Version '$live' statt $want - bitte pruefen (fly logs)." -ForegroundColor Yellow
}
