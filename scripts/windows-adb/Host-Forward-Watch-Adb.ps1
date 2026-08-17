[CmdletBinding()]
param(
    [string]$WatchAddress = '',
    [ValidateRange(0, 65535)]
    [int]$WatchPort = 0,
    [ValidateRange(1, 65535)]
    [int]$ListenPort = 39517
)

$ErrorActionPreference = 'Stop'
$ruleName = 'WatchNav ADB TCP Forward (VMnet8)'
$targetFile = Join-Path $PSScriptRoot 'watch-adb-target.txt'

if ([string]::IsNullOrWhiteSpace($WatchAddress) -and (Test-Path -LiteralPath $targetFile -PathType Leaf)) {
    $savedTarget = (Get-Content -LiteralPath $targetFile -Raw).Trim()
    if ($savedTarget -match '^([^:]+):(\d+)$') {
        $WatchAddress = $Matches[1]
        $WatchPort = [int]$Matches[2]
    }
}
if ([string]::IsNullOrWhiteSpace($WatchAddress)) {
    $WatchAddress = (Read-Host 'Watch IP address').Trim()
}
if ($WatchPort -le 0) {
    $WatchPort = [int](Read-Host 'Watch wireless-debugging connection port')
}
if ($WatchAddress -notmatch '^\d{1,3}(\.\d{1,3}){3}$') {
    throw 'WatchAddress must be an IPv4 address.'
}
"${WatchAddress}:${WatchPort}" | Set-Content -LiteralPath $targetFile -Encoding Ascii

function Write-Step([string]$Message) {
    Write-Host "[WatchNav] $Message" -ForegroundColor Cyan
}

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host '[WatchNav] Requesting Administrator permission...' -ForegroundColor Yellow
    $quotedScript = '"' + $PSCommandPath + '"'
    $elevatedArguments = "-NoLogo -NoProfile -ExecutionPolicy Bypass -File $quotedScript " +
        "-WatchAddress $WatchAddress -WatchPort $WatchPort -ListenPort $ListenPort"
    Start-Process `
        -FilePath "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" `
        -ArgumentList $elevatedArguments `
        -Verb RunAs
    Write-Host '[WatchNav] Continue in the Administrator window.' -ForegroundColor Green
    return
}

Write-Step 'Locating the VMware NAT adapter...'
$vmnet = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object {
        $_.InterfaceAlias -match 'VMware.*VMnet8' -and
        $_.IPAddress -notlike '169.254.*'
    } |
    Sort-Object InterfaceMetric |
    Select-Object -First 1
if ($null -eq $vmnet) {
    throw 'VMware Network Adapter VMnet8 has no IPv4 address.'
}
$listenAddress = $vmnet.IPAddress
$octets = $listenAddress.Split('.')
$guestSubnet = "$($octets[0]).$($octets[1]).$($octets[2]).0/24"
Write-Step "VMnet8 endpoint: ${listenAddress}:${ListenPort}"

Write-Step "Checking watch endpoint ${WatchAddress}:${WatchPort}..."
$watchTest = Test-NetConnection -ComputerName $WatchAddress -Port $WatchPort -WarningAction SilentlyContinue
if (-not $watchTest.TcpTestSucceeded) {
    throw "The host cannot reach ${WatchAddress}:${WatchPort}. Keep Wireless debugging enabled and confirm its current port."
}

Write-Step 'Starting the Windows TCP forwarding service...'
Set-Service -Name iphlpsvc -StartupType Automatic
Start-Service -Name iphlpsvc

Write-Step 'Replacing only the WatchNav forwarding entry...'
& netsh interface portproxy delete v4tov4 listenaddress=$listenAddress listenport=$ListenPort protocol=tcp | Out-Null
& netsh interface portproxy add v4tov4 listenaddress=$listenAddress listenport=$ListenPort `
    connectaddress=$WatchAddress connectport=$WatchPort protocol=tcp | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "netsh portproxy failed with exit code $LASTEXITCODE."
}

Write-Step "Restricting the listener to VMware NAT guests (${guestSubnet})..."
Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule `
    -DisplayName $ruleName `
    -Direction Inbound `
    -Action Allow `
    -Profile Any `
    -Protocol TCP `
    -LocalAddress $listenAddress `
    -LocalPort $ListenPort `
    -RemoteAddress $guestSubnet | Out-Null

Write-Step 'Verifying the forwarding listener...'
$entry = & netsh interface portproxy show v4tov4
$entry | Out-Host
$listenerTest = Test-NetConnection -ComputerName $listenAddress -Port $ListenPort -WarningAction SilentlyContinue
if (-not $listenerTest.TcpTestSucceeded) {
    throw "Forwarding entry exists but ${listenAddress}:${ListenPort} is not listening."
}

$endpointFile = Join-Path $PSScriptRoot 'watch-adb-forward-endpoint.txt'
"${listenAddress}:${ListenPort}" | Set-Content -LiteralPath $endpointFile -Encoding Ascii
Write-Host ''
Write-Host "READY: In the VM, connect ADB to ${listenAddress}:${ListenPort}" -ForegroundColor Green
Write-Host "Endpoint saved to: $endpointFile" -ForegroundColor Green
