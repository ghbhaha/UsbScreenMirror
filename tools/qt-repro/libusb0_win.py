"""libusb-win32 (libusb0.dll) 的 ctypes 封装 —— 与 iPhoneMirror 用同一套驱动栈。

libusb-1.0 在这台机器的 libusb0 filter 上连 get_configuration 都报 I/O 错误，
而 iPhoneMirror 走 libusb0.dll 完全正常，所以复现也走这条。
"""
import ctypes as C
import ctypes.wintypes as W
import os

# libusb0.dll（libusb-win32，64 位）的查找顺序：
#   1) 环境变量 LIBUSB0_DLL
#   2) 与本文件同目录（把 dll 拷过来最省事）
#   3) 系统 PATH
# 不要在这里写死绝对路径——仓库是公开的，换机器也会直接失败。
DLL_PATH = os.environ.get('LIBUSB0_DLL') or os.path.join(
    os.path.dirname(os.path.abspath(__file__)), 'libusb0.dll')

lib = C.CDLL(DLL_PATH)


class usb_device_descriptor(C.Structure):
    _fields_ = [
        ('bLength', C.c_uint8),
        ('bDescriptorType', C.c_uint8),
        ('bcdUSB', C.c_uint16),
        ('bDeviceClass', C.c_uint8),
        ('bDeviceSubClass', C.c_uint8),
        ('bDeviceProtocol', C.c_uint8),
        ('bMaxPacketSize0', C.c_uint8),
        ('idVendor', C.c_uint16),
        ('idProduct', C.c_uint16),
        ('bcdDevice', C.c_uint16),
        ('iManufacturer', C.c_uint8),
        ('iProduct', C.c_uint8),
        ('iSerialNumber', C.c_uint8),
        ('bNumConfigurations', C.c_uint8),
    ]


class usb_device(C.Structure):
    pass


usb_device._fields_ = [
    ('next', C.POINTER(usb_device)),
    ('prev', C.POINTER(usb_device)),
    ('filename', C.c_char * 512),
    ('bus', C.c_void_p),
    ('descriptor', usb_device_descriptor),
    ('config', C.c_void_p),
    ('dev', C.c_void_p),
    ('devnum', C.c_uint8),
    ('num_children', C.c_uint8),
    ('children', C.c_void_p),
]


class usb_bus(C.Structure):
    pass


usb_bus._fields_ = [
    ('next', C.POINTER(usb_bus)),
    ('prev', C.POINTER(usb_bus)),
    ('dirname', C.c_char * 512),
    ('devices', C.POINTER(usb_device)),
    ('location', C.c_ulong),
    ('root_dev', C.POINTER(usb_device)),
]


lib.usb_init.restype = None
lib.usb_init.argtypes = []
lib.usb_find_busses.restype = C.c_int
lib.usb_find_busses.argtypes = []
lib.usb_find_devices.restype = C.c_int
lib.usb_find_devices.argtypes = []
lib.usb_get_busses.restype = C.POINTER(usb_bus)
lib.usb_get_busses.argtypes = []

lib.usb_open.restype = C.c_void_p
lib.usb_open.argtypes = [C.POINTER(usb_device)]
lib.usb_close.restype = C.c_int
lib.usb_close.argtypes = [C.c_void_p]
lib.usb_set_configuration.restype = C.c_int
lib.usb_set_configuration.argtypes = [C.c_void_p, C.c_int]
lib.usb_set_altinterface.restype = C.c_int
lib.usb_set_altinterface.argtypes = [C.c_void_p, C.c_int]
lib.usb_claim_interface.restype = C.c_int
lib.usb_claim_interface.argtypes = [C.c_void_p, C.c_int]
lib.usb_release_interface.restype = C.c_int
lib.usb_release_interface.argtypes = [C.c_void_p, C.c_int]
lib.usb_control_msg.restype = C.c_int
lib.usb_control_msg.argtypes = [C.c_void_p, C.c_int, C.c_int, C.c_int, C.c_int,
                                C.c_void_p, C.c_int, C.c_int]
lib.usb_bulk_read.restype = C.c_int
lib.usb_bulk_read.argtypes = [C.c_void_p, C.c_int, C.c_void_p, C.c_int, C.c_int]
lib.usb_bulk_write.restype = C.c_int
lib.usb_bulk_write.argtypes = [C.c_void_p, C.c_int, C.c_void_p, C.c_int, C.c_int]
lib.usb_strerror.restype = C.c_char_p
lib.usb_strerror.argtypes = []


def init():
    lib.usb_init()
    lib.usb_find_busses()
    lib.usb_find_devices()


def strerror():
    return lib.usb_strerror().decode('ascii', 'replace')


def iter_devices():
    """遍历所有总线上的设备，产出 (bus_dirname, usb_device)。"""
    bus = lib.usb_get_busses()
    while bus:
        b = bus.contents
        dev = b.devices
        while dev:
            d = dev.contents
            yield b.dirname.decode('ascii', 'replace'), d
            dev = d.next
        bus = b.next


def find_by_vid_pid(vid, pid):
    for busname, d in iter_devices():
        desc = d.descriptor
        if desc.idVendor == vid and desc.idProduct == pid:
            return busname, d
    return None, None


def ctrl(handle, requesttype, request, value, index, size=0, timeout=1000, data=None):
    buf = data if data is not None else C.create_string_buffer(size)
    n = lib.usb_control_msg(handle, requesttype, request, value, index,
                            C.cast(buf, C.c_void_p), size, timeout)
    return n, buf


def bulk_read(handle, ep, size, timeout=1000):
    buf = C.create_string_buffer(size)
    n = lib.usb_bulk_read(handle, ep, C.cast(buf, C.c_void_p), size, timeout)
    return n, buf.raw[:max(0, n)]


def bulk_write(handle, ep, data, timeout=1000):
    buf = C.create_string_buffer(bytes(data), len(data))
    return lib.usb_bulk_write(handle, ep, C.cast(buf, C.c_void_p), len(data), timeout)


if __name__ == '__main__':
    init()
    print('--- devices ---')
    for busname, d in iter_devices():
        desc = d.descriptor
        mark = ' <== Apple' if desc.idVendor == 0x05AC else ''
        print(f'bus {busname} dev {d.devnum:3d}  '
              f'{desc.idVendor:04x}:{desc.idProduct:04x} '
              f'cfg={desc.bNumConfigurations}{mark}')
