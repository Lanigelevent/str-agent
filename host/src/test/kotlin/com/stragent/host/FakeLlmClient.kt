package com.stragent.host

import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.LlmClient

/**
 * 确定性假模型 —— 宿主集成验收（D7 T-020）专用。
 *
 * 为什么需要它：插件形态的**存在性证明**（数据 → brief → 模型 → 报告 → MCP 回包）
 * 不该被「有没有真实模型 Key」卡住。假模型让整条接线可离线、可重复、可断言。
 *
 * 它能证明什么、不能证明什么（别自欺）：
 *  - 能：端口接线对、数据摘要确实到达了模型、报告经 MCP 原样回到调用方、失败路径是中文提示；
 *  - 不能：真实模型的报告质量与数字正确性（那要靠 bench 的金标门禁与真实 Key 下的抽样验收）。
 *
 * 按 prompt 形状分流（不依赖调用顺序，重复调用同一形状也稳定）：
 *  - 语义审查 prompt（含 `"issues"`）→ 返回 `{"issues":[]}`（无问题）
 *  - 追问 prompt（含 `<followup_requirements>`）→ 返回固定回答
 *  - 其余（报告生成）→ 返回固定报告
 *
 * 另可扮演**流式模型**（给 [tokenSize]）：正文逐段回调 onToken，用于验证 MCP 的正文增量帧。
 */
class FakeLlmClient(
    private val report: String = DEFAULT_REPORT,
    private val followup: String = DEFAULT_ANSWER,
    /**
     * 非空 = 假装自己是**流式**模型：正文按这个长度切片、逐段回调 onToken。
     * 默认 null —— 与真实实现同口径：没有订阅者就不改请求形态（见 HttpLlmClient.streamChat）。
     */
    private val tokenSize: Int? = null,
) : LlmClient {

    /** 收到的 prompt（按序）—— 用来断言「数据摘要真的到了模型」 */
    val prompts = mutableListOf<String>()

    var calls: Int = 0
        private set

    override fun chat(
        model: String,
        messages: List<Pair<String, String>>,
        temperature: Double,
        maxTokens: Int,
    ): ChatResult {
        calls += 1
        val joined = messages.joinToString("\n") { it.second }
        prompts.add(joined)
        val content = when {
            joined.contains("\"issues\"") -> "{\"issues\":[]}"
            joined.contains("文本分析员") -> CHUNK_POINTS_JSON
            joined.contains("<followup_requirements>") -> followup
            else -> report
        }
        return ChatResult(text = content, totalTokens = (content.length / 4).coerceAtLeast(1), truncated = false)
    }

    /** 流式通道：只有调用方要了（onToken 非空）且配了切片长度，才真的逐段吐字 */
    override fun streamChat(
        model: String,
        messages: List<Pair<String, String>>,
        temperature: Double,
        maxTokens: Int,
        onToken: ((String) -> Unit)?,
    ): ChatResult {
        val result = chat(model, messages, temperature, maxTokens)
        if (onToken != null && tokenSize != null) {
            result.text.chunked(tokenSize).forEach { onToken(it) }
        }
        return result
    }

    companion object {
        const val DEFAULT_REPORT = "## 总体概览\n\n（离线验收用的固定报告正文）\n"
        const val DEFAULT_ANSWER = "（离线验收用的固定追问回答）"

        /** T-096：要点提取分支的固定应答（要点+关键数字 45.3 与验收材料配套） */
        const val CHUNK_POINTS_JSON =
            """{"points":[{"text":"第一季度销售额 45.3","quote":"销售额 45.3","segment":1}],"keyNumbers":["45.3"],"summary":"材料围绕销售额增长"}"""
    }
}
