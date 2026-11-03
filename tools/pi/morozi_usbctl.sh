#!/system/bin/sh
# Root helper started by init (see morozi.rc) when debug.morozi.usb changes.
# Lets a plain (non-root) adb shell drive the USB gadget for tests:
#   setprop debug.morozi.usb off      -> disconnect from the host (like unplugging)
#   setprop debug.morozi.usb on       -> connect again
#   setprop debug.morozi.usb rebind   -> disconnect, wait 2 s, connect
# Each action is appended to /data/local/tmp/morozi_usb.log.
UDC_FILE=/config/usb_gadget/g1/UDC
UDC_NAME=1000480000.usb
LOG=/data/local/tmp/morozi_usb.log

action=$(getprop debug.morozi.usb)
echo "$(date +%T) $action" >> $LOG
case "$action" in
  off)    echo '' > $UDC_FILE ;;
  on)     echo $UDC_NAME > $UDC_FILE ;;
  rebind) echo '' > $UDC_FILE; sleep 2; echo $UDC_NAME > $UDC_FILE ;;
esac
setprop debug.morozi.usb idle
