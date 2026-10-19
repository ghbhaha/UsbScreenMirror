package com.uav.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** `:protocol` 自带的 Base64 —— 不能依赖 `java.util.Base64`（API 26+ 才有）。 */
class B64Test {

    @Test
    fun `标准向量`() {
        assertEquals("", B64.encode(ByteArray(0)))
        assertEquals("Zg==", B64.encode("f".toByteArray()))
        assertEquals("Zm8=", B64.encode("fo".toByteArray()))
        assertEquals("Zm9v", B64.encode("foo".toByteArray()))
        assertEquals("Zm9vYg==", B64.encode("foob".toByteArray()))
        assertEquals("Zm9vYmE=", B64.encode("fooba".toByteArray()))
        assertEquals("Zm9vYmFy", B64.encode("foobar".toByteArray()))
    }

    @Test
    fun `解码标准向量`() {
        assertEquals("f", String(B64.decode("Zg==")))
        assertEquals("fo", String(B64.decode("Zm8=")))
        assertEquals("foo", String(B64.decode("Zm9v")))
        assertEquals("foobar", String(B64.decode("Zm9vYmFy")))
    }

    @Test
    fun `全字节值往返`() {
        val all = ByteArray(256) { it.toByte() }
        assertArrayEquals(all, B64.decode(B64.encode(all)))
        // 三种余数都覆盖到
        for (n in intArrayOf(1, 2, 3, 61, 62, 63, 64, 65)) {
            val d = all.copyOf(n)
            assertArrayEquals("长度 $n", d, B64.decode(B64.encode(d)))
        }
    }

    @Test
    fun `换行与缩进被忽略`() {
        // plist 的 <data> 会按 64 列换行，还常带制表符缩进
        val text = "\n\tZm9v\n\tYmFy\n"
        assertEquals("foobar", String(B64.decode(text)))
    }

    @Test
    fun `省略填充也能解`() {
        assertEquals("f", String(B64.decode("Zg")))
        assertEquals("fo", String(B64.decode("Zm8")))
    }

    @Test
    fun `非法字符要报错而不是解出错数据`() {
        val e = assertThrows(IllegalArgumentException::class.java) { B64.decode("Zm9v!!!!") }
        assertTrue(e.message!!.contains("非法字符"))
    }

    @Test
    fun `带偏移编码`() {
        val buf = "xxfoobarxx".toByteArray()
        assertEquals("Zm9vYmFy", B64.encode(buf, 2, 6))
    }
}
