# Laedt das Windows-Setup auf das fly-Volume (/data/downloads), damit die Startseite es zum Download anbietet
# (Engine: GET /api/download/info, /api/download/file). Aeltere Setups auf dem Volume werden entfernt.
# Aufruf: powershell -ExecutionPolicy Bypass -File scripts\upload-setup.ps1 -Setup desktop\dist\MageLite-Setup-0.1.6.exe [-App magelite]
# Wird von release.ps1 -Fly nach dem Deploy aufgerufen.
param(
    [Parameter(Mandatory = $true)][string]$Setup,
    [string]$App = ""
)
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

if (-not (Test-Path $Setup)) { Write-Error "Setup nicht gefunden: $Setup" }
$file = Split-Path -Leaf $Setup
if ($file -notmatch '^MageLite-Setup-([0-9][0-9A-Za-z.\-]*)\.exe$') { Write-Error "Dateiname muss MageLite-Setup-<version>.exe sein: $file" }
$version = $Matches[1]

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
if (-not $fly) { Write-Error "flyctl nicht gefunden. Installieren: winget install Fly-io.flyctl (dann 'fly auth login')." }

if ($App -eq "") {
    $line = Get-Content fly.toml | Where-Object { $_ -match '^\s*app\s*=' } | Select-Object -First 1
    if ($line -match '"([^"]+)"') { $App = $Matches[1] }
}
if ($App -eq "") { Write-Error "App-Name nicht gefunden (fly.toml)." }

# Maschine wecken (Auto-Stop), sonst scheitert ssh
$base = "https://$App.fly.dev"
try { $null = Invoke-RestMethod -Uri "$base/api/health" -TimeoutSec 60 } catch { Write-Host "Health nicht erreichbar ($($_.Exception.Message)) - versuche trotzdem." }

$size = [math]::Round((Get-Item $Setup).Length / 1MB)
Write-Host "Lade $file ($size MB) nach /data/downloads auf $App ..."
& $fly ssh sftp put $Setup "/data/downloads/$file" -a $App
if ($LASTEXITCODE -ne 0) { Write-Error "Upload fehlgeschlagen (Exit $LASTEXITCODE)." }

# alte Setups entfernen (nur die neueste Datei bleibt)
& $fly ssh console -a $App -C "sh -c 'cd /data/downloads && ls -t MageLite-Setup-*.exe | tail -n +2 | xargs -r rm -f; ls -la /data/downloads'"
if ($LASTEXITCODE -ne 0) { Write-Host "Aufraeumen alter Setups fehlgeschlagen (Exit $LASTEXITCODE) - nicht kritisch." }

# Pruefen, was die Engine anbietet (Cache 60 s)
$info = $null
for ($i = 0; $i -lt 8; $i++) {
    try {
        $info = Invoke-RestMethod -Uri "$base/api/download/info" -TimeoutSec 30
        if ($info.available -and $info.version -eq $version) { break }
    } catch { }
    Start-Sleep -Seconds 10
}
if ($info -and $info.available -and $info.version -eq $version) {
    Write-Host ("Download bereit: {0}/api/download/file (v{1}, {2} MB)" -f $base, $info.version, [math]::Round($info.bytes / 1MB)) -ForegroundColor Green
} else {
    Write-Error ("Server bietet nicht die erwartete Version an (erwartet {0}, gemeldet {1})." -f $version, $(if ($info) { $info.version } else { 'nichts' }))
}
