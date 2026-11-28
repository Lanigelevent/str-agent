package com.stragent.core.stats

import com.stragent.core.csv.Csv
import com.stragent.core.csv.ColumnType
import com.stragent.core.dataset.Dataset

/**
 * 场景模拟（What-If）—— D10 阶段 5 第二落地。
 *
 * 对数据集指定列施加变换（乘/加/设值），重算关键统计量，输出 before/after 对比。
 * 支持按分组列拆分对比。
 *
 * **纯 Kotlin 零依赖、确定性**——与溯源清单纪律兼容：同一份数据 + 同一参数，
 * 结果必然一致，可重放复核。
 *
 * 诚实边界（不冒称）：
 *  - 只做单列变换（多列联动需要表达式引擎，走 stragent_compute）
 *  - 不做条件变换（"只对华东地区加价"需要 filter+transform，走 stragent_compute_python）
 *  - 统计量是 sum/avg/min/max/count，不做回归/预测（走 stragent_forecast）
 *  - 变换后的数据不落盘、不覆盖原数据集——只是"如果...会怎样"的推算
 */
data class WhatIfResult(
    val column: String,
    val operation: String,
    val factor: Double,
    val groupBy: String?,
    val before: List<GroupStat>,
    val after: List<GroupStat>,
    val delta: List<GroupDelta>,
)

data class GroupStat(
    val group: String,
    val count: Int,
    val sum: Double,
    val avg: Double,
    val min: Double,
    val max: Double,
)

data class GroupDelta(
    val group: String,
    val sumDelta: Double,
    val sumDeltaPct: Double,
    val avgDelta: Double,
    val avgDeltaPct: Double,
)

enum class WhatIfOperation(val id: String, val label: String) {
    MULTIPLY("multiply", "乘"),
    ADD("add", "加"),
    SET("set", "设为");

    companion object {
        fun fromId(id: String): WhatIfOperation? =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/**
 * 执行场景模拟。
 *
 * @param dataset 数据集
 * @param columnIndex 要变换的列索引
 * @param operation 变换操作
 * @param factor 变换因子（multiply: 乘数；add: 加数；set: 设值）
 * @param groupByIndex 分组列索引（-1 = 不分组，只出全局统计）
 * @return before/after 对比结果
 */
fun whatIf(
    dataset: Dataset,
    columnIndex: Int,
    operation: WhatIfOperation,
    factor: Double,
    groupByIndex: Int = -1,
): WhatIfResult {
    val column = dataset.columns[columnIndex]

    // 提取 (groupKey, originalValue) 对
    data class Row(val group: String, val original: Double)
    val rows = mutableListOf<Row>()
    for (row in dataset.rows) {
        val raw = row.getOrNull(columnIndex) ?: continue
        val num = Csv.parseNumber(raw) ?: continue
        if (!num.isFinite()) continue
        val group = if (groupByIndex >= 0) {
            row.getOrNull(groupByIndex)?.takeIf { it.isNotBlank() } ?: "(空白)"
        } else {
            "(全部)"
        }
        rows.add(Row(group, num))
    }

    // 按分组聚合 before
    val beforeGroups: Map<String, List<Row>> = rows.groupBy { it.group }
    val before: List<GroupStat> = beforeGroups.map { (group, items) ->
        val values = items.map { it.original }
        computeStat(group, values)
    }.sortedBy { it.group }

    // 变换后：对每行的 original 施加变换，保持原分组
    val afterGroups: Map<String, List<Double>> = beforeGroups.mapValues { (_, items) ->
        items.map { row ->
            when (operation) {
                WhatIfOperation.MULTIPLY -> row.original * factor
                WhatIfOperation.ADD -> row.original + factor
                WhatIfOperation.SET -> factor
            }
        }
    }

    val after: List<GroupStat> = afterGroups.map { (group, values) ->
        computeStat(group, values)
    }.sortedBy { it.group }

    // delta
    val delta: List<GroupDelta> = before.zip(after).map { (b, a) ->
        GroupDelta(
            group = b.group,
            sumDelta = a.sum - b.sum,
            sumDeltaPct = if (b.sum != 0.0) (a.sum - b.sum) / b.sum * 100.0 else Double.NaN,
            avgDelta = a.avg - b.avg,
            avgDeltaPct = if (b.avg != 0.0) (a.avg - b.avg) / b.avg * 100.0 else Double.NaN,
        )
    }

    return WhatIfResult(
        column = column,
        operation = operation.id,
        factor = factor,
        groupBy = if (groupByIndex >= 0) dataset.columns[groupByIndex] else null,
        before = before,
        after = after,
        delta = delta,
    )
}

/** 找变换目标列索引（与 ForecastTool 的逻辑一致但独立，避免循环依赖） */
fun findWhatIfColumn(dataset: Dataset, preferred: String? = null): Int {
    if (preferred != null) {
        val idx = dataset.columns.indexOfFirst { it.equals(preferred, ignoreCase = true) }
        if (idx >= 0 && dataset.columnTypes[idx] == ColumnType.NUMBER) return idx
        // 指定了列名但不是数值列 → 返回 -2 表示"找到了但类型不对"
        if (idx >= 0) return -2
        // 指定了列名但不存在 → 返回 -3
        return -3
    }
    return dataset.columns.indices.firstOrNull { dataset.columnTypes[it] == ColumnType.NUMBER } ?: -1
}

/** 找分组列索引 */
fun findGroupByColumn(dataset: Dataset, preferred: String): Int {
    val idx = dataset.columns.indexOfFirst { it.equals(preferred, ignoreCase = true) }
    return idx // -1 = 不存在
}

private fun computeStat(group: String, values: List<Double>): GroupStat =
    GroupStat(
        group = group,
        count = values.size,
        sum = Math.round(values.sum() * 10000) / 10000.0,
        avg = Math.round((values.sum() / values.size) * 10000) / 10000.0,
        min = values.minOrNull() ?: 0.0,
        max = values.maxOrNull() ?: 0.0,
    )
