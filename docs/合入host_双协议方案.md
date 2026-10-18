# 合入 app-host：按设备自动选协议（可行性 + 计划）

> 目标：一个 App（`com.uav.host`）同时支持
> **A：安卓手机配件（AOA v2）** 与 **B：iPad（QuickTime USB）**，
> 插哪个就投哪个，不用再装两个 APK。

## 一、结论

**可行**，两边已经有很多可复用件，改造量集中在三层：设备分类、会话抽象、UI 能力集。
**但有一个现在就会出问题的冲突必须先修**（见 §三.1）：现有 AOA 路由会把 iPad 当成"待握手的手机"。

## 二、现状盘点

| 能力 | AOA 路径（app-host） | iOS 路径（app-ios-qt） |
|---|---|---|
| 入口 | `UsbAttachRouter`（attach 广播 + 前台轮询） | `MainActivity` 手动点按钮 |
| 会话 | `LinkCoordinator` → `HostSession` | `MuxLink`+`QtTransport` → `QtSession` |
| 视频 | H.264（`VideoDecoder`）+ `QualityController`（1:1） | H.264（`H264Decoder`） |
| 音频 | AAC → MediaCodec 解码 → `AudioPlayer`（含 A/V 同步、起播延迟） | **裸 PCM** → `AudioPlayer`（简单 AudioTrack） |
| 输入 | `TouchForwarder` + `HidInjector`（真触摸回传） | **无**（iOS 无 USB 输入通道） |
| 配对 | 无（AOA 不需要） | `Pairing` + `MuxLink`（lockdown/TLS） |
| UI | `HostPanel` + `HostUiState` | `IosPanel` + `Stage` |
| 资源 | `host_*` 前缀 | `ios_*` 前缀（但 **12 个 drawable 文件名相同**） |
| 代码量 | 5867 行 | 2960 行（含 ~1000 行协议/解析） |

**可复用**：`AspectRatioSurfaceView`（两份几乎一致，取 host 版）、`HostPanel` 的支架、
`HostUiState`、`AndroidLogger`、`DebugTools`、`:protocol` 里的 `QtPacket`/`AudioParser`/`CertKit`/`PlistXml`/`Lockdown`。

**必须并存**：两个 `AudioPlayer` —— AOA 是 AAC 要 MediaCodec 解码，iOS 是裸 PCM 直送。
抽公共接口 `AudioSink`，不强行合并实现。

## 三、四个真风险

### 1. ★ AOA 路由会把 iPad 抢过去（现在就会发生）

`UsbAttachRouter.pickCandidate()`：

```kotlin
val withBulk = usbManager.deviceList.values.filter { BulkPair.hasBulkPair(it) }
return withBulk.firstOrNull { Aoa.isAccessoryPid(it.productId) } ?: withBulk.maxByOrNull { it.deviceId }
```

iPad 的 `if2` 有 bulk 端点对（`0x86`/`0x05`），PID `0x12ab` 又不是 AOA 配件 PID
⇒ 会落到 `maxByOrNull` 被选中 ⇒ **对一台 iPad 发 AOA 握手（51/52/53）**。
宿主的 `usb_device_filter.xml` 是空 `<usb-device />`，所以 iPad 插上就会拉起 `HostActivity` 走这条错路。

**对策**：分类必须放在握手之前，且分类器是纯函数（可 JVM 单测）：

```
UsbDevice → AoaAccessory | AppleIos | Ignore
  - productId ∈ {0x2D00,0x2D01,0x2D02} → AoaAccessory
  - vendorId == 0x05AC (Apple)         → AppleIos
  - 其余（含内建 HID 触摸屏、鼠标键盘） → Ignore
```

### 2. 资源同名

12 个 drawable（`bg_panel`/`ic_play`/…）两边文件名相同、内容引用不同色板；
`values/{colors,strings,styles}.xml` 也同名，且都定义 `app_name`。
**对策**：iOS 侧资源统一加 `ios_` 前缀（文件名 + `@drawable/` 引用），`values` 合并entry（`host_*`/`ios_*` 已天然不撞）。

### 3. 只有一个物理通道，不能两条链路并存

同一时刻只能有一条会话（一个 Surface、一个 AudioTrack、一份 USB 带宽）。
**对策**：`DeviceRouter` 内部互斥；AOA 与 Apple 同时插时按固定优先级，
且切换前必须先**完整收尾**（iOS 侧尤其重要：`hpa0`/`hpd0` → 等 2 个 `rels`，否则下次只出 3 帧）。

### 4. 生命周期语义不同

- AOA：配件 fd 来自 intent（`EXTRA_ACCESSORY`），Activity 重建即失效；
- iOS：`UsbDevice` 靠权限 + `openDevice`，但**配对记录**在 `filesDir`，跨进程有效。

**对策**：两者都由各自的 Source 负责"自己的资源自己清"，Activity 只调 `source.stop()`。

## 四、模块拓扑（已按决策调整）

**决策 1：保留 `app-ios-qt` 作调试入口** ⇒ iOS 代码不能直接搬进 `app-host`，
必须抽成**两边共用的 Android library**，否则就得双份维护（正是要避免的）。

```
:protocol        (JVM, 零 Android 依赖)
  ├─ QtPacket / AudioParser / CertKit / PlistXml / Lockdown / Der / Scale
  └─ 单测：QtPacketFixtureTest、AudioParserTest、CertKitTest…

:ios-core        (Android library, 新增)   ← iOS 协议 + USB + 面板，两边共用
  ├─ QtTransport / QtSession / MuxLink / Pairing / Identity
  ├─ H264Decoder / PcmAudioSink
  ├─ IosPanel + res（drawable 统一 ios_ 前缀、layout、ios_ 色板/样式）
  └─ IosSource（实现 MirrorSource；调试入口和 app-host 都用它）

app-host         (application)             ← 双协议宿主
  ├─ HostActivity / HostPanel / HostUiState（复用，加 PAIRING/REARM 阶段）
  ├─ DeviceClassifier + DeviceRouter（新增）+ AoaSource（包装现有 AOA 链路）
  └─ depends on :protocol + :ios-core

app-ios-qt       (application, 调试入口)    ← 瘦身成一个壳
  └─ 只保留一个 Activity：host IosPanel + 调 IosSource；协议逻辑一行不留
```

好处：改协议只改 `:ios-core` 一处；调试入口仍然可以单独装、单独跑，不牵动 AOA 代码。

`MirrorSource` 接口（草案）：

```kotlin
interface MirrorSource {
    val capabilities: Set<Cap>          // TOUCH / PAIRING / REARM / AUDIO / ONE_TO_ONE
    fun start(surface: Surface?)
    fun stop()                          // 必须含协议收尾（iOS: hpa0/hpd0 + 2×rels）
    fun onDeviceAttached(device: UsbDevice)
}
```

## 五、分阶段计划（每阶段一个可回退提交）

| 阶段 | 内容 | 验收 |
|---|---|---|
| **0 建 `:ios-core`**（约 2h） | 新建 library 模块；iOS 协议/USB/面板/音频代码迁入；drawable 改名 `ios_*` + 引用同步；values 合并；`app-ios-qt` 瘦身成壳 | `:ios-core` + `:app-ios-qt:assembleDebug` 通过；**调试入口行为不变**（能投屏、能出声） |
| **1 分类 + 抽象**（约 2h） | `DeviceClassifier`（纯函数 + 单测）；`MirrorSource` / `AudioSink`；`AoaSource` 包装现有链路；`IosSource` 实现；`DeviceRouter`（**AOA 配件优先**，互斥） | 单测覆盖 iPad / AOA `0x2D01` / 内建 HID 触摸屏 / 键鼠 |
| **2 接线 + 面板**（约 1.5h） | `HostActivity` 持有 `MirrorSource?`；面板按 capabilities 显隐「配对/武装/触摸」；`HostUiState` 加 `PAIRING`/`REARM` | 真机：插 iPad → 出画面 + 出声；插手机 → AOA 流程**一条行为不变** |
| **3 收尾**（约 0.5h） | 文档 + 记忆；`:ios-core` 的 README 说明"改协议只改这里" | `./gradlew build` + 全部单测绿 |

**总工作量约 6~7 小时**（比原估多 1h，用于抽库这个边界）。

**决策 2：minSdk 保持 23** ⇒ `:ios-core` 也用 23，然后跑 `lint` 找 `NewApi` 逐个处理
（iOS 路径此前只按 24 写过，可能有零星 24+ API，需要真机或静态检查兜一遍）。
**决策 3：AOA 配件优先** ⇒ `DeviceRouter` 固定优先级，Apple 设备仅在无配件时接管。

## 六、已定的三点

1. **保留 `app-ios-qt` 作调试入口** ⇒ 采用 `:ios-core` 库结构（见 §四）。
2. **minSdk 保持 23**，iOS 路径的低版本兼容靠 lint + 真机兜。
3. **AOA 配件优先**，Apple 设备仅在没有配件时接管。

## 七、本次不做

- iOS 触摸回传（无 USB 输入通道，需 BLE HID 或网络，另立方向）。
- 音画同步（iOS 侧音频目前 200ms 缓冲直推，未与视频 PTS 对齐）。

## 八、我的建议：拆成两步，现在不合并 App

**先看体量对比（这是判断的基础）：**

| | AOA 路径 | iOS 路径 |
|---|---|---|
| 核心代码 | `HostActivity` 606 + `LinkCoordinator` 542 + `HostPanel` 472 + `UsbAttachRouter` 386 ≈ **2000 行** | `MainActivity` 733 + `QtSession` 413 + `IosPanel` 280 ≈ **1430 行** |
| JVM 单测 | **334 个**（`:protocol`） | 新增 ~12 个 |
| 成熟度 | 数月打磨：真触摸回传、1:1、p50 71ms、kernel panic / fd 泄漏 / HID 注册这些坑都趟过 | **1 天**，无触摸、无音画同步、配对 UX 未收敛 |

### 建议顺序

**第 1 步（现在做，与合并无关）**：抽出 `:ios-core` 库。
收益是独立的 —— 协议实现只有一处、可单测、`app-ios-qt` 从 1430 行瘦到几百行壳。
**抽库 ≠ 合并 App**：库可以只有一个消费者，等第二个消费者（app-host）出现再接。

**第 2 步（等 iOS 可交付后再合并）**：合并的触发条件写死，满足再动：

1. iOS 路径定位明确 —— 触摸回传做了，或者**明确对外宣称"只读投屏"**；
2. 音画同步做了（现在音频是 200ms 缓冲直推，未与视频 PTS 对齐）；
3. 配对/武装流程收敛到一次点击（现在是三个按钮，首次要按顺序点三次）。

### 为什么不是现在合并

**爆炸半径翻倍。** 现在 iOS 出任何问题都碰不到 AOA 路径；合并后两条路径共用一个
`HostActivity` 的生命周期，为了塞进一个 1 天大的新路径，要去改一个 2000 行、
带自愈/质量自适应/触摸/HID 注册的老路径 —— 这是本项目最贵的一类改动
（记忆里有硬结论：**重构验收 = 真机行为一条不变；删掉的代码不会自己报错**）。

**而且现在合并的收益很小。** 真正的重复只有：12 个同名 drawable（改个前缀就完事）、
一份 ~50 行的 `AspectRatioSurfaceView`、以及两套 UI 的色板。这些都可以通过
「把资源放进 `:ios-core`」消掉，**不需要动 App 边界**。

### 什么时候我会改口说"该合并"

- 产品形态硬要求"一个 APK 插谁投谁"（对外交付，用户不该关心插的是什么设备）；
- iOS 路径功能冻结、且已经连续用了一两周没出问题。

两个条件都满足时，合并只是「加一个 `IosSource` + 面板 capability」——因为 `:ios-core`
已经就绪，那时的工作量比现在估的 6~7 小时要小，风险也低得多。

## 九、实际落地的形态（2026-10-18 18:20，按用户方案）

**没有按 §四/§八 的 `:ios-core` 库走** —— 用户要求「先把代码放到 host，做成两个页面 + 一个选择 activity」。
最终形态更直接：

```
:protocol            QtPacket / AudioParser / UsbDeviceClassify / …（含全部单测）
app-host             ← 唯一的 app
  ├─ ChooserActivity      唯一 LAUNCHER：两张卡片 + 按 VID/PID 分类直达
  ├─ HostActivity         AOA 投屏页（内部一行未改，只从清单里摘掉 LAUNCHER/USB 过滤器）
  └─ ios/IosActivity      iPad 投屏页（原 app-ios-qt 的 MainActivity）
app-ios-qt           已删除（"选择页 → iPad" 就是原来的调试入口；需要时从 git 恢复）
```

**插线过滤器挪到了 ChooserActivity**：它只读设备描述符做分类，能确定就直达对应页，
不确定（普通模式手机 / 触摸屏 / 键鼠）就停在选择页。这样 iPad 再也不会被 AOA 抢。

⚠️ 一个必须记住的行为细节：普通模式手机的 PID 是厂商自己的，分类器只能判成 IGNORE
⇒ ChooserActivity 里**退回合并前的老启发式**（有 bulk 端点对就当候选，交给 AOA 去试），
否则"插线即达"会退化成"每次手点一下"。内建 HID 触摸屏/键鼠是中断端点，不会被误挑。

## 十、合并后实测（2026-10-18 18:11 ~ 18:32）

### 第一次测试：假的失败（旧 APK 残留）

插 iPad 后"点了不动"，`dumpsys window` 看到 `Application Error: com.uav.host`，
dropbox 里三次崩溃全是同一个：

```
java.lang.ClassNotFoundException: com.uav.iosqt.video.AspectRatioSurfaceView
  at IosActivity.onCreate(IosActivity.kt:97) → setContentView(activity_ios)
```

而 `grep -r uav.iosqt app-host/src protocol/src` 一个字都没有 —— **源码早改好了，
设备上跑的是修复前的包**。教训不是"包名要改"，而是：**验证合并这种跨模块改动，
先确认设备上装的确实是刚构建的那个包**（`dumpsys package | grep lastUpdateTime`），
否则会在一个已经修掉的 bug 上白查一轮。改包的修复本身见 §九 上方记录的
"layout 里 `class=` 属性最容易漏"。

### 第二次测试：真失败 —— 入口页没有轮询兜底

装上新包后仍不跳。用 `am start -a android.hardware.usb.action.USB_DEVICE_ATTACHED`
**手工制造**一次 attach intent，分类逻辑立刻正确：

```
I/Chooser: handleAttach action=android.hardware.usb.action.USB_DEVICE_ATTACHED
I/Chooser:   extra=null 选定=5ac:12ab
I/Chooser:   → iOS 投屏页
```

同时 `dumpsys usb` 里 iPad 明明在列（`vendor_id=1452` = 0x05AC，`product_id=4779`），
但插线那一刻 `logcat` 里**没有任何 attach 派发**。

⇒ 命中项目里早有的一条硬结论：**BOE P40UCH 的 USB host 栈在设备插入时不派发
`USB_DEVICE_ATTACHED`，只派发 DETACHED**。AOA 路径之所以"插线即达"，靠的从来不是
清单里的 intent-filter，而是 `UsbAttachRouter.startForegroundPolling()` 那个轮询。
合并时把入口搬到 `ChooserActivity`，**把这条隐形依赖丢在了旧 Activity 里**。

这是"搬家式重构"最典型的失效模式：清单能被 grep 到、代码能被编译校验，
而**"谁来定期看一眼设备表"这件事不在任何一处显式声明**。

### 修复（`ChooserActivity`）

1. **前台轮询兜底**：`onResume` 起 1s 定时轮询（`onPause` 停），对比 `deviceId` 基线，
   只对**基线之外新出现**的设备做跳转判定。
   - 基线 = 进本页时已插着的设备 ⇒ 用户手动点图标进来想选安卓手机时，不会被
     正插着的 iPad 立刻踢走；
   - 设备拔出时从基线移除 ⇒ 同一条线再插回来仍算"新出现"，拔插可重复触发。
2. **`EXTRA_DEVICE` 为空时的兜底**：部分 ROM / 走 `meta-data` 过滤器匹配时，
   attach intent 不带这个 extra。此时退回"设备表里唯一的 Apple 设备"，
   0 台或多台一律不猜（停在选择页）。
3. **区分"插线拉起"与"手动打开"**：显式判 `action == USB_DEVICE_ATTACHED`，
   不再只看有没有 extra。
4. **诊断日志**（`Chooser` 标签，`DebugTools` 门控）：`action` / `extra` / `选定` / 跳转目标。
   入口分类是"静默失败"高发区，没有这行日志就只能靠猜。

### 结果

```
18:31:34.378 I/Chooser: 轮询到新设备：2042=5ac:12ab
18:31:34.385 I/Chooser:   → iOS 投屏页
```

拔插 iPad → 7ms 内进 `IosActivity`，无崩溃；投屏与音频与合并前一致。

**仍然没验的：**

- **AOA 路径的入口复验**：`HostActivity` 内部一行未改，但它在清单里不再是 LAUNCHER，
  走的是 `ChooserActivity` 的 IGNORE 分支 + `BulkPair.hasBulkPair()` 兜底。
  现在轮询也覆盖这条分支了，理论上"插手机自动进 AOA 页"应该恢复，但必须实测一次
  —— 「删掉/搬走的代码不会自己报错」。
- 两台设备先后插拔时的分类（iPad 拔掉立刻插手机、反之）。

