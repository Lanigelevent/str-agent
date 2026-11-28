package com.stragent.core.ai

import com.stragent.core.ports.LlmClient

/**
 * 查询分解器（T-113）——隐式多跳的检索侧：把复合问题拆成可独立检索的子查询。
 *
 * 行业依据（P-151 调研）：多跳 RAG 的轻量路线是**查询分解**（拆子查询并行检索 → 融合），
 * 而非重图索引（HopRAG/SAG 属后续候选）。BM25 词面重合的问题双源同窗已可达（T-112 基线），
 * 真正的差距是「问题不提及后一跳实体」时单查询召不回第二源——分解补的正是这一段。
 *
 * 纪律：
 * - 分解是**轻调用**（maxTokens 300），只在显式接线 decomposer 时发生，默认关 = 零变化；
 * - 解析不出 ≥2 个有效子查询（非多跳/格式坏）→ subQueries=null，单查询行为零变化；
 * - 产出校验从严：剥编号/序号词、去空、去重、剔与原问题等值的行、单条长度上限，
 *   子查询是检索式不是答案——超长说明模型在写作而不是拆解，丢弃。
 */
object LlmQueryDecomposer : com.stragent.core.ports.QueryDecomposer {

    /** 子查询条数上限：多跳超过 4 跳的场景应改用报告链路，追问检索不背重分解的成本 */
    const val MAX_SUB_QUERIES = 4

    /** 单条子查询长度上限（字符）：检索式应短于原问题 */
    const val MAX_SUB_QUERY_CHARS = 60

    private const val SYSTEM_PROMPT =
        "你是检索查询分解器。把用户的问题拆成 2-4 个可独立检索的子查询：每个子查询只覆盖问题的一" + // lint:allow zh-first（用户面中文输出/提示词，T-095/T-113 语言定位）
        "个事实点，保留关键实体与数字，用材料中可能出现的原词。每行一个子查询，" + // lint:allow zh-first（用户面中文输出/提示词，T-095/T-113 语言定位）
        "不要编号，不要解释。如果问题本身就是单一事实，只输出一行原问题。" // lint:allow zh-first（用户面中文输出/提示词，T-095/T-113 语言定位）

    /** 编号行前缀（T-142 热路径纪律：`Pattern` 不可变线程安全，预编译单例） */
    private val NUMBERED_LINE_PREFIX = Regex("^[0-9０-９]+[.、)）：:]\\s*")

    /** LLM 输出 → 有效子查询表：剥编号/序号词、去空、去重、剔与原问题等值行、长度过滤 */
    fun parseSubQueries(raw: String, original: String): List<String> {
        val seen = LinkedHashSet<String>()
        for (line in raw.lines()) {
            var s = line.trim()
            if (s.isEmpty()) continue
            s = s.removePrefix("-").removePrefix("•").trim()
            s = s.replace(NUMBERED_LINE_PREFIX, "").trim()
            s = s.trimEnd('。', '.', '；', ';', '，', ',')
            if (s.length < 2 || s.length > MAX_SUB_QUERY_CHARS) continue
            if (s == original) continue
            seen += s
            if (seen.size >= MAX_SUB_QUERIES) break
        }
        return seen.toList()
    }

    override fun decompose(
        llm: LlmClient,
        model: String,
        question: String,
        cancelSignal: () -> Boolean,
    ): com.stragent.core.ports.DecomposeResult {
        if (cancelSignal()) throw GenerationCancelled()
        val r = chatStep(
            llm, model,
            listOf(
                "system" to SYSTEM_PROMPT,
                "user" to question,
            ),
            ChatOptions(temperature = 0.2, maxTokens = 300),
            cancelSignal = cancelSignal,
        )
        val subs = parseSubQueries(r.content, question.trim())
        return com.stragent.core.ports.DecomposeResult(subs.takeIf { it.size >= 2 }, r.totalTokens)
    }
}
