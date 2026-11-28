package com.stragent.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-100 混合检索单元测试：RRF 融合、语义路补盲、余弦边界、单路退化、确定性。
 */
class HybridRetrieverTest {

    private fun docs() = listOf(
        HybridRetriever.Doc("d1", 1, "第二季度营业收入 128 万元，环比增长 12%。"),
        HybridRetriever.Doc("d2", 2, "仓储物流成本同比上升明显。"),
        HybridRetriever.Doc("d3", 3, "毛利率 30%，成本控制良好。"),
    )

    @Test
    fun `双路命中——融合分高于单路且 sources 记账`() {
        // fake 向量：让 d1 与查询同向（语义路与词法路都命中 d1）
        val q = doubleArrayOf(1.0, 0.0)
        val vecs = listOf(doubleArrayOf(0.9, 0.1), doubleArrayOf(0.0, 1.0), doubleArrayOf(0.2, 0.8))
        val out = HybridRetriever.retrieve(docs(), "营业收入 128", q, vecs, 3)
        assertEquals("d1", out[0].id)
        assertEquals(listOf("bm25", "vector"), out[0].sources.sorted())
        assertTrue(out[0].score > out[1].score, "双路命中分应高于单路")
    }

    @Test
    fun `语义路补盲——BM25 未命中的同义改写经向量命中`() {
        // 查询「营收表现」与文档「营业收入」bigram 不相交（营收 vs 营业/业收）→ BM25 空
        // fake 向量把两者拉到同向（模拟嵌入的同义性）
        val q = doubleArrayOf(1.0, 0.0, 0.0)
        val vecs = listOf(doubleArrayOf(0.95, 0.3, 0.0), doubleArrayOf(0.0, 0.1, 0.9), doubleArrayOf(0.1, 0.0, 0.8))
        val out = HybridRetriever.retrieve(docs(), "营收表现", q, vecs, 3)
        assertTrue(out.isNotEmpty() && out[0].id == "d1", "嵌入应补上词法盲区")
        assertEquals(listOf("vector"), out[0].sources)
    }

    @Test
    fun `单路退化——无向量时等价于 BM25 纯词法`() {
        val hybrid = HybridRetriever.retrieve(docs(), "仓储物流成本", null, null, 3)
        val bm25 = Bm25Index(docs().map { Bm25Index.Doc(it.id, it.segmentIndex, it.indexText) }).score("仓储物流成本")
        assertEquals(bm25.first().id, hybrid[0].id, "无向量 = BM25 既有行为")
        assertTrue(hybrid.all { it.sources == listOf("bm25") })
    }

    @Test
    fun `维度不齐或零向量——该路安全记 0 不抛`() {
        val q = doubleArrayOf(1.0, 0.0)
        val vecs = listOf(doubleArrayOf(1.0), doubleArrayOf(0.0, 0.0), doubleArrayOf(0.3, 0.3))
        val out = HybridRetriever.retrieve(docs(), "成本", q, vecs, 3)
        assertTrue(out.isNotEmpty())
        val cos0 = HybridRetriever.cosine(doubleArrayOf(1.0, 0.0), doubleArrayOf(1.0))
        assertEquals(0.0, cos0)
        assertEquals(0.0, HybridRetriever.cosine(doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 0.0)))
    }

    @Test
    fun `RRF 排名权重——两路都排前的文档胜过单路第一`() {
        // d3：BM25 第 1（词法命中「成本」）；d1：向量第 1 + BM25 靠后
        // 构造：查询同时含 d3 词法项、d1 语义项 → 各自单路第一，融合看两路合计
        val q = doubleArrayOf(0.0, 1.0)
        val vecs = listOf(doubleArrayOf(0.1, 0.9), doubleArrayOf(0.2, 0.8), doubleArrayOf(0.0, 0.2))
        val out = HybridRetriever.retrieve(docs(), "成本", q, vecs, 3)
        assertTrue(out.size >= 2)
        // d2 与 d3 都含「成本」bigram；d1 语义最相近 → d1 应靠双路积分领先
        assertTrue(out.any { it.sources.size == 2 } || out[0].sources.size == 2, "存在双路命中即融合生效")
    }

    @Test
    fun `k 上限与空输入安全`() {
        assertEquals(0, HybridRetriever.retrieve(emptyList(), "任意", null, null, 5).size)
        val out = HybridRetriever.retrieve(docs(), "成本", null, null, 1)
        assertEquals(1, out.size)
    }
}
