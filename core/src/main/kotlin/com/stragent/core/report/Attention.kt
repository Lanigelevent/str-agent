package com.stragent.core.report

import com.stragent.core.dataset.analyzeQuality
import com.stragent.core.dataset.Dataset

/**
 * 接下来该看什么 —— attention.ts 移植。把散在各处的结论收敛成**有优先级的动作**。
 *
 * 三条纪律：a) 只排序不重算（输入是已算好的 AnomalyScan 与质量报告）；
 * b) 判据可解释（reason 原样显示给用户）；c) 上限 3 条，排不进的记 hidden。
 */
internal enum class AttentionTone { BLOCKING, INSIGHT, META }

/** 点了这一条要做什么：跳异常卡并展开归因链 / 跳异常卡 / 跳质量卡 / 去生成报告 */
internal enum class AttentionAction { CHAIN, SCAN, QUALITY, REPORT }

internal data class AttentionItem(
    /** 稳定 id：同一份数据两次调用相同 */
    val id: String,
    val tone: AttentionTone,
    /** 一句话动作。动词开头——它是"该做什么"，不是"有什么" */
    val title: String,
    /** 为什么排这个位置（判据可解释） */
    val reason: String,
    val action: AttentionAction,
    /** action === CHAIN 时要展开的那一期 */
    val period: String? = null,
)

internal data class AttentionPlan(
    val items: List<AttentionItem>,
    /** 一条都没有时的收尾话。要说清"没有要处理的事"，否则用户会回去逐张卡再翻 */
    val quiet: String?,
    /** 排在上限之外还有几条（UI 明说"还有 N 条"） */
    val hidden: Int,
)

/** 一次最多给几条：手机上超过 3 条就回到平铺，用户不会逐条读 */
const val ATTENTION_MAX = 3

/** 空值率高到这个程度才值得占一个名额（低于它对均值的影响小到看不出来） */
private const val EMPTY_RATE_FLOOR = 0.05

/**
 * blocking 档内部的顺序，判据是**污染面**：重复行让所有结论一起错（第一），
 * 时间缺口让环比静默失真，序列塌陷暗示数据源停更，负值最可能"本来就该是这样"（最后）。
 * **常量必须真的驱动排序**：按 id 反查下标，顺序不靠 push 语句的副产物。
 */
private val BLOCKING_ORDER = listOf(
    AnomalyKind.DUPLICATE, AnomalyKind.GAP, AnomalyKind.FLATLINE, AnomalyKind.NEGATIVE,
)

private fun blockingRank(id: String): Int {
    val index = BLOCKING_ORDER.indexOfFirst { "blocking-${it.name.lowercase()}" == id }
    return if (index < 0) BLOCKING_ORDER.size else index
}

/** 变化幅度的措辞：+35% / −18.7% / 12 个百分点 */
private fun changeText(finding: AnomalyFinding): String =
    if (finding.kind == AnomalyKind.MIX) {
        "${"%.1f".format(kotlin.math.abs(finding.change) * 100)} 个百分点"
    } else {
        val sign = if (finding.change > 0) "+" else if (finding.change < 0) "−" else ""
        "$sign${"%.1f".format(kotlin.math.abs(finding.change) * 100)}%"
    }

private fun ofKind(scan: AnomalyScan, kind: AnomalyKind): List<AnomalyFinding> =
    scan.findings.filter { it.kind == kind }

private fun blockingItems(scan: AnomalyScan, emptyRate: Double, worstColumn: String?, worstRate: Double): List<AttentionItem> {
    val items = mutableListOf<AttentionItem>()

    ofKind(scan, AnomalyKind.DUPLICATE).firstOrNull()?.let { duplicate ->
        items.add(
            AttentionItem(
                id = "blocking-duplicate",
                tone = AttentionTone.BLOCKING,
                title = "先确认「${duplicate.metric}」的重复值",
                reason = "${duplicate.detail}。重复行会同时抬高合计、计数、均值与占比，" +
                    "是唯一一种让上面每个结论一起错的错误——不先处理它，别的发现都不可信。",
                action = AttentionAction.SCAN,
            ),
        )
    }

    ofKind(scan, AnomalyKind.GAP).firstOrNull()?.let { gap ->
        items.add(
            AttentionItem(
                id = "blocking-gap",
                tone = AttentionTone.BLOCKING,
                title = "补上缺失的月份，或别做跨缺月的对比",
                reason = "${gap.detail}。它不报错——数字照样算得出来，" +
                    "于是\"下降\"这种结论会被当成环比写进报告，而它比的是两个月前。",
                action = AttentionAction.SCAN,
            ),
        )
    }

    ofKind(scan, AnomalyKind.FLATLINE).firstOrNull()?.let { flatline ->
        items.add(
            AttentionItem(
                id = "blocking-flatline",
                tone = AttentionTone.BLOCKING,
                title = "确认「${flatline.metric}」是否已停止更新",
                reason = "${flatline.detail}。完全相同不是业务常态：" +
                    "若数据源确实停更，那之后每一期的\"变化\"都不是业务变化。",
                action = AttentionAction.SCAN,
            ),
        )
    }

    ofKind(scan, AnomalyKind.NEGATIVE).firstOrNull()?.let { negative ->
        items.add(
            AttentionItem(
                id = "blocking-negative",
                tone = AttentionTone.BLOCKING,
                title = "确认「${negative.metric}」的负值要不要计入合计",
                reason = "${negative.detail}。它可能是有意义的退货（那就该在合计里），" +
                    "也可能是录入错误——定不下来之前，任何总数都要打问号。",
                action = AttentionAction.SCAN,
            ),
        )
    }

    // 空值：只在没有异常发现重复行时用 quality 的空值率——
    // 否则两份来源说同一件事（重复行同时抬高空值率以外的所有统计）
    val duplicateFound = ofKind(scan, AnomalyKind.DUPLICATE).isNotEmpty()
    if (!duplicateFound && emptyRate >= EMPTY_RATE_FLOOR) {
        items.add(
            AttentionItem(
                id = "blocking-empty",
                tone = AttentionTone.BLOCKING,
                title = if (worstColumn == null) "先处理空值再下结论" else "先看「$worstColumn」的空值怎么补",
                reason = "全表空值占 ${"%.1f".format(emptyRate * 100)}%" +
                    (if (worstColumn != null) "，最高的是「$worstColumn」（${"%.1f".format(worstRate * 100)}%）" else "") +
                    "。空值会被均值与占比静默跳过——分母变了，比例却没告诉你。",
                action = AttentionAction.QUALITY,
            ),
        )
    }

    return items
}

/** 第二档：数据可信时，这些是真正值得看的变化 */
private fun insightItems(scan: AnomalyScan): List<AttentionItem> {
    val items = mutableListOf<AttentionItem>()
    for (finding in ofKind(scan, AnomalyKind.SHIFT)) {
        items.add(
            AttentionItem(
                id = "insight-shift-${finding.period ?: "x"}",
                tone = AttentionTone.INSIGHT,
                title = "看 ${finding.period ?: "这一期"} 的 ${changeText(finding)} 变化来自哪里",
                reason = "${finding.detail}。数据本身没问题，所以这是真变化。" +
                    "点进去会一路追到是哪个维度、再追到是量变了还是价变了。",
                action = AttentionAction.CHAIN,
                period = finding.period,
            ),
        )
    }
    for (finding in ofKind(scan, AnomalyKind.MIX)) {
        items.add(
            AttentionItem(
                id = "insight-mix-${finding.dimension ?: finding.metric}",
                tone = AttentionTone.INSIGHT,
                title = "看「${finding.dimension ?: finding.metric}」的占比为什么变了",
                reason = "${finding.detail}。总量可能一点没变——变的是结构，" +
                    "而结构变化最能说明业务在哪一侧使劲。",
                action = AttentionAction.SCAN,
            ),
        )
    }
    return items
}

/** 第三档：不产生新数字，但改变你对上面结论的信心 */
private fun metaItems(scan: AnomalyScan): List<AttentionItem> {
    val items = mutableListOf<AttentionItem>()
    if (scan.coverage.sparse) {
        items.add(
            AttentionItem(
                id = "meta-sparse",
                tone = AttentionTone.META,
                title = "按月的结论只能当参考",
                reason = "有 ${scan.coverage.months} 个月度点，但每月行数的中位数低于 20——" +
                    "这种密度上的环比更像抽样抖动，而抖动最会装成\"大幅突变\"。" +
                    "不是不能看，是别把幅度当真。",
                action = AttentionAction.SCAN,
            ),
        )
    }
    if (!scan.coverage.monthly && !scan.coverage.sparse) {
        items.add(
            AttentionItem(
                id = "meta-no-time",
                tone = AttentionTone.META,
                title = "这份数据做不了时序分析",
                reason = "没有可识别的月份列（${scan.coverage.months} 个月度点）。" +
                    "趋势、环比、缺口这几类发现都需要它——其余结论与时间无关，仍然有效。",
                action = AttentionAction.SCAN,
            ),
        )
    }
    return items
}

/**
 * 排"接下来该看什么"。顺序：blocking → insight → meta。
 * **为什么 blocking 永远在 insight 之前**：一份有 12 行重复的数据上，
 * "3 月销售额 +650%" 完全可能是那 12 行造成的——先看业务变化很吸引人，
 * 但它建立在一个可能错的分母上。
 */
internal fun planAttention(dataset: Dataset, scan: AnomalyScan): AttentionPlan {
    val quality = analyzeQuality(dataset)
    val blocking = blockingItems(
        scan, quality.emptyRate,
        quality.worstColumns.firstOrNull()?.column, quality.worstColumns.firstOrNull()?.emptyRate ?: 0.0,
    ).sortedBy { blockingRank(it.id) }
    // insight 内部：findings 已按 severity → |change| 排过，直接沿用（不重算）
    val insight = insightItems(scan)
    val meta = metaItems(scan)

    val all = blocking + insight + meta
    val items = all.take(ATTENTION_MAX)

    val quiet = if (items.isEmpty()) {
        "没有发现需要优先处理的问题：没有重复值、没有异常波动，" +
            (if (scan.coverage.monthly) "月度序列也连续" else "覆盖情况正常") + "。可以直接生成报告。"
    } else {
        null
    }

    return AttentionPlan(items, quiet, maxOf(0, all.size - items.size))
}
