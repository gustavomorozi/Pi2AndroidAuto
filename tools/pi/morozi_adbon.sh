#!/system/bin/sh
# Run by init at every boot (see morozi.rc): keep USB debugging on and adb
# listening on TCP 5555, so the Pi is always reachable at 192.168.0.200:5555
# even if a setting was turned off by hand.
settings put global adb_enabled 1
setprop persist.adb.tcp.port 5555
setprop service.adb.tcp.port 5555
# V2: the accessibility helper that turns car taps into item activation in
# remote-only TV apps (TapAssistService).
settings put secure enabled_accessibility_services com.morozi.autophone/com.morozi.autophone.screen.TapAssistService
settings put secure accessibility_enabled 1
echo "$(date +%T) adb on, tcp 5555, tap assist on" >> /data/local/tmp/morozi_adbon.log
