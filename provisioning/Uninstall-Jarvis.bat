@echo off
REM Windows double-click entry point. Double-click to remove Jarvis from your Portal.
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" -Uninstall
echo.
pause
