package com.stragent.core.dataset

import com.stragent.core.csv.ColumnType
import com.stragent.core.csv.ParsedTable
import com.stragent.core.csv.Csv

/**
 * 数据集模型与统计摘要 —— src/shared/lib/dataset.ts 的纯逻辑部分移植。
 * 持久化不在这里（DatasetStore 端口在 data/ 落地）。
 *
 * 口径纪律（来自 dataset.ts 注释，违反会被 TS 侧基线测试抓住，这里同守）：
 *  - count 是「实际参与统计的样本数」，missing = 空值 + 脏值
 *  - share 只在 sum + 全为正时给出，否则 NaN 哨兵
 *  - 分位数 = 线性插值（Excel PERCENTILE.INC 同口径）
 */
data class Dataset(
    val name: String,
    val importedAt: Long,
    val columns: List<String>,
    val rows: List<List<String>>,
    val columnTypes: List<ColumnType>,
    val delimiter: Char = ',',
    val truncated: Boolean = false,
    val sourceRows: Int = rows.size,
    /**
     * 大文件抽样口径（T-054）：null = 本次分析用的是完整数据（或导入时按 1 万行截断，
     * 截断由 [truncated] 表达）；非 null = [rows] 只是源文件的确定性系统抽样，
     * 报告里的统计必须是「样本口径」，行号要能按 [SamplingInfo.rowNumbers] 回链源文件。
     */
    val sampling: SamplingInfo? = null,
    /**
     * 文本部分（T-096）：null = 纯表格数据集（既有形态，零变化）；非 null = 正文段落
     * （混合文档时 rows 为文档最大表，text 为正文段落与其余表的投影）。
     * 随数据集持久化（StoredTextContent），摘要/追问/综述的文本材料只从这里取。
     */
    val text: TextContent? = null,
)

/**
 * 确定性系统抽样口径。字段全部随数据集持久化——重启后 `stragent_list_rows`
 * 仍能给出 `sourceRow`，报告仍能标出同一枚指纹。
 */
data class SamplingInfo(
    /** 抽样方法：`systematic`（每 [step] 行取 1 行，起点第 1 个数据行） */
    val method: String = METHOD_SYSTEMATIC,
    val targetRows: Int,
    val step: Int,
    val totalRows: Int,
    /** `sha256:<hex>`——整份源文件字节的摘要（同一文件恒定，改一个字节就变） */
    val fingerprint: String,
    /** 样本第 N 行（1 起）→ 源文件数据行号（1 起，不含表头） */
    val rowNumbers: List<Int>,
) {
    /** 样本行号 → 源文件数据行号；越界返回 null */
    fun sourceRowOf(sampleRow: Int): Int? = rowNumbers.getOrNull(sampleRow - 1)

    companion object {
        const val METHOD_SYSTEMATIC = "systematic"
    }
}

data class ColumnStat(
    val name: String,
    val type: ColumnType,
    val count: Int,
    val missing: Int,
    val min: Double? = null,
    val max: Double? = null,
    val sum: Double? = null,
    val mean: Double? = null,
    val median: Double? = null,
    val p25: Double? = null,
    val p75: Double? = null,
    val distinct: Int? = null,
    val topValues: List<TopValue>? = null,
)

data class TopValue(val value: String, val count: Int)

data class AggregateItem(val label: String, val value: Double, val share: Double, val count: Int)

/**
 * 一个分组的累加结果（tier-1 内存累加与 tier-2 列式聚合共用同一收尾口径）。
 *
 * @param label 分组标签
 * @param sum   该组数值列的累计值
 * @param count 该组**参与统计的行数**（数值列解析不出来的行不计入）
 */
data class AggregateBucket(val label: String, val sum: Double, val count: Int)

data class DefaultColumns(val category: Int, val value: Int, val x: Int)

fun datasetFromParsed(name: String, importedAt: Long, table: ParsedTable): Dataset = Dataset(
    name = name,
    importedAt = importedAt,
    columns = table.columns,
    rows = table.rows,
    columnTypes = table.columnTypes,
    delimiter = table.delimiter,
    truncated = table.truncated,
    sourceRows = table.totalRows,
)

private fun medianOf(sorted: List<Double>): Double {
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2 else sorted[middle]
}

/** 线性插值分位数（Excel PERCENTILE.INC 同口径） */
private fun quantileOf(sorted: List<Double>, q: Double): Double {
    if (sorted.isEmpty()) return Double.NaN
    val position = (sorted.size - 1) * q
    val lower = kotlin.math.floor(position).toInt()
    val upper = kotlin.math.ceil(position).toInt()
    if (lower == upper) return sorted[lower]
    return sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower)
}

fun summarizeColumn(dataset: Dataset, columnIndex: Int): ColumnStat {
    val name = dataset.columns[columnIndex]
    val type = dataset.columnTypes[columnIndex]
    val values = dataset.rows.map { (it.getOrNull(columnIndex) ?: "").trim() }
    val nonEmpty = values.filter { it.isNotEmpty() }
    val base = ColumnStat(name = name, type = type, count = nonEmpty.size, missing = values.size - nonEmpty.size)
    if (type == ColumnType.NUMBER) {
        val numbers = nonEmpty.mapNotNull { Csv.parseNumber(it) }.sorted()
        val corrected = base.copy(count = numbers.size, missing = values.size - numbers.size)
        if (numbers.isEmpty()) return corrected
        val sum = numbers.sum()
        return corrected.copy(
            min = numbers.first(),
            max = numbers.last(),
            sum = sum,
            mean = sum / numbers.size,
            median = medianOf(numbers),
            p25 = quantileOf(numbers, 0.25),
            p75 = quantileOf(numbers, 0.75),
        )
    }
    // LinkedHashMap 保首见序：并列计数时 topValues 的相对次序由首次出现决定（TS Map 同款）
    val counter = LinkedHashMap<String, Int>()
    for (v in nonEmpty) counter[v] = (counter[v] ?: 0) + 1
    val top = counter.entries.sortedByDescending { it.value }.take(5).map { TopValue(it.key, it.value) }
    return base.copy(distinct = counter.size, topValues = top)
}

fun summarize(dataset: Dataset): List<ColumnStat> = dataset.columns.indices.map { summarizeColumn(dataset, it) }

/**
 * 全量口径：有效行数（跳过全空行与**重复表头行**——Excel 分页表头会让合计翻倍）
 * 与单元格密度（分母是全部格子，不是有效格子）。能力层与 agent 工具共用。
 */
fun validRowsAndDensity(dataset: Dataset): Pair<Int, Double> {
    val totalColumns = dataset.columns.size
    var validRows = 0
    var validCells = 0
    for (row in dataset.rows) {
        var nonEmpty = 0
        for (index in 0 until totalColumns) {
            if ((row.getOrNull(index) ?: "").trim().isNotEmpty()) nonEmpty += 1
        }
        if (nonEmpty == 0) continue
        if (nonEmpty == totalColumns && dataset.columns.withIndex().all { (index, name) -> (row.getOrNull(index) ?: "").trim() == name }) {
            continue
        }
        validRows += 1
        validCells += nonEmpty
    }
    val totalCells = dataset.rows.size * totalColumns
    val density = if (totalCells == 0) 0.0 else validCells.toDouble() / totalCells
    return validRows to density
}

fun aggregateBy(dataset: Dataset, categoryIndex: Int, valueIndex: Int, mode: String, topN: Int = 8): List<AggregateItem> {
    val buckets = LinkedHashMap<String, DoubleArray>()
    for (row in dataset.rows) {
        val label = (row.getOrNull(categoryIndex) ?: "").trim().ifEmpty { "（空）" }
        val value = Csv.parseNumber(row.getOrNull(valueIndex) ?: "") ?: continue
        val b = buckets.getOrPut(label) { DoubleArray(2) }
        b[0] += value
        b[1] += 1
    }
    return finalizeAggregate(
        buckets.entries.map { AggregateBucket(it.key, it.value[0], it.value[1].toInt()) },
        mode,
        topN,
    )
}

/**
 * 聚合收尾 —— **tier-1 与 tier-2 的单一事实源**（D10 阶段 1 双轨纪律）。
 *
 * 排序 → share 语义 → topN + 「其他 N 项」，tier-1（内存累加）与 tier-2（DuckDB 列式聚合）
 * 都必须走这里，避免两档各写一套收尾逻辑导致「同一个数变成两个数」。
 *
 * @param buckets 分组累加结果，**顺序即并列时的先后序**（tier-1 用首见序，tier-2 用首行序）
 * @param mode    "sum" 或 "avg"
 * @param topN    明细保留条数，其余合并为「其他 N 项」
 */
fun finalizeAggregate(buckets: List<AggregateBucket>, mode: String, topN: Int): List<AggregateItem> {
    val items = buckets
        .map { Triple(it.label, if (mode == "sum") it.sum else it.sum / it.count, it.count) }
        .sortedByDescending { it.second }
    val grandTotal = items.sumOf { it.second }
    val shareMeaningful = mode == "sum" && grandTotal > 0 && items.all { it.second >= 0 }
    fun shareOf(v: Double): Double = if (shareMeaningful) v / grandTotal else Double.NaN
    val head = items.take(topN).map { (label, value, count) -> AggregateItem(label, value, shareOf(value), count) }
        .toMutableList()
    val rest = items.drop(topN)
    if (rest.isNotEmpty()) {
        val restTotal = rest.sumOf { it.second }
        val restCount = rest.sumOf { it.third }
        head.add(AggregateItem("其他 ${rest.size} 项", restTotal, shareOf(restTotal), restCount))
    }
    return head
}

// ---------- ID 列识别（dataset.ts 同款词表与切词） ----------

private val ID_LIKE_WORD = Regex("^(id|ids|no|nos|num|nums|number|code|index|idx|key|uuid|guid|sn|序号|编号|代码|单号|账号)$", RegexOption.IGNORE_CASE)
private val FILLER_NAME = Regex("^(列\\d+|\\d+)$")
private val DATE_LIKE = Regex("^\\d{1,4}[/\\-.]\\d{1,2}([/\\-.]\\d{1,4})?([ T]\\d{1,2}:\\d{2}(:\\d{2})?)?$")
private val CHINESE_ID_WORD = Regex("编号|序号|代码|单号|账号")

/** 驼峰切词 → 分隔符切词（EmployeeID → Employee ID） */
private fun columnWords(name: String): List<String> =
    name.trim()
        .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        .split(Regex("[\\s_.\\-·]+"))
        .filter { it.isNotEmpty() }

fun looksLikeIdColumn(name: String): Boolean {
    val trimmed = name.trim()
    if (FILLER_NAME.containsMatchIn(trimmed)) return true
    if (CHINESE_ID_WORD.containsMatchIn(trimmed)) return true
    return columnWords(trimmed).any { ID_LIKE_WORD.matches(it) }
}

private fun distinctCount(dataset: Dataset, columnIndex: Int): Int {
    val seen = HashSet<String>()
    for (row in dataset.rows) {
        val value = (row.getOrNull(columnIndex) ?: "").trim()
        if (value.isNotEmpty()) seen.add(value)
    }
    return seen.size
}

/** 分组维度打分：基数 3–40 区间 + 列名像业务维度（dataset.ts DIMENSION_WORDS 同款） */
private val DIMENSION_WORDS = Regex(
    "(region|area|zone|category|segment|channel|product|department|type|group|class|status|grade|level|branch|store|city|province|country|industry|source|platform|device|station|point|site|location|点号|测点|监测|区域|地区|分区|渠道|类别|类型|品类|分组|部门|门店|城市|省份|状态|等级|平台|来源|设备|站点|位置)",
    RegexOption.IGNORE_CASE,
)

fun dimensionScore(name: String, distinct: Int): Int {
    var score = 0
    if (distinct in 3..40) score += 2
    if (DIMENSION_WORDS.containsMatchIn(name)) score += 2
    return score
}

fun pickDefaultColumns(dataset: Dataset): DefaultColumns {
    val rowCount = maxOf(dataset.rows.size, 1)
    val numericIndexes = dataset.columnTypes.mapIndexed { i, t -> if (t == ColumnType.NUMBER) i else -1 }.filter { it >= 0 }
    val textIndexes = dataset.columnTypes.mapIndexed { i, t -> if (t == ColumnType.TEXT) i else -1 }.filter { it >= 0 }

    val meaningfulNumber = numericIndexes.firstOrNull { !looksLikeIdColumn(dataset.columns[it]) }
    val value = meaningfulNumber ?: numericIndexes.firstOrNull() ?: 0

    val upper = minOf(50, maxOf(2, rowCount))
    val scored = textIndexes.mapNotNull { index ->
        val distinct = distinctCount(dataset, index)
        if (distinct < 2 || distinct > upper) return@mapNotNull null
        val name = dataset.columns[index]
        if (looksLikeIdColumn(name)) return@mapNotNull null
        val nonEmpty = dataset.rows.count { (it.getOrNull(index) ?: "").trim().isNotEmpty() }
        val score = dimensionScore(name, distinct) + (if (nonEmpty > rowCount * 0.5) 1 else 0)
        index to score
    }.sortedByDescending { it.second }
    val groupLikeText = scored.firstOrNull()?.first
    val fallback = groupLikeText ?: (numericIndexes + textIndexes).firstOrNull { index ->
        if (index == value) return@firstOrNull false
        if (looksLikeIdColumn(dataset.columns[index])) return@firstOrNull false
        val distinct = distinctCount(dataset, index)
        distinct >= 2 && distinct <= upper
    } ?: -1
    val category = fallback

    val dateLikeText = textIndexes.firstOrNull { index ->
        val sample = dataset.rows.firstOrNull { (it.getOrNull(index) ?: "").trim().isNotEmpty() }
        sample != null && DATE_LIKE.matches((sample[index] ?: "").trim())
    }
    val x = dateLikeText ?: category
    return DefaultColumns(category, value, x)
}
