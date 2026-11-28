package com.stragent.core.stats

import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset

/**
 * 分组月度走势 —— src/shared/lib/group-trend.ts 的 Kotlin 移植。
 *
 * 纪律 a：基线是滚动 12 期（不是固定中位数——有增长的数据上会误判）。
 * 纪律 b：按日历月回推取基线（不按数组位置）。
 * 纪律 c：只报连续段（≥MIN_RUN 期）、只报「低于」那一侧——不报高于，是实测教训
 *         （dip 之后的基线被低值拉低，恢复期全变成伪「超常」段）。
 */
data class GroupTrendRun(
    val start: String,
    val end: String,
    val months: Int,
    val direction: String,
    val deviation: Double,
)

data class GroupTrendPoint(
    val label: String,
    val periods: Int,
    val lastMonth: String?,
    val lastValue: Double?,
    val deviation: Double?,
    val run: GroupTrendRun?,
)

data class GroupTrendReport(
    val metric: String,
    val dimension: String,
    val window: Int,
    val threshold: Double,
    val points: List<GroupTrendPoint>,
)

const val BASELINE_WINDOW = 12
const val MIN_BASELINE_MONTHS = 6
const val RUN_THRESHOLD = 0.3
const val MIN_RUN = 3
const val GROUP_TREND_LIMIT = 3

fun groupTrend(
    dataset: Dataset,
    groupIndex: Int,
    valueIndex: Int,
    dateIndex: Int,
    limit: Int = GROUP_TREND_LIMIT,
): GroupTrendReport? {
    if (groupIndex < 0 || valueIndex < 0 || dateIndex < 0) return null
    if (groupIndex >= dataset.columns.size) return null
    if (valueIndex >= dataset.columns.size) return null
    if (dateIndex >= dataset.columns.size) return null

    // 一次扫描：每个组一份「月份 → 合计」
    val byGroup = LinkedHashMap<String, LinkedHashMap<String, Double>>()
    for (row in dataset.rows) {
        val month = monthKey(row.getOrNull(dateIndex) ?: "") ?: continue
        val value = Csv.parseNumber(row.getOrNull(valueIndex) ?: "") ?: continue
        val label = (row.getOrNull(groupIndex) ?: "").trim()
        if (label.isEmpty()) continue
        val byMonth = byGroup.getOrPut(label) { LinkedHashMap() }
        byMonth[month] = (byMonth[month] ?: 0.0) + value
    }
    if (byGroup.isEmpty()) return null

    data class Deviation(val month: String, val value: Double, val base: Double, val dev: Double)

    val points = mutableListOf<GroupTrendPoint>()
    for ((label, byMonth) in byGroup) {
        val months = byMonth.keys.sorted()
        val periods = months.size
        val deviations = mutableListOf<Deviation>()
        for (month in months) {
            var sum = 0.0
            var covered = 0
            for (back in 1..BASELINE_WINDOW) {
                val key = shiftMonth(month, -back)
                val value = byMonth[key] ?: continue
                sum += value
                covered += 1
            }
            if (covered < MIN_BASELINE_MONTHS) continue
            val base = sum / covered
            if (base == 0.0) continue
            deviations.add(Deviation(month, byMonth[month]!!, base, (byMonth[month]!! - base) / kotlin.math.abs(base)))
        }
        if (deviations.isEmpty()) {
            points.add(GroupTrendPoint(label = label, periods = periods, lastMonth = null, lastValue = null, deviation = null, run = null))
            continue
        }

        // 最长的一段低于基线的连续偏离（只报 below 侧）
        var run: GroupTrendRun? = null
        var runStart = -1
        fun flush(endIndex: Int) {
            if (runStart < 0) return
            val length = endIndex - runStart + 1
            if (length < MIN_RUN) return
            val slice = deviations.slice(runStart..endIndex)
            val deviation = slice.sumOf { kotlin.math.abs(it.dev) } / slice.size
            if (run == null || length > run!!.months) {
                run = GroupTrendRun(
                    start = deviations[runStart].month,
                    end = deviations[endIndex].month,
                    months = length,
                    direction = "below",
                    deviation = deviation,
                )
            }
        }
        deviations.forEachIndexed { index, item ->
            if (item.dev > -RUN_THRESHOLD) {
                flush(index - 1)
                runStart = -1
                return@forEachIndexed
            }
            if (runStart < 0) runStart = index
        }
        flush(deviations.size - 1)

        val last = deviations.last()
        points.add(
            GroupTrendPoint(
                label = label,
                periods = periods,
                lastMonth = last.month,
                lastValue = last.value,
                deviation = Math.round(last.dev * 100) / 100.0,
                run = run,
            ),
        )
    }
    if (points.isEmpty()) return null

    points.sortByDescending { it.lastValue ?: 0.0 }

    return GroupTrendReport(
        metric = dataset.columns[valueIndex],
        dimension = dataset.columns[groupIndex],
        window = BASELINE_WINDOW,
        threshold = RUN_THRESHOLD,
        points = points.take(limit),
    )
}
