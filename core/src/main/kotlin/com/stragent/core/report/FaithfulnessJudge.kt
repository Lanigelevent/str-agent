package com.stragent.core.report

import com.stragent.core.config.Config
import com.stragent.core.ai.ChatOptions
import com.stragent.core.ai.GenerationCancelled
import com.stragent.core.ai.chatStep
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.PrefsStore

/**
 * 忠实度二审（T-102）：确定性门禁（verifyReport/锚点/白名单）之外的 **opt-in** LLM-judge——
 * 逐 claim 检查「报告的每个断言是否被材料实质支撑」（语义层，非逐字）。
 *
 * 为什么 opt-in：LLM-judge 非确定性、有成本；默认关（env STRAGENT_FAITHFULNESS_JUDGE=1 开）。
 * 失败语义沿 T-062：不阻断出稿，未完成显式写 qualityNotes（「没审」≠「没发现问题」）。
 *
 * 与 T-099 的分工：抽样门禁管「引用可回链」（逐字），本审管「语义上是否实质支撑」——
 * 行业两道防线的第二道（RefLens 类方法的轻量实现）。
 */
internal object FaithfulnessJudge {

    /** opt-in 开关（进程级读取一次；env STRAGENT_FAITHFULNESS_JUDGE=1 启用） */
    val enabled: Boolean = Config.get("STRAGENT_FAITHFULNESS_JUDGE") == "1"

    data class Outcome(
        /** 审出的语义层断言问题（空 = 判定全部支撑或未审） */
        val issues: List<String>,
        /** 非 null = 二审未完成原因（解析失败/调用失败）——显式进 qualityNotes */
        val failure: String? = null,
        val totalTokens: Int = 0,
    )

    fun judge(
        llm: LlmClient,
        report: String,
        materialBrief: String,
        model: String,
        cancelSignal: () -> Boolean = { false },
    ): Outcome {
        val system = """你是忠实的审稿人。对照材料逐条检查报告的断言： // lint:allow zh-first（用户面中文输出/提示词，T-095/T-102 语言定位）
报告里的每个结论/数字是否被材料**实质支撑**（语义上成立，不要求逐字）。  // lint:allow zh-first（用户面中文输出/提示词，T-095/T-102 语言定位）
只报确有问题的断言（无中生有 / 超出材料暗示的推断 / 数字口径错配），输出 JSON：  // lint:allow zh-first（用户面中文输出/提示词，T-095/T-102 语言定位）
{"issues":[{"claim":"报告断言","why":"为何不被支撑"}]} // lint:allow zh-first（用户面中文输出/提示词，T-095/T-102 语言定位）
没有问题输出 {"issues":[]}。不要改报告，不要提格式建议。""" // lint:allow zh-first（用户面中文输出/提示词，T-095/T-102 语言定位）
        try {
            val result = chatStep(
                llm, model,
                listOf(
                    "system" to system,
                    "user" to "<material>\n$materialBrief\n</material>\n\n<report>\n$report\n</report>",
                ),
                ChatOptions(temperature = 0.2, maxTokens = 2000),
                cancelSignal = cancelSignal,
            )
            val parsed = parseJudge(result.content)
                ?: return Outcome(emptyList(), "二审响应不可解析（非 JSON 或缺 issues）", result.totalTokens) // lint:allow zh-first（用户面中文输出/提示词，T-095/T-102 语言定位）
            return Outcome(
                issues = parsed.map { "[忠实度二审] ${it.first}——${it.second}" }, // lint:allow zh-first（用户面中文输出/提示词，T-095/T-102 语言定位）
                totalTokens = result.totalTokens,
            )
        } catch (e: GenerationCancelled) {
            throw e
        } catch (e: Exception) {
            return Outcome(emptyList(), "二审调用失败：${e.message ?: e.javaClass.simpleName}") // lint:allow zh-first（用户面中文输出/提示词，T-095/T-102 语言定位）
        }
    }

    /** 宽松解析：剥围栏，取 claims；坏条目跳过 */
    internal fun parseJudge(jsonText: String): List<Pair<String, String>>? {
        var s = jsonText.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```")
            if (s.startsWith("json", ignoreCase = true)) s = s.removePrefix("json")
            val end = s.lastIndexOf("```")
            if (end >= 0) s = s.substring(0, end)
        }
        val obj = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(s) as? kotlinx.serialization.json.JsonObject
        }.getOrNull() ?: return null
        val issues = obj["issues"] as? kotlinx.serialization.json.JsonArray ?: return null
        return issues.mapNotNull { item ->
            val o = item as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            val claim = str("claim") ?: return@mapNotNull null
            str("why")?.let { claim to it }
        }
    }
}
