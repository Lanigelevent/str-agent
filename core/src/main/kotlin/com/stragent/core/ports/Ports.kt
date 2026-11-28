package com.stragent.core.ports

import kotlinx.serialization.json.JsonObject

/**
 * core/ 的出端口（ports）：Str Agent逻辑对存储 / 模型 / 检索的依赖全部走这里。
 * data/ 模块给真实实现，测试给 fake —— 替代 TS 时代「每个套件各自 mock
 * window.localStorage」的样板（186 个套件里的头号重复劳动）。
 */
interface DatasetStore {
    fun put(key: String, value: String)
    fun get(key: String): String?
    fun remove(key: String)
    fun keys(): List<String>
}

interface LlmClient {
    /** OpenAI 兼容 /chat/completions；返回 assistant 文本与 token 用量 */
    fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int): ChatResult

    /**
     * 流式生成：正文按 token 增量交给 [onToken]，返回值与 [chat] 同口径（全文 + 用量 + 截断位）。
     *
     * 三条口径，缺一不可：
     *  1. `onToken == null` = 调用方**明确不要**流式，实现应直接走 [chat]（不为此路径改请求形态）；
     *  2. 增量拼接起来必须与返回的 `text` 逐字对得上（trim 只允许发生在最终值上，
     *     否则「客户端已显示的」与「落库的」会差一截，用户看到的就是假账）；
     *  3. 默认实现 = [chat] + 整段一次性回调 —— **没有流式能力的实现不必改动**，
     *     调用方拿到的仍是「至少一次、内容完整」的增量，只是不够细。
     *
     * 真正的逐 token 实现在 `data/HttpLlmClient`（SSE）。
     */
    fun streamChat(
        model: String,
        messages: List<Pair<String, String>>,
        temperature: Double,
        maxTokens: Int,
        onToken: ((String) -> Unit)?,
    ): ChatResult {
        if (onToken == null) return chat(model, messages, temperature, maxTokens)
        val result = chat(model, messages, temperature, maxTokens)
        if (result.text.isNotEmpty()) onToken(result.text)
        return result
    }

    /**
     * 原生工具调用可用吗？（能力探测，**默认 false**）
     *
     * false = AgentLoop 走既有的 JSON 文本协议，行为与 T-049 之前逐字一致——
     * 所有 fake 与不支持原生协议的实现都不用改一行代码。
     * 真实现（`data/HttpLlmClient`）按用户开关覆写；开关默认关闭（见 D6「原生工具协议」）：
     * 协议本身可测，但报告质量需要真实模型抽样（T-052）后才敢默认切换。
     */
    fun supportsNativeTools(model: String): Boolean = false

    /**
     * 原生工具调用一轮（OpenAI `tools` / `tool_choice` / `response_format` 协议）。
     *
     * 默认实现 = [chat] 并标 `native = false`：**不支持原生工具的客户端无需改动**，
     * 调用方拿到 `native = false` 后回落文本协议（同一份对话，模型看到的是文本协议提示词）。
     *
     * [toolChoice]：`auto` / `none` / `required`，或一个工具名（强制调用该工具——
     * AgentLoop 在步数见底时用它强推 finish）。[responseFormat]：`json_object` 等 OpenAI 取值，null = 不传。
     */
    fun chatTools(
        model: String,
        messages: List<TurnMessage>,
        temperature: Double,
        maxTokens: Int,
        tools: List<ToolSpec>,
        toolChoice: String = "auto",
        responseFormat: String? = null,
    ): ToolTurn {
        val result = chat(model, messages.map { it.role to it.content }, temperature, maxTokens)
        return ToolTurn(result.text, emptyList(), result.totalTokens, result.truncated, native = false)
    }
}

/** 原生工具声明（`tools[].function` 的裁剪映射；[parameters] 为 JSON Schema） */
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

/**
 * 模型发起的一次工具调用。
 * [arguments] 保留**原始 JSON 字符串**——模型可能吐坏 JSON，解析与报错归调用方
 * （这样「参数坏了」能作为工具结果回填给模型修，而不是让整轮失败）。
 */
data class ToolCall(val id: String, val name: String, val arguments: String)

/**
 * 原生对话的一轮消息。比 [LlmClient.chat] 的 Pair 多两样东西：
 * assistant 轮的 [toolCalls]、tool 结果对应的 [toolCallId]（OpenAI 协议要求逐条对账）。
 */
data class TurnMessage(
    val role: String,
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
)

/** 一轮原生工具对话的结果；[native] = false 表示本次没走原生协议（端点不支持），调用方应回落文本协议 */
data class ToolTurn(
    val text: String,
    val toolCalls: List<ToolCall>,
    val totalTokens: Int,
    val truncated: Boolean = false,
    val native: Boolean,
)

data class ChatResult(
    val text: String,
    val totalTokens: Int,
    /** finish_reason === 'length'：输出顶到 max_tokens 被切断（续写判据，如实上报） */
    val truncated: Boolean = false,
    /**
     * 本次实际使用的模型名（T-232）：由 LLM 客户端层填（真实端点/服务商返回口径）。
     *
     * 缺省 null = 调用方没提供（假模型/老实现）——按模型分面的用量会落到 "unknown"。
     * 给默认值的用意：既有实现零改动；JVM 签名变化按 BCV 注记（Kotlin 源兼容）。
     */
    val model: String? = null,
)

interface SearchClient {
    fun search(query: String): List<SearchHit>
}

/**
 * 嵌入端口（T-100）：文本 → 向量。与 [LlmClient] 同构但独立——嵌入的模型/计价/限流
 * 都不同，且并非所有部署都配置；null = 未启用（检索退回 BM25 纯词法，既有行为零变化）。
 *
 * 批量契约：一次调用嵌入一组文本（OpenAI 兼容 /embeddings 的 input 数组形态），
 * 返回顺序与输入一一对应。失败抛异常，由调用方决定回落策略（诚实降级，不静默吞）。
 */
interface EmbeddingClient {
    fun embed(texts: List<String>): List<DoubleArray>
}

/**
 * 重排端口（T-101）：检索管线第三级——hybrid 召回 top-M 后按「查询-文档相关性」精排取 top-K。
 * 行业依据：rerank 是 Anthropic 67% 检索失败率降幅的第三项（contextual prefixes + hybrid + rerank）。
 * null = 未启用（hybrid 融合序直接出结果，既有行为零变化）；失败由调用方回落融合序（诚实降级）。
 */
/** 重排结果项：[index] 对应候选下标，[score] 为服务端相关性分（跨服务商不可比，仅本回合内比较） */
data class RerankHit(val index: Int, val score: Double)

interface RerankClient {
    /**
     * 对候选按与查询的相关性重排，返回按 [RerankHit.score] 降序的命中列表。
     * T-101b：升级为带分数——corrective 质量闸需要「最高分低于阈值 → 诚实回答材料可能不相关」，
     * 阈值由调用方按所选服务商调（env），端口只负责如实带出。
     */
    fun rerank(query: String, candidates: List<String>): List<RerankHit>
}

/**
 * 查询分解端口（T-113）：把复合/多跳问题拆成可独立检索的子查询——隐式多跳的检索侧
 * （P-151 调研结论「分解式改写可进 rewrite 模式族」的落地挂载点）。
 * subQueries = null 表示放弃分解（问题非多跳/解析不出 ≥2 个有效子查询）→ 单查询既有行为零变化。
 */
data class DecomposeResult(val subQueries: List<String>?, val totalTokens: Int)

/**
 * 分解器为无状态 fun interface：llm/model 逐调用传入（追问的 model 参数随回合变化），
 * 失败抛异常由调用方决定回落（诚实降级，不静默吞——与 EmbeddingClient/RerankClient 同纪律）。
 */
fun interface QueryDecomposer {
    fun decompose(llm: LlmClient, model: String, question: String, cancelSignal: () -> Boolean): DecomposeResult
}

/**
 * 块级上下文前缀客户端（T-114，Anthropic Contextual Retrieval 完整版）：
 * 对每个材料块生成一句「放回文档全局语境」的补语，拼进检索索引文本——词法（BM25）与
 * 语义（嵌入）两路索引同时受益（行业数据：单此项 top-20 检索失败率 35%→49% 降幅的大头）。
 * 返回与输入**等长对齐**；null = 该块未产出上下文（解析失败/缺失）→ 该块索引文本零变化。
 * 失败抛异常由调用方决定回落（诚实降级，不静默吞）。
 */
fun interface ChunkContextClient {
    fun contextsFor(llm: LlmClient, model: String, chunks: List<String>, cancelSignal: () -> Boolean): List<String?>
}

/**
 * 块摘要端口（T-117，RAPTOR 摘要节点层）：对每个粗粒度块产出一段检索用要点摘要。
 * 摘要**只进检索索引**（替换 T-116 的原块索引文本），绝不进材料回填与门禁 brief——
 * 生成物的数字不是数据（零编造纪律不因此松动）；命中的回填单位仍是块成员**原文段**。
 * 返回与输入等长对齐；null = 该块摘要未产出（失败/缺失）→ 该块回落原块索引文本（T-116 行为）。
 * 失败抛异常由调用方决定回落（诚实降级，不静默吞）。
 */
fun interface BlockSummarizer {
    fun summariesFor(llm: LlmClient, model: String, blocks: List<String>, cancelSignal: () -> Boolean): List<String?>
}

data class SearchHit(val title: String, val url: String, val snippet: String)

interface PrefsStore {
    fun get(key: String, fallback: String): String
    fun set(key: String, value: String)
    /** 清除一个偏好（如「清除 Key」）；默认空实现——测试 fake 按需覆写 */
    fun remove(key: String) {}
}
