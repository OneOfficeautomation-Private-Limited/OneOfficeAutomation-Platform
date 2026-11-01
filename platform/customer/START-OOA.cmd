@echo off
rem ONEOFFICEAUTOMATION: starts One CommandCenter, One AgentStudio and a runner for this computer, then opens http://localhost:8720.
rem Close this window (or press Ctrl+C) to stop everything. Options are passed on, for example: START-OOA.cmd --port 8730
title ONEOFFICEAUTOMATION
call "%~dp0bin\ooa.bat" server %*
if errorlevel 1 pause
