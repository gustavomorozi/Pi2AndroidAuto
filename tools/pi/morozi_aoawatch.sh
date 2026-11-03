#!/system/bin/sh
# Started by init at boot (see morozi.rc). Mimics what the CarlinKit phone
# role does when the car repeats the Android Open Accessory start while we
# are already in accessory mode: drop off the bus so Android leaves accessory
# mode and comes back as a normal device, letting the car redo the switch from
# scratch. Observed on the TBox Lite S with our bench head unit (it went from
# 18d1:2d00 back to 4ee1); this Android build instead stays in accessory mode
# and the CarlinKit CPC200-CCPA loops on "Accessory handshake timeout".
#
# Detection: a new "startAccessoryMode" line in the UsbDeviceManager log while
# the gadget was already in accessory mode just before it. logcat to a pipe is
# block-buffered, so poll once a second instead of streaming.
G=/config/usb_gadget/g1
UDC_NAME=1000480000.usb
LOG=/data/local/tmp/morozi_aoawatch.log

count() { logcat -d -s UsbDeviceManager 2>/dev/null | grep -c 'startAccessoryMode'; }
in_accessory() { ls -l $G/configs/b.1/ 2>/dev/null | grep -c accessory; }

seen=$(count)
was_acc=$(in_accessory)
echo "$(date +%T) up seen=$seen acc=$was_acc" >> $LOG
while true; do
  sleep 1
  now=$(count)
  if [ "$now" -gt "$seen" ]; then
    seen=$now
    if [ "$was_acc" -gt 0 ]; then
      echo "$(date +%T) repeated accessory start while in accessory mode: dropping to normal" >> $LOG
      echo '' > $G/UDC
      sleep 2
      echo $UDC_NAME > $G/UDC
    fi
  elif [ "$now" -lt "$seen" ]; then
    seen=$now
  fi
  was_acc=$(in_accessory)
done
