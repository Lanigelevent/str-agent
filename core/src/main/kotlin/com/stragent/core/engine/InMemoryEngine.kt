package com.stragent.core.engine

import com.stragent.core.dataset.AggregateItem
import com.stragent.core.dataset.ColumnStat
import com.stragent.core.dataset.Dataset
import com.stragent.core.dataset.aggregateBy as aggregateDatasetBy
import com.stragent.core.dataset.summarizeColumn

/**
 * tier-1 引擎：现有 Kotlin 实现的直接封装，行为零改动。
 *
 * 它是「报告路径」的默认引擎 —— 所有进入报告的数字都来自这里，逐个可回溯到内存原始行。
 * 新增引擎抽象时**不得**顺手改这里的口径，否则基线门禁会立刻报警。
 */
class InMemoryEngine(private val dataset: Dataset) : AnalysisEngine {
    override val id: String = "in-memory"
    override val tier: EngineTier = EngineTier.IN_MEMORY
    override val rowCount: Int get() = dataset.rows.size

    override fun aggregateBy(categoryIndex: Int, valueIndex: Int, mode: String, topN: Int): List<AggregateItem> =
        aggregateDatasetBy(dataset, categoryIndex, valueIndex, mode, topN)

    override fun profileColumn(columnIndex: Int): ColumnStat? =
        if (columnIndex in dataset.columns.indices) summarizeColumn(dataset, columnIndex) else null
}
