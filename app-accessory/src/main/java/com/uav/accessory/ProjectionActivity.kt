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
import com.uav.accessory.audio.TestTonePlayer
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

    /** 探测用测试音（判定内录有没有真的录到声音），只在 debug 包可用。 */
    private val tone = TestTonePlayer()

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

        // ★ 顺序很重要：**先**把会话跑起来（打开配件 fd、能应答宿主报文），
        //   **再**依次要「通知/录音权限」→「录屏授权」。反过来会出现
        //   "授权弹窗期间链路没人应答 CNXN"的空窗。
        startSessionService()
        requestStartupPermissionsThenCapture()
        handleToneIntent(intent)

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
        // ★ 只有**系统因配件接入而重新派发 intent**（带 `EXTRA_ACCESSORY`）才代表
        //   "旧 fd 已失效"，此时才该重建传输会话。
        //
        //   这里踩过一个坑（10.13）：原先无条件 `forceRestart = true`，于是**任何**一次
        //   `am start` 到这个 Activity（例如调试用的 `--ez tone true`）都会走 onNewIntent，
        //   白白释放配件 fd —— 而重开不一定成功（实测 `openAccessory` 连试 6 次全败），
        //   结果直接落进 "手机 `config=accessory,adb` 但 fd=0" 的**死锁态**：
        //   没人再打开那个 fd，宿主读超时、会话停摆，只有**真实重枚举**能救。
        if (intent?.hasExtra(UsbManager.EXTRA_ACCESSORY) == true) {
            startSessionService(forceRestart = true)
        }
        handleToneIntent(intent)
    }

    /**
     * 调试入口：播放测试音，判定内录**到底有没有录到声音**。
     *
     * ```
     * adb shell am start -n com.uav.accessory/.ProjectionActivity --ez tone true [--ei tone_ms 30000]
     * ```
     *
     * ★ 为什么放在本 Activity（它必须是 exported，因此外部够得到）而不是服务：
     *   `CaptureService` 是 `exported=false`，`am startservice` 会被系统直接拒掉
     *   （实测 `Error: Requires permission not exported from uid ...`），根本用不了。
     *
     * ★ 而放在 Activity 之所以安全，是因为 [onNewIntent] 已经修好：只有携带
     *   `EXTRA_ACCESSORY` 的**真配件派发**才会重建传输会话，普通 `am start`
     *   不再释放配件 fd —— 探测手段绝不能扰动被测对象。
     */
    private fun handleToneIntent(intent: Intent?) {
        if (intent == null) return
        if (!DebugTools.PROBE_ENABLED) {
            if (intent.hasExtra(EXTRA_TONE)) {
                AndroidLogger.w(TAG, "release 构建：测试音入口已关闭，忽略 intent extra")
            }
            return
        }
        if (!intent.getBooleanExtra(EXTRA_TONE, false)) return
        val ms = intent.getIntExtra(EXTRA_TONE_MS, TestTonePlayer.DEFAULT_DURATION_MS)
        tone.play(ms.coerceIn(MIN_TONE_MS, MAX_TONE_MS))
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
        tone.stop()
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

    // ------------------------------------------------------------------ 启动权限 → 录屏授权

    /**
     * 先要「通知 + 录音」权限，拿到结果**之后**再去要录屏授权。
     *
     * ★ 录音权限（`RECORD_AUDIO`）是**音频内录**的前提，而且必须在
     *   「前台服务带上 microphone 类型」之前就已授予 —— Android 14 会检查
     *   前台服务类型的运行时前提，不满足会直接 `SecurityException`。
     *   所以它不能等到 `OPEN` 到达时再要（那时服务早就起来了）。
     *
     * 合成**一次** `requestPermissions` 调用而不是分两次：系统同一时刻只展示一个权限弹窗，
     * 连发两个请求时后一个可能被直接丢弃，权限就永远要不到。
     *
     * 被拒不影响投屏 —— 只是没有声音（[CaptureService] 会如实降级并记日志）。
     */
    private fun requestStartupPermissionsThenCapture() {
        val need = ArrayList<String>(2)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !isGranted(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // 内录需要 Android 10+；低版本要了也没用
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !isGranted(Manifest.permission.RECORD_AUDIO)
        ) {
            need.add(Manifest.permission.RECORD_AUDIO)
        }

        if (need.isEmpty()) {
            requestScreenCapture()
            return
        }
        AccessoryStatus.set(
            "请求权限：" + need.joinToString("、") { it.substringAfterLast('.') }
        )
        ActivityCompat.requestPermissions(this, need.toTypedArray(), REQ_STARTUP)
    }

    @Deprecated("plain Activity onRequestPermissionsResult")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_STARTUP) return

        val audioGranted = isGranted(Manifest.permission.RECORD_AUDIO)
        AndroidLogger.i(
            TAG,
            "启动权限结果：录音=" + (if (audioGranted) "已授权" else "被拒（本次将只投屏）")
        )
        if (!audioGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AccessoryStatus.set("录音权限被拒 —— 本次只投屏、不出声音")
        }
        // 权限这一关过了（或被拒）再弹录屏授权：两个系统弹窗不叠在一起
        requestScreenCapture()
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val TAG = "UAV-Accessory"
        const val REQ_PROJECTION = 2001
        const val REQ_NOTIFICATION = 2002

        /** 启动时一次性申请「通知 + 录音」；结果回来后才去要录屏授权。 */
        const val REQ_STARTUP = 2003
        const val TICK_MS = 1000L

        /** adb 调试入口：`--ez tone true [--ei tone_ms N]` 播放测试音（探测内录是否录到声音）。 */
        const val EXTRA_TONE = "tone"
        const val EXTRA_TONE_MS = "tone_ms"

        private const val MIN_TONE_MS = 200
        private const val MAX_TONE_MS = 60_000
    }
}
