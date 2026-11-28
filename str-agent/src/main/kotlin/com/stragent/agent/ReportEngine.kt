package com.stragent.agent

import com.stragent.core.ai.ChapterProgress
import com.stragent.core.ai.InsightResult
import com.stragent.core.caliber.CaliberNote
import com.stragent.core.dataset.Dataset
import com.stragent.core.memory.MemoryFact
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.PrefsStore
import com.stragent.core.ports.SearchClient
import com.stragent.core.report.MAX_GATE_REVISIONS
import com.stragent.core.report.TargetLength
import com.stragent.core.semantic.MetricDef
import com.stragent.core.semantic.SemanticTerm
import kotlinx.serialization.json.JsonPrimitive

/**
 * 报告引擎接口（T-058 / D17 G-19）——取代「按 `plugin.kind` 字符串分支」的引擎分发。
 *
 * 分发纪律：
 *  - 编排器只做一件事：`ReportEngineRegistry.create(plugin, deps)`，拿到引擎就调
 *    [generate]；拿不到才回落经典流水线。**编排器里不再出现 `kind == "editorial"`**；
 *  - 新增引擎 = 在组合根注册一个 kind → 工厂（[ReportEngineRegistry.with]），
 *    引擎自己声明 [gateRevisionBudget]，分发点、门禁预算与回落逻辑一行不改；
 *  - `kind` 仍在插件清单里（用户可读、断点签名的一部分），但它只是**声明**，
 *    能不能被承接由注册表回答（[ReportEngineRegistry.supports]），不再由 if/else 回答。
 */
interface ReportEngine {
    /** 承接的插件 kind（与 [PluginManifest.kind] 对齐） */
    val kind: String

    /**
     * 外层发稿门禁的退稿预算。
     * 编辑部内部已经用满 2 轮退稿预算 → 外层 0（只评估、标记，不重复花钱）；
     * 单 agent 没有内部退稿 → 用满 [MAX_GATE_REVISIONS]。
     */
    val gateRevisionBudget: Int

    /** 生成一份报告；返回 null = 本轮空手（编排器按取消/回落纪律处理） */
    fun generate(request: ReportEngineRequest): InsightResult?
}

/**
 * 一次报告生成的全部上下文——与 `kind` 无关的扁平入参。
 * 引擎从请求里取自己要用的字段，编排器不再为不同引擎拼不同参数表。
 */
data class ReportEngineRequest(
    val dataset: Dataset,
    val plugin: PluginManifest,
    val brief: String,
    val target: TargetLength,
    val typeId: String,
    val caliberNotes: List<CaliberNote> = emptyList(),
    val onStage: ((String, ChapterProgress?) -> Unit)? = null,
    val cancelSignal: () -> Boolean = { false },
    val memory: List<MemoryFact> = emptyList(),
    val semantic: List<SemanticTerm> = emptyList(),
    val metrics: List<MetricDef> = emptyList(),
    val search: SearchClient? = null,
    /** 整份报告这一阶段的断点（单 agent 引擎用） */
    val checkpoint: AgentCheckpoint = AgentCheckpoint.NONE,
    /** 一次运行的全部阶段断点（编辑部引擎用：每个员工各自一段） */
    val checkpoints: AgentRunCheckpoints? = null,
)

/**
 * 引擎注册表：kind → 工厂。
 *
 * 不可变（[with] 返回新表），组合根装配一次、并发读安全；
 * [BUILT_IN] 只登记随包引擎，第三方宿主可 `BUILT_IN.with("my-engine") { … }` 扩展。
 */
class ReportEngineRegistry private constructor(
    private val providers: Map<String, (PluginManifest, Deps) -> ReportEngine>,
) {
    /**
     * 引擎工厂可用的宿主依赖（模型 + 偏好；数据/摘要等都在请求里）。
     *
     * [types] 是**宿主级**的报告类型登记表（T-145）——它与 [prefs] 同级（都是宿主装配时
     * 决定的配置），所以走这里而不是塞进每次调用的 [ReportEngineRequest]。
     */
    class Deps(
        val llm: LlmClient,
        val prefs: PrefsStore,
        val types: com.stragent.core.report.ReportTypeRegistry = com.stragent.core.report.ReportTypeRegistry.BUILT_IN,
    )

    fun supports(kind: String): Boolean = providers.containsKey(kind)

    fun create(plugin: PluginManifest, deps: Deps): ReportEngine? = providers[plugin.kind]?.invoke(plugin, deps)

    /** 注册/替换一个 kind（注册即扩展，不改任何分发点） */
    fun with(kind: String, provider: (PluginManifest, Deps) -> ReportEngine): ReportEngineRegistry =
        ReportEngineRegistry(LinkedHashMap(providers).apply { put(kind, provider) })

    companion object {
        /** 随包引擎：单 agent（report）/ 编辑部（editorial） */
        val BUILT_IN: ReportEngineRegistry = ReportEngineRegistry(
            linkedMapOf(
                "report" to { _, deps -> SingleAgentReportEngine(AgentReportDelegate(deps.llm, deps.prefs)) },
                "editorial" to { _, deps -> EditorialReportEngine(deps.llm, deps.prefs) },
            ),
        )
    }
}

/** 单 agent 引擎：AgentLoop 自主查数 + finish 交全文 */
internal class SingleAgentReportEngine(
    private val delegate: AgentReportDelegate,
) : ReportEngine {
    override val kind: String = "report"
    override val gateRevisionBudget: Int = MAX_GATE_REVISIONS

    override fun generate(request: ReportEngineRequest): InsightResult? = delegate.tryGenerateReport(
        request.dataset,
        request.plugin,
        request.target,
        request.typeId,
        request.caliberNotes,
        request.onStage,
        request.cancelSignal,
        request.memory,
        request.semantic,
        request.metrics,
        request.search,
        checkpoint = request.checkpoint,
    )
}

/**
 * 编辑部引擎：主编选题 → 记者条线 → 主笔成稿（可分章）→ 内部确定性核查。
 * 实现细节仍在 [EditorialDept]；这里只做请求适配与结果整形。
 */
internal class EditorialReportEngine(
    private val llm: LlmClient,
    private val prefs: PrefsStore,
    private val types: com.stragent.core.report.ReportTypeRegistry = com.stragent.core.report.ReportTypeRegistry.BUILT_IN,
) : ReportEngine {
    override val kind: String = "editorial"

    /** 编辑部内部已用满退稿预算 → 外层只评估 */
    override val gateRevisionBudget: Int = 0

    override fun generate(request: ReportEngineRequest): InsightResult? {
        val plugin = request.plugin
        val oc = EditorialDept(llm, types).run(
            request.dataset, request.brief, request.target, request.typeId, prefs, plugin,
            request.caliberNotes, plugin.model ?: "default", request.cancelSignal,
            onStep = { step ->
                request.onStage?.invoke(
                    "tool",
                    ChapterProgress(1, 1, "${step.tool}（${step.argsSummary.take(60)}）"),
                )
            },
            onPhase = { phase -> request.onStage?.invoke("agent", ChapterProgress(0, 0, phase)) },
            memory = request.memory,
            semantic = request.semantic,
            checkpoints = request.checkpoints,
        ) ?: return null
        val reportText = oc.output["report"]
            ?.let { (it as? JsonPrimitive)?.content?.trim() }
            .takeIf { !it.isNullOrBlank() }
            ?: return null
        return InsightResult(
            report = reportText,
            totalTokens = oc.totalTokens,
            targetChars = request.target.chars,
            actualChars = com.stragent.core.ai.countChars(reportText),
            autonomous = true,
            engineId = plugin.id,
            agentPlan = oc.plan,
            agentFindings = oc.findings,
            agentCharts = oc.charts,
            agentTrace = oc.trace.map { it.toJson() },
            qualityNotes = oc.qualityNotes,
            // 内部退稿轮数透传：外层门禁预算为 0，只评估（T-044 不双重计入）
            gateRevisions = oc.gateRevisions,
        )
    }
}
