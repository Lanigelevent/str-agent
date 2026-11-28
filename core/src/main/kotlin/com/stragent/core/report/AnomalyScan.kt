package com.stragent.core.report

import com.stragent.core.csv.ColumnType
import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset
import com.stragent.core.dataset.looksLikeIdColumn
import com.stragent.core.dataset.summarize
import com.stragent.core.stats.monthKey
import com.stragent.core.stats.shiftMonth
import kotlin.math.abs
import kotlin.math.floor

/**
 * 数据异常自动扫描 —— src/shared/lib/anomaly-scan.ts 的 Kotlin 移植。
 *
 * 三条纪律：a) 全部确定性计算；b) 每个数字都能回到原始行（evidenceRows）；
 * c) 阈值自适应（MAD 与 IQR 取大者）。
 *
 * fmt 与 TS 的 toLocaleString('zh-CN') 对齐：两位小数 + 千分位逗号。
 */
enum class AnomalyKind { SHIFT, MIX, GAP, DUPLICATE, NEGATIVE, FLATLINE }
enum class Severity { HIGH, MEDIUM, LOW }

data class Contributor(val label: String, val delta: Double, val share: Double)

data class AnomalyFinding(
    val kind: AnomalyKind,
    val severity: Severity,
    val metric: String,
    val period: String? = null,
    val dimension: String? = null,
    val before: Double,
    val after: Double,
    val change: Double,
    val contributors: List<Contributor>,
    val evidenceRows: List<Int>,
    val detail: String,
)

data class AnomalyCoverage(
    val monthly: Boolean,
    val sparse: Boolean,
    val dimension: String?,
    val metric: String?,
    val months: Int,
)

data class AnomalyScan(
    val findings: List<AnomalyFinding>,
    val coverage: AnomalyCoverage,
    val summary: Summary,
) {
    data class Summary(val high: Int, val medium: Int, val low: Int, val total: Int)
}

// ---------- 内部工具 ----------

/** 千分位 + 去尾零（TS: round2 后 toLocaleString('zh-CN')，与 brief 的 formatNumber 同口径） */
internal fun fmt(value: Double): String {
    if (!value.isFinite()) return "-"
    val rounded = Math.round(value * 100) / 100.0
    val isNegative = rounded < 0
    val stripped = java.math.BigDecimal(abs(rounded))
        .setScale(2, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
    val parts = stripped.split(".")
    val intPart = parts[0]
    val frac = if (parts.size > 1) parts[1] else ""
    val grouped = StringBuilder()
    var count = 0
    for (i in intPart.length - 1 downTo 0) {
        grouped.insert(0, intPart[i])
        count += 1
        if (count % 3 == 0 && i > 0) grouped.insert(0, ',')
    }
    return if (frac.isEmpty()) "${if (isNegative) "-" else ""}$grouped" else "${if (isNegative) "-" else ""}$grouped.$frac"
}

internal fun pct(ratio: Double): String {
    val sign = if (ratio > 0) "+" else if (ratio < 0) "\u2212" else ""
    return "$sign${formatOne(abs(ratio) * 100)}%"
}

private fun formatOne(v: Double): String =
    java.math.BigDecimal(v).setScale(1, java.math.RoundingMode.HALF_UP).toPlainString()

internal fun medianOf(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    val mid = sorted.size shr 1
    return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
}

/** 分位数（线性插值，入参已升序） */
private fun quartile(sorted: List<Double>, q: Double): Double {
    if (sorted.isEmpty()) return 0.0
    val pos = (sorted.size - 1) * q
    val base = floor(pos).toInt()
    val rest = pos - base
    val next = sorted.getOrNull(base + 1) ?: return sorted[base]
    return sorted[base] + rest * (next - sorted[base])
}

/** 负值检测的列名特征（不含率/比/指数） */
private val AMOUNT_WORD = Regex("金额|额|价|款|收入|成本|利润|数量|销量|库存|余|数|量|值")

private data class MonthlyPoint(
    val month: String,
    val total: Double,
    val count: Int,
    val byGroup: LinkedHashMap<String, Double>,
    val rows: List<Int>,
)

/** 月度序列 + 维度分解（一次遍历；返回前统一 round2） */
private fun monthlySeries(dataset: Dataset, dateIndex: Int, valueIndex: Int, groupIndex: Int): List<MonthlyPoint> {
    data class Builder(var total: Double, var count: Int, val byGroup: LinkedHashMap<String, Double>, val rows: MutableList<Int>)

    val buckets = LinkedHashMap<String, Builder>()
    for (r in dataset.rows.indices) {
        val row = dataset.rows[r]
        val month = monthKey(row.getOrNull(dateIndex) ?: "") ?: continue
        val value = Csv.parseNumber(row.getOrNull(valueIndex) ?: "") ?: continue
        val point = buckets.getOrPut(month) { Builder(0.0, 0, LinkedHashMap(), mutableListOf()) }
        point.total += value
        point.count += 1
        if (groupIndex >= 0 && groupIndex != dateIndex) {
            val group = (row.getOrNull(groupIndex) ?: "").trim()
            if (group.isNotEmpty()) point.byGroup[group] = (point.byGroup[group] ?: 0.0) + value
        }
        if (point.rows.size < 3) point.rows.add(r + 1)
    }
    fun round2(v: Double): Double = Math.round(v * 100) / 100.0
    return buckets.entries
        .sortedWith(compareBy { it.key })
        .map { (month, b) ->
            MonthlyPoint(
                month = month,
                total = round2(b.total),
                count = b.count,
                byGroup = LinkedHashMap(b.byGroup.mapValues { round2(it.value) }),
                rows = b.rows.toList(),
            )
        }
}

// ---------- 阈值 ----------

private fun shiftThreshold(ratios: List<Double>): Double {
    if (ratios.isEmpty()) return 0.3
    val sorted = ratios.sorted()
    val mid = medianOf(sorted)
    val mad = medianOf(ratios.map { abs(it - mid) })
    val iqr = quartile(sorted, 0.75) - quartile(sorted, 0.25)
    val scale = maxOf(mad, iqr / 1.349)
    return minOf(maxOf(0.3, 2.5 * scale), 1.0)
}

private fun periodRowFloor(series: List<MonthlyPoint>): Int =
    maxOf(2, floor(medianOf(series.map { it.count.toDouble() }) * 0.5).toInt())

private const val MONTHLY_CREDIBLE_MIN = 20

private const val MIX_MIN = 0.05
private const val MIX_MAX = 0.2
private const val MIX_FALLBACK = 0.1
private const val MIX_FLOOR = 0.05
private const val FLATLINE_RUN = 3
private const val PRIMARY_KEY_MIN_UNIQUE = 0.95
private const val MAX_FINDINGS = 12
private const val TYPE_FLOOR = 1

private fun mixThreshold(deltas: List<Double>): Double {
    val magnitudes = deltas.map { abs(it) }.filter { it.isFinite() }
    if (magnitudes.size < 4) return MIX_FALLBACK
    val mid = medianOf(magnitudes)
    val mad = medianOf(magnitudes.map { abs(it - mid) })
    val sorted = magnitudes.sorted()
    val iqr = (sorted.getOrNull(floor(sorted.size * 0.75).toInt()) ?: 0.0) -
        (sorted.getOrNull(floor(sorted.size * 0.25).toInt()) ?: 0.0)
    val scale = maxOf(mad, iqr / 1.349)
    return minOf(maxOf(MIX_MIN, 2.5 * scale), MIX_MAX)
}

// ---------- 各项检测 ----------

private fun detectShifts(series: List<MonthlyPoint>, metric: String, groupName: String?): List<AnomalyFinding> {
    if (series.size < 4) return emptyList()
    val rowFloor = periodRowFloor(series)
    val scale = medianOf(series.map { abs(it.total) })
    val floor = abs(scale) * 0.05
    val ratios = mutableListOf<Double>()
    for (i in 1 until series.size) {
        val before = series[i - 1].total
        if (abs(before) < floor) continue
        if (series[i - 1].count < rowFloor || series[i].count < rowFloor) continue
        ratios.add((series[i].total - before) / abs(before))
    }
    val threshold = shiftThreshold(ratios)

    val findings = mutableListOf<AnomalyFinding>()
    for (i in 1 until series.size) {
        val prev = series[i - 1]
        val cur = series[i]
        if (abs(prev.total) < floor) continue
        if (prev.count < rowFloor || cur.count < rowFloor) continue
        val ratio = (cur.total - prev.total) / abs(prev.total)
        if (abs(ratio) < threshold) continue

        val contributors = attribute(prev.byGroup, cur.byGroup)
        val magnitude = abs(ratio)
        val severity = when {
            magnitude >= threshold * 2.5 -> Severity.HIGH
            magnitude >= threshold * 1.5 -> Severity.MEDIUM
            else -> Severity.LOW
        }
        val head = if (contributors.isNotEmpty() && groupName != null) {
            "，主要来自" + contributors.take(2).joinToString("、") { item ->
                "${item.label}（${fmt(item.delta)}，占 ${formatZero(item.share * 100)}%）"
            }
        } else ""
        findings.add(
            AnomalyFinding(
                kind = AnomalyKind.SHIFT,
                severity = severity,
                metric = metric,
                period = cur.month,
                before = prev.total,
                after = cur.total,
                change = ratio,
                contributors = contributors,
                evidenceRows = prev.rows + cur.rows,
                detail = "${cur.month}「${metric}」合计 ${fmt(cur.total)}，相对 ${prev.month} 的 ${fmt(prev.total)} " +
                    "变化 ${pct(ratio)}（阈值 ${pct(threshold)}）$head",
            ),
        )
    }
    return findings
}

private fun formatZero(v: Double): String =
    java.math.BigDecimal(v).setScale(0, java.math.RoundingMode.HALF_UP).toPlainString()

private fun attribute(before: Map<String, Double>, after: Map<String, Double>): List<Contributor> {
    val labels = (before.keys + after.keys).toSet()
    val deltas = labels.map { label -> Contributor(label, (after[label] ?: 0.0) - (before[label] ?: 0.0), 0.0) }
    val total = deltas.sumOf { abs(it.delta) }
    if (total == 0.0) return emptyList()
    return deltas
        .sortedByDescending { abs(it.delta) }
        .take(3)
        .map { it.copy(share = abs(it.delta) / total) }
}

private fun detectMix(series: List<MonthlyPoint>, metric: String): List<AnomalyFinding> {
    if (series.size < 4) return emptyList()
    val rowFloor = periodRowFloor(series)
    val comparable = mutableListOf<Pair<MonthlyPoint, MonthlyPoint>>()
    for (i in 1 until series.size) {
        val prev = series[i - 1]
        val cur = series[i]
        if (prev.total == 0.0 || cur.total == 0.0) continue
        if (prev.count < rowFloor || cur.count < rowFloor) continue
        comparable.add(prev to cur)
    }

    val threshold = mixThreshold(
        comparable.flatMap { (prev, cur) ->
            val labels = (prev.byGroup.keys + cur.byGroup.keys).toSet()
            labels.map { label ->
                val before = (prev.byGroup[label] ?: 0.0) / prev.total
                val after = (cur.byGroup[label] ?: 0.0) / cur.total
                after - before
            }
        },
    )

    val findings = mutableListOf<AnomalyFinding>()
    for ((prev, cur) in comparable) {
        val labels = (prev.byGroup.keys + cur.byGroup.keys).toSet()
        for (label in labels) {
            val shareBefore = (prev.byGroup[label] ?: 0.0) / prev.total
            val shareAfter = (cur.byGroup[label] ?: 0.0) / cur.total
            if (shareAfter < MIX_FLOOR && shareBefore < MIX_FLOOR) continue
            val delta = shareAfter - shareBefore
            if (abs(delta) < threshold) continue
            findings.add(
                AnomalyFinding(
                    kind = AnomalyKind.MIX,
                    severity = if (abs(delta) >= threshold * 2.5) Severity.HIGH else Severity.MEDIUM,
                    metric = metric,
                    dimension = label,
                    period = cur.month,
                    before = shareBefore,
                    after = shareAfter,
                    change = delta,
                    contributors = emptyList(),
                    evidenceRows = cur.rows,
                    detail = "${cur.month}「${label}」占「${metric}」的比例 ${formatOne(shareAfter * 100)}%，" +
                        "相对 ${prev.month} 的 ${formatOne(shareBefore * 100)}% 变化 ${formatOne(delta * 100)} 个百分点" +
                        "（阈值 ${formatOne(threshold * 100)} 个百分点）",
                ),
            )
        }
    }
    return findings
}

private fun detectGaps(series: List<MonthlyPoint>, metric: String): List<AnomalyFinding> {
    if (series.size < 3) return emptyList()
    val first = series.first().month
    val last = series.last().month
    val present = series.map { it.month }.toSet()
    val missing = mutableListOf<String>()
    var m = first
    while (m <= last) {
        if (m !in present) missing.add(m)
        m = shiftMonth(m, 1)
    }
    if (missing.isEmpty()) return emptyList()
    val severity = when {
        missing.size >= 3 -> Severity.HIGH
        missing.size >= 2 -> Severity.MEDIUM
        else -> Severity.LOW
    }
    return listOf(
        AnomalyFinding(
            kind = AnomalyKind.GAP,
            severity = severity,
            metric = metric,
            before = series.size.toDouble(),
            after = (series.size + missing.size).toDouble(),
            change = missing.size.toDouble() / series.size,
            contributors = emptyList(),
            evidenceRows = emptyList(),
            detail = "「${metric}」的月度序列从 ${first} 到 ${last} 缺 ${missing.size} 个月：${missing.joinToString("、")}。" +
                "跨缺月的对比不是环比，不能直接说\"下降\"",
        ),
    )
}

private fun detectFlatline(series: List<MonthlyPoint>, metric: String): List<AnomalyFinding> {
    if (series.size < FLATLINE_RUN + 1) return emptyList()
    val findings = mutableListOf<AnomalyFinding>()
    var run = 1

    fun flush(endIndex: Int, length: Int) {
        if (length < FLATLINE_RUN) return
        val startIndex = endIndex - length + 1
        if (startIndex < 1) return
        if (abs(series[startIndex].total - series[startIndex - 1].total) < 0.005) return
        val start = series[startIndex]
        val end = series[endIndex]
        findings.add(
            AnomalyFinding(
                kind = AnomalyKind.FLATLINE,
                severity = if (length >= 4) Severity.HIGH else Severity.MEDIUM,
                metric = metric,
                period = end.month,
                before = start.total,
                after = end.total,
                change = 0.0,
                contributors = emptyList(),
                evidenceRows = end.rows,
                detail = "「${metric}」从 ${start.month} 到 ${end.month} 连续 ${length} 期都是 ${fmt(end.total)}。" +
                    "完全相同不是业务常态，先确认数据源是否还在更新",
            ),
        )
    }

    for (i in 1 until series.size) {
        val same = abs(series[i].total - series[i - 1].total) < 0.005
        if (same) {
            run += 1
            continue
        }
        flush(i - 1, run)
        run = 1
    }
    flush(series.size - 1, run)
    return findings
}

private fun detectDuplicates(dataset: Dataset): List<AnomalyFinding> {
    val findings = mutableListOf<AnomalyFinding>()
    for (index in dataset.columns.indices) {
        if (dataset.columnTypes[index] != ColumnType.TEXT) continue
        if (!looksLikeIdColumn(dataset.columns[index])) continue
        val seen = LinkedHashMap<String, Int>()
        val dupRows = mutableListOf<Int>()
        var total = 0
        for (r in dataset.rows.indices) {
            val value = (dataset.rows[r].getOrNull(index) ?: "").trim()
            if (value.isEmpty()) continue
            total += 1
            val count = seen[value] ?: 0
            seen[value] = count + 1
            if (count >= 1 && dupRows.size < 3) dupRows.add(r + 1)
        }
        val duplicated = seen.values.count { it > 1 }
        if (duplicated == 0) continue
        if (seen.size.toDouble() / maxOf(total, 1) < PRIMARY_KEY_MIN_UNIQUE) continue
        findings.add(
            AnomalyFinding(
                kind = AnomalyKind.DUPLICATE,
                severity = if (duplicated > total * 0.05) Severity.HIGH else Severity.MEDIUM,
                metric = dataset.columns[index],
                before = total.toDouble(),
                after = seen.size.toDouble(),
                change = duplicated.toDouble() / maxOf(total, 1),
                contributors = emptyList(),
                evidenceRows = dupRows,
                detail = "标识列「${dataset.columns[index]}」${total} 个非空值里有 ${duplicated} 个重复值，" +
                    "去重后只剩 ${seen.size} 个。合计类指标会把这些行算重",
            ),
        )
    }
    return findings
}

private fun detectNegatives(dataset: Dataset): List<AnomalyFinding> {
    val stats = summarize(dataset)
    val findings = mutableListOf<AnomalyFinding>()
    for (stat in stats) {
        if (stat.type != ColumnType.NUMBER) continue
        if (looksLikeIdColumn(stat.name)) continue
        if (!AMOUNT_WORD.containsMatchIn(stat.name)) continue
        val min = stat.min ?: continue
        if (min >= 0) continue
        val index = dataset.columns.indexOf(stat.name)
        val rows = mutableListOf<Int>()
        if (index >= 0) {
            for (r in dataset.rows.indices) {
                if (rows.size >= 3) break
                val value = Csv.parseNumber(dataset.rows[r].getOrNull(index) ?: "")
                if (value != null && value < 0) rows.add(r + 1)
            }
        }
        val severity = if (abs(min) > abs(stat.max ?: 0.0)) Severity.HIGH else Severity.MEDIUM
        findings.add(
            AnomalyFinding(
                kind = AnomalyKind.NEGATIVE,
                severity = severity,
                metric = stat.name,
                before = 0.0,
                after = min,
                change = min,
                contributors = emptyList(),
                evidenceRows = rows,
                detail = "「${stat.name}」出现负值，最小 ${fmt(min)}（最大 ${fmt(stat.max ?: 0.0)}）。" +
                    "负值会拉低合计，报告给总数时要说明是否含负值",
            ),
        )
    }
    return findings
}

// ---------- 排序与入口 ----------

private fun severityWeight(s: Severity): Int = when (s) { Severity.HIGH -> 3; Severity.MEDIUM -> 2; Severity.LOW -> 1 }

private val bySeverity = compareByDescending<AnomalyFinding> { severityWeight(it.severity) }
    .thenByDescending { abs(it.change) }

private fun rankFindings(findings: List<AnomalyFinding>): List<AnomalyFinding> {
    if (findings.size <= MAX_FINDINGS) return findings.sortedWith(bySeverity)

    val floored = LinkedHashSet<AnomalyFinding>()
    val kinds = findings.map { it.kind }.toSet()
    for (kind in kinds) {
        findings.filter { it.kind == kind }
            .sortedWith(bySeverity)
            .take(TYPE_FLOOR)
            .forEach { floored.add(it) }
    }
    val rest = findings.filter { it !in floored }.sortedWith(bySeverity)
    val room = maxOf(0, MAX_FINDINGS - floored.size)
    return (floored.toList() + rest.take(room)).sortedWith(bySeverity)
}

fun scanAnomalies(dataset: Dataset, preferredGroup: Int? = null): AnomalyScan {
    val stats = summarize(dataset)
    val numericNames = stats.filter { it.type == ColumnType.NUMBER && !looksLikeIdColumn(it.name) }.map { it.name }.toSet()

    val dateIndex = dataset.columns.indices.firstOrNull { index ->
        dataset.columnTypes[index] == ColumnType.TEXT &&
            dataset.rows.any { monthKey(it.getOrNull(index) ?: "") != null }
    } ?: -1

    val groupIndex = if (preferredGroup != null && preferredGroup >= 0 && preferredGroup != dateIndex) {
        preferredGroup
    } else {
        dataset.columns.indices.firstOrNull { index ->
            if (index == dateIndex) return@firstOrNull false
            if (dataset.columnTypes[index] != ColumnType.TEXT) return@firstOrNull false
            val name = dataset.columns[index]
            if (looksLikeIdColumn(name)) return@firstOrNull false
            val distinct = stats.find { it.name == name }?.distinct ?: 0
            distinct >= 2 && distinct <= 24
        } ?: -1
    }
    val valueIndex = dataset.columns.indices.firstOrNull { index -> dataset.columns[index] in numericNames } ?: -1

    var coverage = AnomalyCoverage(
        monthly = false,
        sparse = false,
        dimension = if (groupIndex >= 0) dataset.columns[groupIndex] else null,
        metric = if (valueIndex >= 0) dataset.columns[valueIndex] else null,
        months = 0,
    )

    val findings = mutableListOf<AnomalyFinding>()

    if (dateIndex >= 0 && valueIndex >= 0) {
        val metric = dataset.columns[valueIndex]
        val series = monthlySeries(dataset, dateIndex, valueIndex, groupIndex)
        coverage = coverage.copy(months = series.size)
        val medianRows = medianOf(series.map { it.count.toDouble() })
        coverage = coverage.copy(
            monthly = series.size >= 4 && medianRows >= MONTHLY_CREDIBLE_MIN,
            sparse = series.size >= 4 && medianRows < MONTHLY_CREDIBLE_MIN,
        )
        if (coverage.monthly) {
            findings.addAll(detectShifts(series, metric, coverage.dimension))
            findings.addAll(detectGaps(series, metric))
            findings.addAll(detectFlatline(series, metric))
            if (groupIndex >= 0) findings.addAll(detectMix(series, metric))
        }
    }

    findings.addAll(detectDuplicates(dataset))
    findings.addAll(detectNegatives(dataset))

    val ranked = rankFindings(findings)

    return AnomalyScan(
        findings = ranked,
        coverage = coverage,
        summary = AnomalyScan.Summary(
            high = ranked.count { it.severity == Severity.HIGH },
            medium = ranked.count { it.severity == Severity.MEDIUM },
            low = ranked.count { it.severity == Severity.LOW },
            total = ranked.size,
        ),
    )
}
