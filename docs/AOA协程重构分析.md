# AOA 宿主侧协程重构分析

> 状态：**P1~P5 已落地，P6 文档中**（分支 `refactor/aoa-coroutines`，已合并前验证；`develop` 未动）。
> 全部改动都各自有编译 / `:protocol:test` / `lintDebug` 闸门，关键路径另有真机记录（见 §九）。
> 参照物：`IosActivity协程重构分析.md`（`refactor/ios-coroutines` 已合并进 `develop`）。
> 本文所有数字都来自当前 `develop`（`c83b6d1`）的实际代码，不是估算。

## 一、目标

和 iOS 那次一样，只有三条：

1. **结构化并发**：生命周期一断就整体取消，不再靠 `onDestroy` 里逐个置标志。
2. **编排可读**：连接编排用挂起函数线性写出来，替代"起线程 → 回调 → 另一个线程"。
3. **等待可取消**：超时等待不再把线程钉死（iOS 那次最直接的收益）。

⚠️ **AOA 这边不追求"零裸线程"**。`bulkTransfer` 是不可中断的阻塞调用，
读循环**必须**留在一条专用线程上（iOS 侧同一条红线，见其 §四）。
协程在这里买的是"归属与取消"，不是"消灭阻塞"。

## 二、当前线程模型（基线）

| 类 | 行数 | 自建调度 | 阻塞等待 |
|---|---|---|---|
| `session/HostSession.kt` | 1288 | 3 条线程：`uav-decode` / `uav-audio-in` / `uav-session` | 2 个 `CountDownLatch`（CNXN 5s、OPEN 5s）、`awaitSurface` 里 `sleep(50)` 轮询 |
| `link/LinkCoordinator.kt` | 612 | `uav-link-io` 单线程池 + **2 处裸 `Thread` 兜底**（池已关时） | `mainHandler` 轮询 + 指数退避 |
| `usb/UsbAttachRouter.kt` | 443 | `uav-usb-worker` 单线程池 | — |
| `input/HidTouchController.kt` | 394 | `uav-hid-touch` 单线程池 | — |
| `aoa/HostActivity.kt` | 656 | — | 11 个 Listener 回调 + intent 解析 + 回放 + HID 探测 |

合计 **4 个类、8 处自建调度**（3 线程 / 3 线程池 / 2 裸线程）+ 2 个 Latch + 1 处轮询 sleep。

已经在做对的事（重构**不要**推翻）：

- `HostSession.stopInternal()` 每一步独立 `runCatching`，最后一步才 `connection.close()` —— 这是 fd 泄漏那两次事故换来的。
- `closed` / `running` 两个 `AtomicBoolean` 的语义是清楚的。
- 协议与队列全在 `:protocol`，`Connection` 有 459 项单测兜底。

## 三、iOS 经验里哪些能搬、哪些不能

| iOS 的做法 | AOA 能不能照搬 | 原因 |
|---|---|---|
| `IosCaptureRun`：运行权登记 + **无条件复位** | ✅ 直接搬 | 形状完全一样，AOA 只是缺这一层 |
| `flow/` 承载流程，Activity 只装配 | ✅ 搬 | `LinkCoordinator` 现在既编排又调度，拆开收益最大 |
| 判据下沉 `:protocol` 并加单测（`LinkLossGate` / `ReadTimer` / `InsertionBaseline`） | ✅ 搬 | AOA 的重连阈值/退避目前是散在 `LinkCoordinator` 里的魔数 |
| 等待用挂起（`delay` / `withTimeout`） | ✅ 搬 | 两个 Latch 换成 `CompletableDeferred` + `withTimeoutOrNull` 即可 |
| 日志限流下沉到产生方 | ✅ 搬 | AOA 侧还有按前缀丢弃的老写法 |
| **读循环整体协程化** | ❌ 不能 | `bulkTransfer` 不可中断；`job.cancel()` 打不断 URB（iOS 侧实测卡死数分钟） |
| **改读缓冲大小 / 分块** | ❌ 绝不能 | 向内核申请 **< 512B** 的读会触发宿主 kernel panic（本仓库最贵的坑） |
| 触摸回传 | ❌ 没有对应物 | AOA 有 HID 注入 + `TouchForwarder`，iOS 无输入通道；这部分要单独保真 |

## 四、目标分层（映射到现有文件）

```
aoa/
├── capture/            ← USB 与协议资源，几乎纯搬运
│   ├── usb/            AoaHandshake, BulkPair, BulkByteChannel, UsbAttachRouter
│   ├── session/        HostSession（只瘦身，不重写）
│   ├── video/ audio/ input/ debug/
├── flow/               ← 【新】编排，suspend
│   ├── AoaConnectFlow   权限 → 50/51/52/53 → 等重枚举 → 建 Transport
│   └── AoaStreamFlow    建会话 → 统计 → 断链判定 → 交回上层决定是否重连
├── run/                ← 【新】运行权与收尾
│   └── AoaLinkRun       轮询 / 退避 / 重连去重（现在在 LinkCoordinator 里）
├── ui/                 HostPanel + HostUiState（不动）
└── HostActivity        只装配 + 生命周期 + 状态推进（目标 <300 行）
```

`LinkCoordinator` 保留但**降级为调度器**：不再自己起线程、不再自己判断"该不该重连"。

## 五、分阶段计划

| 阶段 | 内容 | 风险 | 验收 |
|---|---|---|---|
| **P0** | 建分支；把"当前真机行为"写成清单（投屏 / 触摸 / 音频 / 拔插重连 / 前后台 / 盒子上无触摸） | 无 | `:protocol:test` 基线 469 项绿 |
| **P1** | **调度器收敛**：8 处自建调度 → 注入 `scope` + 一个 `Dispatchers.IO.limitedParallelism(1)` 的阻塞调度器（保读循环独占）。线程名用 `CoroutineName`，`top -H` 仍能认出来 | 低（不动逻辑） | 编译 0 告警；`top -H` 里线程名与旧名一一对应 |
| **P2** | **等待挂起化**：2 个 `CountDownLatch` → `CompletableDeferred` + `withTimeoutOrNull`；`awaitSurface` 的 `sleep(50)` → `delay(50)`；`sessionFlow` 变 `suspend` | 低-中 | 握手超时从"占一条线程 5s"变成"不占线程"；超时文案不变 |
| **P3** | **运行权**：抽 `AoaLinkRun`（对应 `IosCaptureRun`），把 `running/closed` 的复位收敛到一处，`finally` 保底 | 中 | 连续 `start/close` 100 次不复用失效（可写 JVM 测的纯状态机） |
| **P4** | **流程抽取**：`AoaConnectFlow` / `AoaStreamFlow`；`LinkCoordinator` 只做调度；`HostActivity` 只装配 | 中-高 | 真机全流程回归（见 §七） |
| **P5** | **判据下沉**：重连退避 / 连续失败阈值 → `:protocol/aoa/RetryPolicy`，补单测 | 低 | 新增单测；`LinkCoordinator` 里不再有魔数 |
| **P6** | 文档：本文更新为"已落地"；`排错手册` 补一节协程边界 | 无 | — |

**每个阶段单独提交、单独可回退**。P1/P2/P3 不碰业务逻辑，出问题直接 revert 那一支。

## 六、硬边界（本仓库已经付过学费的）

1. **读请求不得小于 512B**（端点包长）。`BufferedByteChannel` 让内核永远整块读 64KB —— P1/P2 都**不许**碰缓冲策略。
2. **收尾每步独立兜异常**，最后才 `connection.close()`；否则一个 `release()` 抛异常就漏 fd，手机永远切不回配件模式。
3. **`close()` 不能和读线程抢同一把锁**（读线程在锁内 `bulkTransfer` ⇒ 关会话时 ANR）。关会话一律搬后台单线程。
4. **触摸注入器的"清掉"与"重建"必须配对**（只 `onLinkReady` 绑、`onLinkLost` 清）。
5. **UI 状态只能靠 `HostUiState` 判阶段**，进度事件不得降级阶段；面板文案不许在采集路径里现编。
6. **`am force-stop` 会留设备侧陈旧会话**；A/B 必须 install → 拔插 → 观察。

## 七、验证闸门

每阶段：

```bash
./gradlew :protocol:test              # 现基线 469 项
./gradlew :app-host:assembleDebug     # 输出落文件后 grep '^w:'，不看 tail
./gradlew :app-host:lintDebug         # NewApi 兜底
```

P3 之后 + P4 之后各做一次真机回归（宿主 P40UCH / 配件 HONOR MAA-AN10）：

- [ ] 插线 → 握手 → 首帧（记录耗时）
- [ ] 触摸回传（点/拖/双指）
- [ ] 音频内录起播
- [ ] 拔线 → 面板回到空闲态 → 重插自动重连
- [ ] 前后台切换两次，画面恢复
- [ ] 盒子（RK356x，无触摸）上 `REGISTER_HID` 返回 -1 但画面正常

## 八、风险与回退

| 风险 | 处置 |
|---|---|
| 读循环协程化导致 URB 卡死无法退出 | 不协程化读循环；保持专用线程 + `stopSignal` 轮询 + `connection.close()` 打断 |
| 收尾顺序被打乱 ⇒ fd 泄漏 | `stopInternal` 原样保留，只把它包进 `finally` |
| 调度器收敛后 `top -H` 认不出线程 | `CoroutineName` + 线程名前缀 `uav-` 保持不变 |
| 大改后行为静默变化 | 每阶段单独提交；P4 之前不合并 `develop` |
| 盒子/商显回归不足 | 每次真机回归都带上 RK356x（无触摸、老 OMX） |

## 九、版本记录

| 日期 | 事件 |
|---|---|
| 2026-10-20 | 建文档；基线为 `develop@c83b6d1`（`:protocol:test` 469 项） |
| 2026-10-20 | **P5（部分）落地**：抽出 `:protocol/aoa/LinkRetry`（运行权 + 失败计数 + 退避间隔），`LinkCoordinator` 的三个原子量换成它；新增 6 项单测（总 475 项）。`P1/P2/P3/P4` 未动 |
| 2026-10-20 | **P6 收尾**：`.uav` 回放从 `HostActivity` 搬进 `aoa/run/AoaReplay`（依赖经 `Deps` 注入，`lateinit` 字段用 getter 取）。⚠️ **`HostActivity` 只从 656 降到 651 行** —— 拆出去 77 行、又写回 ~35 行 Deps，本步买到的是"调试通路可单独阅读"，不是瘦身 |
| 2026-10-20 | **P3 + P4 剩余落地**：`AoaConnectFlow`（握手 → 等重枚举 → claim，权限经 `Permission` 回调注入）从 `UsbAttachRouter.runPipeline` 搬出；`AoaLinkRun`（串行队列 + 运行权 + 队列关闭时另起线程）从 `LinkCoordinator` 的内联 `ioExecutor` 搬出。真机：快路径首帧、`force_handshake`（aoaVersion=2）全流程、连点合并（router `busy` 挡下第二次）均正常 |
| 2026-10-20 | **P1 轮询段真机验证通过**：拔线 → `detach broadcast` 后 31ms `host session stopped`；插回 → 设备在表里约 1.5s 后由 **`轮询发现设备 id=2030（第 1 次尝试）`** 接上（该路径以前只能靠 ATTACHED 广播），握手重枚举 `aoaVersion=2` → 首帧，**拔到画面恢复 6.9s**，全程零告警、HID 只重注册一次 |
| 2026-10-20 | **P4（第一步）落地**：会话装配从 `LinkCoordinator.onReady()` 搬进 `aoa/flow/AoaStreamFlow`（连接 → 通道 → Transport → HostSession），回调经 `Bridge` 翻译回调度器；`LinkCoordinator` 保留"顺序 + 状态"（**行数基本不变** —— 桥从"方法内内联对象"变成"具名字段"，本步买的是**职责边界**不是行数）。真机：快路径与 `force_handshake` 两条装配路径都正常（第二次 610ms 出首帧） |
| 2026-10-20 | **P1（轮询段）落地**：`usbPoller` 从 `Handler.postDelayed` 改协程循环（`uav-aoa-poll`，`Dispatchers.Main` + `delay`）；"预期内重开"标志的 45s 兜底从 `Runnable` 改 `Job`（`uav-expect-restart`）。`removeCallbacks` 全部退场，销毁只认 `scope.cancel()` |
| 2026-10-20 | **P2 真机验证通过**（P40UCH + HONOR MAA-AN10）：完整 AOA 重握手（`aoaVersion=2`）从 `link ready` 到首帧 **571ms**；原地重协商 `OPEN → OKAY` **6ms**，尺寸 `496x1080 → 872x1920`、码率回到 8000k 且帧继续；返回键收尾立即 `host session stopped`，**无「握手异常」**（取消不再被当成失败）。触摸/音频只验到「HID 已注册 id=1，5 点」「audio 无输入超时」，实际手感待人工确认 |
| 2026-10-20 | **P2（握手段）落地**：`HostSession` 的两个 `CountDownLatch` → `CompletableDeferred` + `withTimeoutOrNull`；`sessionFlow` / `sendOpen` / `renegotiate` 变 `suspend`，握手从"占一条 `uav-session` 线程"变成"挂起";`LinkCoordinator` 持有 `scope`，重协商从裸线程改协程。读循环仍为线程（见 §六.1） |

### P2 的具体做法（为什么不是全量协程化）

| 位置 | 之前 | 现在 |
|---|---|---|
| 等对端 `CNXN` | `CountDownLatch.await(5s)`，占住 `uav-session` 线程 | `withTimeoutOrNull(5s) { peerCnxnSignal.await() }`，不占线程 |
| 等 `OKAY` | 同上（`openAckLatch`） | 同上（每次 OPEN 换一只 `CompletableDeferred`） |
| 收尾 | `sessionThread.interrupt()` | `sessionJob.cancel()`（挂起中的等待立刻退出） |
| 原地重协商 | 裸 `Thread("uav-renegotiate")` | `scope.launch(CoroutineName("uav-renegotiate"))` |
| `uav-decode` / `uav-audio-in` | 线程 | **仍是线程**（`MediaCodec`/`AudioTrack` 阻塞写，且与读循环的时序耦合，不动） |

判定标准只有一条：**这个等待"能不能被打断"**。能（纯等待）就协程化；不能（`bulkTransfer`、
`MediaCodec.release`、`AudioTrack.write`）就留在专用线程上。


### 为什么先做这一小步

`restartQueued` / `consecutiveFailures` / `nextPollDelayMs()` 三者是**耦合**的
（成功要清零、失败要计数、计数决定轮询间隔），却各用一个原子量分散在 600 行里。
最容易出的错正是 iOS 那次栽过的："某条路径忘了复位 ⇒ 入口永久锁死"——
`restartQueued` 漏一次 `set(false)`，之后所有扫描 / 插线都会被当成重复请求丢掉，
用户看到的就是"插上没反应"。

把它们合成一个有单测的状态机之后，P3（`AoaLinkRun`）就有现成的地基：
`LinkRetry` 管"谁有权跑一次"，`AoaLinkRun` 管"跑完怎么收尾"。

