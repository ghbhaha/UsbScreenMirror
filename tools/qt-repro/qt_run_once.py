"""一次运行完成：激活 → 等采集配置（最长 120s）→ 切配置 5 → claim → 握手 → 收 FEED。

刻意不做（避免再次蓝屏 / 再把设备打坏）：
  - 不 deactivate（实测一发就把配置打回 4 且 activate 返回 -5）
  - 不 set_altinterface（第二次蓝屏疑似触发点）
  - 不 release_interface（直接 close）
  - 只在 cfg_count>=5 时切配置 5（绝不切不存在的配置）

用法：重启 iPad、解锁亮屏、插好线，然后直接跑本脚本，中途不要动设备。
"""
import os
import struct
import sys
import time

import libusb0_win as U
import qt_stream as Q

VID, PID = 0x05AC, 0x12AB
EP_IN, EP_OUT = 0x86, 0x05
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'capture')


def find():
    U.init()
    return U.find_by_vid_pid(VID, PID)


def wait_capture_mode(max_sec=120):
    """等 cfg_count 变成 >=5。返回 device 或 None。"""
    t0 = time.time()
    while time.time() - t0 < max_sec:
        _, d = find()
        if d is None:
            print(f'  [{int(time.time()-t0)}s] device gone (re-enumerating)')
            time.sleep(2)
            continue
        cc = d.descriptor.bNumConfigurations
        if cc >= 5:
            print(f'  ★ cfg_count={cc} @{int(time.time()-t0)}s')
            return d
        if int(time.time() - t0) % 10 == 0:
            print(f'  [{int(time.time()-t0)}s] cfg_count={cc}')
        time.sleep(1)
    return None


def main():
    os.makedirs(OUT_DIR, exist_ok=True)

    bn, d = find()
    if d is None:
        print('device not found'); return 1
    print(f'device bus={bn} cfg_count={d.descriptor.bNumConfigurations}')

    if d.descriptor.bNumConfigurations < 5:
        h = U.lib.usb_open(d)
        if not h:
            print(f'open failed: {U.strerror()}'); return 2
        try:
            n = U.lib.usb_control_msg(h, 0x40, 0x52, 0, 2, None, 0, 3000)
            print(f'activate => {n} {U.strerror() if n < 0 else "ok"}')
        finally:
            U.lib.usb_close(h)
        print('等待重枚举（activate 可能延迟数十秒才生效，耐心等）…')
        d = wait_capture_mode(120)
        if d is None:
            print('\n✗ 120s 内没等到采集配置。设备需要重启（解锁亮屏）。')
            return 3

    # ---- 进入采集 ----
    h = U.lib.usb_open(d)
    if not h:
        print(f'open failed: {U.strerror()}'); return 4
    n = U.lib.usb_set_configuration(h, 5)
    print(f'set_configuration(5) => {n}')
    if n < 0:
        U.lib.usb_close(h); return 5
    n = U.lib.usb_claim_interface(h, 2)
    print(f'claim_interface(2) => {n} {U.strerror() if n < 0 else "ok"}')
    if n < 0:
        U.lib.usb_close(h); return 6

    t0 = time.time()
    buf_tail = b''
    st = dict(ping=0, cwpa=0, cvrp=0, afmt=0, clok=0, time=0, feed=0, other=0, bytes=0)
    audio_clock = 0
    video_clock = 0
    ff = open(os.path.join(OUT_DIR, 'feed.bin'), 'wb')
    try:
        while time.time() - t0 < 30:
            cnt, data = U.bulk_read(h, EP_IN, 1 << 20, 500)
            if cnt <= 0:
                continue
            st['bytes'] += cnt
            buf = buf_tail + data
            while len(buf) >= 4:
                plen = Q.read_u32(buf, 0)
                if plen < 8 or plen > (8 << 20):
                    print(f'  ! bad length {plen}, resync')
                    buf = b''; break
                if len(buf) < plen:
                    break
                payload = buf[4:plen]
                buf = buf[plen:]
                if len(payload) < 4:
                    continue
                magic = payload[:4]
                if magic == Q.fcc('ping'):
                    st['ping'] += 1
                    w = U.bulk_write(h, EP_OUT, Q.make_ping(), 1000)
                    print(f'  ◆ PING → 回写 {w}B')
                elif magic == Q.fcc('sync') and len(payload) >= 16:
                    corr = Q.read_u64(payload, 16)
                    sub = payload[12:16]
                    if sub == Q.fcc('cwpa') and len(payload) >= 32:
                        st['cwpa'] += 1
                        audio_clock = Q.read_u64(payload, 24)
                        U.bulk_write(h, EP_OUT, Q.make_hpd1(), 1000)
                        U.bulk_write(h, EP_OUT, Q.clock_reply(corr, audio_clock + 0x1000), 1000)
                        U.bulk_write(h, EP_OUT, Q.make_hpa1(audio_clock), 1000)
                        print(f'  ◆ CWPA audio={audio_clock} → HPD1+clock+HPA1')
                    elif sub == Q.fcc('cvrp') and len(payload) >= 32:
                        st['cvrp'] += 1
                        video_clock = Q.read_u64(payload, 24)
                        U.bulk_write(h, EP_OUT, Q.make_need(video_clock), 1000)
                        U.bulk_write(h, EP_OUT, Q.clock_reply(corr, video_clock + 0x1000af), 1000)
                        print(f'  ◆ CVRP video={video_clock} → NEED+clock')
                    elif sub == Q.fcc('afmt'):
                        st['afmt'] += 1
                        U.bulk_write(h, EP_OUT, Q.afmt_reply(corr), 1000)
                        print('  ◆ AFMT → reply')
                    elif sub == Q.fcc('clok'):
                        st['clok'] += 1
                        U.bulk_write(h, EP_OUT, Q.clock_reply(corr, Q.read_u64(payload, 4) + 0x10000), 1000)
                    elif sub == Q.fcc('time'):
                        st['time'] += 1
                        U.bulk_write(h, EP_OUT, Q.time_reply(corr, int((time.time()-t0)*1e9)), 1000)
                    else:
                        st['other'] += 1
                        print(f'  · SYNC {sub[::-1]!r}')
                elif magic == Q.fcc('asyn') and len(payload) >= 16:
                    sub = payload[12:16]
                    if sub == Q.fcc('feed'):
                        st['feed'] += 1
                        ff.write(struct.pack('<I', len(payload)) + payload)
                        if st['feed'] <= 3 or st['feed'] % 60 == 0:
                            print(f'  ★ FEED #{st["feed"]} {len(payload)}B')
                        if video_clock:
                            U.bulk_write(h, EP_OUT, Q.make_need(video_clock), 1000)
                    else:
                        st['other'] += 1
                        print(f'  · ASYN {sub[::-1]!r} {len(payload)}B')
                elif magic == Q.fcc('rply'):
                    st['other'] += 1
                else:
                    st['other'] += 1
                    print(f'  ? {magic[::-1]!r} {len(payload)}B')
            buf_tail = buf
    finally:
        ff.close()
        U.lib.usb_close(h)

    dt = time.time() - t0
    print(f'\n=== 结果（{dt:.1f}s）===')
    print(f'  字节 {st["bytes"]}  包 { {k: v for k, v in st.items() if k != "bytes"} }')
    if st['feed']:
        sz = os.path.getsize(os.path.join(OUT_DIR, 'feed.bin'))
        print(f'  ★★★ 拿到视频流：{st["feed"]} 个 FEED，{sz} 字节 → capture/feed.bin')
        return 0
    if st['ping'] or st['cwpa']:
        print('  ✗ 握手有响应但没到 FEED')
        return 7
    print('  ✗ 没有报文')
    return 8


if __name__ == '__main__':
    sys.exit(main())
