# QuickTime USB 协议在 Windows 上的复现（2026-10-18，§四 于 10.19 更新）

> 目标：用**自己写的源码**（Python + libusb0）驱动 Apple 设备的 QuickTime 采集接口，
> 跑到「拿到视频流」这一步。代码在 `tools/qt-repro/`。
>
> ⚠️ **Android 端已按本文跑通**（2026-10-18 晚）。本文仍是协议的唯一权威参考——
> Android 侧 `:protocol/ios/QtProtocolCore` 的每条应答都对齐到这里描述的行为。

---

## 一、结果

在 iPad8,9 / iPadOS 27.2 + Windows 上，完整握手一次跑通，**收到 1260+ 个视频帧、70.6 MB**。

```
device bus=bus-0 cfg_count=4
activate => 0 ok
等待重枚举（activate 可能延迟数十秒才生效，耐心等）…
  ★ cfg_count=5 @1s
set_configuration(5) => 0
claim_interface(2) => 0 ok
  ◆ PING → 回写 16B
  ◆ PING → 回写 16B
  ◆ CWPA audio=519938567904 → HPD1+clock+HPA1
  ◆ CVRP video=519938960992 → NEED+clock
  · ASYN b'sprp' 62B
  · ASYN b'sprp' 57B
  · ASYN b'tbas' 24B
  · ASYN b'tjmp' 72B
  · ASYN b'srat' 48B
  ★ FEED #1 64938B
  ★ FEED #2 67653B
  ★ FEED #3 14140B
  … 每 60 帧打一行 …
  ★ FEED #1260 13540B
```

落盘 `capture/feed.bin` = **70,628,493 字节**，包结构：

```
pkt0: len=64938  magic='asyn'  clock=519940009743  sub='feed'
pkt1: len=67653  magic='asyn'  clock=519940009743  sub='feed'
pkt2: len=14140  magic='asyn'  clock=519940009743  sub='feed'
```

其中 `ASYN/sprp 62B`、`57B` 与 iPhoneMirror 日志里的 `async_sprp bytes=62/57` **逐字节对得上**，
说明我们解析到的包边界与官方实现一致。

> **后续（10.18 晚）**：Android 宿主（`app-host/ios` + `:protocol/ios`）按同一套流程跑通，
> 出画面出声音。**两端唯一的本质差异是 Android 必须自己建 lockdown 可信会话**，
> 详见 §四。

---

## 二、协议流程（实测版）

```
1. control 0x40/0x52 wValue=0 wIndex=2      ← 激活，设备重枚举出第 5 个配置
2. set_configuration(5)
3. claim_interface(2)                        ← 0xFF/0x2A，端点 0x86(IN)/0x05(OUT)
4. 设备发 PING  → 主机原样回 PING（16B）
5. 设备发 PING（第二次）→ 再回一次
6. 设备发 SYNC/CWPA，payload[24..32] = device_audio_clock
   → 主机发 ASYN/HPD1 + rply(clock=audio_clock+0x1000) + ASYN/HPA1(audio_clock)
7. 设备发 SYNC/CVRP，payload[24..32] = device_video_clock
   → 主机发 ASYN/need(video_clock) + rply(clock=video_clock+0x1000af)
8. 设备发 ASYN/sprp、tbas、tjmp、srat（媒体属性/时间基准）
9. 设备开始发 ASYN/feed —— 每包一个 CMSampleBuffer（视频帧）
   主机每收一帧补一个 ASYN/need，维持推流
```

报文格式统一为 `[u32 length][4B magic][payload...]`，length 含 4 字节头。

---

## 三、四个坑（都踩过）

### 3.1 fourcc 是字节反转的

`ping` 在线上是 `67 6e 69 70` 而不是 `70 69 6e 67`。
**构造和解析必须同时反转**。第一版只改了构造侧，结果收到了 `SYNC` 却落进 `else` 分支，
表现为"只到 PING"。

### 3.2 `libusb-1.0.dll` 在这台机器上不可用

```
active configuration unknown: USBError [Errno 5] Input/Output Error
activate → USBError [Errno 5]
```

它对 libusb0 filter 支持不良。必须用 `libusb0.dll`（libusb-win32），与 iPhoneMirror 同路。

### 3.3 `activate` 会延迟数十秒

返回 0 只是被 ack，重枚举可能几十秒后才发生。
曾因 45 秒内没等到就判定失败，随后发 `deactivate`（`0x52 wIndex=0`），
结果配置被打回 4、`activate` 变成 `-5`（NOT_FOUND），只能物理重启恢复。
**教训：activate 之后只等，不要"撤销重试"。**

### 3.4 切配置可导致蓝屏

`libusb0` 叠在 `AppleLowerFilter/AppleKmdfFilter` 上时，
`usb_set_configuration()` 切到**描述符里不存在的配置**会让内核崩溃（本机两次蓝屏）。
第二次疑似由 `set_altinterface(1)` 返回 `-5` 后的收尾触发。

现在的做法：只在 `bNumConfigurations >= 5` 后切 5；不设 alt；不 release，直接 close。

---

## 四、★ 与 Android 端的关键差异

> ⚠️ **本节已在 Android 端跑通后重写（10.19）。**
> 原文把「PING 次数不同」当成了突破口，那是**把症状当原因**——见 4.4。

### 4.1 现状：两端都通，报文层逐字节一致

Android 端（`:protocol/ios` + `app-host/ios`）现已跑通完整链路，出画面出声音：

```
PING#1 → 回写 16 B
PING#2 → 回写 16 B
CWPA audio=… → HPD1+clock+HPA1
AFMT  → reply
CVRP video=… → NEED+clock
ASYN sprp 62B/57B、tbas 24B、tjmp 72B、srat 48B
FEED #1 …（帧长 17~82KB 随画面浮动）
```

Windows 与 Android 的**报文层没有差异**：`QtProtocolCore` 的每条应答都是照
`RayrenSX/iPhoneMirror` 的 `QuickTimeSession.cpp` 逐字节对齐的，两端抓包可对上。

真正不同的只有**驱动/栈层**，以及一段**顺序**。

### 4.2 真正的差异（实测会咬人的那些）

| 维度 | Windows（libusb0） | Android（`UsbDeviceConnection`） | 踩过的坑 |
|---|---|---|---|
| **驱动栈** | libusb0 叠在 `AppleLowerFilter` 上 | 内核 `usbfs` | 切到描述符里不存在的配置会**蓝屏**（本机两次） |
| **选配置** | `usb_set_configuration(5)` 显式切 | `captureConfiguration()` **定位**含 0x2A 的配置 | `dev.getInterface()` 那张扁平表是**所有配置的接口并集**，找到 0x2A ≠ 它当前活动 |
| **setAltInterface** | **不做**（做了蓝屏） | **不做** | 参考实现会做，我们不做反而对 |
| **clearHalt** | **不做** | **不做** | 做了会把 FIFO 里待读的 PING 冲掉，iOS 不重发 ⇒ 连 PING 都没有 |
| **回写 PING** | 同步写 | **同步写**（`writeNow`） | 这是唯一不能进队列的报文：它是设备等着的握手 |
| **会话顺序** | 靠系统 AMDS 借道 | **必须自己建** lockdown 会话 | 见 4.3 |

> ⚠️ 三个"不做"（alt / clearHalt / kick）是 Windows 上踩出来、再被 Android 复用的。
> 详见 `QtTransport` 的类注释——**别把它们加回去**，它们不是"省事"，是"正确"。

### 4.3 ★ 唯一的本质差异：Android 必须自己建 lockdown 会话

这是两端**唯一**的本质区别，也是 Android 从"只有 PING×1"到"拿到视频流"的全部原因。

Windows 上 `usb_set_configuration(5)` 之后，系统里的 **Apple Mobile Device Service（AMDS）**
已经替我们建好了 usbmux/lockdown 可信会话（PC 侧配对记录）。
Android 没有这个服务—— **`setConfiguration(5)` 本身不会让设备开始推流**。

所以 Android 端必须自己把整条会话走一遍，且**顺序不能反**：

```
activate(0x40/0x52, wIndex=2)   → 触发真实重枚举（激活可能延迟数十秒）
  ↓ 等配置出现（别 deactivate！见 §3.3）
mux if#1：VERSION → SETUP → TCP SYN → QueryType
  → Pair（首次，设备弹「信任」）→ StartSession → TLS → GetValue → **后台保活**
  ↓ 只有会话可信之后
claim QT if#2（0xFF/0x2A，IN 0x86 / OUT 0x05）
  ↓
PING → CWPA → AFMT → CVRP → FEED
```

**三个条件必须同时满足，缺一不可**（10.18 逐个 A/B 验证过）：

| 条件 | 缺失时的现象 |
|---|---|
| ① 设备刚开机、`activate` 触发**真实重枚举** | 连 PING 都没有 |
| ② **受信任的 lockdown 会话**（Pair + StartSession + TLS + 保活） | 只有 PING×1，之后永久沉默 |
| ③ **先建会话、再 claim QT**（AMDS 的顺序） | 同 ②；顺序反了等于没会话 |

### 4.4 结论反转：「PING 次数」是判据，不是原因

原文写「设备在 Windows 上会发第二次 PING，Android 只收到一次，这是最有价值的线索」——
**方向反了。** 实测：

| | PING×1 | PING×2 |
|---|---|---|
| 会话可信？ | ❌ | ✅ |
| 有 CWPA？ | 无 | 有 |
| 有 FEED？ | 无 | 有 |

**PING 次数是 lockdown 会话是否可信的"指示灯"，不是推流的开关。**
在 `setConfiguration(5)` + `claim` 都做对之后，即使一个字节的会话流量都没有，
设备**照样会发第一个 PING**（这正是当初误判"只差传输层"的由来）。
第二个 PING 是设备确认"这个主机我认识"之后才发的，它对应的正是 `TrustedHostAttached`。

所以想复现 Android 侧的失败，**不要去调 USB 读写**——把 `mux/lockdown/Pair/TLS`
那一段走完，PING 就会变成两次，FEED 就会来。

### 4.5 顺带记一条：「PING×2 = 会推流」这个判据怎么用

它是**免费**的：只看日志头部两行，不用等 20s 超时、不用看 FEED 计数。
本项目把它同时用作**回归判据**——每次改 `QtProtocolCore` 的握手分支，
先看 PING 有没有变成两次，再看 FEED 起没起来。
**PING 仍是 1 ⇒ 别往下查视频，多半是会话/凭证层坏了。**

---

## 五、复现方法

见 `tools/qt-repro/README.md`。核心就一句：

```bash
# iPad 重启 → 解锁亮屏 → 插线 → 跑
python tools/qt-repro/qt_run_once.py
```

⚠️ 这台机器的驱动栈对第三方直接操作不稳定（已蓝屏两次），若要反复实验，
建议换到 Linux（`usbfs` 稳得多）或一台可牺牲的机器。
