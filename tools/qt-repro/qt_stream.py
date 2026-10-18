"""QuickTime USB 协议最小复现（Windows / libusb0）。

目标：从 iPad 拿到视频流（收到 ASYN/FEED 即算成功）。
协议依据：danielpaulus/quicktime_video_hack + RayrenSX/iPhoneMirror 的 QuickTimeSession.cpp。

流程：
    配置设备 → activate(0x40/0x52/wIndex=2) → 等配置 5 出现 → set_configuration(5)
    → claim if#2 → 读循环：
        PING  → 回 PING
        CWPA  → 回 HPD1 + clock_reply + HPA1      （音频时钟）
        AFMT  → 回 afmt_reply
        CVRP  → 回 NEED + clock_reply             （视频时钟）
        CLOK  → clock_reply
        TIME  → time_reply
        FEED  → ★ 视频样本，落盘
        EAT!  → ★ 音频样本：抽出 sdat 段里的裸 PCM 落盘（用于对照安卓端拿不到音频的问题）

注意：只在「配置 5 确实存在」时才 set_configuration(5)。切到不存在的配置会让驱动栈崩溃
（本机曾因此蓝屏）。
"""
import os
import struct
import sys
import time

import libusb0_win as U

VID, PID = 0x05AC, 0x12AB
EP_IN, EP_OUT = 0x86, 0x05
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'capture')

# ---------------------------------------------------------------- 报文构造

def fcc(name: str) -> bytes:
    """fourcc 在线上是小端存储的 u32 => ASCII 顺序反过来。

    例：'ping' -> 67 6e 69 70（设备实际发的就是这四个字节）。
    """
    return name.encode('ascii')[::-1]


def u32(v):
    return struct.pack('<I', v & 0xFFFFFFFF)


def u64(v):
    return struct.pack('<Q', v & 0xFFFFFFFFFFFFFFFF)


def f64(v):
    return struct.pack('<d', v)


def wrap(magic: bytes, payload: bytes) -> bytes:
    return u32(8 + len(payload)) + magic + payload


def num_u32(v):
    return wrap(fcc('nmbv'), b'\x03' + u32(v))


def num_f64(v):
    return wrap(fcc('nmbv'), b'\x06' + f64(v))


def boolean(v):
    return wrap(fcc('bulv'), b'\x01' if v else b'\x00')


def string(v):
    return wrap(fcc('strv'), v.encode())


def dictionary(items):
    body = b''
    for k, val in items:
        body += wrap(fcc('keyv'), wrap(fcc('strk'), k.encode()) + val)
    return wrap(fcc('dict'), body)


def async_dict(subtype: bytes, clock: int, d: bytes) -> bytes:
    return u32(20 + len(d)) + fcc('asyn') + u64(clock) + subtype + d


def make_ping():
    return u32(16) + fcc('ping') + u32(0) + u32(1)


def make_hpd1(width=1640, height=2360):
    """ASYN/HPD1：显示描述。Valeria=true 是 iPad/新机型的"演示模式"。"""
    display = dictionary([('Width', num_f64(width)), ('Height', num_f64(height))])
    return async_dict(fcc('hpd1'), 1, dictionary([
        ('Valeria', boolean(True)),
        ('HEVCDecoderSupports444', boolean(False)),
        ('DisplaySize', display),
    ]))


def make_hpa1(audio_clock):
    descriptor = (f64(48000.0) + b'lpcm' + u32(12) + u32(4) + u32(1) +
                  u32(4) + u32(2) + u32(16) + u32(0) + f64(48000.0) + f64(48000.0))
    return async_dict(fcc('hpa1'), audio_clock, dictionary([
        ('BufferAheadInterval', num_f64(0.073)),
        ('deviceUID', string('Valeria')),
        ('ScreenLatency', num_f64(0.04)),
        ('formats', wrap(fcc('datv'), descriptor)),
        ('EDIDAC3Support', num_u32(0)),
        ('deviceName', string('Valeria')),
    ]))


def make_need(device_video_clock):
    return u32(20) + fcc('asyn') + u64(device_video_clock) + fcc('need')


def clock_reply(corr, clock):
    return u32(28) + fcc('rply') + u64(corr) + u32(0) + u64(clock)


def extract_pcm(payload: bytes):
    """从 EAT! 帧里抽出 sdat 段（裸 PCM）。

    线上 fourcc 是反转的，所以源码里写的是 'tads'（'sdat' 的线上形式）。
    段布局：[u32 长度（含 8B 头）]['tads'][PCM…]
    """
    at = payload.find(b'tads', 16)
    if at < 4:
        return None
    ln = int.from_bytes(payload[at - 4:at], 'little')
    if ln < 8:
        return None
    data = payload[at + 4: at - 4 + ln]
    return data if data else None


def afmt_reply(corr):
    d = dictionary([('Error', num_u32(0))])
    return u32(20 + len(d)) + fcc('rply') + u64(corr) + u32(0) + d


def time_reply(corr, ns):
    return u32(44) + fcc('rply') + u64(corr) + u32(0) + u64(ns) + u32(1_000_000_000) + u32(1) + u64(0)


def zero_reply(corr):
    return u32(24) + fcc('rply') + u64(corr) + u64(0)


# ---------------------------------------------------------------- 设备操作

def find():
    U.init()
    return U.find_by_vid_pid(VID, PID)


def read_u32(b, off):
    return struct.unpack_from('<I', b, off)[0]


def read_u64(b, off):
    return struct.unpack_from('<Q', b, off)[0]


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    bn, d = find()
    if d is None:
        print('device not found'); return 1
    print(f'device bus={bn} cfg_count={d.descriptor.bNumConfigurations}')

    h = U.lib.usb_open(d)
    if not h:
        print(f'open failed: {U.strerror()}'); return 2

    # 1) 已经在采集配置（cfg_count>=5）就别再 activate —— activate 反而可能把它打回 4
    dev = None
    if d.descriptor.bNumConfigurations >= 5:
        print(f'already in capture mode (cfg_count={d.descriptor.bNumConfigurations}) — skip activate')
        dev = d
        U.lib.usb_close(h)
    else:
        n = U.lib.usb_set_configuration(h, 1)
        print(f'set_configuration(1) => {n}')
        n = U.lib.usb_control_msg(h, 0x40, 0x52, 0, 2, None, 0, 3000)
        print(f'activate => {n}')
        U.lib.usb_close(h)
        # 实测重枚举可能延迟数分钟（本仓库真机记录过 200s+），窗口给足
        for i in range(200):
            time.sleep(1)
            _, d2 = find()
            if d2 is None:
                print(f'  [{i+1}s] gone'); continue
            cc = d2.descriptor.bNumConfigurations
            print(f'  [{i+1}s] cfg_count={cc}')
            if cc >= 5:
                dev = d2
                break
        if dev is None:
            print('\n✗ 设备没有进入采集模式。请重启 iPad（解锁亮屏）后立刻重跑。')
            return 3

    # 2) 切到配置 5（设备上确实存在才切）

    h = U.lib.usb_open(dev)
    if not h:
        print(f'open failed: {U.strerror()}'); return 4
    n = U.lib.usb_set_configuration(h, 5)
    print(f'set_configuration(5) => {n}')
    if n < 0:
        U.lib.usb_close(h); return 5
    n = U.lib.usb_claim_interface(h, 2)
    print(f'claim_interface(2) => {n}')
    if n < 0:
        print(f'  {U.strerror()}'); U.lib.usb_close(h); return 6
    for alt in (0, 1):
        r = U.lib.usb_set_altinterface(h, alt)
        print(f'set_altinterface({alt}) => {r}')

    # 5) 握手 + 收流
    t0 = time.time()
    buf_tail = b''
    stats = dict(ping=0, cwpa=0, cvrp=0, afmt=0, clok=0, time=0, feed=0, eat=0, other=0, bytes=0)
    audio_clock = 0
    video_clock = 0
    feed_file = open(os.path.join(OUT_DIR, 'feed.bin'), 'wb')
    eat_file = open(os.path.join(OUT_DIR, 'audio.pcm'), 'wb')
    eat_bytes = [0]
    try:
        while time.time() - t0 < 25:
            n, data = U.bulk_read(h, EP_IN, 1 << 20, 500)
            if n <= 0:
                if time.time() - t0 > 6 and stats['feed'] == 0 and stats['ping'] == 0:
                    pass
                continue
            stats['bytes'] += n
            buf = buf_tail + data
            while len(buf) >= 4:
                plen = read_u32(buf, 0)
                if plen < 8 or plen > 8 << 20:
                    print(f'  ! bad packet length {plen}, resync')
                    buf = b''
                    break
                if len(buf) < plen:
                    break
                payload = buf[4:plen]
                buf = buf[plen:]
                if len(payload) < 4:
                    continue
                magic = payload[:4]
                if magic == fcc('ping'):
                    stats['ping'] += 1
                    w = U.bulk_write(h, EP_OUT, make_ping(), 1000)
                    print(f'  ◆ PING  → 回写 {w}B')
                elif magic == fcc('sync') and len(payload) >= 16:
                    corr = read_u64(payload, 16)
                    sub = payload[12:16]
                    if sub == fcc('cwpa') and len(payload) >= 32:
                        stats['cwpa'] += 1
                        audio_clock = read_u64(payload, 24)
                        U.bulk_write(h, EP_OUT, make_hpd1(), 1000)
                        U.bulk_write(h, EP_OUT, clock_reply(corr, audio_clock + 0x1000), 1000)
                        U.bulk_write(h, EP_OUT, make_hpa1(audio_clock), 1000)
                        print(f'  ◆ CWPA audio_clock={audio_clock} → HPD1+clock+HPA1')
                    elif sub == fcc('cvrp') and len(payload) >= 32:
                        stats['cvrp'] += 1
                        video_clock = read_u64(payload, 24)
                        U.bulk_write(h, EP_OUT, make_need(video_clock), 1000)
                        U.bulk_write(h, EP_OUT, clock_reply(corr, video_clock + 0x1000af), 1000)
                        print(f'  ◆ CVRP video_clock={video_clock} → NEED+clock')
                    elif sub == fcc('afmt'):
                        stats['afmt'] += 1
                        U.bulk_write(h, EP_OUT, afmt_reply(corr), 1000)
                        print('  ◆ AFMT → afmt_reply')
                    elif sub == fcc('clok'):
                        stats['clok'] += 1
                        U.bulk_write(h, EP_OUT, clock_reply(corr, read_u64(payload, 4) + 0x10000), 1000)
                    elif sub == fcc('time'):
                        stats['time'] += 1
                        U.bulk_write(h, EP_OUT, time_reply(corr, int((time.time() - t0) * 1e9)), 1000)
                    else:
                        stats['other'] += 1
                        print(f'  · SYNC {sub!r}')
                elif magic == fcc('asyn') and len(payload) >= 16:
                    sub = payload[12:16]
                    if sub == fcc('feed'):
                        stats['feed'] += 1
                        feed_file.write(len(payload).to_bytes(4, 'little') + payload)
                        if stats['feed'] <= 3 or stats['feed'] % 60 == 0:
                            print(f'  ★ FEED #{stats["feed"]}  {len(payload)}B')
                        if video_clock:
                            U.bulk_write(h, EP_OUT, make_need(video_clock), 1000)
                    elif sub == fcc('eat!'):
                        stats['eat'] += 1
                        pcm = extract_pcm(payload)
                        if pcm:
                            eat_file.write(pcm)
                            eat_bytes[0] += len(pcm)
                            if stats['eat'] <= 3 or stats['eat'] % 200 == 0:
                                print(f'  ♪ EAT #{stats["eat"]} {len(payload)}B → PCM {len(pcm)}B'
                                      f'（累计 {eat_bytes[0]}B）')
                        else:
                            print(f'  ♪ EAT #{stats["eat"]} 结构未识别（{len(payload)}B）')
                    else:
                        stats['other'] += 1
                        print(f'  · ASYN {sub!r} {len(payload)}B')
                elif magic == fcc('rply'):
                    stats['other'] += 1
                else:
                    stats['other'] += 1
                    print(f'  ? {magic!r} {len(payload)}B')
            buf_tail = buf
    finally:
        feed_file.close()
        eat_file.close()
        try:
            U.lib.usb_release_interface(h, 2)
        except Exception:
            pass
        U.lib.usb_close(h)

    dt = time.time() - t0
    print(f'\n=== 结果（{dt:.1f}s）===')
    print(f'  字节 {stats["bytes"]}  包统计 { {k: v for k, v in stats.items() if k != "bytes"} }')
    if stats['eat']:
        print(f'  ♪♪ 收到音频：{stats["eat"]} 个 EAT 包 / {eat_bytes[0]} 字节 PCM → {OUT_DIR}/audio.pcm')
    else:
        print('  ✗ 一个 EAT 帧都没有（PC 侧也拿不到音频）')
    if stats['feed']:
        size = os.path.getsize(os.path.join(OUT_DIR, 'feed.bin'))
        print(f'  ★★★ 收到视频流：{stats["feed"]} 个 FEED 包，落盘 {size} 字节 → {OUT_DIR}/feed.bin')
        return 0
    if stats['ping']:
        print('  ✗ 只到 PING，没有 CWPA/FEED（与本仓库 Android 端的现象一致）')
        return 7
    print('  ✗ 没有任何报文')
    return 8


if __name__ == '__main__':
    sys.exit(main())
