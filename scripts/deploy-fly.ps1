# Deploy auf fly.io. Bricht ab, wenn gerade ein Spiel laeuft (ausser -Force).
# Aufruf: powershell -ExecutionPolicy Bypass -File scripts\deploy-fly.ps1 [-Force] [-App <name>]
param(
    [switch]$Force,
    [string]$App = ""
)
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

if (-not (Get-Command fly -ErrorAction SilentlyContinue)) {
    Write-Error "flyctl nicht gefunden. Installieren: winget install Fly-io.flyctl (dann 'fly auth login')."
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

# --ha=false: genau eine Maschine (Standard waere 2 fuer Hochverfuegbarkeit = doppelte Kosten, zwei getrennte DBs)
fly deploy --app $App --ha=false
if ($LASTEXITCODE -ne 0) { Write-Error "fly deploy fehlgeschlagen (Exit $LASTEXITCODE)." }
Write-Host "Fertig: https://$App.fly.dev"
