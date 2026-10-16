package com.uav.protocol

/**
 * `OPEN` 服务串的构造与解析 —— **两端共用同一份实现**，杜绝手写字符串不一致（计划 1.7）。
 *
 * 格式（计划 4.3）：
 * ```
 * newScreen<videoId>:<audioId> max_size=<宿主算出的值>&video_bit_rate=8000000&max_fps=30&audio=true&audio_bit_rate=128000
 * ```
 * - `videoId` / `audioId` 与 [StreamId] 的固定常量一致（1 / 2），只是为了让服务串语义自解释。
 * - **payload 必须以 `\0` 结尾**（易漏点），由 [buildPayload] 统一负责。
 * - 未知 key **忽略而非报错**（向前兼容）。
 */
class Options {

    private val map = LinkedHashMap<String, String>()

    val size: Int get() = map.size

    fun isEmpty(): Boolean = map.isEmpty()

    fun set(key: String, value: Any?): Options {
        require(key.isNotEmpty() && !key.contains('=') && !key.contains('&') && !key.contains(' ')) {
            "illegal option key: '$key'"
        }
        if (value == null) {
            map.remove(key)
        } else {
            val v = value.toString()
            require(!v.contains('&') && !v.contains('\u0000')) { "illegal option value for '$key': '$v'" }
            map[key] = v
        }
        return this
    }

    fun get(key: String): String? = map[key]

    fun get(key: String, default: String): String = map[key] ?: default

    fun getInt(key: String, default: Int): Int = map[key]?.toIntOrNull() ?: default

    fun getBoolean(key: String, default: Boolean): Boolean = when (map[key]?.lowercase()) {
        null -> default
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> default
    }

    /** 供解析器使用：不做 key 合法性断言，畸形 key 由调用方过滤后丢弃。 */
    internal fun putRaw(key: String, value: String) {
        map[key] = value
    }

    /** 参数部分字符串（`k=v&k=v`），不含服务名与结尾 `\0`。 */
    fun toParamString(): String = map.entries.joinToString("&") { "${it.key}=${it.value}" }

    override fun toString(): String = toParamString()

    // ---- 类型化便捷访问（语义见计划 4.5） ----

    var maxSize: Int?
        get() = map[KEY_MAX_SIZE]?.toIntOrNull()
        set(v) {
            if (v == null) map.remove(KEY_MAX_SIZE) else map[KEY_MAX_SIZE] = v.toString()
        }

    var videoBitRate: Int
        get() = getInt(KEY_VIDEO_BIT_RATE, DEFAULT_VIDEO_BIT_RATE)
        set(v) { map[KEY_VIDEO_BIT_RATE] = v.toString() }

    var maxFps: Int
        get() = getInt(KEY_MAX_FPS, DEFAULT_MAX_FPS)
        set(v) { map[KEY_MAX_FPS] = v.toString() }

    var audio: Boolean
        get() = getBoolean(KEY_AUDIO, DEFAULT_AUDIO)
        set(v) { map[KEY_AUDIO] = v.toString() }

    /** 音频码率（bps）。只作用于音频流；视频码率另见 [videoBitRate]。 */
    var audioBitRate: Int
        get() = getInt(KEY_AUDIO_BIT_RATE, AudioSpec.DEFAULT_BIT_RATE)
        set(v) { map[KEY_AUDIO_BIT_RATE] = v.toString() }

    companion object {

        const val KEY_MAX_SIZE = "max_size"
        const val KEY_VIDEO_BIT_RATE = "video_bit_rate"
        const val KEY_MAX_FPS = "max_fps"
        const val KEY_AUDIO = "audio"
        const val KEY_AUDIO_BIT_RATE = "audio_bit_rate"

        const val SERVICE_PREFIX = "newScreen"

        /** 计划 4.5：由 6 Mbps 上调到 8 Mbps（多数情况不下采样）。 */
        const val DEFAULT_VIDEO_BIT_RATE = 8_000_000

        /** 计划 4.5：映射到 `KEY_MAX_FPS_TO_ENCODER`。 */
        const val DEFAULT_MAX_FPS = 30

        /**
         * 默认**启用**音频（10.13）。
         *
         * 早先 MVP 是 `false`（当时没有音频管线）。现在音频已经实现，默认开启才符合
         * "投屏"的预期；要 A/B 对比或排障时由宿主显式置 `false` 即可
         * （宿主端 `--ez no_audio true`）。
         *
         * ⚠️ 手机端拿不到内录权限时必须**降级为无音频**并如实记日志，
         * **不能**因为这一项为 true 就把整个会话掐掉。
         */
        const val DEFAULT_AUDIO = true

        /** I 帧间隔（秒）：由 10s 收紧到 3s，与丢帧策略联动（计划 4.5）。 */
        const val I_FRAME_INTERVAL_SECONDS = 3

        fun serviceName(videoId: Int = StreamId.VIDEO, audioId: Int = StreamId.AUDIO): String =
            "$SERVICE_PREFIX$videoId:$audioId"

        /** 默认参数集（`max_size` 由宿主算好后 [maxSize] 赋入）。 */
        fun defaultOptions(maxSize: Int? = null): Options = Options().apply {
            this.maxSize = maxSize
            videoBitRate = DEFAULT_VIDEO_BIT_RATE
            maxFps = DEFAULT_MAX_FPS
            // 显式写出来（而不是靠默认值生效）：串里看得到才好在真机日志里核对
            audio = DEFAULT_AUDIO
            audioBitRate = AudioSpec.DEFAULT_BIT_RATE
        }

        /**
         * 组装 `OPEN` 的 payload：`服务名 [参数串]` + **结尾 `\0`**。
         */
        fun buildPayload(
            options: Options = Options(),
            videoId: Int = StreamId.VIDEO,
            audioId: Int = StreamId.AUDIO,
        ): ByteArray {
            val params = options.toParamString()
            val text = if (params.isEmpty()) serviceName(videoId, audioId)
            else "${serviceName(videoId, audioId)} $params"
            val bytes = text.toByteArray(Charsets.UTF_8)
            return ByteArray(bytes.size + 1).also {
                System.arraycopy(bytes, 0, it, 0, bytes.size)
                it[bytes.size] = 0 // ★ 结尾 \0
            }
        }

        /**
         * 解析 `OPEN` 的 payload。
         *
         * 容忍结尾 `\0`（有则剥掉）。未知参数保留在 [ParsedOpen.options] 里但不会报错。
         *
         * @throws ProtocolException 服务名格式不对（不是 `newScreen<v>:<a>`）
         */
        fun parsePayload(payload: ByteArray, off: Int = 0, len: Int = payload.size - off): ParsedOpen {
            var end = off + len
            while (end > off && payload[end - 1] == 0.toByte()) end-- // 剥掉结尾 \0
            val text = String(payload, off, end - off, Charsets.UTF_8)

            val spaceAt = text.indexOf(' ')
            val service = if (spaceAt < 0) text else text.substring(0, spaceAt)
            val paramText = if (spaceAt < 0) "" else text.substring(spaceAt + 1)

            val m = SERVICE_REGEX.matchEntire(service)
                ?: throw ProtocolException("bad OPEN service string: '$service' (expected ${SERVICE_PREFIX}<v>:<a>)")
            val videoId = m.groupValues[1].toInt()
            val audioId = m.groupValues[2].toInt()

            val options = Options()
            if (paramText.isNotEmpty()) {
                for (kv in paramText.split('&')) {
                    if (kv.isEmpty()) continue
                    val eq = kv.indexOf('=')
                    if (eq <= 0) continue // 无 '=' 的片段忽略（未知/畸形参数不致命）
                    val k = kv.substring(0, eq)
                    val v = kv.substring(eq + 1)
                    // 畸形 key（含空格/空）直接丢弃，绝不因一个坏参数中断 OPEN
                    if (k.isEmpty() || k.contains(' ') || k.contains('\u0000')) continue
                    options.putRaw(k, v)
                }
            }
            return ParsedOpen(videoId, audioId, options, service)
        }

        private val SERVICE_REGEX = Regex("^${SERVICE_PREFIX}(\\d+):(\\d+)$")
    }
}

/** [Options.parsePayload] 的解析结果。 */
data class ParsedOpen(
    val videoId: Int,
    val audioId: Int,
    val options: Options,
    val service: String,
) {
    val maxSize: Int? get() = options.maxSize
    val videoBitRate: Int get() = options.videoBitRate
    val maxFps: Int get() = options.maxFps
    val audio: Boolean get() = options.audio
}
