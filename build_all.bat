@echo off
setlocal

REM Get the full path of this batch file
set "scriptPath=%~dp0"
echo Starting clean build for all platforms via "%scriptPath%gradlew.bat clean build", please wait...
%scriptPath%gradlew.bat clean build
endlocal

pause
