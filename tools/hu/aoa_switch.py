#!/usr/bin/env python3
"""Ask an Android device to switch into Android Open Accessory mode, the way a
car head unit does before an Android Auto session (the public AOA protocol:
GET_PROTOCOL, six SEND_STRINGs, START). Run as root in Linux/WSL with pyusb.

usage: aoa_switch.py [vid:pid]   (default 18d1:4ee1)
"""
import sys
import usb.core

ACCESSORY_GET_PROTOCOL = 51
ACCESSORY_SEND_STRING = 52
ACCESSORY_START = 53

STRINGS = [
    "Android",                       # manufacturer
    "Android Auto",                  # model
    "Android Auto",                  # description
    "2.0.1",                         # version
    "https://www.android.com/auto/", # uri
    "HU-MOROZI-0001",                # serial
]


def main():
    vid, pid = (int(x, 16) for x in (sys.argv[1] if len(sys.argv) > 1 else "18d1:4ee1").split(":"))
    dev = usb.core.find(idVendor=vid, idProduct=pid)
    if dev is None:
        print(f"device {vid:04x}:{pid:04x} not found")
        return 1
    proto = dev.ctrl_transfer(0xC0, ACCESSORY_GET_PROTOCOL, 0, 0, 2)
    version = proto[0] | (proto[1] << 8)
    print(f"AOA protocol version {version}")
    if version < 1:
        return 1
    for index, value in enumerate(STRINGS):
        dev.ctrl_transfer(0x40, ACCESSORY_SEND_STRING, 0, index, value.encode() + b"\0")
    dev.ctrl_transfer(0x40, ACCESSORY_START, 0, 0, None)
    print("AOA start sent; device should re-enumerate as 18d1:2d00/2d01")
    return 0


if __name__ == "__main__":
    sys.exit(main())
