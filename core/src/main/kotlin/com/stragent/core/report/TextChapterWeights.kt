package com.stragent.core.report

/**
 * 文本路径章节骨架权重（T-105，STORM 视角驱动大纲先例）：程序定权重，不交模型。
 * standard 档与既有硬编码份额逐字节一致（overview 0.25 / points 0.40 / facts 0.20 / conclusion 0.20）。
 */
data class TextChapterWeights(
    val overview: Double,
    val points: Double,
    val facts: Double,
    val conclusion: Double,
)

fun textChapterWeights(typeId: String): TextChapterWeights = when (typeId) {
    "executive" -> TextChapterWeights(0.40, 0.30, 0.10, 0.20)
    "deepdive" -> TextChapterWeights(0.15, 0.55, 0.15, 0.15)
    "anomaly" -> TextChapterWeights(0.20, 0.35, 0.20, 0.25)
    "trend" -> TextChapterWeights(0.20, 0.45, 0.15, 0.20)
    "quality" -> TextChapterWeights(0.25, 0.35, 0.25, 0.15)
    else -> TextChapterWeights(0.25, 0.40, 0.20, 0.20)
}
