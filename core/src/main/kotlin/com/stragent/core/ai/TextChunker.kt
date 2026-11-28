package com.stragent.core.ai

import com.stragent.core.dataset.TextContent
import com.stragent.core.dataset.TextSegment

/** 检索子层单元：句组（段落内的句子聚合）或投影段整段（表格投影按行聚合） */
data class TextChunk(
    /** 0 起始的块序号（进度帧「第 k/n 块」用） */
    val index: Int,
    /** 块全文：段落带「N. 」段号前缀（模型引用锚点用） */
    val text: String,
    /** 覆盖的段号列表（1 起始） */
    val segmentIndexes: List<Int>,
    /** 是否表格投影块（独立成块、不参与段落聚合） */
    val tableProjection: Boolean,
)

/**
 * 长文本分块器（T-096）——纯函数，行业方案的落地层。
 *
 * 三层结构（父子两层 + map 块）：
 * - **段**（父层）：导入时的段落/表格投影，1 起始段号即引用锚点粒度；
 * - **句组**（子层，[sentenceGroups]）：~300 字符的句子聚合——检索评分粒度（小对大：
 *   检索按子块、上下文按父段回填，行业 small-to-big 纪律）；
 * - **map 块**（[aggregateChunks]）：~4000 字符的段落聚合——逐块要点提取（map-reduce 的
 *   map 单元），相邻块共享 1 段 overlap（防边界要点丢失）；
 * - **表格投影段独立成块**（行业「统一表格块」纪律：整体成块不切断）。
 *
 * 规模纪律：单段上限 [SEGMENT_MAX_CHARS]（超长在导入侧预切，此处兜底再切）；块预算
 * [CHUNK_CHARS]；块数上限由调用方（TextSummarizer）守。
 */
object TextChunker {

    /** map 块字符预算 */
    const val CHUNK_CHARS = 4000

    /** 单段字符上限（导入侧已预切，此处兜底） */
    const val SEGMENT_MAX_CHARS = 2000

    /** 句组字符预算（检索子层） */
    const val SENTENCE_GROUP_CHARS = 300

    /**
     * 段落规范化：超长段按句子边界再切（保证单段不超 [SEGMENT_MAX_CHARS]）。
     * 段号保持连续（1 起始重排）；投影段不切。
     */
    fun normalizeSegments(segments: List<TextSegment>): List<TextSegment> {
        val out = mutableListOf<TextSegment>()
        var next = 1
        for (seg in segments) {
            if (seg.kind == TextSegment.Kind.TABLE_PROJECTION || seg.text.length <= SEGMENT_MAX_CHARS) {
                out += seg.copy(index = next++)
                continue
            }
            // 超长段：按句子边界聚合切分（中文句终符优先；英文句点因小数歧义不作为切分符）
            val sentences = splitSentences(seg.text)
            var buf = StringBuilder()
            for (s in sentences) {
                if (buf.isNotEmpty() && buf.length + s.length > SEGMENT_MAX_CHARS) {
                    out += TextSegment(next++, buf.toString(), seg.kind)
                    buf = StringBuilder()
                }
                buf.append(s)
            }
            if (buf.isNotEmpty()) out += TextSegment(next++, buf.toString(), seg.kind)
        }
        return out
    }

    /**
     * map 块聚合：段落聚合到 [CHUNK_CHARS]（段落边界，不切断段落），相邻块共享 1 段 overlap；
     * 表格投影段独立成块。返回块列表（index 0 起始连续）。
     */
    fun aggregateChunks(content: TextContent): List<TextChunk> {
        val normalized = normalizeSegments(content.segments)
        val chunks = mutableListOf<TextChunk>()
        var buf = mutableListOf<TextSegment>()
        var bufChars = 0
        fun flush() {
            if (buf.isEmpty()) return
            chunks += TextChunk(
                index = chunks.size,
                text = buf.joinToString("\n\n") { "${it.index}. ${it.text}" },
                segmentIndexes = buf.map { it.index },
                tableProjection = false,
            )
            buf = mutableListOf()
            bufChars = 0
        }
        var lastParagraph: TextSegment? = null
        for (seg in normalized) {
            if (seg.kind == TextSegment.Kind.TABLE_PROJECTION) {
                flush()
                chunks += TextChunk(
                    index = chunks.size,
                    text = seg.text,
                    segmentIndexes = listOf(seg.index),
                    tableProjection = true,
                )
                lastParagraph = null
                continue
            }
            if (buf.isNotEmpty() && bufChars + seg.text.length > CHUNK_CHARS) {
                flush()
                // overlap：新块以上一块最后一段开头（防边界要点丢失，行业 small-to-big 纪律）
                lastParagraph?.let {
                    buf = mutableListOf(it)
                    bufChars = it.text.length
                }
            }
            buf.add(seg)
            bufChars += seg.text.length
            lastParagraph = seg
        }
        flush()
        return mergeFragments(chunks, normalized.associateBy { it.index })
    }

    /**
     * 短群合并（T-104）：表格密集文档中，每张表投影 flush 会把段落群打断成小群
     * （实测：18 表文档聚合出 84 块，大量 <1K 字碎片）——相邻**非投影**块若合计
     * 不超 [CHUNK_CHARS] 则合并（去重 overlap 段），表投影块仍独立不并。
     * 合并按段号并集重建文本（overlap 段不重复出现），index 重排连续。
     */
    internal fun mergeFragments(
        chunks: List<TextChunk>,
        segById: Map<Int, TextSegment>,
    ): List<TextChunk> {
        if (chunks.size <= 1) return chunks
        // 段落块按序贪心合并（预算内），投影块原位独立；合并块排序键 = 成员最小原序
        data class Item(val pos: Int, val chunk: TextChunk)
        val paras = mutableListOf<Item>()
        val proj = mutableListOf<Item>()
        chunks.forEachIndexed { i, c ->
            (if (c.tableProjection) proj else paras) += Item(i, c)
        }
        val mergedParas = mutableListOf<Item>()
        var bufPos = Int.MAX_VALUE
        var bufSegs = LinkedHashSet<Int>()
        fun flushPara() {
            if (bufSegs.isEmpty()) return
            val union = bufSegs.toList()
            val text = union.joinToString("\n\n") { "$it. ${segById[it]!!.text}" }
            mergedParas += Item(bufPos, TextChunk(0, text, union, false))
            bufPos = Int.MAX_VALUE
            bufSegs = LinkedHashSet()
        }
        for ((pos, c) in paras) {
            val segs = LinkedHashSet(c.segmentIndexes)
            val chars = segs.sumOf { (segById[it]!!.text.length + 6) }
            if (bufSegs.isNotEmpty() && bufSegs.sumOf { (segById[it]!!.text.length + 6) } + chars > CHUNK_CHARS) flushPara()
            if (pos < bufPos) bufPos = pos
            bufSegs.addAll(segs)
        }
        flushPara()
        // 按文档序交错回投影块，index 重排连续
        val combined = (mergedParas + proj).sortedBy { it.pos }.map { it.chunk }
        return combined.mapIndexed { i, c -> c.copy(index = i) }
    }
    /**
     * 检索子层：句组切分（段落按句子聚合到 [SENTENCE_GROUP_CHARS]；投影段按行聚合）。
     * 返回子块列表（index 0 起始连续；segmentIndexes=所在段号）。
     */
    fun sentenceGroups(content: TextContent): List<TextChunk> {
        val normalized = normalizeSegments(content.segments)
        val groups = mutableListOf<TextChunk>()
        var idx = 0
        for (seg in normalized) {
            val units: List<String> = if (seg.kind == TextSegment.Kind.TABLE_PROJECTION) {
                seg.text.split('\n').filter { it.isNotBlank() }
            } else {
                splitSentences(seg.text)
            }
            var buf = StringBuilder()
            fun flushGroup() {
                if (buf.isEmpty()) return
                groups += TextChunk(idx++, buf.toString(), listOf(seg.index), seg.kind == TextSegment.Kind.TABLE_PROJECTION)
                buf = StringBuilder()
            }
            for (u in units) {
                if (buf.isNotEmpty() && buf.length + u.length > SENTENCE_GROUP_CHARS) flushGroup()
                if (buf.isNotEmpty()) buf.append(' ')
                buf.append(u)
            }
            flushGroup()
        }
        return groups
    }

    /** 句子切分：中文句终符（。！？；）后可跟空白；保留终止符。英文句点因小数歧义不作为切分符 */
    internal fun splitSentences(text: String): List<String> {
        val out = mutableListOf<String>()
        var buf = StringBuilder()
        for (ch in text) {
            buf.append(ch)
            if (ch == '。' || ch == '！' || ch == '？' || ch == '；') {
                out += buf.toString().trim()
                buf = StringBuilder()
            }
        }
        if (buf.isNotEmpty()) out += buf.toString().trim()
        return out.filter { it.isNotEmpty() }
    }
}
