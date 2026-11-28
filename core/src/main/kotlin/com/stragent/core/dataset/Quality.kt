package com.stragent.core.dataset

import com.stragent.core.csv.ColumnType
import com.stragent.core.csv.Csv
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 数据质量 / IQR 异常检测 / 皮尔逊相关 —— dataset.ts 对应段落的移植。
 *
 * 口径纪律（来自 dataset.ts 注释）：
 *  - 判重用 \u0001 拼接（避免 "a,b"+"c" 与 "a"+"b,c" 撞键）
 *  - 形态分类：千分位/百分数/年月日/月日年/普通数字（小数整数同形态）/文本
 *  - IQR 检测：样本 <4 或 IQR=0 → 不出结论（分位数无判别力）
 *  - pearson 分开开方（1e150 量级防溢出），r 夹到 [-1,1]
 */
data class ConsistencyForm(val shape: String, val count: Int)

data class ConsistencyIssue(val column: String, val forms: List<ConsistencyForm>)

data class WorstColumn(val column: String, val emptyRate: Double)

data class QualityReport(
    val rows: Int,
    val columns: Int,
    val duplicateRows: Int,
    val emptyRate: Double,
    val worstColumns: List<WorstColumn>,
    val numericColumns: Int,
    val textColumns: Int,
    val consistency: List<ConsistencyIssue>,
    val constantColumns: List<String>,
)

/**
 * 形态分类的五个正则——**预编译单例**（T-142）。
 *
 * `shapeOf` 是**每格**调用的（一致性画像逐格判形态），写在函数体内会
 * 每次调用都重新编译 `Pattern`。`Pattern` 不可变且线程安全，提为单例安全。
 * 仓内先例：`core/verify/VerifyCore.kt:17-38`。
 */
private val SHAPE_THOUSAND = Regex("^-?\\d{1,3}(,\\d{3})+(\\.\\d+)?$")
private val SHAPE_PERCENT = Regex("^-?\\d+(\\.\\d+)?%$")
private val SHAPE_YMD = Regex("^\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}")
private val SHAPE_MDY = Regex("^\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{4}")
private val SHAPE_NUMBER = Regex("^-?\\d+(\\.\\d+)?$")

private fun shapeOf(raw: String): String {
    val text = raw.trim()
    if (SHAPE_THOUSAND.matches(text)) return "千分位数字"
    if (SHAPE_PERCENT.matches(text)) return "百分数"
    if (SHAPE_YMD.containsMatchIn(text)) return "年月日"
    if (SHAPE_MDY.containsMatchIn(text)) return "月日年"
    if (SHAPE_NUMBER.matches(text)) return "普通数字"
    return "文本"
}

fun analyzeQuality(dataset: Dataset): QualityReport {
    val columnEmpty = IntArray(dataset.columns.size)
    for (row in dataset.rows) {
        for (index in dataset.columns.indices) {
            if ((row.getOrNull(index) ?: "").trim().isEmpty()) columnEmpty[index] += 1
        }
    }
    val seen = HashSet<String>()
    var duplicateRows = 0
    for (row in dataset.rows) {
        val key = dataset.columns.indices.joinToString("\u0001") { (row.getOrNull(it) ?: "").trim() }
        if (!seen.add(key)) duplicateRows += 1
    }
    val totalCells = dataset.rows.size * dataset.columns.size
    val emptyCells = columnEmpty.sum()
    val numericColumns = dataset.columnTypes.count { it == ColumnType.NUMBER }

    val consistency = mutableListOf<ConsistencyIssue>()
    val constantColumns = mutableListOf<String>()
    for (index in dataset.columns.indices) {
        val shapes = LinkedHashMap<String, Int>()
        val values = HashSet<String>()
        for (row in dataset.rows) {
            val raw = (row.getOrNull(index) ?: "").trim()
            if (raw.isEmpty()) continue
            val shape = shapeOf(raw)
            shapes[shape] = (shapes[shape] ?: 0) + 1
            values.add(raw)
        }
        if (shapes.size > 1) {
            consistency.add(
                ConsistencyIssue(
                    column = dataset.columns[index],
                    forms = shapes.entries.sortedByDescending { it.value }.map { ConsistencyForm(it.key, it.value) },
                ),
            )
        }
        if (values.size == 1 && dataset.rows.size > 1) constantColumns.add(dataset.columns[index])
    }

    return QualityReport(
        rows = dataset.rows.size,
        columns = dataset.columns.size,
        duplicateRows = duplicateRows,
        emptyRate = if (totalCells == 0) 0.0 else emptyCells.toDouble() / totalCells,
        worstColumns = columnEmpty
            .mapIndexed { index, count -> WorstColumn(dataset.columns[index], if (dataset.rows.isEmpty()) 0.0 else count.toDouble() / dataset.rows.size) }
            .filter { it.emptyRate > 0 }
            .sortedByDescending { it.emptyRate }
            .take(3),
        numericColumns = numericColumns,
        textColumns = dataset.columns.size - numericColumns,
        consistency = consistency.take(3),
        constantColumns = constantColumns,
    )
}

data class QualityDimension(val label: String, val value: Double, val hint: String)

data class QualityScore(val dimensions: List<QualityDimension>, val average: Double)

private fun round1(value: Double): Double = Math.round(value * 10) / 10.0

fun scoreQuality(dataset: Dataset): QualityScore {
    val report = analyzeQuality(dataset)
    if (report.rows == 0) {
        return QualityScore(
            dimensions = listOf(
                QualityDimension("完整性", 0.0, "没有数据行可评估"),
                QualityDimension("唯一性", 0.0, "没有数据行可评估"),
            ),
            average = 0.0,
        )
    }
    val rowCount = maxOf(report.rows, 1)
    val dimensions = listOf(
        QualityDimension("完整性", round1((1 - report.emptyRate) * 100), "空值会让均值失真、计数偏少"),
        QualityDimension("唯一性", round1((1 - report.duplicateRows.toDouble() / rowCount) * 100), "重复行会让计数翻倍、聚合值虚高"),
    )
    val average = round1(dimensions.sumOf { it.value } / dimensions.size)
    return QualityScore(dimensions, average)
}

// ---------- IQR 异常检测 ----------

data class OutlierItem(val rowIndex: Int, val value: Double, val deviation: Double, val bound: String)

data class OutlierReport(
    val column: String,
    val sampleSize: Int,
    val q1: Double,
    val q3: Double,
    val iqr: Double,
    val lower: Double,
    val upper: Double,
    val outliers: List<OutlierItem>,
)

private fun quantileOf(sorted: List<Double>, q: Double): Double {
    if (sorted.isEmpty()) return Double.NaN
    val position = (sorted.size - 1) * q
    val lower = kotlin.math.floor(position).toInt()
    val upper = kotlin.math.ceil(position).toInt()
    if (lower == upper) return sorted[lower]
    return sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower)
}

fun detectOutliers(dataset: Dataset, columnIndex: Int): OutlierReport? {
    if (columnIndex < 0 || columnIndex >= dataset.columns.size) return null
    if (dataset.columnTypes[columnIndex] != ColumnType.NUMBER) return null
    val samples = mutableListOf<Pair<Int, Double>>()
    dataset.rows.forEachIndexed { rowIndex, row ->
        val value = Csv.parseNumber(row.getOrNull(columnIndex) ?: "")
        if (value != null) samples.add(rowIndex to value)
    }
    if (samples.size < 4) return null
    val sorted = samples.map { it.second }.sorted()
    val q1 = quantileOf(sorted, 0.25)
    val q3 = quantileOf(sorted, 0.75)
    val iqr = q3 - q1
    if (iqr == 0.0) return null
    val lower = q1 - 1.5 * iqr
    val upper = q3 + 1.5 * iqr
    val outliers = samples
        .filter { (_, value) -> value < lower || value > upper }
        .map { (rowIndex, value) ->
            if (value > upper) OutlierItem(rowIndex, value, (value - upper) / iqr, "high")
            else OutlierItem(rowIndex, value, (lower - value) / iqr, "low")
        }
        .sortedByDescending { it.deviation }
    return OutlierReport(dataset.columns[columnIndex], samples.size, q1, q3, iqr, lower, upper, outliers)
}

fun detectAllOutliers(dataset: Dataset): List<OutlierReport> =
    dataset.columns.indices
        .mapNotNull { detectOutliers(dataset, it) }
        .filter { it.outliers.isNotEmpty() }

// ---------- 皮尔逊相关 ----------

data class CorrelationItem(val a: String, val b: String, val r: Double, val strength: String, val n: Int)

private fun strengthOf(r: Double): String {
    val a = abs(r)
    return when {
        a >= 0.8 -> "极强"
        a >= 0.6 -> "强"
        a >= 0.4 -> "中等"
        a >= 0.2 -> "弱"
        else -> "极弱"
    }
}

private fun pearson(samples: List<Pair<Double, Double>>): Double? {
    val n = samples.size
    var sumX = 0.0
    var sumY = 0.0
    for ((x, y) in samples) { sumX += x; sumY += y }
    val meanX = sumX / n
    val meanY = sumY / n
    var covariance = 0.0
    var varianceX = 0.0
    var varianceY = 0.0
    for ((x, y) in samples) {
        val dx = x - meanX
        val dy = y - meanY
        covariance += dx * dy
        varianceX += dx * dx
        varianceY += dy * dy
    }
    if (varianceX == 0.0 || varianceY == 0.0) return null
    val denominator = sqrt(varianceX) * sqrt(varianceY)
    if (!denominator.isFinite() || denominator == 0.0) return null
    val r = covariance / denominator
    if (!r.isFinite()) return null
    return max(-1.0, min(1.0, r))
}

fun correlate(dataset: Dataset): List<CorrelationItem> {
    val numericIndexes = dataset.columns.indices.filter { dataset.columnTypes[it] == ColumnType.NUMBER }
    val pairs = mutableListOf<CorrelationItem>()
    for (i in numericIndexes.indices) {
        for (j in i + 1 until numericIndexes.size) {
            val a = numericIndexes[i]
            val b = numericIndexes[j]
            val samples = dataset.rows.mapNotNull { row ->
                val x = Csv.parseNumber(row.getOrNull(a) ?: "") ?: return@mapNotNull null
                val y = Csv.parseNumber(row.getOrNull(b) ?: "") ?: return@mapNotNull null
                x to y
            }
            val r = pearson(samples)
            if (r != null) pairs.add(CorrelationItem(dataset.columns[a], dataset.columns[b], r, strengthOf(r), samples.size))
        }
    }
    return pairs.sortedByDescending { abs(it.r) }
}
