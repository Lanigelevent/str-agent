package com.stragent.bench

import com.stragent.bench.external.ExternalBench
import com.stragent.bench.external.ExternalDab
import com.stragent.bench.external.ExternalRegression
import com.stragent.bench.model.BenchScorecard
import com.stragent.bench.suites.runBriefLatency
import com.stragent.bench.suites.runConcurrencyBaseline
import com.stragent.bench.suites.runBriefAccuracy
import com.stragent.bench.suites.runEngineParity
import com.stragent.bench.suites.runFaithfulness
import com.stragent.bench.suites.runLargeData
import com.stragent.bench.suites.runNumericAccuracy
import com.stragent.bench.suites.runPerfBaseline
import com.stragent.bench.suites.runRetrievalLatency
import com.stragent.bench.suites.runHedgeLatency
import com.stragent.bench.suites.runTextPipeline
import com.stragent.bench.suites.runToolSurface
import com.stragent.bench.suites.runWritingQuality
import java.io.File

/**
 * 评测基座主入口（D10 阶段 0 基座 + 阶段 1 双轨门禁）。
 *
 * 运行：gradle --offline :bench:benchmark
 * 产物：bench/build/reports/benchmark-<ts>.md + .json，并打印控制台摘要。
 * 退出码：任一硬失败**或性能回归 FAIL** → 1（供 CI 门禁拦截）；全过 → 0。
 *
 * D15 T12: 支持 --update-baseline 参数，覆盖性能基线文件。
 */
fun main(args: Array<String>) {
    val updateBaseline = args.contains("--update-baseline")
    val outDir = args.filter { it != "--update-baseline" }.firstOrNull()?.let { File(it) } ?: File("build/reports")
    outDir.mkdirs()

    // T-057 外部 SQL 基准：数据不在时显式 SKIP；在时按「相对基线下降 >5%」判定。
    val externalRun = ExternalBench().run()
    val externalVerdict = if (externalRun.datasetPresent && !externalRun.suite.skipped) {
        ExternalRegression().check(
            passed = externalRun.suite.passed,
            total = externalRun.suite.total,
            knownFailures = externalRun.knownFailures,
            updateBaseline = updateBaseline,
        )
    } else {
        null
    }
    if (externalVerdict != null) {
        if (externalVerdict.warnings.isNotEmpty()) {
            println("== 外部基准 WARN ==")
            externalVerdict.warnings.forEach { println("  ⚠ $it") }
        }
        if (externalVerdict.failures.isNotEmpty()) {
            println("== 外部基准 FAIL ==")
            externalVerdict.failures.forEach { println("  ✗ $it") }
        }
    }
    val externalSuite = externalVerdict?.let { verdict ->
        externalRun.suite.copy(
            gatePassed = verdict.ok,
            failures = externalRun.suite.failures + verdict.failures,
        )
    } ?: externalRun.suite

    val suites = listOf(
        runNumericAccuracy(),
        runBriefAccuracy(),
        runFaithfulness(),
        runToolSurface(),
        // 阶段 1 双轨门禁：数字口径对拍（engine-parity）+ 规模门禁（large-data）
        runEngineParity(),
        runLargeData(),
        // T-006 性能基准：100K 行解析耗时 + 堆驻留（帽子外的规模到底多贵）
        runPerfBaseline(),
        // T-032 写作质量门禁：WritingAudit 确定性审计（套话/结构块/篇幅 + 提示词 drift-guard）
        runWritingQuality(),
        // T-075 MCP 并发压测基线：4/16/64 并发 × 快查+长任务 stub 的 P50/P95/错误率/QPS（宽门禁）
        runConcurrencyBaseline(updateBaseline),
        // T-131 检索时延基线：固定大材料冷/热追问（语义等价断言 + 时延 notes 可比）
        runRetrievalLatency(),
        // T-137 对冲基线：尾延迟改善 + 成本上界（本地桩只证机制，真实 P99 待真实端点）
        runHedgeLatency(),
        // T-096 文本管线确定性基线：分块/检索/要点/门禁/编排五层离线断言
        runTextPipeline(),
        // T-140 brief 构建耗时基准：报告链最重的那段计算，此前没有任何性能断言
        runBriefLatency(),
        // T-057 外部 SQL 基准：Spider 2.0-Lite SQLite 子集（数据缺省时 SKIP）
        externalSuite,
        // T-186 DAB 子集 + DABench-RLM：三维执行正确性（数据缺省显式 SKIP，同 Spider 口径）
        ExternalDab().run().suite,
    )

    // D15 T12: 性能回归基线对比（基线缺失时首跑自动写入）
    val perfResult = PerfRegression().check(
        current = buildPerfMetrics(suites),
        updateBaseline = updateBaseline,
    )
    if (perfResult.warnings.isNotEmpty()) {
        println("== 性能回归 WARN ==")
        perfResult.warnings.forEach { println("  ⚠ $it") }
    }
    if (perfResult.failures.isNotEmpty()) {
        println("== 性能回归 FAIL ==")
        perfResult.failures.forEach { println("  ✗ $it") }
    }
    if (updateBaseline && perfResult.ok) {
        println("✔ 性能基线已更新：${PerfRegression.defaultBaselineFile().absolutePath}")
    }

    val card = BenchScorecard(ReportWriter.nowStamp(), suites)

    println(card.renderConsole())
    ReportWriter.write(card, outDir)
    println("报告已写入：${outDir.absolutePath}")

    if (shouldFail(card.ok, perfResult.ok)) {
        // 评测基座：任何套件硬失败或性能回归 FAIL 都必须非零退出，作为 CI 质量门禁。
        // 历史坑：曾只判 card.ok，性能回归只打印 FAIL 横幅却退出 0——「打印了 FAIL 却放行」
        // 比不检查更危险，看日志的人会以为门禁拦住了。
        System.exit(1)
    }
}

/**
 * 退出码口径（可单测）：套件硬失败 **或** 性能回归 FAIL，二者任一都必须让门禁转红。
 *
 * 拆成纯函数是为了让「FAIL 是否真的拦」有测试可钉；`main` 里的 `System.exit` 不适合直接测。
 */
internal fun shouldFail(scorecardOk: Boolean, perfOk: Boolean): Boolean = !scorecardOk || !perfOk

/**
 * D15 T12 + T-140：从套件 notes 中解析性能指标，构造 PerfRegression.Metric 列表。
 *
 * 两个来源：
 *  - `perf-baseline`：CSV **解析**侧的耗时与内存；
 *  - `brief-latency`（T-140 新增）：**报告链最重的计算段**（brief 构建）的耗时与驻留。
 *
 * 解析失败时用宽松上限兜底，但**绝不伪造「无退化」**——宁可报一个偏大的数，
 * 也不能让「取不到数」被当成「没变慢」。
 */
private fun buildPerfMetrics(suites: List<com.stragent.bench.model.SuiteResult>): List<PerfRegression.Metric> {
    val metrics = mutableListOf<PerfRegression.Metric>()

    // ---- perf-baseline：CSV 解析侧 ----
    suites.find { it.name == "perf-baseline" }?.let { perfSuite ->
        val notes = perfSuite.notes

        // 解析耗时：notes 中包含 "tier-1 完整解析 ${parseMs}ms"
        val parseMs = Regex("tier-1 完整解析 (\\d+)ms").find(notes)?.groupValues?.get(1)?.toLongOrNull() ?: 3000L
        metrics += PerfRegression.Metric(
            name = "perf-baseline-tier1-parse",
            baselineMs = 0,
            currentMs = parseMs,
        )

        // 驻留内存：notes 中包含 "解析后堆驻留 ${residentMb}MB"
        val residentMb = Regex("解析后堆驻留 (\\d+)MB").find(notes)?.groupValues?.get(1)?.toLongOrNull() ?: 500L
        metrics += PerfRegression.Metric(
            name = "perf-baseline-tier1-resident",
            baselineMs = 0,
            currentMs = 0,
            currentMemoryMb = residentMb,
        )

        // 采样峰值：notes 中包含 "解析期采样峰值下限约 ${peakDuringParseMb}MB"
        val peakMb = Regex("解析期采样峰值下限约 (\\d+)MB").find(notes)?.groupValues?.get(1)?.toLongOrNull() ?: 1500L
        metrics += PerfRegression.Metric(
            name = "perf-baseline-tier1-peak",
            baselineMs = 0,
            currentMs = 0,
            currentMemoryMb = peakMb,
        )
    }

    // ---- brief-latency（T-140）：brief 构建耗时 + 峰值 ----
    suites.find { it.name == "brief-latency" }?.let { briefSuite ->
        val notes = briefSuite.notes
        // notes 形如 "1000x20=141ms[min/5 中位146ms] 10000x20=1562ms[min/5 ...] 10k20峰值=305MB"
        for ((label, metricName) in listOf(
            "1000x20" to "brief-latency-1000x20",
            "10000x20" to "brief-latency-10000x20",
        )) {
            val ms = Regex(Regex.escape(label) + "=(\\d+)ms").find(notes)?.groupValues?.get(1)?.toLongOrNull()
            // 绝对值太小的场景**不进回归基线**：比率门禁（1.2× WARN / 1.5× FAIL）是相对的，
            // 对一个本身就在 12~14ms 间摆动的场景，1.5× 阈值只有 ~7ms 余量 → 必假红。
            // 实测教训：100x10 场景 min-of-5 仍有 ±17% 波动，基线 14ms 时 FAIL 阈值 21ms。
            // 小规模场景仍然留在套件自己的硬门限与 notes 里（可见），只是不承担回归判定。
            if (ms != null && ms >= MIN_REGRESSION_BASELINE_MS) {
                metrics += PerfRegression.Metric(name = metricName, baselineMs = 0, currentMs = ms)
            }
        }
        // 峰值（采样下限口径）：notes 中包含 "10k20峰值=${peakMb}MB"
        val briefPeakMb = Regex("10k20峰值=(\\d+)MB").find(notes)?.groupValues?.get(1)?.toLongOrNull()
        if (briefPeakMb != null) {
            metrics += PerfRegression.Metric(
                name = "brief-latency-10000x20-peak",
                baselineMs = 0,
                currentMs = 0,
                currentMemoryMb = briefPeakMb,
            )
        }
    }

    return metrics
}

/**
 * 进入性能回归基线所需的最小耗时（ms）。
 *
 * 比率门禁是相对的，噪声也是相对的：实测 abs 越小、相对噪声越大。
 * 低于此值的场景不进回归基线（仍在套件 notes 与硬门限里可见）。
 */
private const val MIN_REGRESSION_BASELINE_MS = 50L
