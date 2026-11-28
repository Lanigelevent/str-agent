package com.stragent.core.ai

import com.stragent.core.ports.DatasetStore
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.ChatResult as PortChatResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-114 块级上下文前缀测试：补语解析（剥块号/超长截断/按位对齐缺失=null）、
 * 批量轻调用（5 块/批）、缓存语义（二次零调用、null 不缓存、部分命中合并）。
 */
class ChunkContextTest {

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

    class CountingCtx : com.stragent.core.ports.ChunkContextClient {
        var calls = 0
        var lastBatch: List<String> = emptyList()
        var fixedNull = false
        override fun contextsFor(
            llm: LlmClient,
            model: String,
            chunks: List<String>,
            cancelSignal: () -> Boolean,
        ): List<String?> {
            calls++
            lastBatch = chunks
            return chunks.map { if (fixedNull) null else "补语(${it.take(4)})" }
        }
    }

    // ---------- parseContextLines（纯函数面） ----------

    @Test
    fun `解析——块号剥离与超长截断`() {
        val lines = LlmChunkContextualizer.parseContextLines(
            "块1：桥梁工程桥面铺装设计参数说明\n块2：项目组织与职责分工说明",
            2,
        )
        assertEquals(listOf("桥梁工程桥面铺装设计参数说明", "项目组织与职责分工说明"), lines)
    }

    @Test
    fun `解析——行数不齐按位对齐缺失槽位如实为 null`() {
        val lines = LlmChunkContextualizer.parseContextLines("块1：只有一条补语", 3)
        assertEquals(listOf("只有一条补语", null, null), lines)
    }

    // ---------- contextsFor（批量轻调用） ----------

    @Test
    fun `分批——十块两批每批五块`() {
        val llm = ScriptedLlm().apply { reply = "块1：通用补语说明甲\n块2：通用补语说明乙\n块3：通用补语说明丙\n块4：通用补语说明丁\n块5：通用补语说明戊" }
        val out = LlmChunkContextualizer.contextsFor(llm, "m", (1..10).map { "第${it}块正文内容" }, { false })
        assertEquals(2, llm.calls.size, "10 块 = 2 次轻调用")
        assertEquals(10, out.size)
        assertTrue(out.all { it != null }, "五条应答覆盖每批五块")
    }

    // ---------- 缓存语义 ----------

    @Test
    fun `缓存——二次调用零 delegate 调用且内容寻址`() {
        val delegate = CountingCtx()
        val store = MemStore()
        val cached = CachedChunkContextClient(delegate, store)
        val chunks = listOf("段1 甲正文", "段2 乙正文")
        val first = cached.contextsFor(ScriptedLlm(), "m", chunks, { false })
        assertEquals(1, delegate.calls)
        val second = cached.contextsFor(ScriptedLlm(), "m", chunks, { false })
        assertEquals(1, delegate.calls, "同材料二次调用全命中缓存")
        assertEquals(first, second)
        assertTrue(store.map.keys.all { it.startsWith("chunkctx.") }, "内容寻址键前缀")
    }

    @Test
    fun `缓存——部分命中只补未命中子集且 null 不缓存`() {
        val delegate = CountingCtx()
        val store = MemStore()
        val cached = CachedChunkContextClient(delegate, store)
        val a = "段1 甲正文"
        val b = "段2 乙正文"
        val first = cached.contextsFor(ScriptedLlm(), "m", listOf(a), { false })
        assertEquals(1, delegate.calls)
        assertEquals("补语(段1 甲)", first[0])
        // 第二次：a 命中缓存，b 未命中——delegate 只收 b；并让 delegate 这次返回 null（解析缺失形态）
        delegate.fixedNull = true
        val out = cached.contextsFor(ScriptedLlm(), "m", listOf(a, b), { false })
        assertEquals(2, delegate.calls)
        assertEquals(listOf("段2 乙正文"), delegate.lastBatch, "未命中子集才过 delegate")
        assertEquals("补语(段1 甲)", out[0], "命中槽位不变")
        assertEquals(null, out[1], "delegate 返回 null 的槽位保持 null")
        // 第三次：b 的 null 未被缓存——delegate 仍会收到 b
        cached.contextsFor(ScriptedLlm(), "m", listOf(b), { false })
        assertEquals(3, delegate.calls, "null 不缓存：解析缺失下次重算")
    }

    @Test
    fun `空表安全`() {
        assertTrue(CachedChunkContextClient(CountingCtx(), MemStore()).contextsFor(ScriptedLlm(), "m", emptyList(), { false }).isEmpty())
    }
}
