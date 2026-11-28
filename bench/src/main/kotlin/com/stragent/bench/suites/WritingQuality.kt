package com.stragent.bench.suites

import com.stragent.bench.model.SuiteResult
import com.stragent.core.report.BANNED_WRITING_RULES
import com.stragent.core.report.WritingAudit
import com.stragent.core.report.humanWritingBlock

/**
 * 套件八 · writing-quality（写作质量确定性门禁，T-032）
 *
 * 数字可信度有 faithfulness 套件守着，写作质量此前没有任何门禁——「去 AI 味」
 * 「专业成文规范」只活在提示词里，写没写对没人验证。本套件量化 `WritingAudit`
 * （与 verifyReport 并排的第二道确定性闸）的**精确率 + 召回率**：
 *   - 召回侧：各类套话样例必须命中；结构块缺失 / 篇幅偏离必须被发现；
 *   - 精确侧：合规样本零误报；硬闸阈值（<3 处不退稿）不许过激；
 *   - **drift-guard**：黑名单每一条都要能在 humanWritingBlock() 提示词里找到锚点
 *     ——审计词表与提示词词表是同一份纪律的两个面，单边漂移测试即红。
 */
private data class WritingCase(
    val tag: String,
    val report: String,
    val targetChars: Int?,
    val structured: Boolean,
    val expectHard: Boolean,
    val expectBannedAtLeast: Int = 0,
    val expectIssueKind: String? = null,
)

/** 一份四段齐全、无套话的合规样本（结构与 professionalStructureBlock 的要求逐条对应） */
private val CLEAN_STRUCTURED_REPORT = """
    ## 总体概览
    Sales 合计 2,297,201.08 元（依据：Sales 列全量统计），Consumer 贡献 1,161,401.52 元最高。
    增长集中在 2015 年下半年，2014 年各月基本持平。先看区域结构，再看月度节奏。

    | 指标 | 全期值 | 最值定位 | 变化或占比 |
    |---|---|---|---|
    | Sales | 2,297,201.08 | Consumer，全期 | (1,161,401.52 ÷ 2,297,201.08) = +50.6% |
    | Orders | 9,929 | Consumer，全期 | 占一半订单量 |

    ## 适用范围
    判定对象：全部 9,929 条订单记录；前提条件：销售额未剔除任何极值；不覆盖的部分：利润与折扣的关系（本数据没有成本字段）。

    ## 数据未能回答的问题
    - 没有成本字段，算不了利润率，也判断不了「销售额高是不是靠打折换来的」。
    - 没有客户 ID 去重口径，复购分析做不了。
""".trimIndent()

fun runWritingQuality(): SuiteResult {
    var passed = 0
    val failures = mutableListOf<String>()
    fun check(tag: String, ok: Boolean, detail: String = "") {
        if (ok) passed += 1 else failures.add("$tag: $detail")
    }

    // —— 召回侧：各类套话必须命中 ——
    val recallCases = listOf(
        WritingCase("neg-cliche-opening", "随着市场的不断发展，销售额持续增长。", null, false, false, 1),
        WritingCase("neg-cliche-transition", "综上所述，销售额表现良好。", null, false, false, 1),
        WritingCase("neg-cliche-empty", "该方案赋能商家抓手闭环，为增长保驾护航，具有深远影响。", null, false, true, 3),
        WritingCase("neg-translationese-3hits", "我们对数据进行了分析，实现了稳定增长，旨在说明问题。", null, false, true, 3),
    )
    for (c in recallCases) {
        val r = WritingAudit.audit(c.report, c.targetChars, c.structured)
        check("${c.tag}-banned", r.bannedHits >= c.expectBannedAtLeast, "期望套话 ≥${c.expectBannedAtLeast}，实际 ${r.bannedHits}")
        check("${c.tag}-hard", r.hard == c.expectHard, "期望 hard=${c.expectHard}，实际 ${r.hard}（命中 ${r.bannedHits}）")
    }

    // —— 精确侧：合规样本零误报（无套话、结构块齐全、篇幅贴合） ——
    run {
        val target = CLEAN_STRUCTURED_REPORT.replace(Regex("\\s"), "").length
        val r = WritingAudit.audit(CLEAN_STRUCTURED_REPORT, target, structured = true)
        check("pos-clean-no-banned", r.bannedHits == 0, "合规样本不该命中套话：${r.issues}")
        check("pos-clean-no-issues", r.issues.isEmpty(), "合规样本零注记：${r.issues}")
        check("pos-clean-not-hard", !r.hard)
    }

    // —— 结构块：结构化类型缺块必须报，短体型不检查 ——
    run {
        val bare = "## 概览\nSales 合计 2,297,201.08 元。\n"
        val structured = WritingAudit.audit(bare, null, structured = true)
        check("neg-missing-blocks", structured.issues.any { it.kind == "缺结构块" }, "结构化类型缺块应报：${structured.issues}")
        val unstructured = WritingAudit.audit(bare, null, structured = false)
        check("pos-shorttype-skip", unstructured.issues.none { it.kind == "缺结构块" }, "短体型不查结构块：${unstructured.issues}")
    }

    // —— 篇幅：双向偏离都要报，区间内不报 ——
    run {
        val short = WritingAudit.audit("内容很短。", 1000, structured = false)
        check("neg-too-short", short.issues.any { it.kind == "篇幅" }, "篇幅过短应报：${short.issues}")
        val longText = ("这一段只是把已知信息换个说法再讲一遍。" ).repeat(60)
        val long = WritingAudit.audit(longText, 300, structured = false)
        check("neg-too-long", long.issues.any { it.kind == "篇幅" }, "篇幅超标应报：${long.issues}")
        val fitted = WritingAudit.audit("x".repeat(600), 1000, structured = false)
        check("pos-length-ok", fitted.issues.none { it.kind == "篇幅" }, "区间内不应报：${fitted.issues}")
    }

    // —— 硬闸阈值：2 处放行、3 处退稿（防过激） ——
    run {
        val two = WritingAudit.audit("综上所述，表现良好。\n总而言之，还算平稳。", null, structured = false)
        check("pos-two-hits-not-hard", !two.hard, "2 处不该触发退稿级：${two.hard}")
        val three = WritingAudit.audit("综上所述，好。\n总而言之，稳。\n值得注意的是，高。", null, structured = false)
        check("neg-three-hits-hard", three.hard, "3 处应触发退稿级")
    }

    // —— drift-guard：审计词表与提示词词表逐条对得上（单边漂移即红） ——
    val promptText = humanWritingBlock()
    for (rule in BANNED_WRITING_RULES) {
        check(
            "drift-${rule.label}",
            promptText.contains(rule.promptAnchor),
            "黑名单「${rule.label}」在 humanWritingBlock() 里找不到锚点「${rule.promptAnchor}」——审计与提示词必须同步改",
        )
    }

    return SuiteResult(
        name = "writing-quality",
        passed = passed,
        total = passed + failures.size,
        failures = failures.take(10),
        notes = "WritingAudit 确定性写作审计：套话召回 + 合规零误报 + 结构块/篇幅 + 硬闸阈值 + 黑名单 drift-guard（${BANNED_WRITING_RULES.size} 条）",
    )
}
