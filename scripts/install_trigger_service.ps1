$ErrorActionPreference = 'Stop'

$startupFolder = [Environment]::GetFolderPath('Startup')
$shortcutPath = Join-Path $startupFolder 'MOSS Live Phone Trigger Listener.lnk'
$targetScript = Join-Path $PSScriptRoot 'start_trigger_listener.vbs'

$wshShell = New-Object -ComObject WScript.Shell
$shortcut = $wshShell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = "wscript.exe"
$shortcut.Arguments = "`"$targetScript`""
$shortcut.WorkingDirectory = $PSScriptRoot
$shortcut.Description = "Listens for phone app launch and opens Start MOSS Live automatically on PC."
$shortcut.Save()

Write-Host "Success! The Phone Trigger Listener will start silently whenever you log into Windows." -ForegroundColor Green
Write-Host "Startup shortcut created at: $shortcutPath" -ForegroundColor Cyan
