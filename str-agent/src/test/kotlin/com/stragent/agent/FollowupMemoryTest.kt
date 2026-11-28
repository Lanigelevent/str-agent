package com.stragent.agent

import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset
import com.stragent.core.memory.MemoryFact
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
 * 跨会话记忆进追问提示词的**两条路径**都要覆盖。
 *
 * 为什么两条都要测：追问按引擎选择分发——`agent.engine` 指向 followup 类插件时走
 * AgentLoop（记忆拼在 task 文本里），否则走 core 的经典流水线（记忆拼在 system 里）。
 * 只测一条就会出现「换了引擎就失忆」：接线看着完整，实际上一半路径漏注入。
 */
class FollowupMemoryTest {

    /** 捕获每一轮 messages，并按脚本回复（agent 路径需要 finish JSON） */
    private class CapturingLlm(private val outputs: MutableList<String>) : LlmClient {
        val calls = mutableListOf<List<Pair<String, String>>>()
        override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int): ChatResult {
            calls.add(messages)
            val out = if (outputs.isNotEmpty()) outputs.removeAt(0) else "（空）"
            return ChatResult(out, 10)
        }
    }

    private class MemPrefs : PrefsStore {
        private val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private class MemStore : DatasetStore {
        private val map = mutableMapOf<String, String>()
        override fun put(key: String, value: String) { map[key] = value }
        override fun get(key: String): String? = map[key]
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): List<String> = map.keys.toList()
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
            com.stragent.core.ai.InsightOrchestrator(llm, prefs, MemStore(), search = null, model = "test"),
            llm, prefs, PluginRegistry(tempDir("followup-memory")),
        )

    private val fact = MemoryFact("f1", "followup", "华东占比 45.3%（上次会话的结论）", "mcp", 100L)

    @Test
    fun `经典路径——记忆块进 system 且在 brief 之外`() {
        val llm = CapturingLlm(mutableListOf("直接回答。"))
        val prefs = MemPrefs()
        orchestrator(llm, prefs).askFollowup(dataset(), listOf(com.stragent.core.ai.ChatTurn("assistant", "首轮报告。")), "再说说华东", "test", listOf(fact))
        val msgs = llm.calls.single()
        val system = msgs[0].second
        assertTrue(system.contains("<session_memory>"), "记忆块必须在 system 里")
        assertTrue(system.contains("- [followup] 华东占比 45.3%"))
        assertTrue(system.contains("本次未复核"))
        // 纪律 a：记忆是独立块，绝不并进带 allowed_numbers 的摘要消息
        val userBrief = msgs[1].second
        assertFalse(userBrief.contains("<session_memory>"))
        assertFalse(system.contains("<allowed_numbers>"))
        // 位置口径（T-128 提示缓存对齐后）：稳定纪律块在前，per-call 可变块（记忆）在 system 末尾
        assertTrue(system.indexOf("<followup_requirements>") < system.indexOf("<session_memory>"))
    }

    @Test
    fun `经典路径——默认空记忆不留任何残渣（逐字节等于改动前）`() {
        val llm = CapturingLlm(mutableListOf("答", "答"))
        val prefs = MemPrefs()
        val oc = orchestrator(llm, prefs)
        val ds = dataset()
        // 显式传 emptyList 与不传（默认参数）必须同形
        oc.askFollowup(ds, emptyList(), "问题", "test")
        oc.askFollowup(ds, emptyList(), "问题", "test", emptyList())
        val a = llm.calls[0][0].second
        val b = llm.calls[1][0].second
        assertEquals(a, b, "默认参数与空表必须产出同一段提示词")
        assertFalse(a.contains("<session_memory>"))
        // 空串拼接不留多余换行：两个锚点之间仍是一个 \n
        assertTrue(a.contains("</conflict_resolution>\n<followup_requirements>"), "无记忆时必须与改动前逐字一致")
    }

    @Test
    fun `agent 路径——记忆拼进 task 且不进 data_brief`() {
        val llm = CapturingLlm(mutableListOf("""{"tool":"finish","args":{"answer":"华东占 45.3%"}}"""))
        val prefs = MemPrefs()
        AgentEngine.setEngineId(prefs, "followup")
        val result = orchestrator(llm, prefs).askFollowup(
            dataset(), emptyList(), "华东占比多少", "test", listOf(fact),
        )
        assertEquals("华东占 45.3%", result.content, "走的必须是 agent 路径（finish.args.answer）")
        val task = llm.calls[0].last { it.second.contains("<data_brief>") }.second
        assertTrue(task.contains("<session_memory>"), "agent 路径同样要带记忆，否则换引擎就失忆")
        assertTrue(task.contains("- [followup] 华东占比 45.3%"))
        // 纪律 a 的 agent 版：记忆在 </data_brief> 之后，摘要里的 allowed_numbers 提不到它
        assertTrue(task.indexOf("</data_brief>") < task.indexOf("<session_memory>"))
        assertFalse(task.substring(task.indexOf("<data_brief>")..task.indexOf("</data_brief>")).contains("<session_memory>"))
    }

    @Test
    fun `agent 路径——空记忆时 task 里不出现记忆块`() {
        val llm = CapturingLlm(mutableListOf("""{"tool":"finish","args":{"answer":"好的"}}"""))
        val prefs = MemPrefs()
        AgentEngine.setEngineId(prefs, "followup")
        orchestrator(llm, prefs).askFollowup(dataset(), emptyList(), "问题", "test")
        val task = llm.calls[0].last { it.second.contains("<data_brief>") }.second
        assertFalse(task.contains("<session_memory>"))
        assertTrue(task.contains("<data_brief>"), "task 本身仍要完整")
    }

    @Test
    fun `agent 路径——模糊追问注入意图澄清闸，明确追问不注入`() {
        val prefs = MemPrefs()
        AgentEngine.setEngineId(prefs, "followup")
        // 模糊：悬空指代 + 泛问
        val llmVague = CapturingLlm(mutableListOf("""{"tool":"finish","args":{"answer":"那个是华东"}}"""))
        orchestrator(llmVague, prefs).askFollowup(dataset(), emptyList(), "那个怎么样", "test")
        val vagueTask = llmVague.calls[0].last { it.second.contains("<data_brief>") }.second
        val gate = vagueTask.substringAfter("<intent_gate>", "")
        assertTrue(gate.isNotEmpty(), "模糊追问必须注入 <intent_gate>（悬空指代不声明落点就是猜着答）")
        assertTrue(gate.contains("那个"), "闸块要带命中证据，供调用方复核判据")
        assertTrue(vagueTask.indexOf("</data_brief>") < vagueTask.indexOf("<intent_gate>"), "闸在摘要之外，不进 data_brief")
        // 明确：零信号，task 与旧形状一致（无 gate 块、无多余空段）
        val llmClear = CapturingLlm(mutableListOf("""{"tool":"finish","args":{"answer":"华东占 45.3%"}}"""))
        orchestrator(llmClear, prefs).askFollowup(dataset(), emptyList(), "华东占比多少", "test")
        val clearTask = llmClear.calls[0].last { it.second.contains("<data_brief>") }.second
        assertFalse(clearTask.contains("<intent_gate>"), "明确追问不得注入闸块（零行为差异纪律）")
    }
}
