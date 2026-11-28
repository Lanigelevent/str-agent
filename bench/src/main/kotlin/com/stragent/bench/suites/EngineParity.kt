package com.stragent.bench.suites

import com.stragent.bench.fixtures.findFixtures
import com.stragent.bench.model.SuiteResult
import com.stragent.core.csv.Csv
import com.stragent.core.dataset.ColumnStat
import com.stragent.core.dataset.Dataset
import com.stragent.core.dataset.datasetFromParsed
import com.stragent.core.engine.InMemoryEngine
import com.stragent.engine.duckdb.DuckDbEngine
import java.io.File

/**
 * 套件五 · engine-parity（双轨口径对拍）—— D10 阶段 1 的**核心门禁**。
 *
 * 为什么必须有这个套件：
 *   双轨数据层最大的风险不是「跑不动」，而是**同一个问题在两档给出两个数**。
 *   一旦 tier-2 的数字进了报告，而它与 tier-1（可逐行回溯）口径不一致，
 *   「数字确定性可溯源」壁垒当场击穿 —— 而这种漂移在界面上完全看不出来。
 *
 * 口径：
 *   - 两侧喂**完全相同的字符串**（先由 core 的解析器解析，再喂给 DuckDB），
 *     所以比的是聚合口径，不是读文件口径；
 *   - 聚合：标签、计数必须完全相等，数值与占比按相对容差 1e-9 比对；
 *   - 列画像：count / missing / distinct / topValues 必须完全相等，统计量按 1e-9 比对。
 *
 * 覆盖范围：fixtures/sample 下全部样本 CSV。
 */
fun runEngineParity(): SuiteResult {
    if (!DuckDbEngine.isAvailable()) {
        return SuiteResult(
            name = "engine-parity",
            passed = 0,
            total = 0,
            notes = "跳过：DuckDB 原生库不可用（本机没有 DuckDB 运行时）。",
        )
    }
    val sampleDir = File(findFixtures(), "sample")
    val files = sampleDir.listFiles { f: File -> f.isFile && f.extension.equals("csv", ignoreCase = true) }
        ?.sortedBy { it.name }
        ?: emptyList()

    var passed = 0
    var total = 0
    val failures = mutableListOf<String>()
    var comparedSamples = 0

    for (file in files) {
        val table = try {
            Csv.parseDelimitedFull(file.readText())
        } catch (_: Throwable) {
            continue // 故意构造的畸形样本（空表 / 只有表头）不计入对拍
        }
        if (table.rows.isEmpty()) continue
        val dataset: Dataset = datasetFromParsed(file.name, 1, table)
        val tier1 = InMemoryEngine(dataset)
        val tier2 = DuckDbEngine.fromRows(table.columns, table.rows)
        try {
            // ---- 聚合对拍：用默认维度列 + 默认数值列（与工具实际用法一致）----
            val defaults = com.stragent.core.dataset.pickDefaultColumns(dataset)
            total += 1
            if (defaults.category >= 0 && defaults.value >= 0) {
                val a = tier1.aggregateBy(defaults.category, defaults.value, "sum")
                val b = tier2.aggregateBy(defaults.category, defaults.value, "sum")
                val diff = compareAggregates(a, b)
                if (diff.isEmpty()) passed += 1 else failures.add("${file.name} 聚合: $diff")
            } else {
                passed += 1 // 没有可用的维度/数值列，本文件不构成聚合用例
            }

            // ---- 列画像对拍：逐列 ----
            table.columns.indices.forEach { columnIndex ->
                total += 1
                val x = tier1.profileColumn(columnIndex)
                val y = tier2.profileColumn(columnIndex)
                val diff = compareProfile(x, y)
                if (diff.isEmpty()) passed += 1 else failures.add("${file.name} 列「${table.columns[columnIndex]}」: $diff")
            }
            comparedSamples += 1
        } finally {
            tier2.close()
        }
    }

    return SuiteResult(
        name = "engine-parity",
        passed = passed,
        total = total,
        failures = failures.take(12),
        notes = "tier-1（内存）与 tier-2（DuckDB）对拍：$comparedSamples 个样本 CSV，" +
            "聚合与列画像逐项比对（字符串/计数精确，数值相对容差 1e-9）。",
    )
}

/** 聚合结果比对：标签与计数精确，数值与占比相对容差 1e-9 */
private fun compareAggregates(
    a: List<com.stragent.core.dataset.AggregateItem>,
    b: List<com.stragent.core.dataset.AggregateItem>,
): String {
    if (a.size != b.size) return "条目数 ${a.size} vs ${b.size}"
    for (i in a.indices) {
        val x = a[i]
        val y = b[i]
        if (x.label != y.label) return "第 $i 条标签「${x.label}」vs「${y.label}」"
        if (x.count != y.count) return "第 $i 条(${x.label}) 计数 ${x.count} vs ${y.count}"
        if (!closeEnough(x.value, y.value)) return "第 $i 条(${x.label}) 数值 ${x.value} vs ${y.value}"
        if (!closeEnough(x.share, y.share)) return "第 $i 条(${x.label}) 占比 ${x.share} vs ${y.share}"
    }
    return ""
}

/** 列画像比对 */
private fun compareProfile(x: ColumnStat?, y: ColumnStat?): String {
    if (x == null || y == null) return "画像为 null（${x == null} vs ${y == null}）"
    if (x.type != y.type) return "列类型 ${x.type} vs ${y.type}"
    if (x.count != y.count) return "count ${x.count} vs ${y.count}"
    if (x.missing != y.missing) return "missing ${x.missing} vs ${y.missing}"
    if (x.distinct != y.distinct) return "distinct ${x.distinct} vs ${y.distinct}"
    if (!closeEnough(x.min, y.min)) return "min ${x.min} vs ${y.min}"
    if (!closeEnough(x.max, y.max)) return "max ${x.max} vs ${y.max}"
    if (!closeEnough(x.sum, y.sum)) return "sum ${x.sum} vs ${y.sum}"
    if (!closeEnough(x.mean, y.mean)) return "mean ${x.mean} vs ${y.mean}"
    if (!closeEnough(x.median, y.median)) return "median ${x.median} vs ${y.median}"
    if (!closeEnough(x.p25, y.p25)) return "p25 ${x.p25} vs ${y.p25}"
    if (!closeEnough(x.p75, y.p75)) return "p75 ${x.p75} vs ${y.p75}"
    val xt = x.topValues.orEmpty()
    val yt = y.topValues.orEmpty()
    if (xt.size != yt.size) return "topValues 条数 ${xt.size} vs ${yt.size}"
    for (i in xt.indices) {
        if (xt[i].value != yt[i].value) return "topValues[$i] ${xt[i].value} vs ${yt[i].value}"
        if (xt[i].count != yt[i].count) return "topValues[$i] 计数 ${xt[i].count} vs ${yt[i].count}"
    }
    return ""
}

/** 相对容差 1e-9（与既有契约门禁同口径）；null 只与 null 相等 */
private fun closeEnough(a: Double?, b: Double?, tolerance: Double = 1e-9): Boolean {
    if (a == null || b == null) return a == null && b == null
    if (a.isNaN() || b.isNaN()) return a.isNaN() && b.isNaN()
    val scale = maxOf(1.0, maxOf(kotlin.math.abs(a), kotlin.math.abs(b)))
    return kotlin.math.abs(a - b) <= tolerance * scale
}
