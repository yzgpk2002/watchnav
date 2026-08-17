@echo off
setlocal
title WatchNav host TCP forward
"%SystemRoot%\System32\fltMC.exe" >nul 2>&1
if errorlevel 1 (
  echo Requesting Administrator permission for the fallback TCP forward...
  "%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -Command "Start-Process -FilePath '%~f0' -WorkingDirectory '%~dp0' -Verb RunAs"
  if errorlevel 1 (
    echo Administrator permission was not granted.
    pause
  )
  exit /b
)
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0Host-Forward-Watch-Adb.ps1"
echo.
if errorlevel 1 (
  echo WatchNav forwarding setup FAILED.
) else (
  echo WatchNav forwarding setup completed.
)
echo This window will remain open.
pause
