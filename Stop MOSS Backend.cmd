@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\stop_backend.ps1"
echo Backend has been stopped.
timeout /t 2 >nul
