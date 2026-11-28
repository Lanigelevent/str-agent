package com.stragent.bench

import com.stragent.bench.model.BenchScorecard
import com.stragent.bench.model.SuiteResult
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 把评分卡落盘为 Markdown 报告 + JSON（供 CI 解析）。
 */
object ReportWriter {
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")

    fun write(card: BenchScorecard, outDir: File) {
        outDir.mkdirs()
        val ts = card.timestamp
        val md = File(outDir, "benchmark-$ts.md")
        val json = File(outDir, "benchmark-$ts.json")
        md.writeText(renderMarkdown(card))
        json.writeText(renderJson(card))
    }

    private fun renderMarkdown(card: BenchScorecard): String = buildString {
        appendLine("# str-agent 评测基座报告（D10 阶段 0 基座 + 阶段 1 双轨门禁）")
        appendLine()
        appendLine("> 生成时间：${card.timestamp}  ·  结果：**${if (card.ok) "ALL PASS" else "REGRESSION"}**")
        appendLine()
        appendLine("## 总览")
        appendLine()
        appendLine("| 套件 | 通过 | 总数 | 通过率 | 结果 |")
        appendLine("|---|---|---|---|---|")
        for (s in card.suites) {
            val mark = when {
                s.skipped -> "⏭ SKIP"
                s.ok -> "✅ PASS"
                else -> "❌ FAIL"
            }
            appendLine("| ${s.name} | ${s.passed} | ${s.total} | ${s.ratioPct()} | $mark |")
        }
        appendLine("| **合计** | **${card.totalPassed}** | **${card.totalCases}** | " +
            "**${"%.1f".format(if (card.totalCases == 0) 100.0 else card.totalPassed * 100.0 / card.totalCases)}%** | " +
            "**${if (card.ok) "✅" else "❌"}** |")
        appendLine()
        for (s in card.suites) {
            appendLine("## ${s.name}")
            appendLine()
            appendLine(s.notes)
            appendLine()
            if (s.failures.isNotEmpty()) {
                appendLine("失败明细（最多 12 条）：")
                appendLine()
                s.failures.take(12).forEach { appendLine("- $it") }
                appendLine()
            }
        }
        appendLine("---")
        appendLine("_由 `bench/` 模块离线生成，无 LLM 依赖。数值精度/摘要精度来自 fixtures/baseline 金标；" +
            "可信度来自 verifyReport 代码校验器；工具面来自 buildAnalysisTools 单一事实源；" +
            "双轨一致性来自 tier-1/tier-2 逐样本对拍（engine-parity），规模来自 500 万行合成数据门禁（large-data）。_")
    }

    private fun renderJson(card: BenchScorecard): String {
        fun SuiteResult.json(): String = """{
  "name": "$name",
  "passed": $passed,
  "total": $total,
  "ratio": ${"%.4f".format(ratio)},
  "ok": $ok,
  "skipped": $skipped,
  "notes": "${notes.replace("\"", "'")}",
  "failures": [${failures.joinToString { "\"${it.replace("\"", "'")}\"" }}]
}"""
        return """{
  "timestamp": "${card.timestamp}",
  "ok": ${card.ok},
  "totalPassed": ${card.totalPassed},
  "totalCases": ${card.totalCases},
  "suites": [
${card.suites.joinToString(",\n") { "    " + it.json() }}
  ]
}"""
    }

    fun nowStamp(): String = LocalDateTime.now().format(fmt)
}
