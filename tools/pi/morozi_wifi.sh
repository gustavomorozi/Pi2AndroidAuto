#!/system/bin/sh
# Wi-Fi watchdog, started by init at boot (see morozi.rc).
#
# 1. Keeps Wi-Fi power saving off. With it on, the chip dozes and idle
#    connections drop: the Google TV remote app on the phone and adb over
#    the network kept losing the Pi.
# 2. When the Pi has no internet for ~30 s AND a saved network is in range,
#    turns the Wi-Fi radio off and on, which makes Android join the best saved
#    network (after losing a phone hotspot with the home Wi-Fi in range, say).
#    With no saved network in range (in the car, usually) it only scans: on
#    2026-11-17 it toggled every 70 s for hours, which did not help.
#    Attempts back off: 90 s, 3, 6, then every 10 min. Passwords are never read.
#
# 3. Logs when a Bluetooth LE device (the remote) connects or drops, with
#    the stack's last disconnect reason, to find out why the remote is lost.
#
# - If you turned Wi-Fi off yourself, it does nothing.
# - Log: /data/local/tmp/morozi_wifi.log
# Stop: setprop debug.morozi.wifi 0     Start: setprop debug.morozi.wifi 1
LOG=/data/local/tmp/morozi_wifi.log
INTERVAL=10     # seconds between checks
BAD_LIMIT=3     # failed checks in a row before acting
GAP_START=90    # seconds before the first rejoin; doubles up to GAP_MAX
GAP_MAX=600

log() {
    echo "$(date +%T) $*" >> "$LOG"
    if [ "$(wc -c < "$LOG")" -gt 100000 ]; then
        tail -n 200 "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
    fi
}

online() {
    ping -c1 -w3 1.1.1.1 >/dev/null 2>&1 || ping -c1 -w3 8.8.8.8 >/dev/null 2>&1
}

current() {
    cmd wifi status 2>/dev/null | grep -o 'connected to "[^"]*"' | head -1
}

power_save_off() {
    case "$(iw dev wlan0 get power_save 2>/dev/null)" in
        *on*) iw dev wlan0 set power_save off 2>/dev/null && log "Wi-Fi power saving turned off" ;;
    esac
}

# Name of a saved network seen in the last scan, or nothing.
saved_in_range() {
    cmd wifi start-scan >/dev/null 2>&1
    sleep 5
    saved="$(cmd wifi list-networks 2>/dev/null | awk 'NR > 1 { $1 = ""; sub(/^ +/, ""); sub(/ +(wpa|sae|open|owe|wep|psk|eap)[^ ]*\^?$/, ""); print }' | sort -u)"
    seen="$(cmd wifi list-scan-results 2>/dev/null)"
    echo "$saved" | while IFS= read -r ssid; do
        [ -n "$ssid" ] && echo "$seen" | grep -qF " $ssid " && { echo "$ssid"; break; }
    done
}

# "Y" or "N": is the bonded Bluetooth LE device (the remote) connected?
remote_state() {
    dumpsys bluetooth_manager 2>/dev/null | grep -E '\[ *LE *\].*ACL BR/EDR:. LE:.' | grep -o 'LE:[YN]' | head -1 | cut -c4
}

bt_last=""
bt_tick=0
check_remote() {
    bt_tick=$((bt_tick + 1))
    [ $((bt_tick % 3)) -ne 0 ] && return    # every ~30 s
    state="$(remote_state)"
    [ -z "$state" ] && return               # no LE device paired
    if [ "$state" != "$bt_last" ]; then
        if [ "$state" = Y ]; then
            log "remote (Bluetooth) connected"
        else
            reason="$(dumpsys bluetooth_manager 2>/dev/null | grep -m1 -o 'disconnect_reason:0x[0-9a-f]*')"
            log "remote (Bluetooth) DISCONNECTED ($reason; screen $(dumpsys power | grep -m1 -o 'mWakefulness=[A-Za-z]*'))"
        fi
        bt_last="$state"
    fi
}

bad=0
last_kick=0
gap=$GAP_START
log "watchdog started"
while true; do
    sleep "$INTERVAL"
    check_remote
    case "$(cmd wifi status 2>/dev/null | head -1)" in
        *enabled*) ;;
        *) bad=0; continue ;;   # Wi-Fi is off: your choice, leave it
    esac
    power_save_off
    if online; then
        [ "$bad" -ge "$BAD_LIMIT" ] && log "back online: $(current)"
        bad=0
        gap=$GAP_START
        continue
    fi
    bad=$((bad + 1))
    [ "$bad" -lt "$BAD_LIMIT" ] && continue
    now="$(date +%s)"
    [ $((now - last_kick)) -lt "$gap" ] && continue
    last_kick="$now"
    target="$(saved_in_range)"
    if [ -z "$target" ]; then
        log "no internet ($(current)); no saved network in range, only scanned"
        continue
    fi
    log "no internet for $bad checks ($(current)); \"$target\" is in range: rejoining (next try in at least ${gap}s)"
    svc wifi disable
    sleep 3
    svc wifi enable
    sleep 25
    power_save_off
    log "after rejoin: $(current)"
    gap=$((gap * 2))
    [ "$gap" -gt "$GAP_MAX" ] && gap=$GAP_MAX
done
