# Veroeffentlicht das Windows-Setup als Release-Asset im oeffentlichen Repo (Standard melknoo/magelite-releases).
# Die Startseite verlinkt es ueber MAGELITE_DOWNLOAD_URL (fly.toml) - die Datei liegt so nicht auf fly
# (kein Egress, kein Volume-Platz, die Maschine muss fuer Downloads nicht laufen).
# Voraussetzung: GitHub CLI (winget install GitHub.cli) und einmal 'gh auth login'.
# Aufruf: powershell -ExecutionPolicy Bypass -File scripts\publish-setup.ps1 -Setup desktop\dist\MageLite-Setup-0.1.7.exe [-Repo owner/name]
# Wird von release.ps1 -Fly vor dem Deploy aufgerufen.
param(
    [Parameter(Mandatory = $true)][string]$Setup,
    [string]$Repo = "melknoo/magelite-releases"
)
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

if (-not (Test-Path $Setup)) { Write-Error "Setup nicht gefunden: $Setup" }
$file = Split-Path -Leaf $Setup
if ($file -notmatch '^MageLite-Setup-([0-9][0-9A-Za-z.\-]*)\.exe$') { Write-Error "Dateiname muss MageLite-Setup-<version>.exe sein: $file" }
$version = $Matches[1]
$tag = "v$version"
$full = (Resolve-Path $Setup).Path

# gh finden: PATH, sonst Standard-Installationsorte
$gh = $null
$c = Get-Command gh -ErrorAction SilentlyContinue
if ($c) { $gh = $c.Source }
if (-not $gh) {
    $cands = @(
        (Join-Path $env:ProgramFiles "GitHub CLI\gh.exe"),
        (Join-Path $env:LOCALAPPDATA "Programs\GitHub CLI\gh.exe")
    )
    $gh = $cands | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $gh) { Write-Error "GitHub CLI nicht gefunden. Installieren: winget install GitHub.cli (dann 'gh auth login')." }

# stderr von gh ueber cmd umleiten (PowerShell 5.1 bricht sonst ab)
function Invoke-Gh([string]$argLine) {
    $out = cmd /c "`"$gh`" $argLine 2>&1"
    return @{ Code = $LASTEXITCODE; Out = ($out | ForEach-Object { "$_" }) }
}

$r = Invoke-Gh "auth status"
if ($r.Code -ne 0) { $r.Out | ForEach-Object { Write-Host $_ }; Write-Error "gh ist nicht angemeldet: 'gh auth login' ausfuehren." }

# Releases-Repo beim ersten Mal anlegen (oeffentlich, mit README - ein leeres Repo kann keine Releases haben)
$r = Invoke-Gh "repo view $Repo"
if ($r.Code -ne 0) {
    Write-Host "Lege oeffentliches Repo $Repo an ..."
    $r = Invoke-Gh "repo create $Repo --public --add-readme --description `"MageLite Windows-Setup (Downloads)`""
    if ($r.Code -ne 0) { $r.Out | ForEach-Object { Write-Host $_ }; Write-Error "Repo $Repo konnte nicht angelegt werden." }
}

$size = [math]::Round((Get-Item $full).Length / 1MB)
$r = Invoke-Gh "release view $tag -R $Repo"
if ($r.Code -eq 0) {
    Write-Host "Release $tag existiert - ersetze das Setup ($size MB) ..."
    $r = Invoke-Gh "release upload $tag `"$full`" -R $Repo --clobber"
} else {
    Write-Host "Lege Release $tag in $Repo an und lade das Setup hoch ($size MB) ..."
    $r = Invoke-Gh "release create $tag `"$full`" -R $Repo --title `"MageLite $version`" --notes `"MageLite $version fuer Windows. Setup ausfuehren, danach in der App 'Online spielen'.`""
}
if ($r.Code -ne 0) { $r.Out | ForEach-Object { Write-Host $_ }; Write-Error "Upload fehlgeschlagen." }

# Pruefen, ob der oeffentliche Link funktioniert (wie ihn die Startseite baut)
$url = "https://github.com/$Repo/releases/download/$tag/$file"
try {
    $resp = Invoke-WebRequest -Uri $url -Method Head -UseBasicParsing -TimeoutSec 60
    Write-Host "Setup oeffentlich erreichbar: $url (HTTP $($resp.StatusCode))" -ForegroundColor Green
} catch {
    Write-Error "Setup-Link nicht erreichbar ($url): $($_.Exception.Message). Ist das Repo oeffentlich?"
}
