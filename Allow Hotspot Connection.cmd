@echo off
setlocal
cd /d "%~dp0"
echo Requesting administrator permissions to configure Windows Firewall for MOSS Live Hotspot...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process powershell -Verb RunAs -ArgumentList '-NoProfile -ExecutionPolicy Bypass -File \"\"%~dp0scripts\setup_firewall.ps1\"\"'"
