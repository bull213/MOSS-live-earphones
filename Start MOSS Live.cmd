@echo off
setlocal
cd /d "%~dp0"

:: 1. Check for administrative elevation. If not elevated, relaunch with UAC prompt.
net session >nul 2>&1
if not %errorLevel% == 0 (
    echo [MOSS Live] Requesting administrator privileges to configure network and firewall...
    powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "Start-Process cmd -ArgumentList '/c \"\"%~f0\"\" %*' -Verb RunAs"
    exit /b
)

:: 2. If elevated, ensure firewall rules and private hotspot profile are configured
if exist "%~dp0scripts\setup_firewall.ps1" (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\setup_firewall.ps1"
)

:: 3. Launch the main MOSS Live launcher
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Start MOSS Live.ps1" %*
if errorlevel 1 (
    echo.
    echo Setup encountered an issue. Read the message above, then press any key to close this window.
    pause >nul
    exit /b 1
)

echo.
echo Ready. Tap Start MOSS Live on the phone when you want to begin.
pause
