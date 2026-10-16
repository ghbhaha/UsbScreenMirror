# UsbScreenMirror

把一台安卓手机的屏幕，镜像到**另一台安卓设备**上——走 USB 配件协议（AOA v2），
**运行时不需要 adb 授权、不需要开发者选项、不需要 root，也不需要往手机里推送任何服务端进程。**

插上线、点一两次系统弹窗，画面就出来了。目标场景是「手边有两台安卓设备，想让其中一台变成另一台的扩展屏」，
而通常的做法（scrcpy 之类）要先开 USB 调试、配 adb 密钥、再往手机推一个服务端——这套东西在本项目里全部省掉。

---

## 它是什么 / 不是什么

先在开工前把期望对齐，这一节比后面的技术细节更重要。

| | 说明 |
| --- | --- |
| ✅ **省掉的** | adb 授权、开发者选项、USB 调试开关、密钥协商、往手机推送服务端、root |
| ✅ **用到的** | 一根 OTG 数据线；Android 系统自带的 USB 配件协议（AOA）与 MediaProjection / MediaCodec |
| ⚠️ **省不掉的** | **Android 14+ 每次投屏仍要点一次录屏授权**——系统取消了 MediaProjection 的"记住选择"，无法绕过 |
| ⚠️ **可能还要点一次** | 首次插线时系统**可能**弹「是否打开该 App 处理 USB 配件」，勾选默认后此后插线即达 |
| ❌ **本期不做** | 鼠标/键盘输入（见「已知限制」） |

一句话：**省掉的是"链路的准备工作"，不是"这一次点击"。**

---

## 工作原理

```
┌────────────────────────┐                        ┌────────────────────────┐
│  宿主 Host             │        OTG 数据线       │  配件 Accessory        │
│  （另一台安卓设备）      │◄──────────────────────►│  （被镜像的手机）        │
│                        │                        │                        │
│  app-host              │  ① AOA 握手 51/52×6/53 │  app-accessory         │
│   UsbAttachRouter ─────┼───────────────────────►│   CaptureService       │
│                        │  ② 手机重枚举为配件     │                        │
│   HostSession ◄────────┼───────────────────────┤   VirtualDisplay       │
│   MediaCodec 解码       │  ③ CNXN / OPEN / OKAY  │   MediaCodec 编码       │
│   SurfaceView 显示      │◄──────────────────────┤                        │
└────────────────────────┘  ④ WRTE 视频帧流       └────────────────────────┘
```

关键点在于**②**：宿主要用 AOA 的 52 号控制请求把「配件身份」写进手机，
手机内核随即把 USB 设备重枚举成配件形态（`VID:PID = 18D1:2D01`），
系统再据此把 `USB_ACCESSORY_ATTACHED` 派发给手机端 App。
**身份写不进去或写得不对，后面一步都不会发生**——这是整个项目最容易踩的坑，
详见 [`docs/排错手册.md`](docs/排错手册.md) 第 3.4 节。

原理与选型的完整推导见 [`docs/配件投屏核心原理.md`](docs/配件投屏核心原理.md)。

---

## 架构

单 Gradle 工程、三个模块，交付为**两个独立 APK**（宿主一个、手机一个）。

| 模块 | 产物 | 职责 |
| --- | --- | --- |
| `:protocol` | 纯 Kotlin/JVM 库 | 报文编解码、常量、连接编排、队列、缩放。**零 Android 依赖** |
| `:app-host` | `app-host-debug.apk` | 宿主：AOA 握手 → USB 通道 → 解码 → `SurfaceView` 渲染 |
| `:app-accessory` | `app-accessory-debug.apk` | 手机：配件通道 → 虚拟屏采集 → 编码 → 推帧 |

`:protocol` 之所以必须是**纯 JVM 库**，是因为整个项目里唯一能在没有真机时真正验收的部分就是它——
把纯逻辑（`AnnexB`、`AccessoryIdentity.encodeSendString`、`Scale` 等）全部下沉进来，
才能用 JVM 单测把它们钉死。日志走注入式 `Logger` 接口，USB 代码只存在于两端各自的 `ByteChannel` 实现里。

---

## 命名说明

产品名与技术标识不是同一个东西，**改名前请先看这张表**：

| 名称 | 值 | 能否随意变更 |
| --- | --- | --- |
| 产品名 | **UsbScreenMirror** | ✅ 随意 |
| Gradle 工程名 | `usbscreen` | ✅ 随意（仅影响 IDE 显示） |
| Android 包名 | `com.uav.host` / `com.uav.accessory` | ⚠️ 改了等于换应用，两端必须同步 |
| 配件身份 | `UAV` / `usbscreen` / `1.0` | ⚠️ **这是协议内容**，见下 |

配件身份（`manufacturer` / `model` / `version`）**参与系统匹配**，三处必须逐字一致：
`:protocol` 的 `AccessoryIdentity`、`app-accessory` 的 `accessory_filter.xml`、以及实际握手时发出的字符串。
（XML 无法引用 Kotlin 常量，属**手工镜像**，改一处必须同步另一处。）

---

## 环境要求

| 项 | 要求 | 备注 |
| --- | --- | --- |
| JDK | **17+** | `gradle.properties` 里用 `org.gradle.java.home` 锁了 JDK 21 的**绝对路径**，换机器必须改 |
| Android SDK | `compileSdk=36` / `buildTools=36.0.0` | `minSdk=24`、`targetSdk=36` |
| Gradle | 8.14.5（wrapper 已配） | 走腾讯云镜像，无需手动装 |
| `local.properties` | **必须**有 `sdk.dir=<你的 SDK 路径>` | ⚠️ 本机 `ANDROID_HOME` 为空，不能靠环境变量 |
| `GRADLE_USER_HOME` | 建议显式设成一个固定目录 | 依赖缓存按此目录复用；不设则用默认的 `~/.gradle`。另外**锁 JDK 建议写在 `$GRADLE_USER_HOME/gradle.properties`（用户级）**，别写进项目文件——绝对路径一旦入库，别的机器会构建失败 |

---

## 构建与验证

```bash
# 协议层单测（唯一可纯本地验证的部分，当前 237 项）
./gradlew :protocol:test

# 两个 APK
./gradlew :app-host:assembleDebug :app-accessory:assembleDebug
./gradlew :app-host:assembleRelease :app-accessory:assembleRelease
```

产物位置：

```
app-host/build/outputs/apk/debug/app-host-debug.apk
app-accessory/build/outputs/apk/debug/app-accessory-debug.apk
```

> **`-release.apk` 不能用来排错**：release 构建下回放入口、抓流落盘、adb 调试 extra、
> 周期统计日志全部被关掉（`DebugTools` 门控）。详见 `docs/排错手册.md` 第 0 节。
> 另外 release 产物默认不签名，直接 `adb install` 会失败。

---

## 运行

```bash
# 1) 两个包分别装上（adb 唯一被用到的场景就是这一步）
adb -s <宿主设备>  install -r -g app-host/build/outputs/apk/debug/app-host-debug.apk
adb -s <配件手机>  install -r -g app-accessory/build/outputs/apk/debug/app-accessory-debug.apk

# 2) 启动宿主，然后把手机插到宿主上
adb -s <宿主设备> shell am start -n com.uav.host/.HostActivity
```

接线的顺序与弹窗：

1. 手机插上宿主 → 系统**可能**弹「**是否打开该 App 处理 USB 配件**」→ 勾选默认（此后插线即达）
2. 手机弹「**开始录制或投放**」录屏授权 → 允许（Android 14+ 每次都要点）
3. 宿主的 `SurfaceView` 上出现手机画面

宿主界面上有三个按钮：

| 按钮 | 用途 |
| --- | --- |
| `SCAN / RETRY` | 手动重新扫描设备。**部分宿主的 USB host 栈在手机重枚举后不发 attach 广播**，此时靠它（App 内已有 2 秒轮询兜底） |
| `FORCE REHANDSHAKE` | 强制走完整 AOA 握手，重写配件身份。用于「手机被别的宿主 App 切成配件模式」 |
| `REPLAY .UAV DUMP` | 调试用：回放抓流文件，跳过整个 USB 与握手（**仅 debug 包**） |

连不上时**先看 [`docs/排错手册.md`](docs/排错手册.md)**，那里面按「症状 → 根因 → 处置」组织，
并有一条最重要的排错手段：用 `.uav` 回放把问题切成「USB/协议」与「解码/渲染」两半。

### 已知的环境不稳定点

- ⚠️ **「等待重枚举超时」不是 OTG 不稳**（旧结论，已推翻）：真因是我们自己漏掉配件 fd，
  破坏了 `/dev/usb_accessory` 的**独占打开**，于是手机再也切不进配件模式。
  「重插拔即恢复」只是顺带杀掉了持 fd 的进程。**已在 10.8 修掉**（含 3 项回归单测），
  别再靠拔插去"确认"问题，用 `docs/实施计划.md` 10.8 的 fd 计数法。
- **供电与 OTG 常互斥**：手机当宿主时一边给配件供电、一边自己掉电。
  长时使用建议用 **OTG + 充电二合一线（带 PD 口）**。
- ⚠️ **宿主设备可能自己 kernel panic 重启**（实测环境 BOE P40UCH / MT8781V / 内核 5.10.226）：
  一天内连续三次启动的原因都是 `kernel_panic,bug`（其中一次只活了 111 秒），
  而同一时刻手机端启动原因完全正常 —— 是**宿主设备的固件/内核问题，App 层解决不了**。
  宿主突然重启时先读 `persist.sys.boot.reason.history` 再谈排查，
  **别误判成我们的 bug**。见 `docs/排错手册.md` 3.9。
- **别随手 `am force-stop` 手机端 App**：App 会进入 stopped 状态，系统此后不再用隐式 intent
  （配件 attach）拉起它，很容易把「链路不通」误判成软件 bug。
- **Git Bash 下操作 adb 要设 `export MSYS_NO_PATHCONV=1`**：否则 Android 绝对路径会被改写成 Windows 路径。

---

## 协议速览

两端必须逐字一致，完整清单见 [`docs/实施计划.md`](docs/实施计划.md) 第 4 节。

| 项 | 约定 |
| --- | --- |
| 报文头 | **24 字节，全小端**：`command / arg0 / arg1 / data_length / data_check / magic` |
| `magic` | `command xor 0xFFFFFFFF`——**字节流错位的第一道防线**（一旦错位，magic 必然对不上） |
| `data_check` | payload 逐字节无符号累加 |
| 命令字 | `CNXN / OPEN / OKAY / CLSE / WRTE`（裁剪掉 `AUTH` / `STLS`） |
| 流 id | `0=control` / `1=video` / `2=audio`；`WRTE.arg0` 是**唯一的流标识** |
| 流头 | 该流**首条** `WRTE` 的 12 字节 payload：`codecId`(4 ASCII) + `width` + `height` |
| 帧 | 每条 `WRTE` payload = `8B ptsAndFlags` + `4B size` + 码流 |
| `ptsAndFlags` | bit63 = CONFIG、bit62 = KEY_FRAME、bit61 预留方向（本期未启用） |
| 上限 | `data_length` 与帧 `size` 均 ≤ **8 MiB** |
| 超时 | `controlTransfer` **1000ms（严禁 0，0 = 无限等待）**；bulk 读 200ms / 写 5000ms |

另外两个关键约定：

- **CONFIG 帧永不吞掉**：解码器配置依赖它，丢了会导致空参数 `configure`。
- **丢弃 P 帧的代价是花屏**，最长 = I 帧间隔（本项目取 **3s**）。

---

## 项目状态

| 项 | 状态 |
| --- | --- |
| 端到端投屏（MVP） | ✅ **真机验证通过**——宿主 `SurfaceView` 上出现手机画面 |
| 协议层单测 | ✅ **237 项全绿**（含两端 `Connection` 对跑的端到端协议联调） |
| 离线回放 | ✅ 28.7 MB 真机抓流按 PTS 配速回放，`dropped=1` |
| release 构建 | ✅ 已端到端真机验证（功能完整，仅少了调试入口） |
| 实测机型 | 宿主 BOE **P40UCH** / Android 14；配件 HONOR **MAA-AN10** / Android 16 |
| 实测输出 | `max_size=992` → 编码 **456×992**（1:1 像素映射：按**宿主显示区** 1920x987 算出，见 10.15） |
| 端到端延迟 | p50 **64ms** / p95 **140ms** / p99 159ms（fresh 口径，n=300，且不含最后 1 个 vsync） |

### 已知限制

- **旋转已支持**：手机侧需**开启自动旋转**。虚拟屏全程复用（`MediaProjection` 令牌
  只允许 `createVirtualDisplay` 一次）、编码器按新尺寸重建、新尺寸经**重发 12B 流头**
  告知宿主 —— 宿主会自动按新方向重配解码器。设计与实测见 `docs/实施计划.md` 10.7。
- **延迟已实测**：端到端 p50 **64ms** / p95 **140ms**（fresh 口径，n=300，见 `docs/实施计划.md` 10.6）。
  ⚠️ 该口径**不含最后 1 个 vsync**（约 8~16ms）；按加上界算 p95 ≈ 148~156ms，
  即**正好卡在 150ms 线上**，不能算轻松达标。
- **断链会自动恢复**：拔插或链路抖动后**无需任何人工操作**，约 1.5s 内画面自己回来，
  且**不会重新弹录屏授权**（投影与编码器都保留）。见 `docs/实施计划.md` 10.8。
- **切后台不会断链**：`SurfaceView` 的 Surface 被销毁时只**停渲染、保持链路**，
  回前台用缓存的 CONFIG 重建解码器 —— 全程**不触发 USB 重枚举**，
  也修掉了原先"按 Home 就崩"的问题。见 10.10。
- **HID 触摸注入已探针验证**（宿主 → 手机）：手机上会真的多出一块**触摸屏**
  （`INPUT_PROP_DIRECT` + `ABS_MT_*`），注入的坐标逐字节精确到达 —— **手机端零改动**，
  且走 EP0、不占视频带宽。⚠️ 目前只是探针（adb 驱动的调试入口），
  **尚未接进正式流程**，且**重枚举后必须重新注册**。见 `docs/实施计划.md` 10.9。
- **触摸可以反向控制手机**（路线图 P1「输入通道」）：直接用**宿主的屏幕当手机的触控屏**
  —— 点、拖都行，手机上同一个位置就发生同一次触摸。走 AOA HID，**手机端零改动**、
  不占视频带宽；**最多 5 指同时**（双指缩放可用）；已处理旋转、重连重注册、残留清理。见
  `docs/实施计划.md` 10.11（单指）/ **10.12（多点）**。
- **有音频（内录）**：Android 10+ 用 `AudioPlaybackCaptureConfiguration` 把手机**正在播放**的
  声音录下来，AAC-LC 128kbps 经 `AUDIO` 流送到宿主播放 —— 是"手机里响什么宿主就响什么"，
  **不是**麦克风（不会把环境噪音也带过去）。真机实测：抖音/游戏的声音两端电平逐拍吻合。见
  `docs/实施计划.md` **10.13**。⚠️ 只能捕获 `USAGE_MEDIA`/`USAGE_GAME`/`USAGE_UNKNOWN`；
  通话、通知、铃声，以及声明了 `allowAudioPlaybackCapture="false"` 的应用（银行/DRM）
  **按设计录不到**。精细 A/V 同步尚未做。
- **点画面外的空白处可显示/隐藏宿主菜单**：视频区内的触摸全部留给"反向控制手机"，
  左/右黑边（信箱边）上的点击才是菜单开关；首帧到达时菜单自动收起一次，
  出错/断开时自动展开。见 `docs/实施计划.md` **10.14**。
- **1:1 像素映射**：编码尺寸按**宿主实际显示区**算（不再只看长边），宿主端不做二次缩放。
  实测编码像素从 1.67 Mpx 降到 0.45 Mpx（27%），清晰度不降、编解码工作量大幅降低。
  手机旋转时尺寸**自动重算**（不需要重新协商）；宿主自己旋转会重建会话以重新协商。见 10.15。
- **像素模式开关**（宿主菜单「画质」按钮）：`1:1`（省算力）↔ `高清`（按面板长边编高分辨率、
  宿主端缩下去 = 效果上超采样，观感更锐）。选择本地持久化。
  切换走**原地重协商**（在同一条连接上重发 `OPEN`，不重连、约 2~3s 生效）。见 10.17 / 10.18。
- 无鼠标/键盘输入。

- 无传输加密——安全性完全依赖**物理接触**。
- 静态画面下约 **10 fps 属正常**（`KEY_REPEAT_PREVIOUS_FRAME_AFTER=100ms` 兜底送帧），不是性能问题。

### 路线图

| 优先级 | 项 |
| --- | --- |
| ✅ 已完成 | 旋转三件套（10.7）、自动重连 + fd 泄漏修复（10.8）、HID 触摸注入（10.9）、Surface 生命周期（10.10）、触摸转发（10.11）、多点触摸（10.12）、音频内录（10.13）、**1:1 像素映射（10.15）** |
| P1 | 整 GOP 尾部丢弃、音频精细 A/V 同步、按面积缩放码率（10.15 未做项） |
| P2 | 流健康监测、编码器死亡上报、低延迟调优、HID 鼠标、宿主真双指手势的硬件验收（见 10.12 末） |
| P3 | 传输加密 |

---

## 文档

| 文档 | 内容 |
| --- | --- |
| [`docs/配件投屏核心原理.md`](docs/配件投屏核心原理.md) | 项目定位、技术选型、AOA 与协议原理 |
| [`docs/实施计划.md`](docs/实施计划.md) | 协议冻结清单 v2、分步实施、风险清单、**真机实测记录（10.4）** |
| [`docs/排错手册.md`](docs/排错手册.md) | 症状速查表、二分定位法、adb 操作红线、日志判读 |

---

## 许可

尚未指定许可证。当前代码未附带任何开源许可，默认保留所有权利。
