"""Tiny WinUSB access for Windows with ctypes only (no pyusb/libusb needed).

Finds a present USB device by VID/PID whose function has the WinUSB driver
(for example after Zadig), opens it and exposes control and bulk transfers.
Only what the bench head unit needs.
"""
import ctypes
import ctypes.wintypes as wt
import winreg

setupapi = ctypes.WinDLL("setupapi", use_last_error=True)
winusb = ctypes.WinDLL("winusb", use_last_error=True)
kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)

DIGCF_PRESENT, DIGCF_DEVICEINTERFACE = 0x02, 0x10
GENERIC_READ, GENERIC_WRITE = 0x80000000, 0x40000000
FILE_SHARE_READ, FILE_SHARE_WRITE = 1, 2
OPEN_EXISTING, FILE_FLAG_OVERLAPPED = 3, 0x40000000
INVALID_HANDLE = ctypes.c_void_p(-1).value
PIPE_TRANSFER_TIMEOUT = 0x03
ERROR_SEM_TIMEOUT = 121


class GUID(ctypes.Structure):
    _fields_ = [("Data1", wt.DWORD), ("Data2", wt.WORD), ("Data3", wt.WORD), ("Data4", ctypes.c_ubyte * 8)]

    @classmethod
    def parse(cls, s):
        s = s.strip("{}")
        a, b, c, d, e = s.split("-")
        g = cls(int(a, 16), int(b, 16), int(c, 16))
        tail = bytes.fromhex(d + e)
        for i in range(8):
            g.Data4[i] = tail[i]
        return g


class SP_DEVICE_INTERFACE_DATA(ctypes.Structure):
    _fields_ = [("cbSize", wt.DWORD), ("InterfaceClassGuid", GUID), ("Flags", wt.DWORD), ("Reserved", ctypes.c_void_p)]


class WINUSB_SETUP_PACKET(ctypes.Structure):
    _pack_ = 1
    _fields_ = [("RequestType", ctypes.c_ubyte), ("Request", ctypes.c_ubyte), ("Value", wt.USHORT),
                ("Index", wt.USHORT), ("Length", wt.USHORT)]


class WINUSB_PIPE_INFORMATION(ctypes.Structure):
    _fields_ = [("PipeType", ctypes.c_int), ("PipeId", ctypes.c_ubyte), ("MaximumPacketSize", wt.USHORT),
                ("Interval", ctypes.c_ubyte)]


setupapi.SetupDiGetClassDevsW.restype = ctypes.c_void_p
setupapi.SetupDiGetClassDevsW.argtypes = [ctypes.POINTER(GUID), wt.LPCWSTR, wt.HWND, wt.DWORD]
setupapi.SetupDiEnumDeviceInterfaces.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.POINTER(GUID), wt.DWORD,
                                                 ctypes.POINTER(SP_DEVICE_INTERFACE_DATA)]
setupapi.SetupDiGetDeviceInterfaceDetailW.argtypes = [ctypes.c_void_p, ctypes.POINTER(SP_DEVICE_INTERFACE_DATA),
                                                      ctypes.c_void_p, wt.DWORD, ctypes.POINTER(wt.DWORD), ctypes.c_void_p]
setupapi.SetupDiDestroyDeviceInfoList.argtypes = [ctypes.c_void_p]
kernel32.CreateFileW.restype = ctypes.c_void_p
kernel32.CreateFileW.argtypes = [wt.LPCWSTR, wt.DWORD, wt.DWORD, ctypes.c_void_p, wt.DWORD, wt.DWORD, ctypes.c_void_p]
kernel32.CloseHandle.argtypes = [ctypes.c_void_p]
winusb.WinUsb_Initialize.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_void_p)]
winusb.WinUsb_Free.argtypes = [ctypes.c_void_p]
winusb.WinUsb_ControlTransfer.argtypes = [ctypes.c_void_p, WINUSB_SETUP_PACKET, ctypes.c_void_p, wt.ULONG,
                                          ctypes.POINTER(wt.ULONG), ctypes.c_void_p]
winusb.WinUsb_QueryPipe.argtypes = [ctypes.c_void_p, ctypes.c_ubyte, ctypes.c_ubyte, ctypes.POINTER(WINUSB_PIPE_INFORMATION)]
winusb.WinUsb_SetPipePolicy.argtypes = [ctypes.c_void_p, ctypes.c_ubyte, wt.ULONG, wt.ULONG, ctypes.c_void_p]
winusb.WinUsb_ReadPipe.argtypes = [ctypes.c_void_p, ctypes.c_ubyte, ctypes.c_void_p, wt.ULONG,
                                   ctypes.POINTER(wt.ULONG), ctypes.c_void_p]
winusb.WinUsb_WritePipe.argtypes = [ctypes.c_void_p, ctypes.c_ubyte, ctypes.c_void_p, wt.ULONG,
                                    ctypes.POINTER(wt.ULONG), ctypes.c_void_p]


def _interface_guids(vid, pid):
    """DeviceInterfaceGUIDs that WinUSB registered for present instances of vid:pid."""
    key = rf"SYSTEM\CurrentControlSet\Enum\USB\VID_{vid:04X}&PID_{pid:04X}"
    guids = []
    try:
        with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, key) as k:
            i = 0
            while True:
                try:
                    inst = winreg.EnumKey(k, i)
                except OSError:
                    break
                i += 1
                try:
                    with winreg.OpenKey(k, inst + r"\Device Parameters") as p:
                        for name in ("DeviceInterfaceGUIDs", "DeviceInterfaceGUID"):
                            try:
                                v, _ = winreg.QueryValueEx(p, name)
                                guids += v if isinstance(v, list) else [v]
                            except OSError:
                                pass
                except OSError:
                    pass
    except OSError:
        pass
    return guids


def _paths(guid_str):
    g = GUID.parse(guid_str)
    h = setupapi.SetupDiGetClassDevsW(ctypes.byref(g), None, None, DIGCF_PRESENT | DIGCF_DEVICEINTERFACE)
    if h in (None, INVALID_HANDLE):
        return []
    out = []
    try:
        idx = 0
        while True:
            data = SP_DEVICE_INTERFACE_DATA()
            data.cbSize = ctypes.sizeof(SP_DEVICE_INTERFACE_DATA)
            if not setupapi.SetupDiEnumDeviceInterfaces(h, None, ctypes.byref(g), idx, ctypes.byref(data)):
                break
            idx += 1
            need = wt.DWORD()
            setupapi.SetupDiGetDeviceInterfaceDetailW(h, ctypes.byref(data), None, 0, ctypes.byref(need), None)
            buf = ctypes.create_string_buffer(need.value)
            ctypes.cast(buf, ctypes.POINTER(wt.DWORD))[0] = 8 if ctypes.sizeof(ctypes.c_void_p) == 8 else 6
            if setupapi.SetupDiGetDeviceInterfaceDetailW(h, ctypes.byref(data), buf, need, None, None):
                out.append(ctypes.wstring_at(ctypes.addressof(buf) + 4))
    finally:
        setupapi.SetupDiDestroyDeviceInfoList(h)
    return out


class WinUsbDevice:
    def __init__(self, vid, pid):
        self.file = self.handle = None
        for g in _interface_guids(vid, pid):
            for path in _paths(g):
                if f"vid_{vid:04x}&pid_{pid:04x}" not in path.lower():
                    continue
                f = kernel32.CreateFileW(path, GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE,
                                         None, OPEN_EXISTING, FILE_FLAG_OVERLAPPED, None)
                if f in (None, INVALID_HANDLE):
                    continue
                h = ctypes.c_void_p()
                if winusb.WinUsb_Initialize(f, ctypes.byref(h)):
                    self.file, self.handle, self.path = f, h, path
                    break
                kernel32.CloseHandle(f)
            if self.handle:
                break
        if not self.handle:
            raise OSError(f"no WinUSB device {vid:04x}:{pid:04x} could be opened")
        self.ep_in = self.ep_out = None
        info = WINUSB_PIPE_INFORMATION()
        i = 0
        while winusb.WinUsb_QueryPipe(self.handle, 0, i, ctypes.byref(info)):
            if info.PipeType == 2:  # bulk
                if info.PipeId & 0x80:
                    self.ep_in = info.PipeId
                else:
                    self.ep_out = info.PipeId
            i += 1

    def set_timeout(self, pipe, ms):
        v = wt.ULONG(ms)
        winusb.WinUsb_SetPipePolicy(self.handle, pipe, PIPE_TRANSFER_TIMEOUT, ctypes.sizeof(v), ctypes.byref(v))

    def control(self, request_type, request, value, index, data=None, length=0):
        n = len(data) if data is not None else length
        buf = ctypes.create_string_buffer(bytes(data) if data is not None else n, max(n, 1))
        pkt = WINUSB_SETUP_PACKET(request_type, request, value, index, n)
        done = wt.ULONG()
        if not winusb.WinUsb_ControlTransfer(self.handle, pkt, buf if n else None, n, ctypes.byref(done), None):
            raise OSError(ctypes.get_last_error(), "control transfer failed")
        return buf.raw[:done.value]

    def write(self, data, timeout_ms=2000):
        self.set_timeout(self.ep_out, timeout_ms)
        buf = ctypes.create_string_buffer(bytes(data), len(data))
        done = wt.ULONG()
        if not winusb.WinUsb_WritePipe(self.handle, self.ep_out, buf, len(data), ctypes.byref(done), None):
            raise OSError(ctypes.get_last_error(), "write failed")
        return done.value

    def read(self, size, timeout_ms):
        """Returns bytes, or None on timeout."""
        self.set_timeout(self.ep_in, timeout_ms)
        buf = ctypes.create_string_buffer(size)
        done = wt.ULONG()
        if not winusb.WinUsb_ReadPipe(self.handle, self.ep_in, buf, size, ctypes.byref(done), None):
            err = ctypes.get_last_error()
            if err == ERROR_SEM_TIMEOUT:
                return None
            raise OSError(err, "read failed")
        return buf.raw[:done.value]

    def close(self):
        if self.handle:
            winusb.WinUsb_Free(self.handle)
        if self.file:
            kernel32.CloseHandle(self.file)
