# Creates the Lumen Desk desktop icon and starts the server with Windows.
# Run: right-click -> Run with PowerShell (or: powershell -ExecutionPolicy Bypass -File install.ps1)
$dir = Split-Path -Parent $MyInvocation.MyCommand.Path
$vbs = Join-Path $dir 'Lumen Desk.vbs'
$shell = New-Object -ComObject WScript.Shell

function New-Link($path, $arguments) {
    $l = $shell.CreateShortcut($path)
    $l.TargetPath = "$env:WINDIR\System32\wscript.exe"
    $l.Arguments = "`"$vbs`" $arguments"
    $l.WorkingDirectory = $dir
    $l.IconLocation = Join-Path $dir 'icon.ico'
    $l.Save()
}

New-Link (Join-Path ([Environment]::GetFolderPath('Desktop')) 'Lumen Desk.lnk') ''
New-Link (Join-Path ([Environment]::GetFolderPath('Startup')) 'Lumen Desk server.lnk') '/server'
Write-Host 'Done: desktop icon + start with Windows.'
Write-Host 'To remove: delete "Lumen Desk server" in the shell:startup folder.'
