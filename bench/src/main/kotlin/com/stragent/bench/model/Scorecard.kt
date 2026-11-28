package com.stragent.bench.model

/**
 * 单个评测套件的结果。
 *
 * @param name       套件名（numeric-accuracy / brief-accuracy / faithfulness / tool-surface）
 * @param passed     通过项数
 * @param total      总项数
 * @param failures   失败项明细（最多保留若干，便于定位）
 * @param notes      该套件的口径/说明（写入报告）
 * @param skipped    依赖外部数据且数据未准备时的显式跳过：不伪造通过，也不把「没跑」判成失败
 * @param gatePassed 覆盖默认门禁口径（passed==total）。外部基准按「相对基线下降 >5%」判定，
 *                   只有它能把「少数题失败但未超阈值」判为通过；报告里的 passed/total 仍是真实计数。
 */
data class SuiteResult(
    val name: String,
    val passed: Int,
    val total: Int,
    val failures: List<String> = emptyList(),
    val notes: String = "",
    val skipped: Boolean = false,
    val gatePassed: Boolean? = null,
) {
    val ratio: Double get() = if (total == 0) 1.0 else passed.toDouble() / total
    val ok: Boolean get() = skipped || (gatePassed ?: (passed == total))
    fun ratioPct(): String = "%.1f%%".format(ratio * 100)
}

/**
 * 评测基座总评分卡：聚合四个套件，给出整体是否通过（任一硬失败即不通过）。
 */
class BenchScorecard(
    val timestamp: String,
    val suites: List<SuiteResult>,
) {
    val ok: Boolean get() = suites.all { it.ok }
    val totalPassed: Int get() = suites.sumOf { it.passed }
    val totalCases: Int get() = suites.sumOf { it.total }

    /** 控制台摘要 */
    fun renderConsole(): String = buildString {
        appendLine("╔══════════════════════════════════════════════════════════╗")
        appendLine("  str-agent 评测基座 · D10 阶段 0 · $timestamp")
        appendLine("╠══════════════════════════════════════════════════════════╣")
        for (s in suites) {
            val mark = when {
                s.skipped -> "SKIP"
                s.ok -> "PASS"
                else -> "FAIL"
            }
            appendLine("  [$mark] ${s.name.padEnd(18)} ${s.passed}/${s.total}  (${s.ratioPct()})")
            if (s.skipped && s.notes.isNotEmpty()) {
                appendLine("        └ ${s.notes.lineSequence().first()}")
            }
            if (s.failures.isNotEmpty()) {
                s.failures.take(5).forEach { appendLine("        └ $it") }
            }
        }
        appendLine("╠══════════════════════════════════════════════════════════╣")
        val total = if (totalCases == 0) 1.0 else totalPassed.toDouble() / totalCases
        appendLine("  合计 ${totalPassed}/${totalCases}  (%.1f%%)  ·  %s".format(total * 100, if (ok) "ALL PASS ✅" else "REGRESSION ❌"))
        appendLine("╚══════════════════════════════════════════════════════════╝")
    }
}
