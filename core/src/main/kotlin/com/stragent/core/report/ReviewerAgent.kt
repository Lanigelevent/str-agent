package com.stragent.core.report

import com.stragent.core.ai.AgentFindingCard
import com.stragent.core.csv.Csv

/**
 * ReviewerAgent —— 编辑部第三道确定性审校闸（D10 阶段 5 第三落地）。
 *
 * 与 verifyReport（数字溯源）和 WritingAudit（写作套话/结构/篇幅）并排，
 * 补的是它们都做不了的**语义级一致性检查**：
 *
 * ① **发现覆盖**：采编日志里的每条 finding 的关键词是否在 draft 中出现——
 *    记者查了但主笔没写的发现是"白采访"。
 * ② **矛盾主张检测**：提取 draft 中的数值声明（增长率/占比/倍数），
 *    同一主题出现不同数字 = 矛盾。
 * ③ **结论溯源**：强结论句（因此/综上/这说明/可见/由此…）引用的数字
 *    是否在 allowedNumbers 中——未溯源的结论 = 无中生有。
 *
 * **纯 Kotlin、确定性**——无 LLM-as-judge 偏差，与溯源纪律兼容。
 *
 * 分级：
 * - **hard**：矛盾主张（确定性、无判断偏差，与未溯源数字同级触发退稿）
 * - **soft**：未覆盖发现 / 未溯源结论 → 只进退稿意见与 qualityNotes，不单独退稿
 */
data class ReviewResult(
    val uncoveredFindings: List<String>,
    val contradictions: List<Contradiction>,
    val unsupportedConclusions: List<String>,
    val hard: Boolean,
    val issues: List<ReviewIssue>,
)

data class Contradiction(
    val subject: String,
    val claim1: String,
    val claim2: String,
    val value1: String,
    val value2: String,
)

data class ReviewIssue(
    val kind: String,
    val detail: String,
)

/**
 * 执行审校。
 *
 * @param draft 报告全文
 * @param findings 采编日志中的发现卡
 * @param allowedNumbers verifyReport 使用的同一份白名单（brief 中的数字）
 */
fun reviewReport(
    draft: String,
    findings: List<AgentFindingCard>,
    allowedNumbers: Set<Double>,
): ReviewResult {
    val uncovered = checkFindingCoverage(draft, findings)
    val contradictions = checkContradictions(draft)
    val unsupported = checkConclusionSourcing(draft, allowedNumbers)

    val hard = contradictions.isNotEmpty()
    val issues = buildList {
        contradictions.forEach { add(ReviewIssue("contradiction", "矛盾：${it.subject}——${it.claim1}（${it.value1}）与 ${it.claim2}（${it.value2}）")) }
        uncovered.forEach { add(ReviewIssue("uncovered", "发现未被引用：$it")) }
        unsupported.forEach { add(ReviewIssue("unsupported_conclusion", "结论数字未溯源：$it")) }
    }

    return ReviewResult(
        uncoveredFindings = uncovered,
        contradictions = contradictions,
        unsupportedConclusions = unsupported,
        hard = hard,
        issues = issues,
    )
}

// ---------- ① 发现覆盖 ----------

/**
 * 检查采编日志中的每条 finding 是否在 draft 中被引用。
 * 判据：finding.title 的关键词（≥2 字的中文词 / ≥3 字的英文词）至少有一个出现在 draft 中。
 */
private fun checkFindingCoverage(
    draft: String,
    findings: List<AgentFindingCard>,
): List<String> {
    if (findings.isEmpty()) return emptyList()
    val draftLower = draft.lowercase()
    val uncovered = mutableListOf<String>()
    for (finding in findings) {
        val keywords = extractKeywords(finding.title)
        if (keywords.isEmpty()) continue // 无法提取关键词的不判
        val anyHit = keywords.any { kw -> draftLower.contains(kw.lowercase()) }
        if (!anyHit) {
            uncovered.add(finding.title)
        }
    }
    return uncovered
}

/** 从标题中提取关键词（去掉常见停用词，取≥2字的中文片段或≥3字的英文词） */
private fun extractKeywords(title: String): List<String> {
    val stopWords = setOf(
        "的", "了", "是", "在", "和", "与", "或", "为", "有", "无",
        "分析", "结果", "情况", "数据", "显示", "表明", "发现",
        "the", "a", "an", "of", "in", "for", "and", "or", "to",
        "is", "are", "was", "were", "be", "been", "has", "have",
    )

    // 中文：按标点和空格分割后取≥2字的片段
    val cjkSegments: List<String> = title.split(
        " ", ",", "\u3000", "\uff0c", "\u3002", "\uff1b", ";", ":", "\uff1a",
        "\u3001", "\uff08", "\uff09", "(", ")", "[", "]", "\u3010", "\u3011",
        "\"", "'", "\u201c", "\u201d", "\u2018", "\u2019", "\uff01", "!", "\uff1f", "?",
        "\n",
    ).asSequence()
        .filter { it.length >= 2 }
        .filter { it !in stopWords }
        .filter { seg: String -> seg.any { c: Char -> c.code > 0x2E80 } }
        .toList()

    // 英文：取≥3字母的单词
    val engWords: List<String> = ENGLISH_WORDS.findAll(title)
        .map { it.value.lowercase() }
        .filter { it !in stopWords }
        .toList()

    val all: List<String> = cjkSegments + engWords
    return all.ifEmpty {
        if (title.length >= 2) listOf(title) else emptyList()
    }
}

// ---------- ② 矛盾主张检测 ----------

/**
 * 提取 draft 中的数值声明（增长率/占比/倍数），检测同一主题下的矛盾。
 *
 * 模式：
 * - "增长 X%" / "下降 X%" / "涨幅 X%" / "跌幅 X%" / "提升 X%"
 * - "占 X%" / "占比 X%"
 * - "X 倍"
 */
private data class NumericClaim(
    val subject: String,   // 声明的主语（数值前面的名词短语）
    val metric: String,    // 增长/下降/占比/倍
    val value: String,     // 数值文本
)

private val GROWTH_PATTERN = Regex(
    "([^，。\\n]{2,20}?)\\s*(?:增长|增长率为|增速|增幅|涨幅|提升|上升|下降|跌幅|减少|回落|下滑)\\s*([\\d,.]+)\\s*%"
)
private val RATIO_PATTERN = Regex(
    "([^，。\\n]{2,20}?)\\s*(?:占|占比|占比达|比例为|份额为)\\s*([\\d,.]+)\\s*%"
)
private val MULTIPLE_PATTERN = Regex(
    "([^，。\\n]{2,20}?)\\s*(?:是|为)\\s*[^，。\\n]{0,6}?的\\s*([\\d.]+)\\s*倍"
)
private val ENGLISH_WORDS = Regex("[a-zA-Z]{3,}")
private val SENTENCE_SPLIT = Regex("[。？！\\n]+")

/** 「段 N」引用锚点（T-192：带锚点的断言句由锚点门禁管辖，不重复判） */
private val CLAIM_ANCHOR = Regex("""段\s*\d+""")

/** `checkContradictions` 的字面量预筛表：GROWTH_PATTERN 每个分支都含其中之一 */
private val GROWTH_TRIGGERS = listOf(
    "增长", "增速", "增幅", "涨幅", "提升", "上升",
    "下降", "跌幅", "减少", "回落", "下滑",
)

private fun checkContradictions(draft: String): List<Contradiction> {
    // 字面量预筛（T-044 性能）：三条模式的分支都含固定字，无字必然无匹配。
    // 30k 字报告一次 findAll 的懒匹配回溯成本远高于三次 contains 扫描。
    val hasGrowth = GROWTH_TRIGGERS.any { draft.contains(it) }
    val hasRatio = draft.contains("占") || draft.contains("比例") || draft.contains("份额")
    val hasMultiple = draft.contains("倍") && (draft.contains("是") || draft.contains("为"))
    if (!hasGrowth && !hasRatio && !hasMultiple) return emptyList()

    val claims = extractNumericClaims(draft, hasGrowth, hasRatio, hasMultiple)
    if (claims.size < 2) return emptyList()

    val contradictions = mutableListOf<Contradiction>()

    // 按 subject 分组（归一化：取最后 2-4 个字作为 subject key）
    val bySubject = claims.groupBy { normalizeSubject(it.subject) }
    for ((subject, group) in bySubject) {
        if (group.size < 2) continue
        // 同一 subject + 同一 metric 下不同 value = 矛盾
        val byMetric = group.groupBy { it.metric }
        for ((metric, items) in byMetric) {
            val values = items.map { it.value }.distinct()
            if (values.size >= 2) {
                contradictions.add(Contradiction(
                    subject = subject,
                    claim1 = "${items[0].subject}${metric}",
                    claim2 = "${items[1].subject}${metric}",
                    value1 = items[0].value + "%",
                    value2 = items[1].value + "%",
                ))
            }
        }
    }
    return contradictions
}

private fun extractNumericClaims(
    text: String,
    scanGrowth: Boolean,
    scanRatio: Boolean,
    scanMultiple: Boolean,
): List<NumericClaim> {
    val claims = mutableListOf<NumericClaim>()

    if (scanGrowth) for (m in GROWTH_PATTERN.findAll(text)) {
        val subject = m.groupValues[1].trim()
        val metricRaw = m.value.substringAfter(subject).takeWhile { it.isLetter() }
        val metric = when {
            metricRaw.contains("增长") || metricRaw.contains("增速") || metricRaw.contains("增幅") ||
                metricRaw.contains("涨幅") || metricRaw.contains("提升") || metricRaw.contains("上升") -> "增长"
            metricRaw.contains("下降") || metricRaw.contains("跌幅") || metricRaw.contains("减少") ||
                metricRaw.contains("回落") || metricRaw.contains("下滑") -> "下降"
            else -> metricRaw
        }
        claims.add(NumericClaim(subject, metric, m.groupValues[2]))
    }

    if (scanRatio) for (m in RATIO_PATTERN.findAll(text)) {
        claims.add(NumericClaim(m.groupValues[1].trim(), "占比", m.groupValues[2]))
    }

    if (scanMultiple) for (m in MULTIPLE_PATTERN.findAll(text)) {
        claims.add(NumericClaim(m.groupValues[1].trim(), "倍", m.groupValues[2]))
    }

    return claims
}

/** 将 subject 归一化为可比的 key：去掉修饰词，取核心名词 */
private fun normalizeSubject(subject: String): String {
    // 去掉常见前缀修饰
    val cleaned = subject
        .replace("的", "")
        .replace("了", "")
        .replace("为", "")
        .replace("是", "")
        .trim()
    // 取最后 2-6 个字作为 key（通常是核心名词）
    return if (cleaned.length > 6) cleaned.takeLast(6) else cleaned
}

// ---------- ③ 结论溯源 ----------

private val CONCLUSION_MARKERS = listOf(
    "因此", "综上", "这说明", "可见", "由此",
    "可以认为", "可以得出", "表明", "证明",
    "这意味着", "说明",
)

private val NUMBER_IN_SENTENCE = Regex("([\\d,]+(?:\\.\\d+)?)\\s*(?:%|万元|亿元|元|倍|个)?")

/**
 * 提取强结论句中的数字，检查是否在 allowedNumbers 中。
 */
private fun checkConclusionSourcing(
    draft: String,
    allowedNumbers: Set<Double>,
): List<String> {
    // 全文无任何结论标记 → 分句与逐句判断都不必做（T-044 性能：省掉 2000+ 次分句分配）
    if (CONCLUSION_MARKERS.none { draft.contains(it) }) return emptyList()

    // 按句号/问号/感叹号分句
    val sentences = draft.split(SENTENCE_SPLIT)
        .map { it.trim() }
        .filter { it.isNotBlank() }

    val unsupported = mutableListOf<String>()
    for (sentence in sentences) {
        val hasMarker = CONCLUSION_MARKERS.any { marker -> sentence.contains(marker) }
        if (!hasMarker) continue

        // 提取句中所有数字
        val numbers = NUMBER_IN_SENTENCE.findAll(sentence)
            .map { it.groupValues[1] }
            .mapNotNull { Csv.parseNumber(it) }
            .filter { it.isFinite() }
            .toList()

        if (numbers.isEmpty()) continue

        // 检查是否有不在 allowedNumbers 中的
        val unsourced = numbers.filter { !hitsAllowed(it, allowedNumbers) }
        if (unsourced.isNotEmpty()) {
            // 取句子的前 60 字作为摘要
            val excerpt = if (sentence.length > 60) sentence.take(60) + "..." else sentence
            unsupported.add(excerpt)
        }
    }
    return unsupported
}

private fun hitsAllowed(value: Double, allowed: Set<Double>): Boolean {
    if (allowed.isEmpty()) return true // 没有白名单 = 不查（零行为差异）
    return allowed.any { allowedVal ->
        kotlin.math.abs(allowedVal - value) < 0.01 ||
        kotlin.math.abs(allowedVal - value) / (kotlin.math.abs(allowedVal) + 1e-9) < 0.001
    }
}

/**
 * 从 brief 文本构建允许数字集合（与 verifyReport 同源逻辑但独立，
 * 避免修改既有 VerificationResult 契约）。
 */
fun buildAllowedNumbers(brief: String): Set<Double> {
    val set = mutableSetOf<Double>()
    for (item in com.stragent.core.verify.extractNumbers(com.stragent.core.verify.citableBriefText(brief))) {
        set.add(item.value)
        if (item.value > 0 && item.value < 1) set.add(item.value * 100)
        if (item.value > 1 && item.value < 100) set.add(item.value / 100)
    }
    return set
}


// ---------- ④ 定性断言溯源（T-192） ----------

/** 定性断言线索词：句子含任一即触发实体级证据回链（排序/比较/趋势/归因类判断）。 */
private val CLAIM_TRACE_MARKERS = listOf(
    "最高", "最低", "最大", "最小", "最多", "最少", "最强", "最弱", "最优", "最差",
    "领先", "落后", "高于", "低于", "超过", "不足", "翻倍", "减半",
    "第一", "第二", "居首", "垫底", "前三", "增长最快", "下降最快", "贡献最大", "拉动", "驱动",
)

/**
 * 通用两字组合（不当实体证据）：纯分析用语/副词/时期单位——从它们身上提不出「证据实体」。
 * 只收明显通用的组合；类目/维度值（华东、线上、苹果）一律不当通用词——宁可漏判（少判不误判）。
 */
private val CLAIM_TRACE_GENERIC = setOf(
    "数据", "分析", "报告", "整体", "表现", "情况", "趋势", "变化", "波动", "水平",
    "明显", "显著", "相对", "比较", "来看", "而言", "其中", "此外", "同时", "因此",
    "综上", "各项", "所有", "各个", "不同", "相同", "期间", "阶段", "层面", "维度",
    "指标", "数值", "结果", "发现", "结论", "建议", "关注", "需求", "业务", "运营",
    "管理", "提升", "优化", "改善", "扩大", "缩小", "集中", "分散", "稳定", "持续",
    "呈现", "存在", "具有", "属于", "处于", "位居", "排名", "位列", "占比", "超过",
    "不足", "主要", "非常", "特别", "大幅", "快速", "稳步", "逐步", "一直", "始终",
    "仍然", "依然", "略微", "小幅", "市场", "增长", "下降", "上升", "下滑", "翻倍",
    "减半", "贡献", "拉动", "驱动", "接近", "持平", "高于", "低于", "领先", "落后",
    "最高", "最低", "最大", "最小", "最多", "最少", "最强", "最弱", "最优", "最差",
    "第一", "第二", "第三", "居首", "垫底", "前三", "增速", "增幅", "涨幅", "跌幅",
    "季度", "月份", "年度", "月度", "年份", "周期", "环比", "同比", "渠道", "品类",
    "区域", "地区", "板块", "业务线", "整体上", "平均", "合计", "总计", "占比达",
)

/**
 * 从断言句提取第一个「实体候选」：按出现顺序取第一个非通用 2 字 CJK 组合。
 * 首字为助词/指代/数量级/序数（的了是在…第一…百千万亿）的组合跳过——它们不是实体。
 * 提不出候选（句子全是通用词）→ null = 不判（诚实召回边界：少判不误判）。
 */
private fun claimEntityOf(sentence: String): String? {
    val functionHead = "的了是在和与或为有这那该各某本每同其此之及但并因而故所如若虽且则把被向于由对按通过根据随着作为一二三四五六七八九十百千万亿第约近超太多高低大小着过很更较挺吗呢吧啊呀哦嘛"
    var i = 0
    while (i <= sentence.length - 2) {
        val a = sentence[i]
        val b = sentence[i + 1]
        if (a.code <= 0x2E80 || b.code <= 0x2E80) { i++; continue }
        val gram = sentence.substring(i, i + 2)
        if (gram in CLAIM_TRACE_GENERIC || a in functionHead || b in functionHead) { i++; continue }
        return gram
    }
    return null
}

/**
 * 定性断言的实体级证据回链（T-192）：报告里的定性判断句（排序/比较/趋势/归因），
 * 其主体实体必须出现在数据摘要（证据语料 = brief，即分析证据块的现有汇编形态）中——
 * 摘要里没有这个实体的判断 = 无证据支撑的定性断言，判「未溯源」。
 *
 * 软问题语义（判据①显式降级不静默）：只进 issues 与 qualityNotes，**不触发修订、
 * 不计入 hard**——启发式有误报，硬失败会把好稿拦在门外（代价不对称）。
 *
 * 分工边界：已带「段 N」引用锚点的句子不判（锚点有效性由 anchorIssuesOf 管辖）；
 * 数字断言不在此管（verifyReport 白名单 + checkConclusionSourcing 管辖）。
 */
fun checkClaimTracing(draft: String, brief: String): List<String> {
    if (brief.isBlank()) return emptyList()
    // 预筛（T-044 性能纪律）：全文无线索词 = 必无断言句，省掉 2000+ 次分句分配
    if (CLAIM_TRACE_MARKERS.none { draft.contains(it) }) return emptyList()
    val out = mutableListOf<String>()
    for (sentence in draft.split(SENTENCE_SPLIT)) {
        val s = sentence.trim()
        if (s.isEmpty()) continue
        if (CLAIM_TRACE_MARKERS.none { s.contains(it) }) continue
        if (CLAIM_ANCHOR.containsMatchIn(s)) continue
        val entity = claimEntityOf(s) ?: continue
        if (!brief.contains(entity)) {
            out.add(if (s.length > 60) s.take(60) + "..." else s) // lint:allow zh-first（审计结论文案，与既有摘录口径同源）
        }
    }
    return out.distinct()
}
