# UsbScreenMirror

[![CI](https://github.com/ghbhaha/UsbScreenMirror/actions/workflows/ci.yml/badge.svg)](https://github.com/ghbhaha/UsbScreenMirror/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
![Platform](https://img.shields.io/badge/Android-6.0%2B%20(API%2023)-3ddc84)

把**安卓设备**（手机、平板、车机……只要是能跑 AOA 的安卓）或 **iPad / iPhone** 的屏幕，
镜像到**另一台安卓设备**上，全程走 USB：安卓这一侧走 AOA v2 配件协议，Apple 设备走 QuickTime USB。

**被投屏的两端都不需要 adb 授权、不需要开发者选项、不需要 root，也不需要往里推送任何进程。**
宿主装一个 APK 就够：插安卓设备走配件模式，插 iPad 走 QuickTime，进哪个页面由选择页按 VID/PID 决定。

---

## 它是什么 / 不是什么

开工前先把期望对齐，这一节比后面的技术细节更重要。

| | 说明 |
| --- | --- |
| ✅ **省掉的** | adb 授权、开发者选项、USB 调试开关、密钥协商、往被投屏设备推送服务端、root |
| ✅ **用到的** | 一根 OTG / 数据线；安卓侧用系统自带的 AOA 与 MediaProjection / MediaCodec；iPad 侧用 QuickTime USB（宿主当 USB 主机） |
| ✅ **宿主只需一个 APK** | 插安卓设备走 AOA、插 iPad 走 QuickTime，进哪个页面由选择页按 VID/PID 决定 |
| ⚠️ **省不掉的（安卓）** | **Android 14+ 每次投屏仍要点一次录屏授权**——系统取消了 MediaProjection 的"记住选择"，无法绕过 |
| ⚠️ **可能还要点一次（安卓）** | 首次插线时系统**可能**弹「是否打开该 App 处理 USB 配件」，勾选默认后此后插线即达 |
| ⚠️ **省不掉的（iPad）** | 首次要在 **iPad 上点一次「信任」**（配对）；之后不再需要。会话开始时 iPad 上的播放会暂停一次，手动再点播放即可 |
| ⚠️ **两端必须同版本** | 配件端（`app-accessory`）与宿主端（`app-host`）要一起升级（例如像素开关依赖配件端 10.17 的 `applyOptions`，旧包上切换不会生效）。目前**尚无版本/能力协商** |
| ❌ **本期不做** | 鼠标/键盘输入；iPad 侧的触摸回传（iOS 无 USB 输入通道） |

一句话：**省掉的是"链路的准备工作"，不是"这一次点击"。**

---

## 使用场景

这个项目解决的不是"投屏"本身，而是"**手边这几台设备不让装东西，但要让它们上同一块屏**"：

- **手边混着安卓和 Apple 设备**（手机、平板，甚至车机 / 一体机），想让它们投到同一块屏上
  —— 通常的方案要么只支持一种，要么得为 iOS 单独装一个 PC 端程序。
- **不能开 USB 调试 / 不能装 PC 软件的环境**：展厅、会议室、客户现场、产线工位。
  宿主是一台自带屏的安卓设备（平板、一体机），插上线就能用。
- **把闲置的老机顶盒变成「投屏盒子」**：宿主 `minSdk` 是 **23（Android 6.0）**，
  很多停在旧系统上的盒子还落在支持范围内 —— 装一个 APK，插上手机或 iPad，电视上就有画面，
  盒子本身不需要 root、不需要改固件。前提是盒子允许装第三方 APK（运营商定制机常被锁）
  且带可用的 USB host 口。
- **装进车机当车载屏**：安卓车机（后装大屏居多）同理，插上手机就能把导航、视频投到中控屏上；
  触摸走 HID 回传（仅安卓手机），可以直接在中控屏上操作手机。前提同样是能装第三方 APK、
  且车机带 USB host —— 多数后装机型可以，前装车机通常被锁。
- **需要用手去点那块屏**（仅安卓侧）：直接拿宿主的屏当被投屏设备的触控屏，点、拖、双指缩放都行，
  被投屏端**零改动**、不占视频带宽。
- **要把设备本身的声音一起带过来**（仅安卓侧）：内录它正在播放的声音，不是麦克风采集。

反过来，下面这些**不是**它的目标：远程桌面、跨网络投屏、多设备同时投、
在 iOS 上做反向控制。这些都不在当前范围内，见[项目状态与路线图](docs/项目状态与路线图.md)。

---

## 工作原理

AOA 那条路上，宿主是**配件**，用 52 号控制请求把「配件身份」写进对方设备，
对方内核随即把 USB 设备重枚举成配件形态（`VID:PID = 18D1:2D01`）：

```
┌────────────────────────┐                        ┌────────────────────────┐
│  宿主 Host             │        OTG 数据线      │  配件 Accessory        │
│  （另一台安卓设备）    │◄──────────────────────►│  （被镜像的安卓设备）  │
│                        │                        │                        │
│  app-host              │  ① AOA 握手 51/52×6/53 │  app-accessory         │
│   ChooserActivity ─────┼───────────────────────►│   CaptureService       │
│                        │  ② 设备重枚举为配件    │                        │
│   aoa.HostActivity ◄───┼────────────────────────┤   VirtualDisplay       │
│   MediaCodec 解码      │  ③ CNXN / OPEN / OKAY  │   MediaCodec 编码      │
│   SurfaceView 显示     │◄───────────────────────┤                        │
└────────────────────────┘  ④ WRTE 视频帧流       └────────────────────────┘
```

关键点在于**②**：**身份写不进去或写得不对，后面一步都不会发生**——这是整个项目最容易踩的坑，
详见 [`docs/排错手册.md`](docs/排错手册.md) 3.4。原理与选型的完整推导见
[`docs/配件投屏核心原理.md`](docs/配件投屏核心原理.md)。

### iPad / iPhone：QuickTime USB（角色正好相反）

投 iPad 时宿主是**标准 USB 主机**，去认领平板的私有接口：

```
┌────────────────────────┐   ① lockdown 会话(mux)  ┌────────────────────────┐
│ 宿主 app-host           │◄──────────────────────►│ iPad                   │
│ ios.IosActivity        │   ② 证书配对 → 受信任    │ （不需要装任何 App）     │
│                        │   ③ activate → 重枚举   │                        │
│ QtSession ◄────────────┼───────────────────────┤ QT 接口 0xFF/0x2A       │
│ H264Decoder + 音频      │   ④ FEED(视频) + EAT(音频)│ 0x86 IN / 0x05 OUT     │
└────────────────────────┘◄───────────────────────┘                        │
```

三个和 AOA 那条路**必须区别对待**的点：

1. **配对**：走 lockdown 的 `Pair`，iPad 上点一次「信任」即可。配对记录存在宿主
   `filesDir/ios_pair.properties`，之后不再需要点。
2. **配对会清掉设备侧的采集武装态** ⇒ 配完必须再 `activate` 一次（要让设备真重枚举）。
   判据只能是「配置集里从 4 个变成 5 个」，**不能**用"能不能找到采集接口"——
   扁平接口表永远包含它，会导致 `activate` 一次都不发。
3. **收尾必须做**：`hpa0`/`hpd0` → 等 2 个 `rels`（途中应答 `SYNC stop`）→ 再补 `hpd0`。
   不做这步，设备侧会话一直挂着，**下一次会话只出 3 帧就卡死**。

协议细节、踩坑与验证记录见 [`docs/iOS_USB投屏_安卓实现.md`](docs/iOS_USB投屏_安卓实现.md)；
线上报文的逐字段对照见 [`docs/协议速览.md`](docs/协议速览.md)。

---

## 架构

单 Gradle 工程、三个模块，交付为**两个独立 APK**（宿主一个、被投屏端一个）。

| 模块 | 产物 | 职责 |
| --- | --- | --- |
| `:protocol` | 纯 Kotlin/JVM 库 | 报文编解码、常量、连接编排、队列、缩放、**设备分类**、QuickTime 报文与音频解析、证书/配对。**零 Android 依赖** |
| `:app-host` | `app-host-debug.apk` | 宿主：**选择页 + 两个投屏页**（AOA / QuickTime） |
| `:app-accessory` | `app-accessory-debug.apk` | 被投屏的安卓设备：配件通道 → 虚拟屏采集 → 编码 → 推帧 |

宿主的三个 Activity：

| Activity | 职责 |
| --- | --- |
| `ChooserActivity` | **唯一入口**。两张卡片 + `USB_DEVICE_ATTACHED` 分类直达；按 VID/PID 判断，Apple 设备绝不会被送去走 AOA 握手 |
| `aoa.HostActivity` | AOA 投屏页（`UsbAttachRouter` → `LinkCoordinator` → 解码 → 触摸回传） |
| `ios.IosActivity` | QuickTime 投屏页（`MuxLink`/`QtTransport` → `QtSession` → `H264Decoder` + `PcmAudioPlayer`） |

包结构按**链路**划分：`aoa/`（audio / debug / input / link / session / ui / usb / video）、
`ios/`、`common/`，让人一眼看出哪些代码属于同一条链路。

`:protocol` 之所以必须是**纯 JVM 库**，是因为整个项目里唯一能在没有真机时真正验收的部分就是它——
把纯逻辑（`AnnexB`、`AccessoryIdentity.encodeSendString`、`Scale`、`QtPacket`、`HostUiState` 等）
全部下沉进来，才能用 JVM 单测（当前 **382 项**）把它们钉死。日志走注入式 `Logger` 接口，
USB 代码只存在于两端各自的 `ByteChannel` 实现里。

> 原先 iOS 是一条独立的 `:app-ios-qt` 分支（单独 APK）。2026-10-18 合并进 `:app-host`，
> 合并方案见 [`docs/合入host_双协议方案.md`](docs/合入host_双协议方案.md)。
>
> ⚠️ 改名字之前先看 [`docs/命名与身份约定.md`](docs/命名与身份约定.md)：包名和配件身份是**协议内容**，
> 配件身份在三处手工镜像，漏改一处就是"插线识别不到"。

---

## 项目引用与感谢

这个项目能跑通，很大程度上是站在别人的排查结果上的。下面这些是**实质参考过**的（本项目不包含
它们的任何代码，许可证各自独立）：

**协议与实现参照**

- **[scrcpy](https://github.com/Genymobile/scrcpy)** —— 它证明了「免 root、免装服务端」的手机镜像
  是可行的，本项目的交互形态（宿主鼠标直接当触屏、面板自动收起）向它看齐。
- **[iPhoneMirror](https://github.com/RayrenSX/iPhoneMirror)** —— 有线 iOS 镜像的参考实现
  （Windows，C++ 核心 + libusb0 过滤驱动）。它的运行日志与
  [`docs/DRIVER_DEPENDENCIES.md`](https://github.com/RayrenSX/iPhoneMirror/blob/main/docs/DRIVER_DEPENDENCIES.md)
  帮我们看清了两件关键事实：**音频不走 UAC 而走 QT 的 `EAT!`**，以及它其实借用了 PC 上常驻的
  Apple Mobile Device Support（安卓上没有这个东西，所以必须自己建 lockdown 会话）。
  附带一个排查教训：它用 libusb0 把设备挂到**虚拟总线**上，所以 USBPcap 抓不到它的 QT 流量
  —— 我们为此白折腾过一轮抓包。
- **[libimobiledevice](https://github.com/libimobiledevice/libimobiledevice)** —— lockdown 配对流程的
  对照实现。我们按它的规格实现 `Pair`，也发现一处必须偏离：它用空 DN 签证书，而 JDK 的
  `X509CertImpl` 会直接拒收——改成 `CN=` 后设备并不校验，才装得上 KeyStore。
- **[pymobiledevice3](https://github.com/doronz88/pymobiledevice3)** —— 与上面并列的配对规格参照。
  用它核对了三张证书的 plist 类型（`<data>` 里装 PEM 文本）、`HostID` 的生成方式
  （`UUIDv3(NAMESPACE_DNS, hostName)` 大写带横线，自造串会被判 `InvalidHostID`）。

**规范与协议**

- **Android Open Accessory (AOA) v2** —— Google 的官方规范，配件模式的 51/52/53 三个控制请求、
  重枚举后的 `18D1:2D0x` PID 都来自这里。
- **QuickTime USB / CoreMediaIO** —— Apple **未公开**的私有协议。本项目靠 USB 抓包与上述参考实现
  反推出来的，**不包含任何 Apple 的代码或文档**。

**调试工具**

- **[USBPcap](https://desowin.org/usbpcap/)** —— Windows 上的 USB 抓包。虽然它挂在物理 USB 类过滤器上、
  抓不到 libusb0 虚拟总线的流量（这个教训记在 `docs/排错手册.md`），但确认设备描述符时仍然好用。
- **Android `logcat` / `dumpsys` / `ramoops`** —— 定位宿主 kernel panic 那次，靠的就是
  `dropbox` 里的 `console-ramoops-0` 完整栈。

如果你在这些项目里看到和本仓库相似的写法，那多半是因为我们照它的结论做过验证 —— 结论对得上，
但代码是照着协议重写的。

---

## 进一步阅读

| 文档 | 内容 |
| --- | --- |
| [`docs/快速开始.md`](docs/快速开始.md) | 环境要求、构建、安装、运行步骤、界面控件、**adb 命令速查**、环境陷阱 |
| [`docs/协议速览.md`](docs/协议速览.md) | 两套协议的线上约定对照（AOA 报文头 / QT fourcc） |
| [`docs/项目状态与路线图.md`](docs/项目状态与路线图.md) | **能用到什么程度**、每条已知限制的来龙去脉、路线图与待补验清单 |
| [`docs/配件投屏核心原理.md`](docs/配件投屏核心原理.md) | 项目定位、技术选型、AOA 与协议原理推导 |
| [`docs/排错手册.md`](docs/排错手册.md) | 症状速查表、二分定位法、adb 操作红线、日志判读 |
| [`docs/实施计划.md`](docs/实施计划.md) | 协议冻结清单、分步实施、真机实测记录（10.x 全部编号都指向这里） |
| [`docs/iOS_USB投屏_安卓实现.md`](docs/iOS_USB投屏_安卓实现.md) | iPad 侧协议（lockdown / 配对 / QT / 音频）、**协议排查优先级**、踩坑记录 |
| [`docs/合入host_双协议方案.md`](docs/合入host_双协议方案.md) | 双协议合并的可行性、风险与落地形态 |
| [`docs/QT协议Windows复现.md`](docs/QT协议Windows复现.md) | PC 上复现 QT 协议的参考脚本与踩坑（含 `set_configuration` 蓝屏红线） |
| [`docs/命名与身份约定.md`](docs/命名与身份约定.md) | 包名 / 配件身份 / 组件全名，改名必读 |

连不上时**先看 [`docs/排错手册.md`](docs/排错手册.md)**，它按「症状 → 根因 → 处置」组织，
并有一条最重要的手段：用抓流回放把问题切成「USB/协议」与「解码/渲染」两半。

---

## 许可

[MIT](LICENSE) © 2026 ghbhaha

Apple、iPad、iPhone、QuickTime 是 Apple Inc. 的商标；Android 是 Google LLC 的商标。
本项目与上述公司无隶属关系，也未获得其背书。
