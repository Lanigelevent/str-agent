package com.stragent.data

import com.stragent.core.ports.AgentTool
import com.stragent.core.tools.buildAnalysisTools
import com.stragent.core.brief.buildDatasetBrief
import com.stragent.core.dataset.Dataset
import com.stragent.core.ports.PrefsStore
import kotlinx.serialization.json.JsonObject

/**
 * 策展分析工具 → MCP 工具适配（**会话当前数据集**模式）。
 *
 * 为什么需要这一层（T-007）：
 *   16 个策展分析工具（`buildAnalysisTools`）原本只活在 agent 循环内部——
 *   它们绑定的是**某个具体数据集实例**，参数用**列名**。
 *   而 MCP 工具面是「无状态协议 + 外部 agent 驱动」：外部 agent 的自然工作流是
 *   「先 `stragent_import_table`，再连续调分析工具」，每一步都带 datasetId 是噪音。
 *
 * 做法：
 *   - 目录（tools/list）的元数据来自工具工厂，**与具体数据集无关**（name/description/schema 都是静态的）；
 *   - 执行（tools/call）时才按**会话当前数据集**（最近一次 import）重建工具并调用 —— 因为
 *     有些工具在构造时就预解析了默认列（`pickDefaultColumns`），必须按当前数据重建才对；
 *   - 工具面保持**只读**：网络型工具（web_search / web_fetch）不进来（与 McpBridge 的安全边界一致）。
 *
 * 数字口径不变：所有工具仍走 core 的同一套统计实现，每个数字都能回溯到原始行。
 */
class AnalysisToolSession(
    private val datasets: AbilityDatasets,
    private val prefs: PrefsStore,
    /** 会话注册表（可选）：给了就按 [McpSessionContext] 的当前会话取数据集指针 */
    private val registry: SessionRegistry? = null,
) {
    /**
     * 当前会话的数据集。注册表在场就**严格按会话**取：具名会话没导入过就是没有，
     * 不许回退到别的会话的全局指针（否则 tools/list 目录会把别的会话的数据摘要漏进来）；
     * 默认会话的「回退全局最近导入」由 [SessionRegistry.currentId] 内部处理。
     * 注册表不在场（无会话语义的调用方）保持原全局语义。
     * 注意不能用 `registry?.current() ?: datasets.current()`：Elvis 链会把
     * 「具名会话没指针」也漏到全局，正是这里要堵的跨会话泄漏。
     */
    private fun sessionDataset(): Dataset? {
        val reg = registry ?: return datasets.current()
        return reg.current(McpSessionContext.current())
    }

    /** 会话当前数据集；没导入过就报中文错误（与能力契约同一口吻） */
    fun currentDataset(): Dataset =
        sessionDataset()
            ?: throw IllegalArgumentException(
                "尚未导入数据：请先用 stragent_import_table / stragent_import_text 导入数据，再调用分析工具", // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            )

    /** 目录用的工具实例（无数据时用空表占位——元数据是静态的，取哪份都一样） */
    private fun templateTools(): List<AgentTool> {
        val dataset = sessionDataset() ?: EMPTY
        val brief = buildDatasetBrief(dataset, prefs)
        return buildAnalysisTools(dataset, brief).filter { it.name !in NETWORK_TOOLS }
    }

    /** 对外暴露的 MCP 工具集（每次取目录时重建，保证与当前数据同步） */
    fun tools(): List<AgentTool> = templateTools().map { template ->
        object : AgentTool {
            override val name: String = template.name
            override val description: String =
                template.description + "（作用于会话当前数据集：最近一次导入的那份；纯文本数据集不可用，请用 stragent_summarize_text）" // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            override val parametersSchema: JsonObject = template.parametersSchema
            override val outputSchema: JsonObject = template.outputSchema
            override val cacheable: Boolean get() = template.cacheable
            override val readOnly: Boolean get() = template.readOnly
            override val destructive: Boolean get() = template.destructive
            override val openWorld: Boolean get() = template.openWorld

            override fun invoke(args: JsonObject): JsonObject {
                val dataset = currentDataset()
                // 纯文本数据集：表格分析工具没有可作用的列/行——给中文指引，
                // 而不是放任下游在空表上算出一个「看起来对」的垃圾数
                if (dataset.rows.isEmpty() && dataset.text != null) {
                    throw IllegalArgumentException(
                        "这是文本型数据集（没有表格行、正文 ${dataset.text!!.segments.size} 段）：表格分析工具不可用——" + // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                            "请用 stragent_summarize_text 总结正文，或走综述报告/追问链路。", // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                    )
                }
                val brief = buildDatasetBrief(dataset, prefs)
                val tool = buildAnalysisTools(dataset, brief)
                    .firstOrNull { it.name == template.name }
                    ?: throw IllegalArgumentException("未知工具: ${template.name}")
                return tool.invoke(args)
            }
        }
    }

    companion object {
        /** 网络型工具不进只读工具面 */
        private val NETWORK_TOOLS = setOf("web_search", "web_fetch")

        /** 空表占位：只用于取静态元数据（目录），不会真的被调用 */
        private val EMPTY = Dataset(
            name = "（空表）",
            importedAt = 0L,
            columns = emptyList(),
            rows = emptyList(),
            columnTypes = emptyList(),
        )
    }
}
