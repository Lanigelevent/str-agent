package com.stragent.agent.plugin

/**
 * 极薄的 harness 内核（"万物皆插件"的宿主）。
 * 职责只有四件：装载（install + activate）、按 id 查找、卸载（deactivate + uninstall）、聚合能力面。
 * 所有能力都在 [HostPlugin] 里；本类不硬编码任何数据分析逻辑。
 *
 * Str Agent 当前是内置装载的唯一插件；该设计允许未来接入别的 agent 插件（不同引擎、
 * 不同领域提示词包），宿主无需改动即可获得新能力——这正是 DeepSeek Harness 的核心思路。
 */
class StrAgentHost(private val ctx: StrAgentHostContext) {
    private val plugins = LinkedHashMap<String, HostPlugin>()

    fun load(plugin: HostPlugin): HostPlugin {
        require(!plugins.containsKey(plugin.manifest.id)) { "插件 id 已存在: ${plugin.manifest.id}" }
        // the reference harness capability safety：装载时检查宿主是否提供插件声明的「必需缝」（先声明后用）
        val missing = plugin.manifest.inject.filter { it.required && !ctxHasSeam(it.id) }
        if (missing.isNotEmpty()) {
            throw IllegalArgumentException("宿主缺少插件所需缝(the reference harness inject): ${missing.joinToString { it.id }}")
        }
        plugin.install(ctx)
        plugin.activate()
        plugins[plugin.manifest.id] = plugin
        return plugin
    }

    fun unload(id: String) {
        plugins.remove(id)?.let { it.deactivate(); it.uninstall() }
    }

    fun get(id: String): HostPlugin? = plugins[id]
    fun list(): List<HostPlugin> = plugins.values.toList()
    fun capabilities(): List<PluginCapability> = plugins.values.flatMap { it.capabilities() }

    /** 宿主端口静态满足性：本宿主能提供哪些被声明的缝（capability safety 判定用）。 */
    private fun ctxHasSeam(seamId: String): Boolean = when (seamId) {
        // 宿主必提供的缝
        "model" -> true            // ctx.llm 恒非空
        "storage" -> true          // ctx.kv 恒非空
        "prefs" -> true            // ctx.prefs 恒非空
        // 可选缝：宿主未提供时插件用内置实现兜底（不阻断装载）
        "session" -> false         // 宿主 observable 层若提供 TraceSink 则接入，否则用内置 InsightTrace
        else -> false
    }
}
