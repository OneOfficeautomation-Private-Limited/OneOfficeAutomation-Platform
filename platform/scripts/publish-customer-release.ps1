# Publishes a customer release of ONEOFFICEAUTOMATION to the public repository VirupakshaPrabhu/OOA_Customer_Build.
#   powershell -ExecutionPolicy Bypass -File scripts\publish-customer-release.ps1 [-Notes "What changed"] [-Draft] [-Preview]
#   powershell -ExecutionPolicy Bypass -File scripts\publish-customer-release.ps1 -PageOnly
# After a release, and alone with -PageOnly, it refreshes the public releases page (Q10): docs\index.html,
# docs\releases.json and the signed template catalogue docs\templates.json (K7) in that repository, which GitHub Pages
# serves (Settings > Pages: branch main, folder /docs).
# -Preview publishes v<version>-preview as a GitHub pre-release, for customers' sandbox Command Centers about three weeks
# before the general release v<version> of the same version (docs\RELEASING.md, Release cadence).
# It builds the customer zip (gradlew :cli:customerDistZip), checks that the vendor tools are not in it, creates the
# release v<version> (the version in platform\build.gradle.kts) with the zips, the software bill of materials and a
# manifest signed with the vendor key attached (customers check downloads with: ooa release verify), and prints the
# release address.
# It signs in with the GitHub account git already uses (Git Credential Manager); nothing is stored or printed.
param(
    [string]$Notes = "",
    [switch]$Draft,
    [switch]$Preview,
    [switch]$PageOnly,
    [string]$Repository = "VirupakshaPrabhu/OOA_Customer_Build",
    [string]$SigningKey = (Join-Path $env:USERPROFILE "OOA License Keys\private.key")
)
$ErrorActionPreference = "Stop"
$platform = Split-Path -Parent $PSScriptRoot
$ooa = Join-Path $platform "cli\build\install\ooa\bin\ooa.bat"

# sign in with git's GitHub credential
# (the request goes through a file: Windows PowerShell changes text it pipes to programs, and git then rejects it)
function Get-GitHubToken {
    $req = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllText($req, "protocol=https`nhost=github.com`n`n", (New-Object System.Text.UTF8Encoding $false))
    try { $cred = cmd /c "git credential fill < `"$req`"" } finally { Remove-Item $req -Force }
    $t = ($cred | Where-Object { $_ -like "password=*" }) -replace "^password=", ""
    if (-not $t) { throw "No GitHub credential: push once to GitHub from this PC, then run this again" }
    return $t
}

# the public releases page: written by ooa release page, then put in the repository's docs folder
function Update-ReleasesPage([string]$Token) {
    $h = @{ Authorization = "Bearer $Token"; Accept = "application/vnd.github+json"; "X-GitHub-Api-Version" = "2022-11-28" }
    $pageDir = Join-Path $platform "cli\build\releases-page"
    $env:OOA_RELEASE_PAGE_TOKEN = $Token
    try { & $ooa release page --repo $Repository --out $pageDir --token-env OOA_RELEASE_PAGE_TOKEN } finally { Remove-Item Env:OOA_RELEASE_PAGE_TOKEN }
    if ($LASTEXITCODE -ne 0) { throw "Making the releases page failed" }
    $pageFiles = @("index.html", "releases.json")
    # the template catalogue (K7), signed with the vendor key: Command Centers list it in Automation > Templates
    $templates = Join-Path (Split-Path -Parent $platform) "templates"
    if (Test-Path $templates) {
        & $ooa release templates --dir $templates --key $SigningKey --out (Join-Path $pageDir "templates.json")
        if ($LASTEXITCODE -ne 0) { throw "Making the template catalogue failed" }
        $pageFiles += "templates.json"
    }
    foreach ($f in $pageFiles) {
        $uri = "https://api.github.com/repos/$Repository/contents/docs/$f"
        $old = $null
        try { $old = (Invoke-RestMethod -Headers $h -Uri "$($uri)?ref=main").sha } catch { }
        $put = @{ message = "Releases page"; branch = "main"; content = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes((Join-Path $pageDir $f))) }
        if ($old) { $put.sha = $old }
        Invoke-RestMethod -Method Put -Headers $h -Uri $uri -ContentType "application/json" -Body ($put | ConvertTo-Json) | Out-Null
    }
    $owner, $name = $Repository.Split("/")
    Write-Host "Releases page updated: https://$($owner.ToLower()).github.io/$name/ (once GitHub Pages serves main /docs)"
}

if ($PageOnly) {
    Push-Location $platform
    try { & .\gradlew.bat :cli:installDist --console=plain -q; if ($LASTEXITCODE -ne 0) { throw "The build failed" } } finally { Pop-Location }
    Update-ReleasesPage (Get-GitHubToken)
    return
}

# 1. version and build
$version = ([regex]'version = "([^"]+)"').Match((Get-Content (Join-Path $platform "build.gradle.kts") -Raw)).Groups[1].Value
if (-not $version) { throw "No version found in platform\build.gradle.kts" }
Write-Host "Building the customer zip for version $version ..."
Push-Location $platform
try { & .\gradlew.bat :cli:customerDistZip :cli:setupDistZip :cli:sbom :cli:installDist --console=plain -q; if ($LASTEXITCODE -ne 0) { throw "The build failed" } } finally { Pop-Location }
$zip = Join-Path $platform "cli\build\distributions\ooa-customer-$version.zip"
$setup = Join-Path $platform "cli\build\distributions\ooa-setup-$version.zip"
foreach ($f in $zip, $setup) { if (-not (Test-Path $f)) { throw "Not found: $f" } }

# 2. the customer zip must not carry the vendor's license tools
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($f in $zip, $setup) {
    $archive = [System.IO.Compression.ZipFile]::OpenRead($f)
    try {
        $vendor = $archive.Entries | Where-Object { $_.FullName -match "vendor-tools|private\.key|\.lic$" }
        if ($vendor) { throw "$f holds vendor files: $($vendor.FullName -join ', ')" }
    } finally { $archive.Dispose() }
}
$sizeMb = [math]::Round((Get-Item $zip).Length / 1MB)
Write-Host "Checked: no vendor tools in $zip ($sizeMb MB)."

# 2b. the signed manifest over the release files (with the vendor key, which never leaves this PC)
if (-not (Test-Path $SigningKey)) { throw "No signing key at $SigningKey (the vendor key that also signs licenses)" }
$stage = Join-Path $platform "cli\build\release-$version"
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory $stage | Out-Null
Copy-Item $zip, $setup $stage
Copy-Item (Join-Path $platform "cli\build\sbom\sbom.cdx.json") (Join-Path $stage "ooa-$version-sbom.cdx.json")
$manifest = Join-Path $stage "ooa-$version.manifest.json"
& $ooa release sign --dir $stage --version $version --key $SigningKey --out $manifest
if ($LASTEXITCODE -ne 0) { throw "Signing the manifest failed" }
& $ooa release verify $manifest
if ($LASTEXITCODE -ne 0) { throw "The signed manifest does not check out" }

# 3. sign in with git's GitHub credential
$token = Get-GitHubToken
$headers = @{ Authorization = "Bearer $token"; Accept = "application/vnd.github+json"; "X-GitHub-Api-Version" = "2022-11-28" }

# 4. the release (refused when it exists already: raise the version for a new release)
$tag = if ($Preview) { "v$version-preview" } else { "v$version" }
try { Invoke-RestMethod -Headers $headers -Uri "https://api.github.com/repos/$Repository/releases/tags/$tag" | Out-Null; throw "Release $tag exists already in $Repository. Raise the version in platform\build.gradle.kts for a new release." }
catch { if ($_.Exception.Message -like "Release $tag exists*") { throw } }
$source = (git -C $platform rev-parse --short HEAD).Trim()
if (-not $Notes) { $Notes = "See RELEASES.md for what changed." }
$body = $(if ($Preview) { "ONEOFFICEAUTOMATION $version preview, for sandbox Command Centers. The general release follows in about three weeks; report problems before then." } else { "ONEOFFICEAUTOMATION $version for customers." }) + "`n`n$Notes`n`n**Install:** download ooa-setup-$version.zip (Java included), unzip it and double-click Install.cmd; for silent installs see README.txt. Check downloads first with: ooa release verify ooa-$version.manifest.json (from an earlier install), or compare the SHA-256 values in it. Or ooa-customer-$version.zip: unzip, double-click START-OOA.cmd (Java 21 needed). Then install your license in Administration > Licenses. Full guide: INSTALL.md.`n`nBuilt from oneofficeautomation@$source."
$release = Invoke-RestMethod -Method Post -Headers $headers -Uri "https://api.github.com/repos/$Repository/releases" -ContentType "application/json" `
    -Body (@{ tag_name = $tag; target_commitish = "main"; name = "ONEOFFICEAUTOMATION $version" + $(if ($Preview) { " (sandbox preview)" } else { "" }); body = $body; draft = [bool]$Draft; prerelease = [bool]$Preview } | ConvertTo-Json)

# 5. attach the zip
Write-Host "Uploading $sizeMb MB ..."
$uploadUrl = $release.upload_url -replace "\{.*\}$", ""
$asset = Invoke-RestMethod -Method Post -Headers $headers -Uri "$($uploadUrl)?name=ooa-customer-$version.zip" -ContentType "application/zip" -InFile $zip -TimeoutSec 3600
Write-Host "Uploading the setup package ($([math]::Round((Get-Item $setup).Length / 1MB)) MB) ..."
Invoke-RestMethod -Method Post -Headers $headers -Uri "$($uploadUrl)?name=ooa-setup-$version.zip" -ContentType "application/zip" -InFile $setup -TimeoutSec 3600 | Out-Null
foreach ($f in "ooa-$version.manifest.json", "ooa-$version-sbom.cdx.json") {
    Invoke-RestMethod -Method Post -Headers $headers -Uri "$($uploadUrl)?name=$f" -ContentType "application/json" -InFile (Join-Path $stage $f) | Out-Null
}
Write-Host "Published $tag : $($release.html_url)"
Write-Host "Download: $($asset.browser_download_url)"

# 6. the public releases page (not for a draft, which customers cannot see yet)
if (-not $Draft) { Update-ReleasesPage $token }
