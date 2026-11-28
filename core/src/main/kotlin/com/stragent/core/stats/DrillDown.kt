package com.stragent.core.stats

import com.stragent.core.csv.Csv
import com.stragent.core.csv.ColumnType
import com.stragent.core.dataset.Dataset
import com.stragent.core.dataset.looksLikeIdColumn
import com.stragent.core.dataset.summarize

/**
 * 图表下钻 —— drill-down.ts 移植。点一根柱子，看到这个分类的**明细与对比**。
 *
 * 三条纪律：a) 纯函数、确定性；b) 每个数字能回到原始行（rows 带原始行号）；
 * c) 不给平均数陷阱——单均必须与全体单均并列给（meanRatio）。
 */
data class DrillDownRow(
    /** 原始行号（数据行，不含表头，从 1 计）——"这个结论能回到原始行"的凭据 */
    val row: Int,
    /** 该行的全部单元格（按 dataset.columns 顺序） */
    val cells: List<String>,
)

/** 第二维度上的分布 */
data class DrillDownCross(
    val dimension: String,
    val items: List<DrillDownCrossItem>,
)

data class DrillDownCrossItem(
    val label: String,
    val value: Double,
    /** 占比的分母是**该组合计**，不是全体——用户看的是"华东内部谁占大头" */
    val share: Double,
)

data class DrillDown(
    val group: String,
    val groupColumn: String,
    val metric: String,
    /** 求和模式：该组合计；平均模式：该组单均（此时 share 无意义 → NaN） */
    val mode: String,
    val total: Double,
    val count: Int,
    val mean: Double,
    /** 占全体比例（0–1）；平均模式不适用 → NaN（UI 不印错的百分比） */
    val share: Double,
    val overallTotal: Double,
    val overallCount: Int,
    val overallMean: Double,
    /** 该组单均 ÷ 全体单均。>1 = 该组笔均更高 */
    val meanRatio: Double,
    val crosses: List<DrillDownCross>,
    val rows: List<DrillDownRow>,
    val evidenceRows: List<Int>,
)

/** 最多展开几个第二维度。两个是手机上能读的上限，第三个要滚动才看见 */
const val DRILL_CROSS_LIMIT = 2

/** 最多给多少行明细。再多就该让用户去看原始数据预览 */
const val DRILL_ROW_LIMIT = 20

/**
 * 下钻一个分类。groupIndex/valueIndex 越界或组不存在（一个值都没有）返回 null——
 * 空对象会让 UI 显示"合计 0"，那是把"没有这组"说成"这组是 0"。
 */
fun drillDown(
    dataset: Dataset,
    groupIndex: Int,
    valueIndex: Int,
    group: String,
    mode: String = "sum",
): DrillDown? {
    if (groupIndex < 0 || valueIndex < 0) return null
    if (groupIndex >= dataset.columns.size || valueIndex >= dataset.columns.size) return null

    val groupColumn = dataset.columns[groupIndex]
    val metric = dataset.columns[valueIndex]

    var groupTotal = 0.0
    var groupCount = 0
    var overallTotal = 0.0
    var overallCount = 0
    val hitRows = mutableListOf<Int>()
    /** 第二维度的列 index → 该组在此维度上的值合计 */
    val crossBuckets = mutableMapOf<Int, MutableMap<String, Double>>()

    val dateIndex = dataset.columns.indices.firstOrNull { index ->
        if (dataset.columnTypes[index] != ColumnType.TEXT) return@firstOrNull false
        dataset.rows.any { monthKey(it.getOrNull(index) ?: "") != null }
    }
    // 第二维度：除分组列 / 时间列 / 指标列外，基数 2–24 的文本列。
    // 上限比 pickDefaultColumns 宽——下钻要的是"这个组内部还能怎么拆"。ID 列排除。
    val stats = summarize(dataset)
    val crossIndexes = dataset.columns.withIndex()
        .filter { (index, name) ->
            if (index == groupIndex || index == dateIndex || index == valueIndex) return@filter false
            if (dataset.columnTypes[index] != ColumnType.TEXT) return@filter false
            if (looksLikeIdColumn(name)) return@filter false
            val distinct = stats.find { it.name == name }?.distinct ?: 0
            distinct in 2..24
        }
        .take(DRILL_CROSS_LIMIT)
        .map { it.index }

    for (r in dataset.rows.indices) {
        val value = Csv.parseNumber(dataset.rows[r].getOrNull(valueIndex) ?: "") ?: continue
        overallTotal += value
        overallCount += 1
        if ((dataset.rows[r].getOrNull(groupIndex) ?: "").trim() != group) continue
        groupTotal += value
        groupCount += 1
        if (hitRows.size < DRILL_ROW_LIMIT) hitRows.add(r + 1)
        for (index in crossIndexes) {
            val label = (dataset.rows[r].getOrNull(index) ?: "").trim()
            if (label.isEmpty()) continue
            val buckets = crossBuckets.getOrPut(index) { mutableMapOf() }
            buckets[label] = (buckets[label] ?: 0.0) + value
        }
    }

    if (groupCount == 0) return null

    val round2 = { value: Double -> Math.round(value * 100) / 100.0 }
    val groupMean = groupTotal / groupCount
    val overallMean = if (overallCount == 0) 0.0 else overallTotal / overallCount

    val crosses = crossIndexes.map { index ->
        val buckets = crossBuckets[index] ?: emptyMap()
        val sum = buckets.values.sum()
        DrillDownCross(
            dimension = dataset.columns[index],
            items = buckets.entries
                .map { (label, value) ->
                    DrillDownCrossItem(
                        label = label,
                        value = round2(value),
                        share = if (sum == 0.0) 0.0 else value / sum,
                    )
                }
                .sortedByDescending { it.value },
        )
    }

    return DrillDown(
        group = group,
        groupColumn = groupColumn,
        metric = metric,
        mode = mode,
        total = round2(groupTotal),
        count = groupCount,
        mean = round2(groupMean),
        // 平均模式下**占比无意义**：两组单均之比不是占比。给 NaN 让调用方判一次
        share = if (mode == "avg" || overallTotal == 0.0) Double.NaN else groupTotal / overallTotal,
        overallTotal = round2(overallTotal),
        overallCount = overallCount,
        overallMean = round2(overallMean),
        meanRatio = if (overallMean == 0.0) Double.NaN else groupMean / overallMean,
        crosses = crosses,
        rows = hitRows.map { row -> DrillDownRow(row, dataset.rows[row - 1].take(dataset.columns.size)) },
        evidenceRows = hitRows,
    )
}
