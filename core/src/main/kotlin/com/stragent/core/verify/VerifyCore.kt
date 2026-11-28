package com.stragent.core.verify

/**
 * report-verify.ts 的工具层移植：归一化 / 数字抽取 / 容差 / 豁免 / 算式验算。
 *
 * 口径纪律（每条都有实测教训，见 TS 源注释）：
 *  - extractNumbers 的日期前向查（前一个字符是数字/斜杠/连字符 → 日期片段跳过）
 *  - 容差按数量级分档（小数 0.5%、中数 0.1%、大数 0.05%）+ 精度单调判据
 *  - 假设句只在数字 ≤2 个时整行豁免；格式举例只认具体的脏数据指示词
 *  - decimalsOf 用字符串（避开浮点取模误差）
 */
import kotlin.math.abs

internal val SCALE = mapOf("万亿" to 1e12, "千亿" to 1e11, "万" to 1e4, "亿" to 1e8, "千" to 1e3, "百万" to 1e6)

// 热路径正则统一预编译：这些函数在每行/每个数字上执行，门禁在主链上（T-044）
private val NORMALIZE_SPACES = Regex("[,，\\s]")
private val NORMALIZE_PERCENT = Regex("[%％]$")
private val NORMALIZE_PLUS = Regex("^[+＋]")
private val NUMERIC_TEXT = Regex("^-?\\d*\\.?\\d+$")
private val NUMBER_NEIGHBOR = Regex("[\\d/\\-]")
private val SAMPLE_MARKER = Regex("\\n\\s*前\\s*\\d+\\s*行样本\\s*[：:]")
private val HYPOTHETICAL_MARKER = Regex("假设|若该|如果是|假如|例如|比如|打个比方|设 .{0,8} 为|待查")
private val FORMAT_EXAMPLE_MARKER = Regex("写法|书写|混着|两种.{0,8}(负号|格式|写法|符号)|解析不出|非数值|文本型|千分位|百分号|记为|写作")
private val LABEL2_PATTERN = Regex("^-\\s*([^：:]+)[：:]")
private val STRUCT_YEAR = Regex("^[12]\\d{3}$")
private val STRUCT_TIME_WORD = Regex("年|Q[1-4]|月|日期|期间|基期")
private val STRUCT_ORDER_HEAD = Regex("^(#{1,6}\\s*)?\\d+[.、)）]?\\s*$")
private val STRUCT_PERCENT_WORD = Regex("分|满分")
private val STRUCT_PAREN = Regex("^\\d+\\)$")
private val THOUSAND_SEPARATOR = Regex("[,，]")
private val BRIEF_FIELD_LINE = Regex("^-\\s*([^（(]+)[（(](?:数值|文本|标识列)")
private val FIELD_QUOTE_PATTERNS = listOf(
    Regex("「([^」]{1,20})」"),
    Regex("\\b([A-Za-z][A-Za-z0-9 _-]{1,24})\\s*(?:列|字段)"),
)
private val NON_ASCII = Regex("[^\\x20-\\x7E]")
private val FORMULA_LINE = Regex("[=＝]|÷|×|\\/\\s*\\d|相加|合计为|除以|之和|减去|乘以")

/**
 * 全角/Unicode 变体归一到 ASCII（T-185：数字门禁的写体旁路）。
 *
 * 探针实测的四个隐身口子（core/verify/AdversarialVerifyTest 钉住）：
 *  - `１２３` 全角数字：`\d` 默认不匹配 → 整段数字**无任何 token**，编造数可直穿；
 *  - `1，000` 全角逗号：token 正则只认 ASCII 逗号 → 被拆成 `1` 和 `000`，白名单对不上；
 *  - `－1000` / `−1000` 全角/Unicode 减号：符号丢失，正负 claim 被当成同一个数；
 *  - `1.2万亿` / `3千亿`：复合量级被截成「万/千」→ 值差 10^8 倍，大数 claim 可被小数白名单背书。
 */
private fun asciiNumeric(raw: String): String {
    val sb = StringBuilder(raw.length)
    for (ch in raw) {
        sb.append(
            when (ch) {
                in '\uFF10'..'\uFF19' -> ('0'.code + (ch.code - 0xFF10)).toChar()
                '％' -> '%'
                '＋' -> '+'
                '－', '\uFF0D', '\u2212' -> '-'
                else -> ch
            },
        )
    }
    return sb.toString()
}

/** 归一化：去千分位、去百分号、去单位前后缀，转数值 */
internal fun normalize(raw: String): Double? {
    val text = asciiNumeric(raw)
        .replace(NORMALIZE_SPACES, "")
        .replace(NORMALIZE_PERCENT, "")
        .replace(NORMALIZE_PLUS, "")
    if (!NUMERIC_TEXT.matches(text)) return null
    val value = text.toDoubleOrNull() ?: return null
    return if (value.isFinite()) value else null
}

internal data class NumToken(val raw: String, val value: Double)

/**
 * 自然值 token（T-051）：百分比按**实际值**存（`50.6%` → 0.506），数量级已展开。
 *
 * 与 [NumToken]/[extractNumbers] 的区别：后者把 `50.6%` 存成 50.6（历史口径，供摘要白名单
 * 与审稿器复用）。校验报告时必须区分「50.6%」与「0.506%」——两者自然值差 100 倍，
 * 若都按裸数比较，前者会替后者背书。
 */
internal data class NaturalToken(val raw: String, val value: Double, val percent: Boolean, val start: Int, val end: Int)

/** 解析中文数字（含 点 小数、十百千万亿、两）；失败返回 null */
internal fun parseChineseNumber(text: String): Double? {
    if (text.isEmpty()) return null
    val dot = text.indexOf('点')
    val intText = if (dot == -1) text else text.substring(0, dot)
    val fracText = if (dot == -1) "" else text.substring(dot + 1)
    val intPart = parseChineseInteger(intText) ?: return null
    if (dot == -1) return intPart
    if (fracText.isEmpty() || fracText.any { CHINESE_DIGITS[it] == null }) return null
    var fraction = 0.0
    var scale = 0.1
    for (ch in fracText) {
        fraction += CHINESE_DIGITS.getValue(ch) * scale
        scale /= 10
    }
    return intPart + fraction
}

private val CHINESE_DIGITS = mapOf(
    '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
    '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
)

private fun parseChineseInteger(text: String): Double? {
    if (text.isEmpty()) return null
    var total = 0.0
    var section = 0.0
    var number = 0.0
    var hasAny = false
    for (ch in text) {
        val digit = CHINESE_DIGITS[ch]
        when {
            digit != null -> {
                number = digit.toDouble()
                hasAny = true
            }
            ch == '十' || ch == '百' || ch == '千' -> {
                val unit = when (ch) { '十' -> 10.0; '百' -> 100.0; else -> 1000.0 }
                if (!hasAny) number = 1.0 // 十一 = 11
                section += number * unit
                number = 0.0
                hasAny = true
            }
            ch == '万' || ch == '亿' -> {
                if (!hasAny) return null
                section += number
                total = if (ch == '亿') (total + section) * 1e8 else total + section * 1e4
                section = 0.0
                number = 0.0
                hasAny = true
            }
            else -> return null
        }
    }
    return total + section + number
}

private val CHINESE_AMOUNT = Regex("([零一二三四五六七八九十百千万亿两]+(?:点[零一二三四五六七八九]+)?)\\s*(万元|亿元|元)")
private val CHINESE_PERCENT = Regex("百分之\\s*([零一二三四五六七八九十百千万亿两]+(?:点[零一二三四五六七八九]+)?|\\d+(?:\\.\\d+)?)")

/** `百分之五十` / `零点五` / `五十` / `一万二千` / `12.5 万元` → 自然值 */
internal fun parseNumberExpression(text: String): NaturalToken? {
    val raw = text.trim()
    if (raw.isEmpty()) return null
    val percent = CHINESE_PERCENT.matchEntire(raw)
    if (percent != null) {
        val inner = percent.groupValues[1]
        val value = inner.toDoubleOrNull() ?: parseChineseNumber(inner) ?: return null
        if (!value.isFinite()) return null
        return NaturalToken(raw, value / 100, true, 0, raw.length)
    }
    val amount = CHINESE_AMOUNT.matchEntire(raw)
    if (amount != null) {
        val base = parseChineseNumber(amount.groupValues[1]) ?: return null
        val scale = when (amount.groupValues[2]) { "万元" -> 1e4; "亿元" -> 1e8; else -> 1.0 }
        return NaturalToken(raw, base * scale, false, 0, raw.length)
    }
    if (raw.all { CHINESE_DIGITS.containsKey(it) || it == '十' || it == '百' || it == '千' || it == '万' || it == '亿' || it == '点' }) {
        val value = parseChineseNumber(raw) ?: return null
        return NaturalToken(raw, value, false, 0, raw.length)
    }
    val match = NUMBER_TOKEN.matchEntire(raw) ?: return null
    val unit = match.groupValues[1]
    val base = normalize(raw) ?: return null
    if (unit == "%" || unit == "％") return NaturalToken(raw, base / 100, true, 0, raw.length)
    val scale = if (unit.isNotEmpty()) SCALE[unit] ?: 1.0 else 1.0
    return NaturalToken(raw, base * scale, false, 0, raw.length)
}

/** 抽出所有自然值 token；中文百分比/金额也收（T-051） */
internal fun extractNaturalNumbers(text: String): List<NaturalToken> {
    val out = mutableListOf<NaturalToken>()
    // 中文写法先收，阿拉伯数字再收时跳过被中文 token 覆盖的部分（「百分之50」只算 0.5 一处）
    val chinese = mutableListOf<NaturalToken>()
    for (match in CHINESE_PERCENT.findAll(text)) {
        val inner = match.groupValues[1]
        val value = inner.toDoubleOrNull() ?: parseChineseNumber(inner) ?: continue
        if (!value.isFinite()) continue
        chinese.add(NaturalToken(match.value.trim(), value / 100, true, match.range.first, match.range.last + 1))
    }
    for (match in CHINESE_AMOUNT.findAll(text)) {
        if (CHINESE_PERCENT.containsMatchIn(match.value)) continue
        val startOfAmount = match.range.first
        if (startOfAmount >= 3 && text.substring(startOfAmount - 3, startOfAmount) == "百分之") continue
        if (chinese.any { it.start < match.range.last + 1 && match.range.first < it.end }) continue
        val base = parseChineseNumber(match.groupValues[1]) ?: continue
        val scale = when (match.groupValues[2]) { "万元" -> 1e4; "亿元" -> 1e8; else -> 1.0 }
        chinese.add(NaturalToken(match.value.trim(), base * scale, false, match.range.first, match.range.last + 1))
    }
    for (match in NUMBER_TOKEN.findAll(text)) {
        if (chinese.any { it.start < match.range.last + 1 && match.range.first < it.end }) continue
        val before = if (match.range.first > 0) text[match.range.first - 1].toString() else ""
        if (NUMBER_NEIGHBOR.matches(before)) continue
        val unit = match.groupValues[1]
        val base = normalize(if (unit.isEmpty()) match.value else match.value.removeSuffix(unit)) ?: continue  // T-184：万/亿等单位后缀先剥再归一化——NUMERIC_TEXT 只认纯数字，不剥则「8888 万」整个 token 被静默跳过（万单位数字绕过白名单门禁；融合复核实测抓出）  // lint:allow zh-first（门禁修复注记，与 VerifyCore 既有中文注记同源）
        val percent = unit == "%" || unit == "％"
        val scale = if (unit.isNotEmpty() && !percent) SCALE[unit] ?: 1.0 else 1.0
        out.add(
            NaturalToken(
                raw = match.value.trim(),
                value = if (percent) base / 100 else base * scale,
                percent = percent,
                start = match.range.first,
                end = match.range.last + 1,
            ),
        )
    }
    out.addAll(chinese)
    return out.sortedBy { it.start }
}

/** 抽出所有数字 token；带数量级时同时给两种值（万与裸数都收） */
internal fun extractNumbers(text: String): List<NumToken> {
    val out = mutableListOf<NumToken>()
    val pattern = NUMBER_TOKEN
    for (match in pattern.findAll(text)) {
        // 日期前向查：「2015-01-03」里的 -01 不是负数
        val before = if (match.range.first > 0) text[match.range.first - 1].toString() else ""
        if (NUMBER_NEIGHBOR.matches(before)) continue
        val unit = match.groupValues[1]
        val base = normalize(if (unit.isEmpty()) match.value else match.value.removeSuffix(unit)) ?: continue
        // T-184：同上——brief 侧同形态数字同样被静默跳过，两处一起修  // lint:allow zh-first（门禁修复注记）
        val scale = if (unit.isNotEmpty() && unit != "%" && unit != "％") SCALE[unit] ?: 1.0 else 1.0
        val raw = match.value.trim()
        out.add(NumToken(raw, base * scale))
        if (scale != 1.0) out.add(NumToken(raw, base))
    }
    return out
}

/**
 * 数字 token 正则（T-043 保留旧口径）：逗号一律并入同一 token。
 *
 * 曾尝试「千分位三位一组」拆分，但 `333,305,270` 这类 CSV 行与千分位天然同形，
 * 拆分会把行内多个值并成一个更大的假数（比旧口径更危险），因此撤销。
 * T-043 最终选择契约层修复：样本行数值不作为引用依据，见 [citableBriefText]。
 */
internal val NUMBER_TOKEN = Regex("[+＋\\-－−]?[\\d０-９][\\d０-９,]*(?:\\.\\d+)?\\s*(万亿|千亿|百万|万|亿|千|%|％)?")

/**
 * brief 里允许被报告引用的部分：样本段之前（T-043）。
 *
 * 样本行的职责是「看字段形态与取值样式」，不是论据；整行 CSV 的逗号还会把多个值
 * 合并成一个 token，逐值授权本身不可靠。因此从 `前 N 行样本：` 起整段排除，
 * 让 verifyReport 的放行范围与 brief 里 `<allowed_numbers>` 的措辞一致。
 */
internal fun citableBriefText(brief: String): String {
    val marker = SAMPLE_MARKER.find(brief) ?: return brief
    return brief.substring(0, marker.range.first)
}

/** 容差按数量级分档 */
internal fun tolerance(value: Double): Double {
    val magnitude = abs(value)
    if (magnitude < 100) return maxOf(magnitude * 0.005, 0.05)
    if (magnitude < 10000) return magnitude * 0.001
    return magnitude * 0.0005
}

internal fun decimalsOf(value: Double): Int {
    // JS String(number) 口径：整数值不带 ".0"（Kotlin 的 889.0.toString() 是 "889.0"，会算出 1 位小数）
    val text = jsNumberString(value)
    val dot = text.indexOf('.')
    return if (dot == -1) 0 else text.length - dot - 1
}

/** JS String(number)：整数无小数点、大数不用科学计数法（Kotlin ≥1e7 会给 1.72E7） */
internal fun jsNumberString(value: Double): String {
    if (!value.isFinite()) return value.toString()
    if (value == value.toLong().toDouble() && kotlin.math.abs(value) < 1e15) {
        return value.toLong().toString()
    }
    val s = value.toString()
    return if (s.contains('E') || s.contains('e')) {
        java.math.BigDecimal(s).toPlainString()
    } else {
        s
    }
}

/** 带容差的命中判定（精度单调：报告精度不得高于摘要精度） */
internal fun hitsBrief(value: Double, briefSet: Set<Double>): Boolean {
    val toleranceOfValue = tolerance(value)
    val valueDecimals = decimalsOf(value)
    for (candidate in briefSet) {
        if (candidate == value) return true
        if (abs(candidate - value) > toleranceOfValue) continue
        if (valueDecimals > decimalsOf(candidate)) continue
        return true
    }
    return false
}

/** 行内数字 token 个数（与 extractNumbers 同口径） */
internal fun countNumbers(line: String): Int {
    var count = 0
    for (match in NUMBER_TOKEN.findAll(line)) {
        if (match.range.first > 0 && NUMBER_NEIGHBOR.matches(line[match.range.first - 1].toString())) continue
        count += 1
    }
    return count
}

/** 假设句豁免：触发词 + 数字 ≤2 个（纯举例才整行豁免） */
internal fun isHypothetical(line: String): Boolean {
    if (!HYPOTHETICAL_MARKER.containsMatchIn(line)) return false
    return countNumbers(line) <= 2
}

/** 格式举例豁免：只认具体的脏数据指示词（不含「形态」「格式」这两个通用词） */
internal fun isFormatExample(line: String): Boolean =
    FORMAT_EXAMPLE_MARKER.containsMatchIn(line) && !FACTUAL_CLAIM.containsMatchIn(line)

/** 带事实主张的行不能靠「写法/千分位」整行豁免（T-051：否则编造数可借格式词逃逸） */
private val FACTUAL_CLAIM = Regex("合计|均值|中位数|占比|增长|下降|利润|销售额|成本|行数|列数|总量|最大|最小|平均|环比|同比")

/** 取「- 标签：…」行首的标签名 */
internal fun label2Of(line: String): String =
    LABEL2_PATTERN.find(line.trim())?.groupValues?.get(1)?.trim() ?: ""

internal data class ArithmeticMistake(val wrong: String, val expected: Double)

/** 一处算式（T-051）：运算符、两个操作数与声称结果，各自带自然值 */
internal data class ArithmeticCheck(
    val operators: List<Char>,
    val operands: List<NaturalToken>,
    val claimed: NaturalToken,
    val expected: Double,
    val matched: Boolean,
    val range: IntRange,
    val operandRanges: List<IntRange>,
    val claimedRange: IntRange,
)

private const val EXPR = "(?:百分之\\s*[零一二三四五六七八九十百千万亿两\\d０-９.,]+(?:点[零一二三四五六七八九\\d０-９]+)?|[零一二三四五六七八九十百千万亿两]+(?:点[零一二三四五六七八九]+)?(?:万元|亿元|元)?|[+＋\\-－−]?[\\d０-９][\\d０-９,]*(?:\\.\\d+)?\\s*(?:万亿|千亿|百万|亿|万|千|%|％)?)"

/** 操作数与运算符之间允许出现的量词（「1,000 行 × 120.5」） */
private const val SEP = "[\\s个行元人单次倍条项]*"
private const val OP = "(?:[+＋加]|减(?:去)?|[-−]|×|\\*|✕|乘(?:以)?|÷|/|除以)"
private val CHAIN_LHS = Regex("($EXPR(?:$SEP$OP$SEP$EXPR)+)$SEP$")
private val EXPR_HEAD = Regex("^\\s*($EXPR)")
private val OP_HEAD = Regex("^$OP")
private val CHAIN_FILLER = Regex("^[\\s个行元人单次倍条项]+")
private val EQUALS = Regex("[=＝]")

private fun opOf(raw: String): Char = when (raw) {
    "+", "＋", "加" -> '+'
    "×", "*", "✕", "乘", "乘以" -> '*'
    "÷", "/", "除以" -> '/'
    else -> '-'
}

/** 把「A + B × C」拆成操作数序列与运算符序列；任何一段解析失败就放弃 */
private fun parseChain(lhs: String): Pair<List<NaturalToken>, List<Char>>? {
    val operands = mutableListOf<NaturalToken>()
    val ops = mutableListOf<Char>()
    var i = 0
    var expectOperand = true
    while (i < lhs.length) {
        CHAIN_FILLER.find(lhs.substring(i))?.let { if (it.range.first == 0) i += it.range.last + 1 }
        if (i >= lhs.length) break
        val rest = lhs.substring(i)
        if (expectOperand) {
            val m = EXPR_HEAD.find(rest) ?: return null
            val token = parseNumberExpression(m.groupValues[1]) ?: return null
            operands.add(token)
            i += m.range.last + 1
            expectOperand = false
        } else {
            val m = OP_HEAD.find(rest) ?: return null
            ops.add(opOf(m.value))
            i += m.range.last + 1
            expectOperand = true
        }
    }
    if (expectOperand || operands.size < 2 || ops.size != operands.size - 1) return null
    return operands to ops
}

/** 先乘除后加减 */
private fun evaluateChain(values: List<Double>, ops: List<Char>): Double? {
    if (values.size != ops.size + 1) return null
    val additive = mutableListOf(values[0])
    val addOps = mutableListOf<Char>()
    for (k in ops.indices) {
        val v = values[k + 1]
        when (ops[k]) {
            '*' -> additive[additive.size - 1] *= v
            '/' -> {
                if (v == 0.0) return null
                additive[additive.size - 1] /= v
            }
            '+' -> {
                addOps.add('+')
                additive.add(v)
            }
            else -> {
                addOps.add('-')
                additive.add(v)
            }
        }
    }
    var result = additive[0]
    for (k in addOps.indices) {
        result = if (addOps[k] == '+') result + additive[k + 1] else result - additive[k + 1]
    }
    return result
}

/**
 * 验算一行里的**所有**算式（T-051）：支持多项链式算式与运算优先级，
 * 操作数可以是裸数/百分比/中文数字。
 */
internal fun checkArithmeticAll(line: String): List<ArithmeticCheck> {
    val out = mutableListOf<ArithmeticCheck>()
    val claimedRanges = mutableSetOf<IntRange>()
    for (eq in EQUALS.findAll(line)) {
        val prefixStart = maxOf(0, eq.range.first - 160)
        val prefix = line.substring(prefixStart, eq.range.first)
        val lhsMatch = CHAIN_LHS.find(prefix) ?: continue
        val parsed = parseChain(lhsMatch.groupValues[1]) ?: continue
        val (operands, ops) = parsed
        val after = line.substring(eq.range.last + 1)
        val claimMatch = EXPR_HEAD.find(after) ?: continue
        val claimGroup = claimMatch.groups[1]!!
        val claimed = parseNumberExpression(claimGroup.value) ?: continue
        if (!claimed.value.isFinite() || operands.any { !it.value.isFinite() }) continue
        val claimedStart = eq.range.last + 1 + claimGroup.range.first
        val claimedRange = claimedStart until (eq.range.last + 1 + claimGroup.range.last + 1)
        if (!claimedRanges.add(claimedRange)) continue
        val expected = evaluateChain(operands.map { it.value }, ops) ?: continue
        val matched = abs(expected - claimed.value) <= maxOf(abs(expected) * 0.005, 0.0005)
        out.add(
            ArithmeticCheck(
                operators = ops,
                operands = operands,
                claimed = claimed,
                expected = expected,
                matched = matched,
                range = (prefixStart + lhsMatch.range.first) until claimedRange.last + 1,
                operandRanges = emptyList(),
                claimedRange = claimedRange,
            ),
        )
    }
    return out
}

/** 验算 `A ÷ B = C`；算不通就是确定的错（保留旧入口，返回首个错） */
internal fun checkArithmetic(line: String): ArithmeticMistake? {
    val bad = checkArithmeticAll(line).firstOrNull { !it.matched } ?: return null
    return ArithmeticMistake(bad.claimed.raw, jsRound2(bad.expected))
}

/** JS Number((x).toFixed(2))：toFixed 后转回 number（去尾零） */
internal fun jsRound2(v: Double): Double {
    val s = java.math.BigDecimal(v).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
    return s.toDouble()
}

/** JS toFixed(2) 字符串形态 */
internal fun jsToFixed2(v: Double): String =
    java.math.BigDecimal(v).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()

internal fun jsToFixed1(v: Double): String =
    java.math.BigDecimal(v).setScale(1, java.math.RoundingMode.HALF_UP).toPlainString()

/** 结构性数字：年份（带时间词）、章节号、100 分、0 与 1（仅限量词/序号语境）、纯序号 */
internal fun isStructural(raw: String, line: String, value: Double? = null, inFormula: Boolean = false): Boolean {
    val numeric = value ?: normalize(raw) ?: return true
    val trimmed = line.trim()
    val rawPos = trimmed.indexOf(raw)
    if (STRUCT_YEAR.matches(raw.replace(THOUSAND_SEPARATOR, "")) &&
        STRUCT_TIME_WORD.containsMatchIn(line)
    ) return true
    if (STRUCT_PERCENT_WORD.containsMatchIn(line) && (numeric == 100.0 || raw.replace(THOUSAND_SEPARATOR, "") == "100")) return true
    if (STRUCT_PAREN.matches(raw)) return true
    if (rawPos >= 0) {
        val head = trimmed.substring(0, minOf(trimmed.length, rawPos + raw.length))
        if (STRUCT_ORDER_HEAD.matches(head)) return true
        // 「第 15 章」「第 3 季度」「第 2 名」是序数定位，不是数据主张（T-051）。
        // 此前只有 0/1 享「第」前缀豁免：摘要里「15%」按自然值存成 0.15 后，
        // 「第 15 章」的 15 失去旧百分比白名单的偶然背书，如实报成未溯源并触发修订。
        if (!inFormula && trimmed.substring(0, rawPos).trimEnd().endsWith("第")) return true
    }
    // 0/1 不再永久豁免（T-051）：只有量词/序号/时间词语境才当结构数；
    // 公式行里的 0/1 一律参与验算与溯源（此前被无条件放行）
    if (numeric == 0.0 || numeric == 1.0) {
        if (inFormula) return false
        if (rawPos >= 0) {
            val after = trimmed.substring(minOf(trimmed.length, rawPos + raw.length))
            if (STRUCT_UNIT_AFTER.containsMatchIn(after)) return true
            val before = trimmed.substring(0, rawPos).trimEnd()
            if (before.endsWith("第") || before.endsWith("Q") || before.endsWith("q")) return true
        }
        return false
    }
    return false
}

/** 0/1 的结构性语境：后面跟量词/计数单位（倍、成、分是量化断言，不在此列） */
private val STRUCT_UNIT_AFTER = Regex(
    "^\\s*(?:个|条|列|行|项|次|张|位|人|类|种|家|年|月|日|页|章|节|组|批|轮|层|档|步|维|部分|段|篇|周|小时|分钟|秒)",
)

/** 从摘要抽列名（`- XXX（数值/文本/标识列…）` 三种形态） */
internal fun extractFields(brief: String): Set<String> {
    val fields = mutableSetOf<String>()
    for (line in brief.split(Regex("\\r\\n|\\r|\\n"))) {
        val match = BRIEF_FIELD_LINE.find(line.trim())
        if (match != null) fields.add(match.groupValues[1].trim())
    }
    return fields
}

internal val NOT_A_FIELD = Regex("总体概览|关键发现|风险|行动建议|数据质量|异常|趋势|对比|摘要|报告|样本|行号|统计|维度|指标$|口径|字段$|列$")

internal data class FieldIssueRaw(val field: String, val context: String)

internal fun checkFields(line: String, fields: Set<String>): List<FieldIssueRaw> {
    if (fields.isEmpty()) return emptyList()
    val issues = mutableListOf<FieldIssueRaw>()
    val seen = mutableSetOf<String>()
    for (pattern in FIELD_QUOTE_PATTERNS) {
        for (match in pattern.findAll(line)) {
            val name = match.groupValues[1].trim()
            if (name.isEmpty() || fields.contains(name) || seen.contains(name)) continue
            if (NOT_A_FIELD.containsMatchIn(name)) continue
            // 含非 ASCII 的「」内容不是字段名（中文引号日常用于强调）
            if (NON_ASCII.containsMatchIn(name)) continue
            seen.add(name)
            issues.add(FieldIssueRaw(name, if (line.length > 90) line.take(90) + "…" else line))
        }
    }
    return issues
}

internal fun isFormulaLine(line: String): Boolean =
    FORMULA_LINE.containsMatchIn(line)
