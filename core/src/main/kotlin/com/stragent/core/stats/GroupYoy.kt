package com.stragent.core.stats

import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset

/**
 * 分组同比 —— src/shared/lib/group-yoy.ts 的 Kotlin 移植。
 *
 * 纪律 a：按月份字符串对齐（shiftMonth 回推查 Map），不按数组位置。
 * 纪律 b：「拖累」= 落后整体 ≥5pp，不是「同比为负」。
 * 纪律 c：行数随结果出去，不在这里偷偷过滤；但 laggards 是结论，行数不足的组不进。
 */
data class GroupYoYPoint(
    val label: String,
    val current: Double,
    val previous: Double,
    val change: Double?,
    val share: Double,
    val rows: Int,
)

data class GroupYoYReport(
    val metric: String,
    val dimension: String,
    val period: String,
    val previousPeriod: String,
    val overall: Double?,
    val points: List<GroupYoYPoint>,
    val laggards: List<String>,
)

const val LAGGARD_GAP = 0.05
const val GROUP_YOY_LIMIT = 3
const val MIN_ROWS_FOR_CHANGE = 3

fun groupYearOverYear(
    dataset: Dataset,
    groupIndex: Int,
    valueIndex: Int,
    dateIndex: Int,
    limit: Int = GROUP_YOY_LIMIT,
): GroupYoYReport? {
    if (groupIndex < 0 || valueIndex < 0 || dateIndex < 0) return null
    if (groupIndex >= dataset.columns.size) return null
    if (valueIndex >= dataset.columns.size) return null
    if (dateIndex >= dataset.columns.size) return null

    val overallByMonth = LinkedHashMap<String, Double>()
    val groupByMonth = LinkedHashMap<String, LinkedHashMap<String, Double>>()
    val rowsByMonth = HashMap<String, Int>()
    val groupRowsByMonth = HashMap<String, HashMap<String, Int>>()

    for (row in dataset.rows) {
        val key = monthKey(row.getOrNull(dateIndex) ?: "") ?: continue
        val value = Csv.parseNumber(row.getOrNull(valueIndex) ?: "") ?: continue
        val label = (row.getOrNull(groupIndex) ?: "").trim()
        if (label.isEmpty()) continue

        overallByMonth[key] = (overallByMonth[key] ?: 0.0) + value
        rowsByMonth[key] = (rowsByMonth[key] ?: 0) + 1

        val byMonth = groupByMonth.getOrPut(label) { LinkedHashMap() }
        byMonth[key] = (byMonth[key] ?: 0.0) + value

        val groupRows = groupRowsByMonth.getOrPut(label) { HashMap() }
        groupRows[key] = (groupRows[key] ?: 0) + 1
    }

    // 最近一期 = 全体的最后一个月（不取各组最后月的最大值）
    var period = ""
    for (key in overallByMonth.keys) if (key > period) period = key
    if (period.isEmpty()) return null

    val previousPeriod = shiftMonth(period, -12)
    val previousTotal = overallByMonth[previousPeriod] ?: return null
    val currentTotal = overallByMonth[period] ?: 0.0
    if (previousTotal == 0.0) return null

    fun ratio(current: Double, previous: Double): Double? =
        if (previous == 0.0) null else round2((current - previous) / kotlin.math.abs(previous))

    val overall = ratio(currentTotal, previousTotal)

    val points = mutableListOf<GroupYoYPoint>()
    for ((label, byMonth) in groupByMonth) {
        val current = byMonth[period] ?: continue
        val previous = byMonth[previousPeriod] ?: continue
        points.add(
            GroupYoYPoint(
                label = label,
                current = current,
                previous = previous,
                change = ratio(current, previous),
                share = if (currentTotal == 0.0) 0.0 else current / currentTotal,
                rows = groupRowsByMonth[label]?.get(period) ?: 0,
            ),
        )
    }
    if (points.isEmpty()) return null

    points.sortByDescending { it.current }

    val overallChange = overall ?: 0.0
    val laggards = points
        .filter { it.change != null && it.rows >= MIN_ROWS_FOR_CHANGE && it.change - overallChange <= -LAGGARD_GAP }
        .map { it.label }

    return GroupYoYReport(
        metric = dataset.columns[valueIndex],
        dimension = dataset.columns[groupIndex],
        period = period,
        previousPeriod = previousPeriod,
        overall = overall,
        points = points.take(limit),
        laggards = laggards,
    )
}
