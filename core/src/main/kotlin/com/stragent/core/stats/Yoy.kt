package com.stragent.core.stats

import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset
import com.stragent.core.dataset.looksLikeIdColumn

/**
 * 同比（YoY）—— src/shared/lib/yoy.ts 的 Kotlin 移植。
 *
 * 三条纪律：同比只在能比的时候才算（去年同月真实存在 + 两期样本量 ≥20）；
 * 同比不替代环比，是并排给；确定性（无随机、无时钟、无模型）。
 */
data class YoYPoint(
    val month: String,
    val lastYear: String?,
    val current: Double,
    val previous: Double?,
    val change: Double?,
    val mom: Double?,
    val previousMonth: Double?,
    val count: Int,
    val lastYearCount: Int?,
)

data class YoYReport(
    val points: List<YoYPoint>,
    val metric: String,
    val dateColumn: String,
    val comparable: Int,
    val months: Int,
    val seasonalMonths: List<String>,
)

private const val YEAR_MONTHS = 12
private const val MIN_ROWS = 20

fun yearOverYear(dataset: Dataset): YoYReport? {
    val dateIndex = findMonthIndex(dataset)
    val valueIndex = dataset.columns.indices.firstOrNull { index ->
        dataset.columnTypes[index] == com.stragent.core.csv.ColumnType.NUMBER && !looksLikeIdColumn(dataset.columns[index])
    } ?: -1
    if (dateIndex < 0 || valueIndex < 0) return null

    val metric = dataset.columns[valueIndex]
    val dateColumn = dataset.columns[dateIndex]

    // 一次遍历：月度合计 + 行数（TS 的 Map 保插入序，Kotlin LinkedHashMap 同）
    val totals = LinkedHashMap<String, DoubleArray>()
    for (row in dataset.rows) {
        val month = monthKey(row.getOrNull(dateIndex) ?: "") ?: continue
        val value = Csv.parseNumber(row.getOrNull(valueIndex) ?: "") ?: continue
        val bucket = totals.getOrPut(month) { DoubleArray(2) }
        bucket[0] += value
        bucket[1] += 1
    }

    val sorted = totals.keys.sorted()
    val points = mutableListOf<YoYPoint>()
    var comparable = 0

    sorted.forEachIndexed { index, month ->
        val bucket = totals[month]!!
        val lastYear = shiftMonth(month, -YEAR_MONTHS)
        val lastBucket = totals[lastYear]
        val canCompare = lastBucket != null && bucket[1].toInt() >= MIN_ROWS && lastBucket[1].toInt() >= MIN_ROWS

        val prev = if (index > 0) totals[sorted[index - 1]] else null
        val mom = if (prev != null && prev[0] != 0.0) round2((bucket[0] - prev[0]) / kotlin.math.abs(prev[0])) else null

        val change = if (canCompare && lastBucket[0] != 0.0) round2((bucket[0] - lastBucket[0]) / kotlin.math.abs(lastBucket[0])) else null

        if (canCompare) comparable += 1

        points.add(
            YoYPoint(
                month = month,
                lastYear = if (lastBucket == null) null else lastYear,
                current = round2(bucket[0]),
                previous = if (canCompare) round2(lastBucket[0]) else null,
                change = change,
                mom = mom,
                previousMonth = if (prev == null) null else round2(prev[0]),
                count = bucket[1].toInt(),
                lastYearCount = lastBucket?.get(1)?.toInt(),
            ),
        )
    }

    return YoYReport(
        points = points,
        metric = metric,
        dateColumn = dateColumn,
        comparable = comparable,
        months = sorted.size,
        seasonalMonths = points.filter { isSeasonal(it) }.map { it.month },
    )
}

private const val MOM_SIGNIFICANT = 0.3
private const val YOY_MILD = 0.1

/** 单点季节性判据：环比大幅(≥30%) + 同比温和(<10%) + 符号相反 */
fun isSeasonal(point: YoYPoint): Boolean {
    if (point.mom == null || point.change == null) return false
    return kotlin.math.abs(point.mom) >= MOM_SIGNIFICANT &&
        kotlin.math.abs(point.change) < YOY_MILD &&
        kotlin.math.sign(point.mom) != kotlin.math.sign(point.change)
}

/** 一个月的同比结论（措辞与 yoyPhrase 一致：±x.x%，负号用 U+2212） */
fun yoyPhrase(point: YoYPoint): String {
    if (point.change == null) return "没有可比的去年同月数据"
    fun pct(value: Double): String {
        val sign = if (value > 0) "+" else if (value < 0) "\u2212" else ""
        return "$sign${formatPct(kotlin.math.abs(value) * 100)}%"
    }
    val mom = if (point.mom == null) "环比不可比" else "环比 ${pct(point.mom)}"
    return "同比 ${pct(point.change)}（$mom）"
}

private fun formatPct(v: Double): String {
    // JS (Math.abs(value)*100).toFixed(1) 对齐
    val scaled = java.math.BigDecimal(v).setScale(1, java.math.RoundingMode.HALF_UP)
    return scaled.toPlainString()
}
