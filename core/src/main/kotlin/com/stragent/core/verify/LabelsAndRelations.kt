package com.stragent.core.verify

/**
 * 标签提取器 + 关系校验 —— report-verify.ts 中段移植。
 *
 * 提取器列表化（不互相 continue）的纪律、按指标成对比较（pairByMetric）、
 * 省略写法继承与跨期桥接——全部照 TS 行为移植。
 */
import kotlin.math.abs

data class ExtractItem(val key: String, val value: Double, val metric: String? = null)

/** 标签的一个取值，连同它来自哪个指标段 */
data class LabelEntry(val metric: String, val value: Double)

private val MOM = Regex("^\\s*((?:19|20)\\d{2}-\\d{2})\\s*环比[：:]\\s*([+-]?[\\d.]+)\\s*%")
private val YOY = Regex("^\\s*((?:19|20)\\d{2})\\s*年同比[：:]\\s*([+-]?[\\d.]+)\\s*%")
private val PERIOD_MONTH = Regex("((?:19|20)\\d{2}-\\d{2})\\s+([\\d,]+(?:\\.\\d+)?)")
private val MONTHLY_SUMMARY = Regex("^\\s*按月汇总")
private val PERIOD_YEAR = Regex("((?:19|20)\\d{2})\\s*年\\s*([\\d,]+(?:\\.\\d+)?)")
private val SIMPLE = Regex("^-\\s*([^：:]+)[：:]\\s*([+-]?[\\d,]+(?:\\.\\d+)?)(?![\\d-])\\s*(个|行|列|项|条|次|年)?")
private val RATIO = Regex("^-\\s*([^：:]+)[：:]\\s*利润率\\s*([\\d.]+)\\s*%")
private val GROUPED = Regex("^-\\s*([^：:]+)[：:]\\s*合计\\s*([\\d,]+(?:\\.\\d+)?)(?:（([\\d.]+)%）)?")
private val MEAN = Regex("单组均值\\s*([\\d,]+(?:\\.\\d+)?)")

private interface Extractor {
    fun test(line: String): Boolean
    fun extract(line: String, metric: String): List<ExtractItem>
}

private val EXTRACTORS = listOf(
    object : Extractor { // 环比
        override fun test(line: String) = MOM.containsMatchIn(line)
        override fun extract(line: String, metric: String): List<ExtractItem> {
            val m = MOM.find(line) ?: return emptyList()
            val value = m.groupValues[2].toDoubleOrNull() ?: return emptyList()
            return listOf(ExtractItem("${m.groupValues[1]}·环比", value, "环比"))
        }
    },
    object : Extractor { // 同比
        override fun test(line: String) = YOY.containsMatchIn(line)
        override fun extract(line: String, metric: String): List<ExtractItem> {
            val m = YOY.find(line) ?: return emptyList()
            val value = m.groupValues[2].toDoubleOrNull() ?: return emptyList()
            return listOf(ExtractItem("${m.groupValues[1]}·同比", value, "同比"))
        }
    },
    object : Extractor { // 期维度（年度/月度 × 维度段）
        override fun test(line: String) = line.startsWith("- ") && label2Of(line).isNotEmpty()
        override fun extract(line: String, metric: String): List<ExtractItem> {
            val label = label2Of(line)
            if (label.isEmpty()) return emptyList()
            val out = mutableListOf<ExtractItem>()
            for (m in PERIOD_MONTH.findAll(line)) {
                val value = m.groupValues[2].replace(",", "").toDoubleOrNull() ?: continue
                out.add(ExtractItem("$label·${m.groupValues[1]}", value))
            }
            for (m in PERIOD_YEAR.findAll(line)) {
                val value = m.groupValues[2].replace(",", "").toDoubleOrNull() ?: continue
                out.add(ExtractItem("$label·${m.groupValues[1]}", value))
            }
            return out
        }
    },
    object : Extractor { // 月度汇总（全体口径；key 用裸期）
        override fun test(line: String) = MONTHLY_SUMMARY.containsMatchIn(line)
        override fun extract(line: String, metric: String): List<ExtractItem> {
            val out = mutableListOf<ExtractItem>()
            for (m in PERIOD_MONTH.findAll(line)) {
                val value = m.groupValues[2].replace(",", "").toDoubleOrNull() ?: continue
                out.add(ExtractItem(m.groupValues[1], value))
            }
            return out
        }
    },
    object : Extractor { // 简单标签（量词 = 计数，不是值）
        override fun test(line: String) = SIMPLE.containsMatchIn(line)
        override fun extract(line: String, metric: String): List<ExtractItem> {
            val m = SIMPLE.find(line) ?: return emptyList()
            if (m.groupValues[3].isNotEmpty()) return emptyList()
            val value = m.groupValues[2].replace(",", "").toDoubleOrNull() ?: return emptyList()
            val label = m.groupValues[1].trim()
            return if (label.isNotEmpty()) listOf(ExtractItem(label, value)) else emptyList()
        }
    },
    object : Extractor { // 利润率
        override fun test(line: String) = RATIO.containsMatchIn(line)
        override fun extract(line: String, metric: String): List<ExtractItem> {
            val m = RATIO.find(line) ?: return emptyList()
            val label = m.groupValues[1].trim()
            val value = m.groupValues[2].toDoubleOrNull() ?: return emptyList()
            return if (label.isNotEmpty()) listOf(ExtractItem("$label·利润率", value)) else emptyList()
        }
    },
    object : Extractor { // 分组合计（占比可选）
        override fun test(line: String): Boolean {
            val m = GROUPED.find(line)
            return m != null && m.groupValues[1].trim().isNotEmpty()
        }
        override fun extract(line: String, metric: String): List<ExtractItem> {
            val m = GROUPED.find(line) ?: return emptyList()
            val label = m.groupValues[1].trim()
            if (label.isEmpty()) return emptyList()
            val out = mutableListOf<ExtractItem>()
            val total = m.groupValues[2].replace(",", "").toDoubleOrNull()
            if (total != null && total.isFinite()) out.add(ExtractItem(label, total))
            if (m.groupValues[3].isNotEmpty()) {
                val share = m.groupValues[3].toDoubleOrNull()
                if (share != null && share.isFinite()) out.add(ExtractItem("$label·占比", share))
            }
            return out
        }
    },
    object : Extractor { // 单组均值（自己取标签，不依赖 grouped 分支）
        override fun test(line: String) = line.startsWith("- ") && MEAN.containsMatchIn(line)
        override fun extract(line: String, metric: String): List<ExtractItem> {
            val label = Regex("^-\\s*([^：:]+)[：:]").find(line)?.groupValues?.get(1)?.trim() ?: ""
            val m = MEAN.find(line) ?: return emptyList()
            if (label.isEmpty()) return emptyList()
            val value = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return emptyList()
            return listOf(ExtractItem("$label·单组均值", value))
        }
    },
)

/** 提取「标签 → 取值列表」（一个标签可多值，值记住自己来自哪个指标段） */
fun extractLabelValues(brief: String): Map<String, List<LabelEntry>> {
    val map = LinkedHashMap<String, MutableList<LabelEntry>>()
    fun put(key: String, value: Double, metric: String) {
        val list = map.getOrPut(key) { mutableListOf() }
        if (list.none { it.metric == metric && abs(it.value - value) < 1e-9 }) {
            list.add(LabelEntry(metric, value))
        }
    }
    var metric = ""
    for (line in brief.split(Regex("\\r\\n|\\r|\\n"))) {
        val trimmed = line.trim()
        if (!trimmed.startsWith("- ")) {
            val head = Regex("分组求和[（(]([^，,）)]+)").find(trimmed)
            if (head != null) metric = head.groupValues[1].trim()
        }
        for (extractor in EXTRACTORS) {
            if (!extractor.test(trimmed)) continue
            for (item in extractor.extract(trimmed, metric)) {
                put(item.key, item.value, item.metric ?: metric)
            }
        }
    }
    return map
}

// ---------- 关系校验 ----------

private val DERIVED_NOUN = Regex("均值|单行|平均|中位数|客单价|人均|环比|同比|利润率|占比|增长率|完成率|转化率|折扣率|(?:19|20)\\d{2}\\s*年|(?:19|20)\\d{2}-\\d{2}")

private fun isDerivedPhrase(text: String): Boolean = DERIVED_NOUN.containsMatchIn(text)

private val DERIVED_KIND_WORDS = mapOf(
    "占比" to listOf("占比", "比例", "份额"),
    "单组均值" to listOf("单组均值", "单均", "均值", "平均"),
    "利润率" to listOf("利润率", "毛利", "收益率"),
)

data class LabelHit(val label: String, val values: List<LabelEntry>, val kind: String? = null)

/** 从嵌套命中的标签里挑最长者（最长者必须包含其余每一个）；并列则放弃 */
private fun pickNested(entries: List<Map.Entry<String, List<LabelEntry>>>, text: String): LabelHit? {
    val hits = entries.filter { text.contains(it.key) }
    if (hits.isEmpty()) return null
    if (hits.size == 1) return LabelHit(hits[0].key, hits[0].value)
    val longest = hits.maxByOrNull { it.key.length }!!
    val nested = hits.all { longest.key.contains(it.key) }
    return if (nested) LabelHit(longest.key, longest.value) else null
}

internal fun findLabelHit(
    labels: Map<String, List<LabelEntry>>,
    text: String,
    fallbackKind: String? = null,
): LabelHit? {
    // 省略写法优先：「Corporate 的占比高于 Consumer」——后半句继承种类
    if (fallbackKind != null) {
        val inherited = labels.entries.filter { (key) ->
            val sep = key.indexOf('·')
            if (sep == -1) return@filter false
            key.substring(sep + 1) == fallbackKind && text.contains(key.substring(0, sep))
        }
        if (inherited.size == 1) {
            return LabelHit(inherited[0].key.split('·')[0], inherited[0].value, fallbackKind)
        }
    }
    if (isDerivedPhrase(text)) {
        // 点了具体「期」时只认那一期
        val periods = Regex("(?:19|20)\\d{2}(?:-\\d{2})?").findAll(text).map { it.value }.toSet().toList()
        fun matchPeriod(entries: List<Map.Entry<String, List<LabelEntry>>>): List<Map.Entry<String, List<LabelEntry>>> {
            if (periods.isEmpty()) return entries
            val narrowed = entries.filter { (key) -> periods.contains(key.substring(key.indexOf('·') + 1)) }
            return if (narrowed.isNotEmpty()) narrowed else entries
        }
        val hits = matchPeriod(
            labels.entries.filter { (key) ->
                val sep = key.indexOf('·')
                if (sep == -1) return@filter false
                val label = key.substring(0, sep)
                val kind = key.substring(sep + 1)
                val words = DERIVED_KIND_WORDS[kind] ?: listOf(kind)
                text.contains(label) && words.any { text.contains(it) }
            },
        )
        if (hits.size == 1) {
            val sep = hits[0].key.indexOf('·')
            return LabelHit(hits[0].key.substring(0, sep), hits[0].value, hits[0].key.substring(sep + 1))
        }
        // 派生分支查不到回落普通标签（仅当无 fallbackKind）
        if (fallbackKind == null) {
            val plain = pickNested(labels.entries.filter { !it.key.contains('·') }, text)
            if (plain != null) return plain
        }
    } else {
        val plain = pickNested(labels.entries.filter { !it.key.contains('·') }, text)
        if (plain != null) return plain
    }
    return null
}

private fun findLabelIn(labels: Map<String, List<LabelEntry>>, text: String): List<LabelEntry>? =
    findLabelHit(labels, text)?.values

/** 按指标成对（不跨指标笛卡尔积）；成对全等或无共享指标时退回全组合 */
private fun pairByMetric(a: List<LabelEntry>, b: List<LabelEntry>): List<Pair<Double, Double>> {
    val shared = a.filter { x -> x.metric.isNotEmpty() && b.any { y -> y.metric == x.metric } }
    if (shared.isNotEmpty()) {
        val pairs = shared.map { x -> x.value to b.first { y -> y.metric == x.metric }.value }
        if (pairs.any { (x, y) -> x != y }) return pairs
    }
    return a.flatMap { x -> b.map { y -> x.value to y.value } }
}

data class RelationIssueRaw(
    val context: String,
    val claimed: String,
    val expected: String,
    val pair: String,
)

private fun ctx(line: String): String = if (line.length > 90) line.take(90) + "…" else line

internal fun checkRelations(line: String, labels: Map<String, List<LabelEntry>>): List<RelationIssueRaw> {
    if (labels.size < 2) return emptyList()
    val issues = mutableListOf<RelationIssueRaw>()

    // 「…是…的 N 倍」
    val ratio = Regex("(.{1,40}?)\\s*是\\s*(.{1,40}?)\\s*的\\s*([\\d.]+)\\s*倍").find(line)
    if (ratio != null) {
        val a = findLabelIn(labels, ratio.groupValues[1])
        val b = findLabelIn(labels, ratio.groupValues[2])
        val claimed = ratio.groupValues[3].toDoubleOrNull()
        val matched = if (a != null && b != null && claimed != null) {
            pairByMetric(a, b).any { (av, bv) ->
                if (bv == 0.0) false
                else abs(av / bv - claimed) <= maxOf(av / bv * 0.01, 0.01)
            }
        } else false
        if (a != null && b != null && !matched && b[0].value != 0.0) {
            val expected = a[0].value / b[0].value
            issues.add(
                RelationIssueRaw(
                    ctx(line),
                    "${ratio.groupValues[3]} 倍",
                    "${jsToFixed2(expected)} 倍",
                    "${ratio.groupValues[1].trim()} ÷ ${ratio.groupValues[2].trim()}",
                ),
            )
        }
    }

    // 「A 比 B 高/低 N%」与「A 高出/低于 B N%」（两种语序分开取）
    val patternA = Regex("(.{1,40}?)\\s*比\\s*(.{1,40}?)\\s*(高出|低于|高|低|多|少)(?:出|了)?\\s*([\\d.]+)\\s*%").find(line)
    val patternB = Regex("(.{1,40}?)\\s*(高出|低于)\\s*(.{1,40}?)\\s*([\\d.]+)\\s*%").find(line)
    data class Diff(val left: String, val right: String, val word: String, val value: String)
    val diff = if (patternA != null) Diff(patternA.groupValues[1], patternA.groupValues[2], patternA.groupValues[3], patternA.groupValues[4])
    else if (patternB != null) Diff(patternB.groupValues[1], patternB.groupValues[3], patternB.groupValues[2], patternB.groupValues[4])
    else null
    if (diff != null) {
        val hitA = findLabelHit(labels, diff.left)
        val hitB = findLabelHit(labels, diff.right)
        val sameCaliber = hitA != null && hitB != null && (hitA.kind ?: "") == (hitB.kind ?: "")
        val claimed = diff.value.toDoubleOrNull()
        val pctOk = if (hitA != null && hitB != null && claimed != null) {
            pairByMetric(hitA.values, hitB.values).any { (a, b) ->
                if (b == 0.0) false
                else abs(abs((a - b) / b * 100) - claimed) <= maxOf(abs((a - b) / b * 100) * 0.02, 0.2)
            }
        } else false
        if (sameCaliber && !pctOk && hitB!!.values[0].value != 0.0) {
            val a = hitA!!.values[0].value
            val b = hitB.values[0].value
            val expected = abs((a - b) / b * 100)
            issues.add(
                RelationIssueRaw(
                    ctx(line),
                    "${diff.value}%",
                    "${jsToFixed1(expected)}%",
                    "${diff.left.trim()} vs ${diff.right.trim()}",
                ),
            )
            // 方向校验（即使百分比对得上也要查）
            val wantsHigher = diff.word == "高出" || diff.word == "高" || diff.word == "多"
            val wantsLower = diff.word == "低于" || diff.word == "低" || diff.word == "少"
            val dirOk =
                (wantsHigher && pairByMetric(hitA.values, hitB.values).any { (a, b) -> a > b }) ||
                    (wantsLower && pairByMetric(hitA.values, hitB.values).any { (a, b) -> a < b })
            if ((wantsHigher || wantsLower) && !dirOk) {
                issues.add(
                    RelationIssueRaw(
                        ctx(line),
                        "${diff.left.trim()} ${diff.word} ${diff.right.trim()}",
                        "实算 ${diff.left.trim()} = ${hitA.values[0].value}、${diff.right.trim()} = ${hitB.values[0].value}，方向说反了",
                        "${diff.left.trim()} vs ${diff.right.trim()}",
                    ),
                )
            }
        }
    }

    // 环比/同比的方向与数值（符号在摘要里，可直接判）
    val RISE = "上升|增长|提高|上涨|增加"
    val FALL = "下降|回落|降低|下滑|减少"
    val periodPatterns = listOf(
        Regex("((?:19|20)\\d{2}-\\d{2})\\s*(?:的\\S{0,8}?)?\\s*环比\\s*($RISE|$FALL)\\s*([\\d.]+)\\s*%") to "环比",
        Regex("((?:19|20)\\d{2})\\s*年\\s*(?:的\\S{0,8}?)?\\s*同比\\s*($RISE|$FALL)\\s*([\\d.]+)\\s*%") to "同比",
        Regex("(?:环比|同比)(?:看|来|看，|而言)?[，,]?\\s*((?:19|20)\\d{2}-\\d{2})\\s*($RISE|$FALL)\\s*([\\d.]+)\\s*%") to "环比",
    )
    for ((re, kind) in periodPatterns) {
        for (claim in re.findAll(line)) {
            val period = claim.groupValues[1]
            val word = claim.groupValues[2]
            val pctText = claim.groupValues[3]
            val context = ctx(line)
            val entries = labels["${period}·$kind"]
            if (entries == null) {
                issues.add(
                    RelationIssueRaw(
                        context,
                        "$period $kind$word $pctText%",
                        "摘要里没有 $period 的${kind}数据，这个变化率没有依据",
                        "$period $kind",
                    ),
                )
                continue
            }
            val stored = entries[0].value
            val claimed = pctText.toDoubleOrNull() ?: continue
            val wantsRise = Regex(RISE).containsMatchIn(word)
            val wantsFall = Regex(FALL).containsMatchIn(word)
            if ((wantsRise && stored < 0) || (wantsFall && stored > 0)) {
                issues.add(
                    RelationIssueRaw(
                        context,
                        "$period $kind$word $pctText%",
                        "摘要里 $period ${kind}是 ${if (stored >= 0) "+" else ""}${jsToFixed1(stored)}%，方向说反了",
                        "$period $kind",
                    ),
                )
                continue
            }
            if (abs(abs(stored) - claimed) > maxOf(abs(stored) * 0.02, 0.2)) {
                issues.add(
                    RelationIssueRaw(context, "$pctText%", "${jsToFixed1(abs(stored))}%", "$period $kind"),
                )
            }
        }
    }

    // 纯方向断言（不带百分比）
    val pure = Regex("(.{1,40}?)\\s*(高于|低于|领先|落后于|多于|少于)\\s*(.{1,40}?)(?:[。，、；！？]|$)").find(line)
    if (pure != null) {
        val hitA = findLabelHit(labels, pure.groupValues[1])
        var hitB = findLabelHit(labels, pure.groupValues[3], hitA?.kind)
        // 跨期省略写法桥接（年月优先于年份）
        if (hitB == null && hitA != null && hitA.kind != null &&
            Regex("^(?:(?:19|20)\\d{2}|(?:19|20)\\d{2}-\\d{2})$").matches(hitA.kind)
        ) {
            val bPeriod = Regex("((?:19|20)\\d{2}-\\d{2})").find(pure.groupValues[3])?.value
                ?: Regex("((?:19|20)\\d{2})\\s*年").find(pure.groupValues[3])?.groupValues?.get(1)
            if (bPeriod != null) {
                val bridged = labels["${hitA.label}·$bPeriod"]
                if (bridged != null) hitB = LabelHit(hitA.label, bridged, bPeriod)
            }
        }
        val PERIOD = Regex("^(?:(?:19|20)\\d{2}|(?:19|20)\\d{2}-\\d{2})$")
        fun isMonth(kind: String) = kind.contains('-')
        val bothPeriods = hitA != null && hitB != null && hitA.kind != null && hitB.kind != null &&
            PERIOD.matches(hitA.kind) && PERIOD.matches(hitB.kind) && isMonth(hitA.kind) == isMonth(hitB.kind)
        val comparable = hitA != null && hitB != null &&
            ((hitA.kind ?: "") == (hitB.kind ?: "") || bothPeriods) &&
            pairByMetric(hitA.values, hitB!!.values).any { (a, b) -> a != b }
        if (comparable) {
            val word = pure.groupValues[2]
            val wantsHigher = word == "高于" || word == "领先" || word == "多于"
            val wantsLower = word == "低于" || word == "落后于" || word == "少于"
            val pairs = pairByMetric(hitA!!.values, hitB!!.values)
            val dirOk =
                (wantsHigher && pairs.any { (a, b) -> a > b }) ||
                    (wantsLower && pairs.any { (a, b) -> a < b })
            if ((wantsHigher || wantsLower) && !dirOk) {
                issues.add(
                    RelationIssueRaw(
                        ctx(line),
                        "${pure.groupValues[1].trim()} $word ${pure.groupValues[3].trim()}",
                        "实算 ${pure.groupValues[1].trim()} = ${hitA.values[0].value}、${pure.groupValues[3].trim()} = ${hitB.values[0].value}，方向说反了",
                        "${pure.groupValues[1].trim()} vs ${pure.groupValues[3].trim()}",
                    ),
                )
            }
        }
    }

    return issues
}
