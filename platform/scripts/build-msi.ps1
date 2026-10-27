# Builds ooa-<version>.msi from the setup package's files (gradlew :cli:installSetupDist), with WiX Toolset 5:
#   dotnet tool install --global wix --version 5.0.2        (once; CI does this)
#   powershell -ExecutionPolicy Bypass -File scripts\build-msi.ps1 [-Wix PATH\wix.exe]
# The MSI installs for all users into Program Files\ONEOFFICEAUTOMATION, with a Start menu entry, and sets the
# computer up from its properties (Install-OOA.ps1 -ConfigureOnly), for example:
#   msiexec /i ooa-0.1.1.msi /qn ROLE=CommandCenter SERVICE=1 PORT=8720
#   msiexec /i ooa-0.1.1.msi /qn ROLE=Runner URL=https://ooa.example.com:8720 REGISTRATION_KEY=ooareg_... [RUNNER_NAME=..] [CA_FILE=..] [PROXY=..]
# Without ROLE it only installs the files. Uninstalling removes the services first; data folders are kept.
param(
    [string]$Wix = "wix",
    [string]$Source = "",
    [string]$Out = ""
)
$ErrorActionPreference = "Stop"
$platform = Split-Path -Parent $PSScriptRoot
$version = ([regex]'version = "([^"]+)"').Match((Get-Content (Join-Path $platform "build.gradle.kts") -Raw)).Groups[1].Value
if (-not $Source) {
    Push-Location $platform
    try { & .\gradlew.bat :cli:installSetupDist --console=plain -q; if ($LASTEXITCODE -ne 0) { throw "The build failed" } } finally { Pop-Location }
    $Source = Join-Path $platform "cli\build\install\ooa-setup"
}
$Source = (Resolve-Path $Source).Path
if (-not (Test-Path (Join-Path $Source "runtime\bin\java.exe"))) { throw "$Source is not the setup package (runtime\bin\java.exe missing)" }
if (-not $Out) { $Out = Join-Path $platform "cli\build\distributions\ooa-$version.msi" }
$work = Join-Path $platform "cli\build\msi"
Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $work | Out-Null

function X([string]$s) { [System.Security.SecurityElement]::Escape($s) }

# ---------- the folder tree and one component per file ----------
$dirs = @{ "" = "INSTALLFOLDER" }
$n = 0
$tree = New-Object System.Text.StringBuilder
$components = New-Object System.Text.StringBuilder
function Walk([string]$abs, [string]$rel, [string]$id, [int]$depth) {
    foreach ($d in Get-ChildItem -LiteralPath $abs -Directory | Sort-Object Name) {
        $script:n++; $did = "d$($script:n)"
        $r = if ($rel) { "$rel\$($d.Name)" } else { $d.Name }
        [void]$tree.Append(("  " * $depth) + "<Directory Id=`"$did`" Name=`"$(X $d.Name)`">`n")
        Walk $d.FullName $r $did ($depth + 1)
        [void]$tree.Append(("  " * $depth) + "</Directory>`n")
    }
    foreach ($f in Get-ChildItem -LiteralPath $abs -File | Sort-Object Name) {
        if ($f.Name -eq "Install.cmd") { continue }   # the MSI is the installer
        $script:n++
        [void]$components.Append("      <Component Id=`"c$($script:n)`" Directory=`"$id`" Bitness=`"always64`"><File Id=`"f$($script:n)`" Source=`"$(X $f.FullName)`" KeyPath=`"yes`" /></Component>`n")
    }
}
Walk $Source "" "INSTALLFOLDER" 4
$files = $n

$ps = "[System64Folder]WindowsPowerShell\v1.0\powershell.exe"
$configure = "&quot;$ps&quot; -NoProfile -ExecutionPolicy Bypass -File &quot;[INSTALLFOLDER]Install-OOA.ps1&quot; -ConfigureOnly -Silent -Role [ROLE] -ServiceFlag &quot;[SERVICE]&quot; -Port [PORT] -DataDir &quot;[DATA_DIR]&quot; -Url &quot;[URL]&quot; -RegistrationKey &quot;[REGISTRATION_KEY]&quot; -RunnerName &quot;[RUNNER_NAME]&quot; -CaFile &quot;[CA_FILE]&quot; -Proxy &quot;[PROXY]&quot;"
$unconfigure = "&quot;$ps&quot; -NoProfile -ExecutionPolicy Bypass -File &quot;[INSTALLFOLDER]Uninstall-OOA.ps1&quot; -Silent -KeepFiles"

$wxs = @"
<Wix xmlns="http://wixtoolset.org/schemas/v4/wxs">
  <Package Name="ONEOFFICEAUTOMATION" Manufacturer="ONEOFFICEAUTOMATION" Version="$version" UpgradeCode="5D636A94-CAEF-4BB5-A14E-8DD07D1EC2F8" Scope="perMachine">
    <SummaryInformation Description="ONEOFFICEAUTOMATION $version (Command Center, Agent Studio and runner, with Java)" />
    <MajorUpgrade DowngradeErrorMessage="A newer ONEOFFICEAUTOMATION is already installed." />
    <MediaTemplate EmbedCab="yes" CompressionLevel="high" />
    <Property Id="ARPNOMODIFY" Value="1" />
    <Property Id="ARPNOREPAIR" Value="1" />
    <Property Id="ROLE" Secure="yes" />
    <Property Id="SERVICE" Secure="yes" />
    <Property Id="PORT" Value="8720" Secure="yes" />
    <Property Id="DATA_DIR" Secure="yes" />
    <Property Id="URL" Secure="yes" />
    <Property Id="REGISTRATION_KEY" Secure="yes" Hidden="yes" />
    <Property Id="RUNNER_NAME" Secure="yes" />
    <Property Id="CA_FILE" Secure="yes" />
    <Property Id="PROXY" Secure="yes" />
    <StandardDirectory Id="ProgramFiles64Folder">
      <Directory Id="INSTALLFOLDER" Name="ONEOFFICEAUTOMATION">
$($tree.ToString())      </Directory>
    </StandardDirectory>
    <StandardDirectory Id="ProgramMenuFolder">
      <Directory Id="MenuFolder" Name="ONEOFFICEAUTOMATION" />
    </StandardDirectory>
    <ComponentGroup Id="ProductFiles">
$($components.ToString())    </ComponentGroup>
    <Component Id="MenuShortcut" Directory="MenuFolder" Bitness="always64">
      <Shortcut Id="StartShortcut" Name="ONEOFFICEAUTOMATION" Description="Start the Command Center and Agent Studio" Target="[INSTALLFOLDER]START-OOA.cmd" WorkingDirectory="INSTALLFOLDER" />
      <RemoveFolder Id="RemoveMenuFolder" On="uninstall" />
      <RegistryValue Root="HKLM" Key="Software\ONEOFFICEAUTOMATION" Name="StartMenu" Type="integer" Value="1" KeyPath="yes" />
    </Component>
    <Component Id="InstallPath" Directory="INSTALLFOLDER" Bitness="always64">
      <RegistryValue Root="HKLM" Key="Software\ONEOFFICEAUTOMATION" Name="InstallDir" Type="string" Value="[INSTALLFOLDER]" KeyPath="yes" />
    </Component>
    <Feature Id="Product" Title="ONEOFFICEAUTOMATION">
      <ComponentGroupRef Id="ProductFiles" />
      <ComponentRef Id="MenuShortcut" />
      <ComponentRef Id="InstallPath" />
    </Feature>
    <CustomAction Id="Configure" Directory="INSTALLFOLDER" ExeCommand="$configure" Execute="deferred" Impersonate="no" Return="check" HideTarget="yes" />
    <CustomAction Id="Unconfigure" Directory="INSTALLFOLDER" ExeCommand="$unconfigure" Execute="deferred" Impersonate="no" Return="ignore" />
    <InstallExecuteSequence>
      <Custom Action="Unconfigure" Before="RemoveFiles" Condition="REMOVE~=&quot;ALL&quot; AND NOT UPGRADINGPRODUCTCODE" />
      <Custom Action="Configure" Before="InstallFinalize" Condition="NOT REMOVE AND ROLE" />
    </InstallExecuteSequence>
  </Package>
</Wix>
"@
$wxsFile = Join-Path $work "ooa.wxs"
[System.IO.File]::WriteAllText($wxsFile, $wxs, (New-Object System.Text.UTF8Encoding $false))
Write-Host "Building $Out from $files files ..."
& $Wix build -arch x64 -o $Out $wxsFile
if ($LASTEXITCODE -ne 0) { throw "wix build failed" }
Write-Host "Built $Out ($([math]::Round((Get-Item $Out).Length / 1MB)) MB)"
