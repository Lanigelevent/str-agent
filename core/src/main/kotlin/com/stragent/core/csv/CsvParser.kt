package com.stragent.core.csv

/**
 * CSV / TSV / 分隔文本解析 —— src/shared/lib/csv.ts 的 Kotlin 移植。
 *
 * 契约（与 TS 实现逐条对齐，移植期间不改行为）：
 *  - 自动探测分隔符（, \t ; | 取首个非空行里出现次数最多者，引号段先剥掉再数）
 *  - 引号包裹字段与 "" 转义；只有字段起始位置的 " 才是引号起点
 *  - 逻辑行切分：引号内换行不切断；空逻辑行丢弃；BOM 剥离
 *  - parseNumber：容忍前后空白、% 后缀、千分位空格；千分位逗号仅在标准分组下接受
 *  - 数值列推断：非空值中 ≥85% 可解析为数字
 *  - 列名：空名补「列N」；重名循环递增后缀直到唯一（a, a_2, a_2_2）
 *  - MAX_ROWS = 10000 截断（parseDelimited；parseDelimitedFull 不截断，供双轨对拍/规模门禁用）
 *
 * 验收门：fixtures/baseline/label-baseline.json（TS 侧生成）结构化比对 +
 * fixtures/sample/ 边界样本的行为测试（本模块测试直接读同一批夹具）。
 */
data class ParsedTable(
    val columns: List<String>,
    val rows: List<List<String>>,
    val columnTypes: List<ColumnType>,
    val delimiter: Char,
    val truncated: Boolean = false,
    val totalRows: Int = rows.size,
)

enum class ColumnType { NUMBER, TEXT }

object Csv {
    const val MAX_ROWS = 10000
    private val DELIMITER_CANDIDATES = listOf(',', '\t', ';', '|')

    /**
     * 热路径正则——**预编译单例**（T-142）。
     *
     * `Pattern` 不可变且线程安全，写在函数体内会**每次调用都重新编译**；
     * `parseNumber` 是**每格**调用的（tier-1 上限 10,000 行 × N 列），
     * 所以这里必须是单例。仓内先例：`core/verify/VerifyCore.kt:17-38`。
     */
    private val THOUSAND_GROUPED = Regex("^-?\\d{1,3}(,\\d{3})+(\\.\\d+)?$")
    private val QUOTED_FIELD = Regex("\"[^\"]*\"")

    fun parseDelimited(text: String): ParsedTable = parse(text, MAX_ROWS)

    /**
     * 不截断的完整解析（双轨 tier-1 基准用）。
     *
     * 用途：规模门禁与 tier-1/tier-2 对拍需要「同一份数据的完整口径」——
     * 走 parseDelimited 会被 MAX_ROWS 截到 1 万行，对拍就变成了「1 万行 vs 500 万行」，
     * 比的不是口径而是规模。**报告路径仍然走 parseDelimited**（10K 软约束 + 显式提示）。
     */
    fun parseDelimitedFull(text: String): ParsedTable = parse(text, null)

    /** @param maxRows null 表示不截断 */
    private fun parse(text: String, maxRows: Int?): ParsedTable {
        val withoutBom = text.removePrefix("\uFEFF")
        val lines = splitLogicalLines(withoutBom).filter { it.isNotBlank() }
        if (lines.isEmpty()) throw IllegalArgumentException("没有解析到任何数据行")
        val delimiter = detectDelimiter(lines)
        val header = splitLine(lines[0], delimiter)
        // 单列是合法输入，只有整行全空才拒（csv.ts 同款）
        if (header.all { it.isEmpty() }) {
            throw IllegalArgumentException("第一行不像表头：请检查分隔符是否正确")
        }
        val columns = dedupeColumns(header)
        val totalRows = lines.size - 1
        val truncated = maxRows != null && totalRows > maxRows
        val bodyLines = if (truncated) lines.slice(1 until maxRows!! + 1) else lines.drop(1)
        val rows = bodyLines.map { line ->
            val cells = splitLine(line, delimiter)
            columns.map { c -> cells.getOrNull(columns.indexOf(c)) ?: "" }.ifEmpty { List(columns.size) { "" } }
        }.map { row ->
            if (row.size == columns.size) row else (row + List(columns.size) { "" }).take(columns.size)
        }
        val columnTypes = columns.indices.map { c -> inferType(rows.map { it[c] }) }
        return ParsedTable(columns, rows, columnTypes, delimiter, truncated, totalRows)
    }

    /**
     * 数值解析的唯一口径（csv.ts parseNumber）。
     * 容忍前后空白、% 后缀、千分位空格；千分位逗号仅在标准分组 ^-?\d{1,3}(,\d{3})+(\.\d+)?$ 下接受。
     */
    fun parseNumber(raw: String): Double? {
        val text = raw.trim().trimEnd('%').replace(" ", "")
        if (text.isEmpty()) return null
        val normalized = if (THOUSAND_GROUPED.matches(text)) text.replace(",", "") else text
        val value = normalized.toDoubleOrNull() ?: return null
        return if (value.isFinite()) value else null
    }

    fun inferTypes(rows: List<List<String>>, columnCount: Int): List<ColumnType> =
        (0 until columnCount).map { c -> inferType(rows.map { it.getOrNull(c) ?: "" }) }

    fun limitRows(table: ParsedTable): ParsedTable {
        val totalRows = table.totalRows
        if (table.rows.size <= MAX_ROWS) return table.copy(truncated = table.truncated, totalRows = totalRows)
        val rows = table.rows.take(MAX_ROWS)
        return table.copy(rows = rows, columnTypes = inferTypes(rows, table.columns.size), truncated = true, totalRows = totalRows)
    }

    fun numericColumnIndexes(table: ParsedTable): List<Int> =
        table.columnTypes.mapIndexed { i, t -> if (t == ColumnType.NUMBER) i else null }.filterNotNull()

    // ---------- 内部实现（与 TS 逐条对齐） ----------

    /** 字段起始位置 = current 为空，或刚读过一个候选分隔符（isFieldStart 同款）
     *  internal：CsvSampler 的逻辑行扫描必须与这里同口径，别在第二处复制规则 */
    internal fun isFieldStartForQuote(current: CharSequence): Boolean =
        current.isEmpty() || current[current.length - 1] in DELIMITER_CANDIDATES

    private fun isFieldStart(current: CharSequence): Boolean = isFieldStartForQuote(current)

    private fun splitLine(line: String, delimiter: Char): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length && line[i + 1] == '"') { current.append('"'); i += 1 } else inQuotes = false
                } else current.append(ch)
            } else when {
                ch == '"' && current.isEmpty() -> inQuotes = true
                ch == delimiter -> { cells.add(current.toString()); current.clear() }
                else -> current.append(ch)
            }
            i += 1
        }
        cells.add(current.toString())
        return cells.map { it.trim() }
    }

    /** 逻辑行切分：引号内换行不切断；CR/LF/CRLF 都是换行；引号起点用 isFieldStart 判断 */
    private fun splitLogicalLines(text: String): List<String> {
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (inQuotes) {
                current.append(ch)
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { current.append('"'); i += 1 } else inQuotes = false
                }
            } else when {
                ch == '"' && isFieldStart(current) -> { inQuotes = true; current.append(ch) }
                ch == '\n' || ch == '\r' -> {
                    if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i += 1
                    lines.add(current.toString()); current.clear()
                }
                else -> current.append(ch)
            }
            i += 1
        }
        lines.add(current.toString())
        return lines
    }

    /** 首个非空行里剥掉引号段后计数最多者；0 次则 ',' */
    private fun detectDelimiter(lines: List<String>): Char {
        val sample = lines.firstOrNull { it.isNotBlank() } ?: ""
        var best = ','
        var bestCount = 0
        for (candidate in DELIMITER_CANDIDATES) {
            val stripped = sample.replace(QUOTED_FIELD, "")
            val count = stripped.count { it == candidate }
            if (count > bestCount) { best = candidate; bestCount = count }
        }
        return best
    }

    /** 空名补「列N」；重名循环递增后缀直到唯一（a, a_2, a_2_2 —— 不能产出两个 a_2） */
    private fun dedupeColumns(header: List<String>): List<String> {
        val used = mutableSetOf<String>()
        return header.mapIndexed { index, name ->
            val base = if (name.isEmpty()) "列${index + 1}" else name
            if (used.add(base)) base
            else {
                var suffix = 2
                while (used.contains("${base}_$suffix")) suffix += 1
                val unique = "${base}_$suffix"
                used.add(unique)
                unique
            }
        }
    }

    private fun inferType(values: List<String>): ColumnType {
        var total = 0
        var numeric = 0
        for (v in values) {
            if (v.trim().isEmpty()) continue
            total += 1
            if (parseNumber(v) != null) numeric += 1
        }
        return if (total > 0 && numeric.toDouble() / total >= 0.85) ColumnType.NUMBER else ColumnType.TEXT
    }
}
