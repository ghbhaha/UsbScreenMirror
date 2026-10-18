# iOS USB 投屏（安卓宿主）—— 已打通的实现记录

> 状态：**实时投屏可用**。iPad 主屏经 USB 线实时渲染到安卓宿主，**可反复开关，无需重启设备**。
> 本文只记"怎么做才不踩坑"，协议细节的探索过程见 `docs/QT协议Windows复现.md` 与 `docs/Apple_USB投屏方案全网调研.md`。

---

## 一、结论先行

| 能力 | 状态 |
|---|---|
| 实时投屏（USB 有线） | ✅ 点一下起流，首帧约 **0.8~1.0s** |
| 反复开关 | ✅ 连开三次实测通过，**不需要重启 iPad** |
| 依赖设备端安装 App | ❌ 不需要 |
| 依赖开发者模式 | ❌ 不需要 |
| 配对 | ⚠️ 当前导入的是 PC 上已生效的配对凭证；App 自建配对仍未解决 |

代码在 `app-ios-qt`（包名 `com.uav.iosqt`，minSdk 24），约 1700 行 / 8 个文件。

---

## 二、三个必须同时满足的条件

这三条是花了一整天才逐个排除出来的，**缺任何一条都拿不到流**：

### ① 设备侧 QT 会话必须先"武装"

设备不会主动进入采集模式。`activate` 请求（`controlTransfer(0x40, 0x52, wValue=0, wIndex=2)`）
只在**配置 5 尚不存在**时才触发真实重枚举；配置 5 已存在时它是**空操作**。

⇒ 要让设备反复可用，靠的是 **配置来回**（见第四节），不是反复 `activate`。
`activate` 的重枚举**可能延迟 2~3 分钟**才生效——等待窗口必须给到 300 秒，否则会误判成失败。

### ② 必须有一条**受信任的 lockdown 会话**，且要先建后 claim

2026-10-18 的决定性对照（PC 上做减法，唯一变量是 Apple 的常驻服务 AMDS）：

| 条件 | PING | CWPA | FEED |
|---|---|---|---|
| AMDS 在跑（受信任会话存在） | **2 次** | ✅ | 1421 帧 |
| AMDS 停掉（无会话） | **1 次** | ❌ | 0 |

⇒ **判据很好用：PING 两次 = 会推流；PING 一次 = 不会。** 不用等画面就能判断会话建对没。

### ③ 顺序必须是「先 lockdown，再 claim QT」

此前所有实现都是「QT 先开读、mux 后建」，顺序反了等于没建会话。

---

## 三、完整流程

```
1) 找设备（VID 0x05AC）→ 申请权限
2) 若设备停在配置 5（上次残留）：setConfiguration(3) → 等 300ms     ← 重新武装
3) 否则：activate → 等 300s 内出现配置 5
4) setConfiguration(5)                      ← 此时**不能有任何接口被 claim**
5) 第二条连接：claim 接口 1（0xFF/0xFE/02）→ 建 lockdown 会话
     VERSION(8B 头, major=2) → SETUP(载荷 0x07, tx=0/rx=0xFFFF)
     → TCP SYN → :62078 → SYN/ACK → QueryType
     → StartSession → 升 TLS → GetValue（验信任）
     → 后台线程每 200ms pumpOnce 保活
6) 第一条连接：claim 接口 2（0xFF/0x2A）→ 读 bulk
     PING×2 → CWPA → AFMT → CVRP → FEED
7) 每帧：SampleParser 抽 NAL → MediaCodec → SurfaceView
8) 收尾：release 两个接口 → setConfiguration(3) → close
```

---

## 四、踩过的坑（按代价排序）

### 1. 「每次都要重启 iPad」其实是没做收尾 ★

把设备留在配置 5，下次 `activate` 就是空操作。iPhoneMirror 每次收流后都会
`usb_configuration_restore`，所以它能反复开。

**解法**：claim 之前先 `setConfiguration(3)` → 300ms → `setConfiguration(5)`。
不需要设备重枚举（它也不会重枚举），一秒级完成。

⚠️ **切配置时不能有接口被 claim**，否则 `setConfiguration` 直接返回 false。

### 2. SPS/PPS 不在 `ASYN/sprp` 里 ★

`sprp` 只是键值（`ObeyEmptyMediaMarkers` / `RenderEmptyMedia`）。
真正的 avcC 嵌在 **FEED 样本尾部的字典**：

```
… 'vtad'(datv) | 4B 长度 | 01 64 00 2a ff e1 00 10 <SPS 16B> 01 00 04 <PPS 4B>
```

552 个包的 NAL 类型只有 `1/5/6`（P/IDR/SEI），**从不带 7/8**。
所以解码器必须先从这个位置取 CSD 才能启动。

### 3. `UsbDevice` 没有无参 `getConfiguration()`

扁平接口表 `interfaceCount/getInterface()` 是**所有配置的并集**，
里面找到 `0x2A` 不代表它在当前活动配置里 —— 直接 claim 会失败。
必须 `configurationCount/getConfiguration(i)` 定位到承载它的配置。

### 4. Android `bulkTransfer` 对「超时」和「端点错误」都返回 -1

只能用**耗时**区分：接近 timeout 的是超时，明显更短的是错误。
别把超时误判成 STALL 然后去 clear_halt（那是无用功）。

### 5. 落盘/回放的长度字段是**小端**

`QtPacket.u32` 是小端，回放时按大端读会直接 0 包。

### 6. mux 协议细节（字节级）

- 帧头 **16B 大端**：`protocol | length(含头) | magic=0xfeedface | tx_seq(u16) | rx_seq(u16)`；
  只有 VERSION 用 **8B 头**（`protocol | length`）。
- TCP 子头必须是 **20B 的系统 `struct tcphdr`**（16B 会被拒 "short tcp header"）；
  **Data Offset 写在 byte 12 的高 4 位**（`0x50`，不是 `0x05`）。
- lockdownd 流 = `[u32 **大端**长度][plist]`，长度不含自身 4 字节；设备回复恒为 XML。

---

## 五、怎么跑

```bash
# 编、装、起
./gradlew :app-ios-qt:assembleDebug
adb install -r app-ios-qt/build/outputs/apk/debug/app-ios-qt-debug.apk
adb shell am start -n com.uav.iosqt/.MainActivity
# 然后点「开始采集」；日志在 adb logcat -s UAV-IosQt:I
```

**离线自测**：**长按**「开始采集」= 回放 `filesDir/feed.bin`，
不依赖设备、不用重启 iPad，就能验证「解析 → 解码 → 上屏」整条链路。

---

## 六、还没做的

1. **App 自建配对**：目前 `assets/pc_*` 是从 PC 配对记录导出的凭证（含私钥，已 gitignore）。
   自己 `Pair` 生成的那套，设备在 TLS 阶段会 3ms 内 RST —— 设备只回 32B `EscrowBag`、
   不回完整 PairRecord。这是「装到别人设备上就能用」的最后一块。
2. **音频**：`HPA1` / `EAT!` 那条线还没接。
3. **触摸回传**：iOS 侧没有 USB 输入通道，只能走 BLE HID 或网络。
4. **稳定性**：长时间运行、切前后台、断线重连都还没验。
