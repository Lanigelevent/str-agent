package com.stragent.core.ai

import com.stragent.core.ports.DatasetStore
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.ChatResult as PortChatResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-117 块摘要器测试：摘要清洗（剥前缀/引号/截断/空=null）、逐块单调用、缓存语义
 * （二次零调用、null 不缓存、部分命中合并）。
 */
class BlockSummaryTest {

    class ScriptedLlm : LlmClient {
        val calls = java.util.Collections.synchronizedList(mutableListOf<List<Pair<String, String>>>())
        var reply = ""
        override fun chat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
        ): PortChatResult {
            calls += messages
            return PortChatResult(reply, 55)
        }
    }

    class MemStore : DatasetStore {
        val map = linkedMapOf<String, String>()
        override fun put(key: String, value: String) { map[key] = value }
        override fun get(key: String): String? = map[key]
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): List<String> = map.keys.toList()
    }

    class CountingSummarizer : com.stragent.core.ports.BlockSummarizer {
        var calls = 0
        var lastBatch: List<String> = emptyList()
        var fixedNull = false
        override fun summariesFor(
            llm: LlmClient,
            model: String,
            blocks: List<String>,
            cancelSignal: () -> Boolean,
        ): List<String?> {
            calls++
            lastBatch = blocks
            return blocks.map { if (fixedNull) null else "摘要(${it.take(4)})" }
        }
    }

    // ---------- cleanSummary（纯函数面） ----------

    @Test
    fun `清洗——剥摘要前缀与包裹引号且超长截断`() {
        assertEquals("本块汇总巡检记录与现场管理情况", LlmBlockSummarizer.cleanSummary("摘要：本块汇总巡检记录与现场管理情况"))
        assertEquals("直接给出内容也行", LlmBlockSummarizer.cleanSummary("「直接给出内容也行」"))
        assertEquals(null, LlmBlockSummarizer.cleanSummary("   "))
        val long = LlmBlockSummarizer.cleanSummary("长".repeat(500))!!
        assertEquals(LlmBlockSummarizer.MAX_SUMMARY_CHARS, long.length, "超长截断到上限")
    }

    // ---------- summariesFor（逐块单调用） ----------

    @Test
    fun `逐块单调用——两块两次调用且空应答槽位如实为 null`() {
        val llm = ScriptedLlm().apply { reply = "第一块摘要内容" }
        val out = LlmBlockSummarizer.summariesFor(llm, "m", listOf("块甲正文", "块乙正文"), { false })
        assertEquals(2, llm.calls.size, "逐块单调用（不做批内行对齐）")
        assertEquals("第一块摘要内容", out[0])
        // 第二块应答仍是同一固定文本（ScriptedLlm），槽位有值；空应答语义由清洗单测覆盖
        assertTrue(out.all { it != null })
    }

    @Test
    fun `空应答——清洗为 null 该块如实缺失`() {
        val llm = ScriptedLlm().apply { reply = "" }
        val out = LlmBlockSummarizer.summariesFor(llm, "m", listOf("块甲正文"), { false })
        assertEquals(listOf<String?>(null), out, "空应答 → null（该块回落原块索引）")
    }

    // ---------- 缓存语义 ----------

    @Test
    fun `缓存——二次调用零 delegate 调用且 null 不缓存`() {
        val delegate = CountingSummarizer()
        val store = MemStore()
        val cached = CachedBlockSummarizer(delegate, store)
        val blocks = listOf("段1-17 块甲正文", "段18-34 块乙正文")
        val first = cached.summariesFor(ScriptedLlm(), "m", blocks, { false })
        assertEquals(1, delegate.calls)
        val second = cached.summariesFor(ScriptedLlm(), "m", blocks, { false })
        assertEquals(1, delegate.calls, "同材料二次调用全命中缓存")
        assertEquals(first, second)
        assertTrue(store.map.keys.all { it.startsWith("blocksum.") }, "内容寻址键前缀")
        // null 不缓存：delegate 返回 null 的块，下次仍会重算
        delegate.fixedNull = true
        cached.summariesFor(ScriptedLlm(), "m", listOf("段1-17 块甲正文"), { false })
        delegate.fixedNull = false
        val third = cached.summariesFor(ScriptedLlm(), "m", listOf("段1-17 块甲正文"), { false })
        assertEquals("摘要(段1-1)", third[0])
    }

    @Test
    fun `缓存——部分命中只补未命中子集`() {
        val delegate = CountingSummarizer()
        val store = MemStore()
        val cached = CachedBlockSummarizer(delegate, store)
        val a = "块甲正文"
        val b = "块乙正文"
        cached.summariesFor(ScriptedLlm(), "m", listOf(a), { false })
        assertEquals(1, delegate.calls)
        val out = cached.summariesFor(ScriptedLlm(), "m", listOf(a, b), { false })
        assertEquals(2, delegate.calls)
        assertEquals(listOf(b), delegate.lastBatch, "未命中子集才过 delegate")
        assertEquals("摘要(块甲正文)", out[0])
        assertEquals("摘要(块乙正文)", out[1])
    }
}
