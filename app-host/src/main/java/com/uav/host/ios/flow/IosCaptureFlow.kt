package com.uav.host.ios.flow

import android.content.Context
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.yield
import com.uav.host.common.DebugTools
import com.uav.host.ios.capture.Identity
import com.uav.host.ios.capture.IosFeatures
import com.uav.host.ios.capture.MuxLink
import com.uav.host.ios.capture.Pairing
import com.uav.host.ios.capture.PcmAudioPlayer
import com.uav.host.ios.capture.QtSession
import com.uav.host.ios.capture.QtTransport
import com.uav.protocol.ios.AudioParser
import com.uav.protocol.ios.DeviceVersion
import com.uav.protocol.ios.LinkLossGate
import com.uav.protocol.ios.PairStore
import com.uav.protocol.ios.QtPacket
import com.uav.protocol.ios.QtStats

/**
 * 采集流程需要宿主回答的几件事。
 *
 * 刻意只留必要的：解码/音频出口是构造参数（直接接解码器，与界面无关），
 * 界面状态（Stage/设备卡/忙碌）由 Activity 通过 [listener] 自己去看 ——
 * 这里不要让采集路径知道"哪一步该点亮哪个药丸"。
 */
internal interface IosCaptureHost {
    /** 用户已经要求停止（停止按钮 / 返回键 / 拔出广播）。读循环每轮都会问一次。 */
    fun isStopRequested(): Boolean

    /** 是否把 FEED 原始报文落盘（只能由 `adb --ez record true` 打开）。 */
    fun recordFeed(): Boolean

    /**
     * 首帧到了；返回 `true` 表示**这是本次会话的第一帧**。
     *
     * 用它而不是在 Activity 里另存一个标志：重新武装会换一个新的 [QtSession]，
     * 而"首帧只处理一次"这个语义属于会话，归采集流程统一计一次数就够。
     */
    fun onFirstFrame(): Boolean
}

/**
 * 采集路径上值得让界面知道的**阶段事实**（不是 UI 指令）。
 *
 * 之所以做成监听器而不是让采集流程直接调 `panel.render(...)`：面板写法虽已线程安全，
 * 但"某一步该显示什么文案"是界面策略，不该散落在 USB 路径上。
 */
internal fun interface IosCaptureListener {
    fun onPhase(phase: Phase)

    /** 采集路径上可观察的转折点。 */
    enum class Phase {
        /** 拿到端点、开始收流 */
        MIRRORING,

        /** lockdown 会话已经在线（保活线程在跑） */
        LOCKDOWN_UP,
    }
}

/**
 * 一次采集占着的全部 USB 资源。
 *
 * 从前这些是 `capture()` 里的一串局部量，收尾函数要接七个参数；而且 `Mux` 自己的
 * `close()` 里写着 `itf?.let { release(conn!!, it) }` —— 一个 `!!` 断言挂在错误处理路径上。
 * 聚成对象后，**收尾只需要一个参数**，每个字段的可空性都在这里一次说清。
 *
 * ⚠️ 生命周期严格是"capture 开头建 → finally 收"，不跨会话复用。
 */
private class CaptureSession(
    transport: QtTransport,
    val dev: UsbDevice,
    val cfg: UsbConfiguration,
    val itf: UsbInterface,
    val conn: UsbDeviceConnection,
    var epIn: UsbEndpoint,
    var epOut: UsbEndpoint?,
    /** 当前在跑的那个 QT 会话（重新武装会换一个新的）。 */
    var qt: QtSession? = null,
    /** lockdown 那一组资源；跳过 lockdown 时全为 null。 */
    val mux: MuxGroup = MuxGroup.empty(transport),
) {
    /** 会话整个生命周期里有效的音频播放器（有没有声音由它自己决定）。 */
    var audio: PcmAudioPlayer? = null
}

/**
 * lockdown 会话那一组东西 —— 它们总是一起建、一起收。
 *
 * 每一样都可空：「跳过 lockdown」「没有 mux 接口」「openDevice 失败」都是合法状态，
 * 后面的收尾照常跑一遍空操作，不用到处判。
 */
private class MuxGroup(
    private val transport: QtTransport,
    val conn: UsbDeviceConnection? = null,
    val link: MuxLink? = null,
    val itf: UsbInterface? = null,
    val keepAlive: AtomicBoolean = AtomicBoolean(false),
    /** 保活 Job；没建 lockdown 时为 null。 */
    var job: Job? = null,
) {
    /**
     * 是否已经完整收尾过。
     *
     * ★ 让 [close] 幂等：`rearmAndRunAgain` 切配置前必须先收一次 mux（否则
     *   `setConfiguration` 一定 false），而 `teardown` 还会再收一次。第二次若照旧执行，
     *   会多打一行 `releaseInterface(if#1) => false` —— 看着像新故障，其实只是重复收尾。
     */
    private val closed = AtomicBoolean(false)

    companion object {
        /** 「跳过 lockdown」的合法状态：每一项都空，收尾时全是空操作。 */
        fun empty(transport: QtTransport) = MuxGroup(transport)
    }

    /** 只停保活，不动连接（QT claim 失败时用：连接由调用方自己关）。 */
    fun closeKeepAliveOnly() {
        keepAlive.set(false)
        // ★ 保活循环卡在 bulkTransfer（D 状态）时，协程的 cancel 打不断 —— 它是协作式的。
        //   真正能打断 URB 的只有 Thread.interrupt，所以循环里显式开了一个裸线程承接读取，
        //   这里 interrupt 的是那个线程（见 IosCaptureFlow.pumpLoop）。
        job?.cancel()
        runCatching { link?.shutdown("qt 未起来") }
    }

    /** 完整收尾：先 FIN 再放接口再关连接（顺序见 [IosCaptureFlow.teardown]）。 */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeKeepAliveOnly()
        val c = conn ?: return
        itf?.let { runCatching { transport.release(c, it) } }
        runCatching { c.close() }
    }
}

/**
 * 「激活 → 等采集配置 → claim → lockdown → 收流」整条采集路径。
 *
 * 从 `IosActivity` 里整块搬出来的：顺序、日志、注释里的真机结论**原样保留**。
 * 搬的理由是职责切分 —— 这条路径只跟 USB 与协议打交道；判失败、说原因、复位界面
 * 全在 Activity 里。混在一起时任何一次改动都要重新确认"这行到底属于协议还是界面"。
 *
 * ## 它不负责的事
 *
 * - 停止**原因**的判断（拔线 / 链路断 / 没帧）→ 返回 [QtStats]，调用方看
 *   [QtSession.abortReason]；
 * - `activeSession` 字段 → 通过 [onSession] 交给 Activity 登记，因为停止信号要能
 *   打到**跑在读循环里的那个**会话；
 * - 收尾后的界面复位 → 调用方的 `finally`，与这里 USB 收尾的顺序无关。
 *
 * ## 挂起形状
 *
 * 整个类是 `suspend` 的：等待（重枚举、配置来回）用 `delay`，不占线程也不堵线程池。
 * 但 `bulkTransfer` / `controlTransfer` / `claimInterface` 本身的**不可中断**没变，
 * 停止仍然只认 [IosCaptureHost.isStopRequested]。
 */
internal class IosCaptureFlow(
    private val transport: QtTransport,
    private val host: IosCaptureHost,
    private val log: (String) -> Unit,
    /** 确保拿到 USB 权限（`IosActivity.ensurePermission`）。 */
    private val ensurePermission: suspend (UsbDevice) -> Boolean,
    // ---- 解码 / 音频：本类只负责把数据送出去，不关心怎么渲染 ----
    private val onCsd: (ByteArray) -> Unit,
    private val onFrame: (ByteArray) -> Unit,
    private val onAudio: (ByteArray, AudioParser.Format) -> Unit,
    private val onAudioPlayer: (PcmAudioPlayer?) -> Unit,
    /** 会话一建好就交出去登记（停止信号要打到它身上）。 */
    private val onSession: (QtSession) -> Unit,
    /** 阶段事实（拿到端点、开始收流 / lockdown 在线）。默认空实现 —— 这条路径本身不依赖界面。 */
    private val listener: IosCaptureListener = IosCaptureListener { _ -> },
) {

    private val appContext: Context = transport.appContext
    private val filesDir: File = appContext.filesDir

    /**
     * 本次会话读到的设备版本（纯诊断）。
     *
     * 落在这里而不是局部变量：`MuxGroup` 是 [capture] 里的局部量，而"要不要给用户
     * 一句版本相关的提示"要等到**收尾判失败**时才决定。
     */
    @Volatile
    var lastDeviceVersion: DeviceVersion = DeviceVersion.UNKNOWN
        private set

    /**
     * 配对自检读到的版本。
     *
     * 单独一个字段而不是并进 [lastDeviceVersion]：采集会话是在**配对之后**才建的，
     * 如果这里直接写 [lastDeviceVersion]，一次失败的会话会把自检的结论覆盖成 UNKNOWN。
     * 失败提示取两者中"更有话说"的那个（见 `IosActivity.finishRun`）。
     */
    @Volatile
    var pairingDeviceVersion: DeviceVersion = DeviceVersion.UNKNOWN

    /** 「0 字节」这类失败最该给的一句话：优先用自检读到的版本，其次才是本次会话。 */
    fun failureHint(): String? =
        pairingDeviceVersion.failureHint() ?: lastDeviceVersion.failureHint()

    /** 上一次会话是否非正常结束（拔线 / 端点坏）。重新武装前要看它。 */
    @Volatile
    /** 上一趟是不是**因为设备侧原因中断**（拔线 / 端点坏了）；调用方据此复位自动开始基线。 */
    var aborted = false
        private set

    /** 首帧已经处理过了（收起面板 / 全局状态）。一次 [capture] 里只处理一次。 */
    private var firstFrameHandled = false

    private fun handleFrame(frame: ByteArray) {
        onFrame(frame)
        // 首帧到了：收起面板把画面让出来（药丸仍显示「投屏中」）
        if (!firstFrameHandled && host.onFirstFrame()) {
            firstFrameHandled = true
            log("首帧已渲染 ⇒ 收起面板")
        }
    }

    /**
     * 跑一次采集，返回收流统计；`null` = 根本没进到收流（没设备 / 没权限 / 没起来）。
     *
     * @param maxFrames 收满即停（正常投屏是 `Int.MAX_VALUE`）
     * @param idleAbortSeconds 开头这么久一个字节都没来就提前返回，把"要不要重新武装"
     *                         的决定权交回上层
     */
    suspend fun capture(maxFrames: Int = Int.MAX_VALUE, idleAbortSeconds: Int = 12): QtStats? {
        firstFrameHandled = false
        aborted = false
        // 1) 找设备
        var dev = transport.findAppleDevice()
        if (dev == null) {
            log("未找到 Apple 设备（VID 05ac）。请确认线已连到宿主。")
            return null
        }
        log("设备 ${dev.deviceName} pid=0x${dev.productId.toString(16)}")

        // 2) 权限
        if (!ensurePermission(dev)) {
            log("未取得权限，中止")
            return null
        }

        // 3) ★ 若设备仍停在采集配置（上次会话的残留），**先复原再激活**。
        //    这就是「每次都要重启 iPad」的根因：设备一直留在配置 5，activate 就成了空操作。
        //    iPhoneMirror 每次收流结束都会做 usb_configuration_restore，所以它能反复开。
        if (dev.configurationCount >= IosFeatures.CAPTURE_CONFIG_COUNT) {
            // ★ 复用上次会话的关键：把配置从 5 切回 3，再切回 5 —— 设备侧的 QT 会话就重新武装了。
            //   实测（14:30）这一步之后 PING 又来了两次，**完全不用重启 iPad**。
            //   不用等重枚举：设备不会重枚举，配置来回一次就够（约 0.3s）。
            log("设备停在采集配置（${dev.configurationCount} 个配置 = 上次会话残留）⇒ 配置 3→5 重新武装")
            restoreToNormal(dev)
            delay(300)
            log("  已切回普通配置，准备重新进入采集配置")
        }

        // 4) 设备**不在采集态**就 activate 并等重枚举。
        //    ⚠️ 判据必须是「配置集里有几个配置」，不能是"能不能找到采集接口"：
        //    后者扫的是扁平表（所有配置的接口并集），永远能找到 ⇒ activate 从来没被真正触发过。
        if (dev.configurationCount < IosFeatures.CAPTURE_CONFIG_COUNT) {
            val conn0 = transport.open(dev) ?: return null
            try {
                if (!transport.activate(conn0)) {
                    log("activate 失败，中止")
                    return null
                }
            } finally {
                runCatching { conn0.close() }
            }
            log("已请求激活，等待设备重枚举（最长 ${ACTIVATE_TIMEOUT_S}s；实测可能延迟 2~3 分钟）…")
            dev = waitForCaptureMode(ACTIVATE_TIMEOUT_S)
            if (dev == null) {
                log("✗ ${ACTIVATE_TIMEOUT_S}s 内没有出现采集接口。设备需要物理重启（解锁亮屏）。")
                return null
            }
        } else {
            log("设备已在采集态（${dev.configurationCount} 个配置），跳过激活")
        }

        // 4) 重枚举后要重新拿权限（上面拿到的是旧 UsbDevice 对象的结论）
        if (!ensurePermission(dev)) {
            log("重枚举后未取得权限，中止")
            return null
        }

        // 5) 先把配置切到承载 0x2A 的那个（等价 Windows 的 set_configuration(5)）。
        //    只切这一次 —— 之后建 lockdown 会话、claim QT 都不再动配置。
        transport.dumpConfigs(dev)
        val cfg = transport.captureConfiguration(dev)
        if (cfg == null) {
            log("没有任何配置包含 0xFF/0x2A 采集接口，中止")
            return null
        }
        val itf = transport.captureInterfaceIn(cfg)
        if (itf == null) {
            log("配置 id=${cfg.id} 里找不到 0xFF/0x2A 接口，中止")
            return null
        }
        val conn = transport.open(dev) ?: return null
        if (!transport.setConfiguration(conn, cfg)) {
            log("切换配置失败")
            runCatching { conn.close() }
            return null
        }
        val (epIn, epOut) = transport.bulkEndpoints(itf)
        if (epIn == null) {
            log("接口里没有 bulk IN 端点")
            runCatching { transport.release(conn, itf) }
            runCatching { conn.close() }
            return null
        }

        // 6) 先建 lockdown 会话（可选）
        //    ★ 身份按设备取：配对记录是"主机 × 设备"这一对的关系，换设备各用各的
        val identity = Identity.load(appContext, log, Pairing.deviceKey(dev))
        val mux = openLockdown(dev, cfg, identity)
        val session = CaptureSession(transport, dev, cfg, itf, conn, epIn, epOut, mux = mux)

        // 7) 现在才 claim QT 并开读
        if (!transport.claim(conn, itf)) {
            log("claim QT 接口失败")
            mux.closeKeepAliveOnly()
            runCatching { conn.close() }
            return null
        }
        log("QT 接口 if#${itf.id} 已 claim；IN=0x${epIn.address.toString(16)} " +
            "OUT=${epOut?.let { "0x" + it.address.toString(16) } ?: "无"}")
        // 音频若走**另一个** bulk IN，只取第一个 IN 就会永远收不到 EAT —— 先把端点全列出来
        val eps = (0 until itf.endpointCount).map { i ->
            val e = itf.getEndpoint(i)
            "%s0x%02x/%dB".format(
                if (e.direction == UsbConstants.USB_DIR_IN) "IN " else "OUT ",
                e.address, e.maxPacketSize
            )
        }
        log("  接口端点 ${itf.endpointCount} 个：${eps.joinToString(" ")}")

        // 8) 收流：**一直投到拔线**（maxSeconds = 0）
        //
        // ★ **默认不落盘**。以前这里无条件把 FEED 原始报文写进 feed.bin，
        //   代价是 ~2 MB/s（一小时 7 GB）外加"写盘失败就把读循环打断"：
        //   `sink.write` 抛 IOException 会一路冒到读循环外面，表现成"投屏莫名断了"。
        //   现在只在 `--ez record true` 时开，而且流是可空的（QtSession 本来就接受 null）。
        val sinkStream = recordFile()?.outputStream()
        // 音频：第一包 PCM 到才真正建轨道（没声音就不占音频资源）
        val audioPlayer = PcmAudioPlayer(log)
        session.audio = audioPlayer
        onAudioPlayer(audioPlayer)
        return try {
            // 用短窗口探一下：一字节都没有 = 设备侧没武装，交回上层重新唤醒
            var st = runSession(session, sinkStream, maxFrames, idleAbortSeconds)
            // abortReason 非空 = 设备被拔 / 端点失效，此时再"重新武装"毫无意义
            if (st.bytes > 0 || host.isStopRequested() || aborted) return st
            log("${idleAbortSeconds}s 内没有任何数据 ⇒ 用配置 4→5 重新武装一次")
            st = rearmAndRunAgain(session, sinkStream, maxFrames, idleAbortSeconds) ?: st
            st
        } finally {
            teardown(session)
            // 录制流收尾。默认没开时 sinkStream 是 null，这里就是空操作。
            // ⚠️ 必须显式 close：不关的话最后几帧可能还压在 FileOutputStream 里。
            runCatching { sinkStream?.close() }
        }
    }

    // ─────────────────────────────────────────────────────────────── 会话

    /** 建一次 [QtSession] 并跑到它自己返回。 */
    private fun runSession(
        s: CaptureSession,
        sink: OutputStream?,
        maxFrames: Int,
        idleAbortSeconds: Int,
    ): QtStats {
        // 端点齐了、开始收流 —— 这是最值得让界面知道的阶段转折
        listener.onPhase(IosCaptureListener.Phase.MIRRORING)
        val session = QtSession(
            s.conn, s.epIn, s.epOut, sink, log,
            onCsd = onCsd,
            onFrame = ::handleFrame,
            onAudio = onAudio,
            requestAudio = REQUEST_AUDIO,
            // 读循环靠它判断"设备是不是已经不在设备表里了"（拔线检测的权威判据）
            linkAlive = { transport.findAppleDevice() != null },
            // ★ 把停止信号接进读循环：返回键走 onDestroy 时 activeSession 已被置 null，
            //   那边置位到不了这里（2026-10-19 实测「返回后界面没了、后台还在无限收流」）。
            stopSignal = host::isStopRequested,
            logThrottle = ::logThrottle,
        )
        s.qt = session
        onSession(session)
        // maxSeconds = 0：一直投到停止信号 / 拔线
        val st = session.run(maxSeconds = 0, maxFrames = maxFrames, idleAbortSeconds = idleAbortSeconds)
        aborted = session.abortReason != null
        return st
    }

    /**
     * 「配置 4→5 再来一次」。
     *
     * 触发条件：短窗口内**一个字节都没进来**，说明设备侧 QT 服务没被武装。
     * 这一步不动 lockdown（它还活着），只重切配置并重新 claim QT 接口。
     */
    private suspend fun rearmAndRunAgain(
        s: CaptureSession,
        sink: OutputStream?,
        maxFrames: Int,
        idleAbortSeconds: Int,
    ): QtStats? {
        // ★★ 切配置之前必须先把 mux 接口放掉。
        //
        //   内核规则：设备上只要还有一个接口被 claim 着，`setConfiguration` 就**直接返回 false**
        //   （`QtTransport.setConfiguration` 的注释、`teardown` 里「先 mux.close() 再切配置」的顺序
        //   都写着这一条）。从前这里只 release 了 QT 接口 if#2 —— 而 lockdown 的 mux 接口 if#1
        //   挂在**另一条连接**上、保活线程还在 pump，于是 `4→5` 这一刀必然失败。
        //   2026-10-20 18:10 实测：`setConfiguration(id=4) => false` / `(id=5) => false`，
        //   而同一函数收尾时（mux 已 close）`setConfiguration(id=3) => true` —— 同一条规则的两面。
        //
        //   收掉 mux 同时满足另一条红线：放弃 mux 会话必须发 TCP FIN（`close()` 内部走 shutdown），
        //   否则下一次连 VERSION 都收不到。
        //
        //   ⚠️ 重新武装后**不重建** lockdown：QT 收流本来就不依赖它（`openLockdown` 允许
        //   "跳过 lockdown，直接 claim QT"），而重建会把刚 claim 回来的接口次序再打乱一遍。
        s.mux.close()
        transport.release(s.conn, s.itf)
        val cfg4 = transport.configurationById(s.dev, 4)
        if (cfg4 != null) {
            transport.setConfiguration(s.conn, cfg4)
            delay(300)
        }
        if (!transport.setConfiguration(s.conn, s.cfg)) {
            log("  重新切回配置 ${s.cfg.id} 失败")
            return null
        }
        // 切配置会让之前的 claim 失效，必须重新 claim；claim 不上就别硬跑（否则又是一个 12s 零字节）
        if (!transport.claim(s.conn, s.itf)) {
            log("  重新武装后 claim QT 接口失败")
            return null
        }
        val (in2, out2) = transport.bulkEndpoints(s.itf)
        if (in2 == null) {
            log("  重新武装后没有 bulk IN")
            return null
        }
        s.epIn = in2
        s.epOut = out2
        return runSession(s, sink, maxFrames, idleAbortSeconds)
    }

    // ────────────────────────────────────────────────────────────── lockdown

    private fun openLockdown(dev: UsbDevice, cfg: UsbConfiguration, identity: Identity?): MuxGroup {
        val none = MuxGroup.empty(transport)
        if (!USE_LOCKDOWN) {
            log("★ 按 iPhoneMirror 的做法跳过 lockdown，直接 claim QT")
            return none
        }
        val itfMux = transport.muxInterfaceIn(cfg)
        if (itfMux == null) {
            log("⚠ 配置 id=${cfg.id} 里没有 usbmux 接口（0xFF/0xFE/2），跳过 lockdown 会话")
            return none
        }
        val cm = transport.open(dev)
        if (cm == null) {
            log("⚠ mux 连接 openDevice 失败")
            return none
        }
        if (!transport.claim(cm, itfMux)) {
            log("⚠ claim usbmux 接口失败")
            runCatching { cm.close() }
            return none
        }
        val (mi, mo) = transport.bulkEndpoints(itfMux)
        if (mi == null || mo == null) {
            log("⚠ usbmux 接口端点不全")
            runCatching { transport.release(cm, itfMux) }
            runCatching { cm.close() }
            return none
        }
        log("建立 lockdown 会话（if#${itfMux.id}）…")
        val link = MuxLink(cm, mi, mo, identity, log)
        if (!link.open(IosFeatures.TLS_WAIT_MS)) {
            log("✗ lockdown 会话未建立：${link.failed}")
            runCatching { transport.release(cm, itfMux) }
            runCatching { cm.close() }
            return none
        }
        log("★★ lockdown 会话在线（TLS=${link.tlsReady} 信任=${link.trusted}），开始保活")
        listener.onPhase(IosCaptureListener.Phase.LOCKDOWN_UP)
        if (link.deviceVersion != DeviceVersion.UNKNOWN) lastDeviceVersion = link.deviceVersion
        // ★ 归属校验：「暂时接管」的旧版记录要靠**真的信任成功**才能转正。
        //   TLS 窗口被缩短后 `trusted` 恒为 false —— 这时**不能** dropProvisional，
        //   否则会把刚迁移过来的记录删掉。宁可让它停在"暂管"。
        val devKey = Pairing.deviceKey(dev)
        if (link.trusted) {
            PairStore.confirm(filesDir, devKey, log)
            // 顺手补记版本（纯诊断；变化时才写盘，见 PairStore.recordVersion）
            PairStore.recordVersion(filesDir, devKey, link.deviceVersion)
        }
        val mux = MuxGroup(transport, cm, link, itfMux, AtomicBoolean(true))
        mux.job = pumpLoop(link, mux.keepAlive)
        return mux
    }

    /**
     * lockdown 保活：一直 pump，直到被停或链路断了。
     *
     * ## 为什么循环还是裸线程 + 中断
     *
     * `MuxLink.pumpOnce` 里的 `bulkTransfer` 卡在 `usb_start_wait_urb` 时是 **D 状态**，
     * 协程取消（协作式）打不断它。收尾时只有 `Thread.interrupt()` 才能把 URB 打断、
     * 让 `pumpOnce` 立刻返回 —— 这是本仓库最硬的一条边界，不能改成纯协程。
     *
     * 所以：协程只做**调度与归属**（`isActive` 决定下一轮、Job 决定取消），
     * 真正发起阻塞调用的仍是一条具名线程，`closeKeepAliveOnly` 通过 `job.cancel()` →
     * finally 里的 `interrupt()` 完成打断。
     *
     * ★ 循环**不卡死**：`keepAlive` 是 `AtomicBoolean`，从主线程被置 false 时循环看不到；
     *   所以最后一轮 `pumpOnce(200)` 之后必须能自己醒过来 —— 收尾路径一律走
     *   `cancel()` → finally 的 `interrupt()`，而循环体每轮都有一次 `yield()` 作为取消点。
     *
     * ★ 连续失败也要能退出：端点真的死了（拔线 / USB 半释放）时会一直返回负值。
     *   `pumpOnce` 已保证每次迭代至少 200ms，但没有上限就等于"永远重试"。
     */
    private fun pumpLoop(link: MuxLink, keepAlive: AtomicBoolean): Job =
        CoroutineScope(Dispatchers.IO).launch {
            val gate = LinkLossGate()
            val worker = Thread.currentThread()
            try {
                while (isActive && keepAlive.get() && link.failed == null) {
                    val n = runCatching { link.pumpOnce(200) }.getOrDefault(-1)
                    when {
                        n > 0 -> gate.onData()
                        // ★★ mux 上「没数据」是常态，不是故障。
                        //   `pumpOnce` 已经保证每轮至少睡满 200ms，所以"读满窗口"= 设备安静；
                        //   以前这里不分青红皂白记一次快速失败，8 轮（1.6 秒）后
                        //   `LinkLossGate` 就判 ENDPOINT_DEAD 把会话杀掉 —— 2026-10-20 实测。
                        link.lastPumpTimedOut -> gate.onTimeout()
                        else -> {
                            val cause = gate.onFastFailure(
                                devicePresent = transport.findAppleDevice() != null
                            )
                            if (cause != null) {
                                log("✗ lockdown 保活中止：$cause（保活循环退出）")
                                break
                            }
                        }
                    }
                    // 交还调度点：让 cancel() 有机会插进来
                    yield()
                }
                // 因为 link.failed 而退出时从来没说过话，排查时容易误判成"循环还在"
                link.failed?.let { log("✗ lockdown 保活结束：$it") }
            } finally {
                // ★ 取消 / 正常退出都要打断：否则线程还卡在 URB 上，USB 得不到释放
                worker.interrupt()
            }
        }

    // ─────────────────────────────────────────────────────────────── 收尾

    /**
     * USB 侧的收尾。**顺序有依赖，不能重排**：
     *
     * 1. 停保活循环（先让它别再往 mux 上写，并且打断卡住的 URB）；
     * 2. 发 `HPA0`/`HPD0` —— **必须在 release 接口之前**，否则 OUT 端点不可写，
     *    `bulkTransfer` 静默返回 -1，等于什么都没发；
     * 3. 给 mux 那条 TCP 发 FIN —— 不发的话设备侧一直 ESTABLISHED，下次插上连 VERSION 都收不到；
     * 4. 放接口、还配置（不还配置 ⇒ 下次 activate 变空操作，只能重启 iPad）；
     * 5. 关连接、关音频。
     */
    private fun teardown(s: CaptureSession) {
        s.mux.closeKeepAliveOnly()
        // ★★ 先发停止序列（HPA0/HPD0），**再**放接口 ——
        //   顺序反了等于没发：接口一 release，OUT 端点就不可写，bulkTransfer 静默返回 -1。
        //   依据 iPhoneMirror PROTOCOL.md §4「停止顺序是 HPA0、HPD0、…然后释放接口」。
        runCatching {
            s.qt?.sendStopSequence(
                displayWidth = QtPacket.DEFAULT_DISPLAY_WIDTH,
                displayHeight = QtPacket.DEFAULT_DISPLAY_HEIGHT,
            )
        }
        s.mux.close()
        runCatching { transport.release(s.conn, s.itf) }
        runCatching {
            transport.normalConfiguration(s.dev, s.cfg.id)?.let { transport.setConfiguration(s.conn, it) }
        }
        s.audio?.let { runCatching { it.stop() } }
        onAudioPlayer(null)
        runCatching { s.conn.close() }
        s.qt = null
        log("收尾完成：配置已还回普通态，可直接再次开始（无需重启 iPad）")
    }

    // ─────────────────────────────────────────────────────────── 设备状态

    /** 把设备从采集配置切回普通配置（收尾用）。 */
    private fun restoreToNormal(dev: UsbDevice): Boolean {
        val conn = transport.open(dev)
        if (conn == null) {
            log("  复原失败：openDevice 返回 null")
            return false
        }
        return try {
            val target = transport.normalConfiguration(dev, IosFeatures.CAPTURE_CONFIG_COUNT)
            if (target == null) {
                log("  复原失败：找不到普通配置")
                false
            } else {
                transport.setConfiguration(conn, target)
            }
        } finally {
            runCatching { conn.close() }
        }
    }

    /** 轮询直到设备上出现 0xFF/0x2A 采集接口，返回新的 UsbDevice。 */
    private suspend fun waitForCaptureMode(maxSeconds: Int): UsbDevice? {
        val deadline = System.currentTimeMillis() + maxSeconds * 1000L
        var lastTick = 0L
        while (!host.isStopRequested() && System.currentTimeMillis() < deadline) {
            val d = transport.findAppleDevice()
            if (d != null) {
                // 同样不能用扁平表：配置集从 4 涨到 5 才代表设备真的切进了采集模式
                if (d.configurationCount >= IosFeatures.CAPTURE_CONFIG_COUNT) {
                    log("★ 设备已进入采集态：${d.deviceName}（${d.configurationCount} 个配置）")
                    return d
                }
                val now = System.currentTimeMillis()
                if (now - lastTick > 5000) {
                    lastTick = now
                    log("  等待中… 当前 ${d.deviceName}")
                }
            }
            delay(300)
        }
        return null
    }

    /** FEED 落盘文件；没开录制就是 `null`。 */
    private fun recordFile(): File? {
        if (!host.recordFeed() || !DebugTools.ENABLED) return null
        return File(appContext.getExternalFilesDir(null) ?: filesDir, "feed.bin").also {
            log("★ 录制已开启（adb）：${it.absolutePath}")
        }
    }

    /**
     * 高频日志的去重器：同一个 key 每 [throttleMs] 只放行一行。
     *
     * `QtSession` 里的 SKEW 应答、渲染计数每秒好几条，面板只有 50 条缓冲 ——
     * 以前是靠 `IosDecoderHost.log` 按前缀硬编码丢弃，等于"为了界面好看把排障信息删了"。
     * 现在由**产生日志的地方**自己说"这条是高频道、我要不要限流"，界面层不再认识业务前缀。
     */
    private val throttle = HashMap<String, Long>()

    private fun logThrottle(key: String, msg: String, everyMs: Long = 2000L) {
        val now = System.currentTimeMillis()
        val last = throttle[key] ?: Long.MIN_VALUE
        if (now - last < everyMs) return
        throttle[key] = now
        log(msg)
    }

    private companion object {
        /**
         * 是否先建 lockdown 受信任会话再 claim QT。
         *
         * ★ iPhoneMirror（有线模式，能同时出音视频）**完全不做 lockdown**：
         *   它的日志是 `libusb0_quicktime_open claim=success configuration=5 interface=2`
         *   紧接 `async_sprp`，中间没有任何 mux/TLS 步骤。
         *   而我们从 2026-10-18 起一直坚持"先 lockdown 再 claim"（当时的 A/B 说没有它会静默）。
         *   ⇒ 已验证：关掉之后设备**只回一次 PING 就沉默**（零 CWPA/零 FEED）。
         *   原因是它借用了 PC 上常驻的 **Apple Mobile Device Service**（它自己的
         *   DRIVER_DEPENDENCIES.md 把 AMDS 列为必需依赖）—— 那份受信任会话由系统提供，
         *   安卓上没有，所以我们必须自己建。**这个开关必须保持 true。**
         */
        const val USE_LOCKDOWN = true

        /** 等设备重枚举的上限。实测延迟 2~3 分钟，所以给 300s。 */
        const val ACTIVATE_TIMEOUT_S = 300

        /**
         * 是否向设备要音频（`HPD1` 里的音频标志）。
         *
         * 实测（2026-10-18 17:03 / 17:15，iPad 正放音频）：`HPD1`×2+clock+`HPA1` 与 AFMT
         * 应答全齐、写路径也改成独立线程了，**仍然零 EAT** ⇒ 这台 iPad8,9 / iOS 27.2
         * 就是不推 QT 音频帧。保留 true 只是为了让协议侧继续按"有音频"走，
         * 换一台设备时能立刻验证；真要关的时候会同时失去 HPD1 音频标志。
         */
        const val REQUEST_AUDIO = true
    }
}

