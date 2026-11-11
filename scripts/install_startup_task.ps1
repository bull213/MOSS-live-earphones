$ErrorActionPreference = 'Stop'

$startupFolder = [Environment]::GetFolderPath('Startup')
$shortcutPath = Join-Path $startupFolder 'Start MOSS Backend.lnk'
$targetScript = Join-Path $PSScriptRoot 'start_backend_silently.vbs'

$wshShell = New-Object -ComObject WScript.Shell
$shortcut = $wshShell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = "wscript.exe"
$shortcut.Arguments = "`"$targetScript`""
$shortcut.WorkingDirectory = $PSScriptRoot
$shortcut.Description = "Starts the MOSS Visual Assistant backend silently in the background at logon."
$shortcut.Save()

Write-Host "Success! The backend will now start silently in the background whenever you log into Windows." -ForegroundColor Green
Write-Host "Startup shortcut created at: $shortcutPath" -ForegroundColor Cyan
