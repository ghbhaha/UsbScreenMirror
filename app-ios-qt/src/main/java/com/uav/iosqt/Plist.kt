package com.uav.iosqt

/**
 * lockdownd 的最小 plist 请求（只发不解析：设备回复恒为 XML，直接按文本打日志）。
 *
 * 顺序对应 AMDS 的行为：`QueryType` → `StartSession`（回 `EnableSessionSSL=true`）
 * → 升 TLS → `GetValue` 验证「设备真的把我们当受信任主机」。
 */
internal object Plist {

    private fun dict(vararg kv: Pair<String, String>): ByteArray {
        val body = kv.joinToString("") { (k, v) -> "<key>$k</key><string>$v</string>" }
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0"><dict>$body</dict></plist>
        """.trimIndent()
        return xml.toByteArray(Charsets.UTF_8)
    }

    fun queryType(): ByteArray = dict(
        "Label" to "UsbScreenQt",
        "Request" to "QueryType"
    )

    fun startSession(hostId: String, systemBuid: String): ByteArray = dict(
        "Label" to "UsbScreenQt",
        "Request" to "StartSession",
        "HostID" to hostId,
        "SystemBUID" to systemBuid
    )

    /** 不带 Key = 取全部；带 Key = 取单项。 */
    fun getValue(key: String? = null): ByteArray =
        if (key == null) dict("Label" to "UsbScreenQt", "Request" to "GetValue")
        else dict("Label" to "UsbScreenQt", "Request" to "GetValue", "Key" to key)
}
