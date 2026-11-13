@echo off
setlocal
cd /d "%~dp0"

:: 1. Check for administrative elevation. If not elevated, relaunch with UAC prompt.
net session >nul 2>&1
if not %errorLevel% == 0 (
    echo [MOSS Live] Requesting administrator privileges to restart backend...
    powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "Start-Process cmd -ArgumentList '/c \"\"%~f0\"\"' -Verb RunAs"
    exit /b
)

echo ========================================================
echo               Restarting MOSS Live Backend
echo ========================================================
echo.

:: 2. Stop running backend on port 8000
echo Stopping running backend on port 8000...
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\stop_backend.ps1"

:: 3. Start updated backend
echo.
echo Starting updated backend server...
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\run_backend.ps1"

echo.
echo ========================================================
echo Backend restarted successfully!
echo You can now tap 'Switch Google Account' on your phone.
echo ========================================================
echo.
echo Window closing in 5 seconds...
timeout /t 5 >nul
