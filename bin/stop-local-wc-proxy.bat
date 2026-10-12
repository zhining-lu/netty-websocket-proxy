@echo off
rem ============================================================
rem  netty-websocket-proxy  CLIENT (local SOCKS5 proxy) stopper
rem
rem  NOTE: keep this file ASCII-only. cmd.exe reads .bat in the
rem  OEM codepage (GBK on zh-CN); UTF-8 comments corrupt parsing.
rem ============================================================
setlocal EnableExtensions

pushd "%~dp0.."
if errorlevel 1 (
    echo [ERROR] cannot enter "%~dp0.."
    pause
    exit /b 1
)

set "PIDFILE=logs\local-proxy.pid"
set "PID="
if exist "%PIDFILE%" set /p PID=<"%PIDFILE%"

set "KILLED=0"

rem --- Path 1: exact PID from the pidfile written by the start script.
rem     NOTE: cmd cannot combine "cmd && ( ... ) else ( ... )" - it fails
rem     with "else was not expected at this time", so keep the two
rem     branches flat and use goto. ---
if not defined PID goto :try_orphans
tasklist /fi "PID eq %PID%" 2>nul | find "%PID%" >nul
if errorlevel 1 (
    echo [INFO] PID %PID% is not running any more.
    del "%PIDFILE%" >nul 2>nul
    goto :try_orphans
)

echo Stopping client proxy ^(PID=%PID%^) ...
taskkill /f /t /pid %PID% >nul 2>nul
if errorlevel 1 (
    echo [WARN] failed to kill PID=%PID% - try running as Administrator
) else (
    echo Stopped.
    set "KILLED=1"
)
del "%PIDFILE%" >nul 2>nul

:try_orphans
rem --- Path 2: fallback. NEVER "taskkill /im javaw.exe".
rem     Match the jar name inside the command line so only this
rem     project's process is affected, not IDEA or other Java apps. ---
for /f "usebackq delims=" %%P in (`powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='javaw.exe'\" ^| Where-Object { $_.CommandLine -like '*netty-websocket-proxy*' } ^| Select-Object -ExpandProperty ProcessId" 2^>nul`) do (
    echo Stopping orphan client ^(PID=%%P^) ...
    taskkill /f /t /pid %%P >nul 2>nul
    if not errorlevel 1 set "KILLED=1"
)

if "%KILLED%"=="0" echo [INFO] nothing to stop.

echo.
rem --- old script ended with a bare "pause": window blocked forever with
rem     no message. Show a friendly countdown instead: auto-close after
rem     3s, or press any key to close right away.
rem     NOTE: "choice" prints the message itself; do not echo it again. ---
choice /c yc /n /t 3 /d c /m "All done. Closing in 3s - press any key to close now..."

popd
endlocal
exit /b 0
