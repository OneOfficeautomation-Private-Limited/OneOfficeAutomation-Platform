@echo off
rem ONEOFFICEAUTOMATION: starts Control Room, Studio and a Bot Agent for this computer in one window,
rem then opens http://localhost:8720 in your browser. Close this window (or press Ctrl+C) to stop everything.
rem Extra options are passed on, for example:  start-ooa.bat --port 8730 --workspace D:\Bots
title ONEOFFICEAUTOMATION
cd /d "%~dp0.."
call gradlew.bat :cli:run --console=plain -q --args="server %*"
if errorlevel 1 pause
