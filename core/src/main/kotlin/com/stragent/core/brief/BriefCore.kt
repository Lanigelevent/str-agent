package com.stragent.core.brief

import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset
import com.stragent.core.dataset.looksLikeIdColumn
import com.stragent.core.dataset.summarize
import java.util.Locale

/** 千分位 + 去尾零（TS: round2 后 toLocaleString('zh-CN')——14 而非 14.00、0.2 而非 0.20）。公开给 UI 层复用同一数字口径 */
fun formatNumber(value: Double): String {
    if (!value.isFinite()) return "-"
    val rounded = Math.round(value * 100) / 100.0
    // JS 的 -0 语义：-0.001 round2 后是 -0，toLocaleString 输出 "-0"（不是 "0"）
    val isNeg = rounded < 0 || (rounded == 0.0 && value < 0)
    // round2 后 strip 尾零：14.00→14、0.20→0.2、2,297,201.08 保留两位
    val stripped = java.math.BigDecimal(kotlin.math.abs(rounded))
        .setScale(2, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
    val parts = stripped.split(".")
    val intPart = parts[0]
    val frac = if (parts.size > 1) parts[1] else ""
    val sb = StringBuilder()
    var count = 0
    for (i in intPart.length - 1 downTo 0) {
        sb.insert(0, intPart[i])
        count += 1
        if (count % 3 == 0 && i > 0) sb.insert(0, ',')
    }
    val grouped = sb.toString()
    return when {
        frac.isEmpty() -> "${if (isNeg) "-" else ""}$grouped"
        else -> "${if (isNeg) "-" else ""}$grouped.$frac"
    }
}

/** JS toFixed(1) 对齐（NaN/Infinity 原样输出；负零保留 "-0.0"，JS 行为） */
internal fun toFixed1(v: Double): String {
    if (!v.isFinite()) return v.toString()
    val s = java.math.BigDecimal(v).setScale(1, java.math.RoundingMode.HALF_UP).toPlainString()
    return if (s == "0.0" && v < 0) "-0.0" else s
}

/** JS toFixed(2) 对齐（负零保留 "-0.00"） */
internal fun toFixed2(v: Double): String {
    if (!v.isFinite()) return v.toString()
    val s = java.math.BigDecimal(v).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
    return if (s == "0.00" && v < 0) "-0.00" else s
}

/** 整数千分位（toLocaleString('zh-CN')） */
internal fun formatInt(n: Int): String = String.format(Locale.CHINA, "%,d", n)

/** dateParts：认 YYYY-MM-DD / YYYY/M/D / 美式 M/D/YYYY（三种，模块内私有口径） */
internal data class DateParts(val year: Int, val month: Int, val day: Int)

/**
 * 热路径正则——**预编译单例**（T-142）。
 *
 * `dateParts` / `yearlySeries` / `monthlyFromColumnNames` 都是**逐行/逐格**调用的，
 * 写在函数体内会每次调用都重新编译 `Pattern`。`Pattern` 不可变且线程安全，
 * 提为单例安全。仓内先例：`core/verify/VerifyCore.kt:17-38`。
 */
private val DATE_YMD = Regex("^(\\d{4})[-/.](\\d{1,2})(?:[-/.](\\d{1,2}))?(?:[ T].*)?$")
private val DATE_US = Regex("^(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{4})(?:[ T].*)?$")
private val MONTH_ISO = Regex("^((?:19|20)\\d{2})-(\\d{2})$")
private val YEAR_TOKEN = Regex("\\b(?:19|20)\\d{2}\\b")
/** 列名日期：YYYY[-/.]M[-/.]D（**日可省**，且不接受时间后缀——与 DATE_YMD 不同口径） */
private val DATE_COLUMN = Regex("^(\\d{4})[-/.](\\d{1,2})(?:[-/.](\\d{1,2}))?$")

internal fun dateParts(raw: String): DateParts? {
    val text = raw.trim()
    var match = DATE_YMD.find(text)
    val year: Int
    val month: Int
    val day: Int
    if (match != null) {
        year = match.groupValues[1].toInt()
        month = match.groupValues[2].toInt()
        day = match.groupValues[3].takeIf { it.isNotEmpty() }?.toInt() ?: 1
    } else {
        match = DATE_US.find(text) ?: return null
        month = match.groupValues[1].toInt()
        day = match.groupValues[2].toInt()
        year = match.groupValues[3].toInt()
    }
    if (month < 1 || month > 12 || day < 1 || day > 31 || year < 1900 || year > 2200) return null
    return DateParts(year, month, day)
}

/** shiftMonth：YYYY-MM 回推 n 月（模块内私有口径，与 stats 的同构） */
internal fun shiftMonthLocal(month: String, delta: Int): String {
    val match = MONTH_ISO.find(month) ?: return month
    val total = match.groupValues[1].toInt() * 12 + (match.groupValues[2].toInt() - 1) + delta
    val year = Math.floorDiv(total, 12)
    val mon = Math.floorMod(total, 12) + 1
    return "$year-${mon.toString().padStart(2, '0')}"
}

// ---------- 数据画像 ----------

data class DataProfile(
    val validRows: Int,
    val totalRows: Int,
    val validColumns: Int,
    val totalColumns: Int,
    val numericColumns: Int,
    val groupColumns: Int,
    val hasTimeColumn: Boolean,
    val density: Double,
    val validCells: Int,
)

fun profileDataset(dataset: Dataset): DataProfile {
    val totalRows = dataset.rows.size
    val totalColumns = dataset.columns.size
    val totalCells = totalRows * totalColumns

    val columnNonEmpty = IntArray(totalColumns)
    var validRows = 0
    var validCells = 0

    for (row in dataset.rows) {
        var nonEmpty = 0
        for (index in 0 until totalColumns) {
            if ((row.getOrNull(index) ?: "").trim().isNotEmpty()) {
                nonEmpty += 1
                columnNonEmpty[index] += 1
            }
        }
        if (nonEmpty == 0) continue
        if (nonEmpty == totalColumns && dataset.columns.withIndex().all { (i, name) -> (row.getOrNull(i) ?: "").trim() == name }) continue
        validRows += 1
        validCells += nonEmpty
    }

    val stats = summarize(dataset)
    val numericColumns = stats.count { it.type == com.stragent.core.csv.ColumnType.NUMBER && !looksLikeIdColumn(it.name) }
    val groupColumns = stats.count {
        it.type == com.stragent.core.csv.ColumnType.TEXT && !looksLikeIdColumn(it.name) &&
            (it.distinct ?: 0) >= 2 && (it.distinct ?: 0) <= 12
    }
    val hasTimeColumn = stats.any { it.type == com.stragent.core.csv.ColumnType.TEXT && (it.distinct ?: 0) > totalRows * 0.5 }

    return DataProfile(
        validRows = validRows,
        totalRows = totalRows,
        validColumns = columnNonEmpty.count { it > 0 },
        totalColumns = totalColumns,
        numericColumns = numericColumns,
        groupColumns = groupColumns,
        hasTimeColumn = hasTimeColumn,
        density = if (totalCells == 0) 0.0 else validCells.toDouble() / totalCells,
        validCells = validCells,
    )
}

// ---------- 年度 / 月度 × 维度 ----------

data class YearlyPoint(val label: String, val value: Double, val count: Int)

fun yearlySeries(dataset: Dataset, dateIndex: Int, valueIndex: Int): List<YearlyPoint> {
    val buckets = LinkedHashMap<String, IntArray>() // [total*1000? no — use DoubleArray]
    val doubles = LinkedHashMap<String, DoubleArray>()
    val counts = LinkedHashMap<String, IntArray>()
    for (row in dataset.rows) {
        val raw = (row.getOrNull(dateIndex) ?: "").trim()
        val year = YEAR_TOKEN.find(raw)?.value ?: continue
        val value = Csv.parseNumber(row.getOrNull(valueIndex) ?: "") ?: continue
        val b = doubles.getOrPut(year) { doubleArrayOf(0.0) }
        b[0] += value
        counts.getOrPut(year) { intArrayOf(0) }[0] += 1
    }
    return doubles.keys.sorted().map { label -> YearlyPoint(label, doubles[label]!![0], counts[label]!![0]) }
}

data class YearByDim(val label: String, val years: List<Pair<String, Double>>)

fun yearByDimension(dataset: Dataset, dateIndex: Int, groupIndex: Int, valueIndex: Int): List<YearByDim> {
    val buckets = LinkedHashMap<String, LinkedHashMap<String, Double>>()
    for (row in dataset.rows) {
        val raw = (row.getOrNull(dateIndex) ?: "").trim()
        val year = YEAR_TOKEN.find(raw)?.value ?: continue
        val group = (row.getOrNull(groupIndex) ?: "").trim()
        if (group.isEmpty()) continue
        val value = Csv.parseNumber(row.getOrNull(valueIndex) ?: "") ?: continue
        val byYear = buckets.getOrPut(group) { LinkedHashMap() }
        byYear[year] = (byYear[year] ?: 0.0) + value
    }
    return buckets.entries
        .map { (label, byYear) -> YearByDim(label, byYear.entries.sortedWith(compareBy { it.key }).map { (y, v) -> y to v }) }
        .filter { it.years.size >= 2 }
        .sortedByDescending { it.years.sumOf { (_, v) -> v } }
}

data class MonthByDim(val label: String, val months: List<Pair<String, Double>>)

fun monthByDimension(dataset: Dataset, dateIndex: Int, groupIndex: Int, valueIndex: Int): List<MonthByDim> {
    val buckets = LinkedHashMap<String, LinkedHashMap<String, Double>>()
    for (row in dataset.rows) {
        val parts = dateParts(row.getOrNull(dateIndex) ?: "") ?: continue
        val key = "${parts.year}-${parts.month.toString().padStart(2, '0')}"
        val group = (row.getOrNull(groupIndex) ?: "").trim()
        if (group.isEmpty()) continue
        val value = Csv.parseNumber(row.getOrNull(valueIndex) ?: "") ?: continue
        val byMonth = buckets.getOrPut(group) { LinkedHashMap() }
        byMonth[key] = (byMonth[key] ?: 0.0) + value
    }
    return buckets.entries
        .map { (label, byMonth) -> MonthByDim(label, byMonth.entries.sortedWith(compareBy { it.key }).map { (m, v) -> m to v }) }
        .filter { it.months.size >= 3 }
        .sortedByDescending { it.months.sumOf { (_, v) -> v } }
}

data class CrossCellBrief(val a: String, val b: String, val value: Double, val count: Int)

fun crossTabulateBrief(dataset: Dataset, aIndex: Int, bIndex: Int, valueIndex: Int, topN: Int): List<CrossCellBrief> {
    // 嵌套 Map 保序（同 stats.CrossTab 的纪律）
    val byA = LinkedHashMap<String, LinkedHashMap<String, DoubleArray>>()
    val order = mutableListOf<Pair<String, String>>()
    for (row in dataset.rows) {
        val a = (row.getOrNull(aIndex) ?: "").trim().ifEmpty { "（空）" }
        val b = (row.getOrNull(bIndex) ?: "").trim().ifEmpty { "（空）" }
        val value = Csv.parseNumber(row.getOrNull(valueIndex) ?: "") ?: continue
        val rowCells = byA.getOrPut(a) { LinkedHashMap() }
        if (rowCells[b] == null) {
            rowCells[b] = doubleArrayOf(0.0, 0.0)
            order.add(a to b)
        }
        rowCells[b]!![0] += value
        rowCells[b]!![1] += 1
    }
    return order
        .map { (a, b) -> CrossCellBrief(a, b, byA[a]!![b]!![0], byA[a]!![b]!![1].toInt()) }
        .sortedByDescending { it.count }
        .take(maxOf(1, topN))
}

// ---------- 日期列判定 ----------

internal data class DateRange(val min: String, val max: String, val count: Int)

private fun dateValue(raw: String): Double? {
    val parts = dateParts(raw) ?: return null
    // Date.UTC 口径（天粒度，忽略时分秒）：epochDay 精确对齐，不能用月×30 的近似
    return try {
        java.time.LocalDate.of(parts.year, parts.month, parts.day).toEpochDay().toDouble()
    } catch (e: Exception) {
        null
    }
}

internal fun dateRangeOf(dataset: Dataset, index: Int): DateRange? {
    var minTime = Double.MAX_VALUE
    var maxTime = -Double.MAX_VALUE
    var minRaw = ""
    var maxRaw = ""
    var matched = 0
    for (row in dataset.rows) {
        val raw = (row.getOrNull(index) ?: "").trim()
        if (raw.isEmpty()) continue
        val time = dateValue(raw) ?: return null
        matched += 1
        if (time < minTime) { minTime = time; minRaw = raw }
        if (time > maxTime) { maxTime = time; maxRaw = raw }
    }
    if (matched < 2) return null
    return DateRange(minRaw, maxRaw, matched)
}

/** 按月汇总（最近 pointLimit 个点；buckets < 3 → null） */
internal data class MonthlyPointBrief(val label: String, val sum: Double, val count: Int)

internal fun monthlySeriesBrief(dataset: Dataset, dateIndex: Int, valueIndex: Int, pointLimit: Int): List<MonthlyPointBrief>? {
    val buckets = LinkedHashMap<String, DoubleArray>()
    for (row in dataset.rows) {
        val raw = (row.getOrNull(dateIndex) ?: "").trim()
        if (raw.isEmpty()) continue
        val parts = dateParts(raw) ?: continue
        val value = Csv.parseNumber(row.getOrNull(valueIndex) ?: "") ?: continue
        val key = "${parts.year}-${parts.month.toString().padStart(2, '0')}"
        val bucket = buckets.getOrPut(key) { doubleArrayOf(0.0, 0.0) }
        bucket[0] += value
        bucket[1] += 1
    }
    if (buckets.size < 3) return null
    return buckets.entries
        .sortedWith(compareBy { it.key })
        .takeLast(pointLimit)
        .map { (label, b) -> MonthlyPointBrief(label, b[0], b[1].toInt()) }
}

/** 转置宽表：列名即日期（≥12 个日期列名），同月并成均值 */
internal fun monthlyFromColumnNames(dataset: Dataset, pointLimit: Int): List<MonthlyPointBrief>? {
    val dateCols = mutableListOf<Pair<Int, String>>()
    for (index in dataset.columns.indices) {
        val match = DATE_COLUMN.find(dataset.columns[index].trim()) ?: continue
        val month = match.groupValues[2].toInt()
        if (month < 1 || month > 12) continue
        dateCols.add(index to "${match.groupValues[1]}-${month.toString().padStart(2, '0')}")
    }
    if (dateCols.size < 12) return null

    val buckets = LinkedHashMap<String, DoubleArray>()
    for (row in dataset.rows) {
        for ((index, month) in dateCols) {
            val value = Csv.parseNumber(row.getOrNull(index) ?: "") ?: continue
            val bucket = buckets.getOrPut(month) { doubleArrayOf(0.0, 0.0) }
            bucket[0] += value
            bucket[1] += 1
        }
    }
    if (buckets.size < 3) return null
    return buckets.entries
        .sortedWith(compareBy { it.key })
        .takeLast(pointLimit)
        .map { (label, b) -> MonthlyPointBrief(label, if (b[1] > 0) b[0] / b[1] else 0.0, b[1].toInt()) }
}

// ---------- 维度挑选辅助 ----------

internal fun labelsLookNumeric(dataset: Dataset, index: Int): Boolean {
    val values = LinkedHashSet<String>()
    for (row in dataset.rows) {
        val cell = (row.getOrNull(index) ?: "").trim()
        if (cell.isNotEmpty()) values.add(cell)
        if (values.size > 200) break
    }
    if (values.size < 2) return false
    val numeric = values.count { Csv.parseNumber(it) != null }
    return numeric.toDouble() / values.size > 0.5
}

internal fun columnSignature(dataset: Dataset, index: Int, rows: Int = 40): String =
    dataset.rows.take(rows).joinToString("\u0001") { (it.getOrNull(index) ?: "").trim() }

internal fun looksNumericByValue(dataset: Dataset, index: Int, sample: Int = 200): Boolean {
    var numeric = 0
    var total = 0
    for (row in dataset.rows.take(sample)) {
        val cell = (row.getOrNull(index) ?: "").trim()
        if (cell.isEmpty()) continue
        total += 1
        if (Csv.parseNumber(cell) != null) numeric += 1
    }
    return total >= 5 && numeric.toDouble() / total > 0.7
}

/** 除主维度外再挑几个分组维度（打分降序、签名去重、数值标签排除） */
internal fun pickExtraDimensions(dataset: Dataset, primaryIndex: Int, limit: Int): List<Int> {
    if (limit <= 0) return emptyList()
    val upper = minOf(50, maxOf(2, dataset.rows.size))
    val taken = mutableSetOf(columnSignature(dataset, primaryIndex))
    val picked = mutableListOf<Int>()
    val candidates = dataset.columns
        .mapIndexed { index, name -> name to index }
        .filter { (_, index) ->
            if (index == primaryIndex) return@filter false
            if (dataset.columnTypes[index] != com.stragent.core.csv.ColumnType.TEXT) return@filter false
            if (looksLikeIdColumn(dataset.columns[index])) return@filter false
            if (looksNumericByValue(dataset, index)) return@filter false
            if (labelsLookNumeric(dataset, index)) return@filter false
            val distinct = dataset.rows.map { (it.getOrNull(index) ?: "").trim() }.filter { it.isNotEmpty() }.toSet().size
            distinct >= 2 && distinct <= upper
        }
        .map { (_, index) ->
            val distinct = dataset.rows.map { (it.getOrNull(index) ?: "").trim() }.filter { it.isNotEmpty() }.toSet().size
            Triple(index, distinct, com.stragent.core.dataset.dimensionScore(dataset.columns[index], distinct))
        }
        .sortedWith(compareByDescending<Triple<Int, Int, Int>> { it.third }.thenBy { it.first })
    for ((index, _, _) in candidates) {
        if (picked.size >= limit) break
        val signature = columnSignature(dataset, index)
        if (signature in taken) continue
        taken.add(signature)
        picked.add(index)
    }
    return picked
}
