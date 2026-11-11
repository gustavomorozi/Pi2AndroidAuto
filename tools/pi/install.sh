#!/usr/bin/env bash
# Installs Pi2AndroidAuto on the Pi in one go: builds the app, installs it,
# the system files from tools/pi and the CarLink app, then reboots the Pi.
#
#   tools/pi/install.sh [--no-build] [--no-carlink] [--no-smarttube] [--no-keyboard] [--update-carlink] [--install-gapps] [--install-magisk [zip]] [pi-address]
#   e.g. tools/pi/install.sh 192.168.0.200
#
# --no-build installs the APK already built instead of building it again.
# --install-gapps first installs the Google apps (Play Store) through TWRP and
# factory-resets the Pi: see install_gapps.sh (asks you to type ERASE). Run
# this script again afterwards to put everything back and get the Google ID.
# --install-magisk [zip] flashes Magisk (root) through TWRP, no data erased:
# see install_magisk.sh (needs the KonstaKANG zip downloaded by hand; asks YES).
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
smarttube=1
keyboard=1
gapps=0
magisk=0
magisk_zip=""
carlink_pkg=com.carlink.app
while [ $# -gt 0 ]; do
    case "$1" in
        --no-build) build=0 ;;
        --no-carlink) carlink=0 ;;
        --update-carlink) update_carlink=1 ;;
        --no-smarttube) smarttube=0 ;;
        --no-keyboard) keyboard=0 ;;
        --install-gapps) gapps=1 ;;
        --install-magisk)
            magisk=1
            # optional zip path: next argument if it is an existing file
            if [ -n "${2:-}" ] && [ -f "$2" ]; then magisk_zip="$2"; shift; fi ;;
        -*) echo "unknown option $1" >&2; exit 2 ;;
        *) break ;;
    esac
    shift
done

addr=""
target=()
if [ $# -ge 1 ]; then
    addr="$1"
    case "$addr" in *:*) ;; *) addr="$addr:5555" ;; esac
    adb connect "$addr" >/dev/null
    target=(-s "$addr")
elif [ -n "${ANDROID_SERIAL:-}" ]; then
    :  # adb picks that device by itself
else
    # No address given: use the Pi on USB, else the address used last time.
    usb="$(adb devices | awk '$2=="device" && $1 ~ /^[0-9a-f]{16}$/ {print $1}' | head -1)"
    last="$(cat "$HOME/.pi2androidauto_addr" 2>/dev/null || true)"
    if [ -n "$usb" ]; then
        target=(-s "$usb")
    elif [ -n "$last" ]; then
        addr="$last"
        adb connect "$addr" >/dev/null 2>&1 || true
        target=(-s "$addr")
        echo "Using the address from last time: $addr"
    fi
fi
a() { adb "${target[@]}" "$@"; }
# Local paths as adb sees them (adb.exe on Windows wants C:/..., not /c/...).
local_path() { if command -v cygpath >/dev/null; then cygpath -m "$1"; else echo "$1"; fi; }

step() { echo "==> $*"; }

# Remembers the Pi's Wi-Fi/Ethernet address for next time and shows it.
remember_pi() {
    local ip
    ip="$(a shell "ip -4 -o addr show scope global | awk '{print \$4}' | cut -d/ -f1 | head -1" 2>/dev/null | tr -d '\r' | head -1 || true)"
    if [ -n "$ip" ]; then
        echo "$ip:5555" > "$HOME/.pi2androidauto_addr"
        step "Pi address: $ip   (adb connect $ip:5555, ssh root@$ip)"
    fi
}

# Root for reading /data (the Pi has no su). Returns 1 and says why when the
# Pi's "Rooted debugging" option (Settings -> System -> Developer options) is off.
rooted=0
ensure_root() {
    [ "$rooted" = 1 ] && return 0
    local out
    out="$(a root 2>&1 || true)"
    case "$out" in
        *disabled*) echo "    adb root is off on the Pi: turn on Settings -> System -> Developer options -> Rooted debugging." >&2
                    return 1 ;;
    esac
    sleep 3
    if [ ${#target[@]} -gt 0 ]; then adb connect "${target[1]}" >/dev/null 2>&1 || true; fi
    a wait-for-device
    rooted=1
}

# SSH is on at every boot (morozi_adbon.sh). Its login key is the Pi's own
# ed25519 key; it is copied once to ~/.ssh/pi2androidauto_key (needs adb root),
# and the command to use it is shown.
ssh_hint() {
    local key="$HOME/.ssh/pi2androidauto_key" host="${addr%%:*}"
    if [ ! -f "$key" ] && ensure_root; then
        mkdir -p "$HOME/.ssh"
        if a shell "cat /data/ssh/ssh_host_ed25519_key" 2>/dev/null | tr -d '\r' > "$key.tmp" \
            && grep -q 'BEGIN OPENSSH PRIVATE KEY' "$key.tmp"; then
            mv "$key.tmp" "$key"
            chmod 600 "$key"
        else
            rm -f "$key.tmp"
        fi
    fi
    step "SSH"
    if [ -f "$key" ]; then
        echo "    ssh -i ~/.ssh/pi2androidauto_key root@${host:-<ip-do-pi>}"
    else
        echo "    key not copied yet (needs adb root, and the Pi creates it on its first boot with SSH on)."
    fi
}

# With Google apps installed, shows the Pi's Google Services Framework ID: the
# one to register at https://www.google.com/android/uncertified (signed in with
# the same Google account as on the Pi) when Play Store says "not certified".
# Registering needs your Google account, so it is not done from here.
gsf_id_hint() {
    a shell pm path com.google.android.gms >/dev/null 2>&1 || return 0
    local id="" db
    step "Google Services ID of this Pi (for Play Store certification)"
    if ensure_root; then
        for db in com.google.android.gsf com.google.android.gms; do
            id="$(a shell "sqlite3 -readonly /data/user/0/$db/databases/gservices.db \"SELECT value FROM main WHERE name='android_id';\" 2>/dev/null || true" | tr -d '\r' | head -1 || true)"
            [ -n "$id" ] && break
        done
    fi
    if [ -n "$id" ]; then
        echo "    ID (decimal): $id"
        echo "    ID (hex, what the Google page asks for): $(printf '%016x' "$id")"
        echo "    Register it at https://www.google.com/android/uncertified with the Google account signed in on the Pi."
    else
        echo "    not available yet: needs adb root on the Pi, internet and the Google account signed in."
        echo "    Fix that on the Pi, then run this script again: it shows the ID."
    fi
}

if [ "$gapps" = 1 ]; then
    bash "$here/install_gapps.sh"
    exit 0
fi

if [ "$magisk" = 1 ]; then
    bash "$here/install_magisk.sh" ${magisk_zip:+"$magisk_zip"}
    exit 0
fi

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

# Android TV itself is only checked, never replaced from here: updating it
# flashes the system and needs the Pi's recovery (TWRP) or its Updater app.
step "Checking the Android TV build"
os_page="https://konstakang.com/devices/rpi5/LineageOS24-ATV/"
installed_os="$(a shell getprop ro.lineage.version | tr -d '
' | grep -o '[0-9]\{8\}' | head -1 || true)"
latest_os="$(curl -fsL -m 15 "$os_page" 2>/dev/null | grep -o 'lineage-[0-9.]*-[0-9]\{8\}-UNOFFICIAL-KonstaKANG-rpi5-atv.zip' | grep -o '[0-9]\{8\}' | sort -u | tail -1 || true)"
if [ -z "$installed_os" ] || [ -z "$latest_os" ]; then
    echo "    could not compare builds (Pi: ${installed_os:-?}, site: ${latest_os:-?}); skipped"
elif [ "$latest_os" -gt "$installed_os" ]; then
    echo "    NEWER Android TV build: $latest_os (Pi has $installed_os)."
    echo "    Update by hand, it flashes the system: $os_page"
    echo "    (TWRP OTA zip or the Updater app). Run this script again afterwards:"
    echo "    it puts the Pi2AndroidAuto system files back."
else
    echo "    up to date ($installed_os)"
fi


if [ -f "$apk" ]; then
    step "Installing the app (keeps its settings)"
    # -r updates in place and keeps the app's data. Never uninstall here: that
    # would wipe the saved car settings and the TLS key.
    if ! a install -r "$(local_path "$apk")"; then
        echo "    app not updated (data kept). If the signature changed, uninstall it by hand first." >&2
        exit 1
    fi
    app_updated=1
    # V3: lets the floating button's "Abas" panel order apps by recent use.
    a shell appops set com.morozi.autophone GET_USAGE_STATS allow || true
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
    # Saved to a file first: piping curl into head can cut it off mid-write.
    url=""
    if curl -fsSL --retry 3 -o "$(local_path "$dl/release.json")" https://api.github.com/repos/F-e-n-y-x/CarLink/releases/latest; then
        url="$(grep -o 'https://[^"]*\.apk"' "$dl/release.json" | head -1 | tr -d '"')" || url=""
    fi
    if [ -z "$url" ]; then
        echo "    could not find the CarLink APK in the latest release; skipped"
    else
        name="$(basename "$url")"
        if curl -fsSL --retry 3 -o "$(local_path "$dl/$name")" "$url" && curl -fsSL --retry 3 -o "$(local_path "$dl/$name.sha256")" "$url.sha256" \
            && (cd "$dl" && sha256sum -c "$name.sha256" >/dev/null); then
            a install -r "$(local_path "$dl/$name")" || echo "    CarLink not installed (a newer one may be there); skipped"
        else
            echo "    $name download or SHA-256 check failed; not installed"
        fi
    fi
    rm -rf "$dl"
fi

# SmartTube (https://github.com/yuliskov/SmartTube, open-source YouTube client
# for Android TV): latest stable arm64 release from GitHub, installed only when
# missing (it updates itself). The release publishes no checksum, so the zip
# integrity is checked and the SHA-256 is printed.
if [ "$smarttube" = 1 ]; then
    if a shell pm path org.smarttube.stable >/dev/null 2>&1; then
        step "SmartTube already installed; left as it is"
    else
        step "Installing SmartTube (latest stable, arm64)"
        st="$(mktemp -d)"
        if curl -fsSL --retry 3 -o "$(local_path "$st/rel.json")" https://api.github.com/repos/yuliskov/SmartTube/releases/latest; then
            url="$(grep -o 'https://[^"]*stable_[0-9.]*_arm64-v8a\.apk' "$st/rel.json" | head -1 || true)"
        else
            url=""
        fi
        if [ -n "$url" ] && curl -fsSL --retry 3 -o "$(local_path "$st/app.apk")" "$url" && unzip -tq "$st/app.apk" >/dev/null; then
            echo "    $(basename "$url")  sha256 $(sha256sum "$st/app.apk" | cut -c1-16)..."
            a install -r "$(local_path "$st/app.apk")" || echo "    SmartTube not installed; skipped"
        else
            echo "    could not download SmartTube; skipped"
        fi
        rm -rf "$st"
    fi
fi

# Touch keyboard: Android TV's own keyboards work with the remote only, not
# with the car's touch. HeliBoard (https://github.com/HeliBorg/HeliBoard, open
# source) is installed when missing and made the default keyboard. Its
# long-press delay goes from 300 to 800 ms: on a busy Pi a quick tap could read
# as a long press and type an accent ("c" became "ç").
if [ "$keyboard" = 1 ]; then
    hb_ime="helium314.keyboard/.latin.LatinIME"
    if a shell pm path helium314.keyboard >/dev/null 2>&1; then
        step "Touch keyboard (HeliBoard) already installed"
    else
        step "Installing the touch keyboard (HeliBoard, latest release)"
        kb="$(mktemp -d)"
        if curl -fsSL --retry 3 -o "$(local_path "$kb/rel.json")" https://api.github.com/repos/HeliBorg/HeliBoard/releases/latest; then
            url="$(grep -o 'https://[^"]*HeliBoard_[0-9.]*-release\.apk' "$kb/rel.json" | head -1 || true)"
        else
            url=""
        fi
        if [ -n "$url" ] && curl -fsSL --retry 3 -o "$(local_path "$kb/kb.apk")" "$url" && unzip -tq "$kb/kb.apk" >/dev/null; then
            echo "    $(basename "$url")  sha256 $(sha256sum "$kb/kb.apk" | cut -c1-16)..."
            a install -r "$(local_path "$kb/kb.apk")" || echo "    HeliBoard not installed; skipped"
        else
            echo "    could not download HeliBoard; skipped"
        fi
        rm -rf "$kb"
    fi
    if a shell pm path helium314.keyboard >/dev/null 2>&1; then
        a shell "ime enable $hb_ime >/dev/null; ime set $hb_ime >/dev/null; settings put secure show_ime_with_hard_keyboard 1"
        echo "    default keyboard: HeliBoard"
        prefs=/data/user_de/0/helium314.keyboard/shared_prefs/helium314.keyboard_preferences.xml
        if ensure_root && a shell "[ -f $prefs ]" 2>/dev/null; then
            a shell "am force-stop helium314.keyboard
                if grep -q key_longpress_timeout $prefs; then
                    sed 's|<int name=\"key_longpress_timeout\" value=\"[0-9]*\" />|<int name=\"key_longpress_timeout\" value=\"800\" />|' $prefs > /data/local/tmp/hb.xml
                else
                    sed 's|</map>|    <int name=\"key_longpress_timeout\" value=\"800\" />\n</map>|' $prefs > /data/local/tmp/hb.xml
                fi
                cat /data/local/tmp/hb.xml > $prefs; rm /data/local/tmp/hb.xml
                ime set $hb_ime >/dev/null"
            echo "    long-press delay: 800 ms"
        else
            echo "    long-press delay left at its default (open a text box once, then run this again)"
        fi
    fi
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# System files: only the ones that differ from what the Pi has are copied, and
# the Pi reboots only if one of them changed.
files=(morozi.rc:/system/etc/init
       morozi_adbon.sh:/system/bin morozi_aoawatch.sh:/system/bin
       morozi_usbctl.sh:/system/bin morozi_screen.sh:/system/bin morozi_wifi.sh:/system/bin
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

# The Android TV screensaver starts after a while without a remote press and
# covers the picture the car shows (seen while a video played); the Pi is a car
# screen, so it stays off.
if [ "$(a shell settings get secure screensaver_enabled | tr -d '\r')" != 0 ]; then
    step "Turning the screensaver off (it covered the car display)"
    a shell "settings put secure screensaver_enabled 0; settings put secure screensaver_activate_on_sleep 0"
fi

if [ "$(a shell settings get global restricted_networking_mode | tr -d '\r')" != 0 ]; then
    step "Letting the app use the network"
    a shell settings put global restricted_networking_mode 0
fi

if [ ${#changed[@]} -gt 0 ]; then
    gsf_id_hint
    ssh_hint
    step "Rebooting the Pi (system files changed)"
    a reboot
    remember_pi
    echo "Done. After boot, open Pi2AndroidAuto once on the Pi."
else
    if [ "$app_updated" = 1 ]; then
        # The screen server runs code from the APK; restart it to load the new one.
        step "Restarting the screen server and the app"
        a shell 'setprop debug.morozi.screen 0; sleep 1; setprop debug.morozi.screen 1; am start -n com.morozi.autophone/.MainActivity >/dev/null'
    fi
    gsf_id_hint
    ssh_hint
    remember_pi
    echo "Done. No reboot needed."
fi
