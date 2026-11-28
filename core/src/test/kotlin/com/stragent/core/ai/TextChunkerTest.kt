package com.stragent.core.ai

import com.stragent.core.dataset.TextContent
import com.stragent.core.dataset.TextSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-096 分块器测试：normalize/aggregate/sentenceGroups 三层结构的确定性断言。
 */
class TextChunkerTest {

    private fun para(vararg texts: String) = texts.map { TextSegment(0, it) }

    @Test
    fun `normalize 超长段按句子边界切分并保持段号连续`() {
        val long = "甲。".repeat(1500) // ~3000 字符，超 SEGMENT_MAX_CHARS=2000
        val segs = TextChunker.normalizeSegments(listOf(TextSegment(0, long)))
        assertTrue(segs.size >= 2, "超长段应被切成多段")
        assertTrue(segs.all { it.text.length <= 2000 })
        assertEquals((1..segs.size).toList(), segs.map { it.index }, "段号 1 起始连续")
    }

    private fun normalize(segments: List<TextSegment>) = TextChunker.normalizeSegments(segments)

    @Test
    fun `normalize 短段原样保留不重排`() {
        val segs = normalize(listOf(TextSegment(0, "第一段。"), TextSegment(0, "第二段。")))
        assertEquals(2, segs.size)
        assertEquals("第一段。", segs[0].text)
    }

    @Test
    fun `aggregate 段落聚合到块预算并产生 overlap`() {
        // 每段 ~600 字符 → 每块约 6 段
        val para = "字".repeat(600) + "。"
        val content = TextContent(Array(20) { TextSegment(0, para) }.toList())
        val chunks = TextChunker.aggregateChunks(content)
        assertTrue(chunks.size >= 3, "20 段×600 字应产生多块")
        assertTrue(chunks.all { it.text.length <= 6000 }, "块文本上限=预算+单段")
        // overlap：第 2 块的首个段号 == 第 1 块的最后一个段号
        assertEquals(chunks[0].segmentIndexes.last(), chunks[1].segmentIndexes.first())
    }

    @Test
    fun `aggregate 表格投影独立成块不参与聚合`() {
        val content = TextContent(
            para("段落一。", "段落二。") + TextSegment(0, "| 表 | 头 |\n|---|---|\n| 1 | 2 |", TextSegment.Kind.TABLE_PROJECTION),
        )
        val chunks = TextChunker.aggregateChunks(content)
        assertEquals(2, chunks.size)
        assertTrue(chunks[1].tableProjection)
        assertEquals(listOf(3), chunks[1].segmentIndexes, "投影段号连续编排")
        assertTrue(chunks[1].text.startsWith("| 表 |"), "投影块文本=投影段原文")
    }

    @Test
    fun `sentenceGroups 按句聚合到子层预算`() {
        val segs = para("甲乙丙丁。".repeat(100)) // 500 字
        val groups = TextChunker.sentenceGroups(TextContent(segs))
        assertTrue(groups.size >= 2)
        assertTrue(groups.all { it.segmentIndexes == listOf(1) })
    }

    @Test
    fun `splitSentences 保留句终符`() {
        val parts = TextChunker.splitSentences("第一句。第二句！第三句？")
        assertEquals(listOf("第一句。", "第二句！", "第三句？"), parts)
    }

    @Test
    fun `短群合并——表格交替文档的小碎片并回预算内（T-104）`() {
        // 模拟监测方案类材料：短段落群被 8 张表打断 → 初始大量小群
        val segs = buildList {
            repeat(8) { t ->
                add(TextSegment(0, "第${t + 1}节正文：某监测项目说明。"))
                add(TextSegment(0, "第${t + 1}节正文补充说明，含若干细节内容与数字 ${100 + t}。"))
                add(TextSegment(0, "| 表$t | a |\n|---|---|\n| 1 | 2 |", TextSegment.Kind.TABLE_PROJECTION))
            }
        }
        val chunks = TextChunker.aggregateChunks(TextContent(segs))
        // 合并后：段落群碎片应并入相邻段落块；表投影块仍独立
        val projCount = chunks.count { it.tableProjection }
        assertEquals(8, projCount, "投影块 8 个独立")
        val paraChunks = chunks.filter { !it.tableProjection }
        assertTrue(paraChunks.size < 8, "段落碎片应被合并（合并前为 8+ 小群，实际 ${paraChunks.size}）")
        assertTrue(paraChunks.all { it.text.length <= 4000 + 2000 }, "合并后仍守块预算上限")
        // 内容不丢失：全部段落号仍在某个块里
        val covered = chunks.flatMap { it.segmentIndexes }.toSet()
        assertEquals((1..24).toSet(), covered, "段号全覆盖不丢（16 正文 + 8 投影）")
    }
}
