package com.stragent.agent.plugin

import com.stragent.agent.AgentCheckpointStore
import com.stragent.agent.AgentIntegratedOrchestrator
import com.stragent.agent.PluginRegistry
import com.stragent.core.ai.ChatResult as CoreChatResult
import com.stragent.core.ai.ChatTurn
import com.stragent.core.ai.InsightOrchestrator
import com.stragent.core.ai.InsightResult
import com.stragent.core.caliber.CaliberNote
import com.stragent.core.dataset.Dataset

/**
 * Str Agent 作为「万物皆插件」里的插件实现。
 *
 * - 通过 [install] 接收一个宿主提供的 [StrAgentHostContext]（端口），完成引擎装配；
 * - 通过 [capabilities] 暴露能力面（外部宿主可枚举）；
 * - 通过类型安全的 [generateInsight] / [askFollowup] 直接调用（同进程高性能路径）；
 * - 通过 [invoke] 提供声明式调用（跨进程 / 外部 harness 接入路径）。
 *
 * 回落纪律不变：agent 路径失败 / 取消 / 返回 null → 自动回落 classic（见 [AgentIntegratedOrchestrator]）。
 */
class StrAgentPlugin : HostPlugin {
    override val manifest = StrAgentPluginManifest()

    private var ctx: StrAgentHostContext? = null
    private var registry: PluginRegistry? = null
    private var orchestrator: AgentIntegratedOrchestrator? = null
    private var active = false

    override fun install(context: StrAgentHostContext) {
        val reg = PluginRegistry(context.pluginDir)
        val classic = InsightOrchestrator(
            context.llm, context.prefs, context.kv, context.search,
            model = context.modelResolver(null),
            reportTypes = context.reportTypes,
        )
        val oc = AgentIntegratedOrchestrator(
            classic, context.llm, context.prefs, reg, context.search,
            checkpoints = AgentCheckpointStore(context.checkpointDir).also {
                // T-156：启动时孤儿回收——取消/崩溃残留的 run 目录（此前无限累积）按
                // 7 天窗口清扫；近期写过的活跃任务不受影响。清扫失败只记数，不打断装配。
                val swept = runCatching { it.sweepOrphans() }.getOrDefault(0)
                if (swept > 0) {
                    com.stragent.core.observability.StructuredLogger.getLogger("AgentCheckpoint")
                        .info("checkpoint 孤儿回收：清理 $swept 个超期 run 目录（7 天窗口）") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                }
            },
            // T-145：把宿主的两个注册表**真正接进生产装配路径**——
            // 此前这两行没传，扩展点只能被测试用。
            reportEngines = context.reportEngines,
            reportTypes = context.reportTypes,
        )
        this.registry = reg
        this.orchestrator = oc
        this.ctx = context
    }

    override fun activate() { active = true }

    override fun deactivate() { active = false }

    override fun uninstall() {
        active = false
        ctx = null
        registry = null
        orchestrator = null
    }

    /** 底层引擎（宿主或 App 想直接驱动时用）。未安装时抛错，提示先 install。 */
    val engine: AgentIntegratedOrchestrator
        get() = orchestrator ?: throw IllegalStateException("Str Agent 插件未安装：请先调用 install(ctx)") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）

    /** 插件注册表（内置 + 用户导入的 agent 插件清单）。 */
    val registryOf: PluginRegistry
        get() = registry ?: throw IllegalStateException("Str Agent 插件未安装：请先调用 install(ctx)") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）

    val isActive: Boolean get() = active

    override fun capabilities(): List<PluginCapability> = manifest.provides

    // ---- 类型安全便捷路径（同进程） ----
    fun generateInsight(
        dataset: Dataset,
        useWeb: Boolean = false,
        caliberNotes: List<CaliberNote> = emptyList(),
        memory: List<com.stragent.core.memory.MemoryFact> = emptyList(),
        semantic: List<com.stragent.core.semantic.SemanticTerm> = emptyList(),
        /** 指标目录（T-055）：空 = 不注入指标口径段，简报与本改动前逐字节一致 */
        metrics: List<com.stragent.core.semantic.MetricDef> = emptyList(),
        /** 协作式取消信号（MCP `notifications/cancelled` 等）：默认恒 false，零行为差异 */
        cancelSignal: () -> Boolean = { false },
        /** 上游链路（T-056）：MCP `_meta.traceparent` 解析结果——报告 span 树挂到调用方 trace 下 */
        traceParent: com.stragent.core.observability.OtelSpanContext? = null,
        /** trace 落点（T-056）：宿主接了 OTel 导出器就传 `{ otel.export(it) }`；不传 = 只录制不导出 */
        traceSink: ((com.stragent.core.trace.InsightTrace) -> Unit)? = null,
        /** 每 1K token 估算单价（USD）：null = 不写成本（不编造） */
        costPer1kTokens: Double? = null,
        /** T-105 按调用输出适配：prefs 键→值覆盖（analysis.reportType/style/industry/depth/audience/requirements）；空 = 零差异 */
        styleOverrides: Map<String, String> = emptyMap(),
    ): InsightResult {
        val oc = engine
        val context = ctx
        // 宿主两个回调都没给时保持「裸调用」：不包 lambda、引擎侧不建节流器、请求形态不变
        if (context == null || (context.progressListener == null && context.bodyListener == null)) {
            return oc.generateInsight(
                dataset, useWeb, caliberNotes,
                cancelSignal = cancelSignal,
                traceSink = traceSink,
                memory = memory,
                semantic = semantic,
                metrics = metrics,
                traceParent = traceParent,
                costPer1kTokens = costPer1kTokens,
                styleOverrides = styleOverrides,
            )
        }
        return oc.generateInsight(
            dataset, useWeb, caliberNotes,
            onStage = context.progressListener,
            onBody = context.bodyListener,
            cancelSignal = cancelSignal,
            traceSink = traceSink,
            memory = memory,
            semantic = semantic,
            metrics = metrics,
            traceParent = traceParent,
            costPer1kTokens = costPer1kTokens,
            styleOverrides = styleOverrides,
        )
    }

    fun askFollowup(
        dataset: Dataset,
        history: List<ChatTurn>,
        question: String,
        model: String? = null,
        memory: List<com.stragent.core.memory.MemoryFact> = emptyList(),
        semantic: List<com.stragent.core.semantic.SemanticTerm> = emptyList(),
        /** 协作式取消信号（MCP `notifications/cancelled` 等）：默认恒 false，零行为差异 */
        cancelSignal: () -> Boolean = { false },
        /** 嵌入客户端（T-100 hybrid 检索）；缺省用宿主上下文的配置 */
        embedding: com.stragent.core.ports.EmbeddingClient? = null,
        /** 重排客户端（T-101 精排）；缺省用宿主上下文的配置 */
        rerank: com.stragent.core.ports.RerankClient? = null,
        /** 受众画像（T-106，PersonaRAG 先例）；空 = 不注入 */
        audience: String? = null,
        /** 查询分解器（T-113 隐式多跳检索侧）；缺省用宿主上下文的配置 */
        decomposer: com.stragent.core.ports.QueryDecomposer? = null,
        /** 块级上下文前缀（T-114）；缺省用宿主上下文的配置 */
        chunkContext: com.stragent.core.ports.ChunkContextClient? = null,
        /** 块摘要器（T-117）；缺省用宿主上下文的配置 */
        blockSummarizer: com.stragent.core.ports.BlockSummarizer? = null,
    ): CoreChatResult =
        engine.askFollowup(
            dataset, history, question,
            ctx?.modelResolver?.invoke(model) ?: (model ?: "default"),
            memory,
            semantic,
            cancelSignal = cancelSignal,
            embedding = embedding ?: ctx?.embedding,
            rerank = rerank ?: ctx?.rerank,
            audience = audience,
            decomposer = decomposer ?: ctx?.queryDecomposer,
            chunkContext = chunkContext ?: ctx?.chunkContext,
            blockSummarizer = blockSummarizer ?: ctx?.blockSummarizer,
        )

    // ---- 声明式路径（跨进程 / 外部 harness） ----
    override fun invoke(request: PluginRequest): PluginResponse = try {
        when (request.capability) {
            "manifest" -> PluginResponse(true, mapOf("manifest" to manifest.toJson().toString()))
            "capabilities" -> PluginResponse(true, mapOf("capabilities" to capabilities().map { it.id }))
            "list-plugins" -> PluginResponse(true, mapOf("plugins" to registryOf.list().map { it.id }))
            "report" -> {
                val ds = request.payload["dataset"] as? Dataset
                    ?: return PluginResponse(false, error = "report 需要 payload[\"dataset\"]: Dataset") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                val useWeb = (request.payload["useWeb"] as? Boolean) ?: false
                PluginResponse(true, mapOf("result" to generateInsight(ds, useWeb)))
            }
            "followup" -> {
                val ds = request.payload["dataset"] as? Dataset
                    ?: return PluginResponse(false, error = "followup 需要 payload[\"dataset\"]: Dataset") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                val question = request.payload["question"] as? String
                    ?: return PluginResponse(false, error = "followup 需要 payload[\"question\"]: String") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                val history = (request.payload["history"] as? List<*>)?.filterIsInstance<ChatTurn>() ?: emptyList()
                PluginResponse(true, mapOf("result" to askFollowup(ds, history, question)))
            }
            // T-179：review 缝派发补齐——对给定报告做**事后确定性复核**（verifyReport：未溯源
            // 数字/算式错误/关系失配/字段缺失四类硬检查），无 LLM、可复跑。缝文案里的语义面
            // （口径/因果/越界）走生成时门禁的语义审查；两者互补不互替（WIRING.md 已写口径）。
            "review" -> {
                val ds = request.payload["dataset"] as? Dataset
                    ?: return PluginResponse(false, error = "review 需要 payload[\"dataset\"]: Dataset") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                val report = request.payload["report"] as? String
                    ?: return PluginResponse(false, error = "review 需要 payload[\"report\"]: String（要复核的报告全文）") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                val v = com.stragent.core.verify.verifyReport(report, com.stragent.core.brief.buildDatasetBrief(ds))
                PluginResponse(true, mapOf(
                    "checked" to v.checked,
                    "briefNumbers" to v.briefNumbers,
                    "unsourced" to v.unsourced.map { "${it.value}（${it.kind}）@${it.context.take(40)}" },
                    "formula" to v.formula.map { "${it.value}（${it.kind}）@${it.context.take(40)}" },
                    "arithmetic" to v.arithmetic.map { "${it.claimed} 应为 ${it.expected} @${it.context.take(40)}" },  // lint:allow zh-first（复核结果结构化文案，与 review 分支同源）
                    "relations" to v.relations.map { "${it.claimed} 应为 ${it.expected} @${it.context.take(40)}" },  // lint:allow zh-first（复核结果结构化文案，与 review 分支同源）
                    "fields" to v.fields.map { "${it.field} @${it.context.take(40)}" },
                    "passed" to (v.unsourced.isEmpty() && v.formula.isEmpty() && v.arithmetic.isEmpty() &&
                        v.relations.isEmpty() && v.fields.isEmpty()),
                ))
            }
            else -> PluginResponse(false, error = "未知能力: ${request.capability}（先用 capabilities 查看）") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        }
    } catch (e: Exception) {
        PluginResponse(false, error = e.message ?: e.toString())
    }
}
