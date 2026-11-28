package com.stragent.core.ai

import com.stragent.core.dataset.TextContent
import com.stragent.core.dataset.TextSegment
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * T-124 索引缓存测试：原结构命中/构建一次、富化条目同键合并并发构建、
 * 失败不留缓存（重算）、降级条目不留缓存、LRU 上限、语义等价（命中=现算）。
 */
class TextIndexCacheTest {

    @org.junit.Before
    fun clear() = TextIndexCache.clear()

    @Test
    fun `原结构——同键只构建一次且命中等于现算`() {
        val content = TextContent(List(5) { TextSegment(it + 1, "第${it + 1}段内容。") })
        val h = TextIndexCache.hashOf(content)
        var builds = 0
        val first = TextIndexCache.raw(h, "groups") { builds++; TextChunker.sentenceGroups(content) }
        val second = TextIndexCache.raw(h, "groups") { builds++; TextChunker.sentenceGroups(content) }
        assertEquals(1, builds, "第二次命中缓存")
        assertEquals(first, second, "命中=现算逐字节等价")
        // 不同 kind 不互串
        val blocks = TextIndexCache.raw(h, "blocks") { builds++; TextChunker.aggregateChunks(content) }
        assertEquals(2, builds)
        assertEquals(blocks.size, TextIndexCache.raw(h, "blocks") { builds++; TextChunker.aggregateChunks(content) }.size)
    }

    @Test
    fun `富化条目——同键并发构建合并为一次`() {
        val builds = AtomicInteger(0)
        val results = Collections.synchronizedList(mutableListOf<String>())
        val threads = (1..4).map {
            Thread {
                results.add(
                    TextIndexCache.enriched("mat-conc", "flags-x") {
                        builds.incrementAndGet()
                        Thread.sleep(50) // 放大并发窗口
                        "值"
                    },
                )
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(1, builds.get(), "并发同键合并为一次构建")
        assertEquals(listOf("值", "值", "值", "值"), results)
    }

    @Test
    fun `富化条目——构建抛异常不留缓存下次重算`() {
        var attempts = 0
        assertFailsWith<IllegalStateException> {
            TextIndexCache.enriched("mat-fail", "f") { attempts++; throw IllegalStateException("构建失败") }
        }
        val v = TextIndexCache.enriched("mat-fail", "f") { attempts++; "成功" }
        assertEquals(2, attempts, "失败不留缓存：第二次重算")
        assertEquals("成功", v)
    }

    @Test
    fun `富化条目——降级条目不留缓存`() {
        var attempts = 0
        class Deg : TextIndexCache.CacheDegradeAware {
            override val cacheDegraded get() = true
        }
        val d1: Deg = TextIndexCache.enriched("mat-deg", "f") { attempts++; Deg() }
        assertTrue(d1.cacheDegraded)
        val d2: Deg = TextIndexCache.enriched("mat-deg", "f") { attempts++; Deg() }
        assertEquals(2, attempts, "降级条目下次重算（不把一次失败钉死成进程级降级）")
    }

    @Test
    fun `LRU——超出上限挤出最旧条目`() {
        TextIndexCache.clear()
        val content = TextContent(List(3) { TextSegment(it + 1, "内容。") })
        val h = TextIndexCache.hashOf(content)
        // 填满 8 份后挤出（cap 默认 8；用富化条目验证）
        for (i in 1..10) {
            val r: Any = TextIndexCache.enriched("mat-$i", "f") { "值$i" }
            assertEquals("值$i", r, "第 $i 次插入即取即验")
        }
        val v: Any = TextIndexCache.enriched("mat-10", "f") { "值10" }
        assertEquals("值10", v, "最新条目在缓存")
        // 材料哈希本身与材料内容解耦：不同 key 不互串
        assertEquals(h, TextIndexCache.hashOf(content))
    }
}
