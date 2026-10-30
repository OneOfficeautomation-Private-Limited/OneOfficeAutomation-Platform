# Tests the MSI on this machine (as administrator; CI runs it on a fresh Windows machine):
#   1. msiexec /qn ROLE=CommandCenter SERVICE=1: the Command Center runs as a Windows service and answers
#   2. msiexec /fa ROLE=Runner URL=.. REGISTRATION_KEY=..: a runner service registers and comes online
#   3. msiexec /x /qn: both services, the files and the Start menu entry are gone; the data folder stays
#   powershell -ExecutionPolicy Bypass -File scripts\test-msi.ps1 -Msi cli\build\distributions\ooa-0.2.0.msi
# Exit code: the number of failed checks. Logs go to -Logs (default %TEMP%\ooa-msi-test).
param([Parameter(Mandatory = $true)] [string]$Msi, [string]$Logs = (Join-Path $env:TEMP "ooa-msi-test"), [int]$Port = 8720)
$ErrorActionPreference = "Continue"
$Msi = (Resolve-Path $Msi).Path
New-Item -ItemType Directory -Force $Logs | Out-Null
$data = "C:\OOA-msi-test-data"
$dir = Join-Path $env:ProgramFiles "ONEOFFICEAUTOMATION"
$base = "http://127.0.0.1:$Port"
$failed = 0
function Check($ok, $what) { if ($ok) { Write-Host "PASS  $what" } else { Write-Host "FAIL  $what"; $script:failed++ } }
function Health() { try { return (Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 "$base/api/health").StatusCode } catch { return 0 } }
function WaitFor([scriptblock]$cond, [int]$seconds) { for ($i = 0; $i -lt $seconds; $i += 3) { if (& $cond) { return $true }; Start-Sleep 3 }; return $false }

$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) { Write-Host "Run this as administrator."; exit 1 }
Remove-Item -Recurse -Force $data -ErrorAction SilentlyContinue

# ---------- 1. Command Center as a service ----------
$p = Start-Process msiexec.exe -Wait -PassThru -ArgumentList "/i `"$Msi`" /qn ROLE=CommandCenter SERVICE=1 PORT=$Port DATA_DIR=`"$data`" /l*v `"$Logs\install.log`""
Check ($p.ExitCode -eq 0) "msiexec installed the Command Center (exit $($p.ExitCode))"
Check (Test-Path "$dir\runtime\bin\java.exe") "the files and the bundled Java are in $dir"
Check (Test-Path "$env:ProgramData\Microsoft\Windows\Start Menu\Programs\ONEOFFICEAUTOMATION\ONEOFFICEAUTOMATION.lnk") "Start menu entry"
$svc = Get-Service "OOA Command Center" -ErrorAction SilentlyContinue
Check ($svc -and $svc.StartType -eq "Automatic") "the service OOA Command Center starts with Windows"
Check (WaitFor { (Health) -eq 200 } 150) "the Command Center answers on $base"
$image = (Get-ItemProperty "HKLM:\SYSTEM\CurrentControlSet\Services\OOA Command Center" -ErrorAction SilentlyContinue).ImagePath
Check ($image -match "--host 0\.0\.0\.0") "the service listens on every network card, not only this computer"
$ip = (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" } | Select-Object -First 1).IPAddress
if ($ip) {
    $lan = 0; try { $lan = (Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 "http://${ip}:$Port/api/health").StatusCode } catch { }
    Check ($lan -eq 200) "the Command Center answers on this computer's network address $ip"
}
# the same command again, as an update or a repeated deployment does: the existing service is kept
$p = Start-Process msiexec.exe -Wait -PassThru -ArgumentList "/i `"$Msi`" /qn REINSTALL=ALL REINSTALLMODE=omus ROLE=CommandCenter SERVICE=1 PORT=$Port DATA_DIR=`"$data`" /l*v `"$Logs\again.log`""
Check ($p.ExitCode -eq 0) "msiexec ROLE=CommandCenter a second time keeps the existing service (exit $($p.ExitCode))"
Check (WaitFor { (Health) -eq 200 } 150) "the Command Center answers after the second install"

# ---------- 2. a runner service registered with it ----------
$serviceLog = Join-Path $data "logs\service-server.log"
$pw = $null
if (Test-Path $serviceLog) { $line = (Select-String -Path $serviceLog -Pattern "one-time password\s+(\S+)" | Select-Object -Last 1); if ($line) { $pw = $line.Matches[0].Groups[1].Value } }
Check ([bool]$pw) "the first-start password is in the service log"
if ($pw) {
    try {
        $t = (Invoke-RestMethod -Method Post -Uri "$base/api/auth/login" -ContentType application/json -Body (@{ username = "admin"; password = $pw } | ConvertTo-Json)).token
        $new = "Msi-" + [guid]::NewGuid().ToString("N").Substring(0, 16) + "4c"
        Invoke-RestMethod -Method Post -Uri "$base/api/auth/password" -Headers @{ Authorization = "Bearer $t" } -ContentType application/json -Body (@{ current = $pw; new = $new } | ConvertTo-Json) | Out-Null
        $t = (Invoke-RestMethod -Method Post -Uri "$base/api/auth/login" -ContentType application/json -Body (@{ username = "admin"; password = $new } | ConvertTo-Json)).token
        $key = (Invoke-RestMethod -Method Post -Uri "$base/api/devices/registration-key" -Headers @{ Authorization = "Bearer $t"; "X-OOA-Request" = "1" } -ContentType application/json -Body '{"hours":1}').key
        # the MSI's own runner properties, as SCCM would pass them. On this machine the product is installed already, so
        # this is a reinstall (/i with REINSTALL; /f would drop the properties) that runs the set-up again.
        $p = Start-Process msiexec.exe -Wait -PassThru -ArgumentList "/i `"$Msi`" /qn REINSTALL=ALL REINSTALLMODE=omus ROLE=Runner URL=$base REGISTRATION_KEY=$key RUNNER_NAME=MSI-Test-Runner /l*v `"$Logs\runner.log`""
        Check ($p.ExitCode -eq 0) "msiexec ROLE=Runner finished (exit $($p.ExitCode))"
        Check (-not (Select-String -Path "$Logs\runner.log" -SimpleMatch "Skipping action: Configure" -Quiet)) "the MSI ran the runner set-up"
        Check (WaitFor { (Health) -eq 200 } 120) "the Command Center still answers after the reinstall"
        Check (-not (Select-String -Path "$Logs\runner.log" -SimpleMatch $key -Quiet)) "the registration key is not in the MSI log"
        Check ((Get-Service "OOA Runner" -ErrorAction SilentlyContinue).Status -eq "Running") "the service OOA Runner is running"
        $online = WaitFor { $d = (Invoke-RestMethod -Uri "$base/api/devices" -Headers @{ Authorization = "Bearer $t" }).devices | Where-Object { $_.name -eq "MSI-Test-Runner" }; $d -and $d.status -eq "online" -and $d.session_state } 90
        $d = (Invoke-RestMethod -Uri "$base/api/devices" -Headers @{ Authorization = "Bearer $t" }).devices | Where-Object { $_.name -eq "MSI-Test-Runner" }
        Check $online "the runner service is online in the Command Center (screen: $($d.session_state))"
        Check ($d.session_state -eq "no_desktop") "a runner service reports that it has no desktop"
    } catch { Check $false "runner set-up: $($_.Exception.Message)" }
}
Copy-Item $serviceLog "$Logs\service-server.log" -ErrorAction SilentlyContinue

# ---------- 3. uninstall ----------
$p = Start-Process msiexec.exe -Wait -PassThru -ArgumentList "/x `"$Msi`" /qn /l*v `"$Logs\uninstall.log`""
Check ($p.ExitCode -eq 0) "msiexec removed the product (exit $($p.ExitCode))"
Check (-not (Get-Service "OOA Command Center" -ErrorAction SilentlyContinue)) "no Command Center service left"
Check (-not (Get-Service "OOA Runner" -ErrorAction SilentlyContinue)) "no runner service left"
Check (-not (Test-Path "$dir\lib")) "the program files are gone"
Check (Test-Path "$data\logs") "the data folder is kept"
Check ((Health) -ne 200) "nothing answers on $base any more"
Copy-Item "$env:WINDIR\Temp\ooa-install.log" "$Logs\ooa-install-system.log" -ErrorAction SilentlyContinue

# ---------- 4. without DATA_DIR: the data goes to ProgramData, not the system account's profile ----------
$pd = Join-Path $env:ProgramData "OneOfficeAutomation\Command Center"
Remove-Item -Recurse -Force $pd -ErrorAction SilentlyContinue
$p = Start-Process msiexec.exe -Wait -PassThru -ArgumentList "/i `"$Msi`" /qn ROLE=CommandCenter SERVICE=1 PORT=$Port /l*v `"$Logs\install-default.log`""
Check ($p.ExitCode -eq 0) "msiexec installed without DATA_DIR (exit $($p.ExitCode))"
Check (WaitFor { (Health) -eq 200 } 150) "the Command Center answers"
Check (Test-Path (Join-Path $pd "logs")) "its data folder is $pd"
Check (-not (Test-Path "$env:WINDIR\System32\config\systemprofile\OOA Command Center")) "nothing in the system account's profile"
$p = Start-Process msiexec.exe -Wait -PassThru -ArgumentList "/x `"$Msi`" /qn /l*v `"$Logs\uninstall-default.log`""
Check ($p.ExitCode -eq 0) "removed again (exit $($p.ExitCode))"
if ($failed -eq 0) { Write-Host "ALL PASSED" } else { Write-Host "$failed CHECK(S) FAILED; logs in $Logs" }
exit $failed
