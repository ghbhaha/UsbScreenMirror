# QuickTime USB 协议在 Windows 上的复现（2026-10-18）

> 目标：用**自己写的源码**（Python + libusb0）驱动 Apple 设备的 QuickTime 采集接口，
> 跑到「拿到视频流」这一步。代码在 `tools/qt-repro/`。

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

**同一台 iPad、同一套协议，Windows 全通，Android 停在 PING。**

| 阶段 | Windows（本复现） | Android（`app-probe`） |
|---|---|---|
| PING | **两次** | **一次** |
| CWPA | ✅ 第二次 PING 后到达 | ❌ 从不到达 |
| HPD1 写入 | ✅ 成功 | ❌ `-1`（OUT 端点 STALL） |
| FEED | ✅ 1260+ 帧 | — |

「设备在 Windows 上会发第二次 PING」是目前最有价值的线索。
Android 侧为什么只收到一次，尚未定位——可能的方向：

1. Android 回写 PING 的字节/时序让设备认为"这次会话不需要再 PING"；
2. Android 的 URB 提交方式（同步 bulkTransfer + 5s 超时）导致第二次 PING 被合并/丢弃；
3. 两条 USB 主机栈在"短包"语义上的差异。

这条差异比之前所有猜测都具体，值得作为下一步的唯一突破口。

---

## 五、复现方法

见 `tools/qt-repro/README.md`。核心就一句：

```bash
# iPad 重启 → 解锁亮屏 → 插线 → 跑
python tools/qt-repro/qt_run_once.py
```

⚠️ 这台机器的驱动栈对第三方直接操作不稳定（已蓝屏两次），若要反复实验，
建议换到 Linux（`usbfs` 稳得多）或一台可牺牲的机器。
