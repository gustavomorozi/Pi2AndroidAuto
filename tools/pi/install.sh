#!/usr/bin/env bash
# Installs Pi2AndroidAuto on the Pi in one go: builds the app, installs it,
# the system files from tools/pi and the CarLink app, then reboots the Pi.
#
#   tools/pi/install.sh [--no-build] [--no-carlink] [--update-carlink] [pi-address]
#   e.g. tools/pi/install.sh 192.168.0.200
#
# --no-build installs the APK already built instead of building it again.
# Safe to run again: the app is updated in place (its settings are kept),
# CarLink is left alone if already installed, only changed system files are
# copied, and the Pi reboots only when one of them changed.
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

build=1
carlink=1
update_carlink=0
carlink_pkg=com.carlink.app
while [ $# -gt 0 ]; do
    case "$1" in
        --no-build) build=0 ;;
        --no-carlink) carlink=0 ;;
        --update-carlink) update_carlink=1 ;;
        -*) echo "unknown option $1" >&2; exit 2 ;;
        *) break ;;
    esac
    shift
done

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

if [ "$build" = 1 ]; then
    step "Building the app"
    (cd "$repo" && sh ./gradlew -q assembleDebug)
fi

step "Checking the Pi"
if ! timeout 60 adb "${target[@]}" wait-for-device; then
    echo "    Pi not found. Is it on, on the network (or USB), with ADB on?" >&2
    exit 1
fi
a shell true


if [ -f "$apk" ]; then
    step "Installing the app (keeps its settings)"
    # -r updates in place and keeps the app's data. Never uninstall here: that
    # would wipe the saved car settings and the TLS key.
    if ! a install -r "$(local_path "$apk")"; then
        echo "    app not updated (data kept). If the signature changed, uninstall it by hand first." >&2
        exit 1
    fi
    app_updated=1
else
    echo "    no APK at $apk; run without --no-build to build it"
    app_updated=0
fi

# CarLink updates itself from inside the app, so it is only installed when it
# is missing; reinstalling could downgrade it. --update-carlink forces it.
if [ "$carlink" = 1 ] && a shell pm path "$carlink_pkg" >/dev/null 2>&1 && [ "$update_carlink" = 0 ]; then
    step "CarLink already installed; left as it is"
elif [ "$carlink" = 1 ]; then
    step "Installing CarLink (latest release)"
    dl="$(mktemp -d)"
    url="$(curl -fsSL https://api.github.com/repos/F-e-n-y-x/CarLink/releases/latest | grep -o 'https://[^"]*.apk' | head -1)" || url=""
    if [ -z "$url" ]; then
        echo "    could not find the CarLink APK in the latest release; skipped"
    else
        name="$(basename "$url")"
        if curl -fsSL -o "$dl/$name" "$url" && curl -fsSL -o "$dl/$name.sha256" "$url.sha256" \
            && (cd "$dl" && sha256sum -c "$name.sha256" >/dev/null); then
            a install -r "$(local_path "$dl/$name")" || echo "    CarLink not installed (a newer one may be there); skipped"
        else
            echo "    $name download or SHA-256 check failed; not installed"
        fi
    fi
    rm -rf "$dl"
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# System files: only the ones that differ from what the Pi has are copied, and
# the Pi reboots only if one of them changed.
files=(morozi.rc:/system/etc/init
       morozi_adbon.sh:/system/bin morozi_aoawatch.sh:/system/bin
       morozi_usbctl.sh:/system/bin morozi_screen.sh:/system/bin
       Vendor_18d1_Product_7a01.idc:/system/usr/idc)
changed=()
step "Checking system files"
for entry in "${files[@]}"; do
    f="${entry%%:*}"; dir="${entry#*:}"
    [ -f "$here/$f" ] || continue
    sed 's/\r$//' "$here/$f" > "$tmp/$f"
    local_sum="$(sha256sum "$tmp/$f" | cut -d' ' -f1)"
    pi_sum="$(a shell "sha256sum $dir/$f 2>/dev/null || true" | cut -d' ' -f1 | tr -d '\r')"
    if [ "$local_sum" = "$pi_sum" ]; then
        echo "    $dir/$f up to date"
    else
        changed+=("$entry")
    fi
done

if [ ${#changed[@]} -gt 0 ]; then
    step "Getting root and a writable /system"
    a root >/dev/null
    sleep 3
    [ ${#target[@]} -gt 0 ] && adb connect "${target[1]}" >/dev/null
    a wait-for-device
    a remount
    step "Copying changed system files"
    for entry in "${changed[@]}"; do
        f="${entry%%:*}"; dir="${entry#*:}"
        a push "$(local_path "$tmp/$f")" "$dir/$f" >/dev/null 2>&1 || { echo "    failed to copy $f"; exit 1; }
        echo "    $dir/$f"
    done
    a shell 'chmod 755 /system/bin/morozi_*.sh; sync'
fi

if [ "$(a shell settings get global restricted_networking_mode | tr -d '\r')" != 0 ]; then
    step "Letting the app use the network"
    a shell settings put global restricted_networking_mode 0
fi

if [ ${#changed[@]} -gt 0 ]; then
    step "Rebooting the Pi (system files changed)"
    a reboot
    echo "Done. After boot, open Pi2AndroidAuto once on the Pi."
else
    if [ "$app_updated" = 1 ]; then
        # The screen server runs code from the APK; restart it to load the new one.
        step "Restarting the screen server and the app"
        a shell 'setprop debug.morozi.screen 0; sleep 1; setprop debug.morozi.screen 1; am start -n com.morozi.autophone/.MainActivity >/dev/null'
    fi
    echo "Done. No reboot needed."
fi
