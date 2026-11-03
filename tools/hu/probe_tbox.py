"""Behaviour probes of a phone-role device in accessory mode (Windows/WinUSB).
Run with the device already in accessory mode (18d1:2d00).

  probe_tbox.py idle N      : open, send nothing, watch N s (does it give up?)
  probe_tbox.py session     : version + TLS, close; then reopen and send a
                              new version request (is a 2nd session accepted?)
  probe_tbox.py reaoa       : AOA start while in accessory mode; time how long
                              until it leaves and what comes back
"""
import struct
import sys
import time

import winusb

T0 = time.time()


def log(m):
    print(f"{time.time() - T0:6.2f} {m}", flush=True)


def present(pid):
    for g in winusb._interface_guids(0x18D1, pid):
        for p in winusb._paths(g):
            if f"pid_{pid:04x}" in p.lower():
                return True
    return False


def state():
    return " ".join(f"{p:04x}" for p in (0x4EE1, 0x2D00, 0x2D01) if present(p)) or "none"


def watch(seconds, label):
    last = None
    end = time.time() + seconds
    while time.time() < end:
        s = state()
        if s != last:
            log(f"{label}: present = {s}")
            last = s
        time.sleep(0.25)


def version_roundtrip(dev, timeout_ms=3000):
    dev.write(bytes.fromhex("0003000600010001 0001".replace(" ", "")))
    data = dev.read(16384, timeout_ms)
    return data.hex() if data else None


mode = sys.argv[1] if len(sys.argv) > 1 else "idle"
if mode == "idle":
    n = int(sys.argv[2]) if len(sys.argv) > 2 else 30
    dev = winusb.WinUsbDevice(0x18D1, 0x2D00)
    log("opened, sending nothing")
    dev.close()
    watch(n, "idle")
elif mode == "session":
    dev = winusb.WinUsbDevice(0x18D1, 0x2D00)
    log(f"version 1: {version_roundtrip(dev)}")
    dev.close()
    log("closed without TLS; reopening")
    time.sleep(1)
    dev = winusb.WinUsbDevice(0x18D1, 0x2D00)
    log(f"version 2 on same accessory: {version_roundtrip(dev)}")
    dev.close()
    watch(10, "after")
elif mode == "reaoa":
    dev = winusb.WinUsbDevice(0x18D1, 0x2D00)
    proto = dev.control(0xC0, 51, 0, 0, length=2)
    log(f"GET_PROTOCOL in accessory mode -> {proto[0] | proto[1] << 8}")
    for i, s in enumerate(["Android", "Android Auto", "Android Auto", "2.0.1", "https://www.android.com/auto/", "X"]):
        dev.control(0x40, 52, 0, i, s.encode() + b"\0")
    dev.control(0x40, 53, 0, 0)
    log("AOA START sent while already in accessory mode")
    dev.close()
    watch(15, "reaoa")
