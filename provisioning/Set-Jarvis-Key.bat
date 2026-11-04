@echo off
REM Windows double-click entry point. Double-click to add or change your Gemini API key (and the optional Search key, only used on Gemini 3.8).
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" -Key
echo.
pause
