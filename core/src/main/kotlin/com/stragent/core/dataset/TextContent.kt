package com.stragent.core.dataset

/**
 * 数据集的文本部分（T-096）：段落与表格投影的载体，使一份内容中的「表格 + 正文」可以
 * 以任意比例并存——纯表格（text=null，既有形态零变化）、纯文本（表格部分为空表）、
 * 混合（rows=最大表 + text=正文段落与其余表投影）。
 *
 * 纪律（T-096）：
 * - 段落是文本部分的**唯一权威存所**：摘要/追问/综述都从这里取材料；
 * - [TextSegment.Kind.TABLE_PROJECTION] 是表格的文本投影（Markdown 序列化，行业「统一表格块」
 *   纪律：整体成块不切断）——它是给模型看的**语义投影**，不是统计口径（统计仍走 rows/表格链）；
 * - 每段上限 2000 字符（超长在导入时按句子边界切分），总量上限见 host 导入工具。
 */
data class TextContent(
    val segments: List<TextSegment>,
) {
    /** 总字符数（导入上限与自适应 targetChars 的依据） */
    val totalChars: Int get() = segments.sumOf { it.text.length }

    /** 段落数（不含投影） */
    val paragraphCount: Int get() = segments.count { it.kind == TextSegment.Kind.PARAGRAPH }
}

/**
 * 单个文本段。kind 区分正文段落与表格投影（投影段独立成块、不参与段落聚合）。
 */
data class TextSegment(
    /** 1 起始的段号（引用锚点的粒度：报告/摘要中的「段 N」即此值） */
    val index: Int,
    val text: String,
    val kind: Kind = Kind.PARAGRAPH,
) {
    enum class Kind { PARAGRAPH, TABLE_PROJECTION }
}
