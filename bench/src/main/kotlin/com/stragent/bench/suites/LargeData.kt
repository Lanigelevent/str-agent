package com.stragent.bench.suites

import com.stragent.bench.model.SuiteResult
import com.stragent.engine.duckdb.DuckDbEngine
import java.io.File

/**
 * 套件六 · large-data（规模门禁）—— D10 阶段 1 的验收项。
 *
 * 验收口径（D10 §阶段1）：**500 万行 CSV 聚合 < 2s**。
 *
 * 为什么这道门重要：
 *   tier-1 的 10K 硬帽是「规模」维度的天花板，tier-2 就是为拆掉它而存在。
 *   但规模不是「能跑就行」—— DuckDB 读 500 万行的速度决定了这条路线在真实业务里
 *   是否可用；而且 **tier-2 读的是文件而不是内存**，这正是它存在的意义。
 *
 * 口径：
 *  - 合成 CSV 完全确定性（分类 = i % 7，数值 = 确定性公式），真值在生成时同步算出；
 *  - tier-2 用 `fromCsvFile` 直接读文件（read_csv），全程不把数据搬进 JVM 堆；
 *  - 断言：分组标签与计数精确相等、数值相对容差 1e-9；聚合耗时设硬门（2s）。
 *
 * 行数可用 `-Dbench.largeData.rows=N` 覆盖（默认 500 万）。
 */
fun runLargeData(): SuiteResult {
    if (!DuckDbEngine.isAvailable()) {
        return SuiteResult(
            name = "large-data",
            passed = 0,
            total = 0,
            notes = "跳过：DuckDB 原生库不可用。",
        )
    }
    val rowCount = System.getProperty("bench.largeData.rows")?.toIntOrNull() ?: 5_000_000
    val groupCount = 7

    // ---- 1. 生成确定性 CSV，同时算出真值 ----
    val file = File.createTempFile("str-agent-large-data-", ".csv")
    file.deleteOnExit()
    val truthSums = DoubleArray(groupCount)
    val truthCounts = LongArray(groupCount)
    val writeStart = System.nanoTime()
    file.bufferedWriter(Charsets.UTF_8).use { writer ->
        writer.write("id,category,value\n")
        for (i in 0 until rowCount) {
            val category = i % groupCount
            val value = ((i * 37) % 1000) * 1.5 + 0.5
            truthSums[category] += value
            truthCounts[category] += 1
            writer.write("$i,分类$category,$value\n")
        }
    }
    val writeMs = (System.nanoTime() - writeStart) / 1_000_000

    // ---- 2. tier-2 读文件 + 聚合 ----
    var passed = 0
    var total = 0
    val failures = mutableListOf<String>()
    var loadMs = 0L
    var aggregateMs = 0L
    var observedRows = -1
    val loadStart = System.nanoTime()

    DuckDbEngine.fromCsvFile(file).use { engine ->
        // 注意：读文件耗时必须包住 fromCsvFile（DuckDB 的 CSV 扫描与建表都在里面发生）
        loadMs = (System.nanoTime() - loadStart) / 1_000_000
        observedRows = engine.rowCount

        val aggStart = System.nanoTime()
        val items = engine.aggregateBy(1, 2, "sum", topN = groupCount)
        aggregateMs = (System.nanoTime() - aggStart) / 1_000_000

        // 行数一致
        total += 1
        if (observedRows == rowCount) passed += 1 else failures.add("行数 $observedRows != $rowCount")

        // 逐分组真值一致
        for (g in 0 until groupCount) {
            total += 1
            val expectedLabel = "分类$g"
            val actual = items.firstOrNull { it.label == expectedLabel }
            if (actual == null) {
                failures.add("缺少分组 $expectedLabel")
                continue
            }
            val expectedSum = truthSums[g]
            val expectedCount = truthCounts[g].toInt()
            val ok = actual.count == expectedCount &&
                kotlin.math.abs(actual.value - expectedSum) <= 1e-9 * maxOf(1.0, kotlin.math.abs(expectedSum))
            if (ok) passed += 1 else failures.add("$expectedLabel: count=${actual.count}/$expectedCount value=${actual.value}/$expectedSum")
        }

        // 耗时硬门（D10 验收：500 万行聚合 < 2s）
        total += 1
        if (aggregateMs < 2000) passed += 1 else failures.add("聚合耗时 ${aggregateMs}ms ≥ 2000ms")
    }
    file.delete()

    // ---- 场景二：超高基数维度（每个维度值唯一）—— 验证 topN 下沉 SQL 后不再有分组数硬顶 ----
    val highCardinality = runHighCardinalityCase()

    return SuiteResult(
        name = "large-data",
        passed = passed + highCardinality.passed,
        total = total + highCardinality.total,
        failures = (failures + highCardinality.failures).take(12),
        notes = "合成 $rowCount 行 CSV：生成 ${writeMs}ms，tier-2 读入 ${loadMs}ms，" +
            "聚合 ${aggregateMs}ms（门限 2000ms），真值逐分组比对（相对容差 1e-9）。" +
            "另含超高基数场景：${highCardinality.notes}",
    )
}

/**
 * 超高基数维度场景：100 万行、**每个维度值唯一**。
 *
 * 验证点：topN 与「其他 N 项」下沉 SQL 后，百万级分组不再触发分组数上限，
 * 且 top 值、其余汇总值、组数都与解析解一致。
 */
private fun runHighCardinalityCase(): SuiteResult {
    val rows = 1_000_000
    val topN = 8
    var passed = 0
    var total = 0
    val failures = mutableListOf<String>()

    val file = File.createTempFile("str-agent-high-cardinality-", ".csv")
    file.deleteOnExit()
    file.bufferedWriter(Charsets.UTF_8).use { writer ->
        writer.write("id,category,value\n")
        for (i in 0 until rows) {
            // 每行一个唯一维度值；数值随行号递增 → 最大的几组在末尾，真值可解析求出
            writer.write("$i,cat_$i,$i\n")
        }
    }

    var aggregateMs = 0L
    DuckDbEngine.fromCsvFile(file).use { engine ->
        val start = System.nanoTime()
        val items = engine.aggregateBy(1, 2, "sum", topN = topN)
        aggregateMs = (System.nanoTime() - start) / 1_000_000

        // ① 条目数 = topN + 「其他 N 项」
        total += 1
        if (items.size == topN + 1) passed += 1 else failures.add("条目数 ${items.size} != ${topN + 1}")

        // ② 最大的一组（数值随行号递增 → 最后一行最大）
        total += 1
        val top = items.firstOrNull()
        if (top != null && top.label == "cat_${rows - 1}" && top.value == (rows - 1).toDouble()) {
            passed += 1
        } else {
            failures.add("top 组不对：${top?.label}/${top?.value}")
        }

        // ③ 「其他 N 项」的组数与汇总值（其余组的值之和 = 0..rows-1 去掉最大的 topN 个）
        total += 1
        val rest = items.lastOrNull()
        val expectedRestGroups = rows - topN
        val expectedRestSum = (0 until rows - topN).sumOf { it.toDouble() }
        if (rest != null && rest.label == "其他 $expectedRestGroups 项" &&
            kotlin.math.abs(rest.value - expectedRestSum) <= 1e-9 * kotlin.math.abs(expectedRestSum)
        ) {
            passed += 1
        } else {
            failures.add("其余汇总不对：${rest?.label}/${rest?.value}")
        }

        // ④ 耗时硬门（topN 场景 < 2s）
        total += 1
        if (aggregateMs < 2000) passed += 1 else failures.add("超高基数聚合耗时 ${aggregateMs}ms ≥ 2000ms")
    }
    file.delete()

    return SuiteResult(
        name = "high-cardinality",
        passed = passed,
        total = total,
        failures = failures.take(6),
        notes = "$rows 行 / 维度值全唯一，topN=$topN 聚合 ${aggregateMs}ms（门限 2000ms）。",
    )
}
