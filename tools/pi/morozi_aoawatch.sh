#!/system/bin/sh
# Started by init at boot (see morozi.rc). Makes the Pi handle the USB link
# the way the CarlinKit TBox phone role does (measured with our bench car):
#
# 1. The car repeats the Android Open Accessory start while we are already in
#    accessory mode -> drop off the bus quickly and stay off ~6 s, so Android
#    leaves accessory mode and we come back as a normal device; the car then
#    redoes the switch from scratch. (TBox: off the bus in 0.4 s, back as a
#    normal device after ~6 s. This Android build otherwise stays in accessory
#    mode and the CPC200-CCPA loops on "Accessory handshake timeout".)
# 2. Our session ends (car gone, link error) -> same drop, so the next car
#    session starts from a clean switch (TBox: build_normal_android()). Not
#    repeated for the session end our own drop causes.
# 3. Optional (setprop debug.morozi.noadb 1): in accessory mode expose only
#    the accessory interface (18d1:2d00), like the TBox, instead of
#    accessory + adb (2d01). Off by default: it changes the gadget behind
#    Android's USB HAL.
#
# logcat to a pipe is block-buffered, so poll instead of streaming.
G=/config/usb_gadget/g1
UDC_NAME=1000480000.usb
LOG=/data/local/tmp/morozi_aoawatch.log
OFF_SECONDS=6
QUIET_AFTER_DROP=20   # seconds during which session ends are ours

starts() { logcat -d -s UsbDeviceManager 2>/dev/null | grep -c 'startAccessoryMode'; }
ends() { logcat -d -s AccessoryConnection 2>/dev/null | grep -c 'Session ended'; }
in_accessory() { ls -l $G/configs/b.1/ 2>/dev/null | grep -c accessory; }

drop() {
  echo "$(date +%T) $1: off the bus for ${OFF_SECONDS}s" >> $LOG
  echo '' > $G/UDC
  sleep $OFF_SECONDS
  echo $UDC_NAME > $G/UDC
  last_drop=$(date +%s)
}

accessory_only() {
  # Only when the gadget currently carries accessory + adb.
  [ "$(getprop debug.morozi.noadb)" = "1" ] || return
  ls -l $G/configs/b.1/ | grep -q accessory || return
  adb_link=$(ls -l $G/configs/b.1/ | grep ffs.adb | awk '{print $8}')
  [ -n "$adb_link" ] || return
  echo "$(date +%T) accessory-only: removing adb from the accessory gadget" >> $LOG
  echo '' > $G/UDC
  rm $G/configs/b.1/$adb_link
  echo 0x2d00 > $G/idProduct
  echo $UDC_NAME > $G/UDC
}

seen_starts=$(starts)
seen_ends=$(ends)
was_acc=$(in_accessory)
last_drop=0
echo "$(date +%T) up starts=$seen_starts ends=$seen_ends acc=$was_acc" >> $LOG
while true; do
  sleep 0.3
  now_starts=$(starts)
  now_ends=$(ends)
  [ "$now_starts" -lt "$seen_starts" ] && seen_starts=$now_starts   # log cleared
  [ "$now_ends" -lt "$seen_ends" ] && seen_ends=$now_ends
  if [ "$now_starts" -gt "$seen_starts" ]; then
    seen_starts=$now_starts
    if [ "$was_acc" -gt 0 ]; then
      drop "repeated accessory start while in accessory mode"
      seen_ends=$(ends)
    else
      sleep 1
      accessory_only
    fi
  elif [ "$now_ends" -gt "$seen_ends" ]; then
    seen_ends=$now_ends
    if [ $(( $(date +%s) - last_drop )) -gt $QUIET_AFTER_DROP ] && [ "$(in_accessory)" -gt 0 ]; then
      drop "session ended"
      seen_ends=$(ends)
    fi
  fi
  was_acc=$(in_accessory)
done
