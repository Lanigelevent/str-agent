package com.stragent.host

import com.stragent.core.ports.AgentTool
import com.stragent.agent.plugin.StrAgentPlugin
import com.stragent.core.observability.StrAgentMetrics
import com.stragent.data.AbilityDatasets
import com.stragent.data.McpRequestCancellation
import com.stragent.data.StoredChart
import com.stragent.core.trace.stepIndicesMentioning
import com.stragent.core.report.ReportLanguage
import com.stragent.core.report.ReportLib
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 把插件能力包成 MCP 工具 —— 让**无头宿主**也具备「生成报告 / 追问」这两条主链路。
 *
 * 为什么要补这一层：
 *   MCP 桥原本只暴露四条数据契约（import_table / column_profile / group_aggregate /
 *   list_rows），那是「把表交给外部 agent 去分析」的路；**报告生成**（agent-report）
 *   与**追问**（agent-followup）才是 Str Agent 自己的看家本领。以前它们只在 App UI 里可达，
 *   去掉 App 后若无这一层，纯插件形态下最核心的能力对外就断了。
 *
 * 口径纪律：
 *   - 工具语义直接走插件的 `generateInsight` / `askFollowup`（同一条代码路径，不另写实现）；
 *   - 数据集用 `stragent_import_table` 返回的 datasetId 定位（会话内存，与能力契约一致）；
 *   - 报告经引擎无关的发稿门禁（T-044：verify → revise≤2 → 写作审计 / 语义审校），
 *     回执恒带 `gateFailed` / `gateIssues` / `gateRevisions`——过了闸与「没这路信息」是两种事实。
 *
 * **跨会话记忆（T-008）**：`memory` 给了就接上三条动作——
 *   ① 追问前按 datasetId 召回上次会话的产出，随参数进提示词（数据一改指纹就变，召不回来）；
 *   ② 报告与追问各自记一条事实（存的是原文截断，不做提炼）；
 *   ③ 多一个只读工具 `stragent_recall_memory`，让外部 agent 自己看这份数据以前聊过什么。
 * 不给 `memory` 时三条全不生效，行为与本改动前逐字一致（宿主可以选择不接这层）。
 *
 * **指标目录（T-055）**：`metrics` 给了就先把用户自定义指标与内置指标合流
 * （[MetricEngine.resolveCatalog]：内置优先、同口径去重、冲突剔除并留痕），
 * 再把可编译的 `MetricDef` 交给简报层——报告里的核心数字按指标口径算，
 * 引用时写指标名而不是现编口径。不给时简报与本改动前逐字节一致。
 */
/**
 * T-067：数据面注入信号——数据集 + 召回记忆 + 语义术语一起扫。
 * 纪律：命中 ≠ 拦截（数据仍是数据，统计与白名单零改动），只做回执可见性。
 */
private fun collectInjectionSignals(
    dataset: com.stragent.core.dataset.Dataset,
    recalled: List<com.stragent.core.memory.MemoryFact>,
    semanticTerms: List<com.stragent.core.semantic.SemanticTerm>,
): List<com.stragent.core.security.InjectionSignal> = buildList {
    addAll(com.stragent.core.security.PromptInjectionHeuristics.scanDataset(dataset).signals)
    recalled.forEachIndexed { index, fact ->
        addAll(com.stragent.core.security.PromptInjectionHeuristics.scanText(fact.text, "记忆[$index]"))
    }
    semanticTerms.forEach { term ->
        addAll(com.stragent.core.security.PromptInjectionHeuristics.scanText(term.name, "语义术语[${term.name.take(20)}]"))
        addAll(com.stragent.core.security.PromptInjectionHeuristics.scanText(term.definition, "语义术语定义[${term.name.take(20)}]"))
    }
}

/** 信号 → 回执字段（紧凑字符串：`R2(系统标记伪装)@第5行[列:备注]: sample…`；只带前 10 条） */
private fun injectionSignalsJson(signals: List<com.stragent.core.security.InjectionSignal>): JsonArray =
    JsonArray(
        signals.take(10).map { signal ->
            JsonPrimitive("${signal.rule}(${signal.ruleName})@${signal.location}: ${signal.sample}")
        },
    )

fun pluginTools(
    plugin: StrAgentPlugin,
    datasets: AbilityDatasets,
    memory: com.stragent.data.SessionMemoryStore? = null,
    semantic: com.stragent.data.SemanticStore? = null,
    metrics: com.stragent.data.MetricStore? = null,
    /** OTel 导出器（T-056）：非 null 时报告 span 树随工具调用上报；null = 只录制不导出 */
    otel: com.stragent.core.observability.OtelTraces? = null,
    /** 每 1K token 估算单价（USD）：null = span 上不写成本（不编造） */
    costPer1kTokens: Double? = null,
    /** OTel metrics 注册表（T-065）：非 null 时报告调用数/时延/token 计数随生成入注册表 */
    otelMetrics: com.stragent.core.observability.OtelMetrics? = null,
    /** 最近报告环形缓冲（T-070）：非 null 时每次报告入表，/ui 驾驶舱数据源 */
    recentReports: RecentReports? = null,
    /**
     * 溯源留痕器（T-073）：非 null 时追问回执带 provenanceRef（引用数字 ↔ 留痕步骤对拍）；null = 不归因
     */
    provenance: com.stragent.data.ProvenanceRecorder? = null,
    /** 嵌入客户端（T-100）：非 null 时文本追问检索走 hybrid（BM25+语义 RRF）；null = BM25 单路既有行为 */
    embedding: com.stragent.core.ports.EmbeddingClient? = null,
    /** 重排客户端（T-101）：非 null 时 hybrid 召回后精排取 top-K；null = 融合序直出 */
    rerank: com.stragent.core.ports.RerankClient? = null,
    /** 查询分解器（T-113 隐式多跳检索侧）：非 null 且分解出 ≥2 子查询时逐子查询检索、RRF 合并 */
    decomposer: com.stragent.core.ports.QueryDecomposer? = null,
    /** 块级上下文前缀（T-114）：非 null 时检索索引文本带 LLM 上下文补语（索引专用） */
    chunkContext: com.stragent.core.ports.ChunkContextClient? = null,
    /** 块摘要器（T-117）：非 null 时粗层索引文本用 LLM 摘要（索引专用） */
    blockSummarizer: com.stragent.core.ports.BlockSummarizer? = null,
    /** 偏好存储（T-107）：非 null 时启用已存名预设（expandProfile 查询与 stragent_save_profile 落库） */
    prefs: com.stragent.core.ports.PrefsStore? = null,
    /** 用量与成本账本（T-171）：非 null 时报告用量入账并在回执里给出用量/成本块 */
    usageLedger: com.stragent.core.observability.UsageLedger? = null,
): List<AgentTool> = listOfNotNull(
    generateReportTool(plugin, datasets, memory, semantic, metrics, otel, costPer1kTokens, otelMetrics, recentReports, prefs, usageLedger),
    askFollowupTool(plugin, datasets, memory, semantic, provenance, embedding, rerank, decomposer, chunkContext, blockSummarizer),
    recallMemoryTool(datasets, memory),
    saveProfileTool(prefs),
    listProfilesTool(prefs),
)

private fun generateReportTool(
    plugin: StrAgentPlugin,
    datasets: AbilityDatasets,
    memory: com.stragent.data.SessionMemoryStore?,
    semantic: com.stragent.data.SemanticStore?,
    metricStore: com.stragent.data.MetricStore?,
    otel: com.stragent.core.observability.OtelTraces? = null,
    costPer1kTokens: Double? = null,
    otelMetrics: com.stragent.core.observability.OtelMetrics? = null,
    recentReports: RecentReports? = null,
    prefs: com.stragent.core.ports.PrefsStore? = null,
    usageLedger: com.stragent.core.observability.UsageLedger? = null,
): AgentTool =
    object : AgentTool {
        override val name = "stragent_generate_report"
        override val description =
            "基于已导入的数据集生成可信中文分析报告：章节化结构 + 每个数字可回溯到原始行。" +
            "先用 stragent_import_table 导入表格并取回 datasetId，再调用本工具。"
        override val parametersSchema = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(
                        "datasetId",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "stragent_import_table 返回的 datasetId")
                        },
                    )
                    put(
                        "useWeb",
                        buildJsonObject {
                            put("type", "boolean")
                            put("description", "是否联网检索（默认 false；联网会增加耗时与不确定性）")
                        },
                    )
                    put(
                        "reportType",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "报告类型覆盖（T-105，可选）：standard/executive/bulletin/deepdive/compare/anomaly/trend/quality；缺省用偏好设置") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                        },
                    )
                    put(
                        "style",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "文风覆盖（T-105，可选）：professional/concise/indepth/executive/friendly/teaching/detective/news；缺省用偏好设置") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                        },
                    )
                    put(
                        "industry",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "行业框架覆盖（T-105，可选）：general/finance/banking/securities/ecommerce/civil 等 47 项 id；缺省用偏好设置") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                        },
                    )
                    put(
                        "depth",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "详细程度覆盖（T-105，可选）：brief（篇幅减半）/standard（默认）/deep（篇幅 1.75 倍且分章更细）；仅篇幅轴，与报告类型正交") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                        },
                    )
                    put(
                        "audience",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "受众画像（T-105，可选，≤300 字）：写给谁（如「施工方项目经理，非监测专业」），术语深度按此校准") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                        },
                    )
                    put(
                        "requirements",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "附加输出要求（T-105，可选，≤2000 字）：总结口径等自由要求（如「只写风险与遗留事项」）；优先级高于类型/行业/文风，但不得突破零编造与数字白名单硬约束") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                        },
                    )
                    put(
                        "language",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "报告输出语言档位（T-173，可选）：zh（缺省，中文）/ en（英文）。切换语言时写作纪律、结构规范与写作审计的词表同步切换；数据里的列名与专有名词保留原样。") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                        },
                    )
                    put(
                        "profile",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "预设 bundle（T-106，可选）：内联 JSON 对象一次给出多参（如 {\"depth\":\"deep\",\"audience\":\"施工方项目经理\",\"requirements\":\"只写风险\"}）；显式参数优先于 bundle 值；bundle 值同样过校验") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                        },
                    )
                },
            )
            put("required", JsonArray(listOf(JsonPrimitive("datasetId"))))
        }

        // 报告生成有副作用（调用模型、耗时与 token），不进缓存
        override val cacheable = false
        // 调模型 + 落记忆/断点：不是只读，也不是封闭世界
        override val readOnly = false
        override val openWorld = true

        override fun invoke(args: JsonObject): JsonObject {
            val datasetId = args.stringArg("datasetId")
                ?: throw IllegalArgumentException("datasetId 不能为空（先用 stragent_import_table 导入）")
            val useWeb = args.stringArg("useWeb")?.toBooleanStrictOrNull() ?: false
            fun requireValid(field: String, id: String, ids: List<String>) {
                require(id in ids) { "$field 无效：$id（合法值：${ids.joinToString("/")}）" } // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            }
            // T-106 预设 bundle（Langfuse prompt 组合先例）：内联 JSON 展开；显式参数优先（后 put 覆盖）。
            // profile 值同样过枚举/长度校验——overlay 不静默回退非法 id。
            val profileBase = args.stringArg("profile")?.takeIf { it.isNotBlank() }?.let { p ->
                val bundle = prefs?.let { ReportLib.expandProfile(it, p) } ?: ReportLib.parseProfileBundle(p)
                    ?: throw IllegalArgumentException("profile 无效：${p.take(80)}（已存名不存在且不是合法 JSON bundle）") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                ReportLib.requireValidProfileBundle(bundle)
                bundle
            } ?: emptyMap()
            // T-105 按调用输出适配：枚举校验（无效 id 中文报错列合法值）+ prefs 覆盖 map（空 = 偏好设置，零差异）
            val styleOverrides = buildMap {
                profileBase.forEach { (k, v) -> put(k, v) }
                args.stringArg("reportType")?.takeIf { it.isNotBlank() }?.let {
                    requireValid("reportType", it, ReportLib.reportTypes.map { x -> x.id })
                    put(ReportLib.TYPE_PREF_KEY, it)
                }
                args.stringArg("style")?.takeIf { it.isNotBlank() }?.let {
                    requireValid("style", it, ReportLib.reportStyles.map { x -> x.id })
                    put(ReportLib.STYLE_PREF_KEY, it)
                }
                args.stringArg("industry")?.takeIf { it.isNotBlank() }?.let {
                    requireValid("industry", it, ReportLib.industries.map { x -> x.id })
                    put(ReportLib.INDUSTRY_PREF_KEY, it)
                }
                args.stringArg("depth")?.takeIf { it.isNotBlank() }?.let {
                    requireValid("depth", it, ReportLib.depths)
                    put(ReportLib.DEPTH_PREF_KEY, it)
                }
                args.stringArg("audience")?.takeIf { it.isNotBlank() }?.let {
                    require(it.length <= 300) { "audience 过长：${it.length} 字（上限 300）" } // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                    put(ReportLib.AUDIENCE_PREF_KEY, it)
                }
                args.stringArg("requirements")?.takeIf { it.isNotBlank() }?.let {
                    require(it.length <= 2000) { "requirements 过长：${it.length} 字（上限 2000）" } // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                    put(ReportLib.REQ_PREF_KEY, it)
                }
                // T-173：语言档位（zh 缺省 / en）——写进同一张适配 map，
                // 提示词与写作审计按它取对应语言的词表与结构块标记。
                args.stringArg("language")?.takeIf { it.isNotBlank() }?.let { raw ->
                    val normalized = raw.trim().lowercase()
                    require(normalized in setOf("zh", "en", "en-us", "en-gb", "english")) {
                        "language 无效：$raw（合法值：zh / en）" // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                    }
                    put(ReportLanguage.PREF_KEY, ReportLanguage.from(raw).id)
                }
            }
            // T-028：首轮报告也带记忆——召回（按内容指纹）在前，生成后落「本轮产出」。
            // 召回失败不阻塞生成（记忆是增强，不是依赖），与追问路径同纪律。
            val recalled = memory?.recall(datasetId) ?: emptyList()
            // T-033：语义层召回——跨数据集指标定义，不按指纹失效。
            // 全部召回（按列名匹配在 core 层做），与记忆同纪律：缺失不阻塞生成。
            val semanticTerms = semantic?.listTerms() ?: emptyList()
            // T-055：指标目录——内置 + 用户自定义合流；冲突项在写入口已拒，
            // 这里再兜一次（旧数据 / 手工改 kv 也能挡住「同一指标两个数」）。
            val catalog = com.stragent.core.semantic.MetricEngine.resolveCatalog(
                metricStore?.list() ?: emptyList(),
            )
            // T-054：大文件抽样数据集——报告是「样本口径」，必须把指纹与步长随回包交出去
            val dataset = datasets.mustDataset(datasetId)
            // T-056 链路：传输层把上游 `_meta.traceparent` 放进线程上下文（McpTraceContext），
            // 这里把它作为报告根 span 的父上下文；导出的 span 树于是与调用方的 trace 连成一片。
            // traceId 取自同一份 trace，随回包交出，客户端能据此在 Jaeger/Tempo 里对上号。
            var traceId: String? = null
            // T-081：时长走单调时钟（墙钟差值亚刻度量化为 0、可被 NTP 回拨为负）
            val startedNanos = System.nanoTime()
            val result = plugin.generateInsight(
                dataset, useWeb,
                memory = recalled,
                semantic = semanticTerms,
                metrics = catalog.metrics,
                // MCP 取消：客户端发 notifications/cancelled 后，引擎在下一个章/步边界退出
                cancelSignal = McpRequestCancellation.signal(),
                traceParent = com.stragent.data.McpTraceContext.current(),
                traceSink = { trace ->
                    traceId = trace.traceId
                    otel?.export(trace)
                    // T-065 metrics：报告调用数/时延/token 按 engine.id 记入注册表
                    //（与 span 导出相互独立；未配置注册表 = 不记，零成本）
                    otelMetrics?.let { m ->
                        val engineAttrs = mapOf("engine.id" to (trace.engineId ?: "unknown"))
                        m.counter(StrAgentMetrics.REPORT_CALLS).add(1, engineAttrs)
                        m.histogram(StrAgentMetrics.REPORT_DURATION).record(trace.durationMs().toDouble(), engineAttrs)
                        m.counter(StrAgentMetrics.REPORT_TOKENS).add(trace.totalTokens.toLong(), engineAttrs)
                    }
                },
                costPer1kTokens = costPer1kTokens,
                styleOverrides = styleOverrides,
            )
            // 记一条「这份数据出过什么报告」：存的是报告开头原文截断（core 归一），
            // 不做提炼——提炼是理解，理解就会错，而错的理解下一轮会被当成事实继续用。
            memory?.remember(datasetId, "report", "首轮报告：" + result.report)
            // T-070：入最近报告环形缓冲（/ui 驾驶舱数据源；纯内存窗口，失败不阻塞回执）
            // D25 可观测性收口（gap-3）：门禁命中计数——attributes 收敛在 engine×gate 两维（series 有界）。
            // 此前 gateFailed 只进单次回执，AD-028 的核心质量信号无法告警或看趋势。
            otelMetrics?.counter(StrAgentMetrics.GATE_RESULTS)
                ?.add(1, mapOf("engine.id" to (result.engineId ?: "unknown"), "gate.passed" to "${!result.gateFailed}"))
            recentReports?.record(
                RecentReport(
                    atMs = System.currentTimeMillis(),
                    datasetId = datasetId,
                    datasetName = dataset.name,
                    engineId = result.engineId,
                    totalTokens = result.totalTokens,
                    gateFailed = result.gateFailed,
                    traceId = traceId,
                    reportText = result.report,
                    durationMs = (System.nanoTime() - startedNanos) / 1_000_000,
                    // T-086：报告链产出的图表数据随条目入环（/ui 报告视图渲染离线 SVG；
                    // AgentChartCard.data 是 (标签, 值) 对，展平成 StoredChart 的 labels/values）
                    charts = result.agentCharts.map { card ->
                        StoredChart(
                            type = card.type,
                            title = card.title,
                            labels = card.data.map { it.first },
                            values = card.data.map { it.second },
                            description = card.description,
                        )
                    },
                ),
            )
            return buildJsonObject {
                put("report", result.report)
                put("engineId", result.engineId)
                // T-171：用量与成本（估算，basis 明写；未配单价时只给 token 数，不编造金额）
                usageLedger?.let { ledger ->
                    ledger.record("stragent_generate_report", result.totalTokens, result.engineId)
                    put("usage", ledger.usageBlock("stragent_generate_report", result.totalTokens, result.engineId))
                }
                // T-056：本次生成的 trace id（与 OTel 导出的是同一条链）；
                // 客户端给了 `_meta.traceparent` 时与上游 trace 相同，便于在 Jaeger/Tempo 里对上
                traceId?.let { put("traceId", it) }
                put("usedWeb", result.usedWeb)
                put("truncated", result.truncated)
                put("totalTokens", result.totalTokens)
                put("actualChars", result.actualChars)
                put("targetChars", result.targetChars)  // lint:allow zh-first（注释中文规则盲区）——T-096：目标篇幅随回执
                // T-108 生效配置回显（恒输出）：调用方要能分辨「参数生效了」与「走了偏好缺省」
                put(
                    "outputProfile",
                    buildJsonObject {
                        args.stringArg("profile")?.takeIf { it.isNotBlank() }?.let { put("profile", it) }
                        put("overrides", buildJsonObject { styleOverrides.forEach { (k, v) -> put(k, v) } })
                    },
                )
                put("semanticIssues", result.semantic.size)
                put("memoryFactsUsed", recalled.size)
                put("semanticTermsUsed", semanticTerms.size)
                // T-067：数据面注入信号（数据集+记忆+语义术语；命中不拦截，只做可见性）
                put("injectionSignals", injectionSignalsJson(collectInjectionSignals(dataset, recalled, semanticTerms)))
                // T-055：指标目录规模与冲突数随回包交出（0 表示未接或没有用户指标）；
                // 具体哪几个指标在本次数据上可编译，由简报层的「指标口径」段体现。
                put("metricsAvailable", catalog.metrics.size)
                put("metricConflicts", catalog.conflicts.size)
                // T-032 写作审计注记（软提示：缺结构块/篇幅偏离；硬项已走编辑部退稿循环）。
                // 空数组也输出——「审过且干净」与「没有这路信息」是两种事实，宿主要能区分。
                put(
                    "qualityNotes",
                    JsonArray(result.qualityNotes.map { JsonPrimitive(it) }),
                )
                // T-044 发稿门禁记录（恒输出）：gateFailed=true 表示硬问题经 ≤2 轮修订仍未清零，
                // 报告照发但**不静默放行**；gateIssues 是终稿的全量问题清单（含软问题）。
                put("gateFailed", result.gateFailed)
                put("gateIssues", JsonArray(result.gateIssues.map { JsonPrimitive(it) }))
                put("gateRevisions", result.gateRevisions)
                // T-091：引擎回落标记恒输出（T-032 同纪律）——用户所选引擎未被承接、
                // 实际按经典流水线生成时如实可见，原因随 reason 交出
                put("engineFallback", result.engineFallback)
                result.engineFallbackReason?.let { put("engineFallbackReason", it) }
                // T-089（T-086 边界段登记项）：报告链建议的图表随回执交出——
                // 客户端可自行渲染（数据形状 = StoredChart 同构：labels/values 对齐）。
                // 空数组恒输出：「报告无图表」与「回执没带图表」是两种事实（T-032 同纪律）。
                put(
                    "charts",
                    JsonArray(
                        result.agentCharts.map { card ->
                            buildJsonObject {
                                put("type", card.type)
                                put("title", card.title)
                                if (card.description.isNotEmpty()) put("description", card.description)
                                put("labels", JsonArray(card.data.map { JsonPrimitive(it.first) }))
                                put("values", JsonArray(card.data.map { JsonPrimitive(it.second) }))
                            }
                        },
                    ),
                )
                dataset.sampling?.let { put("sampling", samplingJson(it, dataset.rows.size)) }
            }
        }
    }

/**
 * `stragent_save_profile`（T-107）：落库命名预设 bundle（Langfuse name+label 完整形态）。
 * bundle 校验通过才写 prefs；此后 generate_report 的 profile 参数直接传名即可展开。
 * prefs 未接线时工具不注册（诚实边界：无存储就没有持久化）。
 */
private fun saveProfileTool(prefs: com.stragent.core.ports.PrefsStore?): AgentTool? {
    if (prefs == null) return null
    return object : AgentTool {
        override val name = "stragent_save_profile"
        override val description =
            "保存命名输出预设（T-107）：bundle 校验通过后落库，此后 stragent_generate_report 的 profile 参数传名即可。" // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        override val parametersSchema = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(
                        "name",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "预设名（字母/数字/下划线/连字符/中文，≤64 字符）") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                        },
                    )
                    put(
                        "bundle",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "预设 JSON：{\"reportType\":..,\"style\":..,\"industry\":..,\"depth\":..,\"audience\":..,\"requirements\":..}（键均可选，值过校验）") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                        },
                    )
                },
            )
            put("required", JsonArray(listOf(JsonPrimitive("name"), JsonPrimitive("bundle"))))
        }

        override val cacheable = false
        override val readOnly = false
        override val openWorld = false

        override fun invoke(args: JsonObject): JsonObject {
            val name = args.stringArg("name")?.trim()
                ?: throw IllegalArgumentException("name 不能为空") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            val bundle = args.stringArg("bundle")
                ?: throw IllegalArgumentException("bundle 不能为空") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            com.stragent.core.report.ReportLib.saveProfile(prefs, name, bundle)
            return buildJsonObject {
                put("saved", name)
                put("usage", "stragent_generate_report 传 profile=\"$name\" 即可展开该预设") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            }
        }
    }
}

/**
 * `stragent_list_profiles`（T-108）：列出已存命名预设（只读；recall_memory/list_semantic_terms 同纪律——
 * 注册表要可发现，外部 agent 才能在 profile 参数传名）。prefs 未接线时不注册。
 */
private fun listProfilesTool(prefs: com.stragent.core.ports.PrefsStore?): AgentTool? {
    if (prefs == null) return null
    return object : AgentTool {
        override val name = "stragent_list_profiles"
        override val description =
            "列出已保存的命名输出预设（T-108，只读）：返回每个预设的 name 与 bundle，可把 name 传给 stragent_generate_report 的 profile 参数。" // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        override val parametersSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {})
            put("required", JsonArray(emptyList()))
        }

        override val cacheable = false
        override val readOnly = true
        override val openWorld = false

        override fun invoke(args: JsonObject): JsonObject {
            val profiles = com.stragent.core.report.ReportLib.listProfiles(prefs)
            return buildJsonObject {
                put("count", profiles.size)
                put(
                    "profiles",
                    JsonArray(
                        profiles.map { (name, bundle) ->
                            buildJsonObject { put("name", name); put("bundle", bundle) }
                        },
                    ),
                )
                // 恒输出：「没有已存预设」与「回执没带清单」是两种事实（T-032 纪律）
                if (profiles.isEmpty()) {
                    put("notice", "尚无已存预设：用 stragent_save_profile 保存后即可按名使用。") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                }
            }
        }
    }
}

private fun askFollowupTool(
    plugin: StrAgentPlugin,
    datasets: AbilityDatasets,
    memory: com.stragent.data.SessionMemoryStore?,
    semantic: com.stragent.data.SemanticStore?,
    provenance: com.stragent.data.ProvenanceRecorder? = null,
    embedding: com.stragent.core.ports.EmbeddingClient? = null,
    rerank: com.stragent.core.ports.RerankClient? = null,
    decomposer: com.stragent.core.ports.QueryDecomposer? = null,
    chunkContext: com.stragent.core.ports.ChunkContextClient? = null,
    blockSummarizer: com.stragent.core.ports.BlockSummarizer? = null,
): AgentTool =
    object : AgentTool {
        override val name = "stragent_ask_followup"
        override val description =
            "对已生成报告的数据集继续追问：回答基于同一份数据与报告垫底，口径与报告一致。"
        override val parametersSchema = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(
                        "datasetId",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "stragent_import_table 返回的 datasetId")
                        },
                    )
                    put(
                        "question",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "追问内容（自然语言）")
                        },
                    )
                    put(
                        "audience",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "受众画像（T-106，可选，≤300 字）：写给谁；非空时注入 persona 块校准回答的术语深度与背景交代") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                        },
                    )
                },
            )
            put("required", JsonArray(listOf(JsonPrimitive("datasetId"), JsonPrimitive("question"))))
        }

        override val cacheable = false
        // 调模型 + 落记忆：不是只读，也不是封闭世界
        override val readOnly = false
        override val openWorld = true

        override fun invoke(args: JsonObject): JsonObject {
            val datasetId = args.stringArg("datasetId")
                ?: throw IllegalArgumentException("datasetId 不能为空（先用 stragent_import_table 导入）")
            val question = args.stringArg("question")
                ?: throw IllegalArgumentException("question 不能为空")
            // 先召回再问：本轮的问答要等答完才入库，否则提示词里会出现「自己刚说的话」，
            // 模型会把它当成既有结论复述一遍（自证预言的最短路径）
            val recalled = memory?.recall(datasetId) ?: emptyList()
            // T-033：语义层召回——全部术语（追问可能涉及任意已知术语）。
            val semanticTerms = semantic?.listTerms() ?: emptyList()
            // T-054：追问同样基于抽样数据集时，口径提示随回包交出（数字仍是样本口径）
            val dataset = datasets.mustDataset(datasetId)
            val result = plugin.askFollowup(
                dataset, emptyList(), question,
                memory = recalled, semantic = semanticTerms,
                cancelSignal = McpRequestCancellation.signal(),
                embedding = embedding,
                rerank = rerank,
                decomposer = decomposer,
                chunkContext = chunkContext,
                blockSummarizer = blockSummarizer,
                audience = args.stringArg("audience")?.takeIf { it.isNotBlank() }?.also {
                    require(it.length <= 300) { "audience 过长：${it.length} 字（上限 300）" } // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                },
            )
            memory?.remember(datasetId, "followup", "问：$question 答：${result.content}")
            // T-033：从追问里抽语义术语候选——用户在追问里说「销售额指的是不含退货」时
            // 自动抽取候选入库（版本门控保护已有定义）。与 caliber 同纪律：候选不静默入库。
            val candidate = com.stragent.core.semantic.extractTermHint(question)
            if (candidate != null && semantic != null) {
                semantic.upsert(candidate, source = "followup")
            }
            // ---- T-073 追问归因：回答引用的数字 ↔ 已留痕步骤的数值面对拍 ----
            // 说明文案走 i18n（预算制：新代码零新增硬编码中文）
            val answerNumbers = com.stragent.core.trace.numericSurface(JsonPrimitive(result.content))
            val provSteps = provenance?.stepsOf(datasetId) ?: emptyList()
            val refs = provSteps.stepIndicesMentioning(answerNumbers)
            val provenanceNote = when {
                provenance == null ->
                    com.stragent.core.i18n.Messages.get("followup.provenance.not_wired")
                provSteps.isEmpty() ->
                    com.stragent.core.i18n.Messages.get("followup.provenance.no_steps")
                refs.isEmpty() ->
                    com.stragent.core.i18n.Messages.get("followup.provenance.no_match")
                else ->
                    com.stragent.core.i18n.Messages.get("followup.provenance.ok")
            }
            return buildJsonObject {
                put("answer", result.content)
                put("totalTokens", result.totalTokens)
                put("truncated", result.truncated)
                // T-044 追问门禁（记录级）：回答不改写，但数字是否都能回到摘要必须可见
                put("gateFailed", result.gateFailed)
                put("gateIssues", JsonArray(result.gateIssues.map { JsonPrimitive(it) }))
                // T-073 追问溯源：引用数字 ↔ 留痕步骤对拍；空表与未接线都如实说明（不装作归因成功）
                put(
                    "provenanceRef",
                    JsonArray(
                        refs.map { (idx, matched) ->
                            buildJsonObject {
                                put("step", idx)
                                put("tool", provSteps[idx].tool)
                                put("matched", JsonArray(matched.map { JsonPrimitive(it) }))
                            }
                        },
                    ),
                )
                put("provenanceNote", provenanceNote)
                // 本次带了几个字节的记忆进上下文：外部 agent 要能分辨「这是背景还是结论」，所以召回量必须出现在回包里，而不是只在提示词里默默生效
                put("memoryFactsUsed", recalled.size)
                put("semanticTermsUsed", semanticTerms.size)
                // T-113 查询分解回显：本次实际生效的子查询（空表=未分解/未启用）——配置回显纪律（T-108 同源）
                put("subQueries", JsonArray(result.subQueries.map { JsonPrimitive(it) }))
                // T-114 块上下文回显：检索索引中获得上下文补语的块数（0=未启用/全失败）
                put("chunkContextCount", result.contextualChunks)
                // T-067：数据面注入信号（同报告口径）
                put("injectionSignals", injectionSignalsJson(collectInjectionSignals(dataset, recalled, semanticTerms)))
                // 候选术语抽取结果：外部 agent 要知道这次追问有没有产出新的语义定义
                val extractedName = candidate?.name
                if (extractedName != null) {
                    put("semanticTermExtracted", extractedName)
                    put("semanticTermKind", candidate.kind)
                }
                dataset.sampling?.let { put("sampling", samplingJson(it, dataset.rows.size)) }
            }
        }
    }

/**
 * 只读召回工具：把某份数据的历史会话产出摊给外部 agent 看。
 *
 * `cacheable = false` —— 记忆是**会变的**（每轮追问后多一条），同参数命中缓存就等于
 * 把「刚写进去的记忆」看不见；这与「导入顺序会变」那类有状态工具同口径。
 * 未接记忆层时本工具不注册（`pluginTools` 末尾 filterNotNull），工具目录不骗人。
 */
private fun recallMemoryTool(
    datasets: AbilityDatasets,
    memory: com.stragent.data.SessionMemoryStore?,
): AgentTool? {
    if (memory == null) return null
    return object : AgentTool {
        override val name = "stragent_recall_memory"
        override val description =
            "查询这份数据在此前会话里留下的产出记录（报告要点与追问问答）。" +
                "记忆按数据集内容指纹存取：数据改过就召不回，同内容跨进程可复现。" +
                "**记忆不是数据**——里面的数字是上一轮的复述，要用的话得在本次数据上重算。"
        override val parametersSchema = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(
                        "datasetId",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "缺省用会话当前数据集（最近一次导入）；显式传则指定")
                        },
                    )
                    put(
                        "kind",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "只看某类：report / followup，缺省全部")
                        },
                    )
                },
            )
        }

        override val cacheable = false

        override fun invoke(args: JsonObject): JsonObject {
            val datasetId = args.stringArg("datasetId")
                ?: datasets.currentId()
                ?: throw IllegalArgumentException("datasetId 不能为空（且当前会话还没有导入数据集）")
            val kind = args.stringArg("kind")
            val facts = memory.recall(datasetId).let { if (kind == null) it else it.filter { f -> f.kind == kind } }
            return buildJsonObject {
                put("datasetId", datasetId)
                put("count", facts.size)
                put(
                    "facts",
                    JsonArray(
                        facts.map { f ->
                            buildJsonObject {
                                put("id", f.id)
                                put("kind", f.kind)
                                put("text", f.text)
                                put("source", f.source)
                                put("savedAt", f.savedAt)
                            }
                        },
                    ),
                )
                put("notice", "以上为此前会话的复述，未在本次数据上复核；数字与名称一律不可直接引用。")
            }
        }
    }
}

/** 取字符串参数（JSON 字符串或原始字面量都认） */
private fun JsonObject.stringArg(key: String): String? {
    val element = this[key] ?: return null
    val primitive = element as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.content.takeIf { it.isNotBlank() }
}

/** 抽样口径 → JSON（报告/追问回包共用）：数字是样本口径这件事必须随回包可见，不藏在提示词里 */
private fun samplingJson(info: com.stragent.core.dataset.SamplingInfo, sampleRows: Int): JsonObject = buildJsonObject {
    put("method", info.method)
    put("step", info.step)
    put("targetRows", info.targetRows)
    put("sampleRows", sampleRows)
    put("totalRows", info.totalRows)
    put("fingerprint", info.fingerprint)
    put(
        "notice",
        "本结果基于源文件 ${info.totalRows} 行的确定性系统抽样（每 ${info.step} 行取 1 行，共 $sampleRows 行），" +
            "是样本口径、不是全量；明细行回链见 stragent_list_rows 的 sourceRow 字段。",
    )
}

/**
 * 只读语义术语查询工具（T-033）：把跨数据集沉淀的指标定义与约束摊给外部 agent 看。
 *
 * 语义层与记忆（按指纹失效）互补——术语定义跨数据集持久，不随数据更换丢失。
 * `cacheable = false`——术语是**会变的**（追问后可能新增），同参数命中缓存
 * 就等于把「刚抽取的术语」看不见。
 */
fun listSemanticTermsTool(semantic: com.stragent.data.SemanticStore): AgentTool =
    object : AgentTool {
        override val name = "stragent_list_semantic_terms"
        override val description =
            "查询已定义的指标口径与业务约束（跨数据集沉淀，不随数据更换而丢失）。" +
                "用户在追问里说「销售额指的是不含退货的净销售」时自动抽取入库；" +
                "到另一份数据上有一列也叫「销售额」时，这条定义仍适用。" +
                "**语义不是数据**——定义里的数字与名称不可直接引用。"
        override val parametersSchema = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(
                        "name",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "按术语名过滤（精确匹配，缺省全部）")
                        },
                    )
                },
            )
        }

        override val cacheable = false

        override fun invoke(args: JsonObject): JsonObject {
            val nameFilter = args.stringArg("name")
            val terms = if (nameFilter != null) {
                listOfNotNull(semantic.findTerm(nameFilter))
            } else {
                semantic.listTerms()
            }
            return buildJsonObject {
                put("count", terms.size)
                put(
                    "terms",
                    JsonArray(
                        terms.map { t ->
                            buildJsonObject {
                                put("id", t.id)
                                put("name", t.name)
                                if (t.aliases.isNotEmpty()) {
                                    put("aliases", JsonArray(t.aliases.map { JsonPrimitive(it) }))
                                }
                                if (t.definition.isNotEmpty()) put("definition", t.definition)
                                t.unit?.let { put("unit", it) }
                                if (t.constraints.isNotEmpty()) {
                                    put("constraints", JsonArray(t.constraints.map { JsonPrimitive(it) }))
                                }
                                put("source", t.source)
                                put("version", t.version)
                                put("updatedAt", t.updatedAt)
                            }
                        },
                    ),
                )
                put("notice", "以上为跨数据集指标口径定义，不随数据更换失效；数字与名称不可直接引用。")
            }
        }
    }
