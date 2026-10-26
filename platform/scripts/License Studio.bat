@echo off
rem License Studio (vendor only): the page for issuing and checking ONEOFFICEAUTOMATION license files.
rem Uses the signing key in %USERPROFILE%\OOA License Keys. Close this window to stop it.
title License Studio
call "%~dp0..\cli\build\install\ooa\bin\ooa.bat" license ui %*
if errorlevel 1 pause
