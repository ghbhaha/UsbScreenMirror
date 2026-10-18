package com.uav.protocol

import java.io.Closeable

/**
 * 平台层字节搬运 —— **整个协议库唯一需要两端各自实现的接口**（计划 1.1）。
 *
 * 两端的字节语义并不对称，但不对称性被这一个接口吃掉：
 *
 * | 侧 | 底层 | 实现要处理的事 |
 * | --- | --- | --- |
 * | 手机（accessory） | accessory fd 的流 | 包 `ParcelFileDescriptor`；`close()` 用于**打断阻塞写** |
 * | 宿主（host） | `UsbDeviceConnection.bulkTransfer` | 包语义需自己拼成流；ZLP 会让读"以 0 字节完成" |
 * | 离线回放 | 本地 `.uav` 文件 | 纯流，读到末尾返回 -1 |
 *
 * **契约**：
 *  - `read` **允许短读、允许返回 0**（0 通常表示"本次超时/ZLP，非致命"，上层会重试）。
 *  - `read` 返回 `-1` 表示对端已关闭（EOF）。
 *  - `write` 返回时表示 `len` 字节已全部写出，否则抛 `IOException`。
 *  - `close()` 必须能**中断**正在阻塞的 `read`/`write`（方式不限，抛异常即可）。
 */
interface ByteChannel : Closeable {

    /**
     * 读取至多 [len] 字节。
     *
     * @return 实际读到的字节数，`0..len`；**-1 表示 EOF / 对端关闭**。
     *         允许返回 0（超时或 ZLP），这是**非致命**的，由 [com.uav.protocol.aoa.Transport] 决定重试策略。
     */
    fun read(dst: ByteArray, off: Int, len: Int): Int

    /**
     * 写出 [len] 字节。返回即表示全部写出。
     *
     * @throws java.io.IOException 超时 / 断开 / 已被 [close]
     */
    fun write(src: ByteArray, off: Int, len: Int)

    /** 用于日志的可读名称，例如 `usb:0x81/0x02`、`accessory-fd`、`file:dump.uav`。 */
    val label: String

    override fun close()
}
