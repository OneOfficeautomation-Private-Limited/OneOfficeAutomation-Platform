# Creates a "ONEOFFICEAUTOMATION" shortcut on your desktop that runs start-ooa.bat.
# Usage (from the platform folder):  powershell -ExecutionPolicy Bypass -File scripts\create-desktop-shortcut.ps1
$ErrorActionPreference = "Stop"
$bat = Join-Path $PSScriptRoot "start-ooa.bat"
$link = Join-Path ([Environment]::GetFolderPath("Desktop")) "ONEOFFICEAUTOMATION.lnk"
$shell = New-Object -ComObject WScript.Shell
$s = $shell.CreateShortcut($link)
$s.TargetPath = $bat
$s.WorkingDirectory = Split-Path $PSScriptRoot -Parent
$s.Description = "Start Control Room, Studio and the Bot Agent"
$icon = Join-Path (Split-Path $PSScriptRoot -Parent) "cli\src\main\resources\studio\favicon.ico"
if (Test-Path $icon) { $s.IconLocation = $icon }
$s.Save()
Write-Host "Shortcut created: $link"
