package com.stragent.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-113 多列表 RRF 融合测试：Σ 1/(K+rank) 数学、跨列表互证排序、同分稳定性、sources 并集、空表安全。
 */
class HybridRetrieverRrfFuseTest {

    private fun hit(id: String, seg: Int, src: String = "bm25") =
        HybridRetriever.Scored(id, seg, 0.0, listOf(src))

    @Test
    fun `融合数学——各列表按名次贡献 1 除以 60 加 rank`() {
        val fused = HybridRetriever.rrfFuse(
            listOf(
                listOf(hit("a", 1), hit("b", 2)),
                listOf(hit("c", 3)),
            ),
        )
        val byId = fused.associateBy { it.id }
        // rank 从 1 起算：a/b 在列表 0 的名次 1/2；c 在列表 1 的名次 1
        assertEquals(1.0 / 61, byId.getValue("a").score, 1e-12)
        assertEquals(1.0 / 62, byId.getValue("b").score, 1e-12)
        assertEquals(1.0 / 61, byId.getValue("c").score, 1e-12)
        // 同分（a 与 c 各列首位）按首列表出现名次稳定：a 在列表 0 → 先于 c
        assertEquals("a", fused.first().id)
    }

    @Test
    fun `跨列表互证——两个列表都命中的文档排到只命中一个的前面`() {
        val fused = HybridRetriever.rrfFuse(
            listOf(
                listOf(hit("x", 1), hit("y", 2)),
                listOf(hit("z", 3), hit("x", 1), hit("y", 2)),
            ),
        )
        assertEquals("x", fused[0].id, "双列表互证（2/61+1/62）压过单列表首位（1/61）")
        assertEquals("y", fused[1].id)
        assertEquals("z", fused[2].id)
    }

    @Test
    fun `sources 并集与段号透传`() {
        val fused = HybridRetriever.rrfFuse(
            listOf(
                listOf(HybridRetriever.Scored("d1", 7, 0.9, listOf("bm25"))),
                listOf(HybridRetriever.Scored("d1", 7, 0.8, listOf("vector"))),
            ),
        )
        assertEquals(1, fused.size)
        assertEquals(7, fused[0].segmentIndex, "段号取首列表透传")
        assertEquals(setOf("bm25", "vector"), fused[0].sources.toSet())
    }

    @Test
    fun `空表安全与三列表融合`() {
        assertTrue(HybridRetriever.rrfFuse(emptyList()).isEmpty())
        assertTrue(HybridRetriever.rrfFuse(listOf(emptyList(), emptyList())).isEmpty())
        val fused = HybridRetriever.rrfFuse(
            listOf(
                listOf(hit("a", 1)),
                listOf(hit("b", 2)),
                listOf(hit("a", 1)),
            ),
        )
        assertEquals("a", fused[0].id, "三列表：a 两个列表命中（2/61）> b（1/61）")
        assertEquals(2.0 / 61 + 0.0, fused[0].score, 1e-12)
    }
}
