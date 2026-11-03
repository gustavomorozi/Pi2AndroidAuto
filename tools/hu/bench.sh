#!/bin/bash
# Bench run inside WSL: switch the phone (default the TBox, 18d1:4ee1) into
# accessory mode, wait for it to come back as 18d1:2d00/2d01, run hu.py.
# usage: bench.sh [vid:pid] [seconds]
cd "$(dirname "$0")"
DEV=${1:-18d1:4ee1}
SECS=${2:-25}
for i in $(seq 1 20); do lsusb | grep -q "$DEV" && break; sleep 1; done
python3 aoa_switch.py "$DEV" || exit 1
for i in $(seq 1 30); do lsusb | grep -qE "18d1:2d0[01]" && break; sleep 1; done
lsusb | grep 18d1
sleep 1
timeout $((SECS + 30)) python3 hu.py --seconds "$SECS"
