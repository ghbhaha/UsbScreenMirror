package com.uav.protocol

/**
 * 小端字节序读写辅助。
 *
 * JVM 没有内置小端 `ByteBuffer` 语义（`ByteBuffer.order` 每次都要设，易漏），
 * 协议里所有多字节字段都是小端，故统一收敛到此处，避免各处手写位移写错。
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

    /** 按 8 字节对齐（向上取整）。 */
    fun align8(value: Int): Int = (value + 7) and 7.inv()
}
