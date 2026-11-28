package com.stragent.core.ai

import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset
import com.stragent.core.ports.ChatResult as PortChatResult
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.PrefsStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-044 · 发稿门禁接进**真实经典流水线**的出口集成测试。
 *
 * 单元契约已在 `report/ReportGateTest` 覆盖（修订轮数、失败/取消、性能）；
 * 本测试证明编排器真的把门禁接上、接对：
 *  - 单次成文带假数字 → 一次修订、终稿替换、token 累计、轮数如实回传；
 *  - 两轮仍修不干净 → `gateFailed=true` 且终稿不丢（不静默放行）；
 *  - 分章成文 → 门禁对**拼接后的整篇**生效，修订只花一次（不是每章一次）；
 *  - 干净成文 → 结构/篇幅软问题只进 qualityNotes，修订预算一分不花。
 */
class GateIntegrationTest {
    private val sampleDir = File("../fixtures/sample")

    class FakePrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
    }

    /** 按调用序弹出预设输出（与 OrchestrationTest 同构） */
    private class ScriptedLlm(private val outputs: MutableList<PortChatResult>) : LlmClient {
        val calls = java.util.Collections.synchronizedList(mutableListOf<List<Pair<String, String>>>())
        override fun chat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
        ): PortChatResult {
            calls.add(messages)
            return if (outputs.isEmpty()) PortChatResult("(空)", 10) else outputs.removeAt(0)
        }
    }

    /** 按 prompt 形状路由（分章路径的章数随数据集变，不能按序弹） */
    private class RoutingLlm(private val route: (List<Pair<String, String>>) -> String) : LlmClient {
        val calls = java.util.Collections.synchronizedList(mutableListOf<List<Pair<String, String>>>())
        override fun chat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
        ): PortChatResult {
            calls.add(messages)
            val text = route(messages)
            return PortChatResult(text, (text.length / 4).coerceAtLeast(1))
        }
    }

    private fun datasetOf(name: String): Dataset {
        val text = File(sampleDir, "$name.csv").readText()
        val parsed = Csv.parseDelimited(text)
        val limited = Csv.limitRows(parsed)
        val types = Csv.inferTypes(limited.rows, limited.columns.size)
        return Dataset(
            name = "$name.csv", importedAt = 1,
            columns = limited.columns, rows = limited.rows, columnTypes = types,
            delimiter = limited.delimiter, truncated = limited.truncated, sourceRows = limited.totalRows,
        )
    }

    private fun List<Pair<String, String>>.joined(): String = joinToString("\n") { it.second }

    private val dirty = "## 总体概览\n\n得分合计 888888，整体表现不错。\n"
    private val clean = "## 总体概览\n\n得分合计 256，整体平稳。\n"

    @Test
    fun `单次成文——假数字触发一次修订，终稿替换且 token 累计`() {
        val llm = ScriptedLlm(
            mutableListOf(
                PortChatResult(dirty, 100),
                PortChatResult(clean.trim(), 40),
                PortChatResult("""{"issues":[]}""", 20),
            ),
        )
        val result = InsightOrchestrator(llm, FakePrefs(), model = "test").generateInsight(datasetOf("single_col"))

        assertFalse(result.gateFailed, result.gateIssues.joinToString("；"))
        assertEquals(1, result.gateRevisions)
        assertEquals(clean.trim(), result.report)
        assertEquals(140, result.totalTokens, "修订轮 token 必须累计（100 生成 + 40 修订）")
        assertEquals(countChars(clean.trim()), result.actualChars)
        // 修订调用的形状：只带问题清单与当前报告，不是重放生成提示词
        val revisionCall = llm.calls[1].joined()
        assertTrue(revisionCall.contains("<gate_issues>") && revisionCall.contains("<current_report>"))
        assertTrue(revisionCall.contains("unsourced_number") || revisionCall.contains("无法溯源"), revisionCall.take(400))
        assertFalse(revisionCall.contains("<quality_bar>"), "修订不该重放生成提示词")
        // 终稿的问题清单 = qualityNotes（软问题单独可见，硬问题已清零）
        assertEquals(result.qualityNotes, result.gateIssues)
        assertTrue(result.gateIssues.any { it.contains("unsourced_number") }.not(), "终稿不该再有未溯源硬伤：${result.gateIssues}")
        assertTrue(result.gateIssues.any { it.contains("缺结构块") }, "干净但缺结构块应作为软注记可见：${result.gateIssues}")
        assertEquals(3, llm.calls.size, "生成 1 + 修订 1 + 语义审查 1")
    }

    @Test
    fun `单次成文——两轮修不干净标记 gateFailed，终稿不丢`() {
        val dirty2 = "## 总体概览\n\n得分合计 777777。\n"
        val dirty3 = "## 总体概览\n\n得分合计 999999。\n"
        val llm = ScriptedLlm(
            mutableListOf(
                PortChatResult(dirty, 100),
                PortChatResult(dirty2, 30),
                PortChatResult(dirty3, 30),
                PortChatResult("""{"issues":[]}""", 20),
            ),
        )
        val result = InsightOrchestrator(llm, FakePrefs(), model = "test").generateInsight(datasetOf("single_col"))

        assertTrue(result.gateFailed, "两轮修订仍未清零必须如实标记失败")
        assertEquals(2, result.gateRevisions)
        assertEquals(dirty3.trim(), result.report, "终稿保留（不把一份报告换成一个异常；修订输出按约定 trim）")
        assertTrue(result.gateIssues.any { it.startsWith("[unsourced_number]") && it.contains("999999") }, "${result.gateIssues}")
        assertEquals(160, result.totalTokens, "100 生成 + 两轮修订各 30")
        assertEquals(2, llm.calls.count { it.joined().contains("<gate_issues>") })
    }

    @Test
    fun `分章成文——门禁对拼接整篇生效，修订只花一次`() {
        val revised = "## 总体概览\n\n修订后的干净正文。\n"
        val llm = RoutingLlm { messages ->
            val joined = messages.joined()
            when {
                joined.contains("<gate_issues>") -> revised
                joined.contains("\"issues\"") -> """{"issues":[]}"""
                else -> "Sales 合计 888888 元。"
            }
        }
        val result = InsightOrchestrator(llm, FakePrefs(), model = "test").generateInsight(datasetOf("superstore"))

        assertTrue(result.chapters != null && result.chapters!!.size >= 10, "superstore 应走分章路径")
        assertFalse(result.gateFailed, result.gateIssues.joinToString("；"))
        assertEquals(1, result.gateRevisions, "同一份报告只该修一次，不是每章修一次")
        assertEquals(revised.trim(), result.report)
        // 修订 prompt 里是**整篇**：多章标题都在场
        val revisionCall = llm.calls.single { it.joined().contains("<gate_issues>") }.joined()
        assertTrue(revisionCall.contains("888888"), "修订应看到整篇原文")
        assertTrue(result.chapters!!.size >= 10 && revisionCall.contains("## "), revisionCall.take(200))
        // 章生成仍然逐章发生（门禁没有把分章路径改回单次）
        assertTrue(llm.calls.count { it.joined().contains("<chapter_position>") } >= 10)
    }

    @Test
    fun `干净成文——结构软问题只进 qualityNotes，零修订零额外 token`() {
        val llm = ScriptedLlm(
            mutableListOf(
                PortChatResult(clean, 100),
                PortChatResult("""{"issues":[]}""", 20),
            ),
        )
        val result = InsightOrchestrator(llm, FakePrefs(), model = "test").generateInsight(datasetOf("single_col"))

        assertFalse(result.gateFailed)
        assertEquals(0, result.gateRevisions)
        assertEquals(clean, result.report)
        assertEquals(100, result.totalTokens, "未修订就没有额外 token")
        assertTrue(result.gateIssues.any { it.contains("缺结构块") }, "结构化类型缺块是软问题：${result.gateIssues}")
        assertEquals(2, llm.calls.size, "干净路径不多花一次修订调用")
    }
}
