package com.stragent.host

import com.stragent.core.config.Config
import com.stragent.core.ports.ProgressBus
import com.stragent.agent.StepSearchClient
import com.stragent.agent.plugin.StrAgentHost
import com.stragent.agent.plugin.StrAgentHostContext
import com.stragent.agent.plugin.StrAgentPlugin
import com.stragent.core.ports.EmbeddingClient
import com.stragent.core.ports.RerankClient
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.VisionClient
import com.stragent.data.AbilityDatasets
import com.stragent.data.AnalysisToolSession
import com.stragent.data.CaliberStore
import com.stragent.data.DatasetLibrary
import com.stragent.data.FileDatasetStore
import com.stragent.data.FilePrefs
import com.stragent.data.HttpLlmClient
import com.stragent.data.McpBridge
import com.stragent.data.McpTaskStore
import com.stragent.data.MetricStore
import com.stragent.data.OAuthResourceServer
import com.stragent.data.OpenAiCompatibleVisionClient
import com.stragent.data.ProjectStore
import com.stragent.data.ProvenanceRecorder
import com.stragent.data.SemanticStore
import com.stragent.data.SessionMemoryStore
import com.stragent.data.StepfunConfig
import java.io.File

/**
 * 纯 JVM 宿主装配层 —— 替代原 Android 侧的 `AppContainer`。
 *
 * 装配内容与原 AppContainer **一一对应**，只是把「Android 私有目录」换成了
 * 本机目录、把「AndroidKeyStore」换成了 [JvmKeyStore]：
 *
 * ```
 * <home>/prefs.properties   偏好与索引（数据集索引、分类、模型与服务商配置也在这）
 * <home>/kv/                键值文档（数据集本体、章节草稿、历史报告）
 * <home>/plugins/           用户导入的 agent 插件目录
 * <home>/api-key             本机 API Key（仅属主可读写；不进 prefs）
 * ```
 *
 * 宿主只提供 5 个端口（llm / prefs / kv / pluginDir / 可选 search）+ 模型解析器，
 * 其余能力全在插件里 —— 这正是「万物皆插件」的设计意图。
 *
 * 任何第三方宿主都可以跳过本模块，直接用 `core/ports` 的接口自建装配；
 * 本模块只是**参考宿主 + 无头 MCP 桥的落地面**。
 */
class StrAgentHostBootstrap private constructor(
    val home: File,
    llmOverride: LlmClient?,
    visionOverride: VisionClient?,
    /** 嵌入客户端覆盖（T-100 测试注入）；null = 按 env 装配 */
    private val embeddingOverride: EmbeddingClient? = null,
    /** 重排客户端覆盖（T-101 测试注入）；null = 按 env 装配 */
    private val rerankOverride: RerankClient? = null,
    /** 查询分解器覆盖（T-113 测试注入）；null = 按 env 装配 */
    private val decomposerOverride: com.stragent.core.ports.QueryDecomposer? = null,
    /** 块级上下文前缀覆盖（T-114 测试注入）；null = 按 env 装配 */
    private val chunkContextOverride: com.stragent.core.ports.ChunkContextClient? = null,
    /** 块摘要器覆盖（T-117 测试注入）；null = 按 env 装配 */
    private val blockSummarizerOverride: com.stragent.core.ports.BlockSummarizer? = null,
    /**
     * HTTP 监听地址（T-146 可测性）：缺省按 env `STRAGENT_HTTP_BIND` 解析（未设置 → 回环）。
     * 可注入是为了让「远程绑定未配 issuer → 拒绝启动」这条**安全契约可被执行**。
     */
    private val httpBind: String = httpBindAddress(),
) : AutoCloseable {

    private val keyStore = JvmKeyStore(File(home, "api-key"))

    /** 环境变量注入的 Key 优先：CI / 容器里不落盘 */
    private val envApiKey: String? =
        // 注入 API Key 的环境变量（优先级最高，不落盘；名字见 JvmKeyStore 文档）
        Config.get("STRAGENT_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }

    private val encrypt: (String) -> String = { keyStore.encrypt(it) }
    private val decrypt: (String) -> String = { stored -> envApiKey ?: keyStore.decrypt(stored) }

    val prefs = FilePrefs(home)
    val kv = FileDatasetStore(File(home, "kv"))
    val datasets = DatasetLibrary(prefs, kv)
    val projects = ProjectStore(prefs, kv)
    val caliber = CaliberStore(prefs)

    /**
     * 跨会话记忆（T-008）：与数据集同一套 kv 目录，键 `memory.<datasetId>`。
     * datasetId 是内容派生的 `ds_<sha256 前 12 位>`，所以「换 home 目录 = 换会话，
     * 同一份数据仍召得回」；数据一改指纹就变，旧记忆自然失效，不需要额外清理动作。
     */
    val memory = SessionMemoryStore(kv)

    /**
     * 语义层（T-033）：跨数据集指标定义与约束记忆——键 `semantic.terms`，全局唯一，
     * 不按数据集内容指纹分桶。用户在 A 数据集上定义「销售额不含退货」，
     * 到 B 数据集上有一列也叫「销售额」时仍能召回。与记忆（按指纹失效）互补。
     */
    val semantic = SemanticStore(kv)

    /**
     * 指标定义库（T-055）：键 `semantic.metrics`，跨数据集生效——存的是**口径**（表达式 +
     * 字段别名 + 维度 + 粒度 + 自检），不是数字；数字每次按当前数据现算。
     * 内置 10 个指标由 [com.stragent.core.semantic.MetricCatalog.BUILTINS] 提供，不入库。
     */
    val metrics = MetricStore(kv)
    val stepfun = StepfunConfig(prefs, encrypt, decrypt)

    /**
     * 用量与成本账本（T-171 / T-232）：定义在此（早于 LLM 端口），供两个采集点共用——
     * ① LLM 客户端层按**精确模型名**记实际调用；② 工具回执层按工具/引擎记语义分面。
     * 两个分面不相加（口径写在 ledger 快照的 note 里）。
     */
    val usageLedger = com.stragent.core.observability.UsageLedger(
        com.stragent.core.observability.OtelEnvironment.costPer1kTokens(),
    )

    /**
     * 推理端口。默认走真实 HTTP；宿主集成验收（测试）可注入 fake LlmClient，
     * **不必持有真实模型 Key 也能验证整条接线**（数据 → brief → 模型 → 报告 → MCP 回包）。
     */
    val llm: LlmClient = llmOverride ?: HttpLlmClient(prefs, encrypt, decrypt)

    val search = StepSearchClient(prefs)

    /**
     * 视觉端口（阶段 3）：默认走**主流接口**（OpenAI 兼容 vision）——
     * 同一份适配器 + 不同 baseUrl/model/Key 即可覆盖 GLM-4V / Qwen-VL / Kimi-VL / MiniMax / Step-Vision 等；
     * 测试可注入桩（我代跑后端大模型相关测试）。
     */
    val vision: VisionClient = visionOverride
        ?: OpenAiCompatibleVisionClient(stepfun, resolveVisionModel(stepfun, prefs))

    // T-100 嵌入客户端（hybrid 检索语义路）：env 三元配置，未配 BASE_URL = 不启用（BM25 既有行为）。
    // Key 独立于 chat（嵌入计价/模型不同），模型名 env 可调（core TEXT_EMBEDDING_MODEL）。
    val rerankClient: com.stragent.core.ports.RerankClient? =
        rerankOverride ?: Config.get("STRAGENT_RERANK_BASE_URL")?.takeIf { it.isNotBlank() }?.let { base ->
            com.stragent.data.OpenAiCompatibleRerankClient(
                prefs,
                baseUrlOverride = base.removeSuffix("/"),
            ).let { client ->
                val envKey = Config.get("STRAGENT_RERANK_API_KEY")
                if (envKey.isNullOrBlank()) client else EnvKeyRerankClient(client, envKey)
            }
        }

    val embeddingClient: com.stragent.core.ports.EmbeddingClient? =
        embeddingOverride ?: Config.get("STRAGENT_EMBEDDING_BASE_URL")?.takeIf { it.isNotBlank() }?.let { base ->
            com.stragent.data.OpenAiCompatibleEmbeddingClient(
                prefs,
                baseUrlOverride = base.removeSuffix("/") + if (base.contains("/embeddings")) "" else "",
                timeoutMs = 30_000,
            ).let { client ->
                // Key 注入走 prefs（与 chat 同源）；env 直配 Key 的部署用包装覆盖 prefs 读取
                val envKey = Config.get("STRAGENT_EMBEDDING_API_KEY")
                val raw = if (envKey.isNullOrBlank()) client else EnvKeyEmbeddingClient(client, envKey)
                // T-101 缓存补接线（T-114 发现）：装饰器早已交付但 env 装配一直没包缓存——
                // 同一材料的段落向量只算一次（内容寻址 embed.<sha12>），注入型覆盖不受影响（测试自管缓存）
                com.stragent.core.ai.CachedEmbeddingClient(raw, kv)
            }
        }

    /**
     * 查询分解器（T-113 隐式多跳检索侧）：env 显式开启（STRAGENT_TEXT_QUERY_DECOMP=1/on）才装配——
     * 分解是每条文本追问 +1 次轻调用，成本由部署方显式接受，默认关 = 零变化。
     */
    val queryDecomposer: com.stragent.core.ports.QueryDecomposer? =
        decomposerOverride ?: Config.get("STRAGENT_TEXT_QUERY_DECOMP")
            ?.takeIf { it.equals("1", ignoreCase = false) || it.equals("on", ignoreCase = true) }
            ?.let { com.stragent.core.ai.LlmQueryDecomposer }

    /**
     * 块级上下文前缀（T-114 Anthropic Contextual Retrieval 完整版）：env 显式开启
     * （STRAGENT_TEXT_CONTEXTUAL=1/on）才装配——首次追问按每 5 块一次轻调用补语，
     * 缓存（chunkctx.<sha12>）之后零调用；成本由部署方显式接受，默认关 = 零变化。
     */
    val chunkContextClient: com.stragent.core.ports.ChunkContextClient? =
        chunkContextOverride ?: Config.get("STRAGENT_TEXT_CONTEXTUAL")
            ?.takeIf { it.equals("1", ignoreCase = false) || it.equals("on", ignoreCase = true) }
            ?.let { com.stragent.core.ai.CachedChunkContextClient(com.stragent.core.ai.LlmChunkContextualizer, kv) }

    /**
     * 块摘要器（T-117 RAPTOR 摘要节点层）：env 显式开启（STRAGENT_TEXT_SUMMARY_TREE=1/on）才装配——
     * 每 ~4000 字块一次轻调用（首问 N 次，kv 缓存后零），成本由部署方显式接受，默认关 = 零变化。
     */
    val blockSummarizerClient: com.stragent.core.ports.BlockSummarizer? =
        blockSummarizerOverride ?: Config.get("STRAGENT_TEXT_SUMMARY_TREE")
            ?.takeIf { it.equals("1", ignoreCase = false) || it.equals("on", ignoreCase = true) }
            ?.let { com.stragent.core.ai.CachedBlockSummarizer(com.stragent.core.ai.LlmBlockSummarizer, kv) }

    /**
     * 扫描件 OCR 视觉通道（T-115）：env 显式开启（STRAGENT_OCR=1）才把既有 vision 端口交给导入工具——
     * OCR 是每页一次视觉调用，成本由部署方显式接受；默认关 = 扫描件保持既有诚实报错。
     */
    /**
     * 导入后后台预热（T-132）：env 显式开启（STRAGENT_TEXT_WARMUP=1）才装配——预热把
     * ctx/摘要轻调用从首问挪到导入时（同一批调用，只是换时间付），无人追问的场景是纯浪费，
     * 成本由部署方显式接受；默认关 = 零变化。失败静默（预热是增强不是依赖）。
     */
    private val textWarmup: ((com.stragent.core.dataset.Dataset) -> Unit)? =
        Config.get("STRAGENT_TEXT_WARMUP")
            ?.takeIf { it.equals("1", ignoreCase = false) || it.equals("on", ignoreCase = true) }
            ?.let {
                { dataset: com.stragent.core.dataset.Dataset ->
                    dataset.text?.let { text ->
                        com.stragent.core.ai.warmupTextIndex(
                            text, llm, stepfun.getModel(), chunkContextClient, blockSummarizerClient, true,
                        )
                    }
                }
            }

    val ocrVisionClient: com.stragent.core.ports.VisionClient? =
        Config.get("STRAGENT_OCR")
            ?.takeIf { it.equals("1", ignoreCase = false) || it.equals("on", ignoreCase = true) }
            ?.let { vision }

    /** harness 内核：只负责装载 / 卸载 / 路由 */
    val host = StrAgentHost(
        StrAgentHostContext(
            llm = llm,
            prefs = prefs,
            kv = kv,
            pluginDir = File(home, "plugins"),
            modelResolver = { stepfun.getModel() },
            search = search,
            embedding = embeddingClient,
            rerank = rerankClient,
            queryDecomposer = queryDecomposer,
            chunkContext = chunkContextClient,
            blockSummarizer = blockSummarizerClient,
            // 阶段进度 → 进度总线 → 由 MCP 传输层转成 notifications/progress（订阅者缺席时为空转）
            progressListener = { phase, progress ->
                val detail = progress?.let { "（${it.done}/${it.total} ${it.title}）" } ?: ""
                ProgressBus.emit("$phase$detail")
            },
            // 正文增量 → 同一条总线（token 级流式）。帧文口径在这里定死，客户端按它解析：
            //   首行 = 定位信息（第几章 / 第几次生成 / 本章累计字数），换行后 = 原样正文片段；
            //   片段按 (章, 次) 到达序拼接即该章正文；「第几次生成」一变 = 该章在重写，丢掉旧片段。
            // 没人订阅时（不带 progressToken 的调用）连字符串都不拼——进度是增强，不是依赖。
            bodyListener = { delta ->
                // T-172：正文增量的第二个出口——异步任务据此把「已成稿的章节」落进任务表，
                // 客户端轮询 tasks/get 就能看到当前草稿（不必等全文跑完）。
                com.stragent.core.ai.BodyStream.publish(delta)
                if (ProgressBus.isActive()) {
                    ProgressBus.emit(
                        "正文流式（第${delta.chapter}/${delta.total}章 ${delta.title}·第${delta.attempt}次生成·" +
                            "累计 ${delta.chars} 字）\n${delta.text}",
                    )
                }
            },
            // T-050：断点目录（应用私有）。`agent.checkpoint=off` 可整体关闭（排查/演示用）
            checkpointDir = if (prefs.get("agent.checkpoint", "on") == "off") null else File(home, "checkpoints"),
        ),
    )

    /** 插件本体（宿主装载它） */
    val plugin = StrAgentPlugin().also { host.load(it) }

    /** 底层引擎：同进程直接驱动（宿主或测试用） */
    val orchestrator get() = plugin.engine

    /**
     * 四条数据契约的会话数据集注册表（与插件能力面分开）。
     * T-054：接 kv 持久化并在启动时回灌——重启后同一 datasetId 仍可取回；
     * 落盘的是会话里实际分析的那份（大文件为抽样样本），不是源文件。
     */
    val abilityDatasetStore = com.stragent.data.AbilityDatasetStore(kv)
    val abilityDatasets = AbilityDatasets(abilityDatasetStore).apply { restore() }

    /** 会话注册表（T-030）：MCP 会话按 Mcp-Session-Id 分桶「当前数据集指针」 */
    val sessionRegistry = com.stragent.data.SessionRegistry(abilityDatasets)

    /**
     * 策展分析工具的会话适配层（T-007）：工具面随「当前数据集」变化，故用动态提供器接入 MCP 桥。
     */
    val analysisSession = AnalysisToolSession(abilityDatasets, prefs, registry = sessionRegistry)

    /** 溯源留痕器：每次工具调用都记进分析链，供「重放复核」 */
    val provenance = ProvenanceRecorder(abilityDatasets)

    /** HTTP 监听地址（缺省回环；容器端口映射需显式 `STRAGENT_HTTP_BIND=0.0.0.0`） */
    val httpBindAddress: String = httpBind

    /**
     * OAuth 2.1 资源服务器（T-056）：**仅远程绑定 + 配了 `STRAGENT_OAUTH_ISSUER`** 时启用。
     *
     * T-146（BREAKING）：远程绑定**未配** issuer 时**拒绝启动**（fail-closed）——
     * 此前是静默回落静态 Bearer token（OWASP CWE-636「Not Failing Securely」）。
     * 静态 token 无过期/无 audience/无 scope，不能用于远程暴露。错误信息里写明
     * 两条出路：配 issuer，或改回回环绑定。
     */
    val oauth: OAuthResourceServer? = OAuthResourceServer.fromEnvironment(httpBindAddress, McpBridge.PORT)

    /**
     * elicitation 双向通道（T-056）：宿主工具 `stragent_elicit_input` 与 MCP 桥的
     * 传输层必须共用**同一个**实例——stdio 的 stdout 发送器、HTTP 的 `GET /mcp` 流
     * 都注册在这里；给两份实例等于提问发不出去。
     */
    val elicitationBroker = com.stragent.data.ElicitationBroker()

    /**
     * OTel 追踪（T-056 / D17 G-16）：按 `OTEL_EXPORTER_OTLP_ENDPOINT` /
     * `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` / `STRAGENT_OTEL_FILE` 装配；
     * 一个都没配 = null（零成本、零日志噪声）。导出走**异步有界队列**——
     * 收集器慢/挂掉只丢可观测数据，不拖报告生成。
     */
    val otel: com.stragent.core.observability.OtelTraces? =
        com.stragent.core.observability.OtelEnvironment.fromEnvironment(
            System.getenv(),
            com.stragent.data.BuildInfo.version,
        )

    /**
     * OTel metrics（T-065）：按 `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT` / 基础端点 /
     * `STRAGENT_OTEL_METRICS_FILE` 装配；一个都没配 = null（零成本）。周期导出
     * （默认 60s）+ close 收尾，导出慢/挂只丢可观测数据，不拖工具调用。
     */
    val otelMetrics: com.stragent.core.observability.OtelMetrics? =
        com.stragent.core.observability.OtelMetricsEnvironment.fromEnvironment(
            System.getenv(),
            com.stragent.data.BuildInfo.version,
        )

    // T-161/T-232：LLM 调用计数（vendor + 收敛 model 标签）与按精确模型名的成本账本
    // 接线（llm 声明早于 otelMetrics，故此处回填）
    init {
        (llm as? com.stragent.data.HttpLlmClient)?.let { client ->
            client.llmMetrics = otelMetrics
            client.usageLedger = usageLedger
        }
    }

    /**
     * 头采样器（T-065）：`STRAGENT_OTEL_SAMPLER`（0.0–1.0 浮点，缺省 1.0 全采）。
     * 配了但非法打告警并按全采走——采样是增强，不猜配置。
     */
    val otelSampler: com.stragent.core.observability.OtelSampler? =
        com.stragent.core.observability.OtelSampler.fromEnvironment(System.getenv())
            .also { sampler ->
                val raw = Config.get(com.stragent.core.observability.OtelSampler.ENV)?.trim()
                if (sampler == null && !raw.isNullOrBlank()) {
                    com.stragent.core.observability.StructuredLogger.getLogger("Otel").warn(
                        "STRAGENT_OTEL_SAMPLER 配置非法（应为 0.0–1.0 浮点），按全采走",
                        "value" to raw,
                    )
                }
            }

    /**
     * OTel logs（T-066）：按 `OTEL_EXPORTER_OTLP_LOGS_ENDPOINT` / 基础端点 /
     * `STRAGENT_OTEL_LOGS_FILE` 装配；一个都没配 = null（不装桥，零成本）。
     * 配置了基础端点时三信号（traces/metrics/logs）一次全启用。
     */
    val otelLogs: com.stragent.core.observability.OtelLogs? =
        com.stragent.core.observability.OtelLogsEnvironment.fromEnvironment(
            System.getenv(),
            com.stragent.data.BuildInfo.version,
        )

    /** 日志多路桥：原 stderr 通道逐字不变，额外把每行投给 OTel logs 导出（T-066） */
    private val otelLogBridge: com.stragent.core.observability.OtelLogBridge? =
        otelLogs?.let { com.stragent.core.observability.OtelLogBridge(it).also { bridge -> bridge.install() } }

    /** 最近报告环形缓冲（T-070）：报告工具写入，/ui 驾驶舱数据源读取 */
    val recentReports = RecentReports()

    /**
     * 回执与 /ui 驾驶舱共用上面的 [usageLedger]（定义在本类前部，早于 LLM 端口接线）——
     * 现在它同时收 LLM 客户端层的按模型用量和工具回执层的按工具/引擎用量。
     */
    /**
     * 桥引用回填位（T-174）：`uiDashboardData` 是桥的依赖（数据端点），而水位线又要读桥的
     * 计数器——直接互相引用会让类型推断成环，故用装配后回填的可空引用打断。
     */
    private var bridgeRef: McpBridge? = null

    /** /ui 驾驶舱数据源（T-070）：桥注册三个鉴权数据端点 */
    private val uiDashboardData = UiDashboardData(
        abilityDatasets, recentReports, com.stragent.data.BuildInfo.version, otelMetrics,
        usageLedger = usageLedger,
        // T-174：水位线——只读既有计数器，不另建采样
        watermarks = {
            Watermarks.snapshot(
                datasets = abilityDatasets,
                bridge = bridgeRef,
                metrics = metrics,
                semantic = semantic,
                toolCount = null,
                tier2Available = runCatching {
                    Class.forName("org.duckdb.DuckDBDriver")
                }.isSuccess,
            )
        },
    )

    /** 成本估算单价（USD / 1K tokens）：未配置 = null，span 上不写成本（不编造） */
    private val otelCostPer1kTokens: Double? =
        com.stragent.core.observability.OtelEnvironment.costPer1kTokens()

    /**
     * MCP 桥：数据契约 + 插件能力 + 策展分析工具 + 大文件工具 + 溯源清单 + 受控计算
     * + 跨会话记忆召回（`stragent_recall_memory`，只读；不参与溯源重放——记忆会变）。
     */
    val mcpBridge = McpBridge(
        datasets = abilityDatasets,
        prefs = prefs,
        // T-162：工具目录装配提为顶层纯函数（McpToolCatalog.kt）——组合根只保留桥参数
        extraTools = buildStandaloneTools(
            plugin, llm, kv, prefs, stepfun,
            abilityDatasets, sessionRegistry, analysisSession,
            memory, semantic, metrics,
            otel, otelCostPer1kTokens, otelMetrics, recentReports, provenance,
            embeddingClient, rerankClient, queryDecomposer, chunkContextClient, blockSummarizerClient,
            vision, ocrVisionClient, textWarmup, elicitationBroker, usageLedger,
        ),
        extraToolsProvider = { analysisSession.tools() },
        // T-155：tools/list 按当前授权态裁剪——沙箱未开时受控计算两档不下发（省 token 且
        // 不诱导模型调用必然失败的入口）；调度保持全量，调用侧授权拦截原样保留（运行时
        // prefs.set 开启后下一次 tools/list 即恢复下发——宿主运行时授权语义不变）。
        catalogHiddenNames = {
            val hidden = LinkedHashSet<String>()
            if (!computeSandboxAuthorized(prefs)) {
                hidden += setOf("stragent_compute", "stragent_compute_python")
            }
            // T-155 遗留收口：视觉未就绪（无 Key/无模型名）时 analyze_image 不下发——省 token
            // 且不诱导调用必然失败的死入口；调度保持全量、调用侧拦截原样保留；Key 运行时经
            // prefs 配置后下一次 tools/list 即恢复下发（与受控计算同一运行时授权语义）。
            if (!vision.isAvailable()) hidden += "stragent_analyze_image"
            // T-190：db_* 6 条默认隐藏（D24 P1-9：省 token 且缩攻击面）——显式授权
            // （env STRAGENT_DB_TOOLS=1 或 prefs tools.db.enabled=true）才恢复下发；
            // 调度面全量保留（目录隐藏≠能力删除，T-155 同语义）。工具名取自 dbTools()
            // 单一事实源，不在两处硬编码清单。
            if (!dbToolsAuthorized(prefs)) hidden += dbTools().map { it.name }
            hidden
        },
        provenance = provenance,
        registry = sessionRegistry,
        webUiHtml = WebUiPage.html,
        bindAddress = httpBind,
        oauth = oauth,
        // T-204：任务句柄落盘恢复——宿主重启后终态任务在 TTL 内仍可 tasks/get，
        // 非终态如实转 failed；env STRAGENT_TASKS_FILE 可覆盖路径
        taskStore = McpTaskStore(
            storePath = java.nio.file.Path.of(
                com.stragent.core.config.Config.get("STRAGENT_TASKS_FILE")
                    ?: java.io.File(home, "tasks.jsonl").absolutePath,
            ),
        ),
        elicitation = elicitationBroker,
        otel = otel,
        metrics = otelMetrics,
        sampler = otelSampler,
        uiDataProvider = uiDashboardData,
        estimateTotalMs = { recentReports.durationP50() },
    )

    init {
        // T-174：桥已就位，回填引用给水位线快照（打断 uiDashboardData ↔ mcpBridge 的类型环）
        bridgeRef = mcpBridge
    }

    override fun close() {
        mcpBridge.stop()
        // 先给导出器收尾时间（在途 span 批次 / metrics 最后一次快照 / logs 队列余量），再关桥下其他资源
        otelLogBridge?.close()
        otel?.close()
        otelMetrics?.close()
        otelLogs?.close()
        // JDBC 连接是进程级全局对象（DatabaseConnectionStore），宿主关闭时统一释放，
        // 避免长驻进程退出前留下未关闭连接（测试里也按 bootstrap 生命周期收口）。
        DatabaseConnectionStore.closeAll()
    }

    companion object {
        /** 数据目录的环境变量 */
        const val ENV_HOME = "STRAGENT_HOME"

        /** HTTP 监听地址的环境变量（缺省仅回环；容器端口映射需显式 0.0.0.0） */
        const val ENV_HTTP_BIND = "STRAGENT_HTTP_BIND"

        /** 解析监听地址：未设置/空白 → 回环（安全默认值不放宽） */
        fun httpBindAddress(): String =
            Config.get(ENV_HTTP_BIND)?.trim()?.takeIf { it.isNotEmpty() } ?: "127.0.0.1"

        /** 默认数据目录：~/.str-agent */
        fun defaultHome(): File =
            Config.get(ENV_HOME)?.takeIf { it.isNotBlank() }?.let { File(it) }
                ?: File(System.getProperty("user.home"), ".str-agent")

        fun create(
            home: File = defaultHome(),
            llm: LlmClient? = null,
            vision: VisionClient? = null,
            /** 嵌入客户端（T-100 hybrid 检索）；缺省按 env STRAGENT_EMBEDDING_* 装配，未配 = null */
            embedding: EmbeddingClient? = null,
            rerank: RerankClient? = null,
            /** 查询分解器（T-113 隐式多跳检索侧）；缺省按 env STRAGENT_TEXT_QUERY_DECOMP 装配，未配 = null */
            decomposer: com.stragent.core.ports.QueryDecomposer? = null,
            /** 块级上下文前缀（T-114）；缺省按 env STRAGENT_TEXT_CONTEXTUAL 装配，未配 = null */
            chunkContext: com.stragent.core.ports.ChunkContextClient? = null,
            /** 块摘要器（T-117）；缺省按 env STRAGENT_TEXT_SUMMARY_TREE 装配，未配 = null */
            blockSummarizer: com.stragent.core.ports.BlockSummarizer? = null,
            /**
             * HTTP 监听地址（T-146 可测性）：缺省按 env `STRAGENT_HTTP_BIND` 解析
             * （未设置 → 回环，安全默认值不放宽）。
             *
             * 之前它**只**从环境变量读，进程内无法覆盖——于是「远程绑定未配 issuer 时
             * 会不会拒绝启动」这条**安全契约在测试里根本无法验证**。给出参数后，
             * 该契约有了可执行的断言（见 `T146RemoteFailClosedTest`）。
             */
            httpBind: String = httpBindAddress(),
        ): StrAgentHostBootstrap {
            // 目录布局显式就位（不靠各适配器懒创建）：宿主一启动，目录结构就该是确定的
            home.mkdirs()
            File(home, "kv").mkdirs()
            File(home, "plugins").mkdirs()
            val prefsFile = File(home, "prefs.properties")
            if (!prefsFile.exists()) prefsFile.createNewFile()
            return StrAgentHostBootstrap(
                home, llm, vision, embedding, rerank, decomposer, chunkContext, blockSummarizer,
                httpBind = httpBind,
            )
        }
    }
}

/** env 直配 Key 的嵌入客户端包装（T-100）：覆盖 prefs 读取，部署不把嵌入 Key 存偏好时用 */
private class EnvKeyEmbeddingClient(
    private val delegate: com.stragent.core.ports.EmbeddingClient,
    private val key: String,
) : com.stragent.core.ports.EmbeddingClient {
    override fun embed(texts: List<String>): List<DoubleArray> = delegate.embed(texts)
}

/** env 直配 Key 的重排客户端包装（T-101） */
private class EnvKeyRerankClient(
    private val delegate: com.stragent.core.ports.RerankClient,
    private val key: String,
) : com.stragent.core.ports.RerankClient {
    override fun rerank(query: String, candidates: List<String>): List<com.stragent.core.ports.RerankHit> = delegate.rerank(query, candidates)
}
