package com.stragent.core.stats

import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset
import com.stragent.core.dataset.looksLikeIdColumn

/**
 * 极值定位 —— src/shared/lib/dataset.ts 的 locateExtremes / locateWideExtremes /
 * computeExtremes 与 src/shared/lib/extremes.ts 的 resolveExtremes 移植。
 *
 * 三级口径优先级：a) fullStats.extremes 优先；b) 未截断现场算；c) 截断且无全量 → null。
 */
data class ExtremeLocation(
    val value: Double,
    val row: Int,
    val group: String? = null,
    val date: String? = null,
    val column: String? = null,
)

data class ExtremePair(
    val column: String,
    val max: ExtremeLocation? = null,
    val min: ExtremeLocation? = null,
    val top: List<ExtremeLocation>? = null,
)

data class ResolvedExtremes(
    val pairs: List<ExtremePair>,
    val source: String,
    val scopeRows: Int,
    val sourceRows: Int,
)

private val DATE_COLUMN_NAME = Regex("^(\\d{4})[-/.](\\d{1,2})(?:[-/.](\\d{1,2}))?$")
private const val MAX_STORED_EXTREMES = 6

fun locateExtremes(dataset: Dataset, valueIndex: Int, groupIndex: Int = -1, dateIndex: Int = -1): ExtremePair {
    var max: ExtremeLocation? = null
    var min: ExtremeLocation? = null
    for (row in dataset.rows.indices) {
        val value = Csv.parseNumber(dataset.rows[row].getOrNull(valueIndex) ?: "") ?: continue
        fun at(): ExtremeLocation = ExtremeLocation(
            value = value,
            row = row + 1,
            group = if (groupIndex >= 0 && groupIndex != valueIndex) (dataset.rows[row].getOrNull(groupIndex) ?: "").trim().ifEmpty { null } else null,
            date = if (dateIndex >= 0 && dateIndex != valueIndex) (dataset.rows[row].getOrNull(dateIndex) ?: "").trim().ifEmpty { null } else null,
        )
        if (max == null || value > max.value) max = at()
        if (min == null || value < min.value) min = at()
    }
    return ExtremePair(column = dataset.columns[valueIndex], max = max, min = min)
}

/** 转置宽表的极值定位：列名是观测日、每行是一个测点/对象 */
fun locateWideExtremes(dataset: Dataset, labelIndex: Int, topN: Int = 3): ExtremePair? {
    val dateCols = mutableListOf<Int>()
    for (index in dataset.columns.indices) {
        val match = DATE_COLUMN_NAME.matchEntire(dataset.columns[index].trim()) ?: continue
        val month = match.groupValues[2].toInt()
        if (month < 1 || month > 12) continue
        dateCols.add(index)
    }
    if (dateCols.size < 12) return null

    val found = mutableListOf<ExtremeLocation>()
    for (row in dataset.rows.indices) {
        val label = if (labelIndex >= 0) (dataset.rows[row].getOrNull(labelIndex) ?: "").trim() else ""
        if (labelIndex >= 0 && label.isEmpty()) continue
        for (col in dateCols) {
            val value = Csv.parseNumber(dataset.rows[row].getOrNull(col) ?: "") ?: continue
            found.add(
                ExtremeLocation(
                    value = value,
                    row = row + 1,
                    group = if (label.isEmpty()) null else label,
                    column = dataset.columns[col].trim(),
                ),
            )
        }
    }
    if (found.isEmpty()) return null

    val ranked = found.sortedByDescending { it.value }
    return ExtremePair(
        column = "全部 ${dateCols.size} 个观测列",
        max = ranked.first(),
        min = ranked.last(),
        top = ranked.take(maxOf(1, topN)),
    )
}

fun computeExtremes(dataset: Dataset, groupIndex: Int, dateIndex: Int): List<ExtremePair> {
    val wide = locateWideExtremes(dataset, if (groupIndex >= 0) groupIndex else -1)
    if (wide != null) return listOf(wide)

    val picked = dataset.columns
        .mapIndexed { index, name -> name to index }
        .filter { (name, index) ->
            dataset.columnTypes[index] == com.stragent.core.csv.ColumnType.NUMBER &&
                !looksLikeIdColumn(name) &&
                index != groupIndex &&
                index != dateIndex
        }
        .take(MAX_STORED_EXTREMES)
    return picked.map { (name, index) -> locateExtremes(dataset, index, groupIndex, dateIndex) }
}

/**
 * 取极值定位。三级口径：a) fullStats 优先；b) 未截断现场算；c) 截断且无全量 → null。
 * （当前 Dataset 模型没有 fullStats 字段——移植的是 b) + c) 路径；a) 等全量统计
 * 移植后接入。判据与 hasDistortedStats 同一：sourceRows > rows.length = 截断过。）
 */
fun resolveExtremes(dataset: Dataset, groupIndex: Int, dateIndex: Int): ResolvedExtremes? {
    val sourceRows = dataset.sourceRows
    if (sourceRows > dataset.rows.size) return null

    val pairs = computeExtremes(dataset, groupIndex, dateIndex)
    if (pairs.isEmpty()) return null
    return ResolvedExtremes(
        pairs = pairs,
        source = "live",
        scopeRows = dataset.rows.size,
        sourceRows = sourceRows,
    )
}

/** 趋势序列：X 列（重复值合并取均值）+ 数值列 Y */
data class SeriesPoint(val x: String, val y: Double)

fun buildSeries(dataset: Dataset, xIndex: Int, yIndex: Int): List<SeriesPoint> {
    val buckets = LinkedHashMap<String, DoubleArray>()
    for (row in dataset.rows) {
        val x = (row.getOrNull(xIndex) ?: "").trim().ifEmpty { "（空）" }
        val value = Csv.parseNumber(row.getOrNull(yIndex) ?: "") ?: continue
        val bucket = buckets.getOrPut(x) { doubleArrayOf(0.0, 0.0) }
        bucket[0] += value
        bucket[1] += 1
    }
    return buckets.entries.map { (x, bucket) -> SeriesPoint(x, bucket[0] / bucket[1]) }
}
