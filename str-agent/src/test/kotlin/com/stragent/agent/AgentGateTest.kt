package com.stragent.agent

import com.stragent.core.ai.InsightOrchestrator
import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset
import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.DatasetStore
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.PrefsStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-044 · agent 报告出口的发稿门禁（单 Agent 路径）。
 *
 * 经典路径的接线由 core 的 `GateIntegrationTest` 证明；本测试证明**换引擎不换纪律**：
 * `report-standard` 插件产出的报告同样过 `verifyReport`/`WritingAudit`/`ReviewerAgent`，
 * 硬伤触发修订（模型用插件自带的 model），轮数与问题清单如实回传。
 *
 * 编辑部（kind=editorial）的外层预算为 0——内部退稿已用满预算、不双重计费；
 * 该口径拆成两半证据：`runReportGate(maxRevisions=0)` 的语义在 core 测试里，
 * 内部退稿轮数透传在这里的 `gateRevisions` 契约上。
 */
class AgentGateTest {

    private class MapPrefs : PrefsStore {
        private val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private class MapStore : DatasetStore {
        private val map = mutableMapOf<String, String>()
        override fun put(key: String, value: String) { map[key] = value }
        override fun get(key: String): String? = map[key]
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): List<String> = map.keys.toList()
    }

    /** 按 prompt 形状分流：修订调用 → 干净稿；其余 → agent 的 finish JSON */
    private class RoutingLlm(
        private val report: String,
        private val revised: String,
    ) : LlmClient {
        val calls = mutableListOf<Pair<String, List<Pair<String, String>>>>()
        override fun chat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
        ): ChatResult {
            calls.add(model to messages)
            val joined = messages.joinToString("\n") { it.second }
            val text = if (joined.contains("<gate_issues>")) {
                revised
            } else {
                """{"tool":"finish","args":{"report":${jsonString(report)}}}"""
            }
            return ChatResult(text, (text.length / 4).coerceAtLeast(1))
        }

        private fun jsonString(s: String): String =
            buildString { append('"'); s.forEach { c -> when (c) { '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); else -> append(c) } }; append('"') }
    }

    private fun dataset(): Dataset {
        val text = File("../fixtures/sample/single_col.csv").readText()
        val parsed = Csv.parseDelimited(text)
        val limited = Csv.limitRows(parsed)
        val types = Csv.inferTypes(limited.rows, limited.columns.size)
        return Dataset(
            name = "single_col.csv", importedAt = 1,
            columns = limited.columns, rows = limited.rows, columnTypes = types,
            delimiter = limited.delimiter, truncated = limited.truncated, sourceRows = limited.totalRows,
        )
    }

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "$name-${System.nanoTime()}").also { it.mkdirs() }

    private fun orchestrator(llm: LlmClient, prefs: PrefsStore): AgentIntegratedOrchestrator =
        AgentIntegratedOrchestrator(
            InsightOrchestrator(llm, prefs, MapStore(), search = null, model = "test"),
            llm, prefs, PluginRegistry(tempDir("agent-gate")),
        )

    private val dirty = "## 总体概览\n\n得分合计 888888，整体表现不错。\n"
    private val clean = "## 总体概览\n\n得分合计 256，整体平稳。"

    @Test
    fun `单 Agent——假数字触发修订，引擎标识与轮数如实回传`() {
        val prefs = MapPrefs()
        AgentEngine.setEngineId(prefs, "report-standard")
        val llm = RoutingLlm(dirty, clean)
        val result = orchestrator(llm, prefs).generateInsight(dataset())

        assertEquals("report-standard", result.engineId, "走的必须是 agent 路径")
        assertTrue(result.autonomous)
        assertFalse(result.gateFailed, result.gateIssues.joinToString("；"))
        assertEquals(1, result.gateRevisions)
        assertEquals(clean.trim(), result.report, "发出去的是门禁修订后的终稿")
        assertTrue(result.gateIssues.any { it.contains("缺结构块") }, "软注记仍可见：${result.gateIssues}")
        // 修订调用的模型 = 插件自带 model（report-standard 未指定 → default），不是用户当前模型
        val revision = llm.calls.single { it.second.joinToString("\n") { m -> m.second }.contains("<gate_issues>") }
        assertEquals("default", revision.first)
        assertTrue(revision.second.joinToString("\n") { it.second }.contains("888888"), "修订要看到原稿")
        assertEquals(2, llm.calls.size, "agent 一次成稿 + 一次修订（agent 路径无语义审查调用）")
    }

    @Test
    fun `单 Agent——干净成稿零修订，不多花一次调用`() {
        val prefs = MapPrefs()
        AgentEngine.setEngineId(prefs, "report-standard")
        val llm = RoutingLlm(clean, "不该被用到的修订稿")
        val result = orchestrator(llm, prefs).generateInsight(dataset())

        assertEquals("report-standard", result.engineId)
        assertFalse(result.gateFailed)
        assertEquals(0, result.gateRevisions)
        assertEquals(clean.trim(), result.report)
        assertEquals(1, llm.calls.size)
    }
}
