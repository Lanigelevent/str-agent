package com.stragent.agent

import com.stragent.core.ai.InsightOrchestrator
import com.stragent.core.csv.ColumnType
import com.stragent.core.dataset.Dataset
import com.stragent.core.ports.DatasetStore
import com.stragent.core.observability.StructuredLogger
import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.PrefsStore
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-090 · 引擎回落可见性：显式选择了非 classic 引擎却没走成 agent 路径时，
 * 必须留中文警告（结构化日志）——回落行为本身不变（可用性优先），但「降级
 * 要如实可见」是项目纪律（T-089 首测选错引擎 id 时静默回落无人知晓）。
 *
 * 覆盖两种事实（分开说清）：
 *  1. 所选插件存在但 kind 不被引擎注册表支持；
 *  2. 所选插件不存在（可能已被删除）。
 * 以及负向不变量：classic 默认（未显式选择）不告警——那不是降级，是默认路径。
 */
class AgentEngineFallbackTest {

    private class PlainLlm(private val text: String = "# 报告\n\n正常完成。") : LlmClient {
        override fun chat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
        ): ChatResult = ChatResult(text = text, totalTokens = 10, truncated = false)

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

    private class MapStore : DatasetStore {
        private val map = mutableMapOf<String, String>()
        override fun put(key: String, value: String) { map[key] = value }
        override fun get(key: String): String? = map[key]
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): List<String> = map.keys.toList()
    }

    private val lines = mutableListOf<String>()

    @AfterTest
    fun restoreSink() {
        lines.clear()
    }

    /** 捕获结构化日志（StructuredLogger 全局 sink 换入换出），返回捕获到的行 */
    private fun capture(block: () -> Unit): List<String> {
        val original = StructuredLogger.globalOut()
        StructuredLogger.setGlobalOut { line -> lines.add(line) }
        try {
            block()
        } finally {
            StructuredLogger.setGlobalOut(original)
        }
        return lines.toList()
    }

    private fun orchestrator(prefs: MapPrefs): AgentIntegratedOrchestrator {
        val dir = Files.createTempDirectory("agent-fallback-test").toFile()
        val llm = PlainLlm()
        return AgentIntegratedOrchestrator(
            InsightOrchestrator(llm, prefs, MapStore(), search = null, model = "test"),
            llm, prefs, PluginRegistry(dir),
        )
    }

    private fun dataset() = Dataset(
        name = "销售",
        importedAt = 0L,
        columns = listOf("地区", "销售额"),
        rows = listOf(listOf("华东", "1000")),
        columnTypes = listOf(ColumnType.TEXT, ColumnType.NUMBER),
    )

    @Test
    fun `kind 不被支持——回落 classic 且警告与结果标记双落（T-090·T-091）`() {
        val prefs = MapPrefs()
        // 真实入口：把 followup 类插件选为报告引擎——清单 kind 白名单只允许
        // report/editorial/followup/review，而报告引擎注册表只支持 report/editorial
        AgentEngine.setEngineId(prefs, "followup")
        var result: com.stragent.core.ai.InsightResult? = null
        val logs = capture {
            result = orchestrator(prefs).generateInsight(dataset())
        }
        assertTrue(
            logs.any { it.contains("回落 classic") && it.contains("followup") && it.contains("kind=followup") },
            "应留回落警告且点名所选引擎与 kind：${logs.joinToString { l -> l.take(400) }}",
        )
        // T-091：回落标记随结果走（客户端可见，不只日志）
        assertTrue(result!!.engineFallback, "engineFallback 必须为 true")
        assertTrue(
            result!!.engineFallbackReason?.contains("kind=followup") == true,
            "reason 应说明 kind 不被支持：${result!!.engineFallbackReason}",
        )
    }

    @Test
    fun `插件不存在——回落警告区分第二种事实（T-090）`() {
        val prefs = MapPrefs()
        AgentEngine.setEngineId(prefs, "no-such-plugin")
        var result: com.stragent.core.ai.InsightResult? = null
        val logs = capture {
            result = orchestrator(prefs).generateInsight(dataset())
        }
        assertTrue(
            logs.any { it.contains("回落 classic") && it.contains("不存在") },
            "应留回落警告且说明插件不存在：${logs.take(5)}",
        )
        assertTrue(result!!.engineFallback, "engineFallback 必须为 true")
        assertTrue(
            result!!.engineFallbackReason?.contains("不存在") == true,
            "reason 应说明插件不存在：${result!!.engineFallbackReason}",
        )
    }

    @Test
    fun `classic 默认不告警且不带回落标记——默认路径不是降级（负控，T-090·T-091）`() {
        var result: com.stragent.core.ai.InsightResult? = null
        val logs = capture {
            result = orchestrator(MapPrefs()).generateInsight(dataset())
        }
        assertTrue(
            logs.none { it.contains("回落 classic") },
            "classic 默认路径不得告警：${logs.take(5)}",
        )
        assertTrue(!result!!.engineFallback, "classic 路径 engineFallback 必须 false")
        assertEquals(null, result!!.engineFallbackReason, "无回落不得编造原因")
    }
}
