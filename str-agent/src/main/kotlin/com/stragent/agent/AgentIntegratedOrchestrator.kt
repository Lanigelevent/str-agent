package com.stragent.agent

import com.stragent.core.ai.ChatResult
import com.stragent.core.ai.InsightOrchestrator
import com.stragent.core.ai.InsightResult
import com.stragent.core.ai.AgentFindingCard
import com.stragent.core.ai.AgentChartCard
import com.stragent.core.caliber.CaliberNote
import com.stragent.core.dataset.Dataset
import com.stragent.core.observability.StructuredLogger
import com.stragent.core.ports.PrefsStore
import com.stragent.core.report.MAX_GATE_REVISIONS
import com.stragent.core.report.isStructuredReportType
import com.stragent.core.report.reviseReportWithLlm
import com.stragent.core.report.runReportGate

/**
 * Agent 集成编排器 —— 包装经典流水线（core 的 InsightOrchestrator）与
 * Agent 引擎（本模块的 AgentLoop / EditorialDept），按引擎选择分发。
 *
 * 为什么这个类在 str-agent 而不在 core：InsightOrchestrator（core）不应知道
 * agent 的存在（依赖方向 core ← str-agent）。本类是唯一的胶水层。
 *
 * 回落纪律：agent 路径失败 / 返回 null → 自动回落 classic，用户无感；
 * **取消不回落**——取消是客户端的明确意图，不是失败。agent 循环在可停点中止后
 * 抛 AgentCancelled 直接交还调用方，绝不能借「回落经典」把被取消的任务再跑一遍。
 */
class AgentIntegratedOrchestrator(
    private val classic: InsightOrchestrator,
    private val llm: com.stragent.core.ports.LlmClient,
    private val prefs: PrefsStore,
    private val plugins: PluginRegistry,
    private val search: com.stragent.core.ports.SearchClient? = null,
    /** 断点存储（T-050）：null = 不落盘（第三方宿主未提供目录时保持旧行为） */
    private val checkpoints: AgentCheckpointStore? = null,
    /** 报告引擎注册表（T-058）：kind → 引擎；新增引擎只注册，不改本类分发逻辑 */
    private val reportEngines: ReportEngineRegistry = ReportEngineRegistry.BUILT_IN,
    /**
     * 报告类型登记表（T-145）：宿主注册的自定义报告类型经此进入简报预算 / 章节模板 /
     * 目标字数。默认 `BUILT_IN` = 行为与本改动前逐字节一致。
     */
    private val reportTypes: com.stragent.core.report.ReportTypeRegistry =
        com.stragent.core.report.ReportTypeRegistry.BUILT_IN,
) {
    private val log = StructuredLogger.getLogger("AgentIntegratedOrchestrator")
    fun generateInsight(
        dataset: Dataset,
        useWeb: Boolean = false,
        caliberNotes: List<CaliberNote> = emptyList(),
        onStage: ((String, com.stragent.core.ai.ChapterProgress?) -> Unit)? = null,
        cancelSignal: () -> Boolean = { false },
        traceSink: ((com.stragent.core.trace.InsightTrace) -> Unit)? = null,
        onBody: ((com.stragent.core.ai.BodyDelta) -> Unit)? = null,
        memory: List<com.stragent.core.memory.MemoryFact> = emptyList(),
        semantic: List<com.stragent.core.semantic.SemanticTerm> = emptyList(),
        /** 指标定义（T-055）：brief 注入后报告可直接引用指标名与确定性数值 */
        metrics: List<com.stragent.core.semantic.MetricDef> = emptyList(),
        /** 上游链路（T-056：MCP `_meta.traceparent`）：报告 span 树挂到调用方 trace 下 */
        traceParent: com.stragent.core.observability.OtelSpanContext? = null,
        /** 每 1K token 估算单价（USD）：null = 未配置，span 上不写成本（不编造） */
        costPer1kTokens: Double? = null,
        /** T-105 按调用输出适配：prefs 键→值覆盖；空 = 零差异 */
        styleOverrides: Map<String, String> = emptyMap(),
    ): InsightResult {
        // T-105：按调用覆盖（空 = 原 prefs，逐字节零差异）
        val stylePrefs: PrefsStore = if (styleOverrides.isEmpty()) prefs else com.stragent.core.ports.PrefsOverlay(prefs, styleOverrides)
        val trace = com.stragent.core.trace.InsightTrace(
            dataset.name,
            System.currentTimeMillis(),
            parent = traceParent,
            costPer1kTokens = costPer1kTokens,
        )
        val wrappedOnStage: ((String, com.stragent.core.ai.ChapterProgress?) -> Unit)? = { stage, prog ->
            trace.mark(stage, prog?.let { "${it.done}/${it.total} ${it.title}" })
            onStage?.invoke(stage, prog)
        }
        val plugin = AgentEngine.selectedReportPlugin(stylePrefs, plugins, reportEngines)
        // 引擎解析是唯一分发点：注册表说能承接才走 agent 路径；不能承接 = 回落 classic。
        // 这里没有 `kind == "editorial"` 之类的分支——新增引擎不需要改这一行。
        // T-090/T-091：回落可见性在 classic 返回点统一处理（日志警告 + 回执标记，
        // 见下方 classicResult 构造处）——分派点不告警，避免一处回落两处响。
        val engine = plugin?.let { reportEngines.create(it, ReportEngineRegistry.Deps(llm, stylePrefs, reportTypes)) }
        if (plugin != null && engine != null) {
            // agent 引擎的正文是「finish 工具参数里的整篇 JSON 串」，不是逐 token 的正文流：
            // 本路径只发阶段帧（agent/tool），不发正文帧 —— 宁可少一路，也不编造增量。
            // 回落 classic 后（下面）正文帧照常，口径与经典路径一致。
            wrappedOnStage?.invoke("agent", com.stragent.core.ai.ChapterProgress(0, 0, plugin.name))
            val brief = com.stragent.core.brief.buildDatasetBrief(dataset, stylePrefs, metrics, reportTypes)
            val typeId = com.stragent.core.report.ReportLib.getReportType(stylePrefs).id
            val styleLength = com.stragent.core.report.ReportLib.getReportStyle(stylePrefs).length
            val stats = com.stragent.core.dataset.summarize(dataset)
            val numericColumns = stats.count {
                it.type == com.stragent.core.csv.ColumnType.NUMBER && !com.stragent.core.dataset.looksLikeIdColumn(it.name)
            }
            val groupColumns = stats.count {
                it.type == com.stragent.core.csv.ColumnType.TEXT && !com.stragent.core.dataset.looksLikeIdColumn(it.name) &&
                    (it.distinct ?: 0) >= 2 && (it.distinct ?: 0) <= 40
            }
            val target = com.stragent.core.report.targetLengthFor(
                brief, dataset.rows.size, dataset.columns.size, numericColumns, groupColumns,
                styleLength, typeId, reportTypes,
            )
            // 任务级幂等键（T-050）：同一份数据 + 同一个插件/类型/篇幅 → 同一组断点。
            // 崩溃后续跑已完成的阶段；成功过门禁或回落经典后整组清理。
            val scope = checkpoints?.forRun(AgentCheckpointStore.runKey(dataset, plugin, typeId, target.chars))
            val result: InsightResult? = runCatching {
                engine.generate(
                    ReportEngineRequest(
                        dataset = dataset,
                        plugin = plugin,
                        brief = brief,
                        target = target,
                        typeId = typeId,
                        caliberNotes = caliberNotes,
                        onStage = wrappedOnStage,
                        cancelSignal = cancelSignal,
                        memory = memory,
                        semantic = semantic,
                        metrics = metrics,
                        search = if (useWeb) search else null,
                        checkpoint = scope?.stage("report") ?: AgentCheckpoint.NONE,
                        checkpoints = scope,
                    ),
                )
            }.getOrNull()
            // 取消不算「agent 失败」：不能再回落经典（经典入口会立刻再抛一次取消）
            if (result == null && cancelSignal()) throw AgentCancelled()
            if (result != null) {
                // ---- 发稿门禁（T-044）：agent / editorial 出口与经典路径同一套确定性检查 ----
                // 退稿预算由引擎声明：编辑部内部已用满 2 轮 → 0；单 agent → 满额
                val gated = gateAgentReport(result, brief, target, typeId, plugin, engine.gateRevisionBudget, wrappedOnStage, cancelSignal)
                // 交付已完成：断点全部清理，下一次同一任务重新生成而不是回放旧稿
                scope?.clearAll()
                trace.finish(gated.engineId, gated.totalTokens, gated.autonomous)
                traceSink?.invoke(trace)
                return gated
            }
            // 回落经典：agent 的中间状态不再有机会接续，一并清理
            scope?.clearAll()
            wrappedOnStage?.invoke("analyzing", null)
        }
        val classicResult = classic.generateInsight(
            dataset, useWeb, caliberNotes, wrappedOnStage,
            cancelSignal = cancelSignal,
            onBody = onBody,
            memory = memory,
            semantic = semantic,
            metrics = metrics,
            styleOverrides = styleOverrides,
        )
        // T-091：显式选择未被承接时的回落，标记随结果送达客户端（不只服务端日志）
        val selected = AgentEngine.getEngineId(stylePrefs)
        val fallback = selected != AgentEngine.CLASSIC && plugin == null
        val reason = if (fallback) {
            plugins.get(selected)
                ?.let { "插件「${it.name}」的 kind=${it.kind} 不被引擎注册表支持，已按经典流水线生成" } // lint:allow 回执文案经 i18n 例外面（详见 D7 T-091 边界段）
                ?: "所选引擎插件不存在（可能已被删除），已按经典流水线生成" // lint:allow 同上
        } else {
            null
        }
        if (fallback) {
            log.warn("报告引擎回落 classic——所选引擎没有可用承接", "selected" to selected, "reason" to reason) // lint:allow 运维日志面文案（StructuredLogger 输出），非客户端 UI 回执
        }
        trace.finish(classicResult.engineId, classicResult.totalTokens, classicResult.autonomous)
        traceSink?.invoke(trace)
        return classicResult.copy(engineFallback = fallback, engineFallbackReason = reason)
    }

    /**
     * agent / editorial 报告出口的门禁（T-044）：与经典路径同一个 `runReportGate`。
     *
     * 预算由 [ReportEngine.gateRevisionBudget] 声明——编辑部内部已退稿 ≤2 轮，外层用 0
     * （只评估、标记，不重复花钱）；单 agent 没有内部退稿，外层用满 [MAX_GATE_REVISIONS]。
     */
    private fun gateAgentReport(
        base: InsightResult,
        brief: String,
        target: com.stragent.core.report.TargetLength,
        typeId: String,
        plugin: PluginManifest,
        maxRevisions: Int,
        onStage: ((String, com.stragent.core.ai.ChapterProgress?) -> Unit)?,
        cancelSignal: () -> Boolean,
    ): InsightResult {
        val outcome = runReportGate(
            report = base.report,
            brief = brief,
            targetChars = target.chars,
            structured = isStructuredReportType(typeId),
            findings = base.agentFindings,
            maxRevisions = maxRevisions,
            cancelSignal = cancelSignal,
            onRound = { round, hard ->
                onStage?.invoke("发稿门禁（第 $round 轮修订：$hard 处硬伤）", null)
            },
        ) { issues, current, _ ->
            reviseReportWithLlm(
                llm = llm,
                model = plugin.model ?: "default",
                brief = brief,
                currentReport = current,
                issues = issues,
                targetChars = target.chars,
                cancelSignal = cancelSignal,
            )
        }
        val lines = outcome.issues.map { it.line }
        return base.copy(
            report = outcome.report,
            totalTokens = base.totalTokens + outcome.totalTokens,
            truncated = base.truncated || outcome.truncated,
            actualChars = com.stragent.core.ai.countChars(outcome.report),
            qualityNotes = lines,
            gateFailed = outcome.failed,
            gateIssues = lines,
            gateRevisions = base.gateRevisions + outcome.revisions,
        )
    }

    fun askFollowup(
        dataset: Dataset,
        history: List<com.stragent.core.ai.ChatTurn>,
        question: String,
        model: String,
        memory: List<com.stragent.core.memory.MemoryFact> = emptyList(),
        semantic: List<com.stragent.core.semantic.SemanticTerm> = emptyList(),
        /** 协作式取消信号（MCP `notifications/cancelled` 等）：默认恒 false，零行为差异 */
        cancelSignal: () -> Boolean = { false },
        /** 嵌入客户端（T-100 hybrid 检索）；null = BM25 单路既有行为 */
        embedding: com.stragent.core.ports.EmbeddingClient? = null,
        /** 重排客户端（T-101 精排）；null = 融合序直出 */
        rerank: com.stragent.core.ports.RerankClient? = null,
        /** 受众画像（T-106，PersonaRAG 先例）；空 = 不注入 */
        audience: String? = null,
        /** 查询分解器（T-113 隐式多跳检索侧）；null = 单查询既有行为（经典回落路径才生效） */
        decomposer: com.stragent.core.ports.QueryDecomposer? = null,
        /** 块级上下文前缀（T-114）；null = 段号前缀既有行为（经典回落路径才生效） */
        chunkContext: com.stragent.core.ports.ChunkContextClient? = null,
        /** 块摘要器（T-117）；null = 粗层原块文本既有行为（经典回落路径才生效） */
        blockSummarizer: com.stragent.core.ports.BlockSummarizer? = null,
    ): ChatResult {
        val plugin = com.stragent.agent.AgentEngine.selectedFollowupPlugin(prefs, plugins)
        if (plugin != null) {
            val outcome = runCatching {
                askFollowupByAgent(dataset, history, question, model, plugin, memory, semantic, cancelSignal)
            }.getOrNull()
            if (outcome != null) return outcome
            // 取消不回落：agent 路径因取消而空手时，经典路径再跑一遍等于取消失效
            if (cancelSignal()) throw AgentCancelled()
            // 回落经典追问
        }
        return com.stragent.core.ai.askFollowup(
            llm, prefs, dataset, history, question, model, memory, semantic,
            cancelSignal = cancelSignal,
            embedding = embedding,
            rerank = rerank,
            audience = audience,
            decomposer = decomposer,
            chunkContext = chunkContext,
            blockSummarizer = blockSummarizer,
        )
    }

    /** 追问的 agent 路径：带查数工具的循环，finish.args.answer 即回答（仅 str-agent 模块可见） */
    private fun askFollowupByAgent(
        dataset: Dataset,
        history: List<com.stragent.core.ai.ChatTurn>,
        question: String,
        fallbackModel: String,
        plugin: com.stragent.agent.PluginManifest,
        memory: List<com.stragent.core.memory.MemoryFact> = emptyList(),
        semantic: List<com.stragent.core.semantic.SemanticTerm> = emptyList(),
        cancelSignal: () -> Boolean = { false },
    ): ChatResult? {
        val brief = com.stragent.core.brief.buildDatasetBrief(dataset, prefs)
        val trimmed = question.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("请输入问题")
        // 首轮报告永远保留（与经典路径同口径的裁剪）
        val (report, rest) = if (history.isEmpty()) null to emptyList() else history.first() to history.drop(1)
        val budget = com.stragent.core.ai.MAX_FOLLOWUP_TURNS - 1
        val evenBudget = budget - (budget % 2)
        val recent = if (rest.size > evenBudget) rest.takeLast(evenBudget) else rest

        val registry = com.stragent.agent.ToolRegistry.of(
            buildAgentTools(dataset, brief, search = null)
                .filter { tool -> plugin.tools?.contains(tool.name) ?: true },
        )
        val task = buildString {
            append("<task>回答用户对这份数据的追问：$trimmed</task>\n\n")
            append("<data_brief>\n$brief\n</data_brief>\n")
            if (report != null) {
                append("\n<first_report>\n${com.stragent.core.ai.fitForFollowup(report.content)}\n</first_report>\n")
            }
            if (recent.isNotEmpty()) {
                append("\n<recent_dialogue>\n" + recent.joinToString("\n") { "${it.role}: ${it.content.take(400)}" } + "\n</recent_dialogue>\n")
            }
            // 跨会话记忆独立成段（core/memory 纪律 a：绝不并进 data_brief，否则上一轮的
            // 随口数字会被当成「本次可引用的统计值」）。agent 路径同样要带——
            // 只给 classic 加就变成「换了引擎就失忆」。
            com.stragent.core.memory.memoryBlock(memory)?.let { append("\n$it\n") }
            // 
            com.stragent.core.semantic.semanticBlock(semantic)?.let { append("\n$it\n") }
            // 意图澄清闸（round-32）：悬空指代/复合请求/泛问命中时，强制「选定解读 +
            // 被拒备选 + 假设清单 + 拆解 + 单问」纪律，不猜着答。无信号不注入。
            IntentClarifier.analyze(trimmed).gateBlock()?.let { append("\n$it\n") }
            append("\nfinish.args.answer = 回答正文（两三句话，纯文本）。")
        }
        val outcome = com.stragent.agent.AgentLoop(llm).run(
            plugin, registry, plugin.model ?: fallbackModel, task, com.stragent.agent.AGENT_HARNESS_CONTRACT,
            cancelSignal = cancelSignal,
        ) ?: return null
        val answer = outcome.output["answer"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
            ?: return null
        if (answer.isBlank()) return null
        // ---- 追问门禁（T-044）：与经典追问同一口径——记录级，不改写回答 ----
        val gate = com.stragent.core.report.assessAnswer(answer.trim(), brief)
        return ChatResult(
            content = answer.trim(),
            totalTokens = outcome.totalTokens,
            gateFailed = gate.failed,
            gateIssues = gate.issues.map { it.line },
        )
    }
}
