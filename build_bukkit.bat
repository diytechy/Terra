@echo off
setlocal enabledelayedexpansion

REM Get the full path of this batch file
set "scriptPath=%~dp0"

REM Check for GitHub Packages credentials
set "propsFile=%USERPROFILE%\.gradle\gradle.properties"
set "hasCreds=0"
if exist "%propsFile%" (
    set "hasUser=0"
    set "hasKey=0"
    findstr /b "gpr.user=" "%propsFile%" >nul 2>&1
    if !errorlevel! equ 0 set "hasUser=1"
    findstr /b "gpr.key=" "%propsFile%" >nul 2>&1
    if !errorlevel! equ 0 set "hasKey=1"
    if "!hasUser!"=="1" if "!hasKey!"=="1" set "hasCreds=1"
)

if "!hasCreds!"=="0" (
    echo ============================================
    echo  GitHub Packages credentials not found.
    echo  DendryTerra requires authentication to download.
    echo ============================================
    echo.
    set /p "runSetup=Run credential setup now? (Y/n): "
    if /i "!runSetup!"=="n" (
        echo.
        echo Continuing without credentials. Build may fail resolving DendryTerra.
        echo.
    ) else (
        call "%scriptPath%setup_github_packages.bat"
    )
)

echo Starting build for Bukkit (Spigot / Paper) via ^"Running %scriptPath%gradlew.bat ^:platforms^:bukkit^:build^", please wait...
%scriptPath%gradlew.bat ^:platforms^:bukkit^:build
endlocal

pause