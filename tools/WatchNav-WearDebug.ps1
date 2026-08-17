[CmdletBinding()]
param(
    [ValidateSet('Discover', 'Pair', 'Connect', 'Status', 'Install', 'Launch', 'Logcat', 'Screenshot', 'Mirror', 'Disconnect')]
    [string]$Action = 'Status',
    [string]$Endpoint,
    [string]$PairEndpoint,
    [string]$PairCode,
    [string]$Apk = (Join-Path $PSScriptRoot '..\watch-app\build\outputs\apk\debug\watch-app-debug.apk'),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\artifacts\wear-debug')
)

$ErrorActionPreference = 'Stop'
$packageName = 'com.watchnav.app'
$mainActivity = "$packageName/.MainActivity"
$userDataDirectory = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'WatchNav\wear-debug'
$connectionFile = Join-Path $userDataDirectory 'connection.json'

function Resolve-Executable {
    param(
        [Parameter(Mandatory)] [string]$Name,
        [string[]]$Candidates = @()
    )

    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    foreach ($candidate in $Candidates) {
        $resolved = Resolve-Path -Path $candidate -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($resolved) { return $resolved.Path }
    }
    throw "找不到 $Name。请先安装对应工具。"
}

$adbPath = Resolve-Executable -Name 'adb' -Candidates @(
    (Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'Android\Sdk\platform-tools\adb.exe')
)

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments)] [string[]]$Arguments)
    & $adbPath @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB 命令失败，退出码 $LASTEXITCODE。" }
}

function Get-SavedEndpoint {
    $saved = Get-SavedConnection
    if ($saved -and $saved.endpoint) { return "$($saved.endpoint)" }
    return $null
}

function Get-CurrentNetworkIdentity {
    $routes = Get-NetRoute -DestinationPrefix '0.0.0.0/0' -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.NextHop -notin @('0.0.0.0', '100.100.100.100') } |
        Sort-Object RouteMetric, InterfaceMetric
    foreach ($route in $routes) {
        $gateway = "$($route.NextHop)"
        $gatewayNeighbor = Get-NetNeighbor -InterfaceIndex $route.InterfaceIndex -IPAddress $gateway -AddressFamily IPv4 -ErrorAction SilentlyContinue |
            Where-Object LinkLayerAddress | Select-Object -First 1
        if (-not $gatewayNeighbor) {
            Test-Connection -TargetName $gateway -Count 1 -TimeoutSeconds 1 -Quiet -ErrorAction SilentlyContinue | Out-Null
            $gatewayNeighbor = Get-NetNeighbor -InterfaceIndex $route.InterfaceIndex -IPAddress $gateway -AddressFamily IPv4 -ErrorAction SilentlyContinue |
                Where-Object LinkLayerAddress | Select-Object -First 1
        }
        if ($gatewayNeighbor) {
            $adapter = Get-NetAdapter -InterfaceIndex $route.InterfaceIndex -ErrorAction SilentlyContinue
            return [pscustomobject]@{
                id = "$gateway|$($gatewayNeighbor.LinkLayerAddress)"
                gateway = $gateway
                gatewayMac = "$($gatewayNeighbor.LinkLayerAddress)"
                interfaceIndex = $route.InterfaceIndex
                interfaceAlias = if ($adapter) { $adapter.Name } else { $route.InterfaceAlias }
            }
        }
    }
    return $null
}

function Get-ConnectionCatalog {
    if (-not (Test-Path -LiteralPath $connectionFile)) {
        return [pscustomobject]@{ version = 2; networks = @() }
    }
    try {
        $value = Get-Content -LiteralPath $connectionFile -Raw | ConvertFrom-Json
        if ($value.networks) { return $value }
        # Migrate the original single-network record on first use.
        return [pscustomobject]@{ version = 2; networks = @($value) }
    } catch {
        return [pscustomobject]@{ version = 2; networks = @() }
    }
}

function Get-SavedConnection {
    $catalog = Get-ConnectionCatalog
    $network = Get-CurrentNetworkIdentity
    if ($network) {
        $match = @($catalog.networks | Where-Object { $_.networkId -eq $network.id } | Sort-Object savedAt -Descending)[0]
        if ($match) { return $match }
    }
    return $null
}

function Save-Endpoint {
    param([Parameter(Mandatory)] [string]$Value)
    New-Item -ItemType Directory -Path $userDataDirectory -Force | Out-Null
    $hostAddress = $Value.Split(':')[0]
    $model = (& $adbPath -s $Value shell getprop ro.product.model 2>$null | Select-Object -First 1).Trim()
    $neighbor = Get-NetNeighbor -IPAddress $hostAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object LinkLayerAddress | Select-Object -First 1
    $network = Get-CurrentNetworkIdentity
    $catalog = Get-ConnectionCatalog
    $entry = [pscustomobject]@{
        endpoint = $Value
        hostAddress = $hostAddress
        linkLayerAddress = if ($neighbor) { $neighbor.LinkLayerAddress } else { $null }
        model = $model
        networkId = if ($network) { $network.id } else { $null }
        gateway = if ($network) { $network.gateway } else { $null }
        gatewayMac = if ($network) { $network.gatewayMac } else { $null }
        interfaceAlias = if ($network) { $network.interfaceAlias } else { $null }
        savedAt = (Get-Date).ToString('o')
    }
    $others = @($catalog.networks | Where-Object {
        if ($entry.networkId) {
            $_.networkId -and $_.networkId -ne $entry.networkId
        } else {
            $_.endpoint -ne $entry.endpoint
        }
    })
    [pscustomobject]@{ version = 2; networks = @($others + $entry) } |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $connectionFile -Encoding UTF8
}

function Get-DiscoveredEndpoints {
    $lines = & $adbPath mdns services 2>$null
    foreach ($line in $lines) {
        if ($line -match '(?<endpoint>(?:\d{1,3}\.){3}\d{1,3}:\d+)') {
            [pscustomobject]@{
                Endpoint = $Matches.endpoint
                Kind = if ($line -match '_adb-tls-pairing') { 'Pairing' } else { 'Connect' }
                Raw = $line.Trim()
            }
        }
    }
}

function Resolve-Endpoint {
    if ($Endpoint) { return $Endpoint }
    $discovered = @(Get-DiscoveredEndpoints | Where-Object Kind -eq 'Connect')
    if ($discovered.Count -eq 1) { return $discovered[0].Endpoint }
    $saved = Get-SavedEndpoint
    if ($saved) { return $saved }
    throw '没有手表连接记录。请首次通过 -Endpoint 指定无线调试主页面显示的 IP:端口。'
}

function Test-WatchEndpoint {
    param(
        [Parameter(Mandatory)] [string]$Value,
        [string]$ExpectedModel
    )
    & $adbPath connect $Value 2>$null | Out-Null
    $state = & $adbPath -s $Value get-state 2>$null
    if ($LASTEXITCODE -ne 0 -or $state -notcontains 'device') { return $false }
    if ($ExpectedModel) {
        $actualModel = (& $adbPath -s $Value shell getprop ro.product.model 2>$null | Select-Object -First 1).Trim()
        if ($actualModel -ne $ExpectedModel) { return $false }
    }
    return $true
}

function Find-OpenPorts {
    param(
        [Parameter(Mandatory)] [string]$HostAddress,
        [int]$StartPort = 30000,
        [int]$EndPort = 50000
    )
    if (-not ('WatchNav.WearPortScanner' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Collections.Concurrent;
using System.Net.Sockets;
using System.Threading.Tasks;
namespace WatchNav {
    public static class WearPortScanner {
        public static int[] Scan(string host, int startPort, int endPort) {
            var open = new ConcurrentBag<int>();
            Parallel.For(startPort, endPort + 1,
                new ParallelOptions { MaxDegreeOfParallelism = 256 }, port => {
                    using (var client = new TcpClient()) {
                        try {
                            var task = client.ConnectAsync(host, port);
                            if (task.Wait(100) && client.Connected) open.Add(port);
                        } catch { }
                    }
                });
            var result = open.ToArray();
            Array.Sort(result);
            return result;
        }
    }
}
'@
    }
    return [WatchNav.WearPortScanner]::Scan($HostAddress, $StartPort, $EndPort)
}

function Find-SavedWatchHost {
    param([Parameter(Mandatory)] $SavedConnection)
    $savedHost = "$($SavedConnection.hostAddress)"
    $savedMac = "$($SavedConnection.linkLayerAddress)"
    if (-not $savedMac) { return $savedHost }
    $known = Get-NetNeighbor -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.LinkLayerAddress -eq $savedMac } | Select-Object -First 1
    if ($known) { return "$($known.IPAddress)" }
    return $savedHost
}

function Find-WatchEndpoint {
    $saved = Get-SavedConnection
    $expectedModel = if ($saved -and $saved.model) { "$($saved.model)" } else { 'SM-L3150' }
    $candidates = @()
    $candidates += Get-DiscoveredEndpoints | Where-Object Kind -eq 'Connect' | ForEach-Object Endpoint
    if ($saved -and $saved.endpoint) { $candidates += "$($saved.endpoint)" }
    foreach ($candidate in @($candidates | Select-Object -Unique)) {
        if (Test-WatchEndpoint -Value $candidate -ExpectedModel $expectedModel) { return $candidate }
    }

    $hostAddress = if ($saved -and $saved.hostAddress) {
        Find-SavedWatchHost -SavedConnection $saved
    } elseif ($saved -and $saved.endpoint) {
        "$($saved.endpoint)".Split(':')[0]
    } else { $null }
    if (-not $hostAddress) { return $null }

    # Wireless debugging chooses an ephemeral TCP port. Probe in parallel, then ask ADB to
    # authenticate each open endpoint and verify the saved watch model before accepting it.
    foreach ($port in @(Find-OpenPorts -HostAddress $hostAddress)) {
        $candidate = "${hostAddress}:$port"
        if (Test-WatchEndpoint -Value $candidate -ExpectedModel $expectedModel) { return $candidate }
    }
    return $null
}

function Connect-Watch {
    $target = if ($Endpoint) { $Endpoint } else { Find-WatchEndpoint }
    if (-not $target) {
        throw '未能自动找到手表。请保持无线调试主页面亮屏；首次连接可通过 -Endpoint 指定 IP:端口。'
    }
    $result = & $adbPath connect $target 2>&1
    $result | Write-Host
    if ($LASTEXITCODE -ne 0 -or "$result" -match 'failed|cannot|unable') {
        throw "无法连接 $target。请确认电脑与手表网络互通，且手表无线调试仍开启。"
    }
    Save-Endpoint -Value $target
    return $target
}

function Get-WatchSerial {
    $target = if ($Endpoint) { $Endpoint } else { Find-WatchEndpoint }
    if (-not $target) { return Connect-Watch }
    $state = & $adbPath -s $target get-state 2>$null
    if ($LASTEXITCODE -ne 0 -or $state -notcontains 'device') { $target = Connect-Watch }
    Save-Endpoint -Value $target
    return $target
}

switch ($Action) {
    'Discover' {
        $found = @(Get-DiscoveredEndpoints)
        if ($found.Count -eq 0) {
            Write-Host '未发现无线 ADB 服务。打开手表“开发者选项 > 无线调试”，并确保电脑与手表网络互通。'
        } else {
            $found | Format-Table Kind, Endpoint, Raw -AutoSize
        }
    }
    'Pair' {
        $pairTarget = if ($PairEndpoint) { $PairEndpoint } else {
            $pairing = @(Get-DiscoveredEndpoints | Where-Object Kind -eq 'Pairing')
            if ($pairing.Count -ne 1) { throw '请通过 -PairEndpoint 指定手表“使用配对码配对设备”页面显示的 IP:端口。' }
            $pairing[0].Endpoint
        }
        if (-not $PairCode) { $PairCode = Read-Host '输入手表显示的六位配对码' }
        & $adbPath pair $pairTarget $PairCode
        if ($LASTEXITCODE -ne 0) { throw 'ADB 配对失败。请重新打开手表配对码页面后重试。' }
        Write-Host '配对完成。连接端口通常与配对端口不同，请接着执行 Connect。'
    }
    'Connect' { Connect-Watch | Out-Null }
    'Status' {
        $serial = Get-WatchSerial
        Write-Host "手表：$serial"
        Invoke-Adb -Arguments @('-s', $serial, 'shell', 'getprop', 'ro.product.model')
        Invoke-Adb -Arguments @('-s', $serial, 'shell', 'dumpsys', 'package', $packageName) |
            Select-String 'versionName=|versionCode=|firstInstallTime=|lastUpdateTime=|ACCESS_FINE_LOCATION: granted='
    }
    'Install' {
        $serial = Get-WatchSerial
        $apkPath = (Resolve-Path -LiteralPath $Apk).Path
        Invoke-Adb -Arguments @('-s', $serial, 'install', '-r', $apkPath)
        Invoke-Adb -Arguments @('-s', $serial, 'shell', 'am', 'start', '-n', $mainActivity)
    }
    'Launch' {
        $serial = Get-WatchSerial
        Invoke-Adb -Arguments @('-s', $serial, 'shell', 'am', 'start', '-n', $mainActivity)
    }
    'Logcat' {
        $serial = Get-WatchSerial
        New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
        $logFile = Join-Path $OutputDirectory ("watchnav-{0:yyyyMMdd-HHmmss}.log" -f (Get-Date))
        Write-Host "正在记录日志，按 Ctrl+C 停止：$logFile"
        & $adbPath -s $serial logcat --pid=((& $adbPath -s $serial shell pidof $packageName).Trim()) | Tee-Object -FilePath $logFile
    }
    'Screenshot' {
        $serial = Get-WatchSerial
        New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
        $imageFile = Join-Path $OutputDirectory ("watch-{0:yyyyMMdd-HHmmss}.png" -f (Get-Date))
        & $adbPath -s $serial exec-out screencap -p > $imageFile
        if ($LASTEXITCODE -ne 0) { throw '截取手表屏幕失败。' }
        Write-Host $imageFile
    }
    'Mirror' {
        $serial = Get-WatchSerial
        $scrcpyPath = Resolve-Executable -Name 'scrcpy' -Candidates @(
            (Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'Microsoft\WinGet\Packages\Genymobile.scrcpy_*\scrcpy-win64-*\scrcpy.exe')
        )
        & $scrcpyPath --serial $serial --no-audio --max-size 720 --max-fps 30 --window-title 'WatchNav Wear OS'
    }
    'Disconnect' {
        $target = Resolve-Endpoint
        Invoke-Adb -Arguments @('disconnect', $target)
    }
}
