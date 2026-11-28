package com.stragent.core.ai

import com.stragent.core.ports.DatasetStore
import com.stragent.core.ports.EmbeddingClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T-101 嵌入缓存测试：批量合并命中/未命中、二次调用零 API 调用、内容寻址键、失败不缓存。
 */
class CachedEmbeddingClientTest {

    private class CountingEmbedding : EmbeddingClient {
        var calls = 0
        var lastBatch: List<String> = emptyList()
        override fun embed(texts: List<String>): List<DoubleArray> {
            calls++
            lastBatch = texts
            return texts.map { t -> doubleArrayOf(t[0].code.toDouble(), 1.0) }  // 首字符码：不同字不同向量
        }
    }

    private class MemStore : DatasetStore {
        val map = linkedMapOf<String, String>()
        override fun put(key: String, value: String) { map[key] = value }
        override fun get(key: String): String? = map[key]
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): List<String> = map.keys.toList()
    }

    @Test
    fun `批量合并——部分命中只调未命中的子集且按原序拼装`() {
        val kv = MemStore()
        val delegate = CountingEmbedding()
        val cached = CachedEmbeddingClient(delegate, kv)
        // 预热：「甲」已有缓存
        cached.embed(listOf("甲"))
        assertEquals(1, delegate.calls)

        val out = cached.embed(listOf("甲", "乙", "丙"))
        assertEquals(2, delegate.calls, "只调未命中子集（乙丙）")
        assertEquals(listOf("乙", "丙"), delegate.lastBatch, "未命中子集批量下发")
        assertEquals(3, out.size)
        assertEquals("甲"[0].code.toDouble(), out[0][0], 1e-9, "命中的取缓存")
        assertEquals("乙"[0].code.toDouble(), out[1][0], 1e-9, "未命中「乙」取新算")
        assertEquals("丙"[0].code.toDouble(), out[2][0], 1e-9, "未命中「丙」取新算")
    }

    @Test
    fun `二次调用——同文本零 API 调用（跨追问复用的核心断言）`() {
        val kv = MemStore()
        val delegate = CountingEmbedding()
        val cached = CachedEmbeddingClient(delegate, kv)
        val texts = listOf("段落一", "段落二", "段落三")
        cached.embed(texts)
        val callsAfterFirst = delegate.calls

        val out = cached.embed(texts)
        assertEquals(callsAfterFirst, delegate.calls, "全命中 → 零嵌入调用")
        assertEquals(3, out.size)
    }

    @Test
    fun `内容寻址——文本变一个字键就变（无失效问题）`() {
        val kv = MemStore()
        val delegate = CountingEmbedding()
        val cached = CachedEmbeddingClient(delegate, kv)
        cached.embed(listOf("收入 128 万元"))
        cached.embed(listOf("收入 128 万元。"))
        assertEquals(2, delegate.calls, "一字之差 = 不同键 = 重新计算")
    }

    @Test
    fun `失败不缓存——delegate 抛错后重试会再调`() {
        val kv = MemStore()
        var shouldFail = true
        val flaky = object : EmbeddingClient {
            var calls = 0
            override fun embed(texts: List<String>): List<DoubleArray> {
                calls++
                if (shouldFail) throw RuntimeException("端点 503")
                return texts.map { doubleArrayOf(1.0) }
            }
        }
        val cached = CachedEmbeddingClient(flaky, kv)
        try {
            cached.embed(listOf("文本"))
            fail("失败应上抛（回落决策在调用方）")
        } catch (e: RuntimeException) {
            assertTrue(e.message!!.contains("503"))
        }
        shouldFail = false
        val out = cached.embed(listOf("文本"))
        assertEquals(2, flaky.calls, "失败未写缓存 → 重试真的重算")
        assertEquals(1, out.size)
    }
}
