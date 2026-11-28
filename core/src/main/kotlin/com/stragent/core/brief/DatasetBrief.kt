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
 * buildDatasetBrief —— src/shared/lib/dataset-brief.ts 的 Kotlin 移植（核心路径）。
 *
 * 本移植按「门 B 逐字节」的目标对齐 TS 实现：
 *  - 预算规划（planBriefBudget）与 LAYER_SHARE 分层
 *  - 列清单 / 极值定位 / 月度序列与环比 / 数据质量 / 异常值 IQR
 *  - 分组聚合（主指标 + 第二指标 + 双指标派生）
 *  - 年度 × 维度 / 月度 × 维度（TIME_DIM_MAX_CELLS 共享上限）
 *  - 年度汇总与同比 / 异常自动扫描段（含同比 suffix）/ 数字白名单 / 样本段
 *
 * 暂未覆盖（TS 里依赖 fullStats / extraSheets / analyzeQuality / detectAllOutliers
 * 的段——这些模块在后续批次移植，接入点已留好）：
 *  - fullStats 全量统计替换（truncate 场景）
 *  - extraSheets 附加表段
 *  - analyzeQuality / scoreQuality 数据质量段
 *  - detectAllOutliers IQR 异常值段
 */

/** 宽表挑列：数值 6 : 文本 4，保原表列序 */
private fun pickBriefColumns(stats: List<ColumnStat>, limit: Int): List<ColumnStat> {
    val numericQuota = maxOf(floor(limit * 0.6).toInt(), 4)
    val textQuota = maxOf(limit - numericQuota, 4)
    val numeric = stats.filter { it.type == ColumnType.NUMBER && !looksLikeIdColumn(it.name) }.take(numericQuota)
    val text = stats.filter { it.type == ColumnType.TEXT && !looksLikeIdColumn(it.name) }.take(textQuota)
    val keep = (numeric + text).map { it.name }.toSet()
    return stats.filter { it.name in keep }
}

/** 样本 CSV 片段（含引号保护）。注入过滤在入口 [buildDatasetBrief] 的数据集安全化统一完成。 */
private fun sampleCsv(dataset: Dataset, columnNames: List<String>?, rowLimit: Int): String {
    val picked = if (columnNames == null) {
        dataset.columns.indices.toList()
    } else {
        columnNames.map { dataset.columns.indexOf(it) }.filter { it >= 0 }
    }
    val use = if (picked.isNotEmpty()) picked else dataset.columns.indices.toList()
    val lines = mutableListOf(use.joinToString(dataset.delimiter.toString()) { dataset.columns[it] })
    for (row in dataset.rows.take(rowLimit)) {
        lines.add(
            use.joinToString(dataset.delimiter.toString()) { index ->
                val cell = row.getOrNull(index) ?: ""
                if (cell.contains(dataset.delimiter)) "\"$cell\"" else cell
            },
        )
    }
    return lines.joinToString("\n")
}

/**
 * 构建数据集简报（T-067 起含数据面注入安全化）。
 *
 * 入口先把数据集交给 [com.stragent.core.security.PromptInjectionHeuristics.sanitizeForBrief]：
 * 命中注入模式的单元格**全局**替换为 `[已过滤:Rn]` 占位符（样本段/画像/质量段零泄漏），
 * 数字与统计不受影响；零命中时拿到原对象，简报逐字节与改动前一致（门 B）。
 *
 * @param metrics 指标定义（T-055）：可解析且自检通过的指标值写进摘要，
 * 传空（默认）= 行为与本改动前逐字节一致（门 B 基线）。
 */
fun buildDatasetBrief(
    dataset: Dataset,
    prefs: com.stragent.core.ports.PrefsStore? = null,
    metrics: List<com.stragent.core.semantic.MetricDef> = emptyList(),
    /**
     * 报告类型登记表（T-145）：**这条参数此前是死的**——叶子函数 [planBriefBudget] 有
     * `types` 参数，却从没有任何调用方传过，恒为 `BUILT_IN`。现在它**真的被转发下去**，
     * 宿主注册的自定义报告类型才会影响简报预算（`briefFactor`）。
     */
    types: com.stragent.core.report.ReportTypeRegistry = com.stragent.core.report.ReportTypeRegistry.BUILT_IN,
): String {
    val (safeDataset, injectionHits) = com.stragent.core.security.PromptInjectionHeuristics.sanitizeForBrief(dataset)
    return buildDatasetBriefInner(safeDataset, prefs, metrics, injectionHits, types)
}

private fun buildDatasetBriefInner(
    dataset: Dataset,
    prefs: com.stragent.core.ports.PrefsStore?,
    metrics: List<com.stragent.core.semantic.MetricDef>,
    injectionHits: Int,
    types: com.stragent.core.report.ReportTypeRegistry,
): String {
    // 偏好从 PrefsStore 读（与 TS 的 getIndustry/getReportType 同源）；不传 = 默认（general/standard）
    val industryId = prefs?.let { ReportLib.getIndustry(it).id } ?: "general"
    val typeId = prefs?.let { ReportLib.getReportType(it).id } ?: "standard"
    val stats = summarize(dataset)
    val budget = planBriefBudget(dataset, industryId, typeId, types)
    val numeric = stats.filter { it.type == ColumnType.NUMBER }
    val text = stats.filter { it.type == ColumnType.TEXT }

    // 日期列扫一次
    val dateRanges = LinkedHashMap<Int, DateRange>()
    for (index in dataset.columns.indices) {
        if (dataset.columnTypes[index] != ColumnType.TEXT) continue
        dateRangeOf(dataset, index)?.let { dateRanges[index] = it }
    }
    val defaultCategory = pickDefaultColumns(dataset).category
    val groupIndex = if (dateRanges.containsKey(defaultCategory)) -1 else defaultCategory
    val dateIndex = dateRanges.keys.firstOrNull() ?: -1

    val sampling = dataset.sampling
    val shape = "（数值列 ${numeric.size} 个，文本列 ${text.size} 个）"
    // 注意：非抽样数据集（含旧 paste 导入的 1 万行截断）必须保持 TS 基线逐字节一致，
    // 不得在这里追加「源文件 N 行」——门 B（BriefBaselineGate）会当场判红。
    // 截断事实由导入回执的 truncated/sourceRows 字段对外交代；抽样数据集走独立分支。
    val scaleLine = when {
        sampling != null ->
            "规模：源文件 ${sampling.totalRows} 行 × ${dataset.columns.size} 列$shape；本次分析使用**确定性系统抽样** " +
                "${dataset.rows.size} 行（每 ${sampling.step} 行取 1 行，起点第 1 个数据行；文件指纹 ${sampling.fingerprint}）"
        else -> "规模：${dataset.rows.size} 行 × ${dataset.columns.size} 列$shape"
    }
    val lines = mutableListOf(
        "数据来源：${dataset.name}",
        scaleLine,
    )
    if (sampling != null) {
        val share = toFixed1(dataset.rows.size * 100.0 / sampling.totalRows)
        lines.add(
            "抽样口径：下面所有统计、分组、极值、质量评分都只基于这 ${dataset.rows.size} 行样本" +
                "（约占源文件数据行的 $share%），**不是全量**——任何合计、均值、占比都要写成" +
                "「基于 ${dataset.rows.size} 行系统抽样」的样本口径，不得写成全量结论。",
        )
        lines.add(
            "行号换算：文中「第 N 行」是样本内数据行号；对应源文件数据行号 = (N − 1) × ${sampling.step} + 1" +
                "（系统抽样固定步长）。逐行映射见 stragent_list_rows 的 sourceRow 字段。",
        )
    }
    lines.add("")
    lines.add("列清单：")

    val scopeData = if (sampling != null) "抽样数据" else "全量数据"
    val scopeLabel = if (sampling != null) "样本口径" else "全量"
    val extremeScope = if (sampling != null) "样本口径" else "全量口径"

    /** TS 的 lines.push(a, b, ...) 多参形态 */
    fun push(vararg items: String) { items.forEach { lines.add(it) } }

    val listed = if (stats.size > budget.maxColumns) pickBriefColumns(stats, budget.maxColumns) else stats
    if (listed.size < stats.size) {
        push(
            "（共 ${stats.size} 列，下面列 ${listed.size} 列——优先列数值列与分类列，" +
                "${stats.size - listed.size} 个 ID / 索引 / 编号类列略过）",
        )
    }
    val indexOf = stats.withIndex().associate { (i, s) -> s to i }
    for (stat in listed) {
        if (looksLikeIdColumn(stat.name)) {
            push("- ${stat.name}（${if (stat.type == ColumnType.NUMBER) "数值" else "文本"}，标识列，略过统计）")
            continue
        }
        if (stat.type == ColumnType.NUMBER) {
            val quartiles = if (budget.detail == "full") {
                "，P25 ${formatNumber(stat.p25 ?: 0.0)}，中位数 ${formatNumber(stat.median ?: 0.0)}，P75 ${formatNumber(stat.p75 ?: 0.0)}，"
            } else {
                "，中位数 ${formatNumber(stat.median ?: 0.0)}，"
            }
            push(
                "- ${stat.name}（数值）：有效样本 ${stat.count}${if (stat.missing > 0) "（${stat.missing} 个空值或不可解析）" else ""}，" +
                    "最小 ${formatNumber(stat.min ?: 0.0)}$quartiles" +
                    "最大 ${formatNumber(stat.max ?: 0.0)}，均值 ${formatNumber(stat.mean ?: 0.0)}，" +
                    "合计 ${formatNumber(stat.sum ?: 0.0)}",
            )
        } else {
            val top = (stat.topValues ?: emptyList()).take(3).joinToString("、") { "${it.value}(${it.count})" }
            val distinct = if (stat.distinct == null) "" else "，${stat.distinct} 个不同值"
            push(
                "- ${stat.name}（文本）：非空 ${stat.count}${if (stat.missing > 0) "（${stat.missing} 个空值）" else ""}$distinct，高频值：${top.ifEmpty { "无" }}",
            )
            val columnIndex = indexOf[stat]
            val range = columnIndex?.let { dateRanges[it] }
            if (range != null) push("  时间范围：${range.min} ~ ${range.max}（${range.count} 个非空值）")
        }
    }

    // 多块拼接提示（必须在极值定位之前——后者只统计主块）
    val blocks = detectRowBlocks(dataset, groupIndex)
    if (blocks != null) {
        push(
            "",
            "数据结构提示：「${blocks.labelColumn}」列有 ${blocks.labeled} 行有对象名，另有 ${blocks.shifted} 行该列为空、" +
                "对象名写在了相邻列「${dataset.columns[groupIndex + 1]}」里。这是**拼进同一张表的另一组数据**，" +
                "两组共用后面的列但口径可能不同（例如一组存绝对量、一组存变化量）。" +
                "做统计、极值比较、分组对比时必须**分开说明**，不要把两组混成一个总数或一个最大值；" +
                "下面的极值定位只统计「${blocks.labelColumn}」列非空的那 ${blocks.labeled} 行。",
        )
    }

    // 极值定位：TS 的守卫是 `full !== undefined && full.totalRows > rows` 才不给——
    // 没有 fullStats 时（基线/导入未带全量统计的场景）一律现场算
    val extremes = computeExtremes(dataset, if (groupIndex >= 0) groupIndex else -1, dateIndex)
    if (!extremes.isNullOrEmpty()) {
        push("", "关键数值列的极值定位（${extremeScope}，行号按数据行计、不含表头；与上面的列统计同口径）：")
        for (pair in extremes) {
            fun where(spot: ExtremeLocation): String {
                val parts = mutableListOf("第 ${spot.row} 行")
                spot.group?.let { parts.add(it) }
                spot.column?.let { parts.add(it) }
                spot.date?.let { parts.add(it) }
                return "${formatNumber(spot.value)}（${parts.joinToString("，")}）"
            }
            val high = pair.max?.let { "最大 ${where(it)}" } ?: ""
            val low = pair.min?.let { "最小 ${where(it)}" } ?: ""
            push("- ${pair.column}：${listOf(high, low).filter { it.isNotEmpty() }.joinToString("；")}")
            if (pair.top != null && pair.top.size > 1) {
                push("  量值最大的前 ${pair.top.size} 个观测：${pair.top.joinToString("、") { where(it) }}")
            }
        }
    }

    val valueIndexInNumeric = numeric.indexOfFirst { !looksLikeIdColumn(it.name) }
    val resolvedValue = if (valueIndexInNumeric >= 0) stats.indexOf(numeric[valueIndexInNumeric]) else -1

    // 月度序列
    val cellSeries = if (dateIndex >= 0 && resolvedValue >= 0) monthlySeriesBrief(dataset, dateIndex, resolvedValue, budget.monthlyPoints) else null
    val columnSeries = if (cellSeries == null) monthlyFromColumnNames(dataset, budget.monthlyPoints) else null
    val series = cellSeries ?: columnSeries
    if (series != null) {
        val points = series.joinToString("、") { "${it.label} ${formatNumber(it.sum)}" }
        val label = if (columnSeries != null) {
            "按月均值（列名即日期的宽表，取当月各日均值）"
        } else {
            "按月汇总（${dataset.columns[resolvedValue]}，最近 ${series.size} 个月）"
        }
        push("", "$label：$points")

        if (series.size >= 3) {
            val peak = series.reduce { best, p -> if (p.sum > best.sum) p else best }
            val trough = series.reduce { worst, p -> if (p.sum < worst.sum) p else worst }
            val total = series.sumOf { it.sum }
            val first = series.first()
            val last = series.last()
            val share = if (total != 0.0) "（占这 ${series.size} 个月合计的 ${toFixed1(peak.sum / total * 100)}%）" else ""
            val change = if (first.sum != 0.0) {
                "首月 ${first.label} ${formatNumber(first.sum)} → 末月 ${last.label} ${formatNumber(last.sum)}，" +
                    "${if (last.sum >= first.sum) "+" else ""}${toFixed1((last.sum - first.sum) / abs(first.sum) * 100)}%"
            } else {
                "首月 ${first.label} ${formatNumber(first.sum)} → 末月 ${last.label} ${formatNumber(last.sum)}"
            }
            push(
                "月度极值（与上面同口径）：峰值 ${peak.label} ${formatNumber(peak.sum)}$share，" +
                    "谷值 ${trough.label} ${formatNumber(trough.sum)}；$change",
            )
        }

        if (series.size >= 3) {
            val momStart = maxOf(1, series.size - BriefLimits.MONTHLY_MOM_MAX)
            push(
                "",
                "月度环比（与上面同口径；本月 = (本月 - 上月) ÷ |上月|，符号表示方向；" +
                    "只列最近 ${series.size - momStart} 个月，更早的对照上面的逐月值）：",
            )
            for (index in momStart until series.size) {
                val previous = series[index - 1]
                val current = series[index]
                if (previous.sum == 0.0) continue
                val change = (current.sum - previous.sum) / abs(previous.sum) * 100
                push(
                    "  ${current.label} 环比：${if (change >= 0) "+" else ""}${toFixed1(change)}%" +
                        "（${formatNumber(previous.sum)} → ${formatNumber(current.sum)}）",
                )
            }
        }
    }

    // 数据质量：质量报告类型要求"总体评分 + 分类问题"，摘要不给分数模型只能编
    val quality = analyzeQuality(dataset)
    val score = scoreQuality(dataset)
    val problems = mutableListOf<String>()
    if (quality.duplicateRows > 0) problems.add("重复行 ${quality.duplicateRows} 行")
    for (item in quality.worstColumns) {
        problems.add("${item.column} 空值 ${toFixed1(item.emptyRate * 100)}%")
    }
    val unparsable = stats
        .filter { it.type == ColumnType.NUMBER && it.missing > 0 }
        .sortedByDescending { it.missing }
        .take(3)
    if (unparsable.isNotEmpty()) {
        problems.add("数值列有无法解析的单元格：" + unparsable.joinToString("、") { "${it.name} ${it.missing} 个" })
    }
    if (quality.consistency.isNotEmpty()) {
        for (issue in quality.consistency) {
            val forms = issue.forms.joinToString("、") { "${it.shape} ${it.count} 个" }
            problems.add("${issue.column} 书写形态不统一（$forms）")
        }
    }
    if (quality.constantColumns.isNotEmpty()) {
        problems.add("常量列（全表同一取值，分组无效）：${quality.constantColumns.joinToString("、")}")
    }
    push(
        "",
        "数据质量（${scopeLabel}）：完整性 ${formatNumber(score.dimensions[0].value)} / 100，唯一性 ${formatNumber(score.dimensions[1].value)} / 100，" +
            "综合 ${formatNumber(score.average)} / 100；${if (problems.isNotEmpty()) problems.joinToString("；") else "无空值、无重复行"}",
    )

    // 异常值（IQR 1.5 倍）：按"最极端偏离多少 IQR"排序取前 N 列
    val outliers = detectAllOutliers(dataset)
        .sortedByDescending { it.outliers.first().deviation }
        .take(budget.outlierColumns)
    if (outliers.isNotEmpty()) {
        push(
            "",
            "异常值（IQR 1.5 倍口径，${scopeLabel}；行号按数据行计，不含表头。注意：右偏分布下 IQR 会标出较多高值，" +
                "\"异常个数多\"不等于数据有问题，要结合业务判断）：",
        )
        for (report in outliers) {
            val first = report.outliers.first()
            push(
                "- ${report.column}：${report.outliers.size} 个，最极端 ${formatNumber(first.value)}" +
                    "（${if (first.bound == "high") "偏高" else "偏低"} ${toFixed1(first.deviation)} 个 IQR，第 ${first.rowIndex + 1} 行）；" +
                    "正常区间 ${formatNumber(report.lower)} ~ ${formatNumber(report.upper)}",
            )
        }
    } else {
        push("", "异常值（IQR 1.5 倍口径，${scopeLabel}）：未发现异常值。")
    }

    // 分组聚合（主指标 + 第二指标 + 双指标派生）
    if (groupIndex >= 0 && resolvedValue >= 0) {
        val extraDims = pickExtraDimensions(dataset, groupIndex, budget.groupDimensions)
        val mainStat = numeric.getOrNull(valueIndexInNumeric)
        val usableSecond = numeric.filter {
            !looksLikeIdColumn(it.name) && stats.indexOf(it) != resolvedValue && it.count >= (mainStat?.count ?: 0) * 0.9
        }
        val secondValue = if (usableSecond.isNotEmpty()) stats.indexOf(usableSecond.last()) else -1

        for (dim in listOf(groupIndex) + extraDims) {
            val grouped = aggregateBy(dataset, dim, resolvedValue, "sum", budget.groupTopN)
            if (grouped.size < 2) continue
            push(
                "",
                "按「${dataset.columns[dim]}」分组求和（${dataset.columns[resolvedValue]}，${scopeData}；" +
                    "末列为单组均值＝该组合计 ÷ 该组行数）：",
            )
            for (item in grouped) {
                val share = if (item.share.isFinite()) "（${toFixed1(item.share * 100)}%）" else ""
                push(
                    "- ${item.label}：合计 ${formatNumber(item.value)}$share，${item.count} 行" +
                        "，单组均值 ${formatNumber(if (item.count > 0) item.value / item.count else 0.0)}",
                )
            }

            if (secondValue >= 0) {
                val second = aggregateBy(dataset, dim, secondValue, "sum", budget.groupTopN)
                val byLabel = second.associateBy { it.label }
                val matched = grouped.mapNotNull { byLabel[it.label] }
                if (matched.size >= 2) {
                    push("", "按「${dataset.columns[dim]}」分组求和（${dataset.columns[secondValue]}，${scopeData}）：")
                    for (item in matched) {
                        push("- ${item.label}：合计 ${formatNumber(item.value)}，${item.count} 行")
                    }
                }
            }

            if (secondValue >= 0) {
                val second = aggregateBy(dataset, dim, secondValue, "sum", budget.groupTopN)
                val secondByLabel = second.associateBy { it.label }
                val derived = grouped.mapNotNull { item ->
                    val other = secondByLabel[item.label] ?: return@mapNotNull null
                    if (other.value == 0.0) return@mapNotNull null
                    item.label to (item to other)
                }
                if (derived.size >= 2) {
                    push(
                        "",
                        "按「${dataset.columns[dim]}」分组的双指标派生" +
                            "（${dataset.columns[resolvedValue]} 与 ${dataset.columns[secondValue]}，${scopeData}）：",
                    )
                    for ((label, pair) in derived) {
                        val (primary, other) = pair
                        val ratio = other.value / primary.value * 100
                        // 两个单均都用主指标的行数做分母（TS 口径：该组行数，第二指标可能缺几行脏值）
                        val primaryMean = if (primary.count > 0) primary.value / primary.count else 0.0
                        val secondMean = if (primary.count > 0) other.value / primary.count else 0.0
                        push(
                            "- $label：利润率 ${toFixed1(ratio)}%" +
                                "（${formatNumber(other.value)} ÷ ${formatNumber(primary.value)}）" +
                                "，主指标单均 ${formatNumber(primaryMean)}，第二指标单均 ${formatNumber(secondMean)}" +
                                "，${primary.count} 行",
                        )
                    }
                }
            }
        }
    }

    // 年度 × 维度（YEAR_DIM 限额）
    val YEAR_DIM_MAX_GROUPS = 12
    val YEAR_DIM_MAX_CELLS = 30
    var timeCells = 0
    if (dateIndex >= 0 && resolvedValue >= 0) {
        val yearDims = (listOf(groupIndex) + pickExtraDimensions(dataset, groupIndex, budget.groupDimensions))
            .map { it to yearByDimension(dataset, dateIndex, it, resolvedValue) }
            .filter { (_, rows) -> rows.size >= 2 && rows.size <= YEAR_DIM_MAX_GROUPS }
            .filter { (_, rows) -> rows.size * (rows.first().years.size) <= YEAR_DIM_MAX_CELLS }
            .take(3)
        for ((dim, rows) in yearDims) {
            push(
                "",
                "按「${dataset.columns[dim]}」分组的年度走势" +
                    "（${dataset.columns[resolvedValue]} 合计，${scopeData}；每组一行、行内各年）：",
            )
            for (row in rows) {
                val years = row.years.joinToString("、") { (y, v) -> "$y 年 ${formatNumber(v)}" }
                push("- ${row.label}：$years")
                timeCells += row.years.size
            }
        }
    }

    // 月度 × 维度（共享 TIME_DIM_MAX_CELLS）
    val MONTH_DIM_MAX_GROUPS = 8
    val MONTH_DIM_MAX_MONTHS = 18
    if (dateIndex >= 0 && resolvedValue >= 0) {
        val candidates = (listOf(groupIndex) + pickExtraDimensions(dataset, groupIndex, budget.groupDimensions))
            .map { dim ->
                val rows = monthByDimension(dataset, dateIndex, dim, resolvedValue)
                val months = rows.maxOfOrNull { it.months.size } ?: 0
                Triple(dim, rows, months)
            }
            .filter { (_, rows, _) -> rows.size >= 2 && rows.size <= MONTH_DIM_MAX_GROUPS }
        if (candidates.isNotEmpty()) {
            val left = maxOf(0, BriefLimits.TIME_DIM_MAX_CELLS - timeCells)
            val dims = if (left >= 24) 2 else 1
            val perDim = left / dims
            val monthDims = candidates.take(dims).map { (dim, rows, months) ->
                val keep = minOf(months, MONTH_DIM_MAX_MONTHS, maxOf(3, perDim / rows.size))
                val last = rows.maxOfOrNull { it.months.lastOrNull()?.first ?: "" } ?: ""
                val windowStart = shiftMonthLocal(last, -(keep - 1))
                val trimmed = rows.map { MonthByDim(it.label, it.months.filter { (m, _) -> m >= windowStart }) }
                Triple(dim, trimmed, keep)
            }
            for ((dim, rows, keep) in monthDims) {
                push(
                    "",
                    "按「${dataset.columns[dim]}」分组的月度走势" +
                        "（${dataset.columns[resolvedValue]} 合计，${scopeData}；每组一行、行内各月，" +
                        "取最近 $keep 个月）：",
                )
                for (row in rows) {
                    val months = row.months.joinToString("、") { (m, v) -> "$m ${formatNumber(v)}" }
                    push("- ${row.label}：$months")
                }
            }
        }
    }

    // 年度汇总与同比
    if (dateIndex >= 0 && resolvedValue >= 0) {
        val yearly = yearlySeries(dataset, dateIndex, resolvedValue)
        if (yearly.size >= 2) {
            push("", "按年汇总（${dataset.columns[resolvedValue]}，${scopeData}）：")
            val total = yearly.sumOf { it.value }
            for (item in yearly) {
                val share = if (total != 0.0) "（${toFixed1(item.value / total * 100)}%）" else ""
                push("- ${item.label} 年：合计 ${formatNumber(item.value)}$share，${item.count} 行")
            }
            for (index in 1 until yearly.size) {
                val previous = yearly[index - 1]
                val current = yearly[index]
                if (previous.value == 0.0) continue
                val change = (current.value - previous.value) / abs(previous.value) * 100
                push(
                    "  ${current.label} 年同比：${if (change >= 0) "+" else ""}${toFixed1(change)}%" +
                        "（${formatNumber(previous.value)} → ${formatNumber(current.value)}）",
                )
            }
        }
    }

    // 维度交叉
    if (groupIndex >= 0 && resolvedValue >= 0 && budget.crossCells > 0) {
        val secondDim = pickExtraDimensions(dataset, groupIndex, 1).firstOrNull()
        if (secondDim != null) {
            val cells = crossTabulateBrief(dataset, groupIndex, secondDim, resolvedValue, budget.crossCells)
            if (cells.size >= 4) {
                push(
                    "",
                    "维度交叉（「${dataset.columns[groupIndex]}」×「${dataset.columns[secondDim]}」，" +
                        "${dataset.columns[resolvedValue]} 合计，取行数最多的前 ${cells.size} 格）：",
                )
                for (cell in cells) {
                    push("- ${cell.a} × ${cell.b}：合计 ${formatNumber(cell.value)}，${cell.count} 行")
                }
            }
        }
    }

    // 相关性：报告要求"至少一条跨字段发现"；ID 列排除、|r|≥0.3、n≥30，取前 3
    if (numeric.size in 2..10 && dataset.rows.size <= 20000) {
        val strong = correlate(dataset)
            .filter {
                !looksLikeIdColumn(it.a) && !looksLikeIdColumn(it.b) && abs(it.r) >= 0.3 && it.n >= 30
            }
            .take(3)
        if (strong.isNotEmpty()) {
            push("", "数值列相关性（皮尔逊 r，可直接引用；只能说明同向或反向，不能说明因果）：")
            for (item in strong) {
                push("- ${item.a} × ${item.b}：r=${toFixed2(item.r)}（n=${item.n}）")
            }
        }
    }

    // ---------- 异常自动扫描段（含同比 suffix）----------
    val sampleRowChars = maxOf(60, dataset.columns.size * 15)
    var sampleRowsUsed = budget.sampleRows
    run {
        val anomaly = scanAnomalies(dataset, groupIndex)
        val yoy = yearOverYear(dataset)
        fun yoySuffix(finding: com.stragent.core.report.AnomalyFinding): String {
            if (finding.kind != AnomalyKind.SHIFT || finding.period == null) return ""
            val point = yoy?.points?.find { it.month == finding.period } ?: return ""
            if (point.change == null) {
                if (point.lastYear == null) {
                    return "（**没有同比可对照**：数据里不存在去年同月，无法判断这是季节性还是真变化）"
                }
                val rows = point.lastYearCount ?: 0
                return "（**同比不可比**：去年同月 ${point.lastYear} 只有 $rows 行，样本量不足" +
                    "（低于 ${BriefLimits.MIN_ROWS_TEXT} 行），无法判断这是季节性还是真变化）"
            }
            fun pct2(value: Double): String {
                val sign = if (value > 0) "+" else if (value < 0) "\u2212" else ""
                return "$sign${toFixed1(abs(value) * 100)}%"
            }
            val tail = if (isSeasonal(point)) {
                "——去年同期基本持平，**这很可能是季节性波动**，不要当作异常写进结论"
            } else {
                "——去年同期同样在变，说明这不是季节性，是真实变化"
            }
            return "（同比 ${pct2(point.change)}$tail）"
        }
        fun label(severity: Severity): String = when (severity) { Severity.HIGH -> "高危"; Severity.MEDIUM -> "中危"; Severity.LOW -> "低危" }
        val notable = anomaly.findings.filter { it.severity != Severity.LOW }
        val reason = when {
            anomaly.coverage.sparse ->
                "有 ${anomaly.coverage.months} 个月度点，但每月样本量太少（中位数低于 " +
                    "${BriefLimits.MONTHLY_CREDIBLE_MIN_TEXT} 行），环比与占比都是抽样抖动——时序突变 / 结构突变 / " +
                    "时间缺口 / 序列塌陷**不运行**，不要基于月度变化下任何结论"
            anomaly.coverage.monthly ->
                if (anomaly.findings.isNotEmpty()) "仅 ${anomaly.findings.size} 项低危，不影响结论" else "六类检测均未触发（数据形态正常）"
            else -> "没有可识别的日期列与数值列的组合，时序类检测（时序突变 / 结构突变 / 时间缺口 / 序列塌陷）未运行"
        }
        if (notable.isEmpty() || anomaly.coverage.sparse) {
            push("", "数据异常自动扫描：$reason。")
        } else {
            val usedSoFar = lines.joinToString("\n").length
            val tailEstimate = budget.listedNumbers * 10 + budget.sampleRows * sampleRowChars
            val room = BriefLimits.MAX - BriefLimits.HEADROOM - usedSoFar - tailEstimate
            val afford = maxOf(
                if (notable.isNotEmpty()) 1 else 0,
                minOf(BriefLimits.ANOMALY_SCAN_MAX, floor(room / BriefLimits.ANOMALY_ITEM_CHARS.toDouble()).toInt()),
            )
            val anomalyChars = if (afford > 0) afford * BriefLimits.ANOMALY_ITEM_CHARS + 200 else 60
            sampleRowsUsed = maxOf(4, minOf(budget.sampleRows, budget.sampleRows - ceil(anomalyChars / sampleRowChars.toDouble()).toInt()))
            val high = anomaly.summary.high
            if (afford == 0) {
                push(
                    "",
                    "数据异常自动扫描：检出 ${notable.size} 项中高危异常（含 $high 项高危）。" +
                        "本份摘要篇幅已满，未逐条列出。",
                )
            } else {
                push(
                    "",
                    "数据异常自动扫描（六类检测：时序突变 / 结构突变 / 时间缺口 / 主键重复 / 负值 / 序列塌陷；" +
                        "全部是确定性计算结果，不是模型判断，已按严重程度排序）：",
                )
                for (finding in notable.take(afford)) {
                    push("- [${label(finding.severity)}] ${finding.detail}${yoySuffix(finding)}")
                }
                val hidden = notable.size - afford
                if (hidden > 0) push("- （另有 $hidden 项中高危异常因篇幅未列出）")
                if (anomaly.summary.low > 0) {
                    push("- （另有 ${anomaly.summary.low} 项低危异常，不影响结论，未列出）")
                }
                push(
                    "  → 上面标「高危」的项**必须在报告里回应**：写进正文，或明确说明为什么不写。" +
                        "不许整段跳过——这些是已经算出来的事实，不是待验证的猜测。",
                )
            }
        }
    }

    // 指标口径段（T-055）：必须在 allowedNumbersBlock 之前——指标值要进数字白名单
    metricLayerBlock(dataset, metrics)?.let { push("", it) }

    val statsText = lines.joinToString("\n")
    push("", allowedNumbersBlock(statsText, budget.listedNumbers))

    // T-067：命中格注记（安全化在入口已完成；零命中时不多任何一行——门 B 逐字节一致）
    push(
        "",
        if (sampling != null) {
            "前 ${minOf(sampleRowsUsed, dataset.rows.size)} 行样本（样本内的前几行，不是源文件开头）："
        } else {
            "前 ${minOf(sampleRowsUsed, dataset.rows.size)} 行样本："
        },
        if (sampling != null) {
            "（样本只用于看字段形态与取值样式。任何占比、均值、合计、排名都必须引用上面的统计与分组数据；" +
                "禁止用这几十行自行聚合成\"全量\"或\"整份抽样\"结论。）"
        } else {
            "（样本只用于看字段形态与取值样式。任何占比、均值、合计、排名都必须引用上面的统计与分组数据；" +
                "禁止用这几十行样本自行聚合成\"全量\"结论。）"
        },
        "```",
        sampleCsv(dataset, listed.map { it.name }, sampleRowsUsed),
        *(if (injectionHits > 0) {
            arrayOf(
                "（注：样本与画像中共有 $injectionHits 个单元格命中提示注入模式，已替换为 [已过滤:Rn] 占位符——" +
                    "数据与统计未变，命中明细见导入回执 injectionScan。）",
            )
        } else {
            emptyArray()
        }),
        "```",
    )
    return lines.joinToString("\n")
}

/** 数字白名单段（附加表优先收集——本移植暂无附加表段，全量收集） */
private fun allowedNumbersBlock(briefText: String, limit: Int): String {
    val seen = LinkedHashSet<String>()
    for (match in Regex("\\d[\\d,]*(?:\\.\\d+)?").findAll(briefText)) {
        seen.add(match.value)
    }
    if (seen.isEmpty()) return "<allowed_numbers>这份数据里没有可用的数值。</allowed_numbers>"
    val head = seen.toList().take(limit)
    val tail = if (seen.size > limit) "\n（以及上面统计中出现的其余 ${seen.size - limit} 个数值）" else ""
    return "<allowed_numbers>**本报告只允许使用下面这些数字**（共 " + seen.size + " 个，全部来自上面的统计）：\n" +
        head.joinToString("、") + tail +
        "\n规则：① 清单外的数字一个都不许出现——包括看起来合理的外推、估算、\"约\"值；" +
        "② 要用清单里的数做派生（占比、倍数、变化率），必须**就近写出算式**；" +
        "③ 需要的信息清单里没有，就写\"这份数据里没有这个信息\"，不要编一个数顶上。</allowed_numbers>"
}
