param(
    [Parameter(Mandatory=$true)][string]$AndroidSdk,
    [string]$OutputDirectory = 'dist/v0.20.39-public'
)
$ErrorActionPreference = 'Stop'
$sdkTools = Join-Path $AndroidSdk 'build-tools/36.0.0'
$expectedSigner = 'b95f9ee91974647b0401d04fbd0d10c68818d3f751fc1b44a0c3db070acb005d'
New-Item -ItemType Directory -Force $OutputDirectory | Out-Null
$packages = @(
    @{Source='watch-app/build/outputs/apk/debug/watch-app-debug.apk'; File='WatchNav-watch-v0.20.39.apk'; Version='0.20.39'; Code='64'},
    @{Source='mobile-app/build/outputs/apk/debug/mobile-app-debug.apk'; File='WatchNav-phone-v0.20.26-companion.apk'; Version='0.20.26-companion'; Code='10049'}
)
$packageReports = foreach ($package in $packages) {
    $source = (Resolve-Path -LiteralPath $package.Source).Path
    $signer = & (Join-Path $sdkTools 'apksigner.bat') verify --print-certs $source
    if ($LASTEXITCODE -ne 0 -or -not ($signer -match $expectedSigner)) { throw "APK signature mismatch: $source" }
    $badging = & (Join-Path $sdkTools 'aapt.exe') dump badging $source
    if ($LASTEXITCODE -ne 0) { throw "APK metadata read failed: $source" }
    $packageLine = $badging | Where-Object { $_ -like 'package:*' } | Select-Object -First 1
    if ($packageLine -notlike "*name='com.watchnav.app'*" -or
        $packageLine -notlike "*versionCode='$($package.Code)'*" -or
        $packageLine -notlike "*versionName='$($package.Version)'*") { throw "APK version mismatch: $packageLine" }
    Copy-Item -LiteralPath $source -Destination (Join-Path $OutputDirectory $package.File) -Force
    [ordered]@{file=$package.File; package='com.watchnav.app'; versionName=$package.Version; versionCode=$package.Code; signerSha256=$expectedSigner}
}
Copy-Item -LiteralPath 'artifacts/E120_N30-20260816.rd5' -Destination $OutputDirectory -Force
Copy-Item -LiteralPath 'artifacts/phone-watch-regression/sentinel2-2025-test.mbtiles' -Destination $OutputDirectory -Force
Copy-Item -LiteralPath 'docs/MAP_ASSET_MANIFEST.json' -Destination $OutputDirectory -Force
Copy-Item -LiteralPath 'THIRD_PARTY_NOTICES.md' -Destination $OutputDirectory -Force
$packageReports | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'APK_MANIFEST.json') -Encoding utf8
Compress-Archive -Path docs,README.md,CHANGELOG.md,THIRD_PARTY_NOTICES.md -DestinationPath (Join-Path $OutputDirectory 'WatchNav-docs-v0.20.39.zip') -Force
$checksums = Get-ChildItem -LiteralPath $OutputDirectory -File | Where-Object Name -ne 'SHA256SUMS.txt' | Sort-Object Name | ForEach-Object {
    $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $($_.Name)"
}
$checksums | Set-Content -LiteralPath (Join-Path $OutputDirectory 'SHA256SUMS.txt') -Encoding ascii
$packageReports | Format-Table
Get-ChildItem -LiteralPath $OutputDirectory -File | Select-Object Name,Length
