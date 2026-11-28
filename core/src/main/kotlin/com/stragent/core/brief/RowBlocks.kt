package com.stragent.core.brief

import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset

/**
 * 多块拼接检测 —— dataset.ts 的 detectRowBlocks 移植。
 *
 * 判据（刻意收紧，宁可不报也不误报）：
 *  ① 标签列有非空行；② 存在标签为空但相邻列是「像对象名」的文本的行（≥3 行）——
 *  「对象名写到隔壁列」是拼接表最稳定的特征。
 */
data class RowBlocks(val labelColumn: String, val labeled: Int, val shifted: Int)

private val DATE_COLUMN_NAME_LOCAL = Regex("^(\\d{4})[-/.](\\d{1,2})(?:[-/.](\\d{1,2}))?$")

fun detectRowBlocks(dataset: Dataset, labelIndex: Int): RowBlocks? {
    if (labelIndex < 0 || labelIndex + 1 >= dataset.columns.size) return null
    var labeled = 0
    var shifted = 0
    for (row in dataset.rows) {
        val label = (row.getOrNull(labelIndex) ?: "").trim()
        if (label.isNotEmpty()) {
            labeled += 1
            continue
        }
        val next = (row.getOrNull(labelIndex + 1) ?: "").trim()
        if (next.isEmpty()) continue
        if (Csv.parseNumber(next) != null) continue
        if (DATE_COLUMN_NAME_LOCAL.matches(next)) continue
        if (next == dataset.columns[labelIndex + 1]) continue
        val hasData = row.withIndex().any { (index, cell) -> index != labelIndex + 1 && (cell ?: "").trim().isNotEmpty() }
        if (hasData) shifted += 1
    }
    if (labeled == 0 || shifted < 3) return null
    return RowBlocks(dataset.columns[labelIndex], labeled, shifted)
}
