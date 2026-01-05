@echo off
setlocal

REM Get the full path of this batch file
set "scriptPath=%~dp0"
echo Starting build for Bukkit (Spigot / Paper) via ^"Running %scriptPath%gradlew.bat ^:platforms^:bukkit^:build^", please wait...
%scriptPath%gradlew.bat ^:platforms^:bukkit^:build
pause
endlocal