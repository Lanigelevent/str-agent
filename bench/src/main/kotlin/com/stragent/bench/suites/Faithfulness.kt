package com.stragent.bench.suites

import com.stragent.bench.fixtures.MapPrefs
import com.stragent.bench.fixtures.findFixtures
import com.stragent.bench.model.SuiteResult
import com.stragent.core.brief.buildDatasetBrief
import com.stragent.core.stats.datasetOf
import com.stragent.core.verify.countHardIssues
import com.stragent.core.verify.verifyReport
import java.io.File

/**
 * 套件三 · faithfulness（可信度 / 数字溯源门禁）
 *
 * 复用既有校验器 `verifyReport` + `countHardIssues`（代码校验器，非 LLM judge）。
 * 以 superstore 为基准数据集，构造正/负样本：
 *   - 正样本：报告中每个数字都能在摘要里找到依据 → 期望 0 硬伤（precision 侧）
 *   - 负样本：注入编造数 / 算错算式 / 说反方向 / 幻觉字段 → 期望被抓出（recall 侧）
 *
 * 这是 str-agent 相对通用数据分析 Agent 的差异化壁垒，本套件量化其「精确率 + 召回率」。
 */
private data class FaithCase(val tag: String, val report: String, val expectClean: Boolean)

fun runFaithfulness(): SuiteResult {
    val fx = findFixtures()
    val sampleDir = File(fx, "sample").absolutePath
    val brief = buildDatasetBrief(datasetOf("superstore", sampleDir), MapPrefs())

    val cases = listOf(
        // —— 正样本（期望 0 硬伤；均来自 VerifyGateTest 已验证「0 unsourced / 0 arithmetic」的字符串）——
        FaithCase("pos-clean-summary", "Sales 合计 2,297,201.08 元，中位数 54.49，Consumer 占 50.6%，1,161,401.52 元。", true),
        FaithCase("pos-rounded", "均值约 230，中位数约 54。", true),
        FaithCase("pos-arithmetic-ok", "占比 50.6%（算式：1,161,401.52 ÷ 2,297,201.08 = 0.5056）。", true),
        // —— 负样本（期望被抓，>=1 硬伤；均来自 VerifyGateTest 已验证会被拦截的场景）——
        FaithCase("neg-fabricated", "销售额合计 9,999,999.99 元，共 12345 单。", false),
        FaithCase("neg-relation-wrong", "West 区销售额是 South 区的 3.5 倍。", false),
        FaithCase("neg-direction-wrong", "Consumer 的单组均值高于 Corporate。", false),
        FaithCase("neg-field-hallucination", "按 Customer Level 列分组可见明显分层。", false),
        FaithCase("neg-arithmetic-wrong", "占比 80%（算式：1,161,401.52 ÷ 2,297,201.08 = 0.8）。", false),
    )

    var passed = 0
    val failures = mutableListOf<String>()
    for (c in cases) {
        val issues = countHardIssues(verifyReport(c.report, brief))
        val clean = issues == 0
        val ok = if (c.expectClean) clean else !clean
        if (ok) passed += 1 else failures.add("${c.tag}: 期望 clean=${c.expectClean} 实际 hardIssues=$issues")
    }

    val posTotal = cases.count { it.expectClean }
    val negTotal = cases.size - posTotal
    val posPass = cases.filter { it.expectClean }.count { c ->
        countHardIssues(verifyReport(c.report, brief)) == 0
    }
    val negPass = cases.filter { !it.expectClean }.count { c ->
        countHardIssues(verifyReport(c.report, brief)) >= 1
    }
    val precision = if (posTotal == 0) 1.0 else posPass.toDouble() / posTotal
    val recall = if (negTotal == 0) 1.0 else negPass.toDouble() / negTotal

    return SuiteResult(
        name = "faithfulness",
        passed = passed,
        total = cases.size,
        failures = failures,
        notes = "verifyReport 校验器（代码校验，非 LLM judge）。" +
            "精确率(正样本全过)=${"%.0f".format(precision * 100)}% · " +
            "召回率(负样本全抓)=${"%.0f".format(recall * 100)}%（基准数据集 superstore）。",
    )
}
