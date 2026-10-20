#!/usr/bin/env bash
# Build the watchapp. Run inside WSL/Linux with the Pebble SDK active.
set -euo pipefail
cd "$(dirname "$0")/../watchapp"

if [ -z "${PEBBLE_BIN:-}" ]; then
    if [ -x /opt/pebble-venv/bin/pebble ]; then
        PEBBLE_BIN=/opt/pebble-venv/bin/pebble
    else
        PEBBLE_BIN=pebble
    fi
fi

"$PEBBLE_BIN" build
echo "PBW: $(pwd)/build/watchapp.pbw"
