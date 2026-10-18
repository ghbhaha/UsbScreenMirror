"""One-shot PC audio probe: wait for replug -> activate -> capture.

Why: a correctly torn-down session leaves the device idle (HPD0 means "display
unplugged"), and `activate` is a no-op while config set 5 already exists.  So a
physical replug is the only cheap way back to a fresh QuickTime handshake.  This
script waits for that replug, re-arms, then runs qt_stream.py.

Usage:
    python run_pc_audio.py [seconds]

Pure ASCII on purpose (Windows console encoding).
"""

import os
import subprocess
import sys
import time

import libusb0_win as U

VID, PID = 0x05AC, 0x12AB
HERE = os.path.dirname(os.path.abspath(__file__))


def cfg_count():
    U.init()
    _, d = U.find_by_vid_pid(VID, PID)
    if d is None:
        return None
    return d.descriptor.bNumConfigurations


def log(msg):
    print('%s  %s' % (time.strftime('%H:%M:%S'), msg), flush=True)


def wait_replug(timeout=300):
    log('waiting for you to UNPLUG the iPad (up to %ds)...' % timeout)
    t0 = time.time()
    while time.time() - t0 < timeout:
        if cfg_count() is None:
            log('  device gone - now plug it back in')
            break
        time.sleep(1)
    else:
        log('  never saw it unplugged, continuing anyway')
    t0 = time.time()
    while time.time() - t0 < timeout:
        c = cfg_count()
        if c is not None:
            log('  device back, cfg_count=%s' % c)
            return c
        time.sleep(1)
    log('  device never came back')
    return None


def activate_and_wait(limit=240):
    U.init()
    _, d = U.find_by_vid_pid(VID, PID)
    if d is None:
        log('device not found'); return None
    h = U.lib.usb_open(d)
    if not h:
        log('open failed: %s' % U.strerror()); return None
    # NOTE: deliberately do NOT call usb_set_configuration here. The device is
    # already in active_config=3 (that is what iPhoneMirror logs show), and
    # moving it there is exactly the call that BSOD'd this machine twice.
    n = U.lib.usb_control_msg(h, 0x40, 0x52, 0, 2, None, 0, 3000)
    log('activate => %d' % n)
    U.lib.usb_close(h)
    for i in range(limit):
        time.sleep(1)
        c = cfg_count()
        if c is not None and c >= 5:
            log('  [%ds] cfg_count=5 - device is armed' % (i + 1))
            return c
        if i % 10 == 9:
            log('  [%ds] cfg_count=%s' % (i + 1, c))
    log('  activate never took effect')
    return None


def main():
    seconds = sys.argv[1] if len(sys.argv) > 1 else '150'
    c = wait_replug()
    if c is None:
        return 2
    if c < 5:
        if activate_and_wait() is None:
            return 3
    log('starting capture for %ss (send HPA1)' % seconds)
    env = dict(os.environ)
    env['LIBUSB0_DLL'] = env.get('LIBUSB0_DLL', r'C:\Windows\System32\libusb0.dll')
    env['QT_HPA1'] = '1'
    env['QT_SECONDS'] = seconds
    py = sys.executable
    return subprocess.call([py, '-u', os.path.join(HERE, 'qt_stream.py')], env=env, cwd=HERE)


if __name__ == '__main__':
    sys.exit(main())
