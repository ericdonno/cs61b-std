@echo off
setlocal enabledelayedexpansion

rem Change to the directory of this script (project root) so that
rem config/, save/ and assets/ relative paths resolve correctly.
cd /d "%~dp0"

set "MAIN_CLASS=out\production\proj2\byog\Core\Main.class"
if not exist "%MAIN_CLASS%" (
    echo [play] Missing compiled main class: %MAIN_CLASS%
    echo [play] Build the project in IntelliJ first, then run this script again.
    exit /b 1
)

where java >nul 2>nul
if errorlevel 1 (
    echo [play] java not found in PATH. Install a JDK or fix PATH, then retry.
    exit /b 1
)

set "CP=out\production\proj2"
for %%j in ("..\library-sp18\javalib\*.jar") do set "CP=!CP!;%%~fj"

echo [play] Starting DungeonMind...
java -cp "%CP%" byog.Core.Main %*
exit /b %errorlevel%
