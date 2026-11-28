package com.stragent.core.stats

import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset
import com.stragent.core.dataset.looksLikeIdColumn

/**
 * 维度交叉 + 行均 —— src/shared/lib/cross-tab.ts 的 Kotlin 移植。
 *
 * 纪律 a：叫「行均」不叫「客单价」——count 是行数不是订单数。
 * 纪律 b：行数太少的格子不进「行均最高」，但仍在格子里出现。
 * 纪律 c：一条格子都没有就不渲染（返回 null）。
 */
data class CrossCellWithYield(
    val a: String,
    val b: String,
    val value: Double,
    val count: Int,
    val yield: Double,
)

data class CrossTabReport(
    val aColumn: String,
    val bColumn: String,
    val metric: String,
    val cells: List<CrossCellWithYield>,
    val baseline: Double,
    val busiest: CrossCellWithYield?,
    val bestYield: CrossCellWithYield?,
    val rows: Int,
)

const val CROSS_TOP_N = 6
const val MIN_ROWS_FOR_YIELD = 5
private const val SECOND_DIM_MIN = 2
private const val SECOND_DIM_MAX = 24

/** 挑第二个维度：主分组列之外的第一个基数合适的文本列 */
fun pickSecondDimension(dataset: Dataset, aIndex: Int): Int {
    for (index in dataset.columns.indices) {
        if (index == aIndex) continue
        if (dataset.columnTypes[index] != com.stragent.core.csv.ColumnType.TEXT) continue
        if (looksLikeIdColumn(dataset.columns[index])) continue
        val distinct = HashSet<String>()
        for (row in dataset.rows) {
            val label = (row.getOrNull(index) ?: "").trim()
            if (label.isNotEmpty()) distinct.add(label)
        }
        if (distinct.size >= SECOND_DIM_MIN && distinct.size <= SECOND_DIM_MAX) return index
    }
    return -1
}

/**
 * 维度交叉 + 行均。格子用嵌套 Map 查找 + 一个数组保序——
 * 保序数组是必须的：cells 按 count 降序排，count 相同时相对次序由
 * first-encounter 顺序决定，换顺序就会改输出（analysis-baseline 会红）。
 */
fun crossTabulateWithYield(
    dataset: Dataset,
    aIndex: Int,
    bIndex: Int,
    valueIndex: Int,
    topN: Int = CROSS_TOP_N,
): CrossTabReport? {
    if (aIndex < 0 || bIndex < 0 || valueIndex < 0) return null
    if (aIndex >= dataset.columns.size) return null
    if (bIndex >= dataset.columns.size) return null
    if (valueIndex >= dataset.columns.size) return null
    if (aIndex == bIndex || aIndex == valueIndex || bIndex == valueIndex) return null

    // Cell: [total, count] 按插入序保序
    val byA = LinkedHashMap<String, LinkedHashMap<String, DoubleArray>>()
    val order = mutableListOf<Pair<String, String>>()
    var totalValue = 0.0
    var totalRows = 0

    for (row in dataset.rows) {
        val value = Csv.parseNumber(row.getOrNull(valueIndex) ?: "") ?: continue
        val a = (row.getOrNull(aIndex) ?: "").trim().ifEmpty { "（空）" }
        val b = (row.getOrNull(bIndex) ?: "").trim().ifEmpty { "（空）" }
        val rowCells = byA.getOrPut(a) { LinkedHashMap() }
        if (rowCells[b] == null) {
            rowCells[b] = doubleArrayOf(0.0, 0.0)
            order.add(a to b)
        }
        rowCells[b]!![0] += value
        rowCells[b]!![1] += 1
        totalValue += value
        totalRows += 1
    }
    if (order.isEmpty()) return null

    val all = order.map { (a, b) ->
        val cell = byA[a]!![b]!!
        CrossCellWithYield(a = a, b = b, value = cell[0], count = cell[1].toInt(), yield = if (cell[1] == 0.0) 0.0 else cell[0] / cell[1])
    }

    val byCount = all.sortedByDescending { it.count }
    val credible = all.filter { it.count >= MIN_ROWS_FOR_YIELD }
    val bestYield = credible.reduceOrNull { best, cell -> if (cell.yield > best.yield) cell else best }

    return CrossTabReport(
        aColumn = dataset.columns[aIndex],
        bColumn = dataset.columns[bIndex],
        metric = dataset.columns[valueIndex],
        cells = byCount.take(topN),
        baseline = if (totalRows == 0) 0.0 else totalValue / totalRows,
        busiest = byCount.firstOrNull(),
        bestYield = bestYield,
        rows = totalRows,
    )
}
