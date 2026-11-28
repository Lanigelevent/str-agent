package com.stragent.core.stats

import com.stragent.core.csv.ColumnType
import com.stragent.core.csv.Csv
import com.stragent.core.csv.Csv.limitRows
import com.stragent.core.dataset.Dataset
import com.stragent.core.dataset.datasetFromParsed
import com.stragent.core.dataset.pickDefaultColumns
import com.stragent.core.csv.Csv.inferTypes
import java.io.File

/**
 * 复刻 analysis-contract-entry 的调用路径：pickDefaultColumns → findMonthIndex →
 * pickSecondDimension → 五个分析模块。列选择本身也是输出的一部分（纪律 a）。
 * 「没有结论」也进输出（null，不是跳过——纪律 b）。
 */
data class AnalysisResult(
    val columns: Columns,
    val yoy: YoYReport?,
    val groupYoY: GroupYoYReport?,
    val extremes: ResolvedExtremes?,
    val crossTab: CrossTabReport?,
    val groupTrend: GroupTrendReport?,
) {
    data class Columns(val category: Int, val value: Int, val x: Int, val month: Int, val second: Int)
}

fun datasetOf(name: String, sampleDir: String): Dataset {
    val text = File(sampleDir, "$name.csv").readText()
    val parsed = Csv.parseDelimited(text)
    val limited = limitRows(parsed)
    val types = inferTypes(limited.rows, limited.columns.size)
    return Dataset(
        name = "$name.csv",
        importedAt = 1,
        columns = limited.columns,
        rows = limited.rows,
        columnTypes = types,
        delimiter = limited.delimiter,
        truncated = limited.truncated,
        sourceRows = limited.totalRows,
    )
}

fun analyze(ds: Dataset): AnalysisResult {
    val defaults = pickDefaultColumns(ds)
    val month = findMonthIndex(ds)

    val g = if (defaults.category < 0) -1 else minOf(defaults.category, ds.columns.size - 1)
    val v = if (defaults.value < 0) -1 else minOf(defaults.value, ds.columns.size - 1)
    val second = if (g < 0) -1 else pickSecondDimension(ds, g)

    val usable = g >= 0 && v >= 0
    return AnalysisResult(
        columns = AnalysisResult.Columns(defaults.category, defaults.value, defaults.x, month, second),
        yoy = yearOverYear(ds),
        groupYoY = if (month < 0 || !usable) null else groupYearOverYear(ds, g, v, month),
        extremes = if (month < 0 || !usable) null else resolveExtremes(ds, g, month),
        crossTab = if (second < 0 || !usable) null else crossTabulateWithYield(ds, g, second, v),
        groupTrend = if (month < 0 || !usable) null else groupTrend(ds, g, v, month),
    )
}
