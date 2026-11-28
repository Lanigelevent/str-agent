package com.stragent.agent

import com.stragent.core.csv.ColumnType
import com.stragent.core.dataset.Dataset
import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.PrefsStore
import com.stragent.core.report.TargetLength
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * T-089（T-086 边界段登记项）· 单 agent 引擎层：suggest_chart 产物透传进
 * InsightResult.agentCharts 的端到端测试（循环层已由 AgentKernelTest 覆盖，
 * 本层钉住 engine → InsightResult 的透传与 host 回执映射的数据源契约）。
 *
 * 勘误（T-090 过程中确认）：T-089 首测以为「agent 路径生产不可达」——实为
 * 选错引擎 id（`str-agent` 是宿主插件 manifest id，不是 PluginRegistry 条目）。
 * 内置清单 report-deepdive/report-standard（kind=report）与 editorial-deepdive
 * 是引擎选择的正确目标，agent 路径生产可达；host 端到端非空透传由
 * ReportReceiptChartsTest 钉住（选 report-deepdive 全链走通）。
 */
class ReportEngineChartsTest {

    private class ScriptedLlm : LlmClient {
        private var turn = 0
        override fun chat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
        ): ChatResult {
            turn += 1
            val content = if (turn == 1) {
                """{"tool":"suggest_chart","args":{"type":"bar","title":"各组销售额","data":[{"label":"West","value":725457.94},{"label":"East","value":678781.37}],"description":"分组对比"}}"""
            } else {
                """{"tool":"finish","args":{"report":"## 报告\n\n见 {{chart:0}}。"}}"""
            }
            return ChatResult(text = content, totalTokens = 42, truncated = false)
        }

        override fun streamChat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
            onToken: ((String) -> Unit)?,
        ): ChatResult = chat(model, messages, temperature, maxTokens)
    }

    private class MapPrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private fun dataset() = Dataset(
        name = "销售",
        importedAt = 0L,
        columns = listOf("地区", "销售额"),
        rows = listOf(listOf("华东", "1000"), listOf("华北", "300")),
        columnTypes = listOf(ColumnType.TEXT, ColumnType.NUMBER),
    )

    @Test
    fun `单 agent 引擎把 suggest_chart 产物透传进 agentCharts（T-089）`() {
        val llm = ScriptedLlm()
        val engine = SingleAgentReportEngine(AgentReportDelegate(llm, MapPrefs()))
        val request = ReportEngineRequest(
            dataset = dataset(),
            plugin = PluginManifest(
                id = "test-plugin", name = "测试插件", kind = "report",
                systemPrompt = "做一份测试报告。", tools = null, maxSteps = 5,
            ),
            brief = "",
            target = TargetLength(4000, "测试"),
            typeId = "standard",
        )
        val result = assertNotNull(engine.generate(request), "agent 引擎应产出报告")
        assertEquals("## 报告\n\n见 {{chart:0}}。", result.report)
        assertEquals(1, result.agentCharts.size, "suggest_chart 的产物应进 InsightResult.agentCharts")
        val chart = result.agentCharts[0]
        assertEquals("bar", chart.type)
        assertEquals("各组销售额", chart.title)
        assertEquals("West", chart.data[0].first)
        assertEquals(725457.94, chart.data[0].second, 0.01)
    }
}
