#!/usr/bin/env bash
# Focused regression coverage for the Android shell lifecycle, without root.
set -eu
script="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/root/moontier_root.sh"
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT
for scenario in stale zombie reused graceful forced stuck; do
    echo 424242 > "$test_dir/manager.pid"
    SCENARIO="$scenario" bash -c '
        running=1
        kill() {
            [ "$SCENARIO" != stale ] || return 1
            [ "$running" = 1 ] || return 1
            if [ "$1" != -0 ]; then
                if [ "$SCENARIO" = graceful ] || { [ "$SCENARIO" = forced ] && [ "$1" = -9 ]; }; then
                    running=0
                fi
            fi
            return 0
        }
        awk() { if [ "$SCENARIO" = zombie ]; then echo Z; else echo S; fi; }
        readlink() { if [ "$SCENARIO" = reused ]; then echo /another/process; else echo /mock/core; fi; }
        sleep() { :; }
        . "$1" stop /mock/core "$2/manager.log" "$2/manager.pid"
    ' bash "$script" "$test_dir" && result=0 || result=$?
    if [ "$scenario" = stuck ]; then
        [ "$result" != 0 ] && [ -f "$test_dir/manager.pid" ]
    else
        [ "$result" = 0 ] && [ ! -f "$test_dir/manager.pid" ]
    fi
    echo "PASS $scenario"
done
