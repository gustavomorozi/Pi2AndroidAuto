"""Print the device and configuration descriptors of a WinUSB-bound device
(Windows), e.g. a phone in accessory mode: descriptors.py 2d00"""
import struct
import sys

import winusb

pid = int(sys.argv[1], 16) if len(sys.argv) > 1 else 0x2D00
dev = winusb.WinUsbDevice(0x18D1, pid)


def string(index):
    if not index:
        return ""
    raw = dev.control(0x80, 6, 0x0300 | index, 0x0409, length=255)
    return raw[2:raw[0]].decode("utf-16-le", "replace")


d = dev.control(0x80, 6, 0x0100, 0, length=18)
bcd_usb, cls, sub, proto, mps0, vid, pid_, bcd_dev, i_man, i_prod, i_ser, n_cfg = struct.unpack("<HBBBBHHHBBBB", d[2:])
print(f"device {vid:04x}:{pid_:04x} bcdUSB={bcd_usb:04x} bcdDevice={bcd_dev:04x} class={cls}/{sub}/{proto} ep0={mps0}")
print(f"  manufacturer={string(i_man)!r} product={string(i_prod)!r} serial={string(i_ser)!r}")
head = dev.control(0x80, 6, 0x0200, 0, length=9)
total = struct.unpack("<H", head[2:4])[0]
cfg = dev.control(0x80, 6, 0x0200, 0, length=total)
print(f"  config: {cfg[4]} interface(s), attributes=0x{cfg[7]:02x}, max power={cfg[8] * 2} mA")
i = 0
while i < len(cfg):
    length, kind = cfg[i], cfg[i + 1]
    if kind == 4:
        print(f"  interface {cfg[i + 2]} alt {cfg[i + 3]} class={cfg[i + 5]}/{cfg[i + 6]}/{cfg[i + 7]} "
              f"endpoints={cfg[i + 4]} name={string(cfg[i + 8])!r}")
    elif kind == 5:
        print(f"    endpoint 0x{cfg[i + 2]:02x} type={cfg[i + 3] & 3} maxpacket={struct.unpack('<H', cfg[i + 4:i + 6])[0]}")
    i += length or 1
dev.close()
