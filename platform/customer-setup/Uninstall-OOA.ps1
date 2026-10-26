<#
Removes ONEOFFICEAUTOMATION from this computer: its Windows services, the runner's sign-in task, the Start menu entries,
the Apps and Features entry and the program folder. Data is kept: the Command Center data folder, runner settings
(.ooa-agent in the user profile) and workspaces. -Silent asks nothing. Log: %TEMP%\ooa-install.log.
#>
param([switch]$Silent)
$ErrorActionPreference = "Continue"
$log = Join-Path $env:TEMP "ooa-install.log"
$dir = $PSScriptRoot
$ooa = Join-Path $dir "bin\ooa.bat"
function Log($m) { Add-Content -Path $log -Value "$(Get-Date -Format s)  $m" -Encoding utf8; if (-not $Silent) { Write-Host $m } }

if (-not $Silent) {
    $a = Read-Host "Remove ONEOFFICEAUTOMATION from $dir? Your data is kept. (y/n)"
    if ($a -notmatch '^[yY]') { exit 1 }
}
Log "Uninstalling ONEOFFICEAUTOMATION from $dir"
foreach ($svc in "OOA Command Center", "OOA Runner") {
    if (Get-Service -Name $svc -ErrorAction SilentlyContinue) {
        $out = (& $ooa service uninstall --name $svc 2>&1 | Out-String).Trim(); Log $out
    }
}
if (Get-ScheduledTask -TaskName "OOA Runner" -ErrorAction SilentlyContinue) {
    Stop-ScheduledTask -TaskName "OOA Runner" -ErrorAction SilentlyContinue
    $out = (& $ooa agent uninstall-task 2>&1 | Out-String).Trim(); Log $out
}
# a runner started at sign-in is a javaw from this folder: end it so the files can go
Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe' OR Name = 'java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith($dir, [StringComparison]::OrdinalIgnoreCase) } |
    ForEach-Object { Log "Ending $($_.Name) ($($_.ProcessId))"; Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
foreach ($menu in [Environment]::GetFolderPath("Programs"), [Environment]::GetFolderPath("CommonPrograms")) {
    Remove-Item -Recurse -Force (Join-Path $menu "ONEOFFICEAUTOMATION") -ErrorAction SilentlyContinue
}
foreach ($hive in "HKCU:", "HKLM:") {
    $key = "$hive\Software\Microsoft\Windows\CurrentVersion\Uninstall\ONEOFFICEAUTOMATION"
    if ((Test-Path $key) -and (Get-ItemProperty $key).InstallLocation -eq $dir) { Remove-Item $key -Force -ErrorAction SilentlyContinue }
}
# the folder holds this script: remove it from a helper process once this one has ended
Log "Removing $dir"
Start-Process -WindowStyle Hidden -FilePath "cmd.exe" -ArgumentList "/c ping -n 3 127.0.0.1 >nul & rmdir /s /q `"$dir`""
Log "Uninstalled. Data folders were kept."
exit 0
