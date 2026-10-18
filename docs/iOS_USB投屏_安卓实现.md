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
| 配对 | ✅ **App 自己配对**（lockdown `Pair`），iPad 上点一次「信任」即可，不再需要 PC |

代码在 `app-ios-qt`（包名 `com.uav.iosqt`，minSdk 24）+ `:protocol` 里的 `com.uav.protocol.ios`（证书/plist/配对请求，纯 JVM，有单测）。

---

## 二、三个必须同时满足的条件

这三条是花了一整天才逐个排除出来的，**缺任何一条都拿不到流**：

### ① 设备侧 QT 会话必须先"武装"

设备不会主动进入采集模式。`activate` 请求（`controlTransfer(0x40, 0x52, wValue=0, wIndex=2)`）
会触发一次真实重枚举——**但必须在普通配置（3）下发起**。

- 设备刚开机/刚插线：配置集里只有 **4 个配置** ⇒ `activate` → 重枚举 → 变成 **5 个**（多的那个带 `0xFF/0x2A`）；
- 设备已在采集态（5 个配置）：`activate` 是**空操作**，此时靠 **配置 3→5 来回**重新武装（见第四节）。

⚠️ **判据只能用「配置集里有几个配置」**（`dev.configurationCount >= 5`），
**不能**用 `findCaptureInterface()` —— 后者扫的是扁平接口表（所有配置的接口并集），
永远能找到 0x2A，于是 `activate` 一次都不会发（这个 bug 让"配对后 0 字节"卡了很久）。

`activate` 的重枚举实测通常 1~2 秒就完成，但**给窗口就给足**（240~300 秒）——早先有过延迟 2~3 分钟的记录。

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

## 三·五、协议排查优先级（先做这五步，再谈推理）★

**2026-10-18 音频不通的教训**：为了"为什么收不到音频"对照了四份开源实现、抓了三次包、
反复改参数，最后真正的原因只是**两处 fourcc 写错**（`lpcm` 写成裸 ASCII、`OG` 名字写反）。
这两个错误都不会报错、不会崩，只表现为"设备静默"。下面是按**信息量/代价比**排的排查顺序，
以后任何"发出去的报文被对方无视"的问题，都按这个顺序走，别先做推理。

### 1. 先比字节：把要发的包和真机样本逐字节对齐（最高优先级）

有样本就比样本，没样本就**先造样本**（PC 侧能跑通的实现 / 抓包）。一句 `assertArrayEquals`
胜过半天推理。已经固化成 `QtPacketFixtureTest`（`:protocol` 的 JVM 单测，4 条断言）：
HPA1 全字节、afmt_reply 全字节、codec 必须是反转后的 `lpcm`、`OG` 名必须是 `go! `。

> ★ **凡手写 fourcc 一律走 `fcc()`，禁止字面量。**
> 本项目已因此在同一类错误上栽两次：`b"lpcm"`（裸 ASCII）、`"og! "`（顺序写反）。
> 排查时先 `grep` 一遍所有 fourcc 字面量。

### 2. 找一个"对方接受了我的能力声明"的观测点

"没收到 X" 只能证明没收到，**不能**证明对方没发。要找那种"对方只有认可我们才会发出"的报文：

| 场景 | 判据 |
|---|---|
| 音频 | **对方会不会发 `AFMT`**（不来 = 我们的 HPA1 没被接受，问题一定在自己这边） |
| 视频 | 对方会不会发 `CVRP` / `FEED` |
| 会话 | `PING` 之后有没有 `CWPA` |

### 3. 确认自己没有"静默丢包"

未知子类型走 `else` 分支、`matches()` 永不命中、写失败被忽略 —— 这三类都只打日志不报错。
先确认日志里没有"未知 / 未识别"，再怀疑对方。

### 4. 确认收尾做干净了

`hpa0`/`hpd0` → 等 2 个 `rels`（途中应答 `SYNC stop`）→ 补发 `hpd0`。
不做这步设备侧会话一直挂着，**第二次会话只出 3 帧就卡死**（实测），很容易被误判成"设备不配合"。

### 5. 最后才怀疑环境差异

主机栈 / 驱动 / 系统版本差异要放到最后，而且必须做**同条件对照**（同一台设备、同一时刻、
同一份实现）。⚠️ 对照实验前先验证"对照物本身是对的"——本次就是拿一个自己有 bug 的 PC 脚本
当基准，得出"设备不推音频"的错误结论，白绕好几小时。

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

### 7. 自建配对（`Pair`）—— 字段与类型都不能自己发明 ★

`2026-10-18` 真机跑通。整套规格来自 `pymobiledevice3/lockdown.py::pair` + `ca.py`（PC 上验证过的实现），
逐项对照后发现**几处「想当然就会错」的地方**：

| 项 | 正确做法 | 错法的表现 |
|---|---|---|
| 三张证书的 plist 类型 | `<data>`，**载荷是 PEM 文本** | 发裸 DER → 设备当垃圾 |
| `DeviceCertificate` | 拿 `GetValue(DevicePublicKey)` 的返回值**现签**一张叶子证书 | 以为设备会下发 |
| `HostID` | `UUIDv3(NAMESPACE_DNS, hostName)`，**大写带横线** | `TEST-HOST` 之类的自造串 → `InvalidHostID` |
| `WiFiMACAddress` | 设备的 `WiFiAddress`（**不是本机 MAC**），缺了要整键省略 | 写空串 |
| `HostPrivateKey` | **不进请求**，等 `EscrowBag` 回来才写本地记录 | 一起发 → 设备拒 |
| 应答里的 `PairingDialogResponsePending` | **每分钟重发一次**（用户还没点信任时的常态） | 当失败 → 永远配不上 |

配对完成后：`EscrowBag`（本机 32B）即成功凭据，记录存 `filesDir/ios_pair.properties`；
紧接着用这套身份走一次 `StartSession → TLS → GetValue` 自证，日志里应出现
`★★★ GetValue 成功 —— 设备确认本主机已受信任`。

#### ⚠️ 证书 DN 不能留空（与 libimobiledevice 的唯一偏差）

libimobiledevice / pymobiledevice3 用**空 DN**（`30 00`），设备侧 libcrypto 能吃；
但 **JDK 的 `X509CertImpl` 会直接拒**：`Empty issuer DN not allowed in X509Certificates` ——
而 App 把证书装进 `KeyStore` 走的正是 Java 这条路（Android 的 Conscrypt 宽容，JVM 单测不宽容）。

⇒ 改成 `CN=UsbScreenMirror Root/Host/Device`。设备只校验「签名链是否由它存下的 RootCertificate 认证」，
**DN 内容不参与任何判断**，所以这不改协议语义，却让两侧都能解析。

#### ⚠️ 配对之后必须 `activate` 一次

配对成功 ≠ 能投屏。配对会把设备侧 QT 的武装态清掉：
此时 lockdown/TLS/信任全绿、QT 接口也能 claim，但**一个字节都不来（连 PING 都没有）**。
正确顺序是：**先配对 → 再 `activate`（配置 3 下）→ 等重枚举 → 开始采集**。
之后就可以反复开关了。

---

## 五、怎么跑

```bash
# 编、装、起
./gradlew :app-ios-qt:assembleDebug
adb install -r app-ios-qt/build/outputs/apk/debug/app-ios-qt-debug.apk
adb shell am start -n com.uav.iosqt/.MainActivity
# 界面三个按钮，按状态点：
#   首次： 「配对」→ iPad 上点「信任」→ 「重新武装」→ 「开始采集」
#   之后：  直接「开始采集」（设备在采集态时走配置 3→5 来回）
# 日志： adb logcat -s UAV-IosQt:I
```

**离线自测**：**长按**「开始采集」= 回放 `filesDir/feed.bin`，
不依赖设备、不用重启 iPad，就能验证「解析 → 解码 → 上屏」整条链路。

---

## 六、还没做的

1. **音频**：`HPA1` / `EAT!` 那条线还没接。
2. **触摸回传**：iOS 侧没有 USB 输入通道，只能走 BLE HID 或网络。
3. **首启引导**：现在「配对 → 重新武装 → 开始采集」是三个按钮，首次使用要按顺序点三次；
   应该收敛成一个「连接」按钮（内部按 `是否已配对` / `配置数` 自动决定走哪几步）。
4. **`activate` 的重枚举窗口**：目前按 240s 兜底，实际 1~2s 就完成，可以按实测收紧。
5. **旧的 assets PC 凭证**：已被 PC 侧重新配对顶掉（`InvalidHostID`），可以整体删除，
   只保留自建配对这一条路径。
4. **稳定性**：长时间运行、切前后台、断线重连都还没验。
