package com.uav.protocol.ios

/**
 * 设备的 iOS 版本信息（来自 lockdown 全量 `GetValue` 的 `Value` 字典）。
 *
 * ## 为什么要记它（而协议层什么都不做）
 *
 * 实测结论：**QuickTime 采集协议本身没有版本差异** ——
 * 同一份 `QtProtocolCore` 在 iPadOS 27.2 上跑通，报文与 iOS 8 时代完全一致，
 * 开源实现 iPhoneMirror 同时支持 iOS 26/27 也没有任何版本分支。
 *
 * 但**"能不能推流"的授权门控有版本差异**：
 *
 * | iOS | 门控 | 缺门控时的表现 |
 * |---|---|---|
 * | ≤ 26 | 只需**受信任的 lockdown 会话** | 建好会话就推流 |
 * | 27+ | 额外要**开发者模式 / 媒体流授权** | `QuickTime endpoint opened but no PING`、超时 -2104 |
 *
 * ⚠️ 开发者模式**不是充分条件**：实测开了它CWPA 仍然不来，背后还有 DDI（开发者镜像）
 *   签发的媒体流授权。所以本类**只做"提示"，不做"判定"**——
 *   绝不能因为版本低就拒绝建会话，也绝不能因为版本高就期待它一定能成。
 *
 * ## 唯一的作用
 *
 * 让「**这条设备跑不起来**」在日志/界面上能一眼区分成两种：
 * 「版本不支持」还是「会话没建对」。否则每次遇到新系统设备，
 * 又要从头A/B 一遍——这正是 10.18 那一轮浪费的时间。
 *
 * ⚠️ 老记录没有这些字段（[productVersion] 为 null）⇒ 一律当作"未知"，
 *   **绝不因此改行为**（保持向后兼容：老设备照旧按原路径走）。
 */
class DeviceVersion(
    /** `ProductVersion`，形如 `27.2`、`18.5.1`、`17.0`。取不到为null。 */
    val productVersion: String?,
    /** `ProductType`，形如 `iPad8,9`。取不到为 null。 */
    val productType: String?,
) {

    /**
     * 主版本号，取不到时为 null。
     *
     * 只取第一段数字：`27.2` → 27、`18.5.1` → 18。
     * ⚠️ 解析失败**必须返回 null 而不是 0** —— 0 会被 `>=` 判断误当成"老版本"，
     * 从而在日志里给出错误结论（这跟"把未知当最坏情况"是两回事）。
     */
    val major: Int? = productVersion?.trim()?.substringBefore('.')
        ?.toIntOrNull()

    /** 是否**已知**要额外门控（iOS 27+）。未知一律 false（= 不做任何版本相关判断）。 */
    val needsExtraGating: Boolean = (major ?: 0) >= FIRST_EXTRA_GATING_MAJOR

    /**
     * 界面/日志上的附加提示；不需要时返回 null。
     *
     * 只在 [IosStage] 已判失败之后才用得上——它是**解释**，不是**预测**。
     */
    fun failureHint(): String? = if (needsExtraGating) {
        "iOS $productVersion（$productType）属于新系统，除配对外可能还需要开发者模式/" +
            "媒体流授权；这不是协议不兼容"
    } else {
        null
    }

    /** 一行摘要，供日志。 */
    fun summary(): String = "iOS=${productVersion ?: "?"} 机型=${productType ?: "?"}" +
        if (needsExtraGating) "（新系统，需注意额外门控）" else ""

    override fun toString(): String = summary()

    companion object {
        /**
         * 第一个"需要额外门控"的主版本号。
         *
         * 来源：iPhoneMirror issue #45（iOS 27.0.1 未开开发者模式 ⇒ 六次全黑、
         * 开启后 61.6fps）。⚠️ 这是**门控的版本**，不是**协议不兼容的版本**——
         * 见类注释。
         */
        const val FIRST_EXTRA_GATING_MAJOR = 27

        /** 从 lockdown 全量 `GetValue` 的回包（[PValue]，通常取它的 `Value` 字典）里取值；缺字段就都当 null。 */
        fun fromReply(value: PValue?): DeviceVersion = DeviceVersion(
            productVersion = value?.str("ProductVersion"),
            productType = value?.str("ProductType"),
        )

        /** 同上，但传普通 Map（便于单测与从别处喂数据）。 */
        fun fromMap(value: Map<String, Any?>): DeviceVersion = DeviceVersion(
            productVersion = value["ProductVersion"] as? String,
            productType = value["ProductType"] as? String,
        )

        /** 未知（老记录没有版本字段，或全量 GetValue 失败）。 */
        val UNKNOWN = DeviceVersion(null, null)
    }
}