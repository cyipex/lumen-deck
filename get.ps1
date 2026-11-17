# Lumen Desk – install on Windows with one command (PowerShell):
#
#   irm https://raw.githubusercontent.com/cyipex/lumen-desk/main/get.ps1 | iex
#
# Downloads the installer of the latest release from GitHub, checks it against the release's SHA256SUMS.txt
# and starts it. Nothing else is changed; uninstall any time in Windows Settings → Apps.
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$base = 'https://github.com/cyipex/lumen-desk/releases/latest/download'
$file = Join-Path $env:TEMP 'LumenDeskSetup.exe'

Write-Host 'Downloading Lumen Desk...'
Invoke-WebRequest "$base/LumenDeskSetup.exe" -OutFile $file -UseBasicParsing

# The installer must match the checksum published with the release.
try {
  $sums = (Invoke-WebRequest "$base/SHA256SUMS.txt" -UseBasicParsing).Content
  # GitHub serves release files as binary, so the text may arrive as bytes.
  if ($sums -is [byte[]]) { $sums = [Text.Encoding]::UTF8.GetString($sums) }
  $want = ($sums -split "`n" | Where-Object { $_ -match 'LumenDeskSetup\.exe' } | ForEach-Object { ($_ -split '\s+')[0] }) | Select-Object -First 1
  $have = (Get-FileHash $file -Algorithm SHA256).Hash
  if (-not $want -or $want.ToLower() -ne $have.ToLower()) {
    Remove-Item $file -ErrorAction SilentlyContinue
    throw 'The download does not match the published checksum. Nothing was installed.'
  }
  Write-Host 'Checksum OK.'
} catch [System.Net.WebException] {
  Write-Warning 'This release has no checksum file; the installer could not be checked.'
}

Write-Host 'Starting the installer...'
Start-Process $file -Wait
Remove-Item $file -ErrorAction SilentlyContinue

Write-Host ''
Write-Host 'Done. Lumen Desk runs in the background and starts with Windows.'
Write-Host 'On the tablet, open this page in the browser and install the app:'
Write-Host '  https://github.com/cyipex/lumen-desk/releases/latest/download/lumen-desk.apk'
