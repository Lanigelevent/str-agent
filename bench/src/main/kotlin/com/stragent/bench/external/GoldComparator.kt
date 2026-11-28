package com.stragent.bench.external

import kotlin.math.abs
import kotlin.math.max

/**
 * T-057：Spider 2.0-Lite 官方比较器的忠实移植。
 *
 * 口径来源：Spider2 仓库 `evaluation_suite/evaluate.py` 的
 * `compare_pandas_table` / `compare_multi`（MIT License, Copyright (c) 2024 bird_sql）。
 *
 * 规则（逐条对齐，不做「差不多」放宽）：
 *  - `condition_cols` 是 **0-based** 列下标；空表示金标所有列都要能对上；
 *  - 每条金标列向量必须与预测结果的**某一列**向量整体相等（列数不要求一致，允许预测多列）；
 *  - 向量长度必须相等；
 *  - 空值（pandas NaN）归一为 0；
 *  - 两边都是浮点 → math.isclose(rel_tol=1e-9, abs_tol=1e-2)；整数列/整数与浮点混合 → 精确相等
 *    （pandas 中 np.int64 不算 int/float，走 `a != b` 的数值精确比较）；
 *  - `ignore_order=true` 时按 `(x is None, str(x), isinstance(x,(int,float)))` 排序后逐位比较；
 *  - 多条 gold（a/b/c 等价答案）任一命中即算对。
 *
 * 与官方实现的已知差异（都是「我们把 pandas 的列推断显式化」）：
 *  - 金标 CSV 的列类型按 pandas read_csv 的推断规则复刻（列内有空值/小数 → 浮点；全整数 → 整数；
 *    否则整列文本）；
 *  - 预测结果来自 JDBC 类型而不是 pandas 推断；SQL 显式 CAST 成文本时就按文本比较。
 */
sealed interface Cell {
    data object Missing : Cell
    data class IntCell(val value: Long) : Cell
    data class NumCell(val value: Double) : Cell
    data class TextCell(val value: String) : Cell
}

data class Table(val columns: List<String>, val rows: List<List<Cell>>)

/** manifest 里 condition_cols 的三种官方形态。 */
sealed interface ConditionCols {
    data object Empty : ConditionCols
    data class Flat(val indices: List<Int>) : ConditionCols
    data class PerGold(val lists: List<List<Int>>) : ConditionCols
}

object GoldComparator {
    private val INT_RE = Regex("^-?\\d+$")
    private val NUM_RE = Regex("^[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?$")

    /** pandas read_csv 的默认 NA 记号（忽略大小写差异会误伤文本，保持官方原始写法）。 */
    private val NA_TOKENS = setOf(
        "", "#N/A", "#N/A N/A", "#NA", "-1.#IND", "-1.#QNAN", "-NaN", "-nan",
        "1.#IND", "1.#QNAN", "<NA>", "N/A", "NA", "NULL", "NaN", "None", "n/a", "nan", "null",
    )

    /** 金标 CSV → 列类型按 pandas 推断规则复刻的 Table。 */
    fun parseGoldCsv(text: String): Table {
        val records = parseCsvRecords(text)
        require(records.isNotEmpty()) { "金标 CSV 为空" }
        val columns = records.first()
        val body = records.drop(1)
        val kinds = columns.indices.map { c ->
            val raw = body.map { it.getOrElse(c) { "" } }
            val nonNa = raw.filter { !isNa(it) }
            when {
                nonNa.isEmpty() -> Kind.TEXT
                // pandas：列内只要有 NaN，整数也会升为 float64
                raw.any { isNa(it) } && nonNa.all { NUM_RE.matches(it) } -> Kind.NUM
                nonNa.all { t -> INT_RE.matches(t) && t.toLongOrNull() != null } -> Kind.INT
                nonNa.all { NUM_RE.matches(it) } -> Kind.NUM
                else -> Kind.TEXT
            }
        }
        val rows = body.map { record ->
            columns.indices.map { c ->
                val raw = record.getOrElse(c) { "" }
                if (isNa(raw)) {
                    Cell.Missing
                } else {
                    when (kinds[c]) {
                        Kind.INT -> Cell.IntCell(raw.toLong())
                        Kind.NUM -> Cell.NumCell(raw.toDouble())
                        Kind.TEXT -> Cell.TextCell(raw)
                    }
                }
            }
        }
        return Table(columns, rows)
    }

    /**
     * 最小 CSV 读入（对齐 pandas read_csv 默认行为的关键点）：
     *  - 逗号分隔、双引号包裹、`""` 转义、引号内换行；CR/LF/CRLF 都是换行；
     *  - **不 trim**：文本字段的前后空格是数据（官方 pandas 比较也是精确文本）；
     *  - 空白行跳过（pandas skip_blank_lines=True）；
     *  - BOM 剥离。
     * 不用 core 的 Csv：产品解析器按表格口径会 trim 单元格，拿来比金标会掩盖空格差异。
     */
    internal fun parseCsvRecords(text: String): List<List<String>> {
        val input = text.removePrefix("\uFEFF")
        val records = mutableListOf<List<String>>()
        val record = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var recordHasQuote = false
        var i = 0

        fun endField() {
            record.add(field.toString())
            field.clear()
        }

        fun endRecord() {
            endField()
            // 只有「未加引号的空白行」才算空行；`""` 是带空值的真实数据行（pandas 同口径）
            if (recordHasQuote || record.any { it.isNotBlank() }) records.add(record.toList())
            record.clear()
            recordHasQuote = false
        }

        while (i < input.length) {
            val ch = input[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < input.length && input[i + 1] == '"') {
                        field.append('"'); i += 1
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(ch)
                }
            } else when {
                ch == '"' && field.isEmpty() -> { inQuotes = true; recordHasQuote = true }
                ch == ',' -> endField()
                ch == '\n' || ch == '\r' -> {
                    if (ch == '\r' && i + 1 < input.length && input[i + 1] == '\n') i += 1
                    endRecord()
                }
                else -> field.append(ch)
            }
            i += 1
        }
        if (field.isNotEmpty() || record.isNotEmpty()) endRecord()
        return records
    }

    /**
     * 官方 compare_multi：多条 gold 任一命中即通过。
     *
     * 官方对 `condition_cols` 的分支：
     *  - 空 → 每条 gold 都用空条件（全列）；
     *  - 扁平列表 + 多条 gold → 同一条列条件套到每条 gold；
     *  - 嵌套列表 → 与 gold 一一对应。
     */
    fun compareMulti(pred: Table, golds: List<Table>, conds: ConditionCols, ignoreOrder: Boolean): Boolean {
        if (golds.isEmpty()) return false
        val effective: List<List<Int>> = when (conds) {
            ConditionCols.Empty -> List(golds.size) { emptyList() }
            is ConditionCols.Flat -> List(golds.size) { conds.indices }
            is ConditionCols.PerGold -> {
                require(conds.lists.size >= golds.size) {
                    "condition_cols 分组数 ${conds.lists.size} 少于 gold 数 ${golds.size}"
                }
                conds.lists
            }
        }
        for (i in golds.indices) {
            if (compareTable(pred, golds[i], effective[i], ignoreOrder)) return true
        }
        return false
    }

    /** 官方 compare_pandas_table。 */
    fun compareTable(pred: Table, gold: Table, conditionCols: List<Int>, ignoreOrder: Boolean): Boolean {
        val goldCols = if (conditionCols.isEmpty()) gold.columns.indices.toList() else conditionCols
        for (goldIdx in goldCols) {
            require(goldIdx in gold.columns.indices) {
                "condition_cols 下标 $goldIdx 越界（gold 只有 ${gold.columns.size} 列）"
            }
            val goldVector = gold.rows.map { normalize(it.getOrNull(goldIdx) ?: Cell.Missing) }
            val matched = pred.columns.indices.any { predIdx ->
                val predVector = pred.rows.map { normalize(it.getOrNull(predIdx) ?: Cell.Missing) }
                vectorsMatch(goldVector, predVector, ignoreOrder)
            }
            if (!matched) return false
        }
        return true
    }

    fun normalize(cell: Cell): Cell = if (cell is Cell.Missing) Cell.IntCell(0) else cell

    /** 官方 vectors_match。 */
    fun vectorsMatch(v1: List<Cell>, v2: List<Cell>, ignoreOrder: Boolean): Boolean {
        val a = if (ignoreOrder) v1.sortedWith(ORDER) else v1
        val b = if (ignoreOrder) v2.sortedWith(ORDER) else v2
        if (a.size != b.size) return false
        for (i in a.indices) {
            if (!cellEquals(a[i], b[i])) return false
        }
        return true
    }

    /** Python `sorted(key=(x is None, str(x), isinstance(x,(int,float))))` 的移植。 */
    private val ORDER = Comparator<Cell> { x, y ->
        compareValuesBy(x, y, { it is Cell.Missing }, { sortString(it) }, { isNumeric(it) })
    }

    private fun sortString(cell: Cell): String = when (cell) {
        is Cell.Missing -> ""
        is Cell.IntCell -> cell.value.toString()
        is Cell.NumCell -> cell.value.toString()
        is Cell.TextCell -> cell.value
    }

    private fun isNumeric(cell: Cell): Boolean = cell is Cell.IntCell || cell is Cell.NumCell

    /**
     * 官方逐值比较：
     *  - 同为数值：双浮点走 isclose；其余（整数-整数、整数-浮点）走精确数值相等；
     *  - 其余必须同型且文本完全相等。
     */
    private fun cellEquals(a: Cell, b: Cell): Boolean = when {
        a is Cell.IntCell && b is Cell.IntCell -> a.value == b.value
        a is Cell.NumCell && b is Cell.NumCell -> isClose(a.value, b.value)
        a is Cell.IntCell && b is Cell.NumCell -> a.value.toDouble() == b.value
        a is Cell.NumCell && b is Cell.IntCell -> a.value == b.value.toDouble()
        a is Cell.TextCell && b is Cell.TextCell -> a.value == b.value
        else -> false
    }

    /** Python math.isclose(a, b, rel_tol=1e-9, abs_tol=1e-2)。 */
    private fun isClose(a: Double, b: Double): Boolean {
        if (a.isNaN() || b.isNaN()) return a.isNaN() && b.isNaN()
        if (a == b) return true
        if (a.isInfinite() || b.isInfinite()) return false
        return abs(a - b) <= max(1e-9 * max(abs(a), abs(b)), 1e-2)
    }

    private fun isNa(raw: String): Boolean = raw in NA_TOKENS

    private enum class Kind { INT, NUM, TEXT }
}
