[CmdletBinding()]
param(
    [string]$WatchAddress = '',
    [ValidateRange(0, 65535)]
    [int]$WatchPort = 0,
    [string]$HostVmnetAddress = '192.168.187.1',
    [ValidateRange(1, 65535)]
    [int]$ForwardPort = 39517
)

$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) {
    throw "ADB was not found at: $adb"
}

function Test-AdbDevice([string]$Endpoint) {
    $state = & $adb -s $Endpoint get-state 2>$null
    return $state -eq 'device'
}

function Connect-Adb([string]$Endpoint) {
    & $adb connect $Endpoint | Out-Host
    return Test-AdbDevice $Endpoint
}

& $adb start-server | Out-Host
if (-not [string]::IsNullOrWhiteSpace($WatchAddress) -and $WatchPort -gt 0) {
    $directEndpoint = "${WatchAddress}:${WatchPort}"
    Write-Host "[WatchNav] Trying direct VM-to-watch ADB first: $directEndpoint" -ForegroundColor Cyan
    if (Connect-Adb $directEndpoint) {
        & $adb devices -l | Out-Host
        Write-Host "CONNECTED DIRECTLY: $directEndpoint" -ForegroundColor Green
        exit 0
    }
}
Write-Host '[WatchNav] Direct ADB is unavailable; trying the optional host TCP forward...' -ForegroundColor Yellow

$sharedEndpoint = '\\vmware-host\Shared Folders\BaiduSyncdisk\adb\watch-adb-forward-endpoint.txt'
if (Test-Path -LiteralPath $sharedEndpoint -PathType Leaf) {
    $saved = (Get-Content -LiteralPath $sharedEndpoint -Raw).Trim()
    if ($saved -match '^([^:]+):(\d+)$') {
        $HostVmnetAddress = $Matches[1]
        $ForwardPort = [int]$Matches[2]
    }
}
$endpoint = "${HostVmnetAddress}:${ForwardPort}"

Write-Host "[WatchNav] Testing host TCP forward: $endpoint" -ForegroundColor Cyan
$tcp = Test-NetConnection -ComputerName $HostVmnetAddress -Port $ForwardPort -WarningAction SilentlyContinue
if (-not $tcp.TcpTestSucceeded) {
    throw "Cannot reach $endpoint. Run Run-Host-Watch-Adb-Forward.cmd as Administrator on the physical host first."
}

Write-Host "[WatchNav] Connecting through the forward with the VM ADB key..." -ForegroundColor Cyan
if (-not (Connect-Adb $endpoint)) {
    throw "ADB did not authorize $endpoint. Confirm the wireless debugging authorization prompt on the watch."
}
& $adb devices -l | Out-Host
Write-Host "CONNECTED: $endpoint" -ForegroundColor Green
