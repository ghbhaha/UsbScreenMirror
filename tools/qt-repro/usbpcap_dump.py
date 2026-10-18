"""把 USBPcap 抓到的 pcap 转成"人类可读的线上时序"。

为什么不用 Wireshark：我们要的不是协议树，而是**一条能和我们两端日志直接对照的时序**。
iPhoneMirror 的 USB 会话里有音频而我们没有 —— 逐包对比时只需要看
「谁在什么时刻往 0x05 写了什么帧、从 0x86 收到了什么帧」，控制传输里的 setup 包
（尤其 `activate` 的 0x40/0x52）也一并打出来。

用法：
    python usbpcap_dump.py capture/qt_audio.pcap            # 全量时序
    python usbpcap_dump.py capture/qt_audio.pcap --out      # 只看主机→设备（我们"该发什么"）
    python usbpcap_dump.py capture/qt_audio.pcap --fcc      # 只看 fourcc 统计
    python usbpcap_dump.py capture/qt_audio.pcap --since 3  # 跳过前 3 秒

USBPcap 的链路类型是 249（DLT_USBPCAP）。每帧前面有一段它自己的头：

    u16 headerLen, u64 irpId, u32 status, u16 function, u8 info,
    u16 bus, u16 device, u8 endpoint, u8 transfer, u32 dataLength     # 27B
    （control 传输再多 1B stage ⇒ headerLen = 28）

`info` 位：0x01 = PDO→FDO（设备→主机），0x02 = FDO→PDO（主机→设备），0x04 = 完成，0x08 = short。
`transfer`：0=isochronous 1=interrupt 2=control 3=bulk。

⚠️ 报文里的 fourcc 在线上是**反序**的（`hpd1` 的线上字节是 `31 64 70 68`），所以这里统一反转后再显示，
方便和我们两端日志里的 `hpd1/cwpa/afmt/eat!` 直接对。
"""

import argparse
import struct
import sys
from collections import Counter

DLT_USBPCAP = 249
XFER = {0: "iso", 1: "intr", 2: "ctrl", 3: "bulk"}


def iter_frames(path):
    """逐帧产出 (ts_seconds, raw_bytes)。自动识别 pcap 的字节序。"""
    with open(path, "rb") as f:
        gh = f.read(24)
        if len(gh) < 24:
            raise SystemExit("不是 pcap（文件太短）")
        magic = gh[:4]
        if magic == b"\xd4\xc3\xb2\xa1":
            endian = "<"
        elif magic == b"\xa1\xb2\xc3\xd4":
            endian = ">"
        elif magic == b"\x4d\x3c\xb2\xa1":
            raise SystemExit("这是 pcapng，不是 pcap —— USBPcapCMD 请用 -o xx.pcap")
        else:
            raise SystemExit("不认识的 pcap magic: %r" % magic)

        linktype = struct.unpack(endian + "I", gh[20:24])[0]
        if linktype != DLT_USBPCAP:
            raise SystemExit("链路类型是 %d，不是 USBPcap(249)" % linktype)

        rec_hdr = struct.Struct(endian + "IIII")
        while True:
            h = f.read(16)
            if len(h) < 16:
                return
            ts_sec, ts_usec, incl, _orig = rec_hdr.unpack(h)
            data = f.read(incl)
            if len(data) < incl:
                return
            yield ts_sec + ts_usec / 1e6, data


def parse_usbpcap(data):
    """拆 USBPcap 头。返回 dict；头不合法返回 None。"""
    if len(data) < 27:
        return None
    (hlen, irp, status, function, info,
     bus, device, endpoint, transfer, dlen) = struct.unpack("<HQIHBHHBBI", data[:27])
    if hlen < 27 or hlen > len(data):
        return None
    pkt = dict(
        hlen=hlen, irp=irp, status=status, info=info,
        bus=bus, device=device, endpoint=endpoint,
        transfer=transfer, dlen=dlen, payload=data[hlen:],
    )
    pkt["dir"] = (("out" if info & 0x02 else "") + ("in" if info & 0x01 else "")) or "?"
    pkt["complete"] = bool(info & 0x04)
    if transfer == 2 and hlen >= 28:
        pkt["stage"] = data[27]
    return pkt


def rev_fcc(b):
    """线上 fourcc 是反序的 ⇒ 反转后才是 cwpa/hpd1/eat! 这种可读串。"""
    if len(b) < 4:
        return ""
    s = bytes(reversed(b[:4]))
    return s.decode("latin1").strip("\x00") if all(32 <= c < 127 for c in s) else ""


def describe(ts, pkt, t0):
    rel = ts - t0
    tag = "OUT" if "out" in pkt["dir"] else ("IN " if "in" in pkt["dir"] else "?  ")
    ep = pkt["endpoint"]
    body = ""

    if pkt["transfer"] == 2:
        # 控制传输：setup 包里才有 bmRequestType/bRequest/wValue/wIndex
        if pkt.get("stage") == 0 and len(pkt["payload"]) >= 8:
            bm, req, val, idx, ln = struct.unpack("<BBHHH", pkt["payload"][:8])
            body = "setup bm=0x%02x req=0x%02x wValue=0x%04x wIndex=0x%04x len=%d" % (
                bm, req, val, idx, ln)
            # activate（AOA/QT 切采集配置）就是这个，看它在这台设备上到底发没发
            if bm == 0x40 and req == 0x52:
                body += "   ← activate"
        else:
            body = "data %dB" % len(pkt["payload"])
    else:
        fcc = rev_fcc(pkt["payload"][4:8]) if len(pkt["payload"]) >= 8 else ""
        # ASYN/FEED/EAT 的载荷前面是 4B 长度 + 4B 反序 fourcc
        if fcc and fcc.isprintable():
            body = "len=%-5d fcc=%r" % (pkt["dlen"], fcc)
        else:
            body = "len=%-5d %s" % (pkt["dlen"], pkt["payload"][:16].hex(" "))

    return "%7.3f %s %-4s ep=0x%02x dev=%-2d %s" % (
        rel, tag, XFER.get(pkt["transfer"], "?"), ep, pkt["device"], body)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pcap")
    ap.add_argument("--out", action="store_true", help="只看主机→设备")
    ap.add_argument("--in", dest="only_in", action="store_true", help="只看设备→主机")
    ap.add_argument("--fcc", action="store_true", help="只输出 fourcc 统计")
    ap.add_argument("--since", type=float, default=0.0, help="跳过前 N 秒（对齐握手之后）")
    ap.add_argument("--until", type=float, default=None, help="只看到第 N 秒")
    ap.add_argument("--ep", type=lambda s: int(s, 0), default=None, help="只看某个端点")
    ap.add_argument("--max", type=int, default=100000)
    args = ap.parse_args()

    t0 = None
    count = 0
    stat = Counter()

    for ts, raw in iter_frames(args.pcap):
        if t0 is None:
            t0 = ts
        pkt = parse_usbpcap(raw)
        if pkt is None:
            continue
        rel = ts - t0
        if rel < args.since:
            continue
        if args.until is not None and rel > args.until:
            break
        if args.ep is not None and pkt["endpoint"] != args.ep:
            continue
        if args.out and "out" not in pkt["dir"]:
            continue
        if args.only_in and "in" not in pkt["dir"]:
            continue

        if args.fcc:
            if pkt["transfer"] == 3 and len(pkt["payload"]) >= 8:
                fcc = rev_fcc(pkt["payload"][4:8])
                stat[(pkt["dir"], fcc or "?")] += 1
            continue

        print(describe(ts, pkt, t0))
        count += 1
        if count >= args.max:
            print("…（超过 %d 帧，已截断；用 --since/--until/--ep 收窄）" % args.max)
            break

    if args.fcc:
        total = sum(stat.values())
        print("=== fourcc 统计（共 %d 个 bulk 帧） ===" % total)
        for (d, fcc), n in stat.most_common():
            print("  %-4s %-8r %d" % (d, fcc, n))


if __name__ == "__main__":
    sys.exit(main())
