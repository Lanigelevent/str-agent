package com.stragent.core.report

/**
 * 写作质量确定性审计 —— 与 `verifyReport`（数字溯源）并排的第二道确定性闸。
 *
 * 为什么需要它：数字有 verifyReport 逐数溯源把关，写作质量此前没有任何机器可查的
 * 门禁（bench 七套件全是数字口径）——「去 AI 味」「专业成文规范」只活在提示词里，
 * 写没写对没人验证。本审计把提示词纪律里**机械可查**的部分落成代码：
 *  ① 套话黑名单（[BANNED_WRITING_RULES]，与 [humanWritingBlock] 提示词同一份词表）；
 *  ② 结构块覆盖（结构化类型的速览表 / 适用范围 / 未答问题 / 依据标注——
 *     对应 [professionalStructureBlock] 的四段要求）；
 *  ③ 篇幅偏离（实际字数 vs 目标篇幅的合理区间）。
 *
 * 与退稿循环的关系（T-032 定下的语义）：
 *  - **硬**：套话命中 ≥ 3 处 → 参与退稿（与未溯源数字同级，确定性、无 judge 偏差）；
 *  - **软**：缺结构块 / 篇幅偏离 → 只进退稿意见与质量注记，不单独触发退稿
 *    （体裁允许省略结构块的场景存在，例如短体型报告本就不注入 structure 块）。
 */
object WritingAudit {

    /** 触发退稿的套话命中数下限（1–2 处可能是引用/反例，3 处起判违反纪律） */
    const val BANNED_HARD_THRESHOLD = 3

    /** 篇幅合理区间（实际 / 目标）：低于 50% 稀释了信息密度，高于 200% 违背篇幅纪律 */
    const val LENGTH_RATIO_FLOOR = 0.5
    const val LENGTH_RATIO_CEILING = 2.0

    data class Issue(val kind: String, val detail: String)

    data class Result(
        val issues: List<Issue>,
        /** 套话命中总处数 */
        val bannedHits: Int,
        /** 是否达到退稿级（套话 ≥ [BANNED_HARD_THRESHOLD]） */
        val hard: Boolean,
        /** 实际字数（与 countChars 同口径：去空白） */
        val actualChars: Int,
    )

    fun audit(
        report: String,
        targetChars: Int?,
        structured: Boolean,
        language: ReportLanguage = ReportLanguage.ZH,
    ): Result {
        val issues = mutableListOf<Issue>()
        val markers = structureMarkers(language)

        // ① 套话黑名单（按语言取词表——中文词表查英文报告等于没查）
        var bannedHits = 0
        for (line in report.lines()) {
            for (rule in bannedRules(language)) {
                if (rule.pattern.containsMatchIn(line)) {
                    bannedHits += 1
                    issues.add(Issue("套话", "命中黑名单「${rule.label}」：${line.trim().take(60)}"))
                }
            }
        }

        // ② 结构块覆盖（仅结构化类型要求；短体型本就不注入该提示词，不检查）
        if (structured) {
            if (!report.contains(markers.fullPeriodValue) || !report.contains(markers.peakLocation)) {
                issues.add(
                    Issue(
                        "缺结构块",
                        "速览表缺失：应有「指标 | ${markers.fullPeriodValue} | ${markers.peakLocation} | 变化或占比」四列的 Markdown 表",
                    ),
                )
            }
            if (!report.contains(markers.scope)) {
                issues.add(
                    Issue(
                        "缺结构块",
                        "「${markers.scope}」段缺失：判定对象 / 前提条件 / 不覆盖的部分",
                    ),
                )
            }
            if (!report.contains(markers.openQuestions)) {
                issues.add(
                    Issue(
                        "缺结构块",
                        "「## ${markers.openQuestions}」章缺失：应列 3–5 条",
                    ),
                )
            }
            if (!report.contains(markers.sourcePrefix) && !report.contains(markers.sourcePrefix.trimEnd('：', ':'))) {
                issues.add(Issue("缺结构块", "关键数字后未见（${markers.sourcePrefix}XX）来源标注"))
            }
        }

        // ③ 篇幅偏离
        val actualChars = report.replace(Regex("\\s"), "").length
        if (targetChars != null && targetChars > 0) {
            val ratio = actualChars.toDouble() / targetChars
            if (ratio < LENGTH_RATIO_FLOOR) {
                issues.add(
                    Issue("篇幅", "实际 $actualChars 字，不足目标 $targetChars 字的 ${LENGTH_RATIO_FLOOR.toInt() * 100}%——信息密度可能被稀释或发现被砍"),
                )
            } else if (ratio > LENGTH_RATIO_CEILING) {
                issues.add(
                    Issue("篇幅", "实际 $actualChars 字，超过目标 $targetChars 字的 ${LENGTH_RATIO_CEILING.toInt() * 100}%——存在注水或重复"),
                )
            }
        }

        return Result(issues = issues, bannedHits = bannedHits, hard = bannedHits >= BANNED_HARD_THRESHOLD, actualChars = actualChars)
    }
}
