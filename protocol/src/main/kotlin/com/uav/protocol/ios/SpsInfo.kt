package com.uav.protocol.ios

/**
 * 从 SPS（H.264 `NAL type 7`）解出**真实画面尺寸**。
 *
 * ## 为什么必须自己解
 *
 * iOS 采集流的分辨率**只写在 SPS 里**。`MediaFormat` 的 `KEY_WIDTH/KEY_HEIGHT` 是**我们请求的**
 * 尺寸，不是流里的尺寸 —— 而老厂商的 legacy OMX 组件（实测 Amlogic / Android 7.1 商显）
 * 会把它**原样回显**、也不提供 `crop-*`，于是：
 *
 * ```
 * createVideoFormat(AVC, 1920, 1080)  →  outputFormat 报 1920x1080
 * 实际流（iPad Pro 11"）               →  1200x1718
 * 视图按 16:9 做信箱 ⇒ 宿主屏也是 16:9 ⇒ 铺满整屏 ⇒ 画面被横向拉宽
 * ```
 *
 * ⇒ 尺寸优先级定为：**解码器 crop-* > SPS > 我们请求的值**。
 *
 * ⚠️ SPS 里的尺寸是**宏块对齐后再裁**的：`pic_width_in_mbs_minus1` 只给 16 的倍数，
 * 真实宽高要靠 `frame_crop_*` 减回去（1200x1718 的宽度正好除尽、高度要从 1728 裁 10 行）。
 * 裁剪单位取决于色度格式：4:2:0 是 2×2，4:2:2 是 2×1，4:4:4 / 单色是 1×1（隔行再乘 2）。
 */
object SpsInfo {

    data class Size(val width: Int, val height: Int) {
        override fun toString(): String = "${width}x$height"
    }

    /** 从 Annex-B 的 csd（`00 00 00 01 67 …`，可含多个 NAL）里找第一个 SPS 并解尺寸。 */
    fun fromCsd(csd: ByteArray): Size? {
        var i = 0
        while (i + 3 < csd.size) {
            // 起始码：00 00 01（4 字节版多一个 00）
            val sc = when {
                csd[i] == 0.toByte() && csd[i + 1] == 0.toByte() && csd[i + 2] == 1.toByte() -> 3
                csd[i] == 0.toByte() && csd[i + 1] == 0.toByte() &&
                    csd[i + 2] == 0.toByte() && csd[i + 3] == 1.toByte() -> 4
                else -> 0
            }
            if (sc == 0) { i++; continue }
            val start = i + sc
            if (start >= csd.size) return null
            val type = csd[start].toInt() and 0x1F
            val next = indexOfStartCode(csd, start + 1)
            val end = if (next < 0) csd.size else next
            if (type == 7) return fromSpsNal(csd.copyOfRange(start, end))
            i = if (next < 0) csd.size else next
        }
        return null
    }

    /** @param nal 从 NAL 头字节（`0x67`）开始，不含起始码。 */
    fun fromSpsNal(nal: ByteArray): Size? = runCatching {
        if (nal.size < 4) return null
        if ((nal[0].toInt() and 0x1F) != 7) return null

        val b = Bits(nal, 1)                 // 跳过 NAL 头
        val profile = b.u(8)
        b.u(8)                               // constraint_set*/reserved
        b.u(8)                               // level_idc
        b.ue()                               // seq_parameter_set_id

        var chromaFormatIdc = 1              // 默认 4:2:0（baseline/main 不写这个字段）
        if (profile in HIGH_PROFILES) {
            chromaFormatIdc = b.ue()
            if (chromaFormatIdc == 3) b.u(1) // separate_colour_plane_flag
            b.ue()                           // bit_depth_luma_minus8
            b.ue()                           // bit_depth_chroma_minus8
            b.u(1)                           // qpprime_y_zero_transform_bypass_flag
            if (b.u(1) == 1) skipScalingMatrix(b, if (chromaFormatIdc != 3) 8 else 12)
        }

        b.ue()                               // log2_max_frame_num_minus4
        when (b.ue()) {                      // pic_order_cnt_type
            0 -> b.ue()                      // log2_max_pic_order_cnt_lsb_minus4
            1 -> {
                b.u(1)                       // delta_pic_order_always_zero_flag
                b.se()                       // offset_for_non_ref_pic
                b.se()                       // offset_for_top_to_bottom_field
                repeat(b.ue()) { b.se() }    // offset_for_ref_frame[i]
            }
        }

        b.ue()                               // max_num_ref_frames
        b.u(1)                               // gaps_in_frame_num_value_allowed_flag
        val wMbs = b.ue() + 1
        val hMapUnits = b.ue() + 1
        val frameMbsOnly = b.u(1)
        if (frameMbsOnly == 0) b.u(1)        // mb_adaptive_frame_field_flag
        b.u(1)                               // direct_8x8_inference_flag

        var cropL = 0; var cropR = 0; var cropT = 0; var cropB = 0
        if (b.u(1) == 1) {                   // frame_cropping_flag
            cropL = b.ue(); cropR = b.ue(); cropT = b.ue(); cropB = b.ue()
        }

        // 色度格式决定裁剪单位；separate_colour_plane 时按 4:0:0 处理
        val chromaArrayType = if (chromaFormatIdc == 3) 0 else chromaFormatIdc
        val unitX: Int
        val unitY: Int
        if (chromaArrayType == 0) {
            unitX = 1
            unitY = 2 - frameMbsOnly
        } else {
            val subW = if (chromaArrayType == 1 || chromaArrayType == 2) 2 else 1
            val subH = if (chromaArrayType == 1) 2 else 1
            unitX = subW
            unitY = subH * (2 - frameMbsOnly)
        }

        val width = wMbs * 16 - (cropL + cropR) * unitX
        val height = (2 - frameMbsOnly) * hMapUnits * 16 - (cropT + cropB) * unitY
        if (width <= 0 || height <= 0) null else Size(width, height)
    }.getOrNull()

    /** 100/110/122/244/44/83/86/118/128/138/139/134/135 —— 这些 profile 才带 chroma_format_idc。 */
    private val HIGH_PROFILES = setOf(
        100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135
    )

    private fun indexOfStartCode(d: ByteArray, from: Int): Int {
        var i = from
        while (i + 2 < d.size) {
            if (d[i] == 0.toByte() && d[i + 1] == 0.toByte() && d[i + 2] == 1.toByte()) return i
            i++
        }
        return -1
    }

    /**
     * 跳过 scaling list（High profile 才会出现）。
     *
     * 结构：`for i in 0..count` → `seq_scaling_list_present_flag[1]`，为 1 时跟一串
     * `delta_scale`(se) —— 注意 `nextScale == 0` 之后**不再读**，但循环仍要走完。
     */
    private fun skipScalingMatrix(b: Bits, count: Int) {
        for (i in 0 until count) {
            if (b.u(1) == 0) continue
            val size = if (i < 6) 16 else 64
            var lastScale = 8
            var nextScale = 8
            repeat(size) {
                if (nextScale != 0) {
                    nextScale = (lastScale + b.se() + 256) % 256
                }
                if (nextScale != 0) lastScale = nextScale
            }
        }
    }

    /**
     * 按位读 + **去防竞争字节**（`00 00 03` → `00 00`）。
     *
     * ⚠️ 不解 emulation prevention 就会读错：SPS 里 `00 00 03 xx` 很常见（x264 的
     * 1200x1718 样本里就有两处），一旦多读一个 0x03，后面所有 Exp-Golomb 全部错位。
     */
    private class Bits(source: ByteArray, from: Int) {
        private val data: ByteArray = stripEpb(source, from)
        private var bit = 0

        fun u(n: Int): Int {
            var v = 0
            repeat(n) {
                v = (v shl 1) or bitAt()
            }
            return v
        }

        /** 无符号 Exp-Golomb。 */
        fun ue(): Int {
            var zeros = 0
            while (bitAt() == 0) {
                zeros++
                require(zeros < 32) { "ue 前缀过长" }
            }
            var v = 1
            repeat(zeros) { v = (v shl 1) or bitAt() }
            return v - 1
        }

        /** 有符号 Exp-Golomb。 */
        fun se(): Int {
            val k = ue()
            val mag = (k + 1) / 2
            return if (k % 2 == 0) -mag else mag
        }

        private fun bitAt(): Int {
            require(bit < data.size * 8) { "SPS 位流越界" }
            val byte = data[bit ushr 3].toInt() and 0xFF
            val v = (byte ushr (7 - (bit and 7))) and 1
            bit++
            return v
        }

        private fun stripEpb(src: ByteArray, from: Int): ByteArray {
            val out = ByteArray(src.size - from)
            var o = 0
            var zeros = 0
            for (i in from until src.size) {
                val v = src[i].toInt() and 0xFF
                if (zeros >= 2 && v == 0x03) {
                    zeros = 0                    // 丢掉这个 0x03，下一个字节重新计
                    continue
                }
                out[o++] = src[i]
                zeros = if (v == 0) zeros + 1 else 0
            }
            return if (o == out.size) out else out.copyOf(o)
        }
    }
}
