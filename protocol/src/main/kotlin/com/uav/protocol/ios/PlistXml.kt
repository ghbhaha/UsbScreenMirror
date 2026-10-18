package com.uav.protocol.ios

/**
 * lockdown 用的最小 plist 值模型 + XML 编解码。
 *
 * ## 为什么要自己写
 *
 * 配对请求里 `HostCertificate` / `RootCertificate` / `DeviceCertificate` 的 **plist 类型是 data**，
 * 而 data 的载荷本身又是 **PEM 文本**（`-----BEGIN CERTIFICATE-----…`）。只支持 `<string>` 的
 * 简易构造器表达不了这一层，所以这里补一个真正的（很小的）值模型。
 *
 * ## 解析侧为什么必要
 *
 * `GetValue(DevicePublicKey)` 与 `GetValue` 全量都要读回 `<data>` / 嵌套 `<dict>`；
 * 早期用正则硬凑文本，一旦设备把 `Value` 包成 dict 就读不出来。
 */
sealed class PValue {

    class Str(val v: String) : PValue()
    class Data(val v: ByteArray) : PValue()
    class Bool(val v: Boolean) : PValue()
    class Int(val v: Long) : PValue()
    class Dict(val entries: List<Pair<String, PValue>>) : PValue()
    class Array(val items: List<PValue>) : PValue()

    fun str(key: String): String? = (this as? Dict)?.entries?.firstOrNull { it.first == key }?.second
        .let { (it as? Str)?.v }

    fun data(key: String): ByteArray? = (this as? Dict)?.entries?.firstOrNull { it.first == key }?.second
        .let { (it as? Data)?.v }

    fun dict(key: String): PValue? = (this as? Dict)?.entries?.firstOrNull { it.first == key }?.second

    fun bool(key: String): Boolean? = (this as? Dict)?.entries?.firstOrNull { it.first == key }?.second
        .let { (it as? Bool)?.v }

    /**
     * 取"二进制或文本都算"的字段。
     *
     * 设备对同一语义给过两种形态：`DevicePublicKey` 有时是 `<data>`（DER/PEM 字节），
     * 有时是 `<string>`（PEM 文本）。调用方不该为此分叉。
     */
    fun blob(key: String): ByteArray? =
        when (val v = (this as? Dict)?.entries?.firstOrNull { it.first == key }?.second) {
            is Data -> v.v
            is Str -> v.v.toByteArray(Charsets.UTF_8)
            else -> null
        }

    companion object {
        fun dict(vararg kv: Pair<String, PValue?>): PValue =
            Dict(kv.mapNotNull { (k, v) -> v?.let { k to it } })

        fun dictOf(entries: List<Pair<String, PValue>>): PValue = Dict(entries)
    }
}

object PlistXml {

    private const val HEADER =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" " +
            "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" +
            "<plist version=\"1.0\">"

    // ---------------------------------------------------------------- 编码

    fun encode(root: PValue): ByteArray {
        val sb = StringBuilder(1024)
        sb.append(HEADER)
        write(sb, root)
        sb.append("</plist>\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun write(sb: StringBuilder, v: PValue) {
        when (v) {
            is PValue.Str -> sb.append("<string>").append(escape(v.v)).append("</string>")
            is PValue.Data -> {
                // 单行 base64：设备不关心换行，单行更好对照抓包
                sb.append("<data>")
                    .append(java.util.Base64.getEncoder().encodeToString(v.v))
                    .append("</data>")
            }
            is PValue.Bool -> sb.append(if (v.v) "<true/>" else "<false/>")
            is PValue.Int -> sb.append("<integer>").append(v.v).append("</integer>")
            is PValue.Array -> {
                sb.append("<array>")
                v.items.forEach { write(sb, it) }
                sb.append("</array>")
            }
            is PValue.Dict -> {
                sb.append("<dict>")
                v.entries.forEach { (k, value) ->
                    sb.append("<key>").append(escape(k)).append("</key>")
                    write(sb, value)
                }
                sb.append("</dict>")
            }
        }
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // ---------------------------------------------------------------- 解析

    /** 解析设备回包（XML plist）。失败返回 null —— 调用方按原文打日志。 */
    fun parse(text: String): PValue? = runCatching { Parser(text).document() }.getOrNull()

    private class Parser(private val s: String) {
        private var i = 0

        fun document(): PValue {
            skipUntil("<plist")
            val open = s.indexOf('>', i)
            require(open > 0) { "找不到 <plist> 结束" }
            i = open + 1
            val v = value()
            return v ?: throw IllegalArgumentException("plist 为空")
        }

        private fun skipUntil(token: String) {
            val p = s.indexOf(token, i)
            i = if (p >= 0) p else s.length
        }

        /** 跳过声明、注释、DOCTYPE、空白。 */
        private fun skipNoise() {
            while (i < s.length) {
                val c = s[i]
                if (c.isWhitespace()) { i++; continue }
                if (c == '<') {
                    if (s.startsWith("<?", i)) { i = s.indexOf("?>", i).let { if (it < 0) s.length else it + 2 }; continue }
                    if (s.startsWith("<!--", i)) { i = s.indexOf("-->", i).let { if (it < 0) s.length else it + 3 }; continue }
                    if (s.startsWith("<!", i)) { i = s.indexOf('>', i).let { if (it < 0) s.length else it + 1 }; continue }
                }
                return
            }
        }

        private fun value(): PValue? {
            skipNoise()
            if (i >= s.length || s[i] != '<') return null
            val tagEnd = s.indexOf('>', i)
            require(tagEnd > i) { "标签截断" }
            val tag = s.substring(i + 1, tagEnd).trim().trimEnd('/')
            i = tagEnd + 1                      // ★ 先越过开标签，readText/readData 才从正文开始
            return when (tag) {
                "dict" -> dict(i)
                "array" -> array(i)
                "string" -> PValue.Str(readText("string"))
                "data" -> PValue.Data(readData())
                "integer" -> PValue.Int(readText("integer").trim().toLongOrNull() ?: 0L)
                "true" -> PValue.Bool(true)
                "false" -> PValue.Bool(false)
                "date" -> PValue.Str(readText("date"))
                "real" -> PValue.Str(readText("real"))
                else -> {
                    // 未知标签：整体跳过
                    skipElement(i, tag)
                    null
                }
            }
        }

        private fun dict(from: Int): PValue {
            i = from
            val out = ArrayList<Pair<String, PValue>>(8)
            while (true) {
                skipNoise()
                if (i >= s.length) break
                if (s.startsWith("</dict>", i)) { i += 7; break }
                if (!s.startsWith("<key>", i)) { skipElement(i, "key"); continue }
                i += 5
                val close = s.indexOf("</key>", i)
                require(close > 0) { "key 未闭合" }
                val k = unescape(s.substring(i, close))
                i = close + 6
                val v = value() ?: continue
                out.add(k to v)
            }
            return PValue.Dict(out)
        }

        private fun array(from: Int): PValue {
            i = from
            val out = ArrayList<PValue>(4)
            while (true) {
                skipNoise()
                if (i >= s.length) break
                if (s.startsWith("</array>", i)) { i += 8; break }
                out.add(value() ?: break)
            }
            return PValue.Array(out)
        }

        private fun readText(tag: String): String {
            val close = s.indexOf("</$tag>", i)
            require(close > 0) { "<$tag> 未闭合" }
            val body = unescape(s.substring(i, close))
            i = close + tag.length + 3
            return body
        }

        private fun readData(): ByteArray {
            val close = s.indexOf("</data>", i)
            require(close > 0) { "<data> 未闭合" }
            val b64 = s.substring(i, close).filter { !it.isWhitespace() }
            i = close + 7
            return java.util.Base64.getDecoder().decode(b64)
        }

        private fun skipElement(from: Int, tag: String) {
            val close = s.indexOf("</$tag>", from)
            i = if (close < 0) s.length else close + tag.length + 3
        }

        private fun unescape(v: String): String = v
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&amp;", "&")
    }
}
