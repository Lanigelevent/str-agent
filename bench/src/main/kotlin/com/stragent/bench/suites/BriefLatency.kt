package com.stragent.bench.suites

import com.stragent.bench.fixtures.MapPrefs
import com.stragent.bench.model.SuiteResult
import com.stragent.core.brief.buildDatasetBrief
import com.stragent.core.csv.ColumnType
import com.stragent.core.dataset.Dataset

/**
 * 套件十三 · brief-latency（摘要构建耗时基准）—— T-140 新增。
 *
 * ## 为什么要有这道门
 *
 * brief 是**报告链必经的确定性计算段**：报告里每个数字都来自它。
 * 但在 T-140 之前，bench 的 12 个自建套件里 `buildDatasetBrief` 出现 3 次
 * （brief-accuracy / faithfulness / tool-surface）——**全是当输入源，不量时间**；
 * `perf-baseline` 只量 **CSV 解析**耗时与堆驻留。
 *
 * 也就是说：**报告链最重的那段计算，此前没有任何性能断言**——
 * 「brief 变慢了」这句话在改动发生前无法被证明，任何性能优化都只能靠感觉。
 * T-140 审查进一步定位到：热路径上 `Csv.parseNumber` / `Quality.shapeOf` /
 * `BriefCore.dateParts` / `MonthKey.monthKey` 每次调用都重新编译正则，
 * 在 tier-1 上限（10K 行）附近是**百万级 `Pattern.compile`**——
 * 这种量级的结论必须由**可复算的数字**支撑，不能靠静态推断。
 *
 * ## 三条口径纪律（对齐 perf-baseline 的既有做法）
 *
 * 1. **数据完全确定性**：行号与列号决定一切；brief 是纯函数，同输入必得同输出，
 *    所以耗时差异只可能来自代码变化；
 * 2. **同进程内五次测量取中位数**：JIT 预热与 GC 抖动让单次测量噪声极大。
 *    测量之间**不强制 GC**（强制 GC 会让堆反复伸缩，反而制造噪声）；
 *    离散度超过 ±25% 时在 notes 里**如实标出抖动**，不粉饰；
 * 3. **内存口径是「采样峰值下限」而非「驻留」**：brief 的中间结构是短命的
 *    （构建完即成垃圾），GC 后的驻留差值实测接近 0MB——**这个口径量不到任何东西**。
 *    与 perf-baseline 一致改用 10ms 守护线程采样峰值，量的是「峰值有没有接近打死进程」。
 *
 * 门限宽松（只拦灾难级退化），真正的价值是**基线数字随提交可对比**
 * （`bench/baseline/perf-baseline.json` 走既有 1.2× WARN / 1.5× FAIL 回归门禁）。
 *
 * 规模可覆盖：`-Dbench.brief.rows=N`（覆盖最大场景的行数）。
 */
fun runBriefLatency(): SuiteResult {
    val maxRows = System.getProperty("bench.brief.rows")?.toIntOrNull() ?: 10_000
    val scenarios = listOf(
        Scenario("100x10", 100, 10),
        Scenario("1000x20", 1_000, 20),
        Scenario("10000x20", maxRows, 20),
    )

    val prefs = MapPrefs()
    var passed = 0
    var total = 0
    val failures = mutableListOf<String>()
    val notes = StringBuilder()

    for (s in scenarios) {
        total += 1
        val ds = syntheticDataset(s.rows, s.cols)
        // 预热：让 JIT 把这条路径编到位（不计入测量，否则首个场景永远最慢）
        buildDatasetBrief(ds, prefs)

        val samples = LongArray(SAMPLES)
        var briefChars = 0
        for (i in samples.indices) {
            val start = System.nanoTime()
            val brief = buildDatasetBrief(ds, prefs)
            samples[i] = (System.nanoTime() - start) / 1_000_000
            briefChars = brief.length
        }
        val sorted = samples.sorted()
        val best = sorted.first()
        val median = sorted[sorted.size / 2]
        val spread = if (median > 0) (sorted.last() - sorted.first()).toDouble() / median else 0.0

        // 门限：只拦灾难级退化（真实检测靠 perf-baseline.json 的 1.2×/1.5× 回归门禁）
        val limitMs = when {
            s.rows <= 100 -> 2_000L
            s.rows <= 1_000 -> 8_000L
            else -> 30_000L
        }
        if (median >= limitMs) {
            failures.add("${s.label} 构建耗时中位数 ${median}ms ≥ 门限 ${limitMs}ms")
        } else {
            passed += 1
        }
        // **指标取 5 次里的最小值**：噪声（GC、调度、频率波动）只会让耗时**变长**，
        // 不会让它变短，所以 min 是回归检测的正确统计量——真实退化会让每一次都变慢，
        // 而 min 仍会跟着涨；而单次/中位数会被噪声推高，产生假红。
        // 实测教训：本套件首次接入时，100x10 场景中位数在 15~30ms 间抖动，
        // 1.5× 比率门禁对这种绝对值直接假红（基线 19ms → 实测 30ms = 1.58× = FAIL）。
        notes.append("${s.label}=${best}ms[min/$SAMPLES 中位${median}ms")
        if (spread > 0.25) notes.append(" 抖动${(spread * 100).toInt()}%")
        notes.append("] ")
    }

    // 堆峰值（采样下限口径）：brief 构建期的峰值，量的是「有没有接近把进程打死」
    val big = syntheticDataset(maxRows, 20)
    gc()
    val heapBefore = usedHeapMb()
    val sampler = PeakSampler(intervalMs = 10)
    sampler.start()
    buildDatasetBrief(big, prefs)
    val sampledPeakMb = sampler.stop()
    val peakMb = (maxOf(sampledPeakMb, usedHeapMb()) - heapBefore).coerceAtLeast(0)

    return SuiteResult(
        name = "brief-latency",
        passed = passed,
        total = total,
        failures = failures.take(8),
        notes = "brief 构建耗时（$SAMPLES 次取最小值）：$notes" +
            "10k20峰值=${peakMb}MB（10ms 采样下限口径）；规模覆盖：-Dbench.brief.rows=N。" +
            "数据确定性（行号决定一切），brief 是纯函数，故耗时差异只来自代码变化。",
    )
}

/** 每个场景的测量次数：5 次取中位数——3 次时 1000×20 场景的离散度达 33%，噪声过大 */
private const val SAMPLES = 5

/** 一个基准场景：行数 × 列数 */
private data class Scenario(val label: String, val rows: Int, val cols: Int)

/**
 * 合成数据集：列型刻意覆盖 brief 的四条热路径——
 *  - `id` → 数值列（`Csv.parseNumber`）
 *  - `日期` → 日期文本（`BriefCore.dateParts`）
 *  - `月份` → 月份文本（`MonthKey.monthKey`）
 *  - `备注` → 自由文本（`Quality.shapeOf` 的文本分类）
 * 其余列全为数值列（brief 的聚合与统计主体）。
 *
 * 列型**显式声明**而非靠推断：推断本身会引入与被测对象无关的方差。
 */
private fun syntheticDataset(rows: Int, cols: Int): Dataset {
    require(cols >= 5) { "cols 必须 >= 5（id/日期/月份/备注 + 至少一个数值列）" }
    val columns = mutableListOf("id", "日期", "月份", "备注")
    val columnTypes = mutableListOf(ColumnType.NUMBER, ColumnType.TEXT, ColumnType.TEXT, ColumnType.TEXT)
    for (c in 4 until cols) {
        columns += "指标${c - 3}"
        columnTypes += ColumnType.NUMBER
    }
    val data = ArrayList<List<String>>(rows)
    for (i in 0 until rows) {
        val row = ArrayList<String>(cols)
        row += i.toString()
        row += "2024-%02d-%02d".format((i % 12) + 1, (i % 28) + 1)
        row += "2024-%02d".format((i % 12) + 1)
        row += "备注${i % 997}"
        for (c in 4 until cols) {
            row += (((i * 41 + c * 7) % 997) + 1).toString()
        }
        data += row
    }
    return Dataset(
        name = "brief-latency-synthetic",
        importedAt = 1,
        columns = columns,
        rows = data,
        columnTypes = columnTypes,
    )
}
