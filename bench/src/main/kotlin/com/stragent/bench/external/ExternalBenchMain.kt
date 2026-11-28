package com.stragent.bench.external

import kotlin.system.exitProcess

/**
 * 单独运行外部 SQL 基准（CI 在数据集可用时可直接跑这一步，不必跑全部 8 套件）。
 *
 * 用法：
 *   gradle :bench:externalBench
 *   gradle :bench:externalBench -Pbench.updateBaseline
 *   STRAGENT_BENCH_EXTERNAL_DIR=/data/spider2-lite gradle :bench:externalBench
 *
 * 退出码：外部基准下降超过阈值 / 数据损坏 / 基线异常 → 1；数据缺省 SKIP → 0。
 */
fun main(args: Array<String>) {
    val updateBaseline = args.contains("--update-baseline")
    val run = ExternalBench().run()
    val suite = run.suite

    println("== ${suite.name} ==")
    println("  ${if (suite.skipped) "SKIP" else if (suite.ok) "PASS" else "FAIL"} " +
        "${suite.passed}/${suite.total} (${suite.ratioPct()})")
    suite.failures.forEach { println("  ✗ $it") }
    if (suite.notes.isNotEmpty()) println("  ${suite.notes}")

    if (!run.datasetPresent || suite.skipped) {
        // 数据没准备是「没跑」，不是「失败」；但也不能假装跑过。
        exitProcess(if (suite.ok) 0 else 1)
    }

    val verdict = ExternalRegression().check(
        passed = suite.passed,
        total = suite.total,
        knownFailures = run.knownFailures,
        updateBaseline = updateBaseline,
    )
    verdict.warnings.forEach { println("  ⚠ $it") }
    verdict.failures.forEach { println("  ✗ $it") }
    // 外部基准按「相对基线下降 >5%」判定；不要求 100% 通过。
    exitProcess(if (verdict.ok) 0 else 1)
}
