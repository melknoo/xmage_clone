<#
  Erzeugt den Gradle-Wrapper in engine\ (einmalig). Laedt Gradle temporaer nach %TEMP%.
#>
param([string]$Version = '9.1.0')
$ErrorActionPreference = 'Stop'

$root   = Split-Path -Parent $PSScriptRoot
$engine = Join-Path $root 'engine'
$tmp    = Join-Path $env:TEMP "magelite-gradle-$Version"
$zip    = "$tmp.zip"

if (-not (Test-Path (Join-Path $tmp "gradle-$Version\bin\gradle.bat"))) {
    Write-Host "Lade Gradle $Version ..."
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest "https://services.gradle.org/distributions/gradle-$Version-bin.zip" -OutFile $zip -UseBasicParsing
    Expand-Archive $zip -DestinationPath $tmp -Force
    Remove-Item $zip
}

Push-Location $engine
try {
    & (Join-Path $tmp "gradle-$Version\bin\gradle.bat") wrapper --gradle-version $Version --distribution-type bin --no-daemon
} finally { Pop-Location }
Write-Host 'Gradle-Wrapper erstellt.'
