package com.uav.protocol

/**
 * 帧元数据 `ptsAndFlags` 的位定义（计划 4.4）。
 *
 * u64 小端，高位复用为标志位、低位是 PTS（微秒）：
 *
 * | 位 | 含义 |
 * | --- | --- |
 * | `1<<63` | CONFIG：payload 是编解码器配置数据（H.264 的 SPS+PPS） |
 * | `1<<62` | KEY_FRAME：关键帧（IDR） |
 * | `1<<61` | 预留：方向标志（MVP 不启用） |
 * | 其余（0..60） | 普通 PTS |
 *
 * ⚠️ 位复用必须保证「普通 PTS 不会误判成标志位」——PTS 只占低 61 位，
 * 秒级大值（如 24 小时 = 8.64e10 µs ≈ 2^36.3）远小于 2^61，安全。
 * 这一点由单测 6 覆盖。
 */
object Flags {

    const val CONFIG: Long = 1L shl 63

    const val KEY_FRAME: Long = 1L shl 62

    /** 预留：方向标志（MVP 不启用，方向由 CONFIG 带来的宽高变化隐含表达）。 */
    const val DIRECTION: Long = 1L shl 61

    /** PTS 掩码：低 61 位。 */
    const val PTS_MASK: Long = (1L shl 61) - 1L

    fun isConfig(value: Long): Boolean = (value and CONFIG) != 0L

    fun isKeyFrame(value: Long): Boolean = (value and KEY_FRAME) != 0L

    fun isDirection(value: Long): Boolean = (value and DIRECTION) != 0L

    fun pts(value: Long): Long = value and PTS_MASK

    fun pack(
        pts: Long,
        config: Boolean = false,
        keyFrame: Boolean = false,
        direction: Boolean = false,
    ): Long {
        require(pts >= 0 && pts <= PTS_MASK) { "pts out of range: $pts" }
        var v = pts
        if (config) v = v or CONFIG
        if (keyFrame) v = v or KEY_FRAME
        if (direction) v = v or DIRECTION
        return v
    }
}
