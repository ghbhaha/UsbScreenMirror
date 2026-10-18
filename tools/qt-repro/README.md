# qt-repro — QuickTime USB 协议的最小复现（Python）

用 Python 直接驱动 Apple 设备的 QuickTime USB 采集接口，**不依赖任何第三方闭源程序**，
目标是跑到「拿到视频流」这一步（收到 `ASYN/feed` 即成功）。

协议依据：`danielpaulus/quicktime_video_hack`（协议事实标准）与
`RayrenSX/iPhoneMirror` 的 `QuickTimeSession.cpp`（状态机）。

## 文件

| 文件 | 作用 |
|---|---|
| `libusb0_win.py` | `libusb0.dll`（libusb-win32）的 ctypes 封装：枚举 / open / control / bulk 读写 |
| `qt_stream.py` | 报文构造（ping / hpd1 / hpa1 / need / clock_reply / afmt_reply）+ 握手状态机 |
| `qt_run_once.py` | **一次跑完**：激活 → 等采集配置 → 切配置 5 → claim → 握手 → 收 FEED 落盘 |

## 依赖

- Python 3.10+
- `libusb0.dll`（libusb-win32，64 位）。查找顺序：
  1. 环境变量 `LIBUSB0_DLL`
  2. 与本目录同级（把 dll 拷过来）
  3. 系统 PATH
- Apple 设备需已安装 Apple 的 USB 驱动（iTunes / Apple Devices 会带）

> **不要用 `libusb-1.0.dll`**：在本项目的实测机上，它对 libusb0 filter 支持不良
> （读 active configuration 直接 I/O error、vendor control 报 Errno 5）。

## 用法

```bash
# 1) 把 iPad 重启，开机后解锁、保持亮屏、插好线
# 2) 一次跑完（约 30 秒，其中可能花数十秒等设备重枚举）
python qt_run_once.py
```

成功时输出形如：

```
activate => 0 ok
  ★ cfg_count=5 @1s
set_configuration(5) => 0
claim_interface(2) => 0 ok
  ◆ PING → 回写 16B
  ◆ CWPA audio=519938567904 → HPD1+clock+HPA1
  ◆ CVRP video=519938960992 → NEED+clock
  ★ FEED #1 64938B
  ...
  ★★★ 拿到视频流：1260 个 FEED，70.6 MB → capture/feed.bin
```

`capture/feed.bin` 是 `[u32 len][payload]` 的拼接，payload 为完整的 `ASYN/feed` 报文
（含 8 字节头 + CMSampleBuffer 序列化数据）。

## ⚠️ 三条硬教训（都是实测踩出来的）

### 1. fourcc 在线上是**字节反转**的

`fourcc('p','i','n','g')` 是大端整数、按小端存储，所以设备实际发的是 `67 6e 69 70`。
构造和解析**两侧都要转**：

```python
def fcc(name):            # 'ping' -> b'gnip' 的字节序
    return name.encode('ascii')[::-1]
```

我第一次只转了构造侧、没转解析侧，结果收到了 `SYNC` 却认不出来，停在 PING。

### 2. `activate` 可能**延迟数十秒**才生效

`0x40/0x52/wIndex=2` 返回 0 只代表被 ack，设备重枚举可能几十秒后才发生。
**不要在 45 秒内就判定失败**——本项目就因此误判，随后发了 `deactivate`
把设备打回配置 4 且 `activate` 变成 `-5`（NOT_FOUND），只能物理重启恢复。

### 3. 切配置会让驱动栈崩溃（蓝屏）

`libusb0` 叠在 Apple 的 `AppleLowerFilter/AppleKmdfFilter` 上时，
`usb_set_configuration()` 切到**设备描述符里不存在的配置**会导致内核崩溃（本机蓝屏两次）。

规则：
- 只在 `bNumConfigurations >= 5`（采集配置确实出现）之后才切 5；
- 不 `set_altinterface`（本项目第二次蓝屏疑似由此触发）；
- 不反复 `claim/release`，用完直接 `close`。

## 与 Android 端的关键差异

同一台 iPad、同一套协议，**Windows 上全通，Android 上停在 PING**：

| 阶段 | Windows（本脚本） | Android（`app-probe`） |
|---|---|---|
| PING | **收到两次**，各回写 16B | 只收到**一次** |
| CWPA | ✅ 第二次 PING 之后到达 | ❌ 从不到达 |
| HPD1 写入 | ✅ 成功 | ❌ 返回 -1（OUT 端点 STALL） |
| FEED | ✅ 1260+ 帧 | — |

这个「两次 PING」的差异是目前最有价值的线索：设备在 Windows 上会再发一次 PING，
之后才进 `WaitingForAudioClock`。Android 侧为什么只收到一次，尚未定位。

## 抓包：USBPcap（对比 iPhoneMirror 有音频、我们没音频）

### 安装（只需一次，**装完必须重启**）

```bash
winget install --id desowin.USBPcap -e --accept-package-agreements --accept-source-agreements
```

安装器结尾若打印

```
PCI\VEN_8086&DEV_51ED...: Failed to invoke DIF_PROPERTYCHANGE! Please reboot.
```

说明过滤器（`USBPcap` 已写进 USB 类 `UpperFilters`）**没能热挂到已存在的主控上**，
必须重启电脑才生效。判断方法：`USBPcapCMD.exe -A -o x.pcap` 跑几秒仍**不产生文件** = 还没挂上。

### 抓

```bash
# iPad 插 PC、iPhoneMirror 用【有线】模式连上并开始投屏后：
"/c/Program Files/USBPcap/USBPcapCMD.exe" -A -o capture/qt_audio.pcap
# Ctrl+C 结束。-A = 所有设备（-d "\\.\USBPcapN" 只抓某个根集线器，更小但要知道 N）
```

### 看

`usbpcap_dump.py` 把 pcap 转成和两端日志能直接对照的时序（不需要 Wireshark）：

```bash
python usbpcap_dump.py capture/qt_audio.pcap            # 全量时序
python usbpcap_dump.py capture/qt_audio.pcap --out      # 只看主机→设备（"我们该发什么"）
python usbpcap_dump.py capture/qt_audio.pcap --fcc      # fourcc 统计（有无 eat! 一眼看出）
```

输出形如：

```
  0.000 OUT ctrl ep=0x00 dev=7  setup bm=0x40 req=0x52 wValue=0x0000 wIndex=0x0002 len=0   ← activate
  1.234 OUT bulk ep=0x05 dev=7  len=24    fcc='hpd1'
  2.345 IN  bulk ep=0x86 dev=7  len=72    fcc='eat!'
```

⚠️ 抓包期间**不要**跑本目录的 `qt_stream.py`（它会 `activate`/切配置，本机蓝屏过两次）。
抓包只做观察，别扰动设备状态。
