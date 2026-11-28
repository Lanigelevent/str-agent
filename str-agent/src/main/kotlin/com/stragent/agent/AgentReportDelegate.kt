package com.stragent.agent

import com.stragent.core.ai.InsightResult
import com.stragent.core.caliber.CaliberNote
import com.stragent.core.dataset.Dataset
import com.stragent.core.report.TargetLength

/**
 * 单 Agent 报告引擎（非编辑部）—— AgentReportDelegate 的实现。
 * EditorialDept 是另一个实现（editorial kind）。
 */
class AgentReportDelegate(
    private val llm: com.stragent.core.ports.LlmClient,
    private val prefs: com.stragent.core.ports.PrefsStore,
) {
    fun tryGenerateReport(
        dataset: Dataset,
        plugin: PluginManifest,
        target: TargetLength,
        typeId: String,
        caliberNotes: List<CaliberNote>,
        onStage: ((String, com.stragent.core.ai.ChapterProgress?) -> Unit)?,
        cancelSignal: () -> Boolean,
        memory: List<com.stragent.core.memory.MemoryFact> = emptyList(),
        semantic: List<com.stragent.core.semantic.SemanticTerm> = emptyList(),
        /** 指标定义（T-055）：brief 里会带上可解析且自检通过的指标值 */
        metrics: List<com.stragent.core.semantic.MetricDef> = emptyList(),
        search: com.stragent.core.ports.SearchClient? = null,
        /** 整份报告这一阶段的断点（T-050）：完成态保留，由编排器过门禁后统一清理 */
        checkpoint: AgentCheckpoint = AgentCheckpoint.NONE,
    ): InsightResult? {
        val brief = com.stragent.core.brief.buildDatasetBrief(dataset, prefs, metrics)
        val registry = ToolRegistry.of(buildAgentTools(dataset, brief, search = search))
        val caliber = com.stragent.core.caliber.caliberBlock(caliberNotes)
        // T-032：插件 systemPrompt（工作流）+ 完整写作纪律栈（行业/类型/文风/去 AI 味/
        // 方法论——与经典路径同一份单一事实源）。此前只有六行浓缩纪律。
        val staffed = plugin.copy(systemPrompt = plugin.systemPrompt + com.stragent.core.ai.writingDisciplineBlocks(prefs))
        val task = buildString {
            append("<task>请基于下面的数据摘要写一份${com.stragent.core.report.ReportLib.getReportType(prefs).label}（$typeId）。" +
                "目标篇幅约 ${target.chars} 字（$target）。写完用 finish 收尾，args.report = 完整 Markdown 报告全文。</task>\n\n")
            append("<data_brief>\n$brief\n</data_brief>\n")
            if (caliber != null) append("\n$caliber\n")
            // 跨会话记忆独立成段（core/memory 纪律 a：绝不并进 data_brief）。
            com.stragent.core.memory.memoryBlock(memory)?.let { append("\n$it\n") }
            // 语义层独立成段（core/semantic 纪律 a：跨数据集指标定义，不进 data_brief）。
            // 按列名匹配后注入——只给与本次数据相关的术语。
            com.stragent.core.semantic.matchTermsForColumns(semantic, dataset.columns)
                .let { com.stragent.core.semantic.semanticBlock(it) }?.let { append("\n$it\n") }
        }
        val outcome = AgentLoop(llm).run(
            staffed, registry, staffed.model ?: "default", task, AGENT_HARNESS_CONTRACT, cancelSignal,
            onStep = { step ->
                onStage?.invoke("tool", com.stragent.core.ai.ChapterProgress(1, 1, "${step.tool}（${step.argsSummary.take(60)}）"))
            },
            checkpoint = checkpoint,
            keepOnFinish = true,
        ) ?: return null
        val report = outcome.output["report"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
            ?: return null
        if (report.isBlank()) return null
        return InsightResult(
            report = report.trim(),
            totalTokens = outcome.totalTokens,
            targetChars = target.chars,
            actualChars = com.stragent.core.ai.countChars(report),
            autonomous = true,
            engineId = plugin.id,
            agentPlan = outcome.plan,
            agentFindings = outcome.findings,
            agentCharts = outcome.charts,
            agentTrace = outcome.trace.map { it.toJson() },
        )
    }
}
