package com.stragent.data

import com.stragent.core.config.Config
import com.stragent.core.observability.StructuredLogger
import com.stragent.core.observability.StrAgentMetrics
import com.stragent.core.ai.HedgeBudget
import com.stragent.core.ai.HedgedCall
import com.stragent.core.ai.StepError
import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.ToolCall
import com.stragent.core.ports.ToolSpec
import com.stragent.core.ports.ToolTurn
import com.stragent.core.ports.TurnMessage
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * LlmClient 的 HTTP 实现（OpenAI 兼容 /chat/completions）——stepfun.ts requestChat 的移植。
 *
 * 用 HttpURLConnection 而不是 OkHttp：请求形态就一个 POST + Bearer 头，读超时 180 秒
 * （T-062 从 60 秒提高：推理模型的长报告一次动辄 100 秒以上），
 * 「能不引库就不引库」的纪律下 JDK 自带的够用。换服务商 = StepfunConfig 里换 baseUrl，
 * 这里不感知厂商。
 *
 * 截断判定（finish_reason === 'length'）如实上报——深度报告顶到 max_tokens 时
 * 调用方（chatStep）要靠它决定续写。
 *
 * **三条通道**：
 *  - [chat]：`stream: false`，整包 JSON 一次读完 —— 追问 / 检索词 / 审查等短返回都走这条，
 *    形态不变、行为不变；
 *  - [streamChat]：`stream: true` + `Accept: text/event-stream`，逐行解 SSE 的
 *    `choices[0].delta.content`，边读边把片段交给调用方。
 *    回落纪律：**一个片段都没发出去**时（传输异常 / 服务端无视 stream 返整包 JSON）
 *    自动退回 [chat]——流式是增强，不该让报告因为增强而失败；
 *    **已经发出去过片段**时不回落，直接把中断如实抛出（此时重写第二遍会和客户端
 *    已显示的片段打架，交给上层按章重试才是正确处置）。
 *    状态类错误（401/429/400…）**不回落**：回落也只是同一个错误。
 *    「正文写完」的判据 = 收到 `data: [DONE]` 或末包带 `finish_reason`；两者都没有 = 连接
 *    提前结束，半截正文不算完成（静默截断就是假账）。
 *  - [chatTools]：OpenAI 原生 `tools` / `tool_choice` / `response_format`（T-049）。
 *    端点不认 tools（400）时**自动改打纯文本**并标 `native = false`，由 AgentLoop 回落
 *    文本协议——工具是增强，不能让报告因此失败。开关默认关闭（见 [NATIVE_TOOLS_STORAGE]）。
 *
 * **错误分类与退避**（T-049）：429 / 5xx / 连接建立失败可重试（500ms、1.5s 指数退避，
 * 优先服从 `Retry-After`，上限 10 秒）；鉴权、参数错误、**读超时**不重试——一次已经等了
 * 180 秒（T-062），再等三轮只会把用户拖死。分类值见 StepfunConfig 的 STEP_ERR_*。
 *
 * Key 的编码/解码钩子由宿主注入（默认恒等）：桌面宿主用本机密钥文件（见 host/JvmKeyStore），
 * 环境变量注入的 Key 也走同一条钩子，避免「设置页写进去、客户端读不出来」的两套口径。
 */
class HttpLlmClient(
    private val prefs: com.stragent.core.ports.PrefsStore,
    encrypt: (String) -> String = { it },
    decrypt: (String) -> String = { it },
    /** 覆盖 base URL（自建网关 / 契约测试的本地桩用；默认取当前服务商） */
    private val baseUrlOverride: String? = null,
    /** 流式读的不活跃上限（T-133 看门狗；契约测试注入小值用） */
    private val streamInactivityMs: Long = 180_000L,
    /**
     * 对冲配额比例（T-137）：每 1 次已完成调用允许多少个对冲副本；**0 = 关闭**。
     *
     * 默认读 env `STRAGENT_LLM_HEDGE_RATIO`；显式传参优先（契约测试用）。
     */
    private val hedgeRatio: Double = envHedgeRatio(),
    /**
     * 对冲延迟阈值（毫秒）：主副本超过它还没回就发副本；**0 = 不对冲**。
     *
     * 默认读 env `STRAGENT_LLM_HEDGE_AFTER_MS`；显式传参优先（契约测试用）。
     */
    private val hedgeAfterMs: Long = envHedgeAfterMs(),
    /** finish_reason 之后的尾帧等待上限（毫秒；契约测试注入小值用） */
    private val finishTailWaitMs: Long = FINISH_TAIL_WAIT_DEFAULT_MS,
    /**
     * 退避重试的睡眠函数（契约测试注入记录器，避免真睡）。
     * 生产用 Thread.sleep：调用线程正在等这份结果，同步重试是天经地义的选择。
     */
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : LlmClient {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * T-133 迁移：java.net.http.HttpClient——内建连接池 + HTTP/2 多路复用（与 T-129 并行扇出
     * 协同：多路并发调用复用同一连接）。连接池由 JDK 管理，T-126 的 reusable/disconnect
     * 手工纪律随之取消（健康连接自动回收复用）。
     */
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS.toLong()))
        .build()

    /** LLM 调用计数注册表（T-161 残余）：bootstrap 在 OTel 装配后接线；null = 不记 */
    /** LLM 调用计数注册表（T-161 残余）：bootstrap 在 OTel 装配后接线；null = 不记（host/data 不在 apiCheck 覆盖内，无 dump 负担） */
    var llmMetrics: com.stragent.core.observability.OtelMetrics? = null

    /** T-232：按**精确模型名**的成本账本（由解析成功处记录，与 llm.calls 的 attempt 口径不同） */
    var usageLedger: com.stragent.core.observability.UsageLedger? = null

    /** 从请求 payload 提取精确模型名（HTTP 层计数用；收敛标签再做 metric label） */
    private fun modelOf(payload: String): String {
        val marker = "\"model\""
        val at = payload.indexOf(marker)
        if (at < 0) return "unknown"
        val colon = payload.indexOf(':', at + marker.length)
        if (colon < 0) return "unknown"
        val quoteStart = payload.indexOf('"', colon + 1)
        if (quoteStart < 0) return "unknown"
        val quoteEnd = payload.indexOf('"', quoteStart + 1)
        if (quoteEnd < 0) return "unknown"
        return payload.substring(quoteStart + 1, quoteEnd)
    }

    /** LLM 调用计数（attempt 口径：HTTP 层，含重试；model 标签经族收敛防止 series 溢出） */
    private fun countLlmAttempt(payload: String, ok: Boolean) {
        val model = modelOf(payload)
        llmMetrics?.counter(com.stragent.core.observability.StrAgentMetrics.LLM_CALLS)
            ?.add(
                1,
                mapOf(
                    "vendor" to config.getVendorId(),
                    "model" to com.stragent.core.observability.StrAgentMetrics.modelFamilyLabel(model),
                    "outcome" to if (ok) "ok" else "error",
                ),
            )
    }

    /** 按精确模型名记一次**逻辑调用**（解析成功处；tokens 为真实用量） */
    private fun recordLlmUsage(model: String, tokens: Int) {
        usageLedger?.recordLlmCall(model, tokens, ok = true)
    }

    private val config = StepfunConfig(prefs, encrypt, decrypt)

    private fun completionsUrl(): String = "${baseUrlOverride ?: config.getVendor().baseUrl}/chat/completions"

    /** 没配 Key 的统一前置检查（两条通道同一句提示，不泄漏任何 Key 片段） */
    private fun requireApiKey(message: String = "还没有配置 ${config.getVendor().keyLabel}"): String {
        val apiKey = config.getApiKey()
        if (apiKey.isEmpty()) throw StepError(STEP_ERR_NO_KEY, message)
        return apiKey
    }

    /**
     * 对冲闸门与执行器（T-137）。
     *
     * **默认关闭**（比例 0）：对冲是**成本换尾延迟**，不替用户默认多付钱。
     * 打开需显式设 env `STRAGENT_LLM_HEDGE_RATIO`（>0 才生效）。
     *
     * 比例是**唯一**的成本上界来源（见 [HedgeBudget]）；阈值是「多慢才值得发副本」。
     */
    private val hedgeBudget = HedgeBudget(hedgeRatio)
    private val hedgedCall = HedgedCall(
        budget = hedgeBudget,
        hedgeAfterMs = hedgeAfterMs,
    )

    private fun messagePayload(
        model: String,
        messages: List<Pair<String, String>>,
        temperature: Double,
        maxTokens: Int,
        stream: Boolean,
    ): String = messagePayload(
        model,
        messages.map { TurnMessage(it.first, it.second) },
        temperature,
        maxTokens,
        stream,
    )

    /**
     * 请求体拼装（三条通道共用）。
     *
     * [tools] 非空时才写 `tools` 与 `tool_choice`（OpenAI 协议要求 tool_choice 依附于 tools）；
     * assistant 轮的 `tool_calls` 与 tool 结果的 `tool_call_id` 逐条回填——少一个，服务端会
     * 以 400 拒绝整段对话。`response_format` 只在调用方显式要求时出现，不污染普通请求。
     */
    private fun messagePayload(
        model: String,
        messages: List<TurnMessage>,
        temperature: Double,
        maxTokens: Int,
        stream: Boolean,
        tools: List<ToolSpec> = emptyList(),
        toolChoice: String? = null,
        responseFormat: String? = null,
    ): String = buildJsonObject {
        put("model", model)
        put("messages", buildJsonArray {
            messages.forEach { message ->
                add(buildJsonObject {
                    put("role", message.role)
                    put("content", message.content)
                    if (message.toolCalls.isNotEmpty()) {
                        put("tool_calls", buildJsonArray {
                            message.toolCalls.forEach { call ->
                                add(buildJsonObject {
                                    put("id", call.id)
                                    put("type", "function")
                                    put("function", buildJsonObject {
                                        put("name", call.name)
                                        put("arguments", call.arguments)
                                    })
                                })
                            }
                        })
                    }
                    message.toolCallId?.let { put("tool_call_id", it) }
                })
            }
        })
        put("temperature", temperature)
        put("max_tokens", maxTokens)
        // 16384 是"大多数模型都支持"的一档；真超了还有 chatStep 的续写兜底
        put("stream", stream)
        if (stream) {
            // 流式下 usage 默认不随包回来（token 账会变成 0 = 假账）；显式要求末包带上
            put("stream_options", buildJsonObject { put("include_usage", true) })
        }
        if (tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                tools.forEach { spec ->
                    add(buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", spec.name)
                            put("description", spec.description)
                            put("parameters", spec.parameters)
                        })
                    })
                }
            })
            put("tool_choice", toolChoiceElement(toolChoice ?: "auto"))
        }
        if (responseFormat != null) {
            put("response_format", buildJsonObject { put("type", responseFormat) })
        }
    }.toString()

    /**
     * 整包通道（`stream: false`）——追问 / 检索词 / 审查等短返回走这条。
     *
     * **T-137 对冲只挂在这条通道**：它不吐片段，「先到先用」不会让客户端收到重复内容
     * （流式通道的「增量拼接 = 最终正文」是硬契约，两个副本同时吐片段 = 内容重复 = 假账）。
     */
    override fun chat(
        model: String,
        messages: List<Pair<String, String>>,
        temperature: Double,
        maxTokens: Int,
    ): ChatResult {
        val apiKey = requireApiKey()
        // T-195 PII Shield：出站脱敏 / 入站还原（默认关；映射随单次请求存亡）
        val mapping = com.stragent.core.security.PiiShield.Mapping()
        val shieldOn = com.stragent.core.security.PiiShield.enabled(prefs)
        val effective = if (shieldOn) messages.map { it.first to com.stragent.core.security.PiiShield.maskText(it.second, mapping) } else messages
        val payload = messagePayload(model, effective, temperature, maxTokens, stream = false)
        // 对冲关闭时 run() 就是单副本调用，与既有行为逐字节一致
        val body = hedgedCall.run({ postCompletion(payload, apiKey) })
        val result = readCompletion(body, model)
        return if (shieldOn) result.copy(text = com.stragent.core.security.PiiShield.restore(result.text, mapping)) else result
    }

    override fun supportsNativeTools(model: String): Boolean =
        prefs.get(NATIVE_TOOLS_STORAGE, "off").equals("on", ignoreCase = true)

    /**
     * 原生工具调用（T-049）。
     *
     * 失败回落只在**端点不认 tools/response_format**（400）时发生：同一份对话去掉工具相关
     * 字段再打一次纯文本通道，`native = false` 交还调用方——AgentLoop 会接着走文本协议。
     * 此时可能多付一次调用：相比「工具不被支持 → 整份报告失败」，这个代价是值得的。
     */
    override fun chatTools(
        model: String,
        messages: List<TurnMessage>,
        temperature: Double,
        maxTokens: Int,
        tools: List<ToolSpec>,
        toolChoice: String,
        responseFormat: String?,
    ): ToolTurn {
        val apiKey = requireApiKey()
        // T-195 PII Shield：消息面出站脱敏（工具参数回执入站还原）
        val mapping = com.stragent.core.security.PiiShield.Mapping()
        val shieldOn = com.stragent.core.security.PiiShield.enabled(prefs)
        val effective = if (shieldOn) messages.map { it.copy(content = com.stragent.core.security.PiiShield.maskText(it.content, mapping)) } else messages
        val payload = messagePayload(
            model, effective, temperature, maxTokens, stream = false,
            tools = tools, toolChoice = toolChoice, responseFormat = responseFormat,
        )
        val body = try {
            postCompletion(payload, apiKey)
        } catch (e: StepError) {
            if (e.code != STEP_ERR_BAD_REQUEST || tools.isEmpty()) throw e
            // 回落只在首轮有意义：AgentLoop 拿到 native=false 后会改走文本协议，
            // 不会带着 tool 角色消息再进这条通道。这里顺手剥掉工具字段，保证请求合法。
            val plainMessages = messages.filter { it.role != "tool" && it.toolCalls.isEmpty() }
            val fallbackBody = postCompletion(
                messagePayload(model, plainMessages, temperature, maxTokens, stream = false),
                apiKey,
            )
            val fallback = readCompletion(fallbackBody, model)
            val fbText = if (shieldOn) com.stragent.core.security.PiiShield.restore(fallback.text, mapping) else fallback.text
            return ToolTurn(fbText, emptyList(), fallback.totalTokens, fallback.truncated, native = false)
        }
        val turn = readToolTurn(body)
        return if (shieldOn) turn.copy(
            text = com.stragent.core.security.PiiShield.restore(turn.text, mapping),
            toolCalls = turn.toolCalls.map { it.copy(arguments = com.stragent.core.security.PiiShield.restore(it.arguments, mapping)) },
        ) else turn
    }

    /**
     * 流式生成（SSE）：正文按 token 交给 [onToken]。
     *
     * 增量拼接 = 最终正文（只在返回时 trim 一次），这是调用方敢把片段直接贴到屏幕上的前提。
     * `onToken == null` 时明确不走流式通道，直接 [chat]。
     */
    override fun streamChat(
        model: String,
        messages: List<Pair<String, String>>,
        temperature: Double,
        maxTokens: Int,
        onToken: ((String) -> Unit)?,
    ): ChatResult {
        if (onToken == null) return chat(model, messages, temperature, maxTokens)
        val apiKey = requireApiKey()
        // T-195 PII Shield：出站脱敏（映射跨整条流）；增量帧原样直出（占位符可能跨帧劈开，
        // 终稿还原——诚实边界记 D4），回落单次通道复用同一份映射
        val mapping = com.stragent.core.security.PiiShield.Mapping()
        val shieldOn = com.stragent.core.security.PiiShield.enabled(prefs)
        val effective = if (shieldOn) messages.map { it.first to com.stragent.core.security.PiiShield.maskText(it.second, mapping) } else messages
        val payload = messagePayload(model, effective, temperature, maxTokens, stream = true)
        // 首包之前的失败（429/5xx/连接建立失败）允许退避重试：此时一个字都没发出去，
        // 重试不会和客户端已显示的内容打架。进入读循环后不再重试（见下方中断语义）。
        val resp = withRetry { openStream(payload, apiKey) }
        // T-133：流式读的每读活跃看门狗——HttpClient 的超时止于响应头，流内读取无超时；
        // WatchedInputStream 保持 T-062 的每读 180s 语义（停滞流被 close 强制中断）
        val body = WatchedInputStream(resp.body(), inactivityMs = streamInactivityMs)
        val text = StringBuilder()
        var emitted = false
        var finishReason: String? = null
        var totalTokens = 0
        var completionTokens: Int? = null
        var reasoningTokens: Int? = null
        var sawDone = false
        try {
            BufferedReader(InputStreamReader(body, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith(SSE_DATA_PREFIX)) continue // 心跳 / event: / 空行
                    val data = line.substring(SSE_DATA_PREFIX.length).trim()
                    if (data == SSE_DONE) {
                        sawDone = true
                        break
                    }
                    val chunk = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                    val choice = chunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    val delta = runCatching {
                        choice?.get("delta")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                    }.getOrNull()
                    if (!delta.isNullOrEmpty()) {
                        text.append(delta)
                        emitted = true
                        onToken(delta)
                    }
                    val usageSeen = runCatching {
                        chunk["usage"]?.jsonObject?.let { usage ->
                            usage["total_tokens"]?.jsonPrimitive?.intOrNull?.let { totalTokens = it }
                            usage["completion_tokens"]?.jsonPrimitive?.intOrNull?.let { completionTokens = it }
                            usage["completion_tokens_details"]?.jsonObject
                                ?.get("reasoning_tokens")?.jsonPrimitive?.intOrNull
                                ?.let { reasoningTokens = it }
                            true
                        } ?: false
                    }.getOrDefault(false)
                    runCatching {
                        choice?.get("finish_reason")?.jsonPrimitive?.takeIf { it.isString }?.content
                    }.getOrNull()?.let {
                        finishReason = it
                        // T-133：finish_reason = 供应商侧完成信号——正文到此为止，之后只剩尾帧
                        // （usage / [DONE]）。有些网关不发 [DONE] 且把连接挂着不关，须有界收尾
                        // （契约⑤的语义）：给「正文完成之后」这一段等待设一个**绝对上限**。
                        // 用上限而非收窄不活跃窗口：正文此刻已读完，砍不到它；而收窄窗口会
                        // 把 usage 帧的迟到余量一并压掉（usage 按协议就排在 finish_reason 之后）
                        // → usage 被砍 → token 账记 0 = 假账。
                        body.capTotalWait(finishTailWaitMs)
                    }
                    // usage 帧已到 = 尾帧收齐，正文与 token 账都齐了 → 不必再等 [DONE]
                    // （网关不发 [DONE] 时，等到天荒地老也等不到）。
                    if (usageSeen && finishReason != null) break
                }
            }
            if (!emitted) {
                // 一个字都没发出去 → 回落单次通道（多半是网关无视 stream、返了整包 JSON）
                return fallbackToSingleShot(model, effective, temperature, maxTokens, onToken)
            }
            // 「正文写完」的判据：收到 [DONE] 或末包带 finish_reason。两者都没有 = 连接提前结束，
            // 半截正文**不能**当成完成——如实报中断，让上层按章重试（此时不回落：客户端已看到片段，
            // 重打一遍会和已显示的内容打架，静默截断更是假账）。
            if (!sawDone && finishReason == null) {
                throw StepError(
                    STEP_ERR_NETWORK,
                    "流式响应中断（已写出 ${text.length} 字）：连接在正文写完前结束，请重试",
                )
            }
            if (text.isBlank()) {
                throw blankContentFailure(finishReason, completionTokens, reasoningTokens, totalTokens)
            }
            recordLlmUsage(model, totalTokens)
            return ChatResult(
                text = if (shieldOn) com.stragent.core.security.PiiShield.restore(text.toString().trim(), mapping) else text.toString().trim(),
                totalTokens = totalTokens,
                truncated = finishReason == "length",
                model = model,
            )
        } catch (e: StepError) {
            throw e
        } catch (e: Exception) {
            if (!emitted) return fallbackToSingleShot(model, effective, temperature, maxTokens, onToken)
            // 宽限期内看门狗关流 = finish_reason 后再无尾帧 → 正常结束（不发 [DONE] 的网关）
            if (finishReason != null) {
                recordLlmUsage(model, totalTokens)
                return ChatResult(
                    text = if (shieldOn) com.stragent.core.security.PiiShield.restore(text.toString().trim(), mapping) else text.toString().trim(),
                    totalTokens = totalTokens,
                    truncated = finishReason == "length",
                    model = model,
                )
            }
            throw StepError(
                STEP_ERR_NETWORK,
                "流式响应中断（已写出 ${text.length} 字）：${e.message ?: "请重试"}",
            )
        }
    }

    /**
     * 回落单次通道。
     *
     * 回落**不吞片段**：整段正文照样交给 [onToken]，「片段拼接 = 最终正文」这条口径
     * 不能因为走了备用路径就断掉（否则客户端屏幕上是一片空白，落库的却是完整报告）。
     */
    private fun fallbackToSingleShot(
        model: String,
        messages: List<Pair<String, String>>,
        temperature: Double,
        maxTokens: Int,
        onToken: (String) -> Unit,
    ): ChatResult {
        val result = chat(model, messages, temperature, maxTokens)
        if (result.text.isNotEmpty()) onToken(result.text)
        return result
    }

    /** 实时拉取当前账户可用的模型列表（GET {baseUrl}/models），设置页「检查模型更新」用 */
    fun fetchLiveModels(): List<String> {
        val vendor = config.getVendor()
        val apiKey = requireApiKey("还没有配置 ${vendor.keyLabel}，无法查询模型列表")
        val body = withRetry { getBody("${vendor.baseUrl}/models", apiKey) }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw StepError(STEP_ERR_NETWORK, "模型列表返回了无法解析的内容，请重试") }
        val data = root["data"]?.jsonArray ?: return emptyList()
        return data.mapNotNull { item ->
            item.jsonObject["id"]?.jsonPrimitive?.takeIf { it.isString }?.content
        }.distinct()
    }

    /**
     * POST 一次 /chat/completions：**单次尝试**把 4xx/5xx 映射成分类错误，
     * 再交给 [withRetry] 按 retryable 判据决定是否退避重试（POST / GET / 流式首包共用同一套节奏）。
     */
    private fun postCompletion(payload: String, apiKey: String): String = withRetry {
        try {
            val resp = http.send(
                postRequest(completionsUrl(), apiKey, accept = "application/json", payload = payload),
                HttpResponse.BodyHandlers.ofString(Charsets.UTF_8),
            )
            val code = resp.statusCode()
            if (code >= 400) {
                countLlmAttempt(payload, ok = false)
                throw mapHttpFailure(code, resp.body(), retryAfterMs(resp))
            }
            // D25 T-161 残余：LLM 调用计数（attempt 口径）；T-232 补 model 标签（族收敛）
            countLlmAttempt(payload, ok = true)
            resp.body()
        } catch (e: StepError) {
            throw e
        } catch (e: java.net.http.HttpTimeoutException) {
            throw timeoutFailure(java.net.SocketTimeoutException(e.message))
        } catch (e: java.net.ConnectException) {
            throw networkFailure(e)
        } catch (e: java.io.IOException) {
            throw networkFailure(e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw networkFailure(e)
        }
    }

    /** 流式通道的**首包前**阶段：发请求、检查状态码；成功才把响应体（SSE 流）交出去 */
    private fun openStream(payload: String, apiKey: String): HttpResponse<java.io.InputStream> {
        try {
            val resp = http.send(
                postRequest(completionsUrl(), apiKey, accept = "text/event-stream", payload = payload),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
            val code = resp.statusCode()
            if (code >= 400) {
                countLlmAttempt(payload, ok = false)
                val errBody = resp.body().readBytes().let { String(it, Charsets.UTF_8) }
                throw mapHttpFailure(code, errBody, retryAfterMs(resp))
            }
            // T-232：流式通道的首包计数（此前只有非流式通道记 llm.calls）
            countLlmAttempt(payload, ok = true)
            return resp
        } catch (e: StepError) {
            throw e
        } catch (e: java.net.http.HttpTimeoutException) {
            throw timeoutFailure(java.net.SocketTimeoutException(e.message))
        } catch (e: java.net.ConnectException) {
            throw networkFailure(e)
        } catch (e: java.io.IOException) {
            throw networkFailure(e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw networkFailure(e)
        }
    }

    private fun getBody(url: String, apiKey: String): String {
        try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(READ_TIMEOUT_MS.toLong()))
                .header("Authorization", "Bearer $apiKey")
                .GET()
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
            val code = resp.statusCode()
            if (code >= 400) throw mapHttpFailure(code, resp.body(), retryAfterMs(resp))
            return resp.body()
        } catch (e: StepError) {
            throw e
        } catch (e: java.net.http.HttpTimeoutException) {
            throw timeoutFailure(java.net.SocketTimeoutException(e.message))
        } catch (e: java.net.ConnectException) {
            throw networkFailure(e)
        } catch (e: java.io.IOException) {
            throw networkFailure(e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw networkFailure(e)
        }
    }

    /**
     * 退避重试：只重试**标了 retryable** 的 StepError（429 / 5xx / 连接建立失败），
     * 最多 2 次（总 3 次尝试），延迟 500ms、1.5s；`Retry-After` 优先但封顶 10 秒。
     * 鉴权/参数/读超时一律不重试——重试只是把同一个失败再买一遍。
     */
    private fun <T> withRetry(block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: StepError) {
                if (!e.retryable || attempt >= RETRY_DELAYS_MS.size) throw e
                val delay = (e.retryAfterMs ?: RETRY_DELAYS_MS[attempt]).coerceIn(0L, MAX_RETRY_DELAY_MS)
                // D25 可观测性收口（gap-1）：重试此前完全不可见——长报告调用内部发生了什么
                // 只有最终回执。退避节奏/重试原因进结构化日志（不回显响应体与 Key）。
                StructuredLogger.getLogger("HttpLlmClient").warn(
                    "LLM 调用重试：第 ${attempt + 1} 次失败（${e.code}），${delay}ms 后重试",
                    "code" to e.code,
                    "attempt" to "${attempt + 1}",
                    "delayMs" to "$delay",
                )
                sleep(delay)
                attempt += 1
            }
        }
    }

    /** 两条通道共用的 POST 请求（只差 `Accept`）；连接池与超时由共享 [http] 客户端管理 */
    private fun postRequest(url: String, apiKey: String, accept: String, payload: String): HttpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMillis(READ_TIMEOUT_MS.toLong()))
            .header("Content-Type", "application/json")
            .header("Accept", accept)
            .header("Authorization", "Bearer $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(payload, Charsets.UTF_8))
            .build()

    private fun readCompletion(body: String, fallbackModel: String): ChatResult {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw StepError(STEP_ERR_NETWORK, "模型服务返回了无法解析的内容，请重试") }
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        val content = choice?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.takeIf { it.isString }?.content
        val finishReason = choice?.get("finish_reason")?.jsonPrimitive?.takeIf { it.isString }?.content
        val usage = runCatching { root["usage"]?.jsonObject }.getOrNull()
        val totalTokens = usage?.get("total_tokens")?.jsonPrimitive?.intOrNull ?: 0
        val completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull
        val reasoningTokens = runCatching {
            usage?.get("completion_tokens_details")?.jsonObject
                ?.get("reasoning_tokens")?.jsonPrimitive?.intOrNull
        }.getOrNull()
        if (content == null || content.isBlank()) {
            // T-062：空正文不再是笼统「请重试」——带 finish_reason 与 token 分解，区分「重试有用」与「预算永远不够」
            throw blankContentFailure(finishReason, completionTokens, reasoningTokens, totalTokens)
        }
        recordLlmUsage(fallbackModel, totalTokens)
        return ChatResult(
            text = content.trim(),
            totalTokens = totalTokens,
            // 深度报告很容易顶到 max_tokens：如实上报，别让用户以为报告本来就这么短
            truncated = finishReason == "length",
            model = fallbackModel,
        )
    }

    /** 原生通道的响应解析：`message.tool_calls` 逐条取出，`arguments` 保持原始字符串 */
    private fun readToolTurn(body: String): ToolTurn {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw StepError(STEP_ERR_NETWORK, "模型服务返回了无法解析的内容，请重试") }
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw StepError(STEP_ERR_NETWORK, "模型服务返回里没有 choices，请重试")
        val message = choice["message"]?.jsonObject
            ?: throw StepError(STEP_ERR_NETWORK, "模型服务返回里没有 message，请重试")
        val content = message["content"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: ""
        val toolCalls = message["tool_calls"]?.jsonArray?.mapNotNull { element ->
            val call = element as? JsonObject ?: return@mapNotNull null
            val function = call["function"]?.jsonObject ?: return@mapNotNull null
            val name = function["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            ToolCall(
                // 个别网关省略 id：兜一个稳定可读的值，回填时至少能对上账
                id = call["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "call_$name",
                name = name,
                arguments = function["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}",
            )
        } ?: emptyList()
        val usage = runCatching { root["usage"]?.jsonObject }.getOrNull()
        val totalTokens = usage?.get("total_tokens")?.jsonPrimitive?.intOrNull ?: 0
        val finishReason = choice["finish_reason"]?.jsonPrimitive?.takeIf { it.isString }?.content
        if (content.isBlank() && toolCalls.isEmpty()) {
            val completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull
            val reasoningTokens = runCatching {
                usage?.get("completion_tokens_details")?.jsonObject
                    ?.get("reasoning_tokens")?.jsonPrimitive?.intOrNull
            }.getOrNull()
            throw blankContentFailure(finishReason, completionTokens, reasoningTokens, totalTokens)
        }
        return ToolTurn(
            text = content.trim(),
            toolCalls = toolCalls,
            totalTokens = totalTokens,
            truncated = finishReason == "length",
            native = true,
        )
    }

    /**
     * 空正文错误（T-062）：把 `finish_reason` 与 token 分解带上。
     *
     * 旧文案「模型没有返回内容，请重试」对推理模型是误诊——推理 tokens 吃光预算时，
     * 重试永远没用，必须让用户看到「预算耗尽」并能据此改 max_tokens 或换非推理模型。
     */
    private fun blankContentFailure(
        finishReason: String?,
        completionTokens: Int?,
        reasoningTokens: Int?,
        totalTokens: Int?,
    ): StepError {
        val facts = buildList {
            add("finish_reason=${finishReason ?: "缺失"}")
            completionTokens?.let { add("completion_tokens=$it") }
            if (reasoningTokens != null && reasoningTokens > 0) add("reasoning_tokens=$reasoningTokens")
            else totalTokens?.let { if (it > 0) add("total_tokens=$it") }
        }.joinToString("，")
        val hint = when {
            finishReason == "length" ->
                "输出预算被耗尽（推理模型会先花完 max_tokens 再写正文）：请提高 max_tokens 或改用非推理模型"
            reasoningTokens != null && reasoningTokens > 0 ->
                "模型只返回了推理过程、没有正文：请重试或改用非推理模型"
            else -> "请重试"
        }
        return StepError(STEP_ERR_NETWORK, "模型没有返回内容（$facts）：$hint")
    }

    /** `Retry-After`（秒）→ 毫秒；非数字/缺失 = null（走指数退避） */
    private fun retryAfterMs(resp: HttpResponse<*>): Long? =
        resp.headers().firstValue("Retry-After").orElse(null)?.trim()?.toLongOrNull()?.times(1000L)

    private fun toolChoiceElement(choice: String): JsonElement =
        if (choice in setOf("auto", "none", "required")) {
            JsonPrimitive(choice)
        } else {
            // 指定函数名 = 强制调用该工具（AgentLoop 步数见底时用它强推 finish）
            buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject { put("name", choice) })
            }
        }

    companion object {
        /**
         * 原生工具协议开关（T-049）：`on` 启用，其余（含默认空值）= 保持文本协议。
         * 默认关闭的理由写在 D6：协议已由本地桩测试覆盖，但报告产物的质量需要真实模型抽样
         * （T-052）后才能评估是否默认切换——没验证过的默认值就是拿用户的报告做实验。
         */
        const val NATIVE_TOOLS_STORAGE = "llm.nativeTools"

        /**
         * 连接超时 15 秒、读超时 180 秒（分开）：连接阶段失败可退避重试，15 秒让「重试两次」
         * 的总代价可控；读阶段是模型在生成，T-062 把耐心上限从 60 秒提到 180 秒——
         * T-061 实测推理模型长报告 100–120 秒才出稿，60 秒会把成功硬判成失败。
         * 代价：真挂死的调用最坏多等 2 分钟；读超时仍不重试，不会按倍放大。
         */
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 180_000

        /** 指数退避（毫秒）：最多重试 2 次（总 3 次尝试）；不做随机抖动——单机工具，行为可测优先 */
        private val RETRY_DELAYS_MS = longArrayOf(500L, 1_500L)
        private const val MAX_RETRY_DELAY_MS = 10_000L

        /** SSE 帧前缀与结束哨兵（OpenAI 兼容流式的通用线格式，逐行读） */
        private const val SSE_DATA_PREFIX = "data:"

        /** 对冲配额比例的 env 旋钮（T-137）：>0 才启用；缺省 = 关闭 */
        private fun envHedgeRatio(): Double =
            Config.get("STRAGENT_LLM_HEDGE_RATIO")?.toDoubleOrNull()?.takeIf { it > 0.0 }
                ?: HedgeBudget.DEFAULT_RATIO

        /** 对冲延迟阈值的 env 旋钮（T-137）：主副本超过它还没回就发副本；缺省 = 不对冲 */
        private fun envHedgeAfterMs(): Long =
            Config.get("STRAGENT_LLM_HEDGE_AFTER_MS")?.toLongOrNull()?.coerceAtLeast(0) ?: 0L
        /**
         * finish_reason 之后的**尾帧等待上限**默认值（毫秒）：正文已完成，剩下的只有尾帧
         * （usage / [DONE]）。有些网关不发 [DONE] 且把连接挂着——没有上限就读端要静默到
         * 整段超时（180 秒）才收尾。
         *
         * 取值 30 秒：只约束「正文完成之后」那一段等待（正文已读完，砍不到它）；usage 按
         * 协议就排在 finish_reason 之后，这个上限必须比它的**正常迟到**更宽
         * （实测 1 秒上限 + 2.5 秒迟到的 usage → totalTokens 归 0 = 假账）。
         * 契约测试经构造参数注入小值（见 [finishTailWaitMs]）。
         */
        private const val FINISH_TAIL_WAIT_DEFAULT_MS = 30_000L
        private const val SSE_DONE = "[DONE]"

        /** 4xx/5xx → 面向用户的错误分类（stepfun.ts mapHttpFailure 逐字移植 + T-049 重试判据） */
        private fun mapHttpFailure(status: Int, body: String?, retryAfterMs: Long? = null): StepError {
            val detail = body?.let { raw ->
                runCatching {
                    val err = Json.parseToJsonElement(raw).jsonObject["error"]?.jsonObject
                    (err?.get("code")?.jsonPrimitive?.contentOrNull)
                        ?: (err?.get("type")?.jsonPrimitive?.contentOrNull)
                        ?: ""
                }.getOrDefault("")
            } ?: ""
            return when {
                status == 401 || status == 403 ->
                    StepError(
                        STEP_ERR_AUTH,
                        // D25 DX 收口：headless 宿主没有「设置」——指引落到两条真实通道
                        "API Key 无效或已失效：env 注入请核对 STRAGENT_API_KEY，文件注入请核对数据目录下的 api-key（改后重启宿主生效）",  // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                    )
                status == 429 ->
                    StepError(
                        STEP_ERR_QUOTA,
                        "请求太频繁或额度已达周期上限，稍后再试",
                        retryable = true,
                        retryAfterMs = retryAfterMs,
                    )
                status == 402 ->
                    StepError(STEP_ERR_QUOTA, "账户余额不足，请充值后重试")
                status == 400 && Regex("model", RegexOption.IGNORE_CASE).containsMatchIn(detail) ->
                    StepError(
                        STEP_ERR_BAD_REQUEST,
                        // D25 DX 收口：模型切换在 headless 下走 prefs（stepfun.vendor/customModel）
                        "当前账户不支持所选模型：请改用该账户可用的模型（prefs 里改 stepfun.customModel，或换 vendor）",  // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                    )
                status == 400 ->
                    StepError(STEP_ERR_BAD_REQUEST, "请求内容不被接受，请减少数据量后重试")
                status in 500..599 ->
                    StepError(
                        STEP_ERR_SERVER,
                        "模型服务暂时不可用（$status），已重试仍未恢复，请稍后再试",
                        retryable = true,
                        retryAfterMs = retryAfterMs,
                    )
                else ->
                    StepError(STEP_ERR_NETWORK, "模型服务返回 $status，请稍后重试")
            }
        }
    }
}

/**
 * 超时分类（T-049）：连接建立阶段值得重试，读阶段不值得。
 * HttpURLConnection 两种超时都抛 SocketTimeoutException，靠消息区分是唯一可用信息。
 */
internal fun timeoutFailure(e: java.net.SocketTimeoutException): StepError {
    val connectPhase = e.message?.contains("connect", ignoreCase = true) == true
    return StepError(
        STEP_ERR_TIMEOUT,
        if (connectPhase) "连接模型服务超时（15 秒），请检查网络后重试"
        else "模型服务响应超时（180 秒），请重试",
        retryable = connectPhase,
    )
}

/** 传输异常分类：连接被拒/重置/DNS 抖动可退避重试；其余按普通网络错误上报 */
internal fun networkFailure(e: Exception): StepError = StepError(
    STEP_ERR_NETWORK,
    "网络异常：${e.message ?: "请检查网络后重试"}",
    retryable = e is java.net.ConnectException ||
        e is java.net.SocketException ||
        e is java.io.EOFException ||
        e is java.net.UnknownHostException,
)
