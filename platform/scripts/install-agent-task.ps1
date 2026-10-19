# Installs the ONEOFFICEAUTOMATION Bot Agent as a Windows scheduled task that starts when you sign in and restarts
# automatically if it stops. It runs in your desktop session on purpose: bots that click and type need a visible desktop.
# Run once, after "ooa agent register", from PowerShell in the platform folder:
#     powershell -ExecutionPolicy Bypass -File scripts\install-agent-task.ps1
param(
    [string]$PlatformDir = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
    [string]$TaskName = "OOA Bot Agent"
)
$ErrorActionPreference = "Stop"
Write-Host "Building the OOA command-line distribution..."
& (Join-Path $PlatformDir "gradlew.bat") -p $PlatformDir :cli:installDist -q
$ooa = Join-Path $PlatformDir "cli\build\install\ooa\bin\ooa.bat"
if (-not (Test-Path $ooa)) { throw "Build failed: $ooa not found" }
$logDir = Join-Path $env:USERPROFILE "OOA Agent Workspace\logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$action = New-ScheduledTaskAction -Execute $ooa -Argument "agent start" -WorkingDirectory (Join-Path $PlatformDir "cli\build\install\ooa")
$trigger = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERDOMAIN\$env:USERNAME"
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable `
    -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -Force `
    -Description "ONEOFFICEAUTOMATION Bot Agent: runs bots from Control Room on this device." | Out-Null
Start-ScheduledTask -TaskName $TaskName
Write-Host "Installed and started '$TaskName'. It starts automatically when you sign in."
Write-Host "Stop it:      Stop-ScheduledTask -TaskName '$TaskName'"
Write-Host "Remove it:    Unregister-ScheduledTask -TaskName '$TaskName' -Confirm:`$false"
