package com.stragent.core.report

/**
 * 报告输出语言档位（T-173 · D22 §6 P2-8）。
 *
 * 定位：**中文优先不变**（D19/D21 的语言定位），本档位是「调用方显式指定才切换」，
 * 不是默认行为。切换的不只是「让模型换个语种写」——门禁也必须跟着换：
 * 套话黑名单与结构块标记都是**按语言**查的，否则中文黑名单对英文报告一条都查不出来，
 * 结构块检查会因为找不到中文标记而永远报「缺失」——那不是门禁，那是噪声。
 *
 * 两条纪律：
 *  1. **每种语言一套词表**：黑名单（[bannedRules]）与结构块标记（[structureMarkers]）
 *     都按语言给；换语言必须同时换词表（漏一处 = 门禁对另一种语言失效）。
 *  2. **数字门禁与语言无关**：`verifyReport` 的数字溯源不依赖语种，切换语言不影响它。
 */
enum class ReportLanguage(val id: String, val label: String) {
    ZH("zh", "中文"),  // lint:allow zh-first（用户面中文文案/结构标记，与语言档位同源）
    EN("en", "English"),
    ;

    companion object {
        /** 偏好键（与其它输出适配项同处一张 prefs map） */
        const val PREF_KEY = "report.language"

        /** 解析调用方给的语言 id；缺省/未知 → 中文（不静默换语种） */
        fun from(id: String?): ReportLanguage =
            when (id?.trim()?.lowercase()) {
                "en", "en-us", "en-gb", "english" -> EN
                else -> ZH
            }
    }
}

/**
 * 语言指令（注入提示词的最前部）。
 *
 * 只说「用什么语言写」，不替模型写内容——写作纪律与结构规范各自有自己的语言版本。
 */
fun languageDirective(language: ReportLanguage): String = when (language) {
    ReportLanguage.ZH ->
        "<report_language>全文用**中文**写作。列名、指标名、原文引用、代码与专有名词保留原样不翻译。</report_language>"  // lint:allow zh-first（用户面中文文案/结构标记，与语言档位同源）
    ReportLanguage.EN ->
        "<report_language>Write the **entire report in English**. Keep column names, metric names, " +
            "quoted source text, code and proper nouns exactly as they appear in the data (do not translate them).</report_language>"
}

/**
 * 结构块标记（写作审计按语言查）。
 *
 * 这些标记必须与提示词里要求模型写的**章节名/列名逐字一致**——否则审计查不到，
 * 结构块覆盖检查形同虚设。改这里就要同步改 [professionalStructureBlock]。
 */
data class StructureMarkers(
    /** 速览表：全期值列 */
    val fullPeriodValue: String,
    /** 速览表：最值定位列 */
    val peakLocation: String,
    /** 适用范围段 */
    val scope: String,
    /** 未答问题章 */
    val openQuestions: String,
    /** 依据标注前缀 */
    val sourcePrefix: String,
)

fun structureMarkers(language: ReportLanguage): StructureMarkers = when (language) {
    ReportLanguage.ZH -> StructureMarkers(
        fullPeriodValue = "全期值",  // lint:allow zh-first（用户面中文文案/结构标记，与语言档位同源）
        peakLocation = "最值定位",  // lint:allow zh-first（用户面中文文案/结构标记，与语言档位同源）
        scope = "适用范围",  // lint:allow zh-first（用户面中文文案/结构标记，与语言档位同源）
        openQuestions = "数据未能回答的问题",  // lint:allow zh-first（用户面中文文案/结构标记，与语言档位同源）
        sourcePrefix = "依据：",  // lint:allow zh-first（用户面中文文案/结构标记，与语言档位同源）
    )
    ReportLanguage.EN -> StructureMarkers(
        fullPeriodValue = "Full-period value",
        peakLocation = "Peak location",
        scope = "Scope",
        openQuestions = "Questions the data cannot answer",
        sourcePrefix = "Source:",
    )
}

/**
 * 套话黑名单（按语言）。
 *
 * ZH 是既有的中文词表（[BANNED_WRITING_RULES]，与中文提示词逐条对得上，有 drift-guard）；
 * EN 是同款 AI 腔的英文词表——**不是翻译**，是按英文的实际套话形态另写的
 * （例如中文的「综上所述」对应英文的 "To sum up" / "In conclusion"）。
 */
fun bannedRules(language: ReportLanguage): List<BannedWritingRule> = when (language) {
    ReportLanguage.ZH -> BANNED_WRITING_RULES
    ReportLanguage.EN -> BANNED_WRITING_RULES_EN
}

/** 英文套话黑名单（与 [humanWritingBlock] 的英文版逐条对得上，有 drift-guard） */
val BANNED_WRITING_RULES_EN: List<BannedWritingRule> = listOf(
    // 开头套话
    BannedWritingRule("In today's … era", Regex("In today'?s[^.\\n]{0,24}(era|world|landscape)")),
    BannedWritingRule("It is well known that", Regex("It is (well known|widely known|widely acknowledged) that")),
    BannedWritingRule("In recent years,", Regex("In recent years,")),
    BannedWritingRule("With the rapid development of", Regex("With the (rapid|continuous|ongoing) development of")),
    // 过渡套话
    BannedWritingRule("To sum up", Regex("To (sum up|conclude|summarize)")),
    BannedWritingRule("It is worth noting that", Regex("It is worth noting that")),
    BannedWritingRule("It should be pointed out that", Regex("It (should|needs to) be pointed out that")),
    BannedWritingRule("It is not difficult to find that", Regex("It is not difficult to (find|see) that")),
    BannedWritingRule("As can be seen", Regex("As (can be seen|we can see|shown above)")),
    BannedWritingRule("In other words", Regex("In other words")),
    // 空洞形容
    BannedWritingRule("significantly improved", Regex("significantly (improved|enhanced|boosted)")),
    BannedWritingRule("crucial", Regex("\\bcrucial\\b")),
    BannedWritingRule("profound impact", Regex("profound impact")),
    BannedWritingRule("strong support", Regex("strong(ly)? (support|backing)")),
    BannedWritingRule("empower", Regex("\\bempower(s|ed|ing)?\\b")),
    BannedWritingRule("leverage", Regex("\\bleverage(s|d|ing)?\\b")),
    BannedWritingRule("closed loop", Regex("closed[- ]loop")),
    BannedWritingRule("consolidate", Regex("\\bconsolidat(e|es|ed|ing|ion)\\b")),
    BannedWritingRule("facilitate", Regex("\\bfacilitat(e|es|ed|ing)\\b")),
    // 模板句式
    BannedWritingRule(
        "First, … Second, … Third, … 串段",  // lint:allow zh-first（用户面中文文案/结构标记，与语言档位同源）
        Regex("First,[^\\n]{4,}Second,[^\\n]{4,}Third,"),
        "First, / Second, / Third,",
    ),
    // 翻译腔
    BannedWritingRule("conducted an analysis of", Regex("conducted an? (analysis|study|review) of")),
    BannedWritingRule("aims to", Regex("\\baims? to\\b")),
    BannedWritingRule("laid a solid foundation", Regex("laid a (solid|strong) foundation")),
)
