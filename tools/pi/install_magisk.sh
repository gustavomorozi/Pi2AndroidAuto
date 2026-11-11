#!/usr/bin/env bash
# Installs Magisk (root) on the Pi's Android TV, the way the KonstaKANG page
# describes: the flashable KonstaKANG-rpi-magisk zip from TWRP, then the
# Magisk app. Called by install.sh --install-magisk; can be run alone.
#
#   tools/pi/install_magisk.sh [--yes] [path-to-KonstaKANG-rpi-magisk-v31.1.zip]
#
# Magisk does NOT wipe anything: data, accounts and settings are kept.
# The zip cannot be downloaded by a script (it is on filen.io, browser only).
# Download it by hand from https://konstakang.com/devices/rpi5/LineageOS24-ATV/
# and pass its path, or leave it in ~/Downloads or the repo's build/ folder.
# Its SHA-256 is checked before anything is copied to the Pi.
#
# Needs: the PC connected by USB to the Pi's USB-C port (TWRP has adb over USB
# only) and an HDMI monitor plugged in BEFORE the Pi boots into TWRP (without
# it TWRP has no screen and its commands hang). Same steps as install_gapps.sh;
# this script was not run end to end.
set -euo pipefail
export MSYS_NO_PATHCONV=1

zip_name="KonstaKANG-rpi-magisk-v31.1.zip"
zip_sha="c0a2c55ea224cce20c56a7dca44da41683c9734c4617a0cb0bfeea232be90180"
apk_url="https://github.com/topjohnwu/Magisk/releases/download/v31.1/Magisk-v31.1.apk"
page="https://konstakang.com/devices/rpi5/LineageOS24-ATV/"

yes=0
zip_arg=""
for arg in "$@"; do
    case "$arg" in
        --yes) yes=1 ;;
        -*) echo "unknown option $arg" >&2; exit 2 ;;
        *) zip_arg="$arg" ;;
    esac
done

local_path() { if command -v cygpath >/dev/null; then cygpath -m "$1"; else echo "$1"; fi; }
step() { echo "==> $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }
quiet() { grep -v -iE 'linker|vdso' || true; }

# --- find and check the zip (before touching the Pi) -------------------------
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
zip_path=""
if [ -n "$zip_arg" ]; then
    [ -f "$zip_arg" ] || die "file not found: $zip_arg"
    zip_path="$zip_arg"
else
    for c in "$HOME/Downloads/$zip_name" "$here/../../build/$zip_name"; do
        [ -f "$c" ] && { zip_path="$c"; break; }
    done
fi
if [ -z "$zip_path" ]; then
    cat >&2 <<EOF
ERROR: $zip_name not found.
It can only be downloaded in a browser (filen.io link):
  1. Open $page
  2. Download "$zip_name" (Magisk v31.1).
  3. Run again: tools/pi/install_magisk.sh /path/to/$zip_name
     (or leave it in ~/Downloads or the repo's build/ folder).
EOF
    exit 1
fi
got="$(sha256sum "$zip_path" | cut -d' ' -f1)"
[ "$got" = "$zip_sha" ] || die "$zip_path has SHA-256 $got, expected $zip_sha. Not using it."
step "Zip checked: $zip_path"

# The Pi must be seen over USB (a 16-hex-digit serial), not by address.
serial="$(adb devices | awk '$2=="device" && $1 ~ /^[0-9a-f]{16}$/ {print $1}' | head -1)"
[ -n "$serial" ] || die "the Pi is not on USB. Connect the PC to the Pi's USB-C port (data cable)."
a() { adb -s "$serial" "$@"; }
step "Pi on USB: $serial"

if a shell pm path com.topjohnwu.magisk >/dev/null 2>&1; then
    echo "The Magisk app is already installed; nothing to do."
    exit 0
fi

cat <<'EOF'

This will flash Magisk on the Pi through TWRP (nothing is erased) and reboot it.
The HDMI monitor must be plugged in now, and the USB cable stays connected.
EOF
if [ "$yes" != 1 ]; then
    [ -t 0 ] || die "not a terminal: pass --yes to confirm."
    read -r -p "Type YES to continue: " answer
    [ "$answer" = "YES" ] || die "cancelled; nothing was changed."
fi

dl="$(mktemp -d)"; trap 'rm -rf "$dl"' EXIT
a push "$(local_path "$zip_path")" "/sdcard/Download/$zip_name" >/dev/null
[ "$(a shell sha256sum "/sdcard/Download/$zip_name" | tr -d '\r' | cut -d' ' -f1)" = "$zip_sha" ] \
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

step "Installing Magisk in TWRP"
a shell "mount | grep -q ' /data ' || mount -t ext4 -o nodev,noatime,nosuid /dev/block/by-name/userdata /data" 2>&1 | quiet
log="$dl/twrp.log"
timeout 600 adb -s "$serial" shell "twrp install /data/media/0/Download/$zip_name" 2>&1 | quiet > "$log" || true
if ! grep -q 'Done processing script file' "$log" || ! grep -q '^Done!' "$log"; then
    echo "The Magisk install did not finish cleanly; see below. Going back to Android with no changes." >&2
    tail -n 15 "$log" >&2
    restore_boot; a reboot; exit 1
fi

step "Going back to Android"
restore_boot
a reboot
sleep 30
for _ in $(seq 1 60); do
    [ "$(a shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
    sleep 5
done

# --- the Magisk app ----------------------------------------------------------
step "Installing the Magisk app"
apk="Magisk-v31.1.apk"
curl -fsSL --retry 3 -o "$(local_path "$dl/$apk")" "$apk_url"
unzip -tq "$dl/$apk" >/dev/null || die "$apk is not a valid APK/zip."
echo "    $apk sha256: $(sha256sum "$dl/$apk" | cut -d' ' -f1)"
a install -r "$(local_path "$dl/$apk")" 2>&1 | quiet | tail -n 2

if a shell pm path com.topjohnwu.magisk >/dev/null 2>&1; then
    echo
    echo "Magisk installed. Open the Magisk app on the Pi."
    echo "Ignore any suggestion to reinstall Magisk within the app (KonstaKANG's note)."
else
    echo "Booted, but the Magisk app is not there; check the output above." >&2
    exit 1
fi
