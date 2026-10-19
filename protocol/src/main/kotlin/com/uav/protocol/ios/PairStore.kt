package com.uav.protocol.ios

import java.io.File

/**
 * 配对记录的**落盘布局**：一台设备一条，互不覆盖。
 *
 * ## 为什么不能只有一条记录
 *
 * 配对记录 = "主机 × 设备"这一对的关系：里面既有主机身份（HostID / 证书链 / 私钥），
 * 也有**设备专属**的 `DeviceCertificate` 与 `EscrowBag`。所以：
 *
 * - 多台设备共用一条 ⇒ 换一台 iPad 就把上一台的信任**覆盖**掉，换回来还要在 iPad 上
 *   重新点一次「信任」（实测就是"换设备需要重新认证"）；
 * - 一台设备一条 ⇒ 只有**第一次**碰到某台 iPad 才需要在它上面点一次信任，
 *   之后来回换都不用再点。
 *
 * 文件名：`ios_pair_<设备键>.properties`，设备键 = USB 序列号（见 `Pairing.deviceKey`）。
 * 旧版是单文件 `ios_pair.properties`，这里负责**一次性迁移**（详见 [load]）。
 *
 * 纯文件逻辑，没有任何 Android 依赖 —— 所以能在 JVM 单测里钉住（`PairStoreTest`）。
 */
object PairStore {

    /** 一台设备一个文件的前缀。 */
    const val PREFIX = "ios_pair_"

    /** 旧版单文件（不分设备）。只用于迁移。 */
    const val LEGACY_FILE = "ios_pair.properties"

    /** 拿不到设备键时的占位键（退回旧版单文件语义）。 */
    const val KEY_UNKNOWN = "unknown"

    fun recordFile(dir: File, key: String = KEY_UNKNOWN): File =
        if (key == KEY_UNKNOWN) File(dir, LEGACY_FILE) else File(dir, "$PREFIX$key.properties")

    fun has(dir: File, key: String = KEY_UNKNOWN): Boolean = load(dir, key) != null

    /**
     * 读某台设备的记录。
     *
     * 迁移规则（只影响升级前留下的那一份）：
     *  - 旧记录**没有** `deviceSerial`（归属未知）⇒ 先让第一台插上的设备**暂时接管**
     *    （`provisional = true`），**旧文件先留着**：等它真的跑通一次 TLS 再转正并删旧文件
     *    （[confirm]）；万一认错人（TLS 没通过）就丢掉这条临时记录（[dropProvisional]），
     *    旧文件还在，可以还给对的那台设备。
     *  - 旧记录**有** `deviceSerial` 且不等于本设备 ⇒ 不接管。
     *
     * 键未知（序列号读不到）时：本地若**只有一台**设备的记录就直接用它 ——
     * 宁可用唯一的那条去试，也不要让用户白点一次「信任」。
     */
    fun load(dir: File, key: String = KEY_UNKNOWN, log: ((String) -> Unit)? = null): PairRecord? {
        val own = recordFile(dir, key)
        if (own.exists() && own.length() > 0) {
            return runCatching { PairRecord.decode(own.readText()) }.getOrNull()
        }

        if (key == KEY_UNKNOWN) {
            val all = records(dir)
            if (all.size == 1) {
                log?.invoke("设备键不可用（序列号读不到）⇒ 沿用本地唯一的那条配对记录")
                return all[0].second
            }
            return null
        }

        val legacy = File(dir, LEGACY_FILE)
        if (!legacy.exists() || legacy.length() == 0L) return null
        val rec = runCatching { PairRecord.decode(legacy.readText()) }.getOrNull() ?: return null
        if (rec.deviceSerial != null && rec.deviceSerial != key) return null

        // 暂时接管：盖章 + 打 provisional 标记，**旧文件留着**（认错人时要还回去）
        val stamped = rec.withDeviceSerial(key, asProvisional = true)
        runCatching { own.writeText(stamped.encode()) }
            .onSuccess {
                log?.invoke(
                    "发现旧版单文件配对记录 ⇒ 暂时按这台设备（$key）接管；" +
                        "跑通一次投屏确认归属后即转正"
                )
            }
        return stamped
    }

    /**
     * 实机校验通过：把"暂时接管"转正，并删掉旧版单文件。
     *
     * 只在**真的跑通了 TLS** 之后调用 —— 这是"旧记录确实属于这台设备"的唯一证据。
     */
    fun confirm(dir: File, key: String, log: ((String) -> Unit)? = null) {
        val own = recordFile(dir, key)
        if (!own.exists()) return
        val rec = runCatching { PairRecord.decode(own.readText()) }.getOrNull() ?: return
        if (!rec.provisional) return
        runCatching { own.writeText(rec.withProvisional(false).encode()) }
        File(dir, LEGACY_FILE).delete()
        log?.invoke("配对记录归属已确认（$key）；旧版单文件已清理")
    }

    /**
     * 认错人了：作废"暂时接管"的记录（旧版单文件仍在，可以留给对的那台设备）。
     *
     * 只对 `provisional` 记录生效 —— 正常配对出来的记录**永远不会**被自动删，
     * 免得一次线材接触不良就把用户的信任关系清掉。
     */
    fun dropProvisional(dir: File, key: String, log: ((String) -> Unit)? = null) {
        val own = recordFile(dir, key)
        if (!own.exists()) return
        val rec = runCatching { PairRecord.decode(own.readText()) }.getOrNull() ?: return
        if (!rec.provisional) return
        own.delete()
        log?.invoke(
            "旧版配对记录不属于这台设备（$key）⇒ 已作废这条临时记录；" +
                "插回原来那台即可自动认领，或在这台上点一次「信任」重新配对"
        )
    }

    /** 写入某台设备的记录，返回落盘的文件。 */
    fun save(dir: File, key: String, rec: PairRecord): File =
        recordFile(dir, key).also { it.writeText(rec.encode()) }

    /** 已记住的设备键（界面用来显示"换回来不用再点信任"）。 */
    fun knownKeys(dir: File): List<String> = records(dir).map { it.first }.sorted()

    /** 旧版单文件是否还没被迁移。 */
    fun hasLegacy(dir: File): Boolean =
        File(dir, LEGACY_FILE).let { it.exists() && it.length() > 0 }

    /** 所有按设备存放的记录（不含旧版单文件）。 */
    private fun records(dir: File): List<Pair<String, PairRecord>> =
        (dir.listFiles() ?: emptyArray())
            .filter { it.name.startsWith(PREFIX) && it.name.endsWith(".properties") }
            .mapNotNull { f ->
                val key = f.name.removePrefix(PREFIX).removeSuffix(".properties")
                runCatching { PairRecord.decode(f.readText()) }.getOrNull()?.let { key to it }
            }
}
