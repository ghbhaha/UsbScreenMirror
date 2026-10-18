# Apple 设备 USB 投屏方案：全网调研

> 2026-10-18。范围：iPhone / iPad 通过**物理线缆**把画面送到另一台设备（PC / Android / 显示器）。
> 标注「实测」的条目已在本仓库真机验证过；其余为资料调研，来源随文附。

---

## 一、结论速览

**iOS 没有"官方的 USB 投屏协议"。** 所有能走线的方案，本质只有四条技术路线：

| # | 路线 | 画面从哪来 | 需要 iOS 端装 App | 延迟（实测/资料） | 画质 |
|---|---|---|---|---|---|
| **1** | **QuickTime USB 协议**（逆向） | 设备侧 QT 采集端点 | ❌ 不需要 | 3.6 ms（实测，iPhoneMirror） | H.264 硬件编码，4:2:0 |
| **2** | **ReplayKit 广播扩展 + usbmux** | iOS App 内录屏后推流 | ✅ **必须** | 数十 ms（含编码 + 传输） | H.264，码率可控 |
| **3** | **HDMI 采集卡**（硬件） | Digital AV Adapter 输出 HDMI | ❌ 不需要 | 10–60 ms（资料） | **4:4:4 无压缩损失** |
| **4** | **AirPlay**（不是 USB） | 设备主动推 RTSP/RTP | ❌ 不需要 | 40–80 ms（实测可用） | H.264 1080p60 |

**一句话选型**：

- 要**零安装、最低延迟** → 路线 1（但新设备可能门控，见 §3.1）
- 要**绝对能成、能拿到画面数据** → 路线 2（代价：iOS 端必须有 App）
- 要**画质最好、最稳** → 路线 3（代价：硬件成本 + 只能看不能取流）
- 只求**能用** → 路线 4（无线，本仓库已跑通）

---

## 二、逐方案详解

### 2.1 官方途径（都有限制）

| 途径 | 平台 | 说明 |
|---|---|---|
| **QuickTime Player** → 文件 → 新建影片录制 | 仅 macOS | **唯一官方的 USB 投屏入口**，也是路线 1 的逆向样本 |
| **USB-C / Lightning Digital AV Adapter** | 任意 HDMI 显示设备 | 走 DisplayPort Alt Mode，**硬件输出**。iPhone 15+ / USB-C iPad 最高 4K60，Lightning 机型 1080p |
| **iPhone Mirroring** | 仅 macOS Sequoia+（Apple Silicon / T2） | **无线**（WiFi + 蓝牙），不是 USB；且 EU 不可用 |
| **AVFoundation / Xcode** | macOS | 开发者抓屏，本质也是调 QT 那套 |

> **重要事实**：iPadOS **不支持作为 USB 显示接收器**——两台 iOS 设备之间插线不能投屏，这是系统限制不是线的问题（[MacMyths](https://macmyths.com/how-to-screen-mirror-from-iphone-to-ipad-a-step-by-step-guide/)）。

### 2.2 路线 1：QuickTime USB 协议（逆向）

**原理**（已在本仓库完整实证，见 `iOS投屏调研清单.md`）：

```
control 0x40/0x52/wIndex=2  → 设备重枚举出"采集配置"（含 0xFF/0x2A 接口）
setConfiguration(5) → claim interface 2 → bulk IN/OUT
设备发 PING → 主机回 PING → 设备发 CWPA(音频时钟)
→ 主机发 HPD1 + clock_reply + HPA1 → AFMT → CVRP → NEED → FEED(视频帧)
```

| 实现 | 语言 / 平台 | 许可 | 状态 |
|---|---|---|---|
| `danielpaulus/quicktime_video_hack` | Go / macOS·Linux | 开源 | 协议事实标准；iPhone 11/12 起有人报"只到 PING" |
| `RayrenSX/iPhoneMirror` | C++ / Windows | GPL-3.0 | **实测可出画**（iPad8,9，720+ 帧）；有 A/B/C 三种模式（Demo/AirPlay/爱思） |
| `furruka/iPhoneMirror` | C++ / Linux | GPL-3.0 | 同上的 Linux 分支，文档详实 |
| `ios-minicap` / `ios_video_pull` | ObjC / macOS | 开源 | 依赖 AVFoundation，仅 macOS |
| ApowerMirror / iMyFone MirrorTo / 3uTools / 爱思助手 | 商业 | 闭源 | **本质都是这条路线**；其中 3uTools 免费 |

**优点**：不用装 iOS App、不用越狱、延迟最低（3.6ms）。
**缺点**：**新设备/新系统可能被设备侧门控**——本仓库实测 iPad8,9 / iPadOS 27.2 上
协议全通、PING 能收能回，但设备始终不发 CWPA（详见 `iOS投屏调研清单.md` §0.25）。
上游 issue #79 里 iPhone 11/12 也有同样报告。

### 2.3 ★ 路线 2：ReplayKit 广播扩展 + usbmux（真·有线，但需 iOS App）

**这是本次调研里最被低估的一条路。** 腾讯 PC 直播姬就是这么做的
（[原文](https://www.bilibili.com/read/cv38061996/)）：

```
iOS 端：
  Broadcast Upload Extension（ReplayKit）
    → processSampleBuffer 回调拿到屏幕帧
    → VideoToolBox H.264 编码 + AudioToolBox AAC 编码
    → 封装 FLV
    → 通过 usbmux 建立的 TCP 连接推给 PC

PC 端：
  libimobiledevice + Apple Mobile Device Service
    → 连到 iOS App 监听的端口
    → 收 FLV → 解封装 → 解码 → 送直播/预览
```

**关键技术点**：

- usbmux 提供"类似 TCP socket"的通信，宿主是 client、iOS App 是 server（端口自行协商）；
- Extension 是独立进程，与宿主 App 之间再用一条本地 TCP 连接传数据；
- 实测稳定带宽约 **35 MiB/s**，USB 2.0 / 3.0 差别不大。

| 维度 | 评价 |
|---|---|
| 延迟 | 数十 ms（多一次 H.264 编码 + FLV 封装） |
| 是否需要信任配对 | **不需要**（usbmux 连接建立在已配对基础上，但消费级方案通常连配对都不强制） |
| 是否受 QT 门控影响 | **不受**——完全不碰采集端点 |
| 能否拿到原始帧 | **能**（自己编码，原始帧在手） |
| 最大门槛 | **iOS 端必须装 App**（App Store / TestFlight / 企业签名 / MDM 推送） |
| 已知限制 | 系统顶部会显示录屏红条；DRM 内容录不到 |

**为什么这条对本项目重要**：它是**唯一既能物理有线、又能拿到画面数据、又不依赖 QT 门控**的软件方案。
代价从"技术难点"变成了"分发问题"——如果目标场景是自有设备（教育平板、企业内部），
用 MDM 或企业签名推一个 iOS App 是可行的。

### 2.4 路线 3：HDMI 采集卡（最稳，硬件路线）

```
iPhone/iPad → Digital AV Adapter → HDMI 线 → USB 采集卡（UVC）→ PC/Android → OBS/预览
```

| 维度 | 数据 |
|---|---|
| 延迟 | 10–60 ms（[审计报告](https://tsight.io/articles/19431794)） |
| 画质 | **4:4:4 全采样，无压缩色偏**（软件路线是 4:2:0） |
| 分辨率 | USB-C 机型最高 4K60；Lightning 机型 1080p |
| 系统依赖 | 无（UVC 标准类，Linux/Android/Windows 都能收） |
| 成本 | 转换头 + 采集卡（数百元） |
| 缺点 | **只能"看"，拿不到可编程的帧流**（除非宿主侧再接采集 SDK）；多一个硬件 |

> 分级参考（同一份审计报告）：T0 = 原装转换头 + USB3.0 采集卡 + OBS；
> T2 = 廉价转换头 + USB2.0 采集卡（约 80ms、易掉线）；
> **T3 = 所谓"USB 模式"的纯软件方案（AirDroid Cast 等），延迟随时间累积，不推荐**。

### 2.5 路线 4：AirPlay（无线，但已验证可用）

| 维度 | 评价 |
|---|---|
| 协议 | mDNS 发现 → RTSP 7000 → RTP 传 H.264 |
| 传输 | **纯 IP**，与 USB 无关 |
| 开源实现 | `jqssun/android-airplay-server`（GPL-3.0，UxPlay + JNI + MediaCodec） |
| 本仓库实测 | ✅ **iPad 主屏完整上屏**，`c2.mtk.avc.decoder` 硬解，crop 1545×1079，frame-rate 60 |
| 延迟 | 40–80 ms 量级 |
| 缺点 | 需用户手动点一次；**不是有线** |

**"有线 AirPlay"的真相**：不存在独立协议。要让它走线，必须先有 IP 通道，
唯一途径是 **USB 网络共享（CDC-NCM）**——要求设备开"个人热点"，即**必须蜂窝版**。
本仓库实测：WiFi 版 iPad8,9 插线前后 `/sys/class/net` 无任何新接口 ⇒ 不可用。

---

## 三、对本项目（Android 宿主）的可行性矩阵

| 方案 | Android 可行性 | 本机实测结论 |
|---|---|---|
| 1. QT USB 协议 | ✅ 技术上可行（已实现） | ❌ **设备不推流**（协议全通、只有 PING 无 CWPA） |
| 2. ReplayKit + usbmux | ✅ 可行（宿主侧 = libimobiledevice 的 Android 等价实现，需自己做隧道） | ⬜ **未验证**，但腾讯直播姬已证明路线成立 |
| 3. HDMI 采集卡 | ⚠️ 可行但设备要能接 UVC 采集卡（宿主需 USB-A/OTG + UVC 驱动） | ⬜ 未验证 |
| 4. AirPlay（WiFi） | ✅ 可行 | ✅ **已跑通**（iPad 主屏上屏） |
| 4b. AirPlay over USB（CDC-NCM） | ❌ | ❌ 设备不提供 NCM 接口（WiFi 版） |

---

## 四、结论与建议

### 4.1 事实层面的三条硬结论

1. **iOS 没有"USB AirPlay"**。声称有的软件，要么是 USB tethering（要蜂窝版），要么其实是 QT 协议。
2. **iPad 不能当 USB 显示接收器**（系统级禁止），所以"两台苹果设备插线互投"不存在。
3. **能拿到画面数据的软件方案只有两条**：QT 协议（零安装）和 ReplayKit App（要安装）。

### 4.2 对本项目的建议顺序

| 优先级 | 动作 | 理由 |
|---|---|---|
| **P0** | 先把 AirPlay（路线 4）做成可用产品 | **已验证**，零风险，能立刻交付 |
| **P1** | 评估路线 2 的可行性（ReplayKit App + usbmux） | 唯一能同时满足"有线 + 拿数据"的软件方案；难点在 iOS App 分发 |
| **P2** | 若客户能接受硬件，路线 3 作为兜底 | 最稳、画质最好，且与 OS 版本解耦 |
| P3 | QT 协议路线暂时归档 | 已投入最多，但在本机被设备侧门控；换设备可能复活 |

### 4.3 一个容易踩的坑（商业宣传）

网上不少文章声称"控制中心直接选 `xxx(USB)` 就能有线投屏、无需装 App"。
**如果 iOS 端确实没装任何 App，这句话不成立**——AirPlay 列表里出现的东西必须是
一个真实的 AirPlay 接收端（走网络），而 USB 直连要出现设备必须走 QT 协议。
遇到这类描述，先让对方给出 `PING/CWPA` 抓包，别信 UI 截图。

---

## 五、资料来源

- Elgato《How to stream or record iPhone gameplay》——三种采集路径、机型能力（4K60 / 1080p）
- MacMyths《How to Screen Mirror from iPhone to iPad》——iPad 不能当 USB 接收器
- tsight.io《iOS 极致低延迟投屏硬件审计报告》——有线方案分级、延迟与采样率对比
- B 站《安卓/iOS 和 Windows 的 USB 有线投屏》——**腾讯直播姬 iOS 有线方案全流程**（ReplayKit + usbmux + FLV）
- `liketcs`《安卓/苹果 USB 有线投屏原理》——QT 协议 3 步流程（启动/接收/关闭）
- GeekChamp《How to share mobile screen on PC》——"Windows 上没有原生 AirPlay over USB"
- UxPlay / android-airplay-server 仓库与 README
- 本仓库：`docs/iOS投屏调研清单.md`（路线 1 全量实证）、`docs/iOS有线AirPlay调研.md`（路线 4 实证）
