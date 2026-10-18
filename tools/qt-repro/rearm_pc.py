"""Re-arm the iPad on PC so `activate` actually takes effect.

Why this exists
---------------
`activate` (0x40/0x52 wIndex=2) only re-enumerates the device when it currently
sits in configuration 3.  On Android the device defaults to config 3, so the
request works every time.  On Windows the Apple driver leaves it in config 1,
so the same request returns 0 and nothing happens (verified 2026-10-18 17:25).

The only way to reach config 5 on PC is therefore: set_configuration(3) -> activate.

DANGER
------
set_configuration on an Apple device through Windows libusb0 has BSOD'd this
machine twice.  The call is split into two phases on purpose, and every line is
fsync'ed, so after a crash the log shows exactly which call was in flight.

Usage
-----
    python rearm_pc.py to3          # phase 1: set configuration 3 (the risky one)
    python rearm_pc.py activate     # phase 2: send activate, poll for config 5

Pure ASCII on purpose: Windows Python may encode stdout as cp936 when redirected.
"""

import os
import struct
import sys
import time

import libusb0_win as U

VID, PID = 0x05AC, 0x12AB
def fcc(name):
    return name.encode('ascii')[::-1]


def hpd0_packet():
    return struct.pack('<I', 20) + fcc('asyn') + struct.pack('<Q', 1) + fcc('hpd0')


LOG = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'capture', 'rearm.log')


def log(msg):
    line = '%s  %s' % (time.strftime('%H:%M:%S'), msg)
    print(line)
    try:
        with open(LOG, 'a', encoding='ascii', errors='replace') as f:
            f.write(line + '\n')
            f.flush()
            os.fsync(f.fileno())
    except OSError:
        pass


def find():
    U.init()
    return U.find_by_vid_pid(VID, PID)


def cfg_count():
    _, d = find()
    if d is None:
        return None
    return d.descriptor.bNumConfigurations


def phase_to3():
    cc = cfg_count()
    log('cfg_count before = %s' % cc)
    if cc is None:
        log('device not found'); return 1
    if cc >= 5:
        log('already at config set 5 - nothing to do'); return 0
    if cc < 3:
        log('only %d configurations - config 3 does not exist, aborting' % cc); return 2

    _, d = find()
    h = U.lib.usb_open(d)
    if not h:
        log('open failed: %s' % U.strerror()); return 3
    log('about to call usb_set_configuration(h, 3)   <== the call that BSODs')
    n = U.lib.usb_set_configuration(h, 3)
    log('usb_set_configuration(3) returned %d' % n)
    U.lib.usb_close(h)
    log('device closed')
    time.sleep(1.5)
    log('cfg_count after = %s' % cfg_count())
    return 0


def phase_activate():
    cc = cfg_count()
    log('cfg_count before = %s' % cc)
    if cc is None:
        log('device not found'); return 1
    if cc >= 5:
        log('already at config set 5 - activate not needed'); return 0

    _, d = find()
    h = U.lib.usb_open(d)
    if not h:
        log('open failed: %s' % U.strerror()); return 3
    log('sending activate (bmRequest=0x40 req=0x52 wValue=0 wIndex=2)')
    n = U.lib.usb_control_msg(h, 0x40, 0x52, 0, 2, None, 0, 3000)
    log('activate returned %d' % n)
    U.lib.usb_close(h)

    for i in range(240):
        time.sleep(1)
        c = cfg_count()
        if c is None:
            log('  [%ds] device gone (re-enumerating)' % (i + 1))
            continue
        if i < 5 or i % 5 == 4 or c >= 5:
            log('  [%ds] cfg_count=%s' % (i + 1, c))
        if c >= 5:
            log('SUCCESS: device is in capture mode (cfg_count=%s)' % c)
            return 0
    log('TIMEOUT: device never reached config set 5')
    return 4


def phase_teardown():
    """只做一次干净收尾：claim if2 -> HPD0(+HPA0) -> 等 rels -> 关闭。

    用途：上一轮进程被 Ctrl+C 打断时没发 HPD0，设备侧的时钟会话会一直开着，
    下一轮会直接在第 3 帧卡死。跑这个通常比重新插拔便宜。
    """
    cc = cfg_count()
    log('cfg_count = %s' % cc)
    _, d = find()
    h = U.lib.usb_open(d)
    if not h:
        log('open failed: %s' % U.strerror()); return 3
    n = U.lib.usb_claim_interface(h, 2)
    log('claim_interface(2) => %d' % n)
    if n < 0:
        U.lib.usb_close(h); return 4
    U.bulk_write(h, 0x05, hpd0_packet(), 500)
    log('sent hpd0, draining for rels...')
    rels = 0
    t0 = time.time()
    buf = b''
    while rels < 2 and time.time() - t0 < 6:
        n, data = U.bulk_read(h, 0x86, 1 << 20, 250)
        if n <= 0:
            continue
        buf += data
        while len(buf) >= 4:
            plen = struct.unpack_from('<I', buf, 0)[0]
            if plen < 8 or plen > (8 << 20) or len(buf) < plen:
                break
            payload = buf[4:plen]
            buf = buf[plen:]
            if len(payload) < 16:
                continue
            if payload[:4] == fcc('asyn') and payload[12:16] == fcc('rels'):
                rels += 1
                log('  rels %d/2' % rels)
    U.lib.usb_release_interface(h, 2)
    U.lib.usb_close(h)
    log('teardown done, rels=%d/2' % rels)
    return 0


if __name__ == '__main__':
    phase = (sys.argv[1] if len(sys.argv) > 1 else '').lower()
    if phase == 'to3':
        sys.exit(phase_to3())
    if phase == 'activate':
        sys.exit(phase_activate())
    if phase == 'teardown':
        sys.exit(phase_teardown())
    print(__doc__)
    sys.exit(9)
