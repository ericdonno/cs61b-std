@echo off
call "%~dp0play.bat" model -ProviderSmoke %*
set "SMOKE_EXIT=%errorlevel%"
if "%SMOKE_EXIT%"=="0" pause
exit /b %SMOKE_EXIT%
