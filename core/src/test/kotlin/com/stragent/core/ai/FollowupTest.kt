package com.stragent.core.ai

import com.stragent.core.ports.ChatResult as PortChatResult
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.PrefsStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FollowupTest {
    class FakePrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
    }

    class ScriptedLlm(private val outputs: MutableList<PortChatResult>) : LlmClient {
        val calls = java.util.Collections.synchronizedList(mutableListOf<List<Pair<String, String>>>())
        override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int): PortChatResult {
            calls.add(messages)
            return if (outputs.isEmpty()) PortChatResult("(空)", 10) else outputs.removeAt(0)
        }
    }

    private fun datasetOf(name: String): com.stragent.core.dataset.Dataset {
        val text = File("../fixtures/sample", "$name.csv").readText()
        val parsed = com.stragent.core.csv.Csv.parseDelimited(text)
        val limited = com.stragent.core.csv.Csv.limitRows(parsed)
        val types = com.stragent.core.csv.Csv.inferTypes(limited.rows, limited.columns.size)
        return com.stragent.core.dataset.Dataset(
            name = "$name.csv", importedAt = 1,
            columns = limited.columns, rows = limited.rows, columnTypes = types,
            delimiter = limited.delimiter, truncated = limited.truncated, sourceRows = limited.totalRows,
        )
    }

    @Test
    fun `sampleByChapters——开头加每章首段，超预算截断`() {
        val report = buildString {
            append("开头段。")
            repeat(60) { append("开头内容${it}。") }
            for (c in 1..3) {
                append("\n\n## 第${c}章\n\n")
                repeat(50) { append("第${c}章内容${it}。") }
            }
        }
        val sampled = sampleByChapters(report, 500)
        assertTrue(sampled.length <= 500 + "…（后续略）…".length, "长度 ${sampled.length}")
        assertTrue(sampled.contains("## 第1章"))
        // 预算内则原样返回
        assertEquals("短报告", sampleByChapters("短报告", 500))
    }

    @Test
    fun `askFollowup——报告垫底、历史成对裁剪、纪律块注入`() {
        val llm = ScriptedLlm(mutableListOf(PortChatResult("直接回答。", 100)))
        val prefs = FakePrefs()
        val dataset = datasetOf("single_col")
        // 8 条历史：报告 + 4 组完整问答。MAX=6 → budget=5 → even=4 → 尾部 2 组
        val history = buildList {
            add(ChatTurn("assistant", "首轮报告。"))
            for (i in 1..8) add(ChatTurn(if (i % 2 == 1) "user" else "assistant", "轮次$i"))
        }
        val result = askFollowup(llm, prefs, dataset, history, "  华南为什么跑输华东？  ", model = "test")
        assertEquals("直接回答。", result.content)
        val msgs = llm.calls.single()
        // system 带 role/task 与写作纪律；user 带摘要与免责声明；报告是第一条 assistant
        assertTrue(msgs[0].first == "system" && msgs[0].second.contains("<followup_requirements>"))
        assertTrue(msgs[0].second.contains("<de_ai_style>"))
        assertTrue(msgs[1].second.contains("统计摘要"))
        assertEquals("assistant", msgs[2].first)
        assertEquals("首轮报告。", msgs[2].second)
        // TS askFollowup 口径：只做偶数条截尾（takeLast(evenBudget)），不做 trimTurns 的孤儿修复——
        // 完整成对的历史（第 5~8 条）截出来正好从 user 开始
        val tail = msgs.drop(3).dropLast(1)
        assertEquals(4, tail.size)
        assertEquals("user", tail.first().first)
        assertEquals("assistant", tail.last().first)
        assertEquals("华南为什么跑输华东？", msgs.last().second, "trim 过的本次提问")
    }

    @Test
    fun `askFollowup——悬空问句按 TS 原样进入上下文（无孤儿修复）`() {
        // rest = 7 条、末条是没答完的 user：takeLast(4) = [a,u,a,u]——
        // TS 代码就是这个行为（trimTurns 的孤儿修复只作用于落盘，不作用于追问上下文）
        val llm = ScriptedLlm(mutableListOf(PortChatResult("答", 10)))
        val prefs = FakePrefs()
        val history = buildList {
            add(ChatTurn("assistant", "首轮报告。"))
            for (i in 1..7) add(ChatTurn(if (i % 2 == 1) "user" else "assistant", "轮次$i"))
        }
        askFollowup(llm, prefs, datasetOf("single_col"), history, "问题", "m")
        val tail = llm.calls.single().drop(3).dropLast(1)
        assertEquals(4, tail.size)
        assertEquals("assistant", tail.first().first)
    }

    @Test
    fun `askFollowup——空问题拒绝、空历史不垫报告`() {
        val llm = ScriptedLlm(mutableListOf(PortChatResult("答", 10)))
        val prefs = FakePrefs()
        val err = runCatching { askFollowup(llm, prefs, datasetOf("single_col"), emptyList(), "   ", "m") }
        assertTrue(err.exceptionOrNull() != null)
        val result = askFollowup(llm, prefs, datasetOf("single_col"), emptyList(), "问题", "m")
        assertTrue(result.content == "答")
        val msgs = llm.calls.last()
        assertTrue(msgs.none { it.first == "assistant" && it.second == "首轮报告。" })
    }

    @Test
    fun `askFollowup——跨会话记忆进 system 且与可引用数字隔离`() {
        val llm = ScriptedLlm(mutableListOf(PortChatResult("答", 10), PortChatResult("答", 10)))
        val prefs = FakePrefs()
        val dataset = datasetOf("single_col")
        val fact = com.stragent.core.memory.MemoryFact("f1", "followup", "华东占比 45.3%", "mcp", 100L)
        // ① 有记忆：独立块 + 三条纪律都在，且不落到带 allowed_numbers 的摘要消息里
        askFollowup(llm, prefs, dataset, emptyList(), "问题", "m", listOf(fact))
        val withMemory = llm.calls[0][0].second
        assertTrue(withMemory.contains("<session_memory>"))
        assertTrue(withMemory.contains("- [followup] 华东占比 45.3%"))
        assertTrue(withMemory.contains("不是数据"))
        assertTrue(llm.calls[0][1].second.contains("<allowed_numbers>"), "摘要消息才是可引用数字的来源")
        assertFalse(llm.calls[0][1].second.contains("<session_memory>"), "纪律 a：记忆不许并进 brief")
        // ② 默认参数（既有调用方零改动）：不留块、也不留一个空行残渣
        askFollowup(llm, prefs, dataset, emptyList(), "问题", "m")
        val withoutMemory = llm.calls[1][0].second
        assertFalse(withoutMemory.contains("<session_memory>"))
        assertTrue(
            withoutMemory.contains("</conflict_resolution>\n<followup_requirements>"),
            "无记忆时的提示词必须与加入记忆能力之前逐字一致",
        )
        assertTrue(withoutMemory.length < withMemory.length)
    }

    @Test
    fun `askFollowup——记录级门禁：假数字标记失败但不改回答`() {
        val llm = ScriptedLlm(
            mutableListOf(
                PortChatResult("得分合计 888888，比上一轮高。", 10),
                PortChatResult("得分合计 256。", 10),
            ),
        )
        val prefs = FakePrefs()
        val dataset = datasetOf("single_col")
        val dirty = askFollowup(llm, prefs, dataset, emptyList(), "这次得分如何？", "m")
        assertTrue(dirty.gateFailed, "回答里的未溯源数字必须如实标记")
        assertEquals("得分合计 888888，比上一轮高。", dirty.content, "对话回答不许被静默改写")
        assertTrue(dirty.gateIssues.any { it.contains("unsourced_number") && it.contains("888888") }, "${dirty.gateIssues}")

        val clean = askFollowup(llm, prefs, dataset, emptyList(), "这次得分如何？", "m")
        assertFalse(clean.gateFailed)
        assertTrue(clean.gateIssues.isEmpty())
    }
}
