@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
set "TARGET=windows-amd64"
set "BINARY_PATH=%SCRIPT_DIR%%TARGET%\living-sync-reporter.exe"

if not exist "%BINARY_PATH%" (
  echo Missing Living Sync reporter binary: %BINARY_PATH% 1>&2
  exit /b 1
)

"%BINARY_PATH%" %*
exit /b %ERRORLEVEL%
