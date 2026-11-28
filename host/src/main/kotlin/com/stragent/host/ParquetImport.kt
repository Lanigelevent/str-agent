package com.stragent.host

import com.stragent.core.csv.ColumnType
import com.stragent.core.csv.Csv
import com.stragent.core.csv.CsvSampler
import com.stragent.core.dataset.Dataset
import com.stragent.core.dataset.SamplingInfo
import com.stragent.data.AbilityDatasets
import com.stragent.data.datasetIdOf
import java.io.File
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import kotlin.math.ceil

/**
 * Parquet 导入（T-058）—— 复用宿主已有的 DuckDB 引擎，不新增依赖、不手写列式解析器。
 *
 * 分工与口径：
 *  - **schema 不靠猜**：`DESCRIBE SELECT * FROM read_parquet(?)` 直接给出 Parquet 自带类型，
 *    回包逐列给出 `parquetType`（原始类型）/ `type`（number|text）/ `nullable`；
 *  - **抽样与 CSV 同一语义**：`row_number()` 按文件顺序编号，固定步长取第 1、1+step…行；
 *    `SET threads=1` + `preserve_insertion_order=true` 保证扫描顺序稳定，同一文件重复导入
 *    得到同一份样本与同一个 datasetId；
 *  - **指纹**：整份文件字节的 SHA-256（与 CSV 抽样同一口径），文件改一个字节就变；
 *  - **统一成字符串表**：所有列 `CAST(... AS VARCHAR)`，空值写空串——与其它导入源同构，
 *    统计与报告走同一条 tier-1 路径。
 *
 * 诚实边界：
 *  - 单文件上限 256MB（与 CSV 抽样一致），不处理目录 / 多文件数据集；
 *  - 嵌套类型（STRUCT/LIST/MAP）按 DuckDB 的 VARCHAR 形式入表（如 `{'a': 1}`），不展开；
 *  - 读取失败统一转成可解释错误（不是 Parquet / 损坏 / 不支持的压缩编码），不吞原始信息。
 */
object ParquetImport {

    /**
     * 导入一个本地 Parquet 文件。
     *
     * @throws IllegalArgumentException 文件不存在/超限/不是 Parquet/重复列名等可解释错误
     */
    fun import(file: File, name: String, targetRows: Int = Csv.MAX_ROWS): Result {
        require(targetRows > 0) { "targetRows 必须为正数" }
        if (!file.isFile) throw IllegalArgumentException("文件不存在：${file.path}")
        if (file.length() > CsvSampler.MAX_FILE_BYTES) {
            throw IllegalArgumentException(
                "文件过大（${file.length() / 1024 / 1024}MB，上限 ${CsvSampler.MAX_FILE_BYTES / 1024 / 1024}MB）：" +
                    "请先拆分文件或导出为更小的批次",
            )
        }

        val fingerprint = fingerprintOf(file)
        val sourceBytes = file.length()

        return withDuckDb(file) { conn ->
            val described = describe(conn, file)
            if (described.isEmpty()) throw IllegalArgumentException("Parquet 里没有列：${file.name}")
            val duplicate = described.groupBy { it.name }.filterValues { it.size > 1 }.keys
            if (duplicate.isNotEmpty()) {
                throw IllegalArgumentException(
                    "Parquet 里存在重复列名（${duplicate.joinToString("、")}），无法确定引用哪一列；请先重命名导出",
                )
            }

            val totalRowsLong = count(conn, file)
            if (totalRowsLong <= 0) throw IllegalArgumentException("Parquet 里没有数据行：${file.name}")
            if (totalRowsLong > Int.MAX_VALUE) {
                throw IllegalArgumentException("Parquet 行数过多（$totalRowsLong），超过工具上限")
            }
            val totalRows = totalRowsLong.toInt()

            val step = ceil(totalRows.toDouble() / targetRows).toInt().coerceAtLeast(1)
            val rows = sampleRows(conn, file, described, step)
            val expected = ceil(totalRows.toDouble() / step).toInt()
            if (rows.size != expected) {
                throw IllegalStateException("抽样行数与预期不一致（预期 $expected 行，读到 ${rows.size} 行）：文件可能在读取过程中被修改")
            }

            val sampling = if (rows.size < totalRows) {
                SamplingInfo(
                    targetRows = targetRows,
                    step = step,
                    totalRows = totalRows,
                    fingerprint = fingerprint,
                    rowNumbers = rows.indices.map { (it + 1) * step - step + 1 },
                )
            } else {
                null
            }

            val dataset = Dataset(
                name = name,
                importedAt = System.currentTimeMillis(),
                columns = described.map { it.name },
                rows = rows,
                columnTypes = described.map { it.type },
                delimiter = ',',
                truncated = sampling != null,
                sourceRows = totalRows,
                sampling = sampling,
            )
            Result(
                dataset = dataset,
                datasetId = datasetIdOf("$fingerprint|${SamplingInfo.METHOD_SYSTEMATIC}|$targetRows"),
                columns = described,
                fingerprint = fingerprint,
                sourceBytes = sourceBytes,
                totalRows = totalRows,
                step = step,
                targetRows = targetRows,
            )
        }
    }

    /** 一列 Parquet 字段的描述（DESCRIBE 原样 + 分析口径映射） */
    data class Column(
        val name: String,
        /** Parquet/DuckDB 原始类型，如 `BIGINT` / `DECIMAL(18,2)` / `TIMESTAMP` / `STRUCT(a INTEGER)` */
        val parquetType: String,
        /** 分析口径：number（参与数值统计）/ text */
        val type: ColumnType,
        val nullable: Boolean,
    )

    /** 导入结果（工具层负责拼回包与落注册表） */
    data class Result(
        val dataset: Dataset,
        val datasetId: String,
        val columns: List<Column>,
        val fingerprint: String,
        val sourceBytes: Long,
        val totalRows: Int,
        val step: Int,
        val targetRows: Int,
    ) {
        val sampled: Boolean get() = dataset.sampling != null
    }

    private fun withDuckDb(file: File, block: (Connection) -> Result): Result {
        val conn: Connection = try {
            DriverManager.getConnection("jdbc:duckdb:")
        } catch (e: SQLException) {
            throw IllegalStateException("DuckDB 驱动不可用，无法读取 Parquet：${e.message}")
        }
        conn.use { c ->
            try {
                c.createStatement().use { st ->
                    // 顺序与内存：单线程扫描 + 保留插入顺序 —— 抽样的行号才是确定的
                    st.execute("SET threads=1")
                    st.execute("SET preserve_insertion_order=true")
                    st.execute("SET memory_limit='512MB'")
                }
                return block(c)
            } catch (e: SQLException) {
                throw IllegalArgumentException(
                    "Parquet 读取失败（${file.name}）：${e.message?.lineSequence()?.firstOrNull() ?: "未知 SQL 错误"}。" +
                        "可能原因：文件不是 Parquet、文件损坏、或使用了不支持的压缩编码。",
                )
            }
        }
    }

    private fun prepared(conn: Connection, sql: String, vararg params: String): PreparedStatement {
        val st = conn.prepareStatement(sql)
        params.forEachIndexed { index, value -> st.setString(index + 1, value) }
        return st
    }

    private fun count(conn: Connection, file: File): Long =
        prepared(conn, "SELECT COUNT(*) FROM read_parquet(?)", file.absolutePath).use { st ->
            st.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    private fun describe(conn: Connection, file: File): List<Column> =
        prepared(conn, "DESCRIBE SELECT * FROM read_parquet(?)", file.absolutePath).use { st ->
            st.executeQuery().use { rs ->
                val columns = mutableListOf<Column>()
                while (rs.next()) {
                    val name = rs.getString("column_name")
                    val type = rs.getString("column_type") ?: "UNKNOWN"
                    columns.add(
                        Column(
                            name = name,
                            parquetType = type,
                            type = analysisTypeOf(type),
                            nullable = (rs.getString("null") ?: "YES").equals("YES", ignoreCase = true),
                        ),
                    )
                }
                columns
            }
        }

    private fun sampleRows(
        conn: Connection,
        file: File,
        columns: List<Column>,
        step: Int,
    ): List<List<String>> {
        val rowNumber = uniqueAlias("__str_agent_rn", columns.map { it.name })
        val sql = buildString {
            append("SELECT * FROM (")
            append("SELECT row_number() OVER () AS ${quote(rowNumber)}, ")
            append(columns.joinToString(", ") { "CAST(${quote(it.name)} AS VARCHAR) AS ${quote(it.name)}" })
            append(" FROM read_parquet(?))")
            append(" WHERE (${quote(rowNumber)} - 1) % ? = 0")
            append(" ORDER BY ${quote(rowNumber)}")
        }
        val st = conn.prepareStatement(sql)
        return st.use { ps ->
            ps.setString(1, file.absolutePath)
            ps.setInt(2, step)
            ps.executeQuery().use { rs ->
                val rows = mutableListOf<List<String>>()
                while (rs.next()) {
                    // 第 1 列是行号（只用于筛选与排序），数据列从第 2 列开始
                    rows.add(columns.indices.map { index -> rs.getString(index + 2) ?: "" })
                }
                rows
            }
        }
    }

    private fun uniqueAlias(base: String, columns: List<String>): String {
        var alias = base
        while (columns.contains(alias)) alias += "_"
        return alias
    }

    private fun quote(identifier: String): String = "\"" + identifier.replace("\"", "\"\"") + "\""

    private fun analysisTypeOf(parquetType: String): ColumnType {
        val t = parquetType.uppercase()
        val numeric = listOf(
            "TINYINT", "SMALLINT", "INTEGER", "BIGINT", "HUGEINT",
            "UTINYINT", "USMALLINT", "UINTEGER", "UBIGINT", "UHUGEINT",
            "FLOAT", "DOUBLE", "REAL", "DECIMAL", "NUMERIC",
        )
        return if (numeric.any { t.startsWith(it) }) ColumnType.NUMBER else ColumnType.TEXT
    }

    private fun fingerprintOf(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
    }
}
