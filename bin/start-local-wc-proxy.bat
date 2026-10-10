@echo off
rem ============================================================
rem  netty-websocket-proxy  CLIENT (local SOCKS5 proxy) launcher
rem  Usage: start-local-wc-proxy.bat [config-file-name]
rem         default = conf\config-example-client.json
rem
rem  NOTE: keep this file ASCII-only. cmd.exe reads .bat in the
rem  OEM codepage (GBK on zh-CN); UTF-8 comments corrupt parsing.
rem ============================================================
setlocal EnableExtensions

rem --- Switch to the distribution root. Use %~dp0, not "cd ../":
rem     "cd ../" depends on the caller's CWD and breaks on
rem     double-click or when invoked from another directory. ---
pushd "%~dp0.."
if errorlevel 1 (
    echo [ERROR] cannot enter "%~dp0.."
    pause
    exit /b 1
)

rem --- config file: 1st argument, default config-example-client.json ---
set "CONF=conf\config-example-client.json"
if not "%~1"=="" set "CONF=conf\%~1"
if not exist "%CONF%" (
    echo [ERROR] config not found: "%CD%\%CONF%"
    echo         available:
    for %%F in (conf\*.json) do echo           %%~nxF
    pause
    popd
    exit /b 1
)

rem --- locate java: %JAVA_HOME% first, then PATH, else fail loudly ---
set "JAVA_CMD="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javaw.exe" set "JAVA_CMD=%JAVA_HOME%\bin\javaw.exe"
if not defined JAVA_CMD for %%J in (javaw.exe) do set "JAVA_CMD=%%~$PATH:J"
if not defined JAVA_CMD (
    echo [ERROR] javaw.exe not found. Set JAVA_HOME or add JDK to PATH.
    pause
    popd
    exit /b 1
)

rem --- locates the main jar and, in the same pass, builds a real classpath.
rem
rem     The shipped package.xml puts dependencies in lib\ and relies on
rem     the manifest's Class-Path. That works only when the jar sits in
rem     the package root; it silently breaks (NoClassDefFoundError on
rem     commons-cli) for any other layout. So build the classpath here
rem     explicitly with "lib\*" - correct regardless of layout. ---
set "JAR="
for /f "delims=" %%F in ('dir /b /o-n "lib\netty-websocket-proxy-*.jar" 2^>nul') do if not defined JAR set "JAR=%CD%\lib\%%F"
if not defined JAR (
    echo [ERROR] lib\netty-websocket-proxy-*.jar not found. Run "mvn package" first.
    pause
    popd
    exit /b 1
)
set "CP=%JAR%;%CD%\lib\*"

rem --- IMPORTANT: never "taskkill /im javaw.exe".
rem     javaw.exe is just an image name - IDEA and every other Java GUI
rem     app share it, so that call kills unrelated user processes.
rem     Instead: if we already started one, refuse and let stop script handle it. ---
if not exist "logs" mkdir "logs"
set "PIDFILE=logs\local-proxy.pid"
set "PID="
if exist "%PIDFILE%" set /p PID=<"%PIDFILE%"
if not defined PID goto :do_start

rem --- if the recorded PID is still alive, refuse to start a second copy.
rem     (cmd rejects "cmd | find ... && ( ... ) else ( ... )", so keep the
rem     test and the branches flat.) ---
tasklist /fi "PID eq %PID%" 2>nul | find "%PID%" >nul
if not errorlevel 1 (
    echo [ERROR] client already running ^(PID=%PID%^).
    echo         run stop-local-wc-proxy.bat first to restart.
    pause
    popd
    exit /b 1
)
rem stale pidfile - drop it
del "%PIDFILE%" >nul 2>nul

:do_start
echo Client service is starting ...
echo    java : %JAVA_CMD%
echo    jar  : %JAR%
echo    conf : %CONF%
echo    log  : %CD%\logs\sw-proxy.log
echo.

rem --- Launch the client and capture its real PID, so the stop script can
rem     target exactly this process instead of the image name "javaw.exe"
rem     (which would also hit IDEA and every other Java GUI app).
rem
rem     Start-Process is enough: it hands the JVM to its own process
rem     (not a nested one), and the JVM keeps running after this script
rem     exits. A note for anyone reproducing failures here: when the
rem     launcher itself runs inside a sandbox or CI job object, Windows
rem     reaps every descendant when the job ends - the JVM will bind its
rem     port and then vanish. That is the harness, not the script. ---
set "PID="
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$p = Start-Process -FilePath '%JAVA_CMD%' -WorkingDirectory '%CD%' -WindowStyle Hidden -PassThru -ArgumentList @('-Xms256m','-Xmx512m','-cp','%CP%','cn.wowspeeder.Application','-c','-conf','%CD%\%CONF%'); $p.Id" > "%TEMP%\swproxy-pid.txt" 2>nul
set /p PID=<"%TEMP%\swproxy-pid.txt"
del "%TEMP%\swproxy-pid.txt" >nul 2>nul
set "PID=%PID: =%"

if defined PID (
    echo %PID%>"%PIDFILE%"
) else (
    echo [ERROR] failed to launch the JVM.
    echo         run this to see the actual error:
    echo         "%JAVA_CMD%" -cp "%CP%" cn.wowspeeder.Application -c -conf="%CD%\%CONF%"
    pause
    popd
    exit /b 1
)

rem --- Health check. A bad config, a busy listen port, or a jar/dependency
rem     missing from lib\ makes the JVM die on startup - and when log4j2.xml
rem     cannot be found it exits with NO output at all, so the window would
rem     otherwise claim "started" for a process that is already gone.
rem     Give it a moment, then confirm it is still alive. ---
echo Waiting for the client to come up ...
ping -n 4 127.0.0.1 >nul 2>nul

tasklist /fi "PID eq %PID%" 2>nul | find "%PID%" >nul
if errorlevel 1 (
    echo.
    echo [ERROR] the client exited right after startup ^(PID=%PID%^ is gone^).
    echo         usual causes:
    echo           - the listen port is already in use
    echo           - the config file is wrong
    echo           - a jar / dependency is missing from lib\
    echo         run this to see the actual error:
    echo         "%JAVA_CMD%" -cp "%CP%" cn.wowspeeder.Application -c -conf="%CD%\%CONF%"
    del "%PIDFILE%" >nul 2>nul
    pause
    popd
    exit /b 1
)

echo Client service is started.  ^(PID=%PID%^)
echo    log : %CD%\logs\sw-proxy.log

echo.
rem --- the old "pause >nul 2>nul" was a no-op: pause printed nothing and
rem     the window hung forever. Show a friendly countdown instead:
rem     auto-close after 5s, or press any key to close right away.
rem     NOTE: "choice" prints the message itself; do not echo it again. ---
choice /c yc /n /t 5 /d c /m "All done. Closing in 5s - press any key to close now..."
popd
endlocal
exit /b 0
