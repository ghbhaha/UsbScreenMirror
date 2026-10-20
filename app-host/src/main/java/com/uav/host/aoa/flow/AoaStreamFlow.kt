package com.uav.host.aoa.flow

import android.hardware.usb.UsbDeviceConnection
import android.view.Surface
import com.uav.host.aoa.capture.session.HostSession
import com.uav.host.aoa.capture.usb.BulkByteChannel
import com.uav.host.aoa.capture.usb.BulkPair
import com.uav.protocol.BufferedByteChannel
import com.uav.protocol.ByteChannel
import com.uav.protocol.Logger
import com.uav.protocol.aoa.Transport
import kotlinx.coroutines.CoroutineScope

/**
 * 「用一条**已 claim 好的** USB 连接建出会话」。
 *
 * 从 `LinkCoordinator.onReady()` 整块搬出来的：那段 ~100 行里混着三件事 ——
 * 收尾旧会话、组装字节通道、把 `HostSession.Listener` 的每个回调翻译成
 * 调度器自己的状态变化。第三件最容易出错（它在会话线程上跑），所以先切干净：
 *
 * - **这里**只做"连接 → 通道 → Transport → HostSession"这条纯装配，以及回调**转发**；
 * - **怎么解释回调**（清哪个标志、要不要排队重连、要不要自愈）留在
 *   [com.uav.host.aoa.flow.LinkCoordinator] —— 那是状态机的事。
 *
 * ## 两条顺序要求（迁移时逐字保留，别再动）

 * 1. `drainStaleInput()` 必须在**新会话开始之前**：上一次会话残留在 FIFO 里的字节
 *    会被当成流头解析，静默配错解码器（实测踩到过）。
 * 2. `BufferedByteChannel` 的 `readChunkSize` 必须 ≥ 端点包长（512B）：协议层哪怕只要
 *    24B 报文头，对内核也永远整块读。直接把小请求透传给内核会触发 MTK 主机栈的
 *    usercopy BUG → 宿主重启（10.16 kernel panic，kmalloc-128 被要求拷 391B）。
 */
internal class AoaStreamFlow(
    private val logger: Logger,
    private val scope: CoroutineScope,
    /** 本端 `versionCode`，握手时通过 `CNXN.arg1` 报给配件端。 */
    private val appVersion: Int,
    private val surfaceProvider: () -> Surface?,
    /** 宿主视频显示区（1:1 像素映射的输入）。 */
    private val displayArea: () -> IntArray?,
    private val oneToOne: () -> Boolean,
    private val audioEnabled: () -> Boolean,
    private val audioDelayMs: () -> Int,
    /** 面板尺寸 `[w, h]`（`DisplaySize.of(context)`）。 */
    private val panelSize: () -> IntArray,
    private val bridge: Bridge,
) {

    /** 会话期间的可观察事实。**只报事实**，怎么落到状态/界面由调度器决定。 */
    internal interface Bridge {
        fun onStatus(text: String)
        fun onVideoSize(width: Int, height: Int)
        fun onFirstFrame()
        fun onClosed(reason: String)
        fun onError(message: String, cause: Throwable?)
        fun onPeerAppMissing()
    }

    /** 建好的两样东西：会话本身 + 它底下的**原始**字节通道（调度器留着做统计 / 收尾）。 */
    internal class Opened(val session: HostSession, val raw: BulkByteChannel)

    /**
     * 在**已经 claim 好的连接**上建会话。调用方负责 `session.start()`。
     *
     * ⚠️ 本函数会阻塞（`drainStaleInput` 要读 USB），只能在 router 的后台线程调用。
     */
    fun open(connection: UsbDeviceConnection, pair: BulkPair): Opened {
        val raw = BulkByteChannel(connection, pair, logger)
        // ★ 先把上一次会话残留在 FIFO 里的字节丢掉，再开新会话。
        //   否则第一个 payload 会被当成流头解析，静默配错解码器（实测踩到过）。
        raw.drainStaleInput()
        // ★ 10.16 内核 panic 防线：协议层无论要多少（哪怕 24B 报文头），对内核的 bulkTransfer
        //   永远整块（readChunkSize=16KB ≥ 端点包长 512B）发起，多到货的进残留缓冲续读。
        //   直接把协议层的小请求透传给内核，MTK 主机栈不截断 actual_length → usercopy BUG → 重启
        //   （实测：kmalloc-128 被要求拷 391B，Comm: uav-reader）。
        val channel: ByteChannel = BufferedByteChannel(raw, pair.readChunkSize)
        val transport = Transport(channel, logger, TAG)

        val size = panelSize()
        val session = HostSession(
            transport = transport,
            localWidth = size[0],
            localHeight = size[1],
            appVersion = appVersion,
            surfaceProvider = surfaceProvider,
            displayArea = displayArea,
            oneToOne = oneToOne,
            audioEnabled = audioEnabled(),
            audioDelayMs = audioDelayMs(),
            scope = scope,
            logger = logger,
            listener = object : HostSession.Listener {
                override fun onStatus(text: String) = bridge.onStatus(text)

                override fun onVideoSize(width: Int, height: Int) =
                    bridge.onVideoSize(width, height)

                override fun onFirstFrame() = bridge.onFirstFrame()

                override fun onClosed(reason: String) = bridge.onClosed(reason)

                override fun onError(message: String, cause: Throwable?) =
                    bridge.onError(message, cause)

                override fun onPeerAppMissing() = bridge.onPeerAppMissing()
            },
        )
        return Opened(session, raw)
    }

    private companion object {
        const val TAG = "UAV-Host"
    }
}
