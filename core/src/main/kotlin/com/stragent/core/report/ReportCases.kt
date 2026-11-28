package com.stragent.core.report

/**
 * 真实案例库 —— report-cases.ts 的逻辑移植（数据在 ReportCasesData，由脚本生成）。
 *
 * 注入的是范式，不是案例原文——原文是别人的数据，照抄只会诱导模型编造数字。
 */
data class RealCase(val source: String, val url: String, val rules: List<String>)

private fun KtCase(c: ReportCasesData.KtRealCase): RealCase = RealCase(c.source, c.url, c.rules)

/** 取某个行业大类可用的案例（没有就不注入，不编造） */
fun industryCases(category: String): List<RealCase> =
    ReportCasesData.industryCasesMap[category]?.map { KtCase(it) } ?: emptyList()

/** 取某个报告类型可用的案例 */
fun typeCases(typeId: String): RealCase? = ReportCasesData.typeCasesMap[typeId]?.let { KtCase(it) }

/**
 * 把案例渲染成 prompt 块。
 *
 * 只渲染**有内容**的部分：没搜到真实案例的行业 / 类型就不出现这个标签，
 * 而不是塞一段"本行业无案例"——空标签只会稀释注意力。
 */
fun realExamplesBlock(industryCategory: String, typeId: String, withStyle: Boolean = true): String {
    val all = buildList {
        addAll(ReportCasesData.generalCases.map { KtCase(it) })
        if (withStyle) addAll(ReportCasesData.styleCases.map { KtCase(it) })
        addAll(industryCases(industryCategory))
        typeCases(typeId)?.let { add(it) }
    }
    if (all.isEmpty()) return ""

    val lines = all.flatMap { item -> listOf("【${item.source}】") + item.rules.map { rule -> "· $rule" } }
    return "\n<real_examples>以下是**真实行业报告**的写作范式，来自公开实践。" +
        "学它们的表述方式与判定逻辑，数字一律用你这份数据里的，不要照抄例子中的数。\n" +
        lines.joinToString("\n") + "\n</real_examples>"
}
