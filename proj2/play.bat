@echo off
setlocal
cd /d "%~dp0"

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0play.ps1" %*
set "PLAY_EXIT=%errorlevel%"
if not "%PLAY_EXIT%"=="0" (
    echo.
    echo [play] Startup failed. See the message above.
    pause
)
exit /b %PLAY_EXIT%
