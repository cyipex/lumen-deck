@echo off
rem Lumen Deck – one-click setup: installs the server, adds a desktop icon, starts it with Windows and opens the settings.
cd /d "%~dp0"
title Lumen Deck setup

where node >nul 2>nul
if errorlevel 1 (
  echo.
  echo  Node.js is not installed.
  echo  Download the LTS version from https://nodejs.org, install it, then run Setup.cmd again.
  echo.
  start "" https://nodejs.org
  pause
  exit /b 1
)

echo Installing Lumen Deck...
call npm install --no-audit --no-fund
if errorlevel 1 (
  echo.
  echo  npm install failed. Check your internet connection and try again.
  pause
  exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1"
start "" wscript.exe "%~dp0Lumen Desk.vbs"

echo.
echo  Done! Lumen Deck is running and will start with Windows.
echo  The settings window opens now. Next: install the app on your tablet.
echo.
pause
