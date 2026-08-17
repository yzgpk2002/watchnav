@echo off
setlocal
title WatchNav VM ADB direct-first connection
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0VM-Connect-Watch-Adb.ps1"
echo.
if errorlevel 1 (
  echo WatchNav VM ADB connection FAILED.
) else (
  echo WatchNav VM ADB connection completed.
)
echo This window will remain open.
pause
