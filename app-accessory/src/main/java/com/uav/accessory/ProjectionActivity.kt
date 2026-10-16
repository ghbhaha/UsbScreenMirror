package com.uav.accessory

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import com.uav.protocol.Protocol

/**
 * 手机端投屏入口（计划阶段 3 的界面骨架）。
 *
 * 阶段 0 只做三件事：能装、能启动、能显示状态文本。
 * 后续阶段在此挂载：
 *  - 阶段 3.2：从 intent 取 EXTRA_ACCESSORY -> openAccessory -> FdByteChannel
 *  - 阶段 3.4：MediaProjection 授权（Android 14+ 必须先 registerCallback）
 */
class ProjectionActivity : Activity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_projection)
        statusText = findViewById(R.id.status)
        Log.i(TAG, "ProjectionActivity created, protocol v${Protocol.VERSION}")
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "intent action=$action")
        if (action == "android.hardware.usb.action.USB_ACCESSORY_ATTACHED") {
            statusText.text = "accessory attached - session not implemented yet (Stage 3)"
        }
    }

    companion object {
        /** 手机侧日志 TAG，与项目约定一致。 */
        const val TAG = "UAV-Accessory"
    }
}
