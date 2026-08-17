@echo off
setlocal
title Install WatchNav direct ADB auto-connect
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0Install-Direct-Watch-Adb-Autoconnect.ps1"
echo.
if errorlevel 1 (
  echo Installation FAILED.
) else (
  echo Installation completed.
)
echo This window will remain open.
pause
