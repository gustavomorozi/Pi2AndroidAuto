#!/usr/bin/env bash
# Installs Pi2AndroidAuto on the Pi in one go: the app (if built), the
# system files from tools/pi and the CarLink app, then reboots the Pi.
#
#   tools/pi/install.sh [--no-carlink] [pi-address]   e.g. tools/pi/install.sh 192.168.0.200
#
# CarLink (https://github.com/F-e-n-y-x/CarLink, AGPL-3.0, a separate project)
# turns the Pi into a CarPlay / Android Auto receiver for a phone plugged into
# its USB-A ports. Its latest release APK is downloaded from GitHub and checked
# against the release's SHA-256 file; nothing of it is kept in this repo.
#
# Without an address it uses the device adb already sees (USB or network).
# Needs a userdebug build (adb root). Files missing on this branch are skipped,
# so the same script works on master (V1) and v2.
set -euo pipefail

# Git Bash on Windows would turn /system/... into a Windows path.
export MSYS_NO_PATHCONV=1

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
apk="$repo/app/build/outputs/apk/debug/app-debug.apk"

carlink=1
if [ "${1:-}" = "--no-carlink" ]; then carlink=0; shift; fi

target=()
if [ $# -ge 1 ]; then
    addr="$1"
    case "$addr" in *:*) ;; *) addr="$addr:5555" ;; esac
    adb connect "$addr" >/dev/null
    target=(-s "$addr")
fi
a() { adb "${target[@]}" "$@"; }
# Local paths as adb sees them (adb.exe on Windows wants C:/..., not /c/...).
local_path() { if command -v cygpath >/dev/null; then cygpath -m "$1"; else echo "$1"; fi; }

step() { echo "==> $*"; }

step "Checking the Pi"
a wait-for-device
a shell true

if [ -f "$apk" ]; then
    step "Installing the app"
    a install -r "$(local_path "$apk")"
else
    echo "    no APK at $apk; run ./gradlew assembleDebug first to install the app too"
fi

if [ "$carlink" = 1 ]; then
    step "Installing CarLink (latest release)"
    dl="$(mktemp -d)"
    url="$(curl -fsSL https://api.github.com/repos/F-e-n-y-x/CarLink/releases/latest | grep -o 'https://[^"]*.apk' | head -1)"
    if [ -z "$url" ]; then
        echo "    could not find the CarLink APK in the latest release; skipped"
    else
        name="$(basename "$url")"
        curl -fsSL -o "$dl/$name" "$url"
        curl -fsSL -o "$dl/$name.sha256" "$url.sha256"
        if (cd "$dl" && sha256sum -c "$name.sha256" >/dev/null); then
            a install -r "$(local_path "$dl/$name")"
        else
            echo "    $name failed its SHA-256 check; not installed"
        fi
    fi
    rm -rf "$dl"
fi

step "Getting root and a writable /system"
a root >/dev/null
sleep 3
[ ${#target[@]} -gt 0 ] && adb connect "${target[1]}" >/dev/null
a wait-for-device
a remount

push() {  # push <file in tools/pi> <dest dir>
    if [ -f "$here/$1" ]; then
        sed 's/\r$//' "$here/$1" > "$tmp/$1"
        a push "$(local_path "$tmp/$1")" "$2/$1" >/dev/null 2>&1 || { echo "    failed to copy $1"; exit 1; }
        echo "    $2/$1"
    fi
}
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

step "Copying system files"
push morozi.rc /system/etc/init
for f in morozi_adbon.sh morozi_aoawatch.sh morozi_usbctl.sh morozi_screen.sh; do
    push "$f" /system/bin
done
push Vendor_18d1_Product_7a01.idc /system/usr/idc
a shell 'chmod 755 /system/bin/morozi_*.sh; sync'

step "Letting the app use the network"
a shell settings put global restricted_networking_mode 0

step "Rebooting the Pi"
a reboot
echo "Done. After boot, open Pi2AndroidAuto once on the Pi."
