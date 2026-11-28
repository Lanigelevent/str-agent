package com.stragent.host

import com.stragent.agent.plugin.StrAgentPlugin
import com.stragent.core.ports.PrefsStore
import com.stragent.data.AbilityDatasets
import com.stragent.data.AnalysisToolSession
import com.stragent.data.ElicitationBroker
import com.stragent.data.MetricStore
import com.stragent.data.ProvenanceRecorder
import com.stragent.data.SemanticStore
import com.stragent.data.SessionMemoryStore
import com.stragent.data.SessionRegistry
import com.stragent.data.StepfunConfig
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.VisionClient

/**
 * T-162（D25 A5 三刀之一）：非插件工具目录装配——**顶层纯函数**。
 *
 * 此前这 80 行内联在 [StrAgentHostBootstrap.mcpBridge] 的黄金段，每次加工具都要碰
 * 520 行组合根文件。现在它是一个显式参数列表的纯装配函数：所有依赖一目了然，
 * 加工具 = 改本文件，组合根只保留生命周期与桥参数。
 *
 * 诚实登记（同轮决策）：三刀中的另外两刀**不再执行**——OTel 段实读后是 4 条薄
 * env-read 声明（提取只加参数管道、零可读性收益）；插件/分析会话段（host/plugin/
 * abilityDatasets 等）是组合根的**公开 API 表面**（外部访问 bootstrap.host 等），
 * 搬走只是移动类边界而非缩小它。
 */
internal fun buildStandaloneTools(
    plugin: StrAgentPlugin,
    llm: LlmClient,
    kv: com.stragent.data.FileDatasetStore,
    prefs: PrefsStore,
    stepfun: StepfunConfig,
    abilityDatasets: AbilityDatasets,
    sessionRegistry: SessionRegistry,
    analysisSession: AnalysisToolSession,
    memory: SessionMemoryStore,
    semantic: SemanticStore,
    metrics: MetricStore,
    otel: com.stragent.core.observability.OtelTraces?,
    otelCostPer1kTokens: Double?,
    otelMetrics: com.stragent.core.observability.OtelMetrics?,
    recentReports: RecentReports,
    provenance: ProvenanceRecorder,
    embeddingClient: com.stragent.core.ports.EmbeddingClient?,
    rerankClient: com.stragent.core.ports.RerankClient?,
    queryDecomposer: com.stragent.core.ports.QueryDecomposer?,
    chunkContextClient: com.stragent.core.ports.ChunkContextClient?,
    blockSummarizerClient: com.stragent.core.ports.BlockSummarizer?,
    vision: VisionClient,
    ocrVisionClient: VisionClient?,
    textWarmup: ((com.stragent.core.dataset.Dataset) -> Unit)?,
    elicitationBroker: ElicitationBroker,
    usageLedger: com.stragent.core.observability.UsageLedger? = null,
): List<com.stragent.core.ports.AgentTool> =
    pluginTools(
        plugin, abilityDatasets, memory, semantic, metrics,
        otel = otel, costPer1kTokens = otelCostPer1kTokens, otelMetrics = otelMetrics,
        recentReports = recentReports,
        provenance = provenance,
        embedding = embeddingClient,
        rerank = rerankClient,
        decomposer = queryDecomposer,
        chunkContext = chunkContextClient,
        blockSummarizer = blockSummarizerClient,
        prefs = prefs,
        usageLedger = usageLedger,
    ) +
        listMetricsTool(metrics, abilityDatasets) +
        defineMetricTool(metrics) +
        evaluateMetricTool(metrics, abilityDatasets, sessionRegistry) +
        elicitInputTool(elicitationBroker) +
        largeCsvAggregateTool() +
        largeCsvProfileTool() +
        computeTool(abilityDatasets, prefs, sessionRegistry) +
        pythonComputeTool(abilityDatasets, prefs, sessionRegistry) +
        visionTool(vision) +
        forecastTool(abilityDatasets, sessionRegistry) +
        whatIfTool(abilityDatasets, sessionRegistry) +
        chartTool(abilityDatasets, sessionRegistry) +
        regressionTool(abilityDatasets, sessionRegistry) +
        hypothesisTestTool(abilityDatasets, sessionRegistry) +
        importFileTool(abilityDatasets, sessionRegistry) +
        importPdfTool(abilityDatasets, sessionRegistry, ocrVisionClient) +
        importTextTool(abilityDatasets, sessionRegistry, ocrVisionClient, textWarmup) +
        summarizeTextTool(llm, abilityDatasets, sessionRegistry, kv, stepfun.getModel(), usageLedger) +
        readSegmentsTool(abilityDatasets, sessionRegistry) +
        listSemanticTermsTool(semantic) +
        // T-205：导出工具注入数据集库+溯源器——有分析留痕时报告自动带「报告元信息」节（数据时点标注）
        exportReportTool(datasets = abilityDatasets, provenance = provenance) +
        dbTools() +
        provenanceTool(
            recorder = provenance,
            datasets = abilityDatasets,
            // 只重放确定性的数据/分析/计算工具；涉及模型调用的（报告/追问）不重放
            replayTools = com.stragent.data.abilityTools(abilityDatasets) +
                analysisSession.tools() +
                listOf(
                    computeTool(abilityDatasets, prefs, sessionRegistry),
                    pythonComputeTool(abilityDatasets, prefs, sessionRegistry),
                    forecastTool(abilityDatasets, sessionRegistry),
                    whatIfTool(abilityDatasets, sessionRegistry),
                    chartTool(abilityDatasets, sessionRegistry),
                    regressionTool(abilityDatasets, sessionRegistry),
                    hypothesisTestTool(abilityDatasets, sessionRegistry),
                ),
        )
