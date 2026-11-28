package com.stragent.core.report

import com.stragent.core.brief.formatNumber

/**
 * 目标篇幅与输出天花板 —— ai-insight.ts 的 targetLengthFor / estimateTokens /
 * outputCeilingFor 移植（targetLengthFor 的算法体在 dataset-brief.ts）。
 *
 * targetLengthFor 的纪律：基数是**可引用事实数**（不是风格字数），
 * 分组按组数 × 角度层数计并按样本量折价；executive/bulletin 短体型按类型上限。
 */
private const val CONTEXT_WINDOW_TOKENS = 100_000
private const val TARGET_CHARS_MAX = 120_000
private const val SINGLE_SHOT_CHARS = 9000

private const val DIMENSION_TIERS_COUNT = 4

fun estimateTokens(text: String): Int = Math.ceil(text.length / 1.4).toInt()

data class TargetLength(val chars: Int, val scale: String)

private fun clampI(v: Int, min: Int, max: Int): Int = minOf(maxOf(v, min), max)
private fun clampD(v: Double, min: Double, max: Double): Double = minOf(maxOf(v, min), max)

/** 主分组段的组数合计（按「按「X」分组求和（…单组均值…）：」标题后的 - 行数） */
private fun sumPrimaryGroupSizes(brief: String): Int {
    val seen = mutableSetOf<String>()
    val lines = brief.split('\n')
    var total = 0
    for (index in lines.indices) {
        val header = Regex("^按「([^」]+)」分组求和（.*单组均值.*）：$").find(lines[index]) ?: continue
        if (!seen.add(header.groupValues[1])) continue
        var count = 0
        for (next in index + 1 until lines.size) {
            if (!lines[next].startsWith("- ")) break
            count += 1
        }
        total += count
    }
    return total
}

fun targetLengthFor(
    brief: String,
    rows: Int,
    columns: Int,
    numericColumns: Int,
    groupColumns: Int,
    styleLength: String,
    typeId: String,
    registry: ReportTypeRegistry = ReportTypeRegistry.BUILT_IN,
): TargetLength {
    // 风格字数：只用来算倾斜
    val styleChars = Regex("(\\d[\\d,]*)\\s*字").find(styleLength)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: 800

    val profile = registry.profileOf(typeId)
    if (profile.shortForm) {
        return TargetLength(styleChars, "${formatNumber(rows.toDouble())} 行 × $columns 列（短体型，按类型上限）")
    }

    // ① 可引用事实数（正则口径与 TS 逐字一致）
    val monthlyPoints = minOf(Regex("\\d{4}-\\d{2} [\\d,.]+").findAll(brief).count(), 24)
    val extremeColumns = Regex("：最大 .+；最小 ").findAll(brief).count()
    val outlierColumns = Regex("：\\d+ 个，最极端").findAll(brief).count()
    val correlations = Regex("：r=-?[\\d.]+（n=").findAll(brief).count()
    val secondGroups = Regex("^按「[^」]+」分组求和（[^）]*，全量数据）：$", setOf(RegexOption.MULTILINE)).findAll(brief).count()
    val yearlyPoints = Regex("^- \\d{4} 年：合计", setOf(RegexOption.MULTILINE)).findAll(brief).count()
    val crossCells = Regex("^- .+ × .+：合计 ", setOf(RegexOption.MULTILINE)).findAll(brief).count() / 2
    val yearlyCrossPoints = Regex("^- .+：\\d{4} 年 [\\d,.]+", setOf(RegexOption.MULTILINE)).findAll(brief)
        .sumOf { line -> Regex("\\d{4} 年 [\\d,.]+").findAll(line.value).count() }
    val extraSheetPoints = Regex("^- 「[^」]+」：\\d+ 行 × \\d+ 列", setOf(RegexOption.MULTILINE)).findAll(brief).count()
    val rowBonus = when {
        rows >= 5000 -> 6
        rows >= 1000 -> 3
        rows >= 200 -> 1
        else -> 0
    }

    val rawGroups = Math.round(sumPrimaryGroupSizes(brief).toDouble()).toInt()
    val volumeFactor = when {
        rows >= 5000 -> 1.0
        rows >= 1000 -> 0.7
        rows >= 300 -> 0.45
        rows >= 100 -> 0.25
        else -> 0.1
    }
    val groupFacts = rawGroups * volumeFactor
    val tierDepth = if (secondGroups > 0) DIMENSION_TIERS_COUNT else 1
    val crossFacts = crossCells * volumeFactor
    val yearlyCrossFacts = yearlyCrossPoints * volumeFactor
    val monthlyFacts = monthlyPoints * volumeFactor
    val facts = numericColumns +
        groupFacts * 3 * tierDepth +
        monthlyFacts +
        extremeColumns +
        outlierColumns +
        correlations * 2 +
        rowBonus +
        secondGroups +
        yearlyPoints +
        crossFacts +
        yearlyCrossFacts +
        extraSheetPoints * 4
    val factCount = Math.round(facts).toInt()
    val base = 400 + factCount * 90

    // ② 报告形态（类型系数在 [ReportTypeRegistry] 登记——新增类型不改编排算法）
    val typeFactor = profile.lengthFactor

    // ③ 文风倾斜（只调松紧）
    val styleTilt = clampD(styleChars / 600.0, 0.7, 1.3)

    val scale = "${formatNumber(rows.toDouble())} 行 × $columns 列，" +
        "可分析数值列 $numericColumns 个、可分组维度 $groupColumns 个（共 $rawGroups 组）" +
        "、月度点 $monthlyPoints 个、维度可拆 $tierDepth 层，共约 $factCount 条可引用事实"

    return TargetLength(clampI(Math.round(base * typeFactor * styleTilt).toInt(), 300, TARGET_CHARS_MAX), scale)
}

/**
 * 报告输出预算下限（T-062）：推理模型会先把 `max_tokens` 花在思维链上，且**思维链长度与目标字数无关**——
 * 真实抽样里目标 300 字的 executive 吃满 8192、trend 吃满 16384，正文都是 0 字。
 * 「按目标 ×3 预留」对推理模型不成立，下限直接给到 cap：`max_tokens` 是上限不是目标，
 * 非推理模型不会因此多付（模型自己会停）。端点若以 400 拒绝该上限，
 * 编排层按 [REPORT_BUDGET_FALLBACK_TOKENS] 再打一次。
 */
const val REPORT_BUDGET_FLOOR_TOKENS = 32_768

/** 端点拒绝高预算时的回落值（T-062）：T-049 以来的旧下限，兼容只接受 8k 输出的模型 */
const val REPORT_BUDGET_FALLBACK_TOKENS = 8_192

/** 输出天花板：目标 ×3 与 floor 32768（T-062；T-049 起为 8192）取高、cap 32768，上下文保险（100K 窗口 5% 余量） */
fun outputCeilingFor(chars: Int, inputText: String = ""): Int {
    val byTarget = Math.round(chars * 3.0).toInt()
    var ceiling = minOf(maxOf(byTarget, REPORT_BUDGET_FLOOR_TOKENS), 32768)
    val inputTokens = estimateTokens(inputText)
    val room = Math.floor(CONTEXT_WINDOW_TOKENS * 0.95).toInt() - inputTokens
    if (room < ceiling) {
        ceiling = maxOf(minOf(room, 32768), 4096)
    }
    return ceiling
}

/** 单次生成上限（超过走分章） */
const val SINGLE_SHOT_CHARS_LIMIT = SINGLE_SHOT_CHARS
