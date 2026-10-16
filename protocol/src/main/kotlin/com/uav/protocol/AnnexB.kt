package com.uav.protocol

/**
 * Annex-B 位流工具（起始码切分）。
 *
 * **它不是主路径**：计划 4.5 冻结的做法是把 CONFIG 帧 payload **整体作为 `csd-0`** 注入解码器；
 * 只有当个别机型的 `configure` 失败时，才回退到这里按起始码切分成 `csd-0`/`csd-1`。
 *
 * 放在 `:protocol` 而不是宿主模块，是因为它是**纯字节运算** ——
 * 放这里可以在 JVM 单测里直接验证，而放在 Android 模块里就只能真机试。
 */
object AnnexB {

    /**
     * 按 `00 00 00 01` 或 `00 00 01` 起始码切分 NAL 单元。
     *
     * 返回的每一段**不含起始码**（起始码本身不属于 NAL 载荷，
     * 混进去会让部分解码器把前导零当成 NAL 头的一部分）。
     *
     * 若整段数据里找不到起始码，则原样返回单元素列表（调用方按"整体"处理）。
     */
    fun split(data: ByteArray, off: Int = 0, len: Int = data.size - off): List<ByteArray> {
        if (len <= 0) return emptyList()

        val codeStarts = ArrayList<Int>()
        val payloadStarts = ArrayList<Int>()

        var i = off
        val end = off + len
        while (i + 2 < end) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                // 4 字节起始码 00 00 00 01
                if (i + 3 < end && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    codeStarts.add(i)
                    payloadStarts.add(i + 4)
                    i += 4
                    continue
                }
                // 3 字节起始码 00 00 01
                if (data[i + 2] == 1.toByte()) {
                    codeStarts.add(i)
                    payloadStarts.add(i + 3)
                    i += 3
                    continue
                }
            }
            i++
        }

        if (codeStarts.isEmpty()) return listOf(data.copyOfRange(off, end))

        val out = ArrayList<ByteArray>(codeStarts.size)
        for (idx in codeStarts.indices) {
            val from = payloadStarts[idx]
            // 每段 NAL 的结束位置 = 下一段起始码的开始位置，故不会把起始码混进载荷
            val to = if (idx + 1 < codeStarts.size) codeStarts[idx + 1] else end
            if (to > from) out.add(data.copyOfRange(from, to))
        }
        return if (out.isEmpty()) listOf(data.copyOfRange(off, end)) else out
    }

    /** 把 NAL 单元重新拼成带 4 字节起始码的 Annex-B 位流。 */
    fun join(nals: List<ByteArray>): ByteArray {
        val total = nals.sumOf { it.size + START_CODE_4.size }
        val out = ByteArray(total)
        var pos = 0
        for (nal in nals) {
            System.arraycopy(START_CODE_4, 0, out, pos, START_CODE_4.size)
            pos += START_CODE_4.size
            System.arraycopy(nal, 0, out, pos, nal.size)
            pos += nal.size
        }
        return out
    }

    val START_CODE_4: ByteArray = byteArrayOf(0, 0, 0, 1)
}
