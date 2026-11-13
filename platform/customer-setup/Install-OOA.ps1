<#
ONEOFFICEAUTOMATION setup: installs the product with its own Java, then sets this computer up as One CommandCenter or
as a runner. Run it through Install.cmd. Without -Silent it asks for anything not given.

  -Role CommandCenter|Runner   what this computer is (default: asked; CommandCenter when silent)
  -InstallDir DIR              where the product goes (default: Program Files with -AllUsers, else your own Programs folder)
  -AllUsers                    install for everyone on this computer (needs administrator)
  -Silent                      no questions; for SCCM, Intune or Group Policy
  One CommandCenter:
  -Service                     run it as a Windows service that starts with Windows (needs administrator)
  -Port N                      its port (default 8720)
  -ListenAddress ADDR          the address the service listens on (default 0.0.0.0, every network card, so runners on
                               other machines reach it; 127.0.0.1 for this computer only). Set up HTTPS (bin\ooa.bat tls)
                               before other machines use it.
  -DataDir DIR                 its data folder (default: as the product chooses; for the MSI, which runs as the system
                               account, %ProgramData%\OneOfficeAutomation\One CommandCenter)
  -DbServer NAME               put its data on this SQL Server (host or host\instance); default: the built-in evaluation
                               database. With it: -DbName (ooa_command_center), -DbUser (ooa_app), -DbSetupUser (ooa_setup),
                               -DbPasswordEnv VAR and -DbSetupPasswordEnv VAR (environment variables holding the passwords;
                               asked for when not silent), -DbTrustCertificate (a test server's self-signed certificate)
  -SkipCheck                   install even when the prerequisite check (ooa check) finds a problem
  -DocFlow                     also install Document Automation (AI > Document Automation): the DocFlow service at the
                               version this release is tested with, downloaded from its GitHub release. Needs internet:
                               it fetches Python 3.12 and Tesseract (winget) and Python packages. Asked when not silent.
                               It runs in managed mode beside One CommandCenter on 127.0.0.1, starts with Windows (with
                               -Service) or at sign-in, and keeps its documents in One CommandCenter's data folder
                               (DATA\docflow). -DocFlowPort N (default 8085), -DocFlowDir DIR (default INSTALLDIR\DocFlow)
  -DocFlowPackage ZIP          install Document Automation with no internet, from ooa-docflow-offline-<ref>.zip
                               (made with Make-DocFlowOffline.ps1 on a computer with internet)
  Runner:
  -Url URL                     One CommandCenter address, for example https://ooa.example.com:8720
  -RegistrationKey KEY         a one-time key from One CommandCenter > Runners > Register a runner (not needed
                               when setup runs again on a registered runner: it keeps its registration)
  -RunnerName NAME             the runner's name in One CommandCenter (default: the computer name)
  -CaFile FILE                 One CommandCenter certificate to trust (for a self-signed one)
  -Proxy system|none|URL       how the runner reaches One CommandCenter (default system: the Windows proxy)
                               windows (or windows:http://host:port): through Windows' web stack, signing in to a
                               proxy that accepts only Windows sign-in (NTLM, Kerberos) as the runner's Windows user
  -Service                     run the runner as a Windows service (only agents that need no screen); default: it starts
                               when the user signs in, for agents that use the screen
  Both:
  -NoShortcuts                 no Start menu entries
  -NoStart                     do not start anything after installing

Exit codes: 0 installed, 1 a parameter is missing or wrong, 2 the install failed, 3 administrator rights are needed,
4 the prerequisite check failed, 5 installed but Document Automation could not be set up (see the log). An update in place stops the services, replaces the files, upgrades the database and
starts the services again.
Everything is logged to %TEMP%\ooa-install.log.
#>
param(
    [ValidateSet("", "CommandCenter", "Runner")] [string]$Role = "",
    [string]$InstallDir = "",
    [switch]$AllUsers,
    [switch]$Silent,
    [switch]$Service,
    [int]$Port = 8720,
    [string]$ListenAddress = "",
    [string]$DataDir = "",
    [string]$Url = "",
    [string]$RegistrationKey = "",
    [string]$RunnerName = "",
    [string]$CaFile = "",
    [string]$Proxy = "",
    [string]$DbServer = "",
    [string]$DbName = "ooa_command_center",
    [string]$DbUser = "ooa_app",
    [string]$DbSetupUser = "ooa_setup",
    [string]$DbPasswordEnv = "",
    [string]$DbSetupPasswordEnv = "",
    [switch]$DbTrustCertificate,
    [switch]$SkipCheck,
    [switch]$DocFlow,
    [int]$DocFlowPort = 8085,
    [string]$DocFlowDir = "",
    # round 4 A5: install Document Automation from ooa-docflow-offline-<ref>.zip (Make-DocFlowOffline.ps1), with no internet
    [string]$DocFlowPackage = "",
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
        Write-Host "  1  One CommandCenter (with OneAgentStudio): the server people sign in to"
        Write-Host "  2  Runner: a computer that runs agents for One CommandCenter elsewhere"
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
    # ...whose profile folder is under C:\Windows\System32: keep One CommandCenter's data in ProgramData instead
    if ($Role -eq "CommandCenter" -and -not $DataDir) { $DataDir = Join-Path $env:ProgramData "OneOfficeAutomation\One CommandCenter" }
} else { $InstallDir = Ask "Install folder" $InstallDir }
if ($Role -eq "Runner") {
    $Url = Ask "One CommandCenter address (for example https://ooa.example.com:8720)" $Url
    # setup run again on a registered runner (an update or repair) keeps its registration: no key is needed then
    $RegistrationKey = Ask "Registration key (One CommandCenter > Runners > Register a runner; empty keeps this computer's registration)" $RegistrationKey
    if (-not $Url) { Fail 1 "A runner needs -Url (and -RegistrationKey the first time)." }
    if ($Url -notmatch '^https?://') { Fail 1 "-Url starts with https:// or http://" }
    if ($CaFile -and -not (Test-Path $CaFile)) { Fail 1 "No such certificate file: $CaFile" }
}
if ($Role -eq "CommandCenter" -and -not $ConfigureOnly -and -not $DbServer -and -not $Silent) {
    $DbServer = Ask "SQL Server for One CommandCenter's data (host or host\instance; empty = built-in evaluation database)" ""
}
if ($Role -eq "CommandCenter" -and $DbServer) {
    # passwords travel in environment variables only, never on a command line or in the log
    if (-not $DbPasswordEnv) {
        if ($Silent) { Fail 1 "-DbServer needs -DbPasswordEnv and -DbSetupPasswordEnv (environment variables holding the passwords)." }
        $env:OOA_SETUP_DB_PW = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR((Read-Host "Password of $DbUser (the account the server runs with)" -AsSecureString)))
        $DbPasswordEnv = "OOA_SETUP_DB_PW"
    }
    if (-not $DbSetupPasswordEnv) {
        if ($Silent) { Fail 1 "-DbServer needs -DbSetupPasswordEnv." }
        $env:OOA_SETUP_DB_SETUP_PW = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR((Read-Host "Password of $DbSetupUser (creates the database and tables)" -AsSecureString)))
        $DbSetupPasswordEnv = "OOA_SETUP_DB_SETUP_PW"
    }
    foreach ($v in $DbPasswordEnv, $DbSetupPasswordEnv) { if (-not [Environment]::GetEnvironmentVariable($v)) { Fail 1 "The environment variable $v is not set." } }
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
    # messages on the error stream are logged, not fatal (Windows PowerShell 5.1 would stop the script on them)
    $ErrorActionPreference = "Continue"
    $out = (& $ooa @a 2>&1 | ForEach-Object { "$_" } | Out-String).Trim()
    Log "> ooa $(($a | ForEach-Object { if ($_ -like 'ooareg_*') { 'ooareg_(hidden)' } else { $_ } }) -join ' ')"
    if ($out) { Log $out }
    return $LASTEXITCODE
}
# Document Automation: installs DocFlow with its own installer (windows\get-docflow.ps1 of the release this
# One CommandCenter is tested with), sets it to managed mode with a new service token, records the address and the
# token (encrypted) for One CommandCenter (ooa docflow configure) and starts it with Windows or at sign-in.
# Returns $true when it is set up; problems are logged and leave One CommandCenter as it is.
# Document Automation from an offline package (round 4, A5): the package's manifest is checked first (release and
# every file's SHA-256), then Python and Tesseract are installed from it for this program only, the packages from its
# wheels (pip --no-index), and the language data is copied. Nothing is downloaded.
function Install-DocFlowOffline($dir, $ref) {
    if (-not (Test-Path $DocFlowPackage)) { throw "The Document Automation package $DocFlowPackage is not there" }
    $work = Join-Path $env:TEMP "ooa-docflow-offline-install"
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
    Log "Unpacking $DocFlowPackage"
    Expand-Archive -Path $DocFlowPackage -DestinationPath $work
    $m = Get-Content (Join-Path $work "manifest.json") -Raw | ConvertFrom-Json
    if ($m.kind -ne "ooa-docflow-offline") { throw "$DocFlowPackage is not a Document Automation offline package (Make-DocFlowOffline.ps1 makes one)" }
    if ($m.ref -ne $ref) { throw "The package holds DocFlow $($m.ref), but this release is tested with ${ref}: make the package for $ref (Make-DocFlowOffline.ps1 -Ref $ref)" }
    foreach ($f in $m.files) {
        $full = Join-Path $work ($f.path -replace "/", "\")
        if (-not (Test-Path $full) -or (Get-FileHash $full -Algorithm SHA256).Hash.ToLower() -ne $f.sha256) { throw "The package is damaged: $($f.path) is missing or changed. Copy it again." }
    }
    Log "Package checked: DocFlow $($m.ref), Python $($m.python), $($m.tesseract), $(@($m.files).Count) files."

    # DocFlow's code (its .venv, data, tessdata and backups are kept on an update)
    New-Item -ItemType Directory -Force $dir | Out-Null
    Get-ChildItem (Join-Path $work "docflow") | ForEach-Object { Copy-Item -Recurse -Force $_.FullName (Join-Path $dir $_.Name) }

    # Python 3.12 for this program only (not on PATH, no launcher)
    $pyDir = Join-Path $InstallDir "DocFlowPython"
    $py = Join-Path $pyDir "python.exe"
    if (-not (Test-Path $py)) {
        $setup = Get-ChildItem (Join-Path $work "python") -Filter "python-*.exe" | Select-Object -First 1
        Log "Installing Python $($m.python) into $pyDir"
        $p = Start-Process $setup.FullName -ArgumentList @("/quiet", "InstallAllUsers=0", "PrependPath=0", "Include_launcher=0", "Include_test=0", "Shortcuts=0", "AssociateFiles=0", "TargetDir=`"$pyDir`"") -Wait -PassThru
        if ($p.ExitCode -ne 0 -or -not (Test-Path $py)) { throw "Python's installer stopped (exit $($p.ExitCode))" }
    }

    # Tesseract OCR beside it (its installer takes /D= as the last argument, without quotes)
    $tessDir = Join-Path $InstallDir "Tesseract-OCR"
    $tessExe = Join-Path $tessDir "tesseract.exe"
    if (-not (Test-Path $tessExe)) {
        $setup = Get-ChildItem (Join-Path $work "tesseract") -Filter "*.exe" | Select-Object -First 1
        Log "Installing Tesseract OCR into $tessDir"
        $p = Start-Process $setup.FullName -ArgumentList @("/S", "/D=$tessDir") -Wait -PassThru
        if (-not (Test-Path $tessExe)) { throw "Tesseract's installer stopped (exit $($p.ExitCode)); install for all users needs an administrator's prompt" }
    }
    $td = Join-Path $dir "tessdata"
    New-Item -ItemType Directory -Force (Join-Path $td "configs") | Out-Null
    Copy-Item -Recurse -Force (Join-Path $work "tessdata\*") $td

    # the virtual environment and every package from the wheels, without the internet
    $vpy = Join-Path $dir ".venv\Scripts\python.exe"
    if (-not (Test-Path $vpy)) { & $py -m venv (Join-Path $dir ".venv"); if ($LASTEXITCODE -ne 0) { throw "Making DocFlow's Python environment failed" } }
    $wheels = Join-Path $work "wheels"
    & $vpy -m pip install --no-index --find-links $wheels --upgrade pip --quiet
    & $vpy -m pip install --no-index --find-links $wheels -r (Join-Path $dir "requirements.txt") --quiet
    if ($LASTEXITCODE -ne 0) { throw "Installing DocFlow's packages from the package failed (pip --no-index); is the package for this DocFlow release?" }
    if ($m.paddle) { & $vpy -m pip install --no-index --find-links $wheels paddlepaddle paddleocr --quiet; if ($LASTEXITCODE -ne 0) { Log "PaddleOCR could not be installed: DocFlow works without it." } }

    # DocFlow's own settings: where Tesseract and the language data are
    $envFile = Join-Path $dir "config\local.env"
    if (-not (Test-Path $envFile)) { Copy-Item (Join-Path $dir "config\local.env.example") $envFile }
    $lines = @(Get-Content $envFile)
    foreach ($kv in @(@("TESSERACT_CMD", $tessExe), @("TESSDATA_PREFIX", $td))) {
        $found = $false
        $lines = @(foreach ($l in $lines) { if ($l -match "^\s*$($kv[0])=") { $found = $true; "$($kv[0])=$($kv[1])" } else { $l } })
        if (-not $found) { $lines += "$($kv[0])=$($kv[1])" }
    }
    Set-Content -Path $envFile -Value $lines -Encoding UTF8
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
    Log "DocFlow $ref installed from the offline package."
}

function Install-DocFlow($dataArgs) {
    try {
        $jar = Get-ChildItem (Join-Path $InstallDir "lib") -Filter "document-automation-*.jar" | Select-Object -First 1
        if (-not $jar) { throw "lib\document-automation-*.jar is missing (the Document Automation module)" }
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $zip = [IO.Compression.ZipFile]::OpenRead($jar.FullName)
        try { $r = New-Object IO.StreamReader($zip.GetEntry("docflow-compat.properties").Open()); $compat = $r.ReadToEnd(); $r.Close() } finally { $zip.Dispose() }
        $ref = [regex]::Match($compat, '(?m)^ref=(\S+)').Groups[1].Value
        if (-not $ref) { throw "docflow-compat.properties names no DocFlow release" }
        $repo = "OneOfficeautomation-Private-Limited/oneoffice-docauto"
        $dir = if ($DocFlowDir) { $DocFlowDir } else { Join-Path $InstallDir "DocFlow" }
        # its documents and database live with One CommandCenter's data, which uninstalling keeps
        $data = Join-Path $(if ($DataDir) { $DataDir } else { Join-Path $env:USERPROFILE "OOA Command Center" }) "docflow"
        if ($DocFlowPackage) {
            Log "Installing Document Automation (DocFlow $ref) into $dir from the offline package; its documents go to $data"
            Install-DocFlowOffline $dir $ref
        } else {
        Log "Installing Document Automation (DocFlow $ref) into $dir from GitHub; its documents go to $data"
        $script = Join-Path $env:TEMP "get-docflow-$ref.ps1"
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest "https://raw.githubusercontent.com/$repo/$ref/windows/get-docflow.ps1" -OutFile $script -UseBasicParsing
        $env:DOCFLOW_REPO = $repo; $env:DOCFLOW_REF = $ref; $env:DOCFLOW_DIR = $dir; $env:DOCFLOW_NO_START = "1"
        $ErrorActionPreference = "Continue"
        $out = (& powershell -NoProfile -ExecutionPolicy Bypass -File $script 2>&1 | ForEach-Object { "$_" } | Out-String).Trim()
        $code = $LASTEXITCODE
        $ErrorActionPreference = "Stop"
        Log $out
        if ($code -ne 0 -or -not (Test-Path (Join-Path $dir ".venv\Scripts\python.exe"))) { throw "DocFlow's installer stopped (exit $code); its output is above" }
        }

        # managed mode, the token both sides share, where the Command Center is, and where the documents go
        $envFile = Join-Path $dir "config\local.env"
        $lines = if (Test-Path $envFile) { @(Get-Content $envFile) } else { @() }
        $old = $lines | ForEach-Object { if ($_ -match '^\s*DOCAUTO_API_TOKEN=(.+)$') { $matches[1].Trim() } } | Select-Object -First 1
        if ($old) { $token = $old } else { $b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); $token = -join ($b | ForEach-Object { $_.ToString("x2") }) }
        $want = [ordered]@{ DOCAUTO_MANAGED = "true"; DOCAUTO_API_TOKEN = $token; DOCAUTO_PORT = "$DocFlowPort"
                            DOCAUTO_PLATFORM_URL = "http://127.0.0.1:$Port"; DOCAUTO_DATA_DIR = $data; DOCFLOW_REF = $ref }
        foreach ($k in $want.Keys) {
            $found = $false
            $lines = @(foreach ($l in $lines) { if ($l -match "^\s*$k=") { $found = $true; "$k=$($want[$k])" } else { $l } })
            if (-not $found) { $lines += "$k=$($want[$k])" }
        }
        New-Item -ItemType Directory -Force (Split-Path $envFile), $data | Out-Null
        Set-Content -Path $envFile -Value $lines -Encoding UTF8
        # the token is a secret: only administrators, the system account and the installing user may read the file
        & icacls $envFile /inheritance:r /grant:r "*S-1-5-32-544:F" "*S-1-5-18:F" "$($env:USERDOMAIN)\$($env:USERNAME):F" | Out-Null

        $env:OOA_SETUP_DOCFLOW_TOKEN = $token
        try { if ((Ooa (@("docflow", "configure", "--url", "http://127.0.0.1:$DocFlowPort", "--token-env", "OOA_SETUP_DOCFLOW_TOKEN") + $dataArgs)) -ne 0) { throw "ooa docflow configure failed" } }
        finally { Remove-Item Env:OOA_SETUP_DOCFLOW_TOKEN -ErrorAction SilentlyContinue }

        # start it with Windows (as the system account, beside the service) or when this user signs in
        $action = New-ScheduledTaskAction -Execute "powershell.exe" -Argument "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$dir\windows\start.ps1`"" -WorkingDirectory $dir
        $settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
        if ($Service) {
            $trigger = New-ScheduledTaskTrigger -AtStartup
            $principal = New-ScheduledTaskPrincipal -UserId "SYSTEM" -LogonType ServiceAccount -RunLevel Highest
        } else {
            $trigger = New-ScheduledTaskTrigger -AtLogOn -User "$($env:USERDOMAIN)\$($env:USERNAME)"
            $principal = New-ScheduledTaskPrincipal -UserId "$($env:USERDOMAIN)\$($env:USERNAME)" -LogonType Interactive
        }
        Register-ScheduledTask -TaskName "OOA DocFlow" -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Force | Out-Null
        if (-not $NoStart) { Start-ScheduledTask -TaskName "OOA DocFlow"; Log "DocFlow started (task 'OOA DocFlow')." }
        Log "Document Automation installed: DocFlow $ref on http://127.0.0.1:$DocFlowPort, managed by this One CommandCenter."
        return $true
    } catch {
        $ErrorActionPreference = "Stop"
        Log "Warning: Document Automation could not be set up: $($_.Exception.Message)"
        return $false
    }
}

if ($Role -eq "CommandCenter") {
    $dataArgs = @(); if ($DataDir) { $dataArgs = @("--data", $DataDir) }
    # prerequisites (I10)
    if (-not $SkipCheck -and -not $ConfigureOnly) {
        if ((Ooa (@("check", "--port", "$Port") + $dataArgs)) -ne 0) { Fail 4 "The prerequisite check found a problem (see $log). Fix it, or run again with -SkipCheck." }
    }
    # the database (I4): SQL Server set up here, or an existing one upgraded after an update (I7)
    if ($DbServer) {
        $a = @("db", "configure", "--engine", "sqlserver", "--host", $DbServer, "--database", $DbName, "--user", $DbUser, "--setup-user", $DbSetupUser) + $dataArgs
        if ($DbTrustCertificate) { $a += "--trust-any-certificate" }
        if ((Ooa $a) -ne 0) { Fail 2 "The database settings could not be written (see $log)." }
        if ((Ooa (@("db", "set-password", "--from-env", $DbPasswordEnv) + $dataArgs)) -ne 0) { Fail 2 "The database password could not be stored (see $log)." }
        if ((Ooa (@("db", "set-password", "--setup", "--from-env", $DbSetupPasswordEnv) + $dataArgs)) -ne 0) { Fail 2 "The setup password could not be stored (see $log)." }
        if ((Ooa (@("db", "init") + $dataArgs)) -ne 0) { Fail 2 "The database could not be created on $DbServer (see $log). Check the server name and the setup account's rights." }
        Log "One CommandCenter keeps its data in $DbName on $DbServer."
    } elseif ((Test-Path (Join-Path $(if ($DataDir) { $DataDir } else { Join-Path $env:USERPROFILE "OOA Command Center" }) "config\ooa-db.properties")) -and ((Ooa (@("db", "migrate") + $dataArgs)) -ne 0)) {
        Log "Warning: the database could not be upgraded now; the server upgrades it at its next start, or run: bin\ooa.bat db migrate"
    }
    if ($Service -and (Get-Service -Name "OOA Command Center" -ErrorAction SilentlyContinue)) {
        # an update (or the MSI run again with ROLE): the service is there already and keeps its settings
        Log "The service 'OOA Command Center' exists already; it keeps its settings. To change them: bin\ooa.bat service uninstall, then install again."
    } elseif ($Service) {
        if (-not $ListenAddress) { $ListenAddress = "0.0.0.0" }
        $a = @("service", "install", "--mode", "server", "--port", "$Port", "--host", $ListenAddress)
        if ($DataDir) { $a += @("--data", $DataDir) }
        if ($NoStart) { $a += "--no-start" }
        if ((Ooa $a) -ne 0) { Fail 2 "The Windows service could not be installed (see $log)." }
        if ($ListenAddress -notin @("127.0.0.1", "localhost")) {
            Log "The service listens on $ListenAddress, port $Port, so other machines can reach it. Set up HTTPS before they do: bin\ooa.bat tls (see docs\infrastructure\04-network-ports-proxies-and-certificates.md)."
        }
    }
    # ---------- Document Automation (optional): DocFlow from its GitHub release, beside One CommandCenter ----------
    if (-not $DocFlow -and -not $Silent -and -not $ConfigureOnly) {
        $DocFlow = [switch]((Ask "Also install Document Automation (DocFlow)? It downloads Python, Tesseract and packages from the internet (y/n)" "n") -match '^(y|yes)$')
    }
    if ($DocFlow) { $docflowOk = Install-DocFlow $dataArgs }
    Log "One CommandCenter installed. $(if ($Service) { "It runs as the Windows service 'OOA Command Center' on port $Port." } else { "Start it from the Start menu (ONEOFFICEAUTOMATION) or with START-OOA.cmd." })"
} else {
    $a = @("agent", "register", "--url", $Url, "--keep-existing")
    if ($RegistrationKey) { $a += @("--key", $RegistrationKey) }
    if ($RunnerName) { $a += @("--name", $RunnerName) }
    if ($CaFile) { $a += @("--ca", (Resolve-Path $CaFile).Path) }
    if ($Proxy) { $a += @("--proxy", $Proxy) }
    if ((Ooa $a) -ne 0) { Fail 2 "The runner could not register with $Url (see $log). Check the address, and give a registration key that is unused and not expired." }
    if ($Service) {
        if (Get-Service -Name "OOA Runner" -ErrorAction SilentlyContinue) {
            Log "The service 'OOA Runner' exists already; it keeps its settings."
        } else {
            $a = @("service", "install", "--mode", "agent"); if ($NoStart) { $a += "--no-start" }
            if ((Ooa $a) -ne 0) { Fail 2 "The runner service could not be installed (see $log)." }
        }
    } else {
        if ((Ooa @("agent", "install-task")) -ne 0) { Fail 2 "The runner could not be set to start at sign-in (see $log)." }
        if (-not $NoStart) { try { Start-ScheduledTask -TaskName "OOA Runner"; Log "Runner started." } catch { Log "Warning: start the runner by signing out and in again ($($_.Exception.Message))" } }
    }
    Log "Runner installed and registered with $Url."
}
foreach ($svc in $restart) { if ($svc) { Start-Service -Name $svc -ErrorAction SilentlyContinue; Log "Started the service $svc again" } }
if ($DocFlow -and -not $docflowOk) { Log "Done, but Document Automation is not set up (see above). One CommandCenter works without it; run setup again with -DocFlow to retry."; exit 5 }
Log "Done."
exit 0
