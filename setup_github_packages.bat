@echo off
setlocal enabledelayedexpansion

echo ============================================
echo  Terra - GitHub Packages Credential Setup
echo ============================================
echo.
echo The DendryTerra addon is hosted on GitHub Packages,
echo which requires a GitHub Personal Access Token (PAT)
echo with the 'read:packages' scope.
echo.
echo Generate a token at:
echo   https://github.com/settings/tokens/new?scopes=read:packages
echo.

set "propsFile=%USERPROFILE%\.gradle\gradle.properties"

REM Check if .gradle directory exists
if not exist "%USERPROFILE%\.gradle" (
    echo Creating %USERPROFILE%\.gradle directory...
    mkdir "%USERPROFILE%\.gradle"
)

REM Check if credentials already exist
set "hasUser=0"
set "hasKey=0"
if exist "%propsFile%" (
    findstr /b "gpr.user=" "%propsFile%" >nul 2>&1
    if !errorlevel! equ 0 set "hasUser=1"
    findstr /b "gpr.key=" "%propsFile%" >nul 2>&1
    if !errorlevel! equ 0 set "hasKey=1"
)

if "!hasUser!"=="1" if "!hasKey!"=="1" (
    echo Credentials already configured in:
    echo   %propsFile%
    echo.
    set /p "overwrite=Overwrite existing credentials? (y/N): "
    if /i not "!overwrite!"=="y" (
        echo.
        echo Keeping existing credentials. No changes made.
        goto :end
    )
    REM Remove existing entries before re-adding
    if exist "%propsFile%" (
        set "tempFile=%propsFile%.tmp"
        (for /f "usebackq delims=" %%a in ("%propsFile%") do (
            echo %%a | findstr /b "gpr.user= gpr.key=" >nul 2>&1
            if errorlevel 1 echo %%a
        )) > "!tempFile!"
        move /y "!tempFile!" "%propsFile%" >nul
    )
)

echo.
set /p "ghUser=Enter your GitHub username: "
if "!ghUser!"=="" (
    echo Error: Username cannot be empty.
    goto :end
)

echo.
echo Enter your GitHub Personal Access Token (PAT).
echo (The token needs the 'read:packages' scope)
set /p "ghToken=Token: "
if "!ghToken!"=="" (
    echo Error: Token cannot be empty.
    goto :end
)

REM Append credentials to gradle.properties
echo.>> "%propsFile%"
echo # GitHub Packages credentials for DendryTerra>> "%propsFile%"
echo gpr.user=!ghUser!>> "%propsFile%"
echo gpr.key=!ghToken!>> "%propsFile%"

echo.
echo ============================================
echo  SUCCESS: Credentials saved to:
echo    %propsFile%
echo ============================================
echo.
echo You can now build Terra with DendryTerra support.
echo.

:end
endlocal
pause
