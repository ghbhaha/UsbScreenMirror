package com.uav.accessory

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.uav.protocol.i
import com.uav.protocol.w

/**
 * 手机端入口（计划 3.1 / 3.4）。
 *
 * 本 Activity 只做两件事：
 *  1. 拿到被系统派发过来的 [UsbAccessory]（`USB_ACCESSORY_ATTACHED`）；
 *  2. 弹出**录屏授权**（必须由 Activity 发起，Service 做不了），
 *     然后把授权结果与配件一起交给 [CaptureService] —— 真正的工作都在前台服务里。
 *
 * ⚠️ 计划 0.1 的期望管理：**Android 14+ 每次投屏都要点一次授权**，
 *    系统取消了 MediaProjection 的"记住选择"，这是无法绕过的系统限制。
 */
class ProjectionActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var statsText: TextView

    private var accessory: UsbAccessory? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            statusText.text = AccessoryStatus.text
            statsText.text = AccessoryStatus.stats
            mainHandler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_projection)

        statusText = findViewById(R.id.status)
        statsText = findViewById(R.id.stats)

        findViewById<Button>(R.id.btn_grant).setOnClickListener { requestScreenCapture() }
        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            ContextCompat.startForegroundService(this, CaptureService.stopIntent(this))
            AccessoryStatus.set("已请求停止")
        }

        accessory = resolveAccessory(intent)
        AndroidLogger.i(TAG, "ProjectionActivity created, accessory=${accessory?.model}")

        ensureNotificationPermission()

        // ★ 顺序很重要：**先**把会话跑起来（打开配件 fd、能应答宿主报文），
        //   **再**弹录屏授权。反过来会出现"授权弹窗期间链路没人应答 CNXN"的空窗。
        startSessionService()
        requestScreenCapture()

        mainHandler.post(ticker)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val acc = resolveAccessory(intent)
        if (acc != null) {
            accessory = acc
            AndroidLogger.i(TAG, "new accessory: ${acc.model}/${acc.manufacturer}")
        }
        // ★ 系统重新派发配件 intent = 配件重新接入 = 旧 fd 已失效，
        //   必须让服务重建传输会话（forceRestart），否则会一直握着一个死 fd
        //   表现为宿主侧"等待手机 CNXN 超时"。
        startSessionService(forceRestart = true)
    }

    /** 阶段一：拉起采集服务，先建立链路（不需要录屏授权）。 */
    private fun startSessionService(forceRestart: Boolean = false) {
        if (accessory == null) {
            AndroidLogger.w(TAG, "暂无配件，跳过会话服务启动")
            return
        }
        ContextCompat.startForegroundService(
            this,
            CaptureService.startIntent(this, accessory, forceRestart),
        )
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(ticker)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 配件

    private fun resolveAccessory(intent: Intent?): UsbAccessory? {
        intent?.let {
            val acc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
            } else {
                @Suppress("DEPRECATION")
                it.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
            }
            if (acc != null) return acc
        }
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        return usbManager.accessoryList?.firstOrNull()
    }

    // ------------------------------------------------------------------ 录屏授权

    private fun requestScreenCapture() {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        AccessoryStatus.set("请求录屏授权…")
        @Suppress("DEPRECATION")
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    @Deprecated("plain Activity onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJECTION) return

        if (resultCode != RESULT_OK || data == null) {
            AccessoryStatus.set("录屏授权被拒绝 —— 无法投屏")
            AndroidLogger.w(TAG, "MediaProjection consent denied (resultCode=$resultCode)")
            // 如实告知宿主，别互相干等（计划 4.4）
            val s = CaptureService.currentSession()
            s?.failAndClose("用户拒绝了录屏授权")
            return
        }

        // 阶段二：把授权结果交给已经在跑的采集服务
        ContextCompat.startForegroundService(
            this,
            CaptureService.projectionIntent(this, resultCode, data),
        )
        AccessoryStatus.set("录屏授权已交给采集服务")
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQ_NOTIFICATION,
        )
    }

    companion object {
        const val TAG = "UAV-Accessory"
        const val REQ_PROJECTION = 2001
        const val REQ_NOTIFICATION = 2002
        const val TICK_MS = 1000L
    }
}
