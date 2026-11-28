package com.stragent.core.ai

import com.stragent.core.ports.LlmClient
import kotlin.math.min

/**
 * chatStep —— stepfun.ts 的截断续写移植（编排层核心）。
 *
 * 被截断时把已写内容垫回 assistant 历史让模型从断点续写，最多 MAX_CONTINUATIONS=3 轮；
 * 续写提示带目标篇幅（「已写 N 字 / 目标 M 字」），防草草收尾与注水两头。
 */
data class ChatOptions(
    val temperature: Double = 0.3,
    val maxTokens: Int = 16384,
    /** 续写时告知总目标 */
    val targetChars: Int? = null,
    /** false = 调用方明确不要续写 */
    val continueOnTruncate: Boolean = true,
)

data class ChatResult(
    val content: String,
    val totalTokens: Int = 0,
    val truncated: Boolean = false,
    /**
     * 追问回答的记录级门禁（T-044）：回答里有不在本次数据摘要中的数字/矛盾主张 = true。
     * 回答**不改写**（对话被静默重写比带标记更糟），只把问题清单透传给宿主。
     */
    val gateFailed: Boolean = false,
    val gateIssues: List<String> = emptyList(),
    /**
     * 查询分解回显（T-113）：本次追问实际生效的子查询表（恒输出，空表=未分解）。
     * 调用方据此分辨「检索走了分解」与「单查询直出」——配置回显纪律（T-108 同源）。
     */
    val subQueries: List<String> = emptyList(),
    /**
     * 块上下文回显（T-114）：本次检索索引中获得上下文补语的块数（0=未启用/全失败）。
     * 上下文只进索引不进材料与门禁——计数是它生效的唯一可见面。
     */
    val contextualChunks: Int = 0,
)

class StepError(
    val code: String,
    message: String,
    /**
     * 该错误是否值得退避重试（T-049）：429 / 5xx / 连接建立阶段失败 = true；
     * 鉴权、参数错误、**读超时** = false（一次已等 180 秒，再等三轮只会把用户拖死）。
     * 分类与退避决策在 `data/HttpLlmClient`，这里只承载判据，供任意实现复用。
     */
    val retryable: Boolean = false,
    /** 服务端建议的重试等待（Retry-After），毫秒；null = 按指数退避 */
    val retryAfterMs: Long? = null,
) : Exception(message)

/**
 * 错误分类值（T-062 从 `data/StepfunConfig` 上移到 core）：[StepError] 住在 core，
 * 分类值的唯一来源也应在这里——否则 core 里想按 `bad-request` / `timeout` 分流只能写裸字面量。
 * `data` 的同名常量保留为别名，调用方无需改动。
 */
const val STEP_ERR_AUTH = "auth"
const val STEP_ERR_QUOTA = "quota"
const val STEP_ERR_BAD_REQUEST = "bad-request"
const val STEP_ERR_NETWORK = "network"
const val STEP_ERR_NO_KEY = "no-key"
const val STEP_ERR_UNSUPPORTED = "unsupported"
const val STEP_ERR_SERVER = "server"
const val STEP_ERR_TIMEOUT = "timeout"

const val MAX_CONTINUATIONS = 3

/**
 * 一次生成（含截断续写）。
 *
 * [onToken] 非空时逐 token 透传给调用方（走 [LlmClient.streamChat]）；**续写的每一轮也在同一个
 * 回调上继续吐字**——断点续写对用户是同一段正文的延续，不是第二篇文章。
 * 为 null 时实现侧一律退回单次 [LlmClient.chat] 通道：不要流式就别改请求形态。
 *
 * [cancelSignal] 在首轮与**每一轮续写之前**检查：截断续写是追加计费的模型调用，
 * 客户端取消后不该再为续写买单。检查点只能落在调用之间——同步 HTTP 调用进行中无法安全中断。
 */
fun chatStep(
    client: LlmClient,
    model: String,
    messages: List<Pair<String, String>>, // role to content
    options: ChatOptions = ChatOptions(),
    onToken: ((String) -> Unit)? = null,
    cancelSignal: () -> Boolean = { false },
): ChatResult {
    if (cancelSignal()) throw GenerationCancelled()
    val first = client.streamChat(
        model = model,
        messages = messages,
        temperature = options.temperature,
        maxTokens = options.maxTokens,
        onToken = onToken,
    )
    if (!first.truncated || !options.continueOnTruncate) {
        return ChatResult(first.text, first.totalTokens, first.truncated)
    }

    val parts = mutableListOf(first.text)
    var latest = first
    // T-049：续写每一轮都是**独立计费**的调用，token 账必须逐轮累加。
    // 旧写法只算「首轮 + 末轮」，中间轮次的用量凭空消失（实际花费比账本高，属于假账）。
    var totalTokens = first.totalTokens
    var rounds = 0
    while (rounds < MAX_CONTINUATIONS && latest.truncated) {
        // 每轮续写前再查一次：已出稿的部分原样返回给调用方（由调用方决定保留还是丢弃），
        // 但不再发起新的计费调用
        if (cancelSignal()) throw GenerationCancelled()
        val resumeHint = buildString {
            append(
                "上面那份回答在中间被截断了。请**从断点处继续写**：不要重复已经写过的任何内容，" +
                    "不要加\"续\"字或解释，直接接着最后一个字的后面往下写。",
            )
            options.targetChars?.let { target ->
                append(
                    "本次回答的目标篇幅约 $target 字，目前已写约 ${parts.joinToString("").length} 字——" +
                        "按剩余篇幅把内容写完，不要为了赶进度删掉该有的发现，也不要注水凑字数。",
                )
            }
        }
        val resumed = client.streamChat(
            model = model,
            messages = messages + listOf(
                "assistant" to parts.joinToString(""),
                "user" to resumeHint,
            ),
            temperature = options.temperature,
            maxTokens = options.maxTokens,
            onToken = onToken,
        )
        parts.add(resumed.text)
        totalTokens += resumed.totalTokens
        latest = resumed
        rounds += 1
    }

    return ChatResult(
        content = parts.joinToString(""),
        totalTokens = totalTokens,
        truncated = latest.truncated,
    )
}
