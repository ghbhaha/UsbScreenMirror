# IosActivity 协程重构分析

> 状态：**P0 + P1 + P2（拆分）+ 审查报告修复已落地**（分支 `refactor/ios-coroutines`，编译 / `:protocol:test` / `lintDebug` 三绿，`compileDebugKotlin --rerun-tasks` 零 `e:`/零 `w:`；真机回归待做）。
> 本文基线为重构前工作树（`IosActivity.kt` 1123 行，`extends Activity()`；零协程依赖）。拆分结果见§六之二。
> 版本信息见文末「版本记录」。
>
> ⚠️ **2026-10-21 补充：本文拆出去的 `run/IosRearmer`（「强制复位采集通道」）已整体删除** ——
> 功能无效，待重新调研。本文保留它作为**重构历史记录**，读的时候别去代码里找这个文件；
> 删除理由见 [`排错手册.md` §3.17](排错手册.md)。

## 一、目标

把 `app-host\src\main\java\com\uav\host\ios\IosActivity.kt` 从裸 `Thread` + `CountDownLatch` + `Handler` 轮询的并发模型，迁移到 Kotlin 协程，换取：

- **结构化并发**：生命周期自动取消，不再手写 `onDestroy` 标志轮询。
- **可读性**：编排层用挂起函数线性表达，替代 `Thread { }.start()` 的散落启动。
- **健壮性**：消除跨线程共享可变状态的裸 `Thread` 心智负担。

**边界（绝不越界）**：不改变 USB 收流/投屏的既有行为；`bulkTransfer` 不可中断这一物理约束决定了「停止」只能靠信号机制，不能靠协程取消。

## 二、当前线程模型（重构前基线）

| 并发机制 | 职责 | 硬约束 |
|---|---|---|
| `ios-auto` Thread | 配对 → 投屏主流程（`autoRun` :284） | `bulkTransfer` 阻塞；`AtomicBoolean.compareAndSet` 防重入 |
| `replay` Thread | 离线回放 `feed.bin`（:373） | 文件读 + `sleep` |
| `ios-rearm` Thread | 240s 重枚举等待（:584） | `sleep` + 轮询 |
| `mux-keepalive` Thread（capture 内 :865, daemon） | lockdown 保活 | **teardown 靠 `muxThread?.interrupt()` 打断卡在 `usb_start_wait_urb` 的 URB** —— 必须留裸线程 |
| `qt-writer` Thread（QtSession 内部） | 异步写队列 | `ReentrantLock`+`Condition`，已封装成自洽状态机 |
| `autoPoller` Runnable（Handler 主线程 :253） | 前台设备轮询 1s | 需随 `onPause`/`onResume` 启停 |
| `CountDownLatch` `permissionLatch`（ensurePermission :1101） | 等 USB 权限广播 | `await(25s)` + 广播恢复 |

关键收尾路径（`capture()` :746 `finally`）：`keepAlive.set(false)` → `interrupt muxThread` → `sendStopSequence(HPA0/HPD0)` → `muxLink.shutdown(FIN)` → release 接口 → 还配置 → `audioPlayer.stop()` → close conns。

## 三、迁移分层（A/B/C/D）

迁移按「改动边界」切成四层，每层独立可验证：

- **A · 编排层只迁**：`autoRun` / `replay` / `rearm` 从裸 `Thread` 迁 `lifecycleScope.launch(Dispatchers.IO)`；`autoPoller` 迁 `repeatOnLifecycle(STARTED)`。只动线程宿主，不改流程逻辑。
- **B · 读循环改调用形态**：`QtSession` 读循环里 `stopSignal` 判据保留；`bulkTransfer` 仍是阻塞调用，仅把循环放进协程体，不改成挂起。
- **C · USB 原语只包不换**：`UsbDeviceConnection.bulkTransfer` 等 C 调用不改为挂起（无 callback 版），只做薄封装，**不承诺可取消**。
- **D · 解码/音频保持回调**：`onCsd` / `onFrame` / `onAudio` 仍走 panel 线程安全封装（`onUi{}`/`post` 已自动切主线程）+ 解码/`audio.feed` 留在专属 `Dispatcher`。

## 四、两个硬边界（不可逾越）

1. **bulkTransfer 不可中断**：协程 `cancel` 打不断阻塞的 USB 调用。必须**保留** `stopRequested` / `stopSignal` 信号机制 + 读循环每轮检查，不能靠取消停投屏。这是重构的硬边界。
2. **mux-keepalive 必须留裸 daemon Thread**：teardown 靠 `muxThread?.interrupt()` 打断卡在 `usb_start_wait_urb` 的 URB，协程 `cancel` 不调 `Thread.interrupt()`，迁过去丢这条退路（呼应 D 状态卡死事故）。⇒ **mux-keepalive 不迁。**

## 五、P0「零行为变化」地基

目标：引入依赖 + 换线程安全的日志格式化，**不改变任何行为**。绿灯 = 编译通过 + 现有行为不变。

| 文件 | 改动 |
|---|---|
| `gradle/libs.versions.toml` | 加 `kotlinxCoroutines=1.10.2` / `lifecycle=2.9.1` / `activity=1.10.1` 三版本与对应库 |
| `app-host/build.gradle.kts` | `dependencies` 补 `kotlinx-coroutines-android` / `lifecycle-runtime-ktx` / `activity-ktx` |
| `IosActivity.kt` | 基类 `Activity()` → `ComponentActivity`（androidx.activity，自带 `lifecycleScope`）；import 换掉 `android.app.Activity` |
| `IosActivity.kt` | `logFmt` 从裸 `SimpleDateFormat`（跨线程非线程安全）改为 `object : ThreadLocal<SimpleDateFormat>() { override fun initialValue() = ... }`（线程安全） |

**为什么改 `logFmt`**：`SimpleDateFormat` 非线程安全，而日志在多个收流线程触发；P0 顺手消除这个隐患，属于零行为变化的正确性修复。

> ⚠️ **版本修正（v2.1）**：旧方案写的是改用 `java.time.DateTimeFormatter`，**不可行**——`java.time` 是 API 26+，本项目 `minSdk = 23` 且**未开启 core library desugaring**（无 `isCoreLibraryDesugaringEnabled`），照改会在 minSdk 23 设备上崩。正解是线程局部 `SimpleDateFormat`：零新增依赖、minSdk 安全、线程安全。
>
> ⚠️ **版本修正（v2.3）**：v2.2 曾用 `ThreadLocal.withInitial { ... }`，那是 **API 24** 才有的方法，同样超出 `minSdk 23`（运行时 `NoSuchMethodError`）。改为继承 `ThreadLocal` 覆写 `initialValue()`（API 1 起可用）。`lintDebug` 无 `NewApi` 告警可作闸门。

## 六、P1 迁移清单（已落地）

| 项 | 迁移前 | 迁移后 |
|---|---|---|
| `autoRun` / `replay` / `rearm` | `Thread({...}, "name").start()` | `run.launch(name) { ... }`（`IosCaptureRun`），收尾统一进 `finally`（`NonCancellable` 包裹） |
| `ensurePermission` | `CountDownLatch.await(25s)` | 保持**阻塞签名**，内部 `runBlocking { withTimeoutOrNull(25_000) { suspendCancellableCoroutine { ... } } }`；字段 `permissionLatch` → `@Volatile permissionCont`，取消时置空防迟到广播 resume 死续体 |
| `replay` / `rearm` 里的 `Thread.sleep` | `sleep(16)` / `sleep(800)` / `sleep(700)` | `delay(...)`（真挂起，不占线程） |
| `mux-keepalive` | 裸 daemon Thread | **原样保留**（见硬边界②） |
| `qt-writer` | 内部 `ReentrantLock`+`Condition` | **原样保留**（自洽状态机，动它收益低风险高） |
| `waitForNormalConfig` | 无调用方的死代码 | 删除 |
| `waitForCaptureMode` 轮询 | 只判 deadline | 加 `!stopRequested` 判据，与 rearm 循环对齐 |

**两个刻意的设计取舍**：

1. **不把 `suspend` 冒泡到 `capture()` / `pairNow()`**：这条链上全是不可取消的 USB 阻塞调用，改成挂起收益为零；且 `capture()` 需要保持「先收尾、后返回 stats」的同步语义。
2. **`ensurePermission` 不用 `lifecycleScope`**：它挂在主线程上，里面一旦走 USB 阻塞调用就直接 ANR。用 `runBlocking` 只堵当前 IO 线程。

**顺带修好的缺陷**：`rearm` 的 `return@Thread` 会跳过函数体末尾的复位三行（`running.set(false)` / `setBusy(false)` / `refreshDeviceInfo`），而 `running` 是在入口 `set(true)` 置位的 ⇒ 一次提前返回就让这个 Activity 再也起不来投屏。收尾挪进 `finally` 后该路径被堵死。

## 六之二、P2 拆分（已落地）

一个文件 1123 行、混着 USB 原语、协议、解码、界面和流程控制，改任何一处都要先读半个文件。拆完之后按「能力 / 流程 / 运行 / 界面」四层分包，依赖**只许向下**：

```
com.uav.host.ios          IosActivity（编排 + 归因，唯一入口）
 ├─ run/                 一次运行的登记、轮询、重放、重枚举等待
 ├─ flow/                配对一条链、采集一条链（有状态、会跑几十秒）
 ├─ capture/             QuickTime over USB 的能力：连接、会话语义、mux、配对记录、解码宿主
 ├─ media/               纯解码（H264Decoder，不认识 ios）
 └─ ui/                  IosPanel（纯 View，不认识 USB）
```

| 包 | 文件 | 行数 | 职责 |
|---|---|---|---|
| — | `IosActivity.kt` | 672 | 生命周期、自动开始轮询、重新武装/回放入口、失败归因、界面复位 |
| `flow/` | `IosCaptureFlow.kt` | 672 | 采集主流程：开放端点 → mux → 会话 → 收尾（`IosCaptureHost` 接口回问宿主）⚠️ 待再切 |
| `flow/` | `IosPairingFlow.kt` | 140 | 配对 / 信任校验（无界面依赖） |
| `flow/` | `IosAutoFlow.kt` | 55 | 自动流程编排：查配对记录 → 没有则配对 → 采集 |
| `capture/` | `MuxLink.kt` | 509 | mux 会话，`pumpOnce` 非阻塞泵 |
| `capture/` | `QtSession.kt` | 436 | 一帧协议的读循环 + 写队列（带 `logThrottle` 限流） |
| `capture/` | `Pairing.kt` | 313 | 配对报文 |
| `capture/` | `Identity.kt` | 258 | 身份证书与 TLS 隧道 |
| `capture/` | `PcmAudioPlayer.kt` | 227 | PCM 播放 |
| `capture/` | `QtTransport.kt` | 209 | USB 连接/配置/接口的原语包装（`ctx: Application`） |
| `capture/` | `IosDecoderHost.kt` | 137 | 解码器与音频的宿主（`onCsd`/`onFrame`/`onAudio`/日志） |
| `capture/` | `IosUsbPermission.kt` | 76 | 权限广播 → `await(dev, timeout)` 挂起等待 |
| `capture/` | `IosFeatures.kt` | 50 | 产品级开关（`TLS_WAIT_MS` / `CAPTURE_CONFIG_COUNT`） |
| `run/` | `IosRearmer.kt` | 158 | 配置 5→3→`activate` → 等重枚举 |
| `run/` | `IosReplay.kt` | 81 | 离线回放 `feed.bin` |
| `run/` | `IosCaptureRun.kt` | 70 | 「一次运行」的登记与**无条件复位** |
| `run/` | `IosAutoStarter.kt` | 71 | 前台轮询找 Apple 设备（`resetBaseline` 供拔插重来） |
| `media/` | `H264Decoder.kt` | 234 | 纯 H.264 解码，不认识 ios 的流程 |
| `ui/` | `IosPanel.kt` | 381 | 纯 View 层 |

**拆分后的三处关键设计**：

1. **`IosCaptureRun` 是运行标志的唯一持有者**。协程只提供**归属**（`Job` + `CoroutineName` 进 logcat），不提供**中断**——`bulkTransfer` 不可中断（硬边界①）。`launch()` 的 `finally` 里用 `NonCancellable` 包住「清标志 + 界面复位」，保证 `return@runCatching` 之类的提前退出也复位。
2. **`stopProjection()` 不再清运行标志**。旧实现 `running.getAndSet(false)` 会让轮询立刻认为空闲、重新拉起一次投屏，而旧会话还在收尾 ⇒ 两个会话抢同一台设备。清标志只在 `IosCaptureRun` 的 `finally`。
3. **`ensurePermission` 维持阻塞签名**。它被 `IosPairingFlow` / `IosCaptureFlow` 这两条"全是不可取消 USB 调用"的同步链调用，把 `suspend` 冒泡上去要改掉每一处调用的形状，收益为零。挂起等待收在 `IosUsbPermission.await` 里，`runBlocking` 堵的是 IO 线程。

**刻意留下的重复**：`restoreToNormal` / `ensurePermission` 仍由 `IosActivity` 实现、以函数引用的形式注入两个子流程。它们需要 `transport` 与 `permission`，而这两者属于宿主；拆成独立类只会把同一份 `transport` 传第二遍。

## 六之三、审查报告修复（已落地）

外部审阅报告约 40 条 finding，全部修复，编译四门全绿。按文件归纳关键改动：

| 文件 | 修复 |
|---|---|
| `capture/IosUsbPermission` | 广播一律唤醒（`granted=false` 也要收，防白等 25s）；不用 `tryResume/completeResume`（`@InternalCoroutinesApi`，编译 error），改用 `isActive + resume`；取消/异常路径摘 `cont` |
| `media/H264Decoder` | `dequeueInputBuffer(0)` 拿不到就丢帧（不阻塞读循环）；csd 整体注入失败按 `AnnexB.split` 切分回退；`configure` 失败 release 半建 codec；`runCatching` 只打第一次错；`BufferInfo` 改用成员复用 |
| `flow/IosCaptureFlow` | `Mux.close()` 里 `!!` 消除；`Thread.sleep`→`delay`；`CAPTURE_CONFIG_COUNT` 改用 `IosFeatures` 常量；新增 `logThrottle`（`SKEW`/`FEED #`/`渲染` 前缀硬编码从 `IosDecoderHost.log` 搬走）；`aborted` 公开只读供宿主复位基线 |
| `capture/QtTransport` | `context: Context` 谎话修正——构造改 `ctx: Application`（防 Activity 隐性泄漏），`appContext` 只读 getter |
| `capture/QtSession` | 新增 `logHot(key, msg)` 限流日志；空闲心跳走限流 |
| `capture/Pairing` | `doPair` 加 `suspend`（`delay` 生效）；`Thread.sleep(1000)`→`delay(1000)` |
| `flow/IosPairingFlow` | `Thread.sleep(400)`→`delay(400)`；`>= 5` 用 `IosFeatures.CAPTURE_CONFIG_COUNT`；删 `onDeviceVersion` 构造参数 |
| `capture/IosDecoderHost` | `log()` 删掉 `SKEW`/`FEED #`/`渲染` 前缀硬编码丢失行（改为产生方限流） |
| `run/IosAutoStarter` | 新增 `resetBaseline()`：采集因设备侧原因中止后复位基线，拔插重来生效 |
| `run/IosRearmer` | `ensurePermission` 改 `suspend`；`restoreToNormal` 返回 false 时新增 `Result.RESTORE_FAILED` 提前返回 |
| `IosActivity` | 重写为纯编排 + 归因 + 界面复位；`ensurePermission` 去掉 `runBlocking` 改 `suspend`；`aborted` 触发 `autoStarter.resetBaseline()` |

## 七、验证闸门

```powershell
.\gradlew.bat clean :app-host:assembleDebug :protocol:test   # 编译 + 单测
.\gradlew.bat :app-host:lintDebug                             # 查 NewApi：minSdk 23 的 API 越界会红
.\gradlew.bat :app-host:compileDebugKotlin --rerun-tasks      # 看 w: 告警（增量构建会吞掉）
```

- P0/P1/P2/审查修复绿灯 = 上面三条命令 `BUILD SUCCESSFUL`、零 `e:`、零 `w:`，且 `lintDebug` 无 `NewApi` 项。
- 真机回归（本条未做，需设备）：投屏、回放、重连、停止、USB 权限授权路径。

> ✅ **v2.4 更正**：v2.2 记录过「当前 sandbox 无法跑 Gradle」——**该结论作废**。根因是当时会话处于 `workspace-write` 沙箱，而 `GRADLE_USER_HOME=D:\Develop\.gradle` 在 workspace 之外，Gradle 启动即要写 `.lck` 被拒（**预期性的权限边界，不是 ACL 缺陷**，也不是 `native-platform.dll` 问题）。切到完全权限后 `:protocol:test` 与 `:app-host:assembleDebug` 均一次通过。教训已写入项目记忆：跑 Gradle 需要完全权限。

## 八、版本记录

| 版本 | 日期 | 说明 |
|---|---|---|
| v2 | 2026-10-19（重写） | 用户确认之前方案甲/P0 改动已全部删除，基于当前工作树重新推导。方向仍为方案甲（ComponentActivity + lifecycleScope，分层 A/B/C/D），硬边界不变。 |
| v2.1 | 2026-10-19 | 修正 P0 的 `logFmt` 方案：弃用 `java.time.DateTimeFormatter`（API 26+，本项目 minSdk 23 且无 desugaring），改用 `ThreadLocal<SimpleDateFormat>`。 |
| v2.2 | 2026-10-19 | P0 源码改动已实际落地（未提交）：libs.versions.toml +3 依赖、build.gradle.kts +3 依赖、IosActivity 基类→ComponentActivity + logFmt→ThreadLocal。编译验证被环境阻塞（见§七）。P1 未动。 |
| v2.3 | 2026-10-19 | 修正 `logFmt`：`ThreadLocal.withInitial{}` 是 API 24，改继承 `ThreadLocal` 覆写 `initialValue()`（minSdk 23 安全）。 |
| v2.4 | 2026-10-19 | P1 落地：三入口迁 `lifecycleScope`、`ensurePermission` 改续体、`Thread.sleep`→`delay`、删死代码 `waitForNormalConfig`、`waitForCaptureMode` 加停止判据、修 `rearm` 提前返回漏复位。§七 更正：环境阻塞结论作废（实为沙箱权限边界）。 |
| v2.5 | 2026-10-20 | P2 拆分落地：`IosActivity.kt` 1123 → 648 行，新增 `IosCaptureFlow` / `IosPairingFlow` / `IosDecoderHost` / `IosUsbPermission` / `IosRearmer` / `IosReplay` / `IosAutoStarter` / `IosCaptureRun`（见§六之二）。修 `stopProjection` 清标志导致两个会话抢设备。闸门补 `--rerun-tasks` 看 `w:`。 |
| v2.6 | 2026-10-20 | 分包：`ios/` 下单层 16 个类拆成 `run/` `flow/` `capture/` `media/` `ui/` 五包，依赖只许向下（见§六之二）。`H264Decoder` 移出 ios 语义，归 `media/`。只动包位置与 import，无逻辑改动。 |
| v2.7 | 2026-10-20 | 审查报告修复落地（见§六之三）：`IosUsbPermission` 防重复 resume；`H264Decoder` 不阻塞/不泄漏；`QtTransport` 改 `Application`；`logThrottle` 限流取代前缀硬编码；新增 `IosAutoFlow` 与 `IosAutoStarter.resetBaseline`。编译四门全绿。 |
