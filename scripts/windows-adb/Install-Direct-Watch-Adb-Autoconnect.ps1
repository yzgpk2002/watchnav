[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot 'Direct-Watch-Adb-Autoconnect.ps1'
$targetDirectory = Join-Path $env:LOCALAPPDATA 'WatchNav\scripts'
$target = Join-Path $targetDirectory 'Direct-Watch-Adb-Autoconnect.ps1'
New-Item -ItemType Directory -Path $targetDirectory -Force | Out-Null
Copy-Item -LiteralPath $source -Destination $target -Force

$action = New-ScheduledTaskAction `
    -Execute "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" `
    -Argument "-NoLogo -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$target`""
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
Register-ScheduledTask -TaskName 'WatchNav Direct ADB Auto Connect' -Action $action -Trigger $trigger `
    -Settings $settings -Description 'Discovers paired Wear OS devices over LAN mDNS and reconnects ADB.' `
    -Force | Out-Null
Start-ScheduledTask -TaskName 'WatchNav Direct ADB Auto Connect'
Write-Host 'WatchNav direct ADB auto-connect is installed and running.' -ForegroundColor Green
