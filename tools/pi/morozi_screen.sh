#!/system/bin/sh
# Started by init at boot (see morozi.rc): runs the privileged screen server
# from the installed app's APK with app_process, as root. It mirrors the main
# display for the car and drives a virtual touchscreen (see ScreenServer.kt).
# Restarts it if the app is reinstalled (the APK path changes) or it exits.
LOG=/data/local/tmp/morozi_screen.log
while true; do
  APK=$(pm path com.morozi.autophone 2>/dev/null | head -1 | cut -d: -f2)
  if [ -n "$APK" ]; then
    echo "$(date +%T) starting screen server from $APK" >> $LOG
    CLASSPATH=$APK app_process /system/bin com.morozi.autophone.screen.ScreenServer >> $LOG 2>&1
    echo "$(date +%T) screen server exited ($?)" >> $LOG
  fi
  sleep 5
done
