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

rem --- Resolve the search tool by ABSOLUTE path (see the long note in
rem     stop-local-wc-proxy.bat): Git for Windows ships a GNU find.exe in
rem     usr\bin, which wins over System32 whenever PATH is inherited from a
rem     Git Bash / IDE shell. A bare "| find %PID%" then searches for a FILE
rem     named "%PID%", exits 1, and a liveness check reads "process gone" -
rem     which would drop a live pidfile and start a second instance.
rem     Used here only for the numeric pidfile check. ---
set "FINDSTR=%SystemRoot%\System32\findstr.exe"

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
rem --- The launcher keeps NO error file of its own - there is no
rem     launcher.err / launcher-start.err anywhere any more.
rem     Reason: Start-Process must pass bInheritHandles=TRUE (it has to, to
rem     hand the JVM its local-jvm.err), and that flag makes the child inherit
rem     EVERY inheritable handle the launcher holds. A handle cmd opens for
rem     "2> file" is inheritable by definition, so the JVM used to inherit
rem     that handle and keep it locked for its whole lifetime - while writing
rem     nothing into it (the file stayed 0 bytes). Any later redirect into the
rem     same path then failed with "the file is being used by another
rem     process", and cmd skips the whole command when that happens, not just
rem     its stderr - so a live client was reported as gone.
rem     With no redirect at all there is no handle to leak. A PowerShell
rem     error now lands on this window's console instead, and every failure
rem     path below ends in "pause", so it stays readable. ---
set "PID="
if exist "%PIDFILE%" set /p PID=<"%PIDFILE%"

rem --- Keep digits only. The pidfile is plain text: a hand edit, or an
rem     editor that saved it with a BOM / trailing CR, leaves junk in there,
rem     and "PID eq <junk>" then matches nothing - the live process would
rem     look dead and a second copy would start. ---
echo %PID%| "%FINDSTR%" /r /c:"^[0-9][0-9]*$" >nul
if errorlevel 1 set "PID="
if not defined PID goto :do_start

rem --- Is the recorded PID still running AND still ours? Liveness alone is
rem     not enough: Windows recycles PIDs, so after a crash that number may
rem     belong to some unrelated program - and then this script would refuse
rem     to start for no reason (and the stop script would kill the wrong
rem     process). Match the command line, like the stop script does.
rem     Only stdout is captured, into a %TEMP% file that is deleted right
rem     after: this check process exits long before any JVM is created, so
rem     nothing can inherit that handle. Deliberately answers 'ours' or 'no',
rem     so an EMPTY answer means "the inspection itself failed" and can never
rem     be mistaken for "not running". ---
set "INSPECT=%TEMP%\swproxy-ours.txt"
del "%INSPECT%" >nul 2>nul
powershell -NoProfile -ExecutionPolicy Bypass -Command "$p = Get-CimInstance Win32_Process -Filter 'ProcessId=%PID%' -ErrorAction SilentlyContinue; if ($p -and $p.CommandLine -like '*netty-websocket-proxy*') { 'ours' } else { 'no' }" > "%INSPECT%"
set "OURS="
for /f "usebackq delims=" %%A in ("%INSPECT%") do set "OURS=%%A"
del "%INSPECT%" >nul 2>nul

if "%OURS%"=="ours" (
    echo [ERROR] client already running ^(PID=%PID%^).
    echo         run stop-local-wc-proxy.bat first to restart.
    pause
    popd
    exit /b 1
)

rem --- If the inspection failed, do not pretend the process is gone: say so,
rem     otherwise the next line silently starts a second copy. ---
if not defined OURS echo [WARN] could not inspect the old PID - a previous copy may still be running.

rem stale pidfile - the process is gone, or the PID now belongs to another app
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
rem     port and then vanish. That is the harness, not the script.
rem
rem     javaw has no console, so without a redirect everything the JVM writes
rem     to stderr is thrown away - including the stack trace of a startup
rem     failure, which is the only evidence when it dies before log4j can open
rem     logs\sw-proxy.log. So stderr goes to logs\local-jvm.err.
rem     The name says who writes it: <role>-jvm.err, same family as
rem     local-proxy.pid. On Linux the same thing is logs/<role>-console.log.
rem     stdout is NOT redirected on purpose: both Console appenders in
rem     log4j2.xml are commented out, so javaw writes nothing there and the
rem     file would stay 0 bytes forever. Re-add
rem         -RedirectStandardOutput '%CD%\logs\local-jvm.out'
rem     if that appender is ever enabled again.
rem     This command line below carries NO redirection at all - not even for
rem     the PID. That is deliberate: every cmd ">" / "2>" handle on it would
rem     be inheritable, and Start-Process passes bInheritHandles=TRUE, so the
rem     JVM would inherit those handles and hold them locked for as long as it
rem     runs. So PowerShell writes the PID itself, with Set-Content, AFTER the
rem     process exists - no cmd handle is ever created, nothing to leak, and
rem     no temp file to clean up.
rem     A PowerShell error here is printed by the launcher straight onto this
rem     console, immediately above the failure message below - the window
rem     pauses on that path, so the text stays readable.
rem     No -WindowStyle here: javaw never creates a console window, and
rem     WindowStyle needs UseShellExecute=true while the redirects force it
rem     to false - asking for both is at best ignored, at worst an error. ---
set "LOGBEFORE=0"
rem --- %%~zA is empty for a file that does not exist yet, so check it before
rem     overwriting the 0 - a missing log must mean "offset 0", not "". ---
for %%A in ("logs\sw-proxy.log") do if not "%%~zA"=="" set "LOGBEFORE=%%~zA"

set "PID="
rem --- remove any stale pidfile first: its presence is how the launch below
rem     reports success, so it must not be a leftover from the previous run. ---
del "%PIDFILE%" >nul 2>nul
powershell -NoProfile -ExecutionPolicy Bypass -Command "$p = Start-Process -FilePath '%JAVA_CMD%' -WorkingDirectory '%CD%' -PassThru -RedirectStandardError '%CD%\logs\local-jvm.err' -ArgumentList @('-Xms256m','-Xmx512m','-cp','%CP%','cn.wowspeeder.Application','-c','-conf','%CD%\%CONF%'); $p.Id | Set-Content -LiteralPath '%CD%\%PIDFILE%' -Encoding ASCII"
if not exist "%PIDFILE%" goto :launch_failed
set /p PID=<"%PIDFILE%"
set "PID=%PID: =%"
rem --- digits only: this number is handed to taskkill later, so a stray
rem     character must stop here instead of turning into a wrong kill. ---
echo %PID%| "%FINDSTR%" /r /c:"^[0-9][0-9]*$" >nul
if errorlevel 1 goto :launch_failed
goto :launched

:launch_failed
del "%PIDFILE%" >nul 2>nul
echo [ERROR] failed to launch the JVM.
echo         any message the launcher printed appears just above this line.
echo         run this to see the actual error:
echo         "%JAVA_CMD%" -cp "%CP%" cn.wowspeeder.Application -c -conf="%CD%\%CONF%"
pause
popd
exit /b 1

:launched

rem --- Health check. "The process is still running" is NOT the same thing as
rem     "the proxy is up": Netty's event-loop threads are non-daemon, so when
rem     Application.main throws (port already bound, bad config, missing
rem     dependency) the JVM stays alive and idle forever - a zombie. The old
rem     check reported "started" for exactly that zombie.
rem     Application.main only logs "start success!" once every port is bound,
rem     so that line is the positive confirmation - but only the part of
rem     logs\sw-proxy.log written by THIS run counts (the file is append-only
rem     and shared by every restart, so %LOGBEFORE% above is the offset).
rem     The polling lives in one PowerShell call because it has to watch three
rem     things at once; it reports back with a single status token:
rem       0 = "start success!" seen        -> really up
rem       1 = alive, but no success line   -> zombie, never bound
rem       2 = process disappeared          -> died while starting
rem       3 = JVM wrote to its stderr      -> threw during startup
rem     The log is read byte-wise and decoded as ASCII on purpose: the file is
rem     GBK on a zh-CN box, so a character offset would not line up with
rem     %LOGBEFORE% (a byte count), while the keyword itself is pure ASCII.
rem     Paths are built from $PWD.Path instead of being left relative: the
rem     .NET file API resolves relative names against the process CWD, while
rem     Test-Path / Get-Item resolve them against the PowerShell location.
rem     Those two are the same only by luck, and a mismatch means silently
rem     reading some other file. ---
echo Waiting for the client to come up ...
set "HC="
powershell -NoProfile -ExecutionPolicy Bypass -Command "$d=$PWD.Path; $log=Join-Path $d 'logs\sw-proxy.log'; $err=Join-Path $d 'logs\local-jvm.err'; $off=%LOGBEFORE%; $r='1'; for($i=0; $i -lt 20; $i++){ if(-not (Get-Process -Id %PID% -ErrorAction SilentlyContinue)){ $r='2'; break }; $new=''; if(Test-Path $log){ $fs=[IO.File]::Open($log,'Open','Read','ReadWrite'); try{ $len=$fs.Length-$off; if($len -gt 0){ $fs.Position=$off; $buf=New-Object byte[] $len; $n=$fs.Read($buf,0,$len); $new=[System.Text.Encoding]::ASCII.GetString($buf,0,$n) } } finally{ $fs.Close() } }; if($new -match 'start success'){ $r='0'; break }; if((Test-Path $err) -and ((Get-Item $err).Length -gt 0)){ $r='3'; break }; Start-Sleep -Milliseconds 500 }; $r" > "%TEMP%\swproxy-hc.txt" 2>nul
set /p HC=<"%TEMP%\swproxy-hc.txt"
del "%TEMP%\swproxy-hc.txt" >nul 2>nul

if "%HC%"=="0" goto :up
set "REASON=startup failed, and the launcher returned no status"
if "%HC%"=="1" set "REASON=the JVM is alive but never logged 'start success!' - it did not bind its port"
if "%HC%"=="2" set "REASON=the JVM exited before it finished starting"
if "%HC%"=="3" set "REASON=the JVM threw an exception during startup"
goto :down

:up
echo Client service is started.  ^(PID=%PID%^)
echo    log : %CD%\logs\sw-proxy.log

echo.
rem --- the old "pause >nul 2>nul" was a no-op: pause printed nothing and
rem     the window hung forever. Show a friendly countdown instead:
rem     auto-close after 3s, or press any key to close right away.
rem     NOTE: "choice" prints the message itself; do not echo it again. ---
choice /c yc /n /t 3 /d c /m "All done. Closing in 3s - press any key to close now..."
popd
endlocal
exit /b 0

:down
echo.
echo [ERROR] %REASON%.
echo         usual causes:
echo           - the listen port is already in use
echo           - the config file is wrong
echo           - a jar / dependency is missing from lib\
rem --- kill the half-started JVM first: it holds no port but keeps its
rem     non-daemon threads alive, and it still owns logs\local-jvm.err, so the
rem     file has to be released before it can be read back. ---
taskkill /f /t /pid %PID% >nul 2>nul
ping -n 2 127.0.0.1 >nul 2>nul
del "%PIDFILE%" >nul 2>nul
echo.
echo --- last lines of logs\local-jvm.err (JVM stderr) ---
powershell -NoProfile -Command "$c=Get-Content 'logs\local-jvm.err' -Encoding Default -ErrorAction SilentlyContinue; if ($c) { $c | Select-Object -Last 15 } else { '           (empty or missing)' }"
echo --- last lines of logs\sw-proxy.log ---
powershell -NoProfile -Command "$c=Get-Content 'logs\sw-proxy.log' -Encoding Default -ErrorAction SilentlyContinue; if ($c) { $c | Select-Object -Last 12 } else { '           (empty or missing)' }"
echo.
echo         if both are empty, run this by hand to see the real error:
echo         "%JAVA_CMD%" -cp "%CP%" cn.wowspeeder.Application -c -conf="%CD%\%CONF%"
pause
popd
exit /b 1
