package com.stragent.core.ai

import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset
import com.stragent.core.ports.ChatResult as PortChatResult
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.PrefsStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-062 · 语义审查的**失败语义**：审查可以失败，但不许冒充通过。
 *
 * 靶心：
 *  ① 预算按推理模型口径给足（T-061 实测一次审查约 12.6k completion tokens，1200 必爆）；
 *  ② 端点以 400 拒绝 16k 上限时回落 8k，仍失败则显式上报；
 *  ③ 非 JSON、引用无法定位、调用异常都返回 failure，不返回「空问题列表」；
 *  ④ 编排层把 failure 写进 qualityNotes，宿主看得见「语义层未审」。
 */
class SemanticReviewTest {
    private class FakePrefs : PrefsStore {
        private val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
    }

    /** 记录每次调用的输出预算，响应由 [respond] 决定 */
    private class RecordingLlm(
        private val respond: (List<Pair<String, String>>, Int) -> PortChatResult,
    ) : LlmClient {
        val budgets = mutableListOf<Int>()
        val calls = java.util.Collections.synchronizedList(mutableListOf<List<Pair<String, String>>>())
        override fun chat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
        ): PortChatResult {
            budgets += maxTokens
            calls += messages
            return respond(messages, maxTokens)
        }
    }

    private fun review(
        llm: LlmClient,
        report: String = "本报告的整体平稳，样本量 20。",
    ): SemanticReviewOutcome = semanticReview(
        llm, FakePrefs(), report, "Sales 合计 20", "test",
    )

    @Test
    fun `审查预算按推理模型给足 16k，成功时不写失败`() {
        val llm = RecordingLlm { _, _ -> PortChatResult("""{"issues":[]}""", 10) }
        val outcome = review(llm)

        assertNull(outcome.failure, outcome.failure)
        assertTrue(outcome.issues.isEmpty())
        assertEquals(listOf(SEMANTIC_REVIEW_MAX_TOKENS), llm.budgets, "预算必须是推理模型口径")
        assertEquals(16_384, llm.budgets.single())
    }

    @Test
    fun `端点 400 拒绝 16k 时回落 8k 再试`() {
        val llm = RecordingLlm { _, maxTokens ->
            if (maxTokens > SEMANTIC_REVIEW_FALLBACK_TOKENS) {
                throw StepError(STEP_ERR_BAD_REQUEST, "max_tokens too large")
            }
            PortChatResult("""{"issues":[]}""", 10)
        }
        val outcome = review(llm)

        assertNull(outcome.failure, outcome.failure)
        assertEquals(listOf(16_384, 8_192), llm.budgets, "先 16k，被拒后回落 8k")
    }

    @Test
    fun `非 JSON 返回显式失败，不冒充没有问题是负控`() {
        val llm = RecordingLlm { _, _ -> PortChatResult("这份报告没有问题。", 5) }
        val outcome = review(llm)

        assertNotNull(outcome.failure, "非 JSON 必须是失败，不能静默当成 0 问题")
        assertTrue(outcome.failure!!.contains("没有 JSON"), outcome.failure)
        assertTrue(outcome.issues.isEmpty())
    }

    @Test
    fun `审查引用的原文片段全部无法定位时显式失败`() {
        val llm = RecordingLlm { _, _ ->
            PortChatResult(
                """{"issues":[{"kind":"归属错误","quote":"报告里根本不存在的句子","why":"归属不对"}]}""",
                9,
            )
        }
        val outcome = review(llm)

        assertNotNull(outcome.failure)
        assertTrue(outcome.failure!!.contains("无法在终稿中定位"), outcome.failure)
        assertTrue(outcome.issues.isEmpty())
    }

    @Test
    fun `审查调用超时显式失败，文案带预算`() {
        val llm = RecordingLlm { _, _ ->
            throw StepError(STEP_ERR_TIMEOUT, "模型服务响应超时（180 秒），请重试")
        }
        val outcome = review(llm)

        assertNotNull(outcome.failure)
        assertTrue(outcome.failure!!.contains("审查调用超时"), outcome.failure)
        assertTrue(outcome.failure!!.contains("max_tokens=16384"), outcome.failure)
    }

    @Test
    fun `审查成功时问题进入 semantic 且不写未完成注记`() {
        val report = "## 总体概览\n\n本数据集只有一列，整体平稳。"
        val llm = RoutingLlm { messages ->
            if (messages.any { it.second == SEMANTIC_REVIEW_PROMPT }) {
                PortChatResult(
                    """{"issues":[{"kind":"归属错误","quote":"整体平稳","why":"没有数字支撑"}]}""",
                    20,
                )
            } else {
                PortChatResult(report, 100)
            }
        }
        val result = InsightOrchestrator(llm, FakePrefs(), model = "test")
            .generateInsight(datasetOf("single_col"))

        assertEquals(1, result.semantic.size, "审查问题应进入产物")
        assertTrue(result.qualityNotes.none { it.contains("语义审查未完成") }, "${result.qualityNotes}")
    }

    @Test
    fun `审查失败在 qualityNotes 显式可见`() {
        val report = "## 总体概览\n\n本数据集只有一列，整体平稳。"
        val llm = RoutingLlm { messages ->
            if (messages.any { it.second == SEMANTIC_REVIEW_PROMPT }) {
                throw StepError(STEP_ERR_TIMEOUT, "模型服务响应超时（180 秒），请重试")
            } else {
                PortChatResult(report, 100)
            }
        }
        val result = InsightOrchestrator(llm, FakePrefs(), model = "test")
            .generateInsight(datasetOf("single_col"))

        assertTrue(result.semantic.isEmpty())
        assertTrue(
            result.qualityNotes.any { it.contains("语义审查未完成") && it.contains("超时") },
            "失败必须写进 qualityNotes：${result.qualityNotes}",
        )
    }

    @Test
    fun `T-193 修复成功——首轮垃圾输出，单轮修复拿到可定位问题`() {
        val llm = RecordingLlm({ msgs, _ ->
            if (msgs.any { it.second.contains("上一轮输出未通过结构校验") }) {
                PortChatResult("""{"issues":[{"kind":"归属错误","quote":"样本量 20","why":"引用可定位的审查问题"}]}""", 10)
            } else {
                PortChatResult("这不是 JSON 的垃圾输出", 8)
            }
        })
        val outcome = review(llm)
        assertNull(outcome.failure)
        assertEquals(1, outcome.repairRounds)
        assertEquals(1, outcome.issues.size)
        assertTrue(outcome.issues[0].quote == "样本量 20")
    }

    @Test
    fun `T-193 修复仍失败——单轮上限后显式降级（预算有界）`() {
        val llm = RecordingLlm { _, _ -> PortChatResult("垃圾", 5) }
        val outcome = review(llm)
        assertNotNull(outcome.failure)
        assertEquals(1, outcome.repairRounds, "修复轮恰好一次（有界）")
        assertEquals(2, llm.calls.size, "首轮 + 单轮修复 = 恰好两次调用")
    }

    @Test
    fun `T-193 非格式失败不修复——超时直接显式降级`() {
        val llm = RecordingLlm { _, _ -> throw StepError(STEP_ERR_TIMEOUT, "模型服务响应超时（180 秒），请重试") }
        val outcome = review(llm)
        assertNotNull(outcome.failure)
        assertEquals(0, outcome.repairRounds, "超时不是格式问题，不花修复轮")
        assertEquals(1, llm.calls.size, "只调一次")
    }

    /** 按 prompt 形状路由（生成 vs 审查），分章/单次都适用 */
    private class RoutingLlm(
        private val route: (List<Pair<String, String>>) -> PortChatResult,
    ) : LlmClient {
        override fun chat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
        ): PortChatResult = route(messages)
    }

    private fun datasetOf(name: String): Dataset {
        val text = File("../fixtures/sample", "$name.csv").readText()
        val parsed = Csv.parseDelimited(text)
        val limited = Csv.limitRows(parsed)
        val types = Csv.inferTypes(limited.rows, limited.columns.size)
        return Dataset(
            name = "$name.csv", importedAt = 1,
            columns = limited.columns, rows = limited.rows, columnTypes = types,
            delimiter = limited.delimiter, truncated = limited.truncated, sourceRows = limited.totalRows,
        )
    }
}
