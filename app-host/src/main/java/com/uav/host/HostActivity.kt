package com.uav.host

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import com.uav.protocol.Protocol

/**
 * 宿主投屏页（计划 2.12 的界面骨架）。
 *
 * 阶段 0 只做三件事：能装、能启动、能显示状态文本与 SurfaceView 占位。
 * 后续阶段在此挂载：
 *  - 阶段 2：USB attach/权限/握手状态机 -> 更新 [statusText]
 *  - 阶段 4：VideoDecoder 直渲到 [surfaceView] 持有的 Surface
 */
class HostActivity : Activity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_host)
        statusText = findViewById(R.id.status)
        Log.i(TAG, "HostActivity created, protocol v${Protocol.VERSION}")
        handleIntent(intent)
    }

    /** singleTask 模式下再次被 attach intent 拉起时走这里（计划 2.1）。 */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "intent action=$action")
        if (action == "android.hardware.usb.action.USB_DEVICE_ATTACHED") {
            statusText.text = "USB attached - handshake not implemented yet (Stage 2)"
        }
    }

    companion object {
        /** 宿主侧日志 TAG，与项目约定一致。 */
        const val TAG = "UAV-Host"
    }
}
