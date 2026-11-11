$ErrorActionPreference = 'SilentlyContinue'

$startupFolder = [Environment]::GetFolderPath('Startup')
$shortcutPath = Join-Path $startupFolder 'Start MOSS Backend.lnk'

if (Test-Path -LiteralPath $shortcutPath) {
    Remove-Item -LiteralPath $shortcutPath -Force
    Write-Host "Startup shortcut removed." -ForegroundColor Yellow
} else {
    Write-Host "Startup shortcut was not installed." -ForegroundColor DarkGray
}
