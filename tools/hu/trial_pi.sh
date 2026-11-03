#!/bin/bash
# One bench session against our app on the Pi (USB-C to the PC, WinUSB driver
# on 18d1:4ee7 and 18d1:2d01 via Zadig). Re-enumerates the Pi's gadget, then
# hu.py switches it to accessory mode and plays the car. Prints the Pi's log.
# usage: trial_pi.sh [seconds] [fresh]
cd "$(dirname "$0")"
PI=192.168.0.200:5555
adb start-server >/dev/null 2>&1; adb connect $PI >/dev/null
if [ "$2" = "fresh" ]; then
  adb -s $PI shell "am force-stop com.morozi.autophone; sleep 1; am start -n com.morozi.autophone/.MainActivity >/dev/null; sleep 6"
fi
adb -s $PI shell "logcat -c; setprop debug.morozi.usb rebind"
sleep 8
adb kill-server >/dev/null 2>&1   # adb holds the Pi's USB interface otherwise
timeout $(( ${1:-20} + 60 )) python hu.py --seconds "${1:-20}" --switch 4ee7 --pid 2d01
adb start-server >/dev/null 2>&1; adb connect $PI >/dev/null
echo "--- Pi"
adb -s $PI shell "logcat -d -s AccessoryConnection AapSession VideoSource | grep -v '	at ' | grep -vE 'Caused|more\$|tls\[|Service ch' | cut -c7-170 | tail -25"
