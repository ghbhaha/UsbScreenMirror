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
| 依赖开发者模式 | ⚠️ **iOS ≤ 26 不需要；27+ 可能需要**（见 §八） |
| 配对 | ✅ **App 自己配对**（lockdown `Pair`），iPad 上点一次「信任」即可，不再需要 PC |

代码在 `app-host`（包名 `com.uav.host`，`ios/` 子包）+ `:protocol` 里的 `com.uav.protocol.ios`（证书/plist/配对请求，纯 JVM，有单测）。

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

配对完成后：`EscrowBag`（本机 32B）即成功凭据，记录存
`filesDir/ios_pair_<设备序列号>.properties`（**一台设备一条**，见下）；
紧接着用这套身份走一次 `StartSession → TLS → GetValue` 自证，日志里应出现
`★★★ GetValue 成功 —— 设备确认本主机已受信任`。

#### ★ 配对记录是"主机 × 设备"一对，必须按设备分开存

记录里既有主机身份（HostID / 证书链 / 私钥），也有**设备专属**的 `DeviceCertificate`
与 `EscrowBag`。所以：

- 多台设备共用一条 ⇒ 换一台 iPad 就把上一台的信任**覆盖**掉，换回来还得在 iPad 上
  重新点一次「信任」（这就是"换设备需要重新认证"的真因）；
- 一台设备一条 ⇒ 只有**第一次**碰到某台 iPad 才需要点一次信任，之后来回换都不用再点
  （新设备上那一次点是 iOS 的机制，绕不过去）。

设备键取 **USB 序列号**（`UsbDevice.getSerialNumber()`）：它是唯一一个"还没配对就能拿到、
且写/查两个方向一致"的标识。⚠️ 不要改用 lockdown 的 `UniqueDeviceID` —— 那要先起
usbmux 会话，而且它与 USB 序列号**可能不一致**，"写"和"查"用了两个来源就会永远查不到、
每次都重新配对。

旧版单文件 `ios_pair.properties` 的迁移是**先暂时接管、再转正**：
它的内容不带归属，无法确定属于哪台设备，所以第一台插上的设备先按 `provisional` 接管
（旧文件**先留着**）；真的跑通一次 TLS（`link.trusted`）后才删掉旧文件；
若没通过 ⇒ 说明认错人，丢掉这条临时记录、把旧文件留给对的那台（正常配对出来的记录
**永远不会**被自动删，免得一次接触不良就清掉用户的信任关系）。

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
# 编、装、起（模块已合入 app-host，页面挂在宿主里）
./gradlew :app-host:assembleDebug
adb install -r app-host/build/outputs/apk/debug/app-host-debug.apk
adb shell am start -n com.uav.host/.ChooserActivity
# 然后插上 iPad 即可 —— 页面会自己跳过去、自己配对、自己投屏，不用按任何按钮
# 日志： adb logcat -s UAV-IosQt:I Chooser:I
```

**界面只剩一个兜底按钮「重新唤醒采集通道」**：只有在"lockdown 已信任、QT 也 claim 到了，
但一个字节都不来"时才需要点它（会走 `activate` + 等设备重枚举）。

**录制 / 离线回放都不在界面上，只由 adb 驱动**（见 `DebugTools.IOS_EXTRA_*`）：

```bash
# 录一份现网码流（默认关闭！约 2 MB/s，一小时 7 GB）
adb shell am start -n com.uav.host/.ios.IosActivity --ez record true

# 用刚录的 feed.bin 离线回放：不插 iPad 也能验证「解析 → 解码 → 上屏」
adb shell am start -n com.uav.host/.ios.IosActivity --ez replay true
```

⚠️ 录制写的是 `getExternalFilesDir()/feed.bin`，**每次开始投屏会覆盖**；
写盘失败（磁盘满等）只会关掉录制，不会打断投屏。

---

## 六、还没做的

1. ~~音频~~ **已打通**：`HPA1` / `EAT!` 可正常出声；两处 fourcc 坑
   （`lpcm` 必须走 `fcc()`、OG 是 `go! ` 不是 `og! `）见 §三·五。
2. **触摸回传**：iOS 侧没有 USB 输入通道，只能走 BLE HID 或网络。
3. **首启引导**：现在「配对 → 重新武装 → 开始采集」是三个按钮，首次使用要按顺序点三次；
   应该收敛成一个「连接」按钮（内部按 `是否已配对` / `配置数` 自动决定走哪几步）。
4. **`activate` 的重枚举窗口**：目前按 240s 兜底，实际 1~2s 就完成，可以按实测收紧。
5. **旧的 assets PC 凭证**：已被 PC 侧重新配对顶掉（`InvalidHostID`），可以整体删除，
   只保留自建配对这一条路径。
4. **稳定性**：长时间运行、切前后台、断线重连都还没验。
---

## 八、iOS 版本差异（2026-10-19 调研）

一句话：**协议报文层没有版本差异，差异在「授权门控」层。**

### 8.1 报文层：无差异，**不要为版本写分支**

核对结果：

| 检查项 | 结果 |
|---|---|
| `QtProtocolCore` 里的版本判断（Version / ProductVersion / HardwareModel…） | **0 处** |
| 按 PID 分类（`0x12AB` 等） | 协议层 **0 处** |
| iPhoneMirror（开源实现，同时支持 iOS 26/27） | 同样无版本分支 |

两份独立证据指向同一结论：Windows 复现（iPadOS 27.2）与 Android 端的报文逐字节一致，
而 QuickTime 采集协议从 iOS 8 至今没变过。

⇒ **硬写版本分支不仅无据，还会误导排查方向**（iOS 28 上让人去查「协议变了」）。

### 8.2 真正的差异：授权门控

| iOS | 门控 | 缺门控时的表现 |
|---|---|---|
| **≤ 26** | 只需 §二② 的受信任 lockdown 会话 | 建好会话就推流 |
| **27+** | 额外要**开发者模式 / 媒体流授权** | `QuickTime endpoint opened but no PING`、超时 -2104 |

来源：iPhoneMirror issue #45 —— iOS 27.0.1 未开开发者模式时连续六次全黑，
开启并重启后恢复 61.6fps；且 iOS 27 把该开关**默认隐藏**。

⚠️ **开发者模式不是充分条件**：本项目按这条线索开了开发者模式，**CWPA 仍然不来**，
背后还有 DDI（开发者镜像）签发的媒体流授权。所以别把它当成「开了就能用」。

### 8.3 我们做了什么（纯诊断，不改行为）

新增 `protocol/ios/DeviceVersion`：把全量 `GetValue` 里的 `ProductVersion` / `ProductType`
记进配对记录，`IosActivity` 在**失败态**用它补一句提示。

三条自律（都写进了代码注释与单测）：

1. **不影响任何行为**。`needsExtraGating` 只用于**失败后解释**，从不用于预判或拒绝。
   老记录没有版本字段 ⇒ `UNKNOWN` ⇒ 行为与从前完全一致。
2. **解析失败返回 `null`，绝不返回 `0`**。返回 0 会被 `>= 27` 判成「老系统」，
   从而给出**错误结论**（「这台不用管」，而它只是没报版本）。
3. **提示里必须写明「这不是协议不兼容」**，否则用户会去查错方向。

存在意义只有一个：**下次遇到新系统设备，日志里能一眼看出版本**，
不用再从头 A/B 一遍（10.18 那一轮浪费的时间）。---

## 九、与上游 PROTOCOL.md 的逐条核对（2026-10-19）

对照 [RayrenSX/iPhoneMirror `docs/PROTOCOL.md`](https://github.com/RayrenSX/iPhoneMirror/blob/main/docs/PROTOCOL.md)（v2026-10-17 复审版）。

**结论：协议核心逐条一致，无冲突。发现 2 处差异 + 3 处已知未做，都不是 bug。**

### 9.1 一致的部分（逐条核对过）

| 上游文档 | 我们的实现 | 位置 |
|---|---|---|
| vendor request `0x40/0x52/0x0000/0x0002` | 同 | `QtTransport.activate` |
| 隐藏配置 subclass `0x2A`，bulk IN/OUT 独立端点 | 同（`interfaceClass==0xFF && subclass==0x2A`） | `QtTransport.isCapture` |
| ★ **激活前的 `0xFD` 不能当屏幕流接口** | 正是靠 subclass 精确定位，不按 interface class | 同上 |
| 包格式 `[uint32_le totalLen][uint32_le magic]` | 同 | `QtPacket.u32At` |
| ★ **fourcc 线上字节反转**（`ASYN=0x6173796e` → 线上`6e 79 73 61`） | 同，`fcc()` 统一反转 | `QtPacket.fcc` |
| PING 固定 16B，原样回写 | 同（**同步**写，不排队） | `QtProtocolCore` |
| SYNC/ASYN 的子类型在**偏移 12** | 同 | `QtPacket.matches(p, 12, …)` |
| RPLY 带 64-bit correlation id | 同 | `QtPacket.clockReply` |
| CWPA→RPLY、CVRP→NEED+RPLY、AFMT→回 Error=0 | 同 | `QtProtocolCore` |
| ★ **每收一个 FEED 就再发一个带视频时钟的 NEED**（背压） | 同，`videoClock != 0` 时补发 | `QtProtocolCore:173` |
| `SPRP` 携带参数集 | 同 | `SampleParser.csd` |
| FEED/EAT 外层 `ASYN\|clock\|FEED\|len\|sbuf\|chunks` | 同 | `SampleParser` |
| `sbuf` 内 `uint32_le len + uint32_le magic + payload` | 同 | 同 |
| 视频 `sdat` 用 **AVCC 大端长度前缀**，不是 Annex-B | 同（`Bytes.getIntBE`） | `SampleParser:24` |
| SPS/PPS 藏在尾部字典的 `avcC` 里 | 同 | `SampleParser.csd` |
| `mdia=vide` / `codc=avc1` / `vdim` | 同（SPS 尺寸解析已覆盖） | `SpsInfo` |
| `mdia=soun` + 40B ASBD，**48k lpcm 非 AAC** | 同（已按 PCM 出声） | `AudioParser` |
| CMTime 24 字节小端 | 同 | — |
| ★ 跨 read 可含半包/多包 | 同（`tail` 保留半包 + 非法长度重同步） | `QtSession:245` |

> 上游明确标注"参考实现无法重组跨 read 的大包，这是高码率下的稳定性风险"
> （文档 §5，Windows 参考仓库把跨 read 标了 TODO）——**我们这块比参考实现更稳。**

### 9.2 ★ 差异 1：停止流程 —— **已补全（2026-10-19）**

上游 §4 结束段：

> 停止顺序是 **HPA0、HPD0、处理 STOP/RPLY 与 RELS**，然后释放接口、恢复 USBMux 配置。

**补之前**：直接 `release` 接口 + `close`，不发任何停止报文。
实测反复开关 3 次也正常，所以当时不是故障 —— 但属**协议对称性缺口**：
设备侧不知道会话结束、可能保留上一会话的时钟状态；
长时间反复插拔的累积行为未验证；**将来若出现「拔插后推流异常」，这里是第一嫌疑**。

**现已补上**，实现要点（三条都是踩过才知道的）：

| 要点 | 说明 |
|---|---|
| ★ **必须同步写，不能走写队列** | 调用点紧接着就是 `stopWriter()` + 释放接口。走队列的话这两包会**永远留在队列里被丢弃** —— 函数写了、日志打了，实际一字节没上线 |
| ★ **必须在 `transport.release` 之前** | 顺序反了等于没发：接口一 release，OUT 端点不可写，`bulkTransfer` 静默返回 `-1`，**不报错也看不出** |
| ★ `hpa0` **不能带 `formats`** | 停止是"关"不是"重新协商"。原样搬 `hpa1` 的 body 会让设备当成新协商而继续推 `EAT!`（"点了停止音频还在来"） |

- `QtPacket.hpa0(clock)` / `QtPacket.hpd0(w, h)`，与 `hpa1`/`hpd1` 结构对称；
  默认显示尺寸提为 `QtPacket.DEFAULT_DISPLAY_WIDTH/HEIGHT`（1640×2360），
  **`hpd1` 与 `hpd0` 共用**，防止两处默认值悄悄漂移。
- `QtSession.sendStopSequence()`：发两条 → 停写线程 → 返回各自写入字节数。
  失败只记日志不抛异常（收尾不该因为"礼貌通知没送到"而中断释放）。
- 收尾时 iPad 已被拔掉（`abortReason != null`）也照发 —— 那时 bulkTransfer 返回 -1，
  日志里是「未送达」，属预期，不影响释放流程。

`STOP` / `RELS` 是**设备→主机**方向，由读循环被动接收即可，本期不处理。


### 9.3 ★ 差异 2：`HPA1` 的 `mFormatFlags` = 0，我们与上游夹具不同

| | mBytesPerPacket | mFramesPerPacket | mBytesPerFrame | mChannels | mBitsPerChannel | **mFormatFlags** |
|---|---|---|---|---|---|---|
| 上游夹具 | 4 | 1 | 4 | 2 | 16 | **76** |
| 我们 | 4 | 1 | 4 | 2 | 16 | **0** |

其余五个字段逐项相同；`formatFlags` 不同。

注意**这不是我们解码侧的默认值**—— [AudioParser.DEFAULT_FORMAT] 是 `12`
（`kAudioFormatFlagIsSignedInteger or kAudioFormatFlagIsPacked`），那是**从设备 `AFMT` 报文读到的实际值**。
`HPA1` 里那一个 `0` 是我们**自己声明**的格式标志。

实践上没影响（`AFMT` 阶段设备会用它的真实值覆盖），但严格对齐更稳妥。

### 9.4 已知未做（上游有、上游自己也在演进）

| 项 | 上游状态 | 我们的选择 |
|---|---|---|
| **HEVC/HVCC** | 支持（含 VPS/IRAP NAL） | 只做 H.264 |
| `satt/keyv` 显示方向（`idxk=29`，iPad15,7 需180/90/270 校正） | 已实现，且2026-10-17 推翻过自己的旧判断 | 未做。**我们靠 SPS 变化感知转屏**，iPad8,9 实测转屏正常 ⇒该`keyv` 方向修正对本机型不必要 |
| HPD1 的A/B/C 三模式（演示 / AirPlay /爱思兼容） | 按机型尺寸表请求 | 固定 `Valeria=true + 1640×2360` |
| 色彩范围 full-range BT.709 归一化 | 有 | 无 |

★ **HPD1 的 DisplaySize 不是编码分辨率控制**（上游 §7 明确）
——这点我们理解正确：请求 1640×2360，设备实际输出按它自己的 `vdim`/timing 走。
我们从 SPS 取真实尺寸正是这个道理（见 `docs` §四 的"画面横向拉宽"修复）。

> ⚠️ 上游文档自己也说明："HPD1 分辨率硬编码为 2160×3840，不代表实际输出能力"
> 是**它自己批评旧参考实现**的话。我们固定尺寸不是同一个错误，
> 但真要改分辨率，得知道**没有 USB 侧开关**，只能改本地渲染上限（上游也是这么做的）。

### 9.5 采纳上游的一条经验（已在代码里）

上游 §10「兼容性风险」：*"协议是逆向结果，不是 Apple 公共 API，
Apple 可以在任意 iOS 版本更改…"* —— 这与 `docs/iOS_USB投屏_安卓实现.md` §八
「不要为版本写分支」的结论一致：我们不写版本分支，但**保留版本号记录用于失败诊断**。