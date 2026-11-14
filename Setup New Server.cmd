@echo off
setlocal
cd /d "%~dp0"

echo ========================================================
echo        Visual Assistant New Server Setup
echo ========================================================
echo.

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\setup_server_node.ps1"

pause
