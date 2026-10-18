package com.uav.protocol.aoa

/**
 * 配件身份标识 —— **两端共用的唯一权威来源**。
 *
 * 宿主在 AOA 握手时通过 `SEND_STRING`(52) 分 6 段上报这些值（计划 2.4），
 * 手机端 `res/xml/accessory_filter.xml` 必须声明**完全相同**的
 * manufacturer / model / version，否则手机不会以配件模式启动对应 App。
 *
 * ⚠️ `accessory_filter.xml` 是 XML，无法引用本 Kotlin 常量，属手工镜像。
 *    修改此处必须同步修改：
 *      `app-accessory/src/main/res/xml/accessory_filter.xml`
 */
object AccessoryIdentity {

    /** SEND_STRING index 0 —— 厂商名。 */
    const val MANUFACTURER: String = "UAV"

    /** SEND_STRING index 1 —— 型号；同时是 accessory_filter 的 model。 */
    const val MODEL: String = "usbscreen"

    /** SEND_STRING index 3 —— 版本号；同时是 accessory_filter 的 version。 */
    const val VERSION: String = "1.0"

    /** SEND_STRING index 2 —— 描述文本（不参与匹配）。 */
    const val DESCRIPTION: String = "UAV USB Accessory Screen Mirroring"

    /** SEND_STRING index 4 —— URI（不参与匹配）。 */
    const val URI: String = "https://uav.example.com/usbscreen"

    /** SEND_STRING index 5 —— 序列号（不参与匹配）。 */
    const val SERIAL: String = "UA V00000000001"

    /**
     * 按 AOA 规范顺序返回 6 段字符串（见计划 2.4）：
     * `0=manufacturer 1=model 2=description 3=version 4=URI 5=serial`
     *
     * ⚠️ 每段必须编码为 **UTF-16LE 且含结尾 `\0`**（计划红线），
     *    AOA 的 52 号控制请求对编码错误只返回失败，不报原因，极易踩坑。
     */
    val SEND_STRING_ORDER: List<String> = listOf(
        MANUFACTURER,
        MODEL,
        DESCRIPTION,
        VERSION,
        URI,
        SERIAL,
    )

    /** 单段字符串长度上限（AOA 规范限制 255 字符）。 */
    const val MAX_STRING_LENGTH: Int = 255

    /**
     * 把一段 `SEND_STRING` 文本编码为 AOA 要求的字节序列。
     *
     * ⚠️ **实测结论：UTF-8 + 单个结尾 `\0`，不是 UTF-16LE。**
     *
     * 计划初稿沿用了"必须 UTF-16LE"的说法，但**真机实测证明那是错的**：
     * 用 UTF-16LE 编码时，手机上 `dumpsys usb` 里的配件身份会变成
     * `manufacturer=U model=u version=1`（每个串只剩首字符）。
     *
     * 原因是 AOSP 框架 `UsbDeviceManager.getAccessoryStrings()` 读的是内核
     * `f_accessory` 暴露的 NUL 分隔字节串，并按 `\u0000` 切分：
     * UTF-16LE 的 `"UAV"` = `55 00 41 00 56 00 00 00`，切分后第一段就是 `"U"`。
     *
     * 后果很严重：配件身份对不上手机的 `accessory_filter.xml` → 系统**不派发**
     * `USB_ACCESSORY_ATTACHED` → 手机端 App 拿不到权限 → `openAccessory` 返回 null
     * → 宿主永远等不到 CNXN 应答。整条链路就此断掉，且报错点离根因很远。
     */
    fun encodeSendString(value: String): ByteArray {
        require(value.length <= MAX_STRING_LENGTH) {
            "SEND_STRING 超过 $MAX_STRING_LENGTH 字符: ${value.length}"
        }
        return (value + "\u0000").toByteArray(Charsets.UTF_8)
    }

    /** 按 AOA 顺序返回 6 段编码后的字节（index 0..5）。 */
    fun encodeSendStrings(): List<ByteArray> = SEND_STRING_ORDER.map { encodeSendString(it) }
}
