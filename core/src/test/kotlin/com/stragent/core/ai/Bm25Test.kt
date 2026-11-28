package com.stragent.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-096 BM25 检索测试：分词确定性 / 评分排序 / 长度归一 / 0 命中。
 */
class Bm25Test {

    @Test
    fun `tokenize 中文 bigram 与 ASCII 整词`() {
        assertEquals(listOf("销售", "售额", "额增", "增长"), Bm25Index.tokenize("销售额增长"))
        assertEquals(listOf("revenue", "2024"), Bm25Index.tokenize("Revenue 2024!"))
        assertEquals(listOf("q3", "销售"), Bm25Index.tokenize("Q3 销售。额"), "句号是分隔符，隔开的单字不成 bigram")
        assertEquals(emptyList(), Bm25Index.tokenize("。！？"))
    }

    @Test
    fun `包含查询词的文档排在前面`() {
        val index = Bm25Index(
            listOf(
                Bm25Index.Doc("d1", 1, "销售额同比增长百分之十"),
                Bm25Index.Doc("d2", 2, "本季度成本下降明显"),
                Bm25Index.Doc("d3", 3, "销售额与毛利率的季度走势"),
            ),
        )
        val hits = index.score("销售额增长")
        assertTrue(hits.size >= 2)
        assertEquals("d1", hits[0].id, "d1 同时含 销售/售额/额增/增长 四个 bigram，应排第一")
        assertTrue(hits[0].score > hits[1].score)
    }

    @Test
    fun `长度归一——短文档同词频得分更高`() {
        val index = Bm25Index(
            listOf(
                Bm25Index.Doc("short", 1, "销售额增长。"),
                Bm25Index.Doc("long", 2, "销售额增长。" + "其他无关内容。".repeat(50)),
            ),
        )
        val hits = index.score("销售额")
        assertEquals("short", hits[0].id, "同 tf 下短文档（b 长度归一）应排前")
    }

    @Test
    fun `上下文前缀参与检索`() {
        val withPrefix = Bm25Index(
            listOf(
                Bm25Index.Doc("a", 1, "年度报告·第三章·成本分析。原材料采购成本上升。"),
                Bm25Index.Doc("b", 2, "年度报告·第五章·风险提示。汇率波动风险。"),
            ),
        )
        val hits = withPrefix.score("第三章")
        assertTrue(hits.isNotEmpty() && hits[0].id == "a", "前缀词应可检索定位章节")
    }

    @Test
    fun `0 命中返回空且 topK 上限生效`() {
        val index = Bm25Index(listOf(Bm25Index.Doc("d1", 1, "完全无关的内容")))
        assertTrue(index.score("销售额").isEmpty())
        assertEquals(1, index.topK("内容", 5).size)
        assertEquals(0, index.topK("内容", 0).size)
    }

    @Test
    fun `空索引与空查询安全`() {
        assertTrue(Bm25Index(emptyList()).score("任意").isEmpty())
        assertTrue(Bm25Index(listOf(Bm25Index.Doc("d", 1, "内容"))).score("").isEmpty())
    }
}
