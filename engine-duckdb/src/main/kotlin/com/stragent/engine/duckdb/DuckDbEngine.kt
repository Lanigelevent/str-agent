package com.stragent.engine.duckdb

import com.stragent.core.csv.ColumnType
import com.stragent.core.dataset.AggregateItem
import com.stragent.core.dataset.ColumnStat
import com.stragent.core.dataset.TopValue
import com.stragent.core.engine.AnalysisEngine
import com.stragent.core.engine.EngineTier
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * tier-2 · 列式分析引擎（DuckDB）。
 *
 * 定位（D10 §阶段1）：
 *  - 承接超出内存阈值的数据规模（10K 硬帽 → 千万级），负责**探索与中间聚合**；
 *  - **报告路径不走这里** —— 报告恒走 tier-1（`InMemoryEngine`），保数字逐个可回溯到原始行。
 *
 * 口径纪律：
 *  - 数值解析与 tier-1 完全同规则（见 [DuckDbSql]）；
 *  - 聚合收尾（排序 / share / 「其他 N 项」）与 tier-1 **同口径**（core 的 `finalizeAggregate`
 *    是那套口径的单一事实源，这里按同一规则在 SQL 侧实现并由 `engine-parity` 逐样本对拍兜底）；
 *  - 维度并列时的先后序：tier-1 用**首见序**，tier-2 用**首行序**（`MIN(__idx)`），语义等价。
 *
 * 规模处理：
 *  - topN 与「其他 N 项」**下沉到 SQL**：只回传 topN 行 + 其余聚合值，
 *    百万级唯一值维度也不必把全部分组搬回 JVM（T-018）。
 *
 * 已知限制：
 *  - `fromCsvFile` 依赖 DuckDB 自带 CSV 读取器，与 tier-1 的解析器不是同一套实现，
 *    因此**对拍只走 `fromRows`**（两侧喂完全相同的字符串，比的才是聚合口径而非读文件口径）。
 */
class DuckDbEngine(
    private val connection: Connection,
    private val columns: List<String>,
    private val table: String,
    override val rowCount: Int,
) : AnalysisEngine, AutoCloseable {

    override val id: String = "duckdb"
    override val tier: EngineTier = EngineTier.COLUMNAR

    override fun aggregateBy(categoryIndex: Int, valueIndex: Int, mode: String, topN: Int): List<AggregateItem> {
        require(categoryIndex in columns.indices) { "维度列索引越界：$categoryIndex（共 ${columns.size} 列）" }
        require(valueIndex in columns.indices) { "数值列索引越界：$valueIndex（共 ${columns.size} 列）" }
        require(mode == "sum" || mode == "avg") { "未知聚合模式：$mode" }

        val labelExpr = DuckDbSql.label(columns[categoryIndex])
        val numExpr = DuckDbSql.numeric(columns[valueIndex])

        // topN 与「其他 N 项」下沉到 SQL：只回传 topN 行 + 其余聚合值，
        // 高基数维度（百万级唯一值）不再需要把全部分组搬回 JVM。
        // 排序口径与 tier-1 的稳定排序一致：值降序，并列按首行序（等价 tier-1 的首见序）。
        val sql = """
            WITH grouped AS (
                SELECT label, SUM(v) AS s, COUNT(*) AS c, MIN($INDEX_COLUMN) AS first_idx
                FROM (
                    SELECT $INDEX_COLUMN, $labelExpr AS label, $numExpr AS v FROM $table
                )
                WHERE v IS NOT NULL
                GROUP BY label
            ),
            ranked AS (
                SELECT label, s, c, first_idx,
                       ROW_NUMBER() OVER (ORDER BY (CASE WHEN '$mode' = 'sum' THEN s ELSE s / c END) DESC, first_idx ASC) AS rn
                FROM grouped
            )
            SELECT label, s, c, rn FROM (
                SELECT label, s, c, rn, 0 AS grp FROM ranked WHERE rn <= $topN
                UNION ALL
                SELECT '其他 ' || CAST(COUNT(*) AS VARCHAR) || ' 项',
                       COALESCE(SUM(CASE WHEN '$mode' = 'sum' THEN s ELSE s / c END), 0),
                       COALESCE(SUM(c), 0),
                       -1,
                       1 AS grp
                FROM ranked WHERE rn > $topN HAVING COUNT(*) > 0
            )
            ORDER BY grp, rn
        """.trimIndent()

        // share 分母与「全为正」判定也要在全量分组上算（否则 head 的占比分母会错）
        val totalsSql = """
            WITH grouped AS (
                SELECT label, SUM(v) AS s, COUNT(*) AS c
                FROM (
                    SELECT $INDEX_COLUMN, $labelExpr AS label, $numExpr AS v FROM $table
                )
                WHERE v IS NOT NULL
                GROUP BY label
            )
            SELECT COALESCE(SUM(s), 0), COALESCE(MIN(s), 0) FROM grouped
        """.trimIndent()

        val rows = mutableListOf<AggregateRow>()
        connection.prepareStatement(sql).use { statement ->
            statement.executeQuery().use { rs ->
                while (rs.next()) {
                    rows.add(AggregateRow(rs.getString(1), rs.getDouble(2), rs.getLong(3).toInt(), rs.getInt(4)))
                }
            }
        }
        var grandTotal = 0.0
        var minGroup = 0.0
        connection.prepareStatement(totalsSql).use { statement ->
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    grandTotal = rs.getDouble(1)
                    minGroup = rs.getDouble(2)
                }
            }
        }

        // 与 core.dataset.finalizeAggregate 同口径的收尾：
        // share 只在 sum 模式、合计为正、且**每一组**都非负时才有意义（分母 = 全体合计）。
        val shareMeaningful = mode == "sum" && grandTotal > 0 && minGroup >= 0
        fun shareOf(value: Double): Double = if (shareMeaningful) value / grandTotal else Double.NaN

        return rows.map { row ->
            // rn = -1 的是「其他 N 项」行：其值已在 SQL 里按 tier-1 口径聚合好，不能再除一次
            val value = if (row.rn < 0) row.value else if (mode == "sum") row.value else row.value / row.count
            AggregateItem(row.label, value, shareOf(value), row.count)
        }
    }

    /** SQL 回传的一行：label / 聚合值 / 样本数 / 名次（-1 = 「其他 N 项」汇总行） */
    private data class AggregateRow(val label: String, val value: Double, val count: Int, val rn: Int)

    override fun profileColumn(columnIndex: Int): ColumnStat? {
        require(columnIndex in columns.indices) { "列索引越界：$columnIndex（共 ${columns.size} 列）" }
        val name = columns[columnIndex]
        val numExpr = DuckDbSql.numeric(name)
        val sql = """
            SELECT
                COUNT(*) FILTER (WHERE ${DuckDbSql.nonEmpty(name)}) AS non_empty,
                COUNT($numExpr) AS numeric_count,
                MIN($numExpr), MAX($numExpr), SUM($numExpr), AVG($numExpr),
                quantile_cont($numExpr, 0.25), quantile_cont($numExpr, 0.5), quantile_cont($numExpr, 0.75)
            FROM $table
        """.trimIndent()

        var nonEmpty = 0
        var numericCount = 0
        var min: Double? = null
        var max: Double? = null
        var sum: Double? = null
        var mean: Double? = null
        var p25: Double? = null
        var median: Double? = null
        var p75: Double? = null
        connection.prepareStatement(sql).use { statement ->
            statement.executeQuery().use { rs ->
                rs.next()
                nonEmpty = rs.getLong(1).toInt()
                numericCount = rs.getLong(2).toInt()
                if (numericCount > 0) {
                    min = rs.getDouble(3)
                    max = rs.getDouble(4)
                    sum = rs.getDouble(5)
                    mean = rs.getDouble(6)
                    p25 = rs.getDouble(7)
                    median = rs.getDouble(8)
                    p75 = rs.getDouble(9)
                }
            }
        }

        // 列类型推断口径与 Csv.inferType 同款：非空值中可解析为数字的比例 ≥ 85%
        val isNumeric = nonEmpty > 0 && numericCount.toDouble() / nonEmpty >= 0.85
        if (isNumeric) {
            if (numericCount == 0) {
                return ColumnStat(name = name, type = ColumnType.NUMBER, count = 0, missing = rowCount)
            }
            return ColumnStat(
                name = name,
                type = ColumnType.NUMBER,
                count = numericCount,
                missing = rowCount - numericCount,
                min = min,
                max = max,
                sum = sum,
                mean = mean,
                median = median,
                p25 = p25,
                p75 = p75,
            )
        }

        val distinct = connection.prepareStatement(
            "SELECT COUNT(DISTINCT trim(coalesce(${DuckDbSql.quoteIdent(name)}, ''))) FROM $table " +
                "WHERE ${DuckDbSql.nonEmpty(name)}",
        ).use { statement ->
            statement.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1).toInt()
            }
        }
        val topValues = mutableListOf<TopValue>()
        connection.prepareStatement(
            "SELECT trim(coalesce(${DuckDbSql.quoteIdent(name)}, '')) AS v, COUNT(*) AS c, MIN($INDEX_COLUMN) AS fi " +
                "FROM $table WHERE ${DuckDbSql.nonEmpty(name)} GROUP BY v ORDER BY c DESC, fi ASC LIMIT 5",
        ).use { statement ->
            statement.executeQuery().use { rs ->
                while (rs.next()) {
                    topValues.add(TopValue(rs.getString(1), rs.getLong(2).toInt()))
                }
            }
        }
        return ColumnStat(
            name = name,
            type = ColumnType.TEXT,
            count = nonEmpty,
            missing = rowCount - nonEmpty,
            distinct = distinct,
            topValues = topValues,
        )
    }

    override fun close() {
        connection.close()
    }

    companion object {
        /**
         * 引擎内部的行序号列（用于「并列时按首见序」，等价于 tier-1 的 LinkedHashMap 首见序）。
         *
         * 它**不属于用户数据**，所以不会出现在对外暴露的列清单里（列索引必须与用户数据列一一对应）。
         */
        private const val INDEX_COLUMN = "__idx"

        private const val BATCH_SIZE = 5_000

        /** DuckDB 原生库是否就绪（Android 端在 .so 下发前返回 false） */
        fun isAvailable(): Boolean = try {
            Class.forName("org.duckdb.DuckDBDriver")
            true
        } catch (_: Throwable) {
            false
        }

        private fun openConnection(): Connection = try {
            DriverManager.getConnection("jdbc:duckdb:")
        } catch (e: Throwable) {
            throw DuckDbUnavailableException("DuckDB 原生库不可用：当前宿主平台缺少对应的原生库", e)
        }

        /**
         * **大文件列画像**：对 CSV 文件做列级统计（tier-2 规模路径）。
         *
         * 与 [aggregateCsvByName] 同款：数据不进 JVM 堆；列名用表头原文；
         * 口径与 tier-1 的 `summarizeColumn` 一致（count 是实际参与统计的样本数，
         * missing 含空值与脏值，分位数为线性插值）。
         *
         * @param column 列名；null = 全部列
         */
        fun profileCsvByName(file: File, column: String? = null): List<ColumnStat> {
            val started = System.nanoTime()
            fromCsvFile(file).use { engine ->
                val indexes = if (column == null) {
                    engine.columns.indices.toList()
                } else {
                    val index = engine.columns.indexOfFirst { it == column }
                    if (index < 0) {
                        throw IllegalArgumentException(
                            "列「$column」不存在（该文件的列：${engine.columns.joinToString("、")}）",
                        )
                    }
                    listOf(index)
                }
                return indexes.mapNotNull { engine.profileColumn(it) }
            }
        }

        /**
         * **大文件聚合**：直接对 CSV 文件做列式分组聚合（tier-2 规模路径）。
         *
         * 与 [fromRows] 的区别：这里**不把数据搬进 JVM**——DuckDB 自己流式读文件，
         * 所以百万行也能在亚秒级出结果。列名用**表头原文**指定（与 tier-1 工具一致）。
         *
         * 口径：数值解析与 tier-1 同规则（见 [DuckDbSql]）；topN 与「其他 N 项」在 SQL 侧完成。
         */
        fun aggregateCsvByName(
            file: File,
            dimension: String,
            metric: String,
            mode: String = "sum",
            topN: Int = 8,
        ): CsvAggregateResult {
            require(mode == "sum" || mode == "avg") { "未知聚合模式：$mode" }
            val started = System.nanoTime()
            fromCsvFile(file).use { engine ->
                val dimIndex = engine.columns.indexOfFirst { it == dimension }
                if (dimIndex < 0) {
                    throw IllegalArgumentException(
                        "维度列「$dimension」不存在（该文件的列：${engine.columns.joinToString("、")}）",
                    )
                }
                val metricIndex = engine.columns.indexOfFirst { it == metric }
                if (metricIndex < 0) {
                    throw IllegalArgumentException(
                        "指标列「$metric」不存在（该文件的列：${engine.columns.joinToString("、")}）",
                    )
                }
                val items = engine.aggregateBy(dimIndex, metricIndex, mode, topN)
                // 合计 = 明细各项 + 「其他 N 项」（sum 模式即全体合计，share 的分母）
                val grandTotal = items.sumOf { it.value }
                return CsvAggregateResult(
                    rowCount = engine.rowCount,
                    dimension = dimension,
                    metric = metric,
                    mode = mode,
                    items = items,
                    grandTotal = grandTotal,
                    truncatedToTopN = items.any { it.label.startsWith("其他 ") },
                    elapsedMs = (System.nanoTime() - started) / 1_000_000,
                )
            }
        }

        /**
         * 从内存行构建引擎 —— **对拍专用入口**。
         *
         * 两侧喂完全相同的字符串，比的才是「聚合口径」，而不是「读文件口径」。
         */
        fun fromRows(columns: List<String>, rows: List<List<String>>): DuckDbEngine {
            require(columns.isNotEmpty()) { "列不能为空" }
            val connection = openConnection()
            val table = "t"
            val ddl = buildString {
                append("CREATE TABLE $table ($INDEX_COLUMN INTEGER")
                columns.forEach { append(", ${DuckDbSql.quoteIdent(it)} VARCHAR") }
                append(")")
            }
            connection.createStatement().use { it.executeUpdate(ddl) }
            val insertSql = buildString {
                append("INSERT INTO $table VALUES (?")
                repeat(columns.size) { append(", ?") }
                append(")")
            }
            connection.prepareStatement(insertSql).use { statement ->
                var pending = 0
                rows.forEachIndexed { index, row ->
                    statement.setInt(1, index)
                    columns.indices.forEach { c -> statement.setString(c + 2, row.getOrNull(c) ?: "") }
                    statement.addBatch()
                    pending += 1
                    if (pending >= BATCH_SIZE) {
                        statement.executeBatch()
                        pending = 0
                    }
                }
                if (pending > 0) statement.executeBatch()
            }
            return DuckDbEngine(connection, columns, table, rows.size)
        }

        /**
         * 从 CSV 文件直接构建引擎 —— **规模路径入口**。
         *
         * 用 DuckDB 自带的 CSV 读取器（`all_varchar`），全程不把数据搬进 JVM 堆，
         * 这才是 tier-2 存在的意义：5 百万行也不进内存。
         */
        fun fromCsvFile(file: File, delimiter: Char = ','): DuckDbEngine {
            val connection = openConnection()
            val table = "t"
            val path = file.absolutePath.replace("'", "''")
            val delim = if (delimiter == '\t') "\\t" else delimiter.toString()
            val readExpr = "read_csv_auto('$path', delim='$delim', header=true, all_varchar=true)"
            try {
                connection.createStatement().use {
                    it.executeUpdate(
                        "CREATE TABLE $table AS SELECT row_number() OVER () AS $INDEX_COLUMN, * FROM $readExpr",
                    )
                }
            } catch (e: Throwable) {
                // 老版本 DuckDB 的 read_csv_auto 不接受 all_varchar 时，回退到 read_csv
                try {
                    connection.createStatement().use {
                        it.executeUpdate(
                            "CREATE TABLE $table AS SELECT row_number() OVER () AS $INDEX_COLUMN, * FROM " +
                                "read_csv('$path', delim='$delim', header=true, all_varchar=true)",
                        )
                    }
                } catch (e2: Throwable) {
                    connection.close()
                    throw e2
                }
            }
            val columns = mutableListOf<String>()
            connection.createStatement().use { statement ->
                statement.executeQuery("DESCRIBE $table").use { rs ->
                    while (rs.next()) {
                        val name = rs.getString(1)
                        // 内部行序号列不对外暴露：对外列索引必须与用户数据列一一对应
                        if (name != INDEX_COLUMN) columns.add(name)
                    }
                }
            }
            val count = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM $table").use { rs ->
                    rs.next()
                    rs.getLong(1).toInt()
                }
            }
            return DuckDbEngine(connection, columns, table, count)
        }
    }
}

/** DuckDB 原生库不可用（如宿主平台缺原生库） */
class DuckDbUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 大文件（tier-2）分组聚合的结果。
 *
 * @param rowCount       文件里的数据行数（DuckDB 直接读文件统计，不进 JVM 堆）
 * @param dimension      维度列名
 * @param metric         指标列名
 * @param mode           "sum" 或 "avg"
 * @param items          明细（topN 项 + 可选的「其他 N 项」汇总行）
 * @param grandTotal     合计（share 的分母；sum 模式下即全体合计）
 * @param truncatedToTopN 是否存在「其他 N 项」（即分组数超过 topN）
 * @param elapsedMs      读文件 + 聚合耗时
 */
data class CsvAggregateResult(
    val rowCount: Int,
    val dimension: String,
    val metric: String,
    val mode: String,
    val items: List<AggregateItem>,
    val grandTotal: Double,
    val truncatedToTopN: Boolean,
    val elapsedMs: Long,
)
