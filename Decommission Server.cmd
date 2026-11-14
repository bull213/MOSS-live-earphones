@echo off
setlocal
cd /d "%~dp0"

echo ========================================================
echo        Visual Assistant Server Decommission
echo ========================================================
echo.

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\decommission_server.ps1"

pause
