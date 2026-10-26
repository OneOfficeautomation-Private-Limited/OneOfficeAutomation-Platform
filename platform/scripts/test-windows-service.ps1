# Tests "ooa service" on this machine: installs a throw-away Command Center service with a self-signed HTTPS
# certificate, checks it answers over HTTPS, stops it, removes it, and writes what happened to a log file.
# Run it as administrator (installing a Windows service needs that):
#   powershell -ExecutionPolicy Bypass -File scripts\test-windows-service.ps1
# Build first: gradlew :cli:installDist. Nothing is left behind except the log and the test data folder.

$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
$ooa = Join-Path $root "cli\build\install\ooa\bin\ooa.bat"
$data = Join-Path $env:TEMP "ooa-service-test"
$log = Join-Path $env:TEMP "ooa-service-test.log"
$name = "OOA Test Service"
$port = 8799
$failed = 0

function Log($m) { $line = "$(Get-Date -Format s)  $m"; Write-Host $line; Add-Content -Path $log -Value $line -Encoding utf8 }
function Ooa([string[]]$a) { $out = (& $ooa @a 2>&1 | Out-String).Trim(); Log "> ooa $($a -join ' ')"; if ($out) { Log $out }; return $LASTEXITCODE }
function Check($ok, $what) { if ($ok) { Log "PASS  $what" } else { Log "FAIL  $what"; $script:failed++ } }
function Health() { try { $c = & curl.exe -s -k -o NUL -w "%{http_code}" "https://localhost:$port/api/health" 2>$null; return "$c" } catch { return "" } }

Remove-Item $log -ErrorAction SilentlyContinue
$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) { Log "Not running as administrator. Right-click PowerShell, Run as administrator, then run this script again."; exit 1 }
if (-not (Test-Path $ooa)) { Log "Not built: $ooa. Run: gradlew :cli:installDist"; exit 1 }

if (Test-Path $data) { Remove-Item -Recurse -Force $data }
New-Item -ItemType Directory -Force $data | Out-Null
Log "Test data folder: $data"

Check ((Ooa @("tls", "self-signed", "--host", "localhost", "--data", $data)) -eq 0) "self-signed certificate made"
Check ((Ooa @("service", "install", "--mode", "control-room", "--name", $name, "--data", $data, "--port", "$port")) -eq 0) "service installed and started"

$code = ""
for ($i = 0; $i -lt 45 -and $code -ne "200"; $i++) { Start-Sleep -Seconds 2; $code = Health }
Check ($code -eq "200") "Command Center answers over HTTPS on port $port (HTTP $code)"
$svc = Get-Service -Name $name -ErrorAction SilentlyContinue
Check ($svc -and $svc.Status -eq "Running") "Windows reports the service running"
Check ($svc -and $svc.StartType -eq "Automatic") "service starts automatically with Windows"
Ooa @("service", "status", "--name", $name) | Out-Null

Check ((Ooa @("service", "stop", "--name", $name)) -eq 0) "service stopped"
Start-Sleep -Seconds 3
Check ((Health) -ne "200") "Command Center no longer answers after stop"
Check ((Ooa @("service", "start", "--name", $name)) -eq 0) "service started again"
$code = ""
for ($i = 0; $i -lt 45 -and $code -ne "200"; $i++) { Start-Sleep -Seconds 2; $code = Health }
Check ($code -eq "200") "answers again after start (HTTP $code)"

Ooa @("service", "stop", "--name", $name) | Out-Null
Start-Sleep -Seconds 3
Check ((Ooa @("service", "uninstall", "--name", $name)) -eq 0) "service removed"
Start-Sleep -Seconds 2
Check (-not (Get-Service -Name $name -ErrorAction SilentlyContinue)) "no service left behind"

$svcLog = Join-Path $data "logs\service-control-room.log"
if (Test-Path $svcLog) { Log "---- service log ----"; Get-Content $svcLog -Tail 40 | ForEach-Object { Log $_ } }

if ($failed -eq 0) { Log "ALL PASSED" } else { Log "$failed CHECK(S) FAILED" }
Log "Log written to $log"
exit $failed
