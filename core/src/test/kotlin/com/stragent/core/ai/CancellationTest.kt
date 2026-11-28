package com.stragent.core.ai

import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset
import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.DatasetStore
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.PrefsStore
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 编排层协作式取消：`cancelSignal` 的每个可停点都真的会抛 [GenerationCancelled]。
 *
 * 背景：`cancelSignal` 参数曾长期「收了信号不执行」——经典路径接上了参数却没人查，
 * 客户端取消后仍会照常花钱生成。本测试把各停点钉住：
 *   ① 入口（排队/预处理阶段就取消）；
 *   ② 单次成文之后、语义审查之前（不再多花一次审查调用）；
 *   ③ 分章之间（未写的章不再发起调用，已写章落盘可续传）；
 *   ④ 截断续写轮（追问与报告同源 `chatStep`：取消后不再追加计费调用）。
 */
class CancellationTest {
    private val sampleDir = File("../fixtures/sample")

    private class FakePrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
    }

    private class FakeStore : DatasetStore {
        val map = mutableMapOf<String, String>()
        override fun put(key: String, value: String) { map[key] = value }
        override fun get(key: String): String? = map[key]
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): List<String> = map.keys.toList()
    }

    /** 每次调用后执行 afterCall（用于模拟「生成期间客户端取消了」） */
    private class CancelAfterLlm(
        private val outputs: MutableList<ChatResult>,
        private val afterCall: (Int) -> Unit,
    ) : LlmClient {
        var calls = 0
            private set

        override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int): ChatResult {
            calls += 1
            val result = if (outputs.isEmpty()) ChatResult("(空)", 10) else outputs.removeAt(0)
            afterCall(calls)
            return result
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

    @Test
    fun `入口即取消——一次模型调用都不发`() {
        val llm = CancelAfterLlm(mutableListOf(ChatResult("不该出现", 1))) { }
        val orchestrator = InsightOrchestrator(llm, FakePrefs(), model = "test")
        assertFailsWith<GenerationCancelled> {
            orchestrator.generateInsight(datasetOf("single_col"), cancelSignal = { true })
        }
        assertEquals(0, llm.calls)
    }

    @Test
    fun `单次路径——成文后取消不再花审查调用`() {
        val cancelled = AtomicBoolean(false)
        val llm = CancelAfterLlm(mutableListOf(ChatResult("## 总体概览\n正文。", 500))) { calls ->
            if (calls == 1) cancelled.set(true)
        }
        val orchestrator = InsightOrchestrator(llm, FakePrefs(), model = "test")
        assertFailsWith<GenerationCancelled> {
            orchestrator.generateInsight(datasetOf("single_col"), cancelSignal = { cancelled.get() })
        }
        // 只发了成文那一次：语义审查被取消信号拦下
        assertEquals(1, llm.calls)
    }

    @Test
    fun `分章路径——章间取消，未写的章不再调用，已写章落盘`() {
        val cancelled = AtomicBoolean(false)
        val store = FakeStore()
        val prefs = FakePrefs()
        val dataset = datasetOf("superstore")
        // 每章的输出都备好；但取消会在第一章之后生效，后面的不该被消费
        val outputs = MutableList(30) { ChatResult("第${it + 1}章内容。", 100) }
        val llm = CancelAfterLlm(outputs) { calls -> if (calls == 1) cancelled.set(true) }
        val orchestrator = InsightOrchestrator(llm, prefs, store, model = "test")
        assertFailsWith<GenerationCancelled> {
            orchestrator.generateInsight(dataset, cancelSignal = { cancelled.get() })
        }
        assertEquals(1, llm.calls, "取消后不该再为后续章节花钱")
        // 已写完的第一章落盘（断点续传语义：取消不是失败，下次接着写）
        val draft = store.map.values.firstOrNull()
        assertTrue(draft != null && draft.contains("第1章内容。"), "第一章应进草稿：$draft")
        assertTrue(draft!!.contains(PART_SEP), "草稿应保留分章分隔格式")
    }

    @Test
    fun `追问入口即取消——一次模型调用都不发`() {
        val llm = CancelAfterLlm(mutableListOf(ChatResult("不该出现", 1))) { }
        assertFailsWith<GenerationCancelled> {
            askFollowup(
                llm, FakePrefs(), datasetOf("single_col"), emptyList(), "华南为什么跑输华东", "test",
                cancelSignal = { true },
            )
        }
        assertEquals(0, llm.calls)
    }

    @Test
    fun `追问续写轮——首轮被截断且已取消时不再发起续写`() {
        val cancelled = AtomicBoolean(false)
        val llm = CancelAfterLlm(mutableListOf(ChatResult("先写一半的回答", 100, truncated = true))) { calls ->
            if (calls == 1) cancelled.set(true)
        }
        assertFailsWith<GenerationCancelled> {
            askFollowup(
                llm, FakePrefs(), datasetOf("single_col"), emptyList(), "华南为什么跑输华东", "test",
                cancelSignal = { cancelled.get() },
            )
        }
        assertEquals(1, llm.calls, "截断续写也是计费调用：取消后不该再发")
    }

    @Test
    fun `单次成文续写轮——首轮被截断且已取消时不再发起续写`() {
        val cancelled = AtomicBoolean(false)
        val llm = CancelAfterLlm(mutableListOf(ChatResult("## 总体概览\n半篇正文。", 500, truncated = true))) { calls ->
            if (calls == 1) cancelled.set(true)
        }
        val orchestrator = InsightOrchestrator(llm, FakePrefs(), model = "test")
        assertFailsWith<GenerationCancelled> {
            orchestrator.generateInsight(datasetOf("single_col"), cancelSignal = { cancelled.get() })
        }
        assertEquals(1, llm.calls, "取消后不该再为截断续写买单")
    }
}
