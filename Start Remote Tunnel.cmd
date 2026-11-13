@echo off
setlocal
cd /d "%~dp0"

echo ========================================================
echo        Starting MOSS Live Remote Mobile Tunnel
echo ========================================================
echo.

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\setup_remote_tunnel.ps1"

if %errorlevel% neq 0 (
    echo.
    echo [ERROR] Tunnel failed to start.
    pause
)
