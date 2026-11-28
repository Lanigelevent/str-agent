package com.stragent.core.stats

import com.stragent.core.brief.formatNumber
import com.stragent.core.csv.ColumnType
import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset
import com.stragent.core.dataset.looksLikeIdColumn
import com.stragent.core.dataset.summarize

/**
 * 异常归因链 —— attribution-chain.ts 移植：把「这个月变了」一路追到「是量变了还是价变了」。
 *
 * 链条四环（每环都可复核，见 detail）：① 指标层 ② 维度层 ③ 子维度层 ④ 量价层。
 *
 * 量价分解为什么是精确的（无残差）：合计 = 笔数 × 笔均，于是
 *   Δ合计 = Δ笔数 × 笔均前 + 笔数后 × Δ笔均
 * 展开验证：(c₂−c₁)·m₁ + c₂·(m₂−m₁) = c₂m₂ − c₁m₁ ✓
 * 用对数分解会留下无业务含义的交叉残差项，所以不用它。
 *
 * 与 TS 的差异：TS 在有 fullStats.monthlyByGroup（导入时截断前算的全量）时整体替换
 * 序列；Kotlin 的导入本就截断在前 1 万行、无全量层，直接用截断数据——口径统一。
 */

/** 链条的一环 */
data class ChainLink(
    val kind: String, // metric | dimension | subdimension | decompose
    /** 这一环的标题：维度值是「华东」，量价层是「笔数」/「笔均」 */
    val label: String,
    /** 这一环的变化量（对合计的贡献，带符号） */
    val delta: Double,
    /** 占起点总变化的比例（0–1；起点那环是 1） */
    val share: Double,
    /** 这一环的算式，写到读者能复核 */
    val detail: String,
    /** 支撑这一环的原始行号（数据行，从 1 计） */
    val evidenceRows: List<Int>,
)

data class AttributionChain(
    val rootPeriod: String,
    val rootPrevious: String,
    val rootMetric: String,
    val rootBefore: Double,
    val rootAfter: Double,
    val rootChange: Double,
    val links: List<ChainLink>,
    /** 量价结论：变化主要来自笔数还是笔均 */
    val verdict: String, // volume | price | both | unknown
    /** 结论的人话。**只说数据能支撑的那半句**，不做业务归因 */
    val verdictText: String,
)

/** 明细行最多带几条（链上每一环都给，太多会淹没正文） */
private const val EVIDENCE_LIMIT = 3

private class MonthBucket(val month: String) {
    var total = 0.0
    var count = 0
    val byGroup = mutableMapOf<String, Double>()
    val rows = mutableListOf<Int>()
}

private fun fmt(value: Double): String =
    if (value.isFinite()) formatNumber(value) else "—"

/** 月度序列 + 分组分解。一次遍历 */
private fun buckets(dataset: Dataset, dateIndex: Int, valueIndex: Int, groupIndex: Int): List<MonthBucket> {
    val map = linkedMapOf<String, MonthBucket>()
    for (r in dataset.rows.indices) {
        val row = dataset.rows[r]
        val month = monthKey(row.getOrNull(dateIndex) ?: "") ?: continue
        val value = Csv.parseNumber(row.getOrNull(valueIndex) ?: "") ?: continue
        val bucket = map.getOrPut(month) { MonthBucket(month) }
        bucket.total += value
        bucket.count += 1
        if (groupIndex >= 0 && groupIndex != dateIndex) {
            val group = (row.getOrNull(groupIndex) ?: "").trim()
            if (group.isNotEmpty()) bucket.byGroup[group] = (bucket.byGroup[group] ?: 0.0) + value
        }
        if (bucket.rows.size < EVIDENCE_LIMIT) bucket.rows.add(r + 1)
    }
    return map.keys.sorted().map { month ->
        val bucket = map[month]!!
        bucket.total = round2(bucket.total)
        bucket.byGroup.keys.forEach { label -> bucket.byGroup[label] = round2(bucket.byGroup[label] ?: 0.0) }
        bucket
    }
}

/** 组内笔数（用于量价分解）：某一期某一维度值占了几行 */
private fun countOf(dataset: Dataset, dateIndex: Int, groupIndex: Int, group: String, month: String): Int =
    dataset.rows.count { row ->
        monthKey(row.getOrNull(dateIndex) ?: "") == month &&
            (row.getOrNull(groupIndex) ?: "").trim() == group
    }

/** 焦点组内按子维度聚合某一期的合计 */
private fun subAggregate(
    dataset: Dataset,
    dateIndex: Int,
    valueIndex: Int,
    groupIndex: Int,
    group: String,
    subIndex: Int,
    month: String,
): Map<String, Double> {
    val result = mutableMapOf<String, Double>()
    for (r in dataset.rows.indices) {
        val row = dataset.rows[r]
        if (monthKey(row.getOrNull(dateIndex) ?: "") != month) continue
        if (groupIndex < 0 || (row.getOrNull(groupIndex) ?: "").trim() != group) continue
        val value = Csv.parseNumber(row.getOrNull(valueIndex) ?: "") ?: continue
        val label = (row.getOrNull(subIndex) ?: "").trim()
        if (label.isEmpty()) continue
        result[label] = round2((result[label] ?: 0.0) + value)
    }
    return result
}

/** 选一个维度列：优先用调用方传的，不可用则启发式（第一个基数 2–24 的非 ID 文本列） */
private fun pickDimension(
    dataset: Dataset,
    preferred: Int?,
    dateIndex: Int,
    stats: List<com.stragent.core.dataset.ColumnStat>,
    exclude: List<Int>,
): Int {
    if (preferred != null && preferred >= 0 && preferred !in exclude) return preferred
    return dataset.columns.withIndex().firstOrNull { (index, name) ->
        if (index in exclude || index == dateIndex) return@firstOrNull false
        if (dataset.columnTypes[index] != ColumnType.TEXT) return@firstOrNull false
        if (looksLikeIdColumn(name)) return@firstOrNull false
        val distinct = stats.find { it.name == name }?.distinct ?: 0
        distinct in 2..24
    }?.index ?: -1
}

/**
 * 为一个时序突变构建归因链。
 *
 * `preferredGroup` 是分组维度列下标（brief / 卡片用的那个）；`preferredSub`
 * 是子维度列下标。传 -1 时用启发式选。
 *
 * 返回 null 的三种情况（调用方要能处理）：
 *   - 找不到那个月（period 不在数据里）
 *   - 没有日期列或数值列
 *   - 起点两期里有一期不存在（缺月时算不出环比）
 */
fun buildAttributionChain(
    dataset: Dataset,
    period: String,
    preferredGroup: Int = -1,
    preferredSub: Int = -1,
): AttributionChain? {
    val dateIndex = dataset.columns.indices.firstOrNull { index ->
        dataset.columnTypes[index] == ColumnType.TEXT &&
            dataset.rows.any { monthKey(it.getOrNull(index) ?: "") != null }
    } ?: return null
    val numericIndex = dataset.columns.withIndex().firstOrNull { (index, name) ->
        dataset.columnTypes[index] == ColumnType.NUMBER && !looksLikeIdColumn(name)
    }?.index ?: return null

    val stats = summarize(dataset)
    val groupIndex = pickDimension(dataset, preferredGroup, dateIndex, stats, listOf(dateIndex, numericIndex))
    val subIndex = pickDimension(dataset, preferredSub, dateIndex, stats, listOf(dateIndex, numericIndex, groupIndex))

    val metric = dataset.columns[numericIndex]
    val series = buckets(dataset, dateIndex, numericIndex, groupIndex)
    val currentIndex = series.indexOfFirst { it.month == period }
    if (currentIndex <= 0) return null
    val current = series[currentIndex]
    val previous = series[currentIndex - 1]
    val totalDelta = round2(current.total - previous.total)
    val change = if (previous.total == 0.0) Double.NaN else totalDelta / kotlin.math.abs(previous.total)

    val links = mutableListOf(
        ChainLink(
            kind = "metric",
            label = metric,
            delta = totalDelta,
            share = 1.0,
            detail = "$period「$metric」合计 ${fmt(current.total)}，相对 ${previous.month} 的 " +
                "${fmt(previous.total)} 变化 ${fmt(totalDelta)}" +
                (if (change.isFinite()) "（${"%.1f".format(change * 100)}%）" else ""),
            evidenceRows = current.rows,
        ),
    )

    /* ---- ② 维度层：哪个维度值贡献了这次变化 ---- */
    var focusGroup: String? = null
    var groupDelta = 0.0
    if (groupIndex >= 0) {
        val labels = previous.byGroup.keys + current.byGroup.keys
        val top = labels
            .map { label -> label to round2((current.byGroup[label] ?: 0.0) - (previous.byGroup[label] ?: 0.0)) }
            .sortedByDescending { (_, delta) -> kotlin.math.abs(delta) }
            .firstOrNull()
        if (top != null && kotlin.math.abs(top.second) > 0) {
            focusGroup = top.first
            groupDelta = top.second
            links.add(
                ChainLink(
                    kind = "dimension",
                    label = top.first,
                    delta = top.second,
                    share = if (totalDelta == 0.0) 0.0 else top.second / totalDelta,
                    detail = "「${dataset.columns[groupIndex]}」中「${top.first}」变化 ${fmt(top.second)}" +
                        "（${fmt(previous.byGroup[top.first] ?: 0.0)} → ${fmt(current.byGroup[top.first] ?: 0.0)}）",
                    evidenceRows = current.rows,
                ),
            )
        }
    }

    /* ---- ③ 子维度层：焦点组内部谁贡献的 ---- */
    if (focusGroup != null && subIndex >= 0) {
        // 焦点组内按子维度再聚合一遍（只看焦点组那些行）
        val subPrev = subAggregate(dataset, dateIndex, numericIndex, groupIndex, focusGroup, subIndex, previous.month)
        val subCur = subAggregate(dataset, dateIndex, numericIndex, groupIndex, focusGroup, subIndex, period)
        val labels = subPrev.keys + subCur.keys
        val top = labels
            .map { label -> label to round2((subCur[label] ?: 0.0) - (subPrev[label] ?: 0.0)) }
            .sortedByDescending { (_, delta) -> kotlin.math.abs(delta) }
            .firstOrNull()
        if (top != null && kotlin.math.abs(top.second) > 0) {
            links.add(
                ChainLink(
                    kind = "subdimension",
                    label = top.first,
                    delta = top.second,
                    share = if (groupDelta == 0.0) 0.0 else top.second / groupDelta,
                    detail = "「$focusGroup」内部按「${dataset.columns[subIndex]}」看，「${top.first}」变化 " +
                        "${fmt(top.second)}（${fmt(subPrev[top.first] ?: 0.0)} → ${fmt(subCur[top.first] ?: 0.0)}）",
                    evidenceRows = emptyList(),
                ),
            )
        }
    }

    /* ---- ④ 量价层：笔数效应 vs 笔均效应 ---- */
    var verdict = "unknown"
    var verdictText = "这份数据无法区分「笔数」与「笔均」的变化——没有分组维度或行数不足。"
    val focus = focusGroup
    if (focus != null && groupIndex >= 0) {
        val countBefore = countOf(dataset, dateIndex, groupIndex, focus, previous.month)
        val countAfter = countOf(dataset, dateIndex, groupIndex, focus, period)
        if (countBefore > 0 && countAfter > 0) {
            val totalBefore = previous.byGroup[focus] ?: 0.0
            val totalAfter = current.byGroup[focus] ?: 0.0
            val meanBefore = totalBefore / countBefore
            val meanAfter = totalAfter / countAfter
            // 精确分解：Δ合计 = Δ笔数 × 笔均前 + 笔数后 × Δ笔均
            val volumeEffect = round2((countAfter - countBefore) * meanBefore)
            val priceEffect = round2(countAfter * (meanAfter - meanBefore))
            links.add(
                ChainLink(
                    kind = "decompose",
                    label = "笔数",
                    delta = volumeEffect,
                    share = if (totalDelta == 0.0) 0.0 else volumeEffect / totalDelta,
                    detail = "笔数效应 ${fmt(volumeEffect)}（笔数 $countBefore → $countAfter，按前一期笔均 " +
                        "${fmt(meanBefore)} 折算）",
                    evidenceRows = emptyList(),
                ),
            )
            links.add(
                ChainLink(
                    kind = "decompose",
                    label = "笔均",
                    delta = priceEffect,
                    share = if (totalDelta == 0.0) 0.0 else priceEffect / totalDelta,
                    detail = "笔均效应 ${fmt(priceEffect)}（笔均 ${fmt(meanBefore)} → ${fmt(meanAfter)}，按当期笔数 " +
                        "$countAfter 折算）",
                    evidenceRows = emptyList(),
                ),
            )
            val total = kotlin.math.abs(volumeEffect) + kotlin.math.abs(priceEffect)
            // **只有两项都为 0 才是 unknown**：两项之和恒等于该组合计变化，
            // 若互相抵消则该组 delta = 0，在维度层根本选不中当焦点。
            // 两项非零、方向相反时正确的结论是 both（量价都在动，互相部分抵消）。
            if (total == 0.0) {
                verdict = "unknown"
                verdictText = "「$focus」的合计与笔数、笔均都没有变化。"
            } else {
                val volumeShare = kotlin.math.abs(volumeEffect) / total
                // 判据用占比而不是符号相消：+10000 笔数量与 −1000 笔均相加仍是 +9000，
                // 只看净变化会把"量增价减"描述成"增长"
                verdict = when {
                    volumeShare >= 0.7 -> "volume"
                    volumeShare <= 0.3 -> "price"
                    else -> "both"
                }
                val dominant = when (verdict) {
                    "volume" -> "笔数"
                    "price" -> "笔均"
                    else -> "笔数与笔均"
                }
                val share = if (verdict == "both") {
                    "两项各占约 ${"%.0f".format(volumeShare * 100)}% / ${"%.0f".format((1 - volumeShare) * 100)}%"
                } else {
                    "${"%.0f".format(kotlin.math.max(volumeShare, 1 - volumeShare) * 100)}%"
                }
                verdictText =
                    "「$focus」的变化主要来自$dominant（$share）。" +
                        "这只是数据层面的归因，为什么笔${if (verdict == "price") "均" else "数"}变了需要结合业务判断。"
            }
        }
    }

    return AttributionChain(
        rootPeriod = period,
        rootPrevious = previous.month,
        rootMetric = metric,
        rootBefore = previous.total,
        rootAfter = current.total,
        rootChange = change,
        links = links,
        verdict = verdict,
        verdictText = verdictText,
    )
}
