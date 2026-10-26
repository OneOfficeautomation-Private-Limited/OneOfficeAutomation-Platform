<#
ONEOFFICEAUTOMATION setup: installs the product with its own Java, then sets this computer up as a Command Center or
as a runner. Run it through Install.cmd. Without -Silent it asks for anything not given.

  -Role CommandCenter|Runner   what this computer is (default: asked; CommandCenter when silent)
  -InstallDir DIR              where the product goes (default: Program Files with -AllUsers, else your own Programs folder)
  -AllUsers                    install for everyone on this computer (needs administrator)
  -Silent                      no questions; for SCCM, Intune or Group Policy
  Command Center:
  -Service                     run it as a Windows service that starts with Windows (needs administrator)
  -Port N                      its port (default 8720)
  -DataDir DIR                 its data folder (default: as the product chooses)
  Runner:
  -Url URL                     the Command Center address, for example https://ooa.example.com:8720
  -RegistrationKey KEY         a one-time key from Command Center > Runners > Register a runner
  -RunnerName NAME             the runner's name in Command Center (default: the computer name)
  -CaFile FILE                 the Command Center certificate to trust (for a self-signed one)
  -Service                     run the runner as a Windows service (only agents that need no screen); default: it starts
                               when the user signs in, for agents that use the screen
  Both:
  -NoShortcuts                 no Start menu entries
  -NoStart                     do not start anything after installing

Exit codes: 0 installed, 1 a parameter is missing or wrong, 2 the install failed, 3 administrator rights are needed.
Everything is logged to %TEMP%\ooa-install.log.
#>
param(
    [ValidateSet("", "CommandCenter", "Runner")] [string]$Role = "",
    [string]$InstallDir = "",
    [switch]$AllUsers,
    [switch]$Silent,
    [switch]$Service,
    [int]$Port = 8720,
    [string]$DataDir = "",
    [string]$Url = "",
    [string]$RegistrationKey = "",
    [string]$RunnerName = "",
    [string]$CaFile = "",
    [switch]$NoShortcuts,
    [switch]$NoStart,
    # used by the MSI: the files are already in place; only set up the role. ServiceFlag 1 = -Service.
    [switch]$ConfigureOnly,
    [string]$ServiceFlag = ""
)
if ($ServiceFlag -in @("1", "yes", "true")) { $Service = [switch]::Present }
$ErrorActionPreference = "Stop"
$log = Join-Path $env:TEMP "ooa-install.log"
$source = $PSScriptRoot
$restart = @()
function Log($m) { $line = "$(Get-Date -Format s)  $m"; Add-Content -Path $log -Value $line -Encoding utf8; if (-not $Silent) { Write-Host $m } }
function Fail($code, $m) { Log "FAILED: $m"; if (-not $Silent) { Write-Host "" ; Write-Host $m -ForegroundColor Red }; exit $code }
function Ask($q, $default) { if ($Silent) { return $default }; $a = Read-Host "$q$(if ($default) { " [$default]" })"; if ($a) { return $a.Trim() } else { return $default } }

Log "ONEOFFICEAUTOMATION setup from $source"
$version = (Get-ChildItem (Join-Path $source "lib") -Filter "cli-*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1).BaseName -replace "^cli-", ""
if (-not (Test-Path (Join-Path $source "runtime\bin\java.exe"))) { Fail 2 "This folder is not a complete setup package (runtime\bin\java.exe is missing). Unzip the whole package and run Install.cmd again." }

# ---------- what and where ----------
if (-not $Role) {
    if ($Silent) { $Role = "CommandCenter" }
    else {
        Write-Host "ONEOFFICEAUTOMATION $version setup"
        Write-Host "  1  Command Center (with Agent Studio): the server people sign in to"
        Write-Host "  2  Runner: a computer that runs agents for a Command Center elsewhere"
        $Role = if ((Ask "Install which (1 or 2)?" "1") -eq "2") { "Runner" } else { "CommandCenter" }
    }
}
$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (($AllUsers -or $Service) -and -not $admin) { Fail 3 "Installing for all users or as a Windows service needs administrator rights: run Install.cmd as administrator." }
if (-not $InstallDir) { $InstallDir = if ($AllUsers) { Join-Path $env:ProgramFiles "ONEOFFICEAUTOMATION" } else { Join-Path $env:LOCALAPPDATA "Programs\ONEOFFICEAUTOMATION" } }
if ($ConfigureOnly) {
    $InstallDir = $source
    # the MSI runs this as the system account: a runner it sets up is a Windows service (there is no user to sign in)
    if ($Role -eq "Runner") { $Service = [switch]::Present }
} else { $InstallDir = Ask "Install folder" $InstallDir }
if ($Role -eq "Runner") {
    $Url = Ask "Command Center address (for example https://ooa.example.com:8720)" $Url
    $RegistrationKey = Ask "Registration key (Command Center > Runners > Register a runner)" $RegistrationKey
    if (-not $Url -or -not $RegistrationKey) { Fail 1 "A runner needs -Url and -RegistrationKey." }
    if ($Url -notmatch '^https?://') { Fail 1 "-Url starts with https:// or http://" }
    if ($CaFile -and -not (Test-Path $CaFile)) { Fail 1 "No such certificate file: $CaFile" }
}
Log "Installing $Role $version into $InstallDir (all users: $([bool]$AllUsers), service: $([bool]$Service))"

# ---------- files ----------
$ooa = Join-Path $InstallDir "bin\ooa.bat"
if (-not $ConfigureOnly) {
try {
    if (Test-Path $InstallDir) {
        Log "Updating the installation in $InstallDir"
        # a running service would hold the files: stop it during the update
        foreach ($svc in "OOA Command Center", "OOA Runner") {
            $s = Get-Service -Name $svc -ErrorAction SilentlyContinue
            if ($s -and $s.Status -eq "Running") { Log "Stopping the service $svc"; Stop-Service -Name $svc -Force; $restart += @($svc) }
        }
        Remove-Item -Recurse -Force (Join-Path $InstallDir "lib"), (Join-Path $InstallDir "runtime") -ErrorAction SilentlyContinue
    }
    New-Item -ItemType Directory -Force $InstallDir | Out-Null
    foreach ($item in Get-ChildItem $source) {
        if ($item.Name -in @("Install.cmd")) { continue }
        Copy-Item -Recurse -Force $item.FullName (Join-Path $InstallDir $item.Name)
    }
} catch { Fail 2 "Copying the files failed: $($_.Exception.Message)" }
Log "Files copied."

# ---------- Apps and Features entry and Start menu ----------
try {
    $hive = if ($AllUsers) { "HKLM:" } else { "HKCU:" }
    $key = "$hive\Software\Microsoft\Windows\CurrentVersion\Uninstall\ONEOFFICEAUTOMATION"
    New-Item -Path $key -Force | Out-Null
    $un = "powershell -NoProfile -ExecutionPolicy Bypass -File `"$InstallDir\Uninstall-OOA.ps1`""
    $values = @{ DisplayName = "ONEOFFICEAUTOMATION"; DisplayVersion = $version; Publisher = "ONEOFFICEAUTOMATION"; InstallLocation = $InstallDir;
                 UninstallString = $un; QuietUninstallString = "$un -Silent"; NoModify = 1; NoRepair = 1; Comments = "Role: $Role" }
    foreach ($k in $values.Keys) { New-ItemProperty -Path $key -Name $k -Value $values[$k] -PropertyType $(if ($values[$k] -is [int]) { "DWord" } else { "String" }) -Force | Out-Null }
    if (-not $NoShortcuts) {
        $menu = if ($AllUsers) { [Environment]::GetFolderPath("CommonPrograms") } else { [Environment]::GetFolderPath("Programs") }
        $folder = Join-Path $menu "ONEOFFICEAUTOMATION"
        New-Item -ItemType Directory -Force $folder | Out-Null
        $shell = New-Object -ComObject WScript.Shell
        if ($Role -eq "CommandCenter" -and -not $Service) {
            $l = $shell.CreateShortcut((Join-Path $folder "ONEOFFICEAUTOMATION.lnk")); $l.TargetPath = Join-Path $InstallDir "START-OOA.cmd"; $l.WorkingDirectory = $InstallDir; $l.Save()
        }
        $l = $shell.CreateShortcut((Join-Path $folder "Uninstall ONEOFFICEAUTOMATION.lnk")); $l.TargetPath = "powershell.exe"
        $l.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$InstallDir\Uninstall-OOA.ps1`""; $l.Save()
    }
} catch { Log "Warning: the Apps entry or Start menu shortcut could not be made: $($_.Exception.Message)" }
}   # the MSI makes its own Apps entry and Start menu shortcut

# ---------- set up the role ----------
function Ooa([string[]]$a) {
    $out = (& $ooa @a 2>&1 | Out-String).Trim()
    Log "> ooa $(($a | ForEach-Object { if ($_ -like 'ooareg_*') { 'ooareg_(hidden)' } else { $_ } }) -join ' ')"
    if ($out) { Log $out }
    return $LASTEXITCODE
}
if ($Role -eq "CommandCenter") {
    if ($Service) {
        $a = @("service", "install", "--mode", "server", "--port", "$Port")
        if ($DataDir) { $a += @("--data", $DataDir) }
        if ($NoStart) { $a += "--no-start" }
        if ((Ooa $a) -ne 0) { Fail 2 "The Windows service could not be installed (see $log)." }
    }
    Log "Command Center installed. $(if ($Service) { "It runs as the Windows service 'OOA Command Center' on port $Port." } else { "Start it from the Start menu (ONEOFFICEAUTOMATION) or with START-OOA.cmd." })"
} else {
    $a = @("agent", "register", "--url", $Url, "--key", $RegistrationKey)
    if ($RunnerName) { $a += @("--name", $RunnerName) }
    if ($CaFile) { $a += @("--ca", (Resolve-Path $CaFile).Path) }
    if ((Ooa $a) -ne 0) { Fail 2 "The runner could not register with $Url (see $log). Check the address and that the key is unused and not expired." }
    if ($Service) {
        $a = @("service", "install", "--mode", "agent"); if ($NoStart) { $a += "--no-start" }
        if ((Ooa $a) -ne 0) { Fail 2 "The runner service could not be installed (see $log)." }
    } else {
        if ((Ooa @("agent", "install-task")) -ne 0) { Fail 2 "The runner could not be set to start at sign-in (see $log)." }
        if (-not $NoStart) { try { Start-ScheduledTask -TaskName "OOA Runner"; Log "Runner started." } catch { Log "Warning: start the runner by signing out and in again ($($_.Exception.Message))" } }
    }
    Log "Runner installed and registered with $Url."
}
foreach ($svc in $restart) { if ($svc) { Start-Service -Name $svc -ErrorAction SilentlyContinue; Log "Started the service $svc again" } }
Log "Done."
exit 0
