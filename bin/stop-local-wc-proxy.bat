@echo off
rem ============================================================
rem  netty-websocket-proxy  CLIENT (local SOCKS5 proxy) stopper
rem
rem  NOTE: keep this file ASCII-only. cmd.exe reads .bat in the
rem  OEM codepage (GBK on zh-CN); UTF-8 comments corrupt parsing.
rem ============================================================
setlocal EnableExtensions

rem --- Resolve the search tool by ABSOLUTE path, and never rely on a bare
rem     "find" in a pipeline. Git for Windows puts its own usr\bin ahead of
rem     System32 on PATH, and that directory ships a GNU find.exe, so when
rem     this script is launched from a Git Bash / IDE environment that
rem     inherits that PATH, "| find %PID%" becomes GNU find: it treats the
rem     PID as a file name, prints "No such file or directory" and exits 1.
rem     The exit code is the pipeline's exit code, so the script would
rem     conclude the proxy is already gone and skip the taskkill.
rem     (Microsoft's own find.exe is no better here: it demands that the
rem     search string keeps its literal double quotes, which is exactly the
rem     kind of thing that breaks as soon as the line is rewritten.)
rem     findstr takes a plain literal string - use it, fully qualified. ---
set "FINDSTR=%SystemRoot%\System32\findstr.exe"

pushd "%~dp0.."
if errorlevel 1 (
    echo [ERROR] cannot enter "%~dp0.."
    pause
    exit /b 1
)

set "PIDFILE=logs\local-proxy.pid"
rem --- The launcher keeps NO error file of its own - no launcher.err, no
rem     launcher-stop.err. Nothing in this script launches a process, so
rem     nothing could inherit a handle from it anyway; the file was only ever
rem     a place for PowerShell's own complaint to land. Dropping it removes a
rem     whole failure mode: a redirect whose target cannot be opened makes cmd
rem     skip the ENTIRE command (not just its stderr), so the PID check used
rem     to answer nothing at all and a live client was reported as gone.
rem     PowerShell errors now print on this window's console, and every
rem     failure path below "pauses", so they stay readable. ---
set "PID="
if exist "%PIDFILE%" set /p PID=<"%PIDFILE%"

rem --- Keep digits only: a hand-edited pidfile, or one saved with a BOM or a
rem     trailing CR, would never match "PID eq <junk>" and the process would
rem     look dead while it is actually running. ---
echo %PID%| "%FINDSTR%" /r /c:"^[0-9][0-9]*$" >nul
if errorlevel 1 set "PID="

set "KILLED=0"

rem --- Path 1: exact PID from the pidfile written by the start script.
rem     NOTE: cmd cannot combine "cmd && ( ... ) else ( ... )" - it fails
rem     with "else was not expected at this time", so keep the two
rem     branches flat and use goto.
rem     "The PID is still listed" is not enough to act on: Windows recycles
rem     PIDs, so it may now be a completely unrelated program. Only kill it
rem     when the command line still carries our jar name.
rem     The result comes back through a %TEMP% file, read with for /f, and the
rem     answer is deliberately 'ours' or 'no': an EMPTY answer then means "the
rem     inspection itself failed" and can never be mistaken for "not running".
rem     The older  for /f (powershell ... -Filter \"Name='javaw.exe'\" ^| ...)
rem     form puts escaped quotes and pipes inside backquotes, and that
rem     handoff silently yielded NOTHING here - a live client was reported
rem     as gone and its pidfile deleted. With single quotes only and not one
rem     pipe character, there is nothing left for cmd to mangle. ---
if not defined PID goto :try_orphans
set "INSPECT=%TEMP%\swproxy-ours.txt"
del "%INSPECT%" >nul 2>nul
powershell -NoProfile -Command "$p = Get-CimInstance Win32_Process -Filter 'ProcessId=%PID%' -ErrorAction SilentlyContinue; if ($p -and $p.CommandLine -like '*netty-websocket-proxy*') { 'ours' } else { 'no' }" > "%INSPECT%"
set "VIA="
for /f "usebackq delims=" %%A in ("%INSPECT%") do set "VIA=%%A"
del "%INSPECT%" >nul 2>nul
if not defined VIA goto :inspect_failed
if "%VIA%"=="ours" goto :kill_our_pid

echo [INFO] PID %PID% is gone, or that number now belongs to another program.
del "%PIDFILE%" >nul 2>nul
goto :try_orphans

:inspect_failed
rem --- Do NOT delete the pidfile and do NOT claim the process is gone: all we
rem     know is that we could not look. The orphan sweep below may still find
rem     it, and the [WARN] keeps the final verdict honest. ---
echo [WARN] could not inspect PID %PID% - not treating it as gone.
echo         the launcher's own error, if any, is printed above this line.
goto :try_orphans

:kill_our_pid
echo Stopping client proxy ^(PID=%PID%^) ...
taskkill /f /t /pid %PID% >nul 2>nul
if errorlevel 1 goto :kill_failed
echo Stopped.
set "KILLED=1"
rem --- wait for the process object to actually disappear, so a restart right
rem     after this can bind the listen port again. ---
powershell -NoProfile -Command "$i=0; while ($i -lt 20) { if (-not (Get-Process -Id %PID% -ErrorAction SilentlyContinue)) { exit 0 }; Start-Sleep -Milliseconds 250; $i++ }; exit 1"
if errorlevel 1 echo [WARN] PID %PID% is still winding down.
del "%PIDFILE%" >nul 2>nul
goto :try_orphans

:kill_failed
echo [WARN] failed to kill PID=%PID% - try running as Administrator
set "KILLED=failed"
del "%PIDFILE%" >nul 2>nul

:try_orphans
rem --- Path 2: fallback. NEVER "taskkill /im javaw.exe".
rem     Match the jar name inside the command line so only this
rem     project's process is affected, not IDEA or other Java apps.
rem     This also sweeps up a second copy started by hand, a client whose
rem     pidfile got lost, and any half-started zombie.
rem     Same file handoff as above, and the foreach form avoids the pipe
rem     character altogether: no escaped quotes, no pipes, no redirect on the
rem     PowerShell side - only the outer "> file" that cmd handles.
rem     The trailing 'END' line is the "I finished" marker. Without it an
rem     empty answer is ambiguous: "no orphans found" and "the scan itself
rem     blew up" produce the same empty file, and the second one must never
rem     be reported as "nothing to stop". ---
powershell -NoProfile -Command "foreach ($x in Get-CimInstance Win32_Process -ErrorAction SilentlyContinue) { if ($x.Name -eq 'javaw.exe' -and $x.CommandLine -like '*netty-websocket-proxy*') { $x.ProcessId } }; 'END'" > "%TEMP%\swproxy-orphans.txt"
set "SCAN="
for /f "usebackq delims=" %%P in ("%TEMP%\swproxy-orphans.txt") do (
    if "%%P"=="END" (
        set "SCAN=ok"
    ) else (
        echo Stopping orphan client ^(PID=%%P^) ...
        taskkill /f /t /pid %%P >nul 2>nul
        if not errorlevel 1 set "KILLED=1"
    )
)
del "%TEMP%\swproxy-orphans.txt" >nul 2>nul

:finish
rem --- "cannot inspect" must never be printed as "nothing is running". ---
if not defined SCAN echo [WARN] the process scan failed - do NOT assume nothing is running.
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
