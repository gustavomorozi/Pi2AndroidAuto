#!/bin/bash
# One bench session against the CarlinKit TBox Lite S (known-good phone role)
# from Windows (Git Bash). The TBox's normal mode is bound to usbipd, so the
# AOA switch is sent from WSL; in accessory mode it comes back on Windows with
# WinUSB and hu.py talks to it natively.
# usage: trial_tbox.sh [seconds]
cd "$(dirname "$0")"
for i in 1 2 3 4 5 6; do
  usbipd attach --wsl --busid 1-1 >/dev/null 2>&1
  sleep 4
  wsl -e bash -lc "lsusb | grep -q 18d1:4ee1" && break
done
wsl -e bash -lc "cd /mnt/c/Users/gutom/.repositorios/morozi-auto-phone/tools/hu && python3 aoa_switch.py 18d1:4ee1"
sleep 4
timeout $(( ${1:-20} + 60 )) python hu.py --seconds "${1:-20}"
