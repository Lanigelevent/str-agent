package com.stragent.agent

import com.stragent.core.ports.PrefsStore

/**
 * 分析引擎选择：`agent.engine` = "classic"（默认，经典流水线）| 插件 id。
 * classic 恒为回落：插件执行失败/被删时自动回落，不丢可用性。
 */
object AgentEngine {
    const val CLASSIC = "classic"
    private const val ENGINE_KEY = "agent.engine"

    fun getEngineId(prefs: PrefsStore): String {
        val saved = prefs.get(ENGINE_KEY, CLASSIC)
        // 指向已删除插件的引擎回落 classic（列表页没有它就没有选择它的入口）
        return saved.ifBlank { CLASSIC }
    }

    fun setEngineId(prefs: PrefsStore, id: String) = prefs.set(ENGINE_KEY, id)

    /** 当前引擎对应的报告插件（classic 或未命中 → null，走经典流水线）。
     *  能否承接由 [ReportEngineRegistry] 回答（report / editorial / 第三方注册的引擎），
     *  本方法不再硬编码 kind 名单（T-058）。 */
    fun selectedReportPlugin(
        prefs: PrefsStore,
        registry: PluginRegistry,
        engines: ReportEngineRegistry = ReportEngineRegistry.BUILT_IN,
    ): PluginManifest? {
        val id = getEngineId(prefs)
        if (id == CLASSIC) return null
        return registry.get(id)?.takeIf { engines.supports(it.kind) }
    }

    /** 当前引擎对应的追问插件（引擎 id 也可以指向 followup 类插件） */
    fun selectedFollowupPlugin(prefs: PrefsStore, registry: PluginRegistry): PluginManifest? {
        val id = getEngineId(prefs)
        if (id == CLASSIC) return null
        return registry.get(id)?.takeIf { it.kind == "followup" }
    }

    /** 审查插件独立开关（沿用同一引擎键不在时回落 classic——审查默认走经典 prompt） */
    fun selectedReviewPlugin(prefs: PrefsStore, registry: PluginRegistry): PluginManifest? {
        val id = getEngineId(prefs)
        if (id == CLASSIC) return null
        return registry.get(id)?.takeIf { it.kind == "review" }
    }
}
