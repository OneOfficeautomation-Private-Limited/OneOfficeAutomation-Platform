@echo off
rem ONEOFFICEAUTOMATION setup. Double-click for a guided install, or run silently, for example:
rem   Install.cmd -Silent -Role Runner -Url https://ooa.example.com:8720 -RegistrationKey ooareg_... -AllUsers
rem All parameters: see Install-OOA.ps1 (or INSTALL.md). The log is %TEMP%\ooa-install.log.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Install-OOA.ps1" %*
set code=%errorlevel%
if not "%~1"=="" exit /b %code%
if not %code%==0 pause
exit /b %code%
