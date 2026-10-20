package com.uav.host.aoa.run

import android.view.Surface
import com.uav.host.aoa.debug.FileByteChannel
import com.uav.host.aoa.session.HostSession
import com.uav.protocol.Logger
import com.uav.protocol.aoa.Transport
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * `.uav` 抓流回放（**只用于 debug 包**，`DebugTools.ENABLED` 门控）。
 *
 * 从 `HostActivity` 搬出来的一份独立通路：它不碰 USB、不碰 AOA 握手，
 * 只是把一个文件当成"手机在推流"。放在 Activity 里时有 70+ 行与生命周期、
 * Intent、渲染混在一起，而它其实是**排障工具**，与产品路径没有交集。
 *
 * ## 两条不能改的语义
 *
 * 1. **不回环**：抓流里的"流头"只在该流首条 WRTE 有效，循环第二遍时这 12B 会被当成
 *    一帧解析而报错。要看第二遍就再触发一次回放。
 * 2. **回调只接进度**：回放模式下没有链路可言，报"已断开 / 出错"会让状态机说谎；
 *    画面与统计照常由 [HostSession] 自己驱动。
 */
internal class AoaReplay(private val deps: Deps) {

    /** 宿主侧依赖。做成接口是为了让本类不持有 Activity（便于以后搬进 `:protocol` 的测试范围）。 */
    internal interface Deps {
        val scope: CoroutineScope
        val logger: Logger
        val appVersion: Int

        /** 面板尺寸 `[w, h]`（`DisplaySize.of(context)`）。 */
        fun panelSize(): IntArray

        fun surface(): Surface?

        /** 把回放会话挂进调度器（它参与"有没有活动会话"的判断）。 */
        fun adopt(session: HostSession)

        /** 断开当前真实链路：回放与 USB 互斥。 */
        fun stopCurrentSession()

        fun onProgress(text: String)

        fun onStarted(label: String)

        fun onError(message: String, cause: Throwable?)
    }

    private var channel: FileByteChannel? = null

    fun start(file: File) {
        deps.stopCurrentSession()

        // 配速默认开启（按帧 PTS 还原真实速度），否则会把深度 4 的队列冲爆。
        val c = FileByteChannel(file, deps.logger, paceByPts = true)
        channel = c
        val transport = Transport(c, deps.logger, TAG)
        val size = deps.panelSize()

        val session = HostSession(
            transport = transport,
            localWidth = size[0],
            localHeight = size[1],
            surfaceProvider = deps::surface,
            logger = deps.logger,
            replay = true,
            appVersion = deps.appVersion,
            listener = listener,
            // 回放也要有个作用域：握手流程在 replay 下会直接返回，但构造参数是必填的
            scope = deps.scope,
        )
        deps.adopt(session)
        session.start()
        deps.onStarted("回放：${file.name}（${c.totalBytes} 字节）")
    }

    /** 只关文件通道；会话本身由调度器统一收尾。 */
    fun close() {
        channel?.close()
        channel = null
    }

    /**
     * ⚠️ **只接进度**：见类注释第 2 条。
     */
    private val listener = object : HostSession.Listener {
        override fun onStatus(text: String) = deps.onProgress(text)

        override fun onError(message: String, cause: Throwable?) = deps.onError(message, cause)
    }

    private companion object {
        const val TAG = "UAV-Host"
    }
}
