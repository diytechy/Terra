@echo off
setlocal

REM Get the full path of this batch file
set "scriptPath=%~dp0"

echo ============================================
echo Terra - Publish to Local Maven Repository
echo ============================================
echo.
echo This script publishes Terra's core modules and addons
echo to your local Maven repository (~/.m2/repository)
echo for use by BiomeTool and other dependent projects.
echo.

REM Publish core modules
echo [1/4] Publishing core API and base implementation...
call %scriptPath%gradlew.bat ^
    :common:api:publishToMavenLocal ^
    :common:implementation:base:publishToMavenLocal ^
    :common:implementation:bootstrap-addon-loader:publishToMavenLocal ^
    --no-daemon
if errorlevel 1 goto :error

REM Publish bootstrap and manifest loaders
echo.
echo [2/4] Publishing addon loaders...
call %scriptPath%gradlew.bat ^
    :common:addons:api-addon-loader:publishToMavenLocal ^
    :common:addons:manifest-addon-loader:publishToMavenLocal ^
    --no-daemon
if errorlevel 1 goto :error

REM Publish biome providers and core addons
echo.
echo [3/4] Publishing biome providers and core addons...
call %scriptPath%gradlew.bat ^
    :common:addons:biome-provider-extrusion:publishToMavenLocal ^
    :common:addons:biome-provider-image:publishToMavenLocal ^
    :common:addons:biome-provider-pipeline:publishToMavenLocal ^
    :common:addons:biome-provider-single:publishToMavenLocal ^
    :common:addons:biome-query-api:publishToMavenLocal ^
    :common:addons:chunk-generator-noise-3d:publishToMavenLocal ^
    :common:addons:language-yaml:publishToMavenLocal ^
    :common:addons:library-image:publishToMavenLocal ^
    :common:addons:pipeline-image:publishToMavenLocal ^
    --no-daemon
if errorlevel 1 goto :error

REM Publish remaining addons
echo.
echo [4/4] Publishing config and structure addons...
call %scriptPath%gradlew.bat ^
    :common:addons:command-addons:publishToMavenLocal ^
    :common:addons:command-packs:publishToMavenLocal ^
    :common:addons:command-profiler:publishToMavenLocal ^
    :common:addons:command-structures:publishToMavenLocal ^
    :common:addons:config-biome:publishToMavenLocal ^
    :common:addons:config-distributors:publishToMavenLocal ^
    :common:addons:config-feature:publishToMavenLocal ^
    :common:addons:config-flora:publishToMavenLocal ^
    :common:addons:config-locators:publishToMavenLocal ^
    :common:addons:config-noise-function:publishToMavenLocal ^
    :common:addons:config-number-predicate:publishToMavenLocal ^
    :common:addons:config-ore:publishToMavenLocal ^
    :common:addons:config-palette:publishToMavenLocal ^
    :common:addons:config-structure:publishToMavenLocal ^
    :common:addons:generation-stage-feature:publishToMavenLocal ^
    :common:addons:generation-stage-structure:publishToMavenLocal ^
    :common:addons:locator-slant-noise-3d:publishToMavenLocal ^
    :common:addons:palette-block-shortcut:publishToMavenLocal ^
    :common:addons:structure-block-shortcut:publishToMavenLocal ^
    :common:addons:structure-mutator:publishToMavenLocal ^
    :common:addons:structure-sponge-loader:publishToMavenLocal ^
    :common:addons:structure-terrascript-loader:publishToMavenLocal ^
    :common:addons:terrascript-function-check-noise-3d:publishToMavenLocal ^
    :common:addons:terrascript-function-sampler:publishToMavenLocal ^
    --no-daemon
if errorlevel 1 goto :error

echo.
echo ============================================
echo SUCCESS: All modules published to local Maven
echo ============================================
echo.
echo Artifacts are now available in:
echo   %USERPROFILE%\.m2\repository\com\dfsek\terra\
echo.
echo You can now build BiomeTool with:
echo   cd C:\Projects\BiomeTool
echo   gradlew.bat build
echo.
goto :end

:error
echo.
echo ============================================
echo ERROR: Build failed! See output above.
echo ============================================
echo.

:end
endlocal
pause
