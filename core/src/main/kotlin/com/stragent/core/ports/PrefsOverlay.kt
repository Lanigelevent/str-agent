package com.stragent.core.ports

/**
 * PrefsStore 按调用覆盖层（T-105）：base + overrides。
 *
 * Langfuse「持久预设 + 运行时变量编译」模式的本仓形态：PrefsStore 是持久预设层
 * （UI 侧设置），工具参数是按调用覆盖——ReportLib.getXxx(prefs) 与
 * writingDisciplineBlocks(prefs) 对 overlay 无感知，零签名变化生效。
 *
 * 语义：
 * - get：overrides 命中（含空串）则返回覆盖值，否则回退 base；
 *   调用方负责不放空白覆盖值（空 requirements/audience 不进 overlay）
 * - set/remove：透传 base（覆盖层偏读；core 生成路径无 prefs 写入）
 */
class PrefsOverlay(
    private val base: PrefsStore,
    private val overrides: Map<String, String>,
) : PrefsStore {
    override fun get(key: String, fallback: String): String =
        if (overrides.containsKey(key)) overrides.getValue(key) else base.get(key, fallback)

    override fun set(key: String, value: String) = base.set(key, value)

    override fun remove(key: String) = base.remove(key)
}
