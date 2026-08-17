[CmdletBinding()]
param(
    [switch]$Once,
    [int]$PollSeconds = 12
)

$ErrorActionPreference = 'Continue'
$logDirectory = Join-Path $env:LOCALAPPDATA 'WatchNav\logs'
$logFile = Join-Path $logDirectory 'direct-watch-adb.log'
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null

function Write-Log([string]$Message) {
    $line = '{0:yyyy-MM-dd HH:mm:ss} {1}' -f (Get-Date), $Message
    Add-Content -LiteralPath $logFile -Value $line -Encoding UTF8
    Write-Host $line
}

function Find-Adb {
    $candidates = @(
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'),
        '\\vmware-host\Shared Folders\BaiduSyncdisk\adb\adb.exe'
    )
    return $candidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
}

function Repair-Bridged-LanRoute {
    $lanAddress = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object {
            $_.InterfaceAlias -notmatch 'Tailscale|vEthernet' -and
            $_.IPAddress -notlike '192.168.187.*' -and
            $_.IPAddress -notlike '169.254.*' -and
            $_.IPAddress -ne '127.0.0.1'
        } |
        Select-Object -First 1
    if ($null -eq $lanAddress) { return $false }

    # Old NAT-specific routes override the connected LAN after a bridge switch.
    Get-NetRoute -PolicyStore PersistentStore -ErrorAction SilentlyContinue |
        Where-Object { $_.NextHop -eq '192.168.187.2' -and $_.DestinationPrefix -match '^192\.168\.' } |
        Remove-NetRoute -Confirm:$false -ErrorAction SilentlyContinue
    return $true
}

function Connect-DiscoveredWatch([string]$Adb) {
    $connectedWatch = (& $Adb devices -l 2>$null) | Where-Object {
        $_ -match '^([^\s]+:5555)\s+device\b.*\bmodel:SM_L'
    } | Select-Object -First 1
    if ($connectedWatch -and $connectedWatch -match '^([^\s]+:5555)') {
        return $true
    }

    $services = & $Adb mdns services 2>$null
    $endpoints = $services | ForEach-Object {
        if ($_ -match '_adb-tls-connect\._tcp\s+([^\s]+:\d+)') { $Matches[1] }
    } | Select-Object -Unique
    foreach ($endpoint in $endpoints) {
        $answer = (& $Adb connect $endpoint 2>&1) -join ' '
        if ($answer -notmatch 'connected to|already connected') { continue }
        $characteristics = (& $Adb -s $endpoint shell getprop ro.build.characteristics 2>$null) -join ''
        $model = (& $Adb -s $endpoint shell getprop ro.product.model 2>$null) -join ''
        if ($characteristics -match 'watch' -or $model -match '^SM-L') {
            Write-Log "Watch connected directly: $endpoint ($model)"
            return $true
        }
    }

    # Some access points suppress multicast DNS. The watch is placed in authenticated legacy TCP
    # mode on port 5555 once; a concurrent /24 probe then survives DHCP address changes without a
    # host-side forward or a user-supplied IP/ephemeral TLS port.
    $local = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object {
            $_.InterfaceAlias -notmatch 'Tailscale|vEthernet' -and
            $_.IPAddress -match '^\d+\.\d+\.\d+\.\d+$' -and
            $_.PrefixLength -eq 24
        } |
        Select-Object -First 1
    if (-not $local) { return $false }
    $octets = $local.IPAddress.Split('.')
    $prefix = "$($octets[0]).$($octets[1]).$($octets[2])"
    $probes = for ($hostNumber = 1; $hostNumber -le 254; $hostNumber++) {
        $ip = "$prefix.$hostNumber"
        if ($ip -eq $local.IPAddress) { continue }
        $client = [System.Net.Sockets.TcpClient]::new()
        try {
            [pscustomobject]@{ IP = $ip; Client = $client; Attempt = $client.BeginConnect($ip, 5555, $null, $null) }
        } catch {
            $client.Dispose()
        }
    }
    Start-Sleep -Milliseconds 600
    $watchFound = $false
    foreach ($probe in $probes) {
        try {
            if (-not $probe.Attempt.IsCompleted) { continue }
            $probe.Client.EndConnect($probe.Attempt)
            if (-not $probe.Client.Connected) { continue }
            $endpoint = "$($probe.IP):5555"
            $answer = (& $Adb connect $endpoint 2>&1) -join ' '
            if ($answer -notmatch 'connected to|already connected') { continue }
            $characteristics = (& $Adb -s $endpoint shell getprop ro.build.characteristics 2>$null) -join ''
            $model = (& $Adb -s $endpoint shell getprop ro.product.model 2>$null) -join ''
            if ($characteristics -match 'watch' -or $model -match '^SM-L') {
                Write-Log "Watch found after DHCP change: $endpoint ($model)"
                $watchFound = $true
                break
            }
        } catch {
            continue
        } finally {
            $probe.Client.Dispose()
        }
    }
    $probes | ForEach-Object { $_.Client.Dispose() }
    return $watchFound
}

$adb = Find-Adb
if (-not $adb) {
    Write-Log 'ADB was not found.'
    exit 2
}
& $adb start-server | Out-Null
Write-Log "Direct watch discovery started with $adb"

do {
    $bridged = Repair-Bridged-LanRoute
    if (-not $bridged) {
        Write-Log 'VMware adapter is still NAT (192.168.187.x); select Bridged in VMware once.'
    } elseif (-not (Connect-DiscoveredWatch $adb)) {
        Write-Log 'No paired Wear OS wireless-debugging service found; waiting for mDNS.'
    }
    if (-not $Once) { Start-Sleep -Seconds ([Math]::Max(5, $PollSeconds)) }
} while (-not $Once)
