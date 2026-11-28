package com.stragent.agent.plugin

import com.stragent.core.ai.BodyDelta
import com.stragent.core.ai.ChapterProgress
import com.stragent.core.ports.DatasetStore
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.PrefsStore
import com.stragent.core.ports.SearchClient
import java.io.File

/**
 * 「万物皆插件」的宿主契约层（纯 JVM，无 Android 依赖）。
 *
 * 设计定位：Str Agent 不再是一个写死接线的大 App，而是一个**自描述、可装载、可卸除**
 * 的插件单元（[HostPlugin]）。任何宿主（安卓 App / Windows 桌面 / 第三方应用）只要提供
 * [StrAgentHostContext] 声明的端口，即可把它作为一个能力单元接入，无需了解内部引擎。
 *
 * 这与 DeepSeek Harness 的「everything is a plugin」同构：harness 核心只负责装载 / 卸载 /
 * 路由，所有能力都在插件里。本项目里 [StrAgentHost] 即那个极薄的 harness 内核。
 */

/** 宿主必须提供的端口（host contract）。纯 JVM 类型，跨端可复用。 */
data class StrAgentHostContext(
    /** OpenAI 兼容 /chat/completions 实现 */
    val llm: LlmClient,
    /** 键值偏好存储（引擎与插件注册表共用） */
    val prefs: PrefsStore,
    /** 经典流水线与报告草稿的键值文档存储（DatasetStore 端口） */
    val kv: DatasetStore,
    /** 用户插件目录（filesDir/plugins）；跨端统一为 File */
    val pluginDir: File,
    /** 模型解析：插件请求的 model（可能 null=默认）映射到具体 model id */
    val modelResolver: (requested: String?) -> String = { it ?: "default" },
    /** 可选：检索客户端（追问/报告联网搜索；null = 不启用） */
    val search: SearchClient? = null,
    /** 可选：嵌入客户端（T-100 文本追问 hybrid 检索；null = BM25 单路既有行为） */
    val embedding: com.stragent.core.ports.EmbeddingClient? = null,
    /** 可选：重排客户端（T-101 检索精排；null = hybrid 融合序直出） */
    val rerank: com.stragent.core.ports.RerankClient? = null,
    /** 可选：查询分解器（T-113 隐式多跳检索侧；null = 单查询既有行为） */
    val queryDecomposer: com.stragent.core.ports.QueryDecomposer? = null,
    /** 可选：块级上下文前缀（T-114 Anthropic Contextual Retrieval 完整版；null = 段号前缀既有行为） */
    val chunkContext: com.stragent.core.ports.ChunkContextClient? = null,
    /** 可选：块摘要器（T-117 RAPTOR 摘要节点层；null = 粗层原块文本既有行为） */
    val blockSummarizer: com.stragent.core.ports.BlockSummarizer? = null,
    /** 进度回调：phase ∈ {agent,tool,analyzing,...}；progress 为章节进度（UI 展示用） */
    val progressListener: ((phase: String, progress: ChapterProgress?) -> Unit)? = null,
    /**
     * 正文增量回调（token 级流式）：报告**写出过程中**的字，客户端据此实时显示 / 判断值不值得等。
     * null = 不要流式（引擎侧连请求形态都不改，走单次通道）。
     */
    val bodyListener: ((BodyDelta) -> Unit)? = null,
    /**
     * 断点目录（T-050）：agent 每个阶段落一份状态，崩溃/重启后从最后一步续跑。
     * null = 不落盘（第三方宿主不提供目录时保持旧行为）。宿主应放在应用私有目录下。
     */
    val checkpointDir: File? = null,
    /**
     * **报告引擎注册表**（T-145）：kind → 引擎工厂。宿主可用
     * `ReportEngineRegistry.BUILT_IN.with("my-engine") { … }` 注册**自己的**引擎，
     * 插件会按 `plugin.kind` 选中它——**不改任何分发点**。
     *
     * 此前这个扩展点在**生产装配路径上不可注入**：`install()` 构造编排器时不传它、
     * 契约里也没有对应字段——`with()` 只能被测试用。
     */
    val reportEngines: com.stragent.agent.ReportEngineRegistry = com.stragent.agent.ReportEngineRegistry.BUILT_IN,
    /**
     * **报告类型登记表**（T-145）：宿主可用
     * `ReportTypeRegistry.BUILT_IN.with(profile)` 注册**自己的**报告类型，
     * 它会影响简报预算（`briefFactor`）、章节模板与目标字数。
     *
     * 同上：此前契约里没有字段、叶子函数的 `types` 参数也从没有任何调用方传过
     * （恒为 `BUILT_IN`）——扩展点**双重失效**。
     */
    val reportTypes: com.stragent.core.report.ReportTypeRegistry = com.stragent.core.report.ReportTypeRegistry.BUILT_IN,
)

/** 宿主需求声明（写进插件清单，供外部 harness 校验「我能不能装这个插件」） */
data class HostRequirement(val name: String, val type: String, val description: String)

/** 插件对外声明的一项能力（宿主可枚举，无需编译期依赖） */
data class PluginCapability(
    val id: String,
    /** agent-report | agent-followup | agent-review | agent-editorial | service */
    val kind: String,
    val description: String,
    /** 可选：输入 JSON Schema 字符串（STEPX 能力 / MCP 工具用） */
    val inputSchemaJson: String? = null,
)

/** 一次声明式调用（宿主 → 插件，传输层无关；适合跨进程 / 跨语言） */
data class PluginRequest(
    val capability: String,
    val payload: Map<String, Any?> = emptyMap(),
)

/** 声明式调用结果 */
data class PluginResponse(
    val ok: Boolean,
    val payload: Map<String, Any?> = emptyMap(),
    val error: String? = null,
)

/** 插件形态的最小契约：自我描述 + 生命周期 + 能力面 + 调用入口 */
interface HostPlugin {
    val manifest: StrAgentPluginManifest
    fun install(ctx: StrAgentHostContext)
    fun activate()
    fun deactivate()
    fun uninstall()
    fun capabilities(): List<PluginCapability>
    fun invoke(request: PluginRequest): PluginResponse
}
