@echo off
setlocal
rem Keep cmd.exe and PowerShell builds on the same automatic JDK selection.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-all.ps1" %*
exit /b %ERRORLEVEL%
