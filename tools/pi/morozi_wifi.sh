#!/system/bin/sh
# Wi-Fi watchdog, started by init at boot (see morozi.rc).
#
# Android joins saved networks by itself, but it can stay stuck on a network
# that is gone or has no internet (after losing a phone hotspot, say, while the
# home Wi-Fi is in range). When the Pi has no internet for ~30 s this turns the
# Wi-Fi radio off and on, which makes Android scan again and join the best
# saved network. Passwords are never read: only the radio is toggled.
#
# - If you turned Wi-Fi off yourself, it does nothing.
# - It rejoins at most once every 90 s.
# - Log: /data/local/tmp/morozi_wifi.log
# Stop: setprop debug.morozi.wifi 0     Start: setprop debug.morozi.wifi 1
LOG=/data/local/tmp/morozi_wifi.log
INTERVAL=10     # seconds between checks
BAD_LIMIT=3     # failed checks in a row before acting
KICK_GAP=90     # seconds between two rejoin attempts

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

bad=0
last_kick=0
log "watchdog started"
while true; do
    sleep "$INTERVAL"
    case "$(cmd wifi status 2>/dev/null | head -1)" in
        *enabled*) ;;
        *) bad=0; continue ;;   # Wi-Fi is off: your choice, leave it
    esac
    if online; then
        [ "$bad" -ge "$BAD_LIMIT" ] && log "back online: $(current)"
        bad=0
        continue
    fi
    bad=$((bad + 1))
    [ "$bad" -lt "$BAD_LIMIT" ] && continue
    now="$(date +%s)"
    [ $((now - last_kick)) -lt "$KICK_GAP" ] && continue
    last_kick="$now"
    log "no internet for $bad checks ($(current)); scanning and rejoining a saved network"
    cmd wifi start-scan >/dev/null 2>&1
    svc wifi disable
    sleep 3
    svc wifi enable
    sleep 25
    log "after rejoin: $(current)"
done
