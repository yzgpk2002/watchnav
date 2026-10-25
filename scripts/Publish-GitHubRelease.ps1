param(
    [Parameter(Mandatory=$true)][string]$Tag,
    [Parameter(Mandatory=$true)][string]$Target,
    [Parameter(Mandatory=$true)][string]$NotesFile,
    [Parameter(Mandatory=$true)][string]$AssetDirectory,
    [switch]$MakePublic
)
$ErrorActionPreference = 'Stop'
$repository = 'yzgpk2002/watchnav'
$credentialLines = "protocol=https`nhost=github.com`n`n" | git credential fill
$passwordLine = $credentialLines | Where-Object { $_ -like 'password=*' } | Select-Object -First 1
if (-not $passwordLine) { throw 'GitHub Git credential was not available.' }
$previousToken = $env:GH_TOKEN
$env:GH_TOKEN = $passwordLine.Substring(9)
try {
    $repo = gh api "repos/$repository" | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0 -or -not $repo.permissions.admin) { throw 'Repository admin access is required.' }
    $existing = gh release view $Tag --repo $repository --json tagName 2>$null
    if ($LASTEXITCODE -ne 0) {
        gh release create $Tag --repo $repository --target $Target --title "WatchNav $Tag" --notes-file $NotesFile --draft
        if ($LASTEXITCODE -ne 0) { throw 'Release creation failed.' }
    }
    foreach ($asset in Get-ChildItem -LiteralPath $AssetDirectory -File) {
        gh release upload $Tag $asset.FullName --repo $repository --clobber
        if ($LASTEXITCODE -ne 0) { throw "Asset upload failed: $($asset.Name)" }
    }
    gh release edit $Tag --repo $repository --draft=false --latest
    if ($LASTEXITCODE -ne 0) { throw 'Release publication failed.' }
    if ($MakePublic) {
        gh repo edit $repository --visibility public --accept-visibility-change-consequences
        if ($LASTEXITCODE -ne 0) { throw 'Visibility change failed.' }
        gh api "repos/$repository" --method PATCH -f default_branch=$Target --silent
        if ($LASTEXITCODE -ne 0) { throw 'Default branch update failed.' }
    }
    gh api "repos/$repository" --jq '{visibility,default_branch,html_url}'
    gh release view $Tag --repo $repository --json url,isDraft,assets
} finally {
    if ($null -eq $previousToken) { Remove-Item Env:GH_TOKEN -ErrorAction SilentlyContinue }
    else { $env:GH_TOKEN = $previousToken }
    $credentialLines = $null
    $passwordLine = $null
}
