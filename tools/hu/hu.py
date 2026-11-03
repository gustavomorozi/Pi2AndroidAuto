#!/usr/bin/env python3
"""Minimal Android Auto head unit ("car" side) for bench tests, over USB with
pyusb. Written from our own captures of the protocol (see the project skill):
it plays the car the way the CarlinKit CPC200-CCPA adapter does -- version
request 1.1, TLS client, AUTH_COMPLETE, then answers the phone's service
discovery with one video sink and opens/acks whatever the phone sends.

Every message in both directions is logged with a timestamp, so a stall shows
exactly which side stopped talking.

usage (root, Linux/WSL): hu.py [--seconds N] [--version 1.1]
The device must already be in accessory mode (18d1:2d00 or 2d01); use
aoa_switch.py first.
"""
import argparse
import ssl
import struct
import sys
import time

if sys.platform == "win32":
    import winusb
else:
    import usb.core
    import usb.util

FLAG_FIRST, FLAG_LAST, FLAG_CONTROL, FLAG_ENCRYPTED = 0x01, 0x02, 0x04, 0x08
CONTROL = 0

# Control channel message ids (from our captures).
VERSION_REQUEST, VERSION_RESPONSE, SSL_HANDSHAKE, AUTH_COMPLETE = 1, 2, 3, 4
SD_REQUEST, SD_RESPONSE, CH_OPEN_REQUEST, CH_OPEN_RESPONSE = 5, 6, 7, 8
PING_REQUEST, PING_RESPONSE = 0x0B, 0x0C
AUDIO_FOCUS_REQUEST, AUDIO_FOCUS_RESPONSE = 0x12, 0x13
# Media channel ids.
MEDIA_DATA, MEDIA_CODEC_CONFIG = 0x0000, 0x0001
MEDIA_SETUP, MEDIA_START, MEDIA_CONFIG, MEDIA_ACK = 0x8000, 0x8001, 0x8003, 0x8004
VIDEO_FOCUS_REQUEST, VIDEO_FOCUS_INDICATION = 0x8007, 0x8008

VIDEO_CHANNEL = 3
T0 = time.time()


def log(msg):
    print(f"{time.time() - T0:7.3f} {msg}", flush=True)


# --- minimal protobuf -------------------------------------------------------
def varint(v):
    out = bytearray()
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def pb_varint(field, v):
    return varint(field << 3) + varint(v)


def pb_bytes(field, data):
    return varint((field << 3) | 2) + varint(len(data)) + data


def pb_fields(data):
    """Yields (field, wiretype, value) for a protobuf message."""
    i = 0
    while i < len(data):
        key, i = _read_varint(data, i)
        field, wt = key >> 3, key & 7
        if wt == 0:
            v, i = _read_varint(data, i)
        elif wt == 2:
            n, i = _read_varint(data, i)
            v, i = data[i:i + n], i + n
        elif wt == 1:
            v, i = data[i:i + 8], i + 8
        elif wt == 5:
            v, i = data[i:i + 4], i + 4
        else:
            raise ValueError(f"wire type {wt}")
        yield field, wt, v


def _read_varint(data, i):
    v = shift = 0
    while True:
        b = data[i]
        i += 1
        v |= (b & 0x7F) << shift
        if b < 0x80:
            return v, i
        shift += 7


# --- transport --------------------------------------------------------------
class Usb:
    def __init__(self):
        self.dev = None
        for pid in (0x2D00, 0x2D01):
            self.dev = usb.core.find(idVendor=0x18D1, idProduct=pid)
            if self.dev:
                break
        if self.dev is None:
            raise SystemExit("no accessory-mode device (18d1:2d00/2d01)")
        cfg = self.dev.get_active_configuration()
        intf = cfg[(0, 0)]
        try:
            if self.dev.is_kernel_driver_active(0):
                self.dev.detach_kernel_driver(0)
        except (NotImplementedError, usb.core.USBError):
            pass
        usb.util.claim_interface(self.dev, 0)
        self.ep_in = usb.util.find_descriptor(intf, custom_match=lambda e: usb.util.endpoint_direction(e.bEndpointAddress) == usb.util.ENDPOINT_IN)
        self.ep_out = usb.util.find_descriptor(intf, custom_match=lambda e: usb.util.endpoint_direction(e.bEndpointAddress) == usb.util.ENDPOINT_OUT)
        log(f"opened {self.dev.idVendor:04x}:{self.dev.idProduct:04x} in=0x{self.ep_in.bEndpointAddress:02x} out=0x{self.ep_out.bEndpointAddress:02x}")
        self.buf = bytearray()

    def write(self, data):
        self.ep_out.write(data, timeout=2000)

    def read_some(self, timeout_ms):
        try:
            self.buf += bytes(self.ep_in.read(16384, timeout=timeout_ms))
            return True
        except usb.core.USBTimeoutError:
            return False

    def read_exact(self, n, deadline):
        while len(self.buf) < n:
            left = int((deadline - time.time()) * 1000)
            if left <= 0:
                return None
            self.read_some(min(left, 500))
        out, self.buf = bytes(self.buf[:n]), self.buf[n:]
        return out


class WinTransport:
    """Same interface as Usb, through WinUSB on Windows (device needs the
    WinUSB driver in accessory mode, e.g. via Zadig)."""

    def __init__(self, pids=(0x2D00, 0x2D01)):
        self.dev = None
        for pid in pids:
            try:
                self.dev = winusb.WinUsbDevice(0x18D1, pid)
                break
            except OSError:
                pass
        if self.dev is None:
            raise SystemExit("no accessory-mode WinUSB device (18d1:2d00/2d01)")
        log(f"opened {self.dev.path} in=0x{self.dev.ep_in:02x} out=0x{self.dev.ep_out:02x}")
        self.buf = bytearray()

    def write(self, data):
        self.dev.write(data, 2000)

    def read_some(self, timeout_ms):
        data = self.dev.read(16384, max(timeout_ms, 1))
        if data:
            self.buf += data
            return True
        return False

    read_exact = Usb.read_exact


def aoa_switch_windows(pid):
    """Switch a WinUSB-bound device (e.g. the Pi in normal mode) to accessory mode."""
    try:
        dev = winusb.WinUsbDevice(0x18D1, pid)
    except OSError:
        return False
    proto = dev.control(0xC0, 51, 0, 0, length=2)
    log(f"AOA protocol {proto[0] | (proto[1] << 8)} on 18d1:{pid:04x}")
    strings = ["Android", "Android Auto", "Android Auto", "2.0.1", "https://www.android.com/auto/", "HU-MOROZI-0001"]
    for i, s in enumerate(strings):
        dev.control(0x40, 52, 0, i, s.encode() + b"\0")
    dev.control(0x40, 53, 0, 0)
    dev.close()
    log("AOA start sent")
    return True


class Link:
    """AAP framing + TLS on top of the USB transport."""

    def __init__(self, t):
        self.t = t
        self.tls = None
        self.partial = {}

    # frames
    def send_frame(self, ch, flags, payload, total=0):
        hdr = struct.pack(">BBH", ch, flags, len(payload))
        if flags & FLAG_FIRST and not flags & FLAG_LAST:
            hdr += struct.pack(">I", total)
        self.t.write(hdr + payload)

    def read_frame(self, timeout_s):
        deadline = time.time() + timeout_s
        hdr = self.t.read_exact(4, deadline)
        if hdr is None:
            return None
        ch, flags, n = struct.unpack(">BBH", hdr)
        if flags & FLAG_FIRST and not flags & FLAG_LAST:
            if self.t.read_exact(4, deadline + 2) is None:
                return None
        payload = self.t.read_exact(n, deadline + 2)
        return ch, flags, payload

    # messages
    def send_plain(self, ch, mid, body=b""):
        self.send_frame(ch, FLAG_FIRST | FLAG_LAST, struct.pack(">H", mid) + body)
        log(f"-> ch{ch} id=0x{mid:x} plain {len(body)}B")

    def send_enc(self, ch, mid, body=b"", control=False):
        self.tls.write(struct.pack(">H", mid) + body)
        cipher = self.out_bio.read()
        flags = FLAG_FIRST | FLAG_LAST | FLAG_ENCRYPTED | (FLAG_CONTROL if control else 0)
        self.send_frame(ch, flags, cipher)
        if mid not in (MEDIA_ACK, PING_REQUEST, PING_RESPONSE):
            log(f"-> ch{ch} id=0x{mid:x} enc {len(body)}B")

    def read_message(self, timeout_s):
        """Returns (ch, flags, mid, body) for one whole message, or None."""
        while True:
            f = self.read_frame(timeout_s)
            if f is None:
                return None
            ch, flags, payload = f
            if flags & FLAG_ENCRYPTED:
                self.in_bio.write(payload)
                chunks = []
                while True:
                    try:
                        chunks.append(self.tls.read(65536))
                    except ssl.SSLWantReadError:
                        break
                payload = b"".join(chunks)
            if flags & FLAG_FIRST and not flags & FLAG_LAST:
                self.partial[ch] = bytearray(payload)
                continue
            if not flags & FLAG_FIRST:
                self.partial.setdefault(ch, bytearray()).extend(payload)
                if not flags & FLAG_LAST:
                    continue
                payload = bytes(self.partial.pop(ch))
            if len(payload) < 2:
                continue
            mid = struct.unpack(">H", payload[:2])[0]
            return ch, flags, mid, payload[2:]

    # TLS client handshake inside SSL_HANDSHAKE messages
    def handshake(self, timeout_s, ccpa=False):
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        if ccpa:
            # What the CarlinKit CPC200-CCPA offered (captured ClientHello):
            # TLS 1.2, plain RSA and DHE-RSA suites only, no ECDHE.
            ctx.maximum_version = ssl.TLSVersion.TLSv1_2
            ctx.set_ciphers("DHE-RSA-AES256-GCM-SHA384:DHE-RSA-CHACHA20-POLY1305:DHE-RSA-AES128-GCM-SHA256:"
                            "DHE-RSA-AES256-SHA256:DHE-RSA-AES128-SHA256:DHE-RSA-AES256-SHA:DHE-RSA-AES128-SHA:"
                            "AES256-GCM-SHA384:AES128-GCM-SHA256:AES256-SHA256:AES128-SHA256:AES256-SHA:AES128-SHA:"
                            "@SECLEVEL=0")
        else:
            ctx.set_ciphers("ALL:@SECLEVEL=0")
        self.in_bio, self.out_bio = ssl.MemoryBIO(), ssl.MemoryBIO()
        self.tls = ctx.wrap_bio(self.in_bio, self.out_bio, server_side=False)
        deadline = time.time() + timeout_s
        while True:
            try:
                self.tls.do_handshake()
                done = True
            except ssl.SSLWantReadError:
                done = False
            out = self.out_bio.read()
            if out:
                self.send_plain(CONTROL, SSL_HANDSHAKE, out)
            if done:
                log(f"TLS done: {self.tls.version()} {self.tls.cipher()[0]}")
                return True
            f = self.read_frame(max(0.1, deadline - time.time()))
            if f is None:
                log("TLS: no answer from phone")
                return False
            ch, flags, payload = f
            mid = struct.unpack(">H", payload[:2])[0]
            log(f"<- ch{ch} id=0x{mid:x} plain {len(payload) - 2}B (TLS)")
            self.in_bio.write(payload[2:])


class TcpTransport:
    """Same interface as Usb, over TCP (our app's listen mode, port 5277)."""

    def __init__(self, host, port):
        import socket
        self.sock = socket.create_connection((host, port), timeout=5)
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        log(f"connected to {host}:{port}")
        self.buf = bytearray()

    def write(self, data):
        self.sock.sendall(data)

    def read_some(self, timeout_ms):
        import socket
        self.sock.settimeout(max(timeout_ms, 1) / 1000)
        try:
            data = self.sock.recv(65536)
        except socket.timeout:
            return False
        if not data:
            raise SystemExit("phone closed the connection")
        self.buf += data
        return True

    read_exact = Usb.read_exact


def ccpa_service_discovery_response():
    """Services as the CarlinKit CPC200-CCPA listed them to us (03:56 capture):
    video ch3 800x480@30 (margins 0x12, density 120), touch input ch1,
    48 kHz stereo media audio ch4."""
    video = pb_varint(1, 3) + pb_bytes(3, pb_varint(1, 3) + pb_varint(2, 0) + pb_bytes(4,
        pb_varint(1, 1) + pb_varint(2, 2) + pb_varint(3, 0) + pb_varint(4, 12) + pb_varint(5, 120)))
    touch = pb_bytes(2, pb_varint(1, 800) + pb_varint(2, 480))
    keys = b"".join(pb_varint(1, k) for k in (1, 2, 3, 4, 5, 19, 20, 21, 22, 23, 84, 85, 87, 88, 126, 127))
    inp = pb_varint(1, 1) + pb_bytes(4, keys + touch)
    audio = pb_varint(1, 4) + pb_bytes(3, pb_varint(1, 1) + pb_varint(2, 3) + pb_bytes(3,
        pb_varint(1, 48000) + pb_varint(2, 16) + pb_varint(3, 2)))
    return (pb_bytes(1, video) + pb_bytes(1, inp) + pb_bytes(1, audio)
            + pb_bytes(2, b"Morozi") + pb_bytes(3, b"Bench CCPA"))


def run(seconds, version, switch_pid=None, pids=(0x2D00, 0x2D01), tcp=None, ccpa=False, dump_path=None, restart=False, tap=None):
    dump = open(dump_path, "wb") if dump_path else None
    if tcp:
        host, port = tcp.split(":")
        transport = TcpTransport(host, int(port))
    elif sys.platform == "win32":
        if switch_pid and aoa_switch_windows(switch_pid):
            time.sleep(3)  # let the device drop off the bus and re-enumerate
        for _ in range(30):
            try:
                transport = WinTransport(pids)
                break
            except SystemExit:
                time.sleep(1)
        else:
            raise SystemExit("device never appeared in accessory mode")
    else:
        transport = Usb()
    link = Link(transport)
    major, minor = (int(x) for x in version.split("."))
    link.send_plain(CONTROL, VERSION_REQUEST, struct.pack(">HH", major, minor))
    m = link.read_message(5)
    if m is None:
        log("RESULT: no version response")
        return 2
    log(f"<- version response {m[3].hex()}")
    if not link.handshake(10, ccpa):
        log("RESULT: TLS failed")
        return 3
    link.send_plain(CONTROL, AUTH_COMPLETE, pb_varint(1, 0))

    frames = 0
    end = time.time() + seconds
    next_ping = time.time() + 1
    restart_at = time.time() + seconds / 2 if restart else None
    taps = list(tap or [])
    tap_at = time.time() + seconds / 3
    while time.time() < end:
        if taps and time.time() >= tap_at:
            # A car touch on the input channel (1), the way the CCPA reports
            # them: field 3 touch {pointer {x, y, id}..., action index, action}.
            # (fingers, x, y): one finger = down, up; two fingers = down,
            # pointer down, pointer up, up (Android MotionEvent actions).
            fingers, x, y = taps.pop(0)
            def pointers(n):
                return b"".join(pb_bytes(1, pb_varint(1, x + 40 * i) + pb_varint(2, y) + pb_varint(3, i)) for i in range(n))
            steps = [(1, 0, 0), (1, 0, 1)] if fingers == 1 else [(1, 0, 0), (2, 1, 5), (2, 1, 6), (1, 0, 1)]
            for n, index, action in steps:
                touch = pointers(n) + pb_varint(2, index) + pb_varint(3, action)
                link.send_enc(1, 0x8001, pb_varint(1, int(time.time() * 1e9)) + pb_bytes(3, touch))
                time.sleep(0.08)
            log(f"TAP sent at ({x}, {y}) with {fingers} finger(s)")
            tap_at = time.time() + 2
        if restart_at and time.time() >= restart_at:
            # Start over in the same connection, as the CarlinKit CCPA does:
            # a plain VERSION_REQUEST, wait for the plain answer, new TLS.
            restart_at = None
            log("RESTART: sending a new version request mid-session")
            link.send_plain(CONTROL, VERSION_REQUEST, struct.pack(">HH", major, minor))
            deadline = time.time() + 10
            while time.time() < deadline:
                m = link.read_message(1)
                if m and m[0] == CONTROL and m[2] == VERSION_RESPONSE and not m[1] & FLAG_ENCRYPTED:
                    log(f"RESTART: version response {m[3].hex()}")
                    break
            else:
                log("RESULT: no version response after restart")
                return 4
            if not link.handshake(10, ccpa):
                log("RESULT: TLS failed after restart")
                return 5
            link.send_plain(CONTROL, AUTH_COMPLETE, pb_varint(1, 0))
            frames_before_restart, frames = frames, 0
            continue
        if time.time() >= next_ping:
            # Keepalive: the CarlinKit firmware drops a link after 5 s without pings.
            link.send_enc(CONTROL, PING_REQUEST, pb_varint(1, int(time.time() * 1e6)))
            next_ping = time.time() + 1
        m = link.read_message(min(1.0, max(0.1, end - time.time())))
        if m is None:
            continue
        ch, flags, mid, body = m
        if ch == CONTROL and mid == SD_REQUEST:
            log(f"<- SERVICE_DISCOVERY_REQUEST {body.hex()}")
            video = pb_varint(1, VIDEO_CHANNEL) + pb_bytes(3, pb_varint(1, 3) + pb_varint(2, 0) + pb_bytes(4,
                pb_varint(1, 1) + pb_varint(2, 2) + pb_varint(3, 0) + pb_varint(4, 0) + pb_varint(5, 160)))
            resp = pb_bytes(1, video) + pb_bytes(2, b"Morozi") + pb_bytes(3, b"Bench HU")
            link.send_enc(CONTROL, SD_RESPONSE, ccpa_service_discovery_response() if ccpa else resp)
        elif mid == CH_OPEN_REQUEST:
            log(f"<- CHANNEL_OPEN_REQUEST ch{ch} {body.hex()}")
            link.send_enc(ch, CH_OPEN_RESPONSE, pb_varint(1, 0), control=True)
        elif ch == CONTROL and mid == PING_REQUEST:
            link.send_enc(CONTROL, PING_RESPONSE, body)
        elif ch == CONTROL and mid == PING_RESPONSE:
            pass
        elif ch == CONTROL and mid == AUDIO_FOCUS_REQUEST:
            link.send_enc(CONTROL, AUDIO_FOCUS_RESPONSE, pb_varint(1, 1))
        elif mid == MEDIA_SETUP:
            log(f"<- MEDIA SETUP ch{ch} {body.hex()}")
            max_unacked = 1 if ccpa and ch == VIDEO_CHANNEL else 4  # CCPA video: 1 (captured)
            link.send_enc(ch, MEDIA_CONFIG, pb_varint(1, 2) + pb_varint(2, max_unacked) + pb_varint(3, 0))
            if ch == VIDEO_CHANNEL:
                # The CarlinKit CPC200-CCPA sends this right after CONFIG
                # (captured 03:56: ch3 0x8008 "08 01 10 01").
                link.send_enc(ch, VIDEO_FOCUS_INDICATION, pb_varint(1, 1) + pb_varint(2, 1))
        elif mid == MEDIA_START:
            log(f"<- MEDIA START ch{ch} {body.hex()}")
        elif ch == 1 and mid == 0x8002:  # key binding request on the input channel
            log(f"<- KEY_BINDING_REQUEST {len(body)}B")
            link.send_enc(ch, 0x8003, pb_varint(1, 0))
        elif mid in (MEDIA_DATA, MEDIA_CODEC_CONFIG):
            frames += 1
            if dump and ch == VIDEO_CHANNEL:
                # Raw H.264: data messages start with an 8-byte timestamp.
                dump.write(body[8:] if mid == MEDIA_DATA else body)
            link.send_enc(ch, MEDIA_ACK, pb_varint(1, 0) + pb_varint(2, 1))
            if frames in (1, 10) or frames % 100 == 0:
                log(f"<- video message #{frames} ({len(body)}B)")
        else:
            log(f"<- ch{ch} id=0x{mid:x} {len(body)}B {body[:24].hex()}")
    log(f"RESULT: TLS ok, {frames} video messages in {seconds}s")
    return 0


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--seconds", type=int, default=20)
    ap.add_argument("--version", default="1.1")
    ap.add_argument("--switch", help="Windows: normal-mode PID to switch first, e.g. 4ee7 for the Pi")
    ap.add_argument("--pid", help="accessory-mode PID to open (2d00 or 2d01); default tries both")
    ap.add_argument("--tcp", help="host:port to reach the phone over TCP instead of USB")
    ap.add_argument("--ccpa", action="store_true", help="behave like the CarlinKit CPC200-CCPA")
    ap.add_argument("--dump", help="write the received H.264 video to this file")
    ap.add_argument("--restart", action="store_true", help="halfway through, start the session over")
    ap.add_argument("--tap", action="append", help="x,y in video coordinates: a touch a third of the way through; repeat for more, 2 s apart")
    ap.add_argument("--tap2", action="append", help="x,y: like --tap but with two fingers (sent after the --tap ones)")
    a = ap.parse_args()
    pids = (int(a.pid, 16),) if a.pid else (0x2D00, 0x2D01)
    tap = [(1, *map(int, t.split(","))) for t in a.tap or []] + [(2, *map(int, t.split(","))) for t in a.tap2 or []]
    sys.exit(run(a.seconds, a.version, int(a.switch, 16) if a.switch else None, pids, a.tcp, a.ccpa, a.dump, a.restart, tap))
