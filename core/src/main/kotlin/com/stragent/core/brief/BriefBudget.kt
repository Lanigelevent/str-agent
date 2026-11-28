package com.stragent.core.brief

import com.stragent.core.csv.ColumnType
import com.stragent.core.csv.Csv
import com.stragent.core.dataset.ColumnStat
import com.stragent.core.dataset.Dataset
import com.stragent.core.dataset.aggregateBy
import com.stragent.core.dataset.analyzeQuality
import com.stragent.core.dataset.correlate
import com.stragent.core.dataset.detectAllOutliers
import com.stragent.core.dataset.looksLikeIdColumn
import com.stragent.core.dataset.pickDefaultColumns
import com.stragent.core.dataset.scoreQuality
import com.stragent.core.dataset.summarize
import com.stragent.core.report.AnomalyKind
import com.stragent.core.report.ReportLib
import com.stragent.core.report.Severity
import com.stragent.core.report.scanAnomalies
import com.stragent.core.stats.computeExtremes
import com.stragent.core.stats.ExtremeLocation
import com.stragent.core.stats.ExtremePair
import com.stragent.core.stats.isSeasonal
import com.stragent.core.stats.monthKey
import com.stragent.core.stats.yearOverYear
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.log2
/**
 * planBriefBudget —— 简报预算规划（T-148 ⑤ 自 DatasetBrief.kt 拆出，行为逐字节不变）。
 *
 * 三层职责：算总预算（数据规模 × 密度/行业/类型系数压缩）→ 按分层份额拆列数/聚合/样本/白名单配额。
 * floor 为包内共享 helper（internal，DatasetBrief.kt 同包直用）；BriefLimits 是 brief 全包上限常量。
 */

/** 预算与上限常量（BRIEF_BUDGET_MIN/MAX、HEADROOM 等） */
internal object BriefLimits {
    const val MIN = 5000
    const val MAX = 15000
    const val HEADROOM = 1000
    const val MONTHLY_MOM_MAX = 12
    const val ANOMALY_SCAN_MAX = 6
    const val ANOMALY_ITEM_CHARS = 90
    const val MONTHLY_CREDIBLE_MIN_TEXT = 20
    const val MIN_ROWS_TEXT = 20
    const val TIME_DIM_MAX_CELLS = 120
    const val EXTRA_SHEETS_MAX = 12
}

private val LAYER_SHARE = mapOf(
    "columns" to 0.4,
    "aggregate" to 0.24,
    "outlier" to 0.1,
    "sample" to 0.13,
    "whitelist" to 0.04,
)

private fun clamp(v: Double, min: Double, max: Double): Double = minOf(maxOf(v, min), max)
private fun clampI(v: Int, min: Int, max: Int): Int = minOf(maxOf(v, min), max)

private fun mustAnswerCount(industryId: String): Int {
    val industry = ReportLib.industries.find { it.id == industryId }
    val block = Regex("<must_answer>([\\s\\S]*?)</must_answer>").find(industry?.prompt ?: "")
    if (block == null) return 4
    return maxOf(Regex("[①②③④⑤⑥⑦⑧⑨⑩]").findAll(block.groupValues[1]).count(), 2)
}

private fun compress(factor: Double): Double {
    if (factor <= 0) return 1.0
    return 1 + log2(factor) * 0.38
}

data class BriefBudget(
    val maxColumns: Int,
    val detail: String, // "full" | "compact"
    val groupTopN: Int,
    val groupDimensions: Int,
    val crossCells: Int,
    val monthlyPoints: Int,
    val outlierColumns: Int,
    val sampleRows: Int,
    val listedNumbers: Int,
    val total: Int,
)

fun planBriefBudget(
    dataset: Dataset,
    industryId: String,
    typeId: String,
    types: com.stragent.core.report.ReportTypeRegistry = com.stragent.core.report.ReportTypeRegistry.BUILT_IN,
): BriefBudget {
    val profile = profileDataset(dataset)
    val rows = maxOf(profile.validRows, 1).toDouble()
    val cols = maxOf(profile.validColumns, 1)

    val total = Math.round(
        clamp(BriefLimits.MIN + cols * 80 + log10(rows) * 400, BriefLimits.MIN.toDouble(), BriefLimits.MAX.toDouble()),
    ).toInt()

    val densityFactor = if (profile.density >= 0.85) 1.0 else clamp(profile.density / 0.85, 0.5, 1.0)
    val answerFactor = 0.8 + mustAnswerCount(industryId) * 0.06
    // 报告类型系数登记在 ReportTypeRegistry（T-058）：新增类型不改本函数
    val typeFactor = types.profileOf(typeId).briefFactor
    val scale = compress(densityFactor * answerFactor) * compress(typeFactor)
    val scaled = Math.round(clamp(total * scale, BriefLimits.MIN.toDouble(), BriefLimits.MAX.toDouble())).toInt()

    val columnBudget = scaled * LAYER_SHARE["columns"]!!
    val affordable = floor(columnBudget / 90).toInt()
    val detail = if (cols <= affordable) "full" else "compact"
    val maxColumns = minOf(cols.toInt(), maxOf(floor(columnBudget / (if (detail == "full") 90 else 65)).toInt(), 12))

    val aggregateBudget = scaled * LAYER_SHARE["aggregate"]!!
    val groupTopN = when {
        aggregateBudget > 1800 -> 10
        aggregateBudget > 1300 -> 8
        aggregateBudget > 900 -> 6
        else -> 4
    }
    val monthlyPoints = when {
        aggregateBudget > 2400 -> 36
        aggregateBudget > 1800 -> 24
        aggregateBudget > 1300 -> 18
        else -> 12
    }
    val outlierColumns = if (aggregateBudget > 1000) 3 else 2

    val sampleBudget = scaled * LAYER_SHARE["sample"]!!
    val perRow = maxOf(cols * 7, 40)
    val sampleRows = clampI(floor(sampleBudget / perRow).toInt(), 3, minOf(rows.toInt(), 30))

    val perDimension = maxOf(groupTopN * 45, 120)
    val groupDimensions = clampI(floor(aggregateBudget / perDimension).toInt() - 1, 0, 4)

    val crossCells = if (aggregateBudget > 2000 && cols >= 4) 12 else 0

    val listedNumbers = clampI(floor(scaled * LAYER_SHARE["whitelist"]!! / 2.5).toInt(), 60, 200)

    return BriefBudget(maxColumns, detail, groupTopN, groupDimensions, crossCells, monthlyPoints, outlierColumns, sampleRows, listedNumbers, scaled)
}

internal fun floor(v: Double): Double = kotlin.math.floor(v)
