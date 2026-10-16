package com.uav.protocol

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
}
