#!/bin/sh
# =============================================================================
#  netty-websocket-proxy  SERVER (tunnel endpoint) launcher
#
#  Usage:
#      ./start-server-wc-proxy.sh                  # conf/config-example-server.json
#      ./start-server-wc-proxy.sh my-server.json   # conf/my-server.json
#      ./start-server-wc-proxy.sh /etc/server.json # absolute path
#      ./start-server-wc-proxy.sh -h               # help
#
#  Environment overrides:
#      JAVA_BIN    java executable   (default: $JAVA_HOME/bin/java, then PATH)
#      JAVA_OPTS   JVM options       (default: -Xms256m -Xmx512m)
#
#  Linux / POSIX only. The script resolves the install root from its own
#  location, so it can be run from any working directory.
# =============================================================================
set -u

# --- role-specific settings (kept in sync with start-server-wc-proxy.sh) -----
ROLE_FLAG="-s"                        # -c = local/client, -s = server
ROLE_NAME="server"
DEFAULT_CONF="config-example-server.json"
PIDFILE_NAME="server-proxy.pid"

die() { printf '[ERROR] %s\n' "$*" >&2; exit 1; }

case "${1:-}" in
    -h|--help)
        awk 'NR > 2 && /^# =/ { exit } NR > 2 { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

# --- resolve the install root from this script's real path -------------------
#     The old "basename $PWD == bin && cd ../" test only worked when the CWD
#     happened to be bin/, and silently used the wrong tree otherwise.
src="$0"
while [ -L "$src" ]; do
    dir="$(cd -P -- "$(dirname -- "$src")" && pwd)"
    src="$(readlink -- "$src")"
    case "$src" in /*) ;; *) src="$dir/$src" ;; esac
done
BIN_DIR="$(cd -P -- "$(dirname -- "$src")" && pwd)"
APP_HOME="$(cd -P -- "$BIN_DIR/.." && pwd)" || die "cannot resolve install dir"
cd -- "$APP_HOME" || die "cannot enter $APP_HOME"

# log4j2.xml writes ./logs/sw-proxy.log relative to the CWD, hence the cd above.
mkdir -p "$APP_HOME/logs" || die "cannot create $APP_HOME/logs"
LOG4J_LOG="$APP_HOME/logs/sw-proxy.log"
BOOT_LOG="$APP_HOME/logs/$ROLE_NAME-console.log"
PIDFILE="$APP_HOME/logs/$PIDFILE_NAME"

# --- config file ------------------------------------------------------------
CONF_ARG="${1:-}"
if [ -z "$CONF_ARG" ]; then
    CONF="$APP_HOME/conf/$DEFAULT_CONF"
elif [ -f "$CONF_ARG" ]; then
    CONF="$(cd -P -- "$(dirname -- "$CONF_ARG")" && pwd)/$(basename -- "$CONF_ARG")"
elif [ -f "$APP_HOME/conf/$CONF_ARG" ]; then
    CONF="$APP_HOME/conf/$CONF_ARG"
else
    printf '[ERROR] config not found: %s\n' "$CONF_ARG" >&2
    printf '        available configs:\n' >&2
    for f in "$APP_HOME"/conf/*.json; do
        [ -e "$f" ] && printf '          %s\n' "$(basename -- "$f")" >&2
    done
    exit 1
fi

# --- locate java ------------------------------------------------------------
if [ -n "${JAVA_BIN:-}" ] && [ -x "${JAVA_BIN}" ]; then
    JAVA="$JAVA_BIN"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
else
    JAVA="$(command -v java 2>/dev/null || true)"
fi
[ -n "${JAVA:-}" ] && [ -x "$JAVA" ] \
    || die "java not found. Set JAVA_HOME, JAVA_BIN, or add a JDK to PATH."

# --- locate the newest main jar --------------------------------------------
#     The version was hardcoded ("1.3.5") in the old script, so every release
#     required editing the launcher. GNU sort -V picks the highest version.
JAR="$(find "$APP_HOME/lib" -maxdepth 1 -name 'netty-websocket-proxy-*.jar' \
        ! -name '*-sources.jar' ! -name '*-javadoc.jar' 2>/dev/null \
       | sort -V | tail -n 1)"
[ -n "$JAR" ] || die "lib/netty-websocket-proxy-*.jar not found. Run 'mvn package' first."

# --- classpath --------------------------------------------------------------
#     The packaged manifest carries a Class-Path, but those entries resolve
#     relative to the *jar's own directory*: from lib/ they become lib/lib/...
#     and the JVM dies with NoClassDefFoundError and no log at all. Building
#     the classpath explicitly works regardless of layout.
CP="$JAR:$APP_HOME/lib/*"

# Fixed 512m/512m committed the whole heap up front; a small proxy does not need
# that, so keep the starting heap modest and let it grow. Override with JAVA_OPTS.
JAVA_OPTS="${JAVA_OPTS:--Xms256m -Xmx512m}"

MANUAL_CMD="$JAVA $JAVA_OPTS -cp '$CP' cn.wowspeeder.Application $ROLE_FLAG -conf '$CONF'"

# --- refuse to start a second copy -----------------------------------------
#     Never "pkill -f netty-websocket-proxy": that also matches editors,
#     greps and this script itself. Trust the pidfile, but verify the PID
#     is really our JVM before believing it (PIDs get reused).
is_ours() {
    _cmd="$(ps -p "$1" -o args= 2>/dev/null || true)"
    [ -z "$_cmd" ] && return 0          # cannot inspect -> trust the pidfile
    case "$_cmd" in *netty-websocket-proxy*) return 0 ;; *) return 1 ;; esac
}

if [ -f "$PIDFILE" ]; then
    old_pid="$(tr -dc '0-9' < "$PIDFILE" 2>/dev/null || true)"
    if [ -n "$old_pid" ] && kill -0 "$old_pid" 2>/dev/null && is_ours "$old_pid"; then
        printf '[ERROR] %s already running (PID=%s).\n' "$ROLE_NAME" "$old_pid" >&2
        printf '        stop it first:  kill %s\n' "$old_pid" >&2
        exit 1
    fi
    rm -f "$PIDFILE"          # stale pidfile from a crashed/killed run
fi

# --- launch -----------------------------------------------------------------
# shellcheck disable=SC2086  # deliberate word splitting of JAVA_OPTS
set -- $JAVA_OPTS

printf '%s service is starting ...\n' "$ROLE_NAME"
printf '   java : %s\n' "$JAVA"
printf '   jar  : %s\n' "$JAR"
printf '   conf : %s\n' "$CONF"
printf '   log  : %s\n' "$LOG4J_LOG"
printf '   boot : %s\n' "$BOOT_LOG"
printf '\n'

# Remember where this run's output starts, as BOTH a byte length and the file's
# identity (inode). The length alone is not enough: log4j2.xml rolls
# sw-proxy.log daily (TimeBasedTriggeringPolicy interval=1 modulate=true) and
# again at 50MB, and a roll renames the old file away and creates a new, empty
# one. The recorded length then points past the end of the new file, the health
# check reads nothing at all, and a healthy run gets reported as "never bound".
# A rolled file is a different inode, so the offset is dropped and the check
# starts from byte 0 - correct by definition, because a fresh file only holds
# this run's lines.
file_id() {   # inode of an existing file; empty when it does not exist
    [ -f "$1" ] || return 0
    ls -di -- "$1" 2>/dev/null | awk '{print $1; exit}'
}

log_before=0
log_before_id=""
if [ -f "$BOOT_LOG" ]; then
    log_before="$(wc -c < "$BOOT_LOG" 2>/dev/null | tr -d ' ')"
    log_before_id="$(file_id "$BOOT_LOG")"
fi
sw_log_before=0
sw_log_before_id=""
if [ -f "$LOG4J_LOG" ]; then
    sw_log_before="$(wc -c < "$LOG4J_LOG" 2>/dev/null | tr -d ' ')"
    sw_log_before_id="$(file_id "$LOG4J_LOG")"
fi
log_before="${log_before:-0}"
sw_log_before="${sw_log_before:-0}"

# stdout/stderr must NOT go to /dev/null: when log4j2.xml is missing from the
# classpath the JVM exits silently, and the old script still reported success.
nohup "$JAVA" "$@" -cp "$CP" cn.wowspeeder.Application \
      "$ROLE_FLAG" -conf "$CONF" >> "$BOOT_LOG" 2>&1 &
PID=$!

printf '%s\n' "$PID" > "$PIDFILE"

# --- health check -----------------------------------------------------------
#     Liveness alone is NOT enough. Netty's event-loop threads are non-daemon,
#     so a failed bind (port taken) or a bad config throws on main and then
#     leaves the JVM alive and idle forever - the old script happily reported
#     "started" for exactly that zombie. So require positive confirmation:
#     Application.main logs "start success!" only after every port is bound.
this_run() {   # $1 = file, $2 = byte offset before the launch, $3 = inode then
    [ -f "$1" ] || return 1
    _off="$2"
    # The offset only means something for the very file it was measured on.
    # A daily/size roll swaps the file underneath us: different inode -> the
    # offset is stale, so read from the beginning of the new file.
    if [ -n "${3:-}" ] && [ "$(file_id "$1")" != "$3" ]; then
        _off=0
    fi
    _size="$(wc -c < "$1" 2>/dev/null | tr -d ' ')"
    [ "${_size:-0}" -lt "$_off" ] && _off=0     # truncated in place
    tail -c +$(( _off + 1 )) "$1" 2>/dev/null
}

boot_failed() {
    this_run "$BOOT_LOG" "$log_before" "$log_before_id" \
        | grep -qE 'Exception in thread|BindException|ClassNotFoundException|NoClassDefFoundError|UnrecognizedOption'
}

logged_start_success() {
    this_run "$LOG4J_LOG" "$sw_log_before" "$sw_log_before_id" | grep -q 'start success'
}

started=0
for _ in 1 2 3 4 5; do
    sleep 1
    logged_start_success && { started=1; break; }
    boot_failed && break
    kill -0 "$PID" 2>/dev/null || break
done

if [ "$started" -ne 1 ]; then
    # A half-started JVM holds no port but keeps its threads alive; kill it
    # instead of leaving a zombie that the next run has to trip over.
    if kill -0 "$PID" 2>/dev/null; then
        if boot_failed; then
            kill "$PID" 2>/dev/null
        else
            # Alive but not confirmed yet - possibly just slow. Keep it.
            printf '[WARN] %s is running (PID=%s) but startup is not confirmed yet.\n' "$ROLE_NAME" "$PID" >&2
            printf '       watch: tail -f %s\n' "$LOG4J_LOG" >&2
            exit 0
        fi
    fi
    rm -f "$PIDFILE"
    printf '[ERROR] the %s failed to start (PID=%s).\n' "$ROLE_NAME" "$PID" >&2
    printf '        usual causes:\n' >&2
    printf '          - the listen port is already in use\n' >&2
    printf '          - the config file is wrong\n' >&2
    printf '          - a jar / dependency is missing from lib/\n' >&2
    # Show the headline line ("java.net.BindException: ..."), not the middle of
    # a 30-frame stack trace - the tail is unreadable and hides the cause.
    cause=""
    this_run "$BOOT_LOG" "$log_before" "$log_before_id" | grep -q . \
        && cause="$(this_run "$BOOT_LOG" "$log_before" "$log_before_id" \
                    | grep -E 'Exception|Error|error:' | head -n 5)"
    if [ -z "$cause" ]; then
        this_run "$LOG4J_LOG" "$sw_log_before" "$sw_log_before_id" | grep -q . \
            && cause="$(this_run "$LOG4J_LOG" "$sw_log_before" "$sw_log_before_id" \
                        | grep -E 'ERROR|Exception|Error' | head -n 5)"
    fi
    if [ -n "$cause" ]; then
        printf '        root cause:\n' >&2
        printf '%s\n' "$cause" | sed 's/^/          /' >&2
    fi
    printf '        full log : %s\n' "$LOG4J_LOG" >&2
    printf '        boot log : %s\n' "$BOOT_LOG" >&2
    printf '        run this to see everything:\n        %s\n' "$MANUAL_CMD" >&2
    exit 1
fi

printf '%s service is started.  (PID=%s)\n' "$ROLE_NAME" "$PID"
printf '   log  : %s\n' "$LOG4J_LOG"
printf '   stop : kill %s\n' "$PID"
exit 0
