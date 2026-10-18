package com.uav.protocol

/**
 * 字节序读写辅助。
 *
 * 协议里两套字节序都要用，所以都收敛到这里：
 *  - **小端**：AOA 私有流的报文头（`Connection`/`FrameCodec`）与 QuickTime 报文；
 *  - **大端**：usbmux（`MuxLink`）的 16/32 位头字段、FEED 样本里的 NAL 长度、avcC 记录。
 *
 * JVM 没有内置小端 `ByteBuffer` 语义（`ByteBuffer.order` 每次都要设，易漏），
 * 而大端若不统一，各处手写 `shl 24` 极易写错顺序且**错了不报错、只表现为静默错值**
 * （历史上 iOS 侧就各写了 7 处）。故统一收敛到此处。
 */
object Bytes {

    fun putIntLE(dst: ByteArray, off: Int, value: Int) {
        dst[off] = (value and 0xFF).toByte()
        dst[off + 1] = ((value ushr 8) and 0xFF).toByte()
        dst[off + 2] = ((value ushr 16) and 0xFF).toByte()
        dst[off + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    fun getIntLE(src: ByteArray, off: Int): Int =
        (src[off].toInt() and 0xFF) or
            ((src[off + 1].toInt() and 0xFF) shl 8) or
            ((src[off + 2].toInt() and 0xFF) shl 16) or
            ((src[off + 3].toInt() and 0xFF) shl 24)

    fun putLongLE(dst: ByteArray, off: Int, value: Long) {
        for (i in 0 until 8) {
            dst[off + i] = ((value ushr (8 * i)) and 0xFF).toByte()
        }
    }

    fun getLongLE(src: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = v or ((src[off + i].toLong() and 0xFF) shl (8 * i))
        }
        return v
    }

    fun intLE(value: Int): ByteArray = ByteArray(4).also { putIntLE(it, 0, value) }

    fun longLE(value: Long): ByteArray = ByteArray(8).also { putLongLE(it, 0, value) }

    // ---------------------------------------------------------------- 大端

    fun putShortBE(dst: ByteArray, off: Int, value: Int) {
        dst[off] = ((value ushr 8) and 0xFF).toByte()
        dst[off + 1] = (value and 0xFF).toByte()
    }

    fun getShortBE(src: ByteArray, off: Int): Int =
        ((src[off].toInt() and 0xFF) shl 8) or (src[off + 1].toInt() and 0xFF)

    fun putIntBE(dst: ByteArray, off: Int, value: Int) {
        dst[off] = ((value ushr 24) and 0xFF).toByte()
        dst[off + 1] = ((value ushr 16) and 0xFF).toByte()
        dst[off + 2] = ((value ushr 8) and 0xFF).toByte()
        dst[off + 3] = (value and 0xFF).toByte()
    }

    fun getIntBE(src: ByteArray, off: Int): Int =
        ((src[off].toInt() and 0xFF) shl 24) or
            ((src[off + 1].toInt() and 0xFF) shl 16) or
            ((src[off + 2].toInt() and 0xFF) shl 8) or
            (src[off + 3].toInt() and 0xFF)

    /** 注意：只用于**不超过 Int 范围**的 16 位字段，避免把符号位扩进去。 */
    fun shortBE(value: Int): ByteArray = ByteArray(2).also { putShortBE(it, 0, value) }

    fun intBE(value: Int): ByteArray = ByteArray(4).also { putIntBE(it, 0, value) }

    /** 按 8 字节对齐（向上取整）。 */
    fun align8(value: Int): Int = (value + 7) and 7.inv()
}
