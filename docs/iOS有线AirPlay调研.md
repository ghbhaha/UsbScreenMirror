# iOS → Android 投屏：三条路径调研（2026-10-18）

> 分支 `probe/ios-airplay-usb`（基于 `develop`）。
> 目标：让 iPad 主动把画面投到 Android App，而不是主机去"抢"设备的采集流。

---

## 一、结论先行

| 路径 | 能否做到"真·有线" | 本机（iPad8,9 / iPadOS 27.2）可行 | 工程量 |
|---|---|---|---|
| **A. QuickTime USB 采集**（已做过两天） | ✅ 有线 | ⚠️ 协议全通但设备不推流（详见 `iOS投屏调研清单.md` §0.25） | 已投入，卡住 |
| **B. USB 网络共享 + AirPlay**（CDC-NCM） | ✅ 有线 | ❌ **实测本机不提供网络接口** | 中（有现成实现可参考） |
| **C. 无线 AirPlay 接收端**（同网 / Android 热点） | ❌ 无线 | ✅ **可行，有成熟开源实现** | 中（可先用现成 APK 验证） |

**一句话**：纯有线的 AirPlay 依赖 iPad 通过 USB 提供一个网络接口（CDC-NCM），
**这台 iPad 实测不提供**（WiFi 版没有"个人热点"所需的蜂窝模块）。
所以对这台设备，现实可行的是 **C**：让 Android 开热点、iPad 连过来、走标准 AirPlay。
它绕开了 QT 协议的全部坑（PING / CWPA / TLS / 开发者模式），也把"投屏"这个动作交回给用户。

---

## 二、为什么"有线 AirPlay"卡在 CDC-NCM

AirPlay 本质是 **IP 网络协议**：RTSP(7000) + RTP + Bonjour/mDNS 发现。
要有线，就必须在 USB 上先有一条 IP 通道。iOS 侧的这条通道只有一种：**CDC-NCM（USB 网络共享）**。

参考实现 `Guo-astro/android-airplay-server`（`jqssun/android-airplay-server` 的 fork，
2026-10-16 起加 USB 支持）的做法印证了这一点：

```
d8c3cb5  feat: usb only     + app/.../discovery/UsbMdnsAdvertiser.kt  (415 行)
543f977  tether             README / Manifest / MainActivity ...
```

- `UsbMdnsAdvertiser` 的作用是**在 USB tethering 的网卡上**做 mDNS 广播，
  让 iPad 在这个"USB 网络"里发现接收端；
- README 原文：「Apple devices natively support USB tethering over **CDC-NCM**，
  所以默认走 RNDIS 的 Android 设备可能需要 `settings put global tether_force_usb_functions 1`」。

**它依赖的那个网卡，我们这台机器上没有。**

### 实测证据（2026-10-18 13:00，iPad 直连宿主 P40UCH）

```bash
$ adb shell ls /sys/class/net
ap0  dummy0  erspan0  gre0  gretap0  ifb0  ifb1  ip6_vti0  ip6gre0
ip6tnl0  ip_vti0  lo  p2p0  sit0  tunl0  wlan0  wlan1
```

**没有 `usb0` / `usb1` / `eth1` / `enx*` 之类的任何新接口**——
插入 iPad 前后网卡列表无变化，说明设备侧没有枚举出 CDC-NCM 功能单元。

原因：iPad8,9 是 **iPad Pro 11 英寸（第 2 代）Wi-Fi 版**，没有蜂窝模块 ⇒ 没有"个人热点"
⇒ 不会被要求提供 USB 网络共享。iOS 的 USB 网络接口是随"个人热点"一起出现的。

> 推论：**换成蜂窝版 iPhone/iPad，路径 B 才成立**。这是换设备时值得记住的一条。

---

## 三、路径 C 的现成实现（可直接复用）

`jqssun/android-airplay-server`（GPL-3.0，F-Droid / Play 上架，XDA 有实测反馈）：
把 **UxPlay**（C 语言 AirPlay/RAOP 服务端）用 **JNI** 桥到 Android 原生层。

| 环节 | 实现 |
|---|---|
| 协议（RTSP/RAOP/FairPlay/HLS） | UxPlay C 库，CMake 编译进 `app/src/main/cpp` |
| mDNS 广播 | 自带（`NsdServiceManager`；fork 版另有 `UsbMdnsAdvertiser`） |
| 视频解码 | **MediaCodec**（H.264 / H.265），软件解码兜底 |
| 渲染 | `SurfaceView` |
| 音频 | MediaCodec 或软件 ALAC/AAC 解码 |
| 已知限制 | **DRM 内容不支持**（Apple TV App 等）；需要与发送端同子网 |

实测反馈（XDA 用户，Fire TV Stick / Android 9，iPhone iOS 26.5）：
「mirroring is smooth, with occasional minor lag」——**可用**。

### 对本项目的意义

- 它**完全绕开**了我们卡住的那条链路：不需要 QT 端点、不需要 PING/CWPA、不需要 TLS、
  不需要开发者模式、不需要"每次开机只有一次机会"。iPad 在控制中心点「屏幕镜像」即可。
- 代价：**不是自动的**（要用户手动点一下）；延迟高于有线 QT（无线 60ms 量级 vs 有线 3.6ms）；
  许可证是 **GPL-3.0**——若并入本项目，整个 APK 需按 GPL 开源。

---

## 四、建议的验证顺序（成本从低到高）

### 第 1 步：先用现成 APK 验证"能不能投"（约 5 分钟，零开发）

把 `jqssun/android-airplay-server` 的 release APK 装到宿主，iPad 与宿主**连同一 WiFi**，
iPad 控制中心 →「屏幕镜像」→ 选中它。

- **能投** ⇒ 路径 C 对这台 iPad 成立，后面再谈集成方式；
- **不能投** ⇒ 直接排除，不必投入移植工作量。

### 第 2 步（若第 1 步成立）：把"无线"做成"点对点"

宿主的 `/sys/class/net` 里有 **`ap0`**，说明支持 SoftAP。
用 `WifiManager.startLocalOnlyHotspot()`（API 26+，不需要 root/系统权限）让宿主自己开热点，
iPad 连这个热点 —— 不依赖家里路由器，延迟和稳定性都可控，体验上最接近"有线"。

### 第 3 步（若确实必须物理有线）

三条备选，按代价排序：

1. **换蜂窝版设备**：iPhone/iPad 蜂窝版开个人热点 → USB CDC-NCM 网卡出现 → 路径 B 成立；
2. **USB 网卡直连**：设备侧插 USB 以太网适配器？——不行，iPad 侧是 Lightning/USB-C，
   且 iOS 只把以太网适配器当"上网出口"，不能当"给主机提供网络"；
3. **自研 USB 隧道 + 用户态 IP 栈**：把 AirPlay 流量塞进 usbmux 的 TCP 隧道 ——
   **不可行**，iOS 的 AirPlay 客户端只走标准网络接口，不会把流量路由到 usbmux。

---

## 五、与已做工作的关系

| | QT USB 采集（`probe/ios-usb-capture`） | AirPlay 接收端（本分支） |
|---|---|---|
| 触发方式 | 主机发 control 请求把设备切进采集配置 | **用户在 iPad 上手动点"屏幕镜像"** |
| 依赖 | QT 端点必须由设备侧放行（**本机不放行**） | 无（走标准 AirPlay） |
| 延迟 | 3.6 ms（iPhoneMirror 实测） | 数十 ms（无线） |
| 自动化程度 | 插入即投（若通） | 需手动操作一次 |
| 现状 | 协议全通、设备不推流 | **未验证，但已有成熟开源实现** |

**建议**：保留 `probe/ios-usb-capture` 作为技术存档（协议链已全部实证），
把产品路径切到 AirPlay 接收端——先用现成 APK 验证，再决定是 fork 集成还是自己实现。

---

## 六、待确认

1. 第 1 步的 APK 验证结果（决定这条路成不成立）；
2. 若集成，许可证怎么处理（GPL-3.0 传染性）；
3. 是否需要"点对点热点"（影响是否要申请 `NEARBY_WIFI_DEVICES` / `CHANGE_WIFI_STATE` 等权限）；
4. 延迟目标：无线 AirPlay 在同热点下大概 40–80ms，若要求 <30ms 则这条路不满足。
