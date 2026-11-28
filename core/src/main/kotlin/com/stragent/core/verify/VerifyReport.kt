package com.stragent.core.verify

/**
 * verifyReport 入口 + 隐含算式 —— report-verify.ts 尾段移植。
 */
import kotlin.math.abs

data class NumberIssue(val value: String, val context: String, val kind: String)

data class ArithmeticError(val context: String, val claimed: String, val expected: Double)

data class RelationIssue(val context: String, val claimed: String, val expected: String, val pair: String)

data class FieldIssue(val field: String, val context: String)

data class VerificationResult(
    val checked: Int,
    val unsourced: List<NumberIssue>,
    val formula: List<NumberIssue>,
    val arithmetic: List<ArithmeticError>,
    val relations: List<RelationIssue>,
    val fields: List<FieldIssue>,
    val briefNumbers: Int,
)

// ---------- 隐含算式 ----------

private val OPTIONAL_DATE = "(?:(?:19|20)\\d{2}[-/.]\\d{1,2}(?:[-/.]\\d{1,2})?\\s*的?\\s*)?"

private val IMPLIED_RATIO = Regex(
    "([\\d,]+(?:\\.\\d+)?)\\s*(?:万元|亿元|元)?\\s*(?:是|为)" +
        "[^，。；\\d]{0,6}\\s*([\\d,]+(?:\\.\\d+)?)\\s*(?:万元|亿元|元)?\\s*的\\s*([\\d.]+)\\s*倍",
)

private val IMPLIED_FROM_TO = Regex(
    "从\\s*$OPTIONAL_DATE([\\d,]+(?:\\.\\d+)?)[^。；]{0,24}?(?:到|至)\\s*" +
        "$OPTIONAL_DATE([\\d,]+(?:\\.\\d+)?)[^。；]{0,30}?" +
        "(增长|为|是|涨幅|回升)\\s*([\\d.]+)\\s*(倍|%)",
)

// 逐行/逐数字的热路径正则：编译一次复用（门禁是报告主链，T-044 要求 p95 增量 <50ms）
private val LINE_SPLIT = Regex("\\r\\n|\\r|\\n")
private val HEADING_LINE = Regex("^#{1,6}\\s")
private val TABLE_SEPARATOR = Regex("^\\|?[\\s:|-]+\\|?$")

private data class ImpliedCheck(val claimed: Double, val expected: Double, val pattern: String)

private fun checkImplied(line: String, briefSet: Set<Double>): List<ImpliedCheck> {
    val out = mutableListOf<ImpliedCheck>()
    fun push(a: Double, b: Double, z: Double, expected: Double, pattern: String) {
        if (a == 0.0) return
        if (!hitsBrief(a, briefSet) || !hitsBrief(b, briefSet)) return
        out.add(ImpliedCheck(z, expected, pattern))
    }

    val ratio = IMPLIED_RATIO.find(line)
    if (ratio != null) {
        val a = normalize(ratio.groupValues[1])
        val b = normalize(ratio.groupValues[2])
        val z = normalize(ratio.groupValues[3])
        if (a != null && b != null && z != null) push(a, b, z, a / b, "A 是 B 的 Z 倍")
    }

    val fromTo = IMPLIED_FROM_TO.find(line)
    if (fromTo != null) {
        val a = normalize(fromTo.groupValues[1])
        val b = normalize(fromTo.groupValues[2])
        val verb = fromTo.groupValues[3]
        val z = normalize(fromTo.groupValues[4])
        val unit = fromTo.groupValues[5]
        if (a != null && b != null && z != null) {
            when {
                unit == "%" -> push(a, b, z, (b - a) / a * 100, "从 A 到 B 的百分比变化")
                verb == "增长" -> push(a, b, z, (b - a) / a, "从 A 到 B 增长 Z 倍")
                else -> push(a, b, z, b / a, "从 A 到 B 为 Z 倍")
            }
        }
    }
    return out
}

// ---------- 入口 ----------

fun verifyReport(report: String, brief: String): VerificationResult {
    // 自然值白名单（T-051）：百分比按实际值（50.6% → 0.506），数量级已展开。
    // 只用「自然值相等」一条口径：50.6% 与 0.506 互认，0.506% 不会被 50.6% 背书。
    // 不再把裸数 X 当作 X% 的替身——摘要里任何一个 ≤100 的计数都会替任意 X% 背书。
    val briefNatural = mutableSetOf<Double>()
    val briefSetLegacy = mutableSetOf<Double>()
    for (item in extractNaturalNumbers(citableBriefText(brief))) {
        briefNatural.add(item.value)
    }
    for (item in extractNumbers(citableBriefText(brief))) {
        briefSetLegacy.add(item.value)
        // 旧口径保留给隐含算式：百分比与小数两种写法都收
        if (item.value > 0 && item.value < 1) briefSetLegacy.add(jsRound6(item.value * 100))
        if (item.value > 1 && item.value < 100) briefSetLegacy.add(jsRound8(item.value / 100))
    }

    fun hitsBriefToken(token: NaturalToken): Boolean = hitsBrief(token.value, briefNatural)

    val unsourced = mutableListOf<NumberIssue>()
    val formula = mutableListOf<NumberIssue>()
    val arithmetic = mutableListOf<ArithmeticError>()
    val relations = mutableListOf<RelationIssue>()
    val fields = mutableListOf<FieldIssue>()
    var checked = 0

    val fieldNames = extractFields(brief)
    val labelValues = extractLabelValues(brief)

    val authorized = mutableSetOf<Double>()
    fun authorize(value: Double) {
        authorized.add(value)
        if (value > 0 && value < 1) authorized.add(jsRound8(value * 100))
        if (value > 1 && value < 100) authorized.add(jsRound8(value / 100))
    }

    fun trusted(token: NaturalToken): Boolean = hitsBriefToken(token) || authorized.contains(token.value)

    for (rawLine in report.split(LINE_SPLIT)) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        if (HEADING_LINE.containsMatchIn(line) || TABLE_SEPARATOR.matches(line)) continue
        val inFormula = isFormulaLine(line)
        val checks = if (inFormula) checkArithmeticAll(line).sortedBy { it.range.first } else emptyList()
        for (check in checks) {
            if (!check.matched) {
                arithmetic.add(
                    ArithmeticError(
                        if (line.length > 100) line.take(100) + "…" else line,
                        check.claimed.raw,
                        jsRound2(check.expected),
                    ),
                )
            } else if (check.operands.all { trusted(it) }) {
                // 只有「验算通过且两个操作数都有依据」才授权结果（T-051）。
                // 旧口径把公式行整行数字都授权，编造数可借自洽算式洗白。
                authorize(check.claimed.value)
            }
        }
        if (!isHypothetical(line) && !isFormatExample(line)) {
            relations.addAll(checkRelations(line, labelValues).map { RelationIssue(it.context, it.claimed, it.expected, it.pair) })
            fields.addAll(checkFields(line, fieldNames).map { FieldIssue(it.field, it.context) })
        }
        if (isHypothetical(line) || isFormatExample(line)) continue

        // 隐含算式（在 unsourced 分类之前——验算通过的先进 authorized）
        for (implied in checkImplied(line, briefSetLegacy)) {
            val close = abs(implied.claimed - implied.expected) <= maxOf(tolerance(implied.expected), 0.06)
            if (close) {
                authorize(implied.claimed)
            } else {
                arithmetic.add(
                    ArithmeticError(
                        if (line.length > 100) line.take(100) + "…" else line,
                        jsNumToString(implied.claimed),
                        implied.expected,
                    ),
                )
            }
        }

        for (item in extractNaturalNumbers(line)) {
            if (isStructural(item.raw, line, item.value, inFormula)) continue
            checked += 1
            if (hitsBriefToken(item)) continue
            if (authorized.contains(item.value)) continue
            val issue = NumberIssue(
                value = item.raw,
                context = if (line.length > 80) line.take(80) + "…" else line,
                kind = if (inFormula) "formula" else "unsourced",
            )
            if (inFormula) formula.add(issue) else unsourced.add(issue)
        }
    }

    return VerificationResult(checked, unsourced, formula, arithmetic, relations, fields, briefNatural.size)
}

/** JS Number(x.toFixed(6)) */
private fun jsRound6(v: Double): Double =
    java.math.BigDecimal(v).setScale(6, java.math.RoundingMode.HALF_UP).toPlainString().toDouble()

private fun jsRound8(v: Double): Double =
    java.math.BigDecimal(v).setScale(8, java.math.RoundingMode.HALF_UP).toPlainString().toDouble()

/** JS String(number)：整数不带小数点 */
private fun jsNumToString(v: Double): String {
    val s = java.math.BigDecimal(v).stripTrailingZeros().toPlainString()
    return if (s.endsWith(".0")) s.dropLast(2) else s
}

/** 给人看的一行结论 */
fun describeVerification(result: VerificationResult): String {
    val bad = result.unsourced.size + result.formula.size + result.arithmetic.size + result.relations.size + result.fields.size
    if (bad == 0) return "已核对 ${result.checked} 个数字，全部能在数据摘要里找到依据"
    val parts = mutableListOf("${result.checked} 个数字中")
    if (result.arithmetic.isNotEmpty()) parts.add("${result.arithmetic.size} 处算式算不通")
    if (result.relations.isNotEmpty()) parts.add("${result.relations.size} 处关系算不通")
    if (result.unsourced.isNotEmpty()) parts.add("${result.unsourced.size} 个在摘要里找不到")
    if (result.formula.isNotEmpty()) parts.add("${result.formula.size} 个在算式里待确认")
    if (result.fields.isNotEmpty()) parts.add("${result.fields.size} 个数据里没有的字段名")
    return parts.joinToString("，")
}

/** 确定有问题的地方合计 */
fun countHardIssues(result: VerificationResult): Int =
    result.arithmetic.size + result.relations.size + result.unsourced.size + result.fields.size
