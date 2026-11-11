#!/usr/bin/env bash
# Installs the Google apps (Play Store) on the Pi's Android TV, the way the
# KonstaKANG page describes: MindTheGapps from TWRP, then a factory reset.
# Called by install.sh --install-gapps; can be run alone.
#
#   tools/pi/install_gapps.sh [--yes-erase]
#
# THIS ERASES THE PI'S DATA (app settings, Wi-Fi, accounts, CarLink data).
# Internal storage (/sdcard) is kept. Afterwards, on the Pi's screen: Wi-Fi,
# Google account. Then run install.sh again to put everything back and to get
# the Google Services ID to register.
#
# Needs: the PC connected by USB to the Pi's USB-C port (TWRP has adb over USB
# only) and an HDMI monitor plugged in BEFORE the Pi boots into TWRP (without
# it TWRP has no screen and its commands hang). Verified by hand on
# 2026-11-11 (LineageOS 24 ATV 20261016, MindTheGapps 20260630); this script
# runs those same steps but was not run end to end.
set -euo pipefail
export MSYS_NO_PATHCONV=1

yes_erase=0
[ "${1:-}" = "--yes-erase" ] && yes_erase=1

local_path() { if command -v cygpath >/dev/null; then cygpath -m "$1"; else echo "$1"; fi; }
step() { echo "==> $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }
quiet() { grep -v -iE 'linker|vdso' || true; }

# The Pi must be seen over USB (a 16-hex-digit serial), not by address.
serial="$(adb devices | awk '$2=="device" && $1 ~ /^[0-9a-f]{16}$/ {print $1}' | head -1)"
[ -n "$serial" ] || die "the Pi is not on USB. Connect the PC to the Pi's USB-C port (data cable)."
a() { adb -s "$serial" "$@"; }
step "Pi on USB: $serial"

if a shell pm path com.google.android.gms >/dev/null 2>&1; then
    echo "Google apps are already installed; nothing to do."
    exit 0
fi

cat <<'EOF'

This will ERASE the Pi's data (factory reset) after installing the Google apps.
The HDMI monitor must be plugged in now, and the USB cable stays connected.
EOF
if [ "$yes_erase" != 1 ]; then
    [ -t 0 ] || die "not a terminal: pass --yes-erase to confirm."
    read -r -p "Type ERASE to continue: " answer
    [ "$answer" = "ERASE" ] || die "cancelled; nothing was changed."
fi

# --- download and check MindTheGapps (Android TV) ---------------------------
step "Downloading MindTheGapps (Android TV)"
dl="$(mktemp -d)"; trap 'rm -rf "$dl"' EXIT
curl -fsSL --retry 3 -o "$(local_path "$dl/release.json")" \
    https://api.github.com/repos/MindTheGapps/17.0.0-arm64-ATV/releases/latest
url="$(grep -o 'https://[^"]*full[^"]*\.zip"' "$dl/release.json" | head -1 | tr -d '"')"
[ -n "$url" ] || die "could not find the MindTheGapps zip in the latest release."
zip="$(basename "$url")"
curl -fsSL --retry 3 -o "$(local_path "$dl/$zip")" "$url"
curl -fsSL --retry 3 -o "$(local_path "$dl/$zip.sha256sum")" "$url.sha256sum"
(cd "$dl" && sha256sum -c "$zip.sha256sum" >/dev/null) || die "$zip failed its SHA-256 check."
a push "$(local_path "$dl/$zip")" "/sdcard/Download/$zip" >/dev/null
want="$(cut -d' ' -f1 "$dl/$zip.sha256sum")"
[ "$(a shell sha256sum "/sdcard/Download/$zip" | tr -d '\r' | cut -d' ' -f1)" = "$want" ] \
    || die "the copy on the Pi does not match the checksum."

# --- boot into TWRP ----------------------------------------------------------
# The ROM picks recovery by swapping the ramdisk in /boot (rpi-recovery.sh).
restore_boot() {  # back to the Android ramdisk and the video overlay
    a shell "mount | grep -q ' /boot ' || mount -t vfat /dev/block/by-name/boot /boot; mount -o remount,rw /boot
        if [ -f /boot/ramdisk-boot.img ]; then
            mv /boot/ramdisk.img /boot/ramdisk-recovery.img && mv /boot/ramdisk-boot.img /boot/ramdisk.img
        fi
        sed -i 's/^#dtoverlay=vc4-kms-v3d/dtoverlay=vc4-kms-v3d/' /boot/config.txt; sync" 2>&1 | quiet
}

step "Rebooting into TWRP"
a root >/dev/null 2>&1 || true; sleep 3; a wait-for-device
a shell "setprop sys.rpi.recovery recovery; sleep 6; ls /boot/ramdisk-boot.img" 2>&1 | quiet | grep -q ramdisk-boot \
    || die "the recovery ramdisk was not set up; nothing was changed in the system."
a reboot
sleep 25
for _ in $(seq 1 30); do
    adb devices | grep -qE "^$serial[[:space:]]+recovery" && break
    sleep 5
done
adb devices | grep -qE "^$serial[[:space:]]+recovery" || die "the Pi did not show up in TWRP over USB."

# TWRP needs the monitor at boot; no framebuffer means its commands would hang.
a shell "ls /dev/graphics/fb0" 2>&1 | quiet | grep -q fb0 || {
    echo "TWRP has no screen (HDMI monitor not detected at boot). Going back to Android." >&2
    restore_boot; a reboot; exit 1; }

step "Installing MindTheGapps in TWRP"
a shell "mount | grep -q ' /data ' || mount -t ext4 -o nodev,noatime,nosuid /dev/block/by-name/userdata /data" 2>&1 | quiet
log="$dl/twrp.log"
timeout 600 adb -s "$serial" shell "twrp install /data/media/0/Download/$zip" 2>&1 | quiet > "$log" || true
if ! grep -q 'Done processing script file' "$log" || ! grep -q '^Done!' "$log"; then
    echo "The Google apps install did not finish cleanly; see below. Going back to Android without a reset." >&2
    tail -n 15 "$log" >&2
    restore_boot; a reboot; exit 1
fi

step "Factory reset (internal storage is kept)"
timeout 300 adb -s "$serial" shell "twrp wipe data" 2>&1 | quiet | tail -n 3

step "Going back to Android"
restore_boot
a reboot
sleep 30
for _ in $(seq 1 60); do
    [ "$(a shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
    sleep 5
done
if a shell pm path com.google.android.gms >/dev/null 2>&1; then
    echo
    echo "Google apps installed. On the Pi's screen now: set up Wi-Fi and sign in to the Google account."
    echo "Then run tools/pi/install.sh again: it reinstalls everything and shows the Google Services ID."
else
    echo "Booted, but the Google apps are not there; check the TWRP log above." >&2
    exit 1
fi
