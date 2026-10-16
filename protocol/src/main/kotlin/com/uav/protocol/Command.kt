package com.uav.protocol

/**
 * 命令字（计划 4.1 / 4.2）。
 *
 * 报文头里 `command` 是**小端 u32**，其字节序列恰好是 ASCII 四字符 —— 例如
 * `"CNXN"` = 字节 `43 4E 58 4E` → 小端 u32 = `0x4E584E43`。
 *
 * **裁剪掉 `AUTH` / `STLS`**：配件模式物理接触即认证，不需要 adb 的密钥协商。
 * 收到这两个命令字视为协议错误（见 [REMOVED_COMMANDS]）。
 */
enum class Command(val wire: Int) {

    /** 双向：连接握手，携带屏幕尺寸。 */
    CNXN(0x4E584E43),

    /** 宿主 → 手机：打开一条（复用多条流的）会话。 */
    OPEN(0x4E45504F),

    /** 手机 → 宿主：应答 OPEN。 */
    OKAY(0x59414B4F),

    /** 双向：关闭某条流，或整条连接。 */
    CLSE(0x45534C43),

    /** 双向：流数据（首条 = 流头，之后每条 = 一帧）。`arg0` = 流 id。 */
    WRTE(0x45545257),
    ;

    /** `magic = command xor 0xffffffff`。 */
    val magic: Int get() = wire xor -1

    companion object {

        /** ASCII 四字符 → 小端 u32，供单测/调试把字面量转成 wire 值。 */
        fun of(ascii: String): Int {
            require(ascii.length == 4) { "command ascii must be 4 chars, got '$ascii'" }
            val b = ascii.toByteArray(Charsets.US_ASCII)
            return Bytes.getIntLE(b, 0)
        }

        fun fromWire(wire: Int): Command? = entries.firstOrNull { it.wire == wire }

        /** 已裁剪的命令（AUTH / STLS）—— 收到即判协议错误（计划 4.2 要点）。 */
        val REMOVED_COMMANDS: Map<Int, String> = mapOf(
            of("AUTH") to "AUTH",
            of("STLS") to "STLS",
        )

        fun removedName(wire: Int): String? = REMOVED_COMMANDS[wire]
    }
}
