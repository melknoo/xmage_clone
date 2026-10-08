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
$expected = (Get-Item $Setup).Length

# Waehrend des Uploads die Engine wach halten: ohne API-Anfragen beendet sie sich nach 10 min (Leerlauf-Exit),
# die Maschine stoppt und die ssh-Verbindung reisst ("connection lost"). /api/health zaehlt nicht als Aktivitaet.
$keep = Start-Job -ArgumentList $base {
    param($b)
    while ($true) {
        try { $null = Invoke-RestMethod -Uri "$b/api/download/info" -TimeoutSec 20 } catch { }
        Start-Sleep -Seconds 45
    }
}
try {
    # Upload auf .part (die Engine bietet nur *.exe an), danach umbenennen; bis zu 3 Versuche
    $done = $false
    for ($attempt = 1; $attempt -le 3 -and -not $done; $attempt++) {
        Write-Host "Lade $file ($size MB) nach /data/downloads auf $App (Versuch $attempt) ..."
        & $fly ssh sftp put $Setup "/data/downloads/$file.part" -a $App
        if ($LASTEXITCODE -ne 0) { Write-Host "Upload abgebrochen (Exit $LASTEXITCODE)."; Start-Sleep -Seconds 10; continue }
        # stderr ("Connecting to ...") ueber cmd umleiten: in PowerShell 5.1 bricht 2>&1 mit ErrorActionPreference=Stop ab
        $check = (cmd /c "`"$fly`" ssh console -a $App -C `"sh -c 'stat -c %s /data/downloads/$file.part'`" 2>&1" |
            Where-Object { $_ -match '^\d+\s*$' } | Select-Object -Last 1)
        if ("$check".Trim() -ne "$expected") { Write-Host "Groesse stimmt nicht ($check statt $expected)."; continue }
        cmd /c "`"$fly`" ssh console -a $App -C `"sh -c 'mv -f /data/downloads/$file.part /data/downloads/$file'`" 2>&1" | Out-Null
        if ($LASTEXITCODE -eq 0) { $done = $true }
    }
    if (-not $done) { Write-Error "Upload nach 3 Versuchen fehlgeschlagen." }

    # alte Setups und Reste entfernen (nur die neueste Datei bleibt)
    cmd /c "`"$fly`" ssh console -a $App -C `"sh -c 'cd /data/downloads && rm -f *.part; ls -t MageLite-Setup-*.exe | tail -n +2 | xargs -r rm -f; ls -la /data/downloads'`" 2>&1"
    if ($LASTEXITCODE -ne 0) { Write-Host "Aufraeumen alter Setups fehlgeschlagen (Exit $LASTEXITCODE) - nicht kritisch." }
} finally {
    Stop-Job $keep -ErrorAction SilentlyContinue
    Remove-Job $keep -Force -ErrorAction SilentlyContinue
}

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
