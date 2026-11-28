package com.stragent.agent

import com.stragent.core.ai.InsightOrchestrator
import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset
import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.DatasetStore
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.PrefsStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-050：断点存储与编排器的接线。
 *
 * 单 Agent 报告出口在生成期间应持有完成态断点（进程崩在门禁前可回放、不重复计费），
 * 成功过门禁交付后**整组清理**——下一次同一份数据是一次新的生成，不是回放旧稿。
 */
class AgentCheckpointWiringTest {

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

    /** 门禁修订调用发生时回调（此时 agent 的完成态断点应已落盘） */
    private class RoutingLlm(
        private val report: String,
        private val revised: String,
    ) : LlmClient {
        var onGateCall: (() -> Unit)? = null
        override fun chat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
        ): ChatResult {
            val joined = messages.joinToString("\n") { it.second }
            val text = if (joined.contains("<gate_issues>")) {
                onGateCall?.invoke()
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

    @Test
    fun `成功交付——门禁期间断点在场，交付后整组清理`() {
        val prefs = MapPrefs()
        AgentEngine.setEngineId(prefs, "report-standard")
        val root = Files.createTempDirectory("stragent-wiring-").toFile()
        val store = AgentCheckpointStore(root)
        // 脏稿触发一次门禁修订：修订调用发生时，agent 的完成态断点必须已经在盘上
        val llm = RoutingLlm(
            "## 总体概览\n\n得分合计 888888，整体表现不错。\n",
            "## 总体概览\n\n得分合计 256，整体平稳。",
        )
        var sawCheckpoint = false
        llm.onGateCall = { sawCheckpoint = root.walkTopDown().any { it.isFile } }
        val result = AgentIntegratedOrchestrator(
            InsightOrchestrator(llm, prefs, MapStore(), search = null, model = "test"),
            llm, prefs, PluginRegistry(tempDir("agent-wiring")), checkpoints = store,
        ).generateInsight(dataset())

        assertEquals("report-standard", result.engineId)
        assertTrue(sawCheckpoint, "门禁修订期间应能读到 agent 的完成态断点")
        assertFalse(
            root.walkTopDown().any { it.isFile },
            "成功交付后整组清理：下一次同一任务是重新生成而不是回放旧稿",
        )
    }
}
