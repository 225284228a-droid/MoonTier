#!/system/bin/sh
PATH=/data/adb/ksu/bin:/data/adb/magisk:/sbin:/system/sbin:/system/bin:/system/xbin:$PATH
export PATH

ACTION="$1"
CORE="$2"
LOG="$3"
PID_FILE="$4"
shift 4

ensure_tun() {
    mkdir -p /dev/net
    if [ ! -e /dev/net/tun ]; then
        if [ -e /dev/tun ]; then
            ln -s /dev/tun /dev/net/tun
        fi
    fi
}

alive() {
    [ -f "$PID_FILE" ] || return 1
    PID=$(cat "$PID_FILE" 2>/dev/null)
    case "$PID" in ''|*[!0-9]*) return 1 ;; esac
    [ "$PID" -gt 1 ] || return 1
    kill -0 "$PID" 2>/dev/null || return 1
    # kill -0 also succeeds for zombies and reused PIDs. Only manage our core.
    STATE=$(awk '{print $3}' "/proc/$PID/stat" 2>/dev/null)
    [ "$STATE" != Z ] && [ "$STATE" != X ] || return 1
    [ "$CORE" -ef "/proc/$PID/exe" ] && return 0
    EXE=$(readlink "/proc/$PID/exe" 2>/dev/null)
    [ "$EXE" = "$CORE" ] || [ "$EXE" = "$CORE (deleted)" ]
}

case "$ACTION" in
    start)
        ensure_tun
        chmod 755 "$CORE" 2>/dev/null
        if alive; then
            cat "$PID_FILE"
            exit 0
        fi
        rm -f "$PID_FILE"
        cd "$(dirname "$CORE")" || exit 1
        : > "$LOG"
        nohup "$CORE" "$@" >>"$LOG" 2>&1 &
        PID=$!
        echo "$PID" > "$PID_FILE"
        sleep 1
        if kill -0 "$PID" 2>/dev/null; then
            echo "$PID"
            exit 0
        fi
        cat "$LOG" 2>/dev/null
        exit 1
        ;;
    stop)
        if alive; then
            kill "$PID" 2>/dev/null
            COUNT=0
            while alive && [ "$COUNT" -lt 20 ]; do
                sleep 0.1
                COUNT=$((COUNT + 1))
            done
            if alive; then
                kill -9 "$PID" 2>/dev/null
                COUNT=0
                while alive && [ "$COUNT" -lt 20 ]; do
                    sleep 0.1
                    COUNT=$((COUNT + 1))
                done
            fi
            if alive; then
                echo "core process $PID is still running"
                exit 1
            fi
        fi
        rm -f "$PID_FILE"
        exit 0
        ;;
    status)
        if alive; then
            cat "$PID_FILE"
            exit 0
        fi
        exit 1
        ;;
esac
exit 1
