package com.stragent.host

import com.stragent.core.ports.AgentTool
import com.stragent.core.csv.ColumnType
import com.stragent.core.dataset.Dataset
import com.stragent.core.i18n.Messages
import com.stragent.core.ports.PrefsStore
import com.stragent.data.AbilityDatasets
import com.stragent.data.SessionRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types
import java.util.concurrent.ConcurrentHashMap

/**
 * 数据库连接管理器 —— D11 v3 S5 数据库连接。
 *
 * 管理 JDBC 连接（SQLite/H2 纯 JVM 驱动优先，MySQL/PostgreSQL 可选）。
 * 连接信息不进 ProvenanceManifest（敏感数据），只记录 connectionId + queryHash。
 *
 * 安全：**只读语义由 SQL 校验保证**，不依赖 JDBC 连接级开关——
 * DuckDB 等驱动的 `setReadOnly` 直接抛 `SQLFeatureNotSupportedException`。
 * 校验口径（T-047 纵深）：单条语句 + 必须以 SELECT/WITH 开头 + 不含写操作关键字 +
 * 不含文件/网络数据源（`read_csv`/`read_text`/`readfile`/`glob`/`httpfs` 等函数，
 * 以及 `FROM 'data.csv'` 这类文件路径字面量）；表名/列名走标识符白名单。
 * 执行侧另有 5 秒超时 + 5 万行上限（见 `DatabaseMetadataScanner`）。
 *
 * 诚实边界：
 *  - 无连接池（每次 query 复用同一 Connection，适合本地分析场景）
 *  - 连接本身在驱动层仍是可写的；绕过工具直接拿 Connection 才能写。
 *    MCP 对外只暴露 dbQuery/dbSample/dbColumnStats 三个只读入口。
 *  - 这是**黑名单**不是 SQL 解析器：方言/畸形写法仍可能绕过；硬保证是只读账号。
 *  - MySQL/PostgreSQL 驱动需额外引入（当前 --offline 模式下不可用）
 */
object DatabaseConnectionStore {

    private val connections = ConcurrentHashMap<String, Connection>()

    /**
     * 连接表上限（D25 安全审查：本仓其余资源面全部有界——任务表 128 / SSE 8 槽 / 请求 8MB /
     * 响应 4MB——唯独这条无界；agent 循环重复 connect 不 close 会持续累积重量级 DuckDB 连接）。
     * 满时**拒绝新建**并给可行动指引（先 close），不静默淘汰——淘汰在用连接比拒绝更危险。
     */
    private const val MAX_CONNECTIONS = 16

    /**
     * T-147：JDBC URL scheme 白名单——host 运行时只随包 DuckDB 驱动（engine-duckdb），
     * 其它 scheme 的驱动根本不在 classpath；放行只会把「协议白名单」降级成「驱动缺失裸异常」，
     * 或被恶意 URL 触发已加载驱动的特性（H2 的 INIT=RUNSCRIPT 类）。
     * 扩白名单 = 显式引入驱动 + 只读账号承诺，两件事都要做。
     */
    private val ALLOWED_JDBC_SCHEMES = setOf("duckdb")

    fun connect(url: String, username: String?, password: String?): String {
        val scheme = url.removePrefix("jdbc:").substringBefore(':').lowercase()
        if (scheme !in ALLOWED_JDBC_SCHEMES) {
            throw IllegalArgumentException(
                "不支持的 JDBC 协议「$scheme」：当前只允许 jdbc:duckdb:（T-147 白名单）。" + // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                    "接入其它数据库需要显式引入驱动并扩白名单，且必须配套只读账号（唯一的硬保证）。", // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            )
        }
        // D25 收口：连接表有界——满时拒绝新建（fail-closed），指引先 close；先算后建，避免
        // 「建了再扔」的连接泄漏（ DriverManager 连接在拒绝路径也要 close）。
        if (connections.size >= MAX_CONNECTIONS) {
            throw IllegalArgumentException(
                "数据库连接数已达上限 $MAX_CONNECTIONS：请先用 stragent_db_close 释放不再使用的连接后再新建。", // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            )
        }
        val conn = DriverManager.getConnection(url, username ?: "", password ?: "")
        // 保持驱动默认 autoCommit：只读性由 validateReadOnly 保证。
        // 曾经设 autoCommit=false 并注释成"只读模式"——那是假的安全感，还会让每次
        // SELECT 挂着一个永不提交的事务（快照累积、锁不释放）。
        // T-047：连接 id 改 UUID —— 自增 id 可被猜测，多用户宿主里等于横向越权入口。
        val connId = "db_${java.util.UUID.randomUUID()}"
        connections[connId] = conn
        return connId
    }

    fun getConnection(connId: String): Connection =
        connections[connId] ?: throw IllegalArgumentException(
            Messages.get("error.db.connection_not_found").replace("{0}", connId)
        )

    fun close(connId: String) {
        connections.remove(connId)?.close()
    }

    fun closeAll() {
        connections.values.forEach { runCatching { it.close() } }
        connections.clear()
    }

    /** SQL 安全校验：单条语句，只允许 SELECT 和 WITH 查询 */
    fun validateReadOnly(sql: String) {
        val trimmed = sql.trim()
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException(Messages.get("error.db.readonly_only").replace("{0}", ""))
        }
        // 单条语句：允许一个结尾分号；中间任何分号都视为语句拼接（'SELECT 1; DROP TABLE t'）
        val body = trimmed.removeSuffix(";").trimEnd()
        if (body.contains(';')) {
            throw IllegalArgumentException(Messages.get("error.db.multi_statement"))
        }
        val upper = body.uppercase()
        if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")) {
            throw IllegalArgumentException(
                Messages.get("error.db.readonly_only").replace("{0}", upper.take(10))
            )
        }
        // 词边界匹配：'DROP\tTABLE' / 'update\n' 这类空白变体不能漏；
        // 代价是危险词出现在字符串字面量/注释里也会被拒——只读场景宁可保守。
        val hit = DANGEROUS_KEYWORDS.find(upper)
        if (hit != null) {
            throw IllegalArgumentException(
                Messages.get("error.db.forbidden_keyword").replace("{0}", hit.value)
            )
        }
        // T-047：文件/网络数据源一律拒绝。DuckDB 的 read_csv/read_text/readfile/glob/
        // httpfs 能直接读本机文件或发起网络请求，仅靠写操作黑名单等于把只读库变成
        // 「任意文件读取 + SSRF」。大小写、引号、注释、嵌套子查询都在原串上扫描——
        // 宁可误伤字符串字面量里出现的同名函数。
        val source = FORBIDDEN_SOURCE_FUNCTIONS.find(upper)?.value
        if (source != null) {
            throw IllegalArgumentException(
                Messages.get("error.db.forbidden_source").replace("{0}", source)
            )
        }
        // `FROM 'data.csv'` / `FROM 'https://…'`：DuckDB 可直接把路径当表引用，
        // 不经过任何函数名，必须单独拦路径字面量（同样宁可保守）。
        val path = FILE_PATH_LITERAL.find(body)?.value
        if (path != null) {
            throw IllegalArgumentException(
                Messages.get("error.db.forbidden_source").replace("{0}", path)
            )
        }
    }

    /** 表名/列名会拼接进 SQL，必须是单个（可带 schema 前缀）标识符 */
    fun requireIdentifier(value: String): String {
        if (!IDENTIFIER.matches(value)) {
            throw IllegalArgumentException(Messages.get("error.db.invalid_identifier").replace("{0}", value))
        }
        return value
    }

    /** 把已校验的标识符按段加引号：schema.table → "schema"."table" */
    fun quoteIdentifier(value: String): String {
        requireIdentifier(value)
        return value.split('.').joinToString(".") { "\"$it\"" }
    }

    private val DANGEROUS_KEYWORDS = Regex(
        "\\b(INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|TRUNCATE|GRANT|REVOKE|MERGE|" +
            "CALL|EXECUTE|EXEC|COPY|ATTACH|DETACH|PRAGMA|VACUUM|INTO)\\b",
    )

    /**
     * 文件/网络数据源函数（T-047 / D17 G-03）。DuckDB 表函数与标量函数同名同形，
     * 在嵌套子查询/CTE/标量位置都能用，所以在整条 SQL 上按词边界扫。
     * 名单按**跨引擎**口径维护（T-147）：DuckDB 内置读文件（csv/json/parquet/orc/avro/xlsx/ice）、
     * 外部库扫描（sqlite/postgres/mysql/iceberg/delta/spatial）、文件系统（readfile/glob/list_files）、
     * 网络（httpfs/http_*），以及 PostgreSQL 系文件读/目录列/内网连接/时序滥用函数——
     * 防的是「白名单将来扩到 PG 时黑名单跟着漏」的漂移。
     */
    private val FORBIDDEN_SOURCE_FUNCTIONS = Regex(
        "\\b(" +
            "READ_CSV|READ_CSV_AUTO|READ_TEXT|READ_BLOB|READ_JSON|READ_JSON_AUTO|" +
            "READ_JSON_OBJECTS|READ_NDJSON|READ_NDJSON_AUTO|READ_PARQUET|READ_ORC|" +
            "READ_AVRO|READ_XLSX|READ_ICE|READ_DELTA|" +
            "CSV_SCAN|JSON_SCAN|PARQUET_SCAN|ORC_SCAN|AVRO_SCAN|" +
            "SQLITE_SCAN|SQLITE_ATTACH|POSTGRES_SCAN|POSTGRES_ATTACH|MYSQL_SCAN|MYSQL_ATTACH|" +
            "ICEBERG_SCAN|DELTA_SCAN|ST_READ|ST_REAthe reference harnessP|MD_READ|" +
            "READFILE|WRITEFILE|FILE_READ|FILE_WRITE|GLOB|LIST_FILES|LISTFILES|" +
            "HTTPFS|HTTP_GET|HTTP_POST|HTTP_PUT|HTTP_DELETE|HTTP_HEAD|" +
            "PG_READ_FILE|PG_READ_BINARY_FILE|PG_LS_DIR|DBLINK|PG_SLEEP|LO_IMPORT|LO_EXPORT" +
            ")\\b",
    )

    /**
     * 路径/URL 字面量：`FROM 'data.csv'`、`FROM 'https://host/x'`、`FROM '/etc/passwd'`。
     * DuckDB 允许把路径直接当表引用，绕过所有函数名黑名单。
     * 代价：查询里过滤 URL 路径（如 `path = '/api/v1'`）也会被拒——只读场景宁可保守。
     */
    private val FILE_PATH_LITERAL = Regex(
        "'[^']*(?::/{2}|\\.(?:csv|tsv|parquet|json|ndjson|jsonl|xlsx|xlsm|orc|avro|db|duckdb|sqlite|txt)\\b)[^']*'" +
            "|'(?:\\.{0,2}/|~)[^']*'",
        RegexOption.IGNORE_CASE,
    )

    /** Unicode 字母/数字/下划线，可带一级 schema 前缀；不含引号与空白 */
    private val IDENTIFIER = Regex("^[\\p{L}_][\\p{L}\\p{N}_$]*(\\.[\\p{L}_][\\p{L}\\p{N}_$]*)?$")
}

/**
 * 数据库元数据扫描器 —— D11 v3 S5。
 *
 * 连接后自动提取表结构、列信息，构建 Dataset 视图。
 */
object DatabaseMetadataScanner {

    /** 单次查询的行数上限（T-047）：超出即截断并显式标记，禁止把整张大表拉进堆 */
    const val MAX_ROWS = 50_000

    /** 单次查询的超时（T-047）：驱动原生超时 + 看门狗 cancel 双保险 */
    const val QUERY_TIMEOUT_SECONDS = 5

    /**
     * 查询结果（T-047）：`truncated` 表示命中行数上限（多读了 1 行做探测），
     * 调用方必须把它透出给客户端，而不是静默截断。
     */
    data class QueryOutcome(val dataset: Dataset, val truncated: Boolean)

    /** 列出所有表（兼容 DuckDB 的 BASE TABLE 和标准 TABLE 类型） */
    fun listTables(conn: Connection): List<String> {
        val meta = conn.metaData
        val tables = mutableListOf<String>()
        val rs = meta.getTables(null, null, "%", arrayOf("TABLE", "BASE TABLE"))
        while (rs.next()) {
            val name = rs.getString("TABLE_NAME")
            // 过滤 DuckDB 内部表（以 duckdb_ 或 sqlite_ 开头）
            if (!name.startsWith("duckdb_") && !name.startsWith("sqlite_") && !name.startsWith("pg_")) {
                tables.add(name)
            }
        }
        rs.close()
        return tables
    }

    /** 描述表结构（列名+类型） */
    fun describeTable(conn: Connection, tableName: String): List<ColumnDef> {
        DatabaseConnectionStore.requireIdentifier(tableName)
        val meta = conn.metaData
        val columns = mutableListOf<ColumnDef>()
        val rs = meta.getColumns(null, null, tableName, "%")
        while (rs.next()) {
            val name = rs.getString("COLUMN_NAME")
            val sqlType = rs.getInt("DATA_TYPE")
            val typeStr = rs.getString("TYPE_NAME")
            columns.add(ColumnDef(name, sqlType, typeStr, mapColumnType(sqlType)))
        }
        rs.close()
        return columns
    }

    /** 采样表数据（前 N 行）；同样受超时与行数上限约束 */
    fun sampleTable(conn: Connection, tableName: String, rowCount: Int = 5): Dataset {
        val quoted = DatabaseConnectionStore.quoteIdentifier(tableName)
        val limit = rowCount.coerceIn(1, 1000)
        val columns = describeTable(conn, tableName)
        val colNames = columns.map { it.name }
        val colTypes = columns.map { it.mappedType }

        val rows = mutableListOf<List<String>>()
        conn.createStatement().use { stmt ->
            withQueryGuard(stmt, limit) {
                stmt.executeQuery("SELECT * FROM $quoted LIMIT $limit").use { rs ->
                    while (rs.next()) {
                        rows.add(colNames.indices.map { i ->
                            try { rs.getString(i + 1) ?: "" } catch (_: Exception) { "" }
                        })
                    }
                }
            }
        }

        return Dataset(
            name = tableName,
            importedAt = System.currentTimeMillis(),
            columns = colNames,
            rows = rows,
            columnTypes = colTypes,
        )
    }

    /**
     * 执行只读查询，返回结果与「是否命中行数上限」。
     *
     * 纵深防御（T-047 / D17 G-03）：
     *  - **外层 LIMIT**：`SELECT * FROM (<sql>) stragent_bounded LIMIT MAX_ROWS+1`，
     *    不依赖驱动是否兑现 `setMaxRows`（DuckDB JDBC 的 setMaxRows 是空实现，
     *    见 D5 TS-026）；
     *  - **读循环硬上限**：即使驱动无视 LIMIT，读满 MAX_ROWS+1 行也停；
     *  - **超时**：`setQueryTimeout` 给兑现它的驱动；另起看门狗在 5 秒时 `cancel()`，
     *    覆盖把超时只存字段的驱动（DuckDB），超时统一抛 `SQLTimeoutException`。
     */
    fun executeQuery(
        conn: Connection,
        sql: String,
        maxRows: Int = MAX_ROWS,
        timeoutSeconds: Int = QUERY_TIMEOUT_SECONDS,
    ): QueryOutcome {
        DatabaseConnectionStore.validateReadOnly(sql)
        // 换行包裹：如果原 SQL 以 `--` 行注释结尾，闭合括号/外层 LIMIT 会被注释吞掉
        val bounded = "SELECT * FROM (\n${sql.trim().removeSuffix(";").trimEnd()}\n) stragent_bounded " +
            "LIMIT ${maxRows + 1}"
        return conn.createStatement().use { stmt ->
            withQueryGuard(stmt, maxRows, timeoutSeconds) {
                stmt.executeQuery(bounded).use { rs ->
                    val meta = rs.metaData
                    val colCount = meta.columnCount
                    val colNames = (1..colCount).map { meta.getColumnLabel(it) }
                    val colTypes = (1..colCount).map { mapColumnType(meta.getColumnType(it)) }

                    val rows = mutableListOf<List<String>>()
                    var truncated = false
                    while (rs.next()) {
                        if (rows.size >= maxRows) {
                            // 第 maxRows+1 行只用于探测「还有更多」，不进入数据集
                            truncated = true
                            break
                        }
                        rows.add((1..colCount).map { i ->
                            try { rs.getString(i) ?: "" } catch (_: Exception) { "" }
                        })
                    }

                    QueryOutcome(
                        dataset = Dataset(
                            name = "query_result",
                            importedAt = System.currentTimeMillis(),
                            columns = colNames,
                            rows = rows,
                            columnTypes = colTypes,
                        ),
                        truncated = truncated,
                    )
                }
            }
        }
    }

    /**
     * 设置行数上限与超时，并用看门狗兜底那些把 `setQueryTimeout` 当摆设的驱动。
     * 看门狗只在语句执行/读取期间存活，正常结束立即取消，不留下常驻任务。
     */
    private fun <T> withQueryGuard(
        stmt: java.sql.Statement,
        maxRows: Int,
        timeoutSeconds: Int = QUERY_TIMEOUT_SECONDS,
        block: () -> T,
    ): T {
        stmt.maxRows = maxRows + 1
        stmt.queryTimeout = timeoutSeconds
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val watchdog = timeoutScheduler.schedule({
            cancelled.set(true)
            runCatching { stmt.cancel() } // 驱动不支持 cancel 时只是无效，不掩盖原异常
        }, timeoutSeconds.toLong(), java.util.concurrent.TimeUnit.SECONDS)
        try {
            return block()
        } catch (e: java.sql.SQLException) {
            if (cancelled.get()) {
                throw java.sql.SQLTimeoutException(
                    Messages.get("error.db.timeout").replace("{0}", timeoutSeconds.toString())
                ).apply { initCause(e) }
            }
            throw e
        } finally {
            watchdog.cancel(false)
        }
    }

    /** 超时看门狗：单线程守护调度器，进程退出不阻塞 */
    private val timeoutScheduler = java.util.concurrent.ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "stragent-sql-timeout").apply { isDaemon = true }
    }

    /** SQL 类型到 ColumnType 的映射 */
    private fun mapColumnType(sqlType: Int): ColumnType = when (sqlType) {
        Types.INTEGER, Types.BIGINT, Types.SMALLINT, Types.TINYINT,
        Types.FLOAT, Types.DOUBLE, Types.DECIMAL, Types.NUMERIC,
        Types.REAL -> ColumnType.NUMBER
        Types.DATE, Types.TIMESTAMP, Types.TIME -> ColumnType.TEXT
        else -> ColumnType.TEXT
    }

    data class ColumnDef(
        val name: String,
        val sqlType: Int,
        val typeName: String,
        val mappedType: ColumnType,
    )
}

/** 数据库连接 MCP 工具集（D11 v3 S5） */

fun dbConnectTool(): AgentTool = object : AgentTool {
    override val name = "stragent_db_connect"
    override val description =
        "连接到 DuckDB 数据库（只允许 jdbc:duckdb: URL，如 jdbc:duckdb:/path/to/db.duckdb）。" +  // lint:allow zh-first（工具描述=调用方引导面，T-104）
        "用途：建立数据库连接以执行 SQL 查询和分析。" +
        "安全：查询入口只允许单条 SELECT/WITH，拒绝 DDL/DML 与文件/网络数据源；单次查询超时 5 秒、最多 50000 行。" +
        "连接本身在驱动层仍可写——**请务必使用只读数据库账号**（这是唯一的硬保证）。" +
        "连接表有上限（16），满时新建连接会被拒绝——请用 stragent_db_close 释放不再使用的连接。" +  // lint:allow zh-first（工具描述=调用方引导面，T-104）
        "返回：connectionId（UUID，后续查询工具使用此 ID）。"
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("url", buildJsonObject {
                put("type", "string")
                put("description", "JDBC 连接 URL（只允许 jdbc:duckdb: 前缀，如 jdbc:duckdb:/path/to/db.duckdb）")
            })
            put("username", buildJsonObject {
                put("type", "string")
                put("description", "数据库用户名（可选，取决于目标库的认证方式）") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            })
            put("password", buildJsonObject {
                put("type", "string")
                put("description", "数据库密码（可选；建议使用只读账号）") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            })
        })
        put("required", JsonArray(listOf(JsonPrimitive("url"))))
    }
    override val cacheable = false
    // 建立连接会改变服务端状态
    override val readOnly = false
    // 数据库是外部实体（网络/文件连接）
    override val openWorld = true
    override fun invoke(args: JsonObject): JsonObject {
        val url = args.string("url") ?: throw IllegalArgumentException("url 不能为空")
        val username = args.string("username")
        val password = args.string("password")
        val connId = DatabaseConnectionStore.connect(url, username, password)
        return buildJsonObject {
            put("connectionId", connId)
            put("status", "connected")
            put("url", url)
        }
    }
}

fun dbListTablesTool(): AgentTool = object : AgentTool {
    override val name = "stragent_db_list_tables"
    override val description = "列出数据库中的所有表名。参数：connectionId（stragent_db_connect 返回的 ID）。"
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("connectionId", buildJsonObject { put("type", "string"); put("description", "连接 ID") })
        })
        put("required", JsonArray(listOf(JsonPrimitive("connectionId"))))
    }
    override val cacheable = false
    // 数据库是外部实体（网络/文件连接）
    override val openWorld = true
    override fun invoke(args: JsonObject): JsonObject {
        val connId = args.string("connectionId") ?: throw IllegalArgumentException("connectionId 不能为空")
        val conn = DatabaseConnectionStore.getConnection(connId)
        val tables = DatabaseMetadataScanner.listTables(conn)
        // T-202：表名是外部库元数据回流面——表名本身可携带注入指令，与导入面同一套扫描口径
        // （T-196 边界清偿：db_query/db_sample 扫结果行，元数据面同轮覆盖）
        val signals = tables.flatMap {
            com.stragent.core.security.PromptInjectionHeuristics.scanText(it, "表名[${it.take(24)}]")  // lint:allow zh-first（T-202 扫描定位标签，与导入面同源，D19/D21 语言定位）
        }
        return buildJsonObject {
            put("tables", JsonArray(tables.map { JsonPrimitive(it) }))
            put("count", tables.size)
            put(
                "injectionScan",
                com.stragent.data.StrAgentAbilities.injectionScanJson(
                    com.stragent.core.security.InjectionScanReport(scannedRows = tables.size, signals = signals),
                ),
            )
        }
    }
}

fun dbQueryTool(): AgentTool = object : AgentTool {
    override val name = "stragent_db_query"
    override val description =
        "执行只读 SQL 查询（SELECT/WITH），返回结果数据集。" +
        "安全：只允许 SELECT 和 WITH 查询，拒绝 DDL/DML 与文件/网络数据源" +
        "（read_csv/read_text/readfile/glob/httpfs 等，含文件路径字面量）；" +
        "超时 5 秒、最多返回 50000 行（超出时 truncated=true）。" +
        "返回：columns（列名数组）、rows（数据行数组）、rowCount、truncated、" +  // lint:allow zh-first（T-202 工具描述返回面，D19/D21 语言定位）
        "injectionScan（结果回流面注入扫描，恒输出）。"  // lint:allow zh-first（T-202 工具描述返回面，D19/D21 语言定位）
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("connectionId", buildJsonObject { put("type", "string"); put("description", "连接 ID") })
            put("sql", buildJsonObject { put("type", "string"); put("description", "SQL 查询语句（仅 SELECT/WITH）") })
        })
        put("required", JsonArray(listOf(JsonPrimitive("connectionId"), JsonPrimitive("sql"))))
    }
    override val cacheable = false
    // 数据库是外部实体（网络/文件连接）
    override val openWorld = true
    override fun invoke(args: JsonObject): JsonObject {
        val connId = args.string("connectionId") ?: throw IllegalArgumentException("connectionId 不能为空")
        val sql = args.string("sql") ?: throw IllegalArgumentException("sql 不能为空")
        val conn = DatabaseConnectionStore.getConnection(connId)
        val outcome = DatabaseMetadataScanner.executeQuery(conn, sql)
        val ds = outcome.dataset
        // T-202：查询结果回流面——结果单元格文本与导入面同一套注入扫描（T-196 边界清偿）
        val injection = com.stragent.core.security.PromptInjectionHeuristics.scanDataset(ds)
        return buildJsonObject {
            put("columns", JsonArray(ds.columns.map { JsonPrimitive(it) }))
            put("rowCount", ds.rows.size)
            put(
                "injectionScan",
                com.stragent.data.StrAgentAbilities.injectionScanJson(injection),
            )
            put("rowLimit", DatabaseMetadataScanner.MAX_ROWS)
            put("truncated", outcome.truncated)
            put("rows", JsonArray(ds.rows.map { row ->
                JsonArray(row.map { JsonPrimitive(it) })
            }))
        }
    }
}

fun dbDescribeTool(): AgentTool = object : AgentTool {
    override val name = "stragent_db_describe"
    override val description = "描述表结构（列名、类型）。参数：connectionId、tableName。"
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("connectionId", buildJsonObject { put("type", "string"); put("description", "连接 ID（db_connect 返回的 connectionId）") }) // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            put("tableName", buildJsonObject { put("type", "string"); put("description", "表名（db_list_tables 列出的名称）") }) // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        })
        put("required", JsonArray(listOf(JsonPrimitive("connectionId"), JsonPrimitive("tableName"))))
    }
    override val cacheable = false
    // 数据库是外部实体（网络/文件连接）
    override val openWorld = true
    override fun invoke(args: JsonObject): JsonObject {
        val connId = args.string("connectionId") ?: throw IllegalArgumentException("connectionId 不能为空")
        val tableName = args.string("tableName") ?: throw IllegalArgumentException("tableName 不能为空")
        val conn = DatabaseConnectionStore.getConnection(connId)
        val columns = DatabaseMetadataScanner.describeTable(conn, tableName)
        // T-202：列名/类型是外部库元数据回流面——列名本身可携带注入指令，与导入面同一套扫描口径
        val signals = columns.flatMap {
            com.stragent.core.security.PromptInjectionHeuristics.scanText(it.name, "列名[${it.name.take(24)}]")  // lint:allow zh-first（T-202 扫描定位标签，与导入面同源，D19/D21 语言定位）
        }
        return buildJsonObject {
            put("tableName", tableName)
            put("columns", JsonArray(columns.map { col ->
                buildJsonObject {
                    put("name", col.name)
                    put("type", col.typeName)
                    put("category", col.mappedType.name)
                }
            }))
            put("columnCount", columns.size)
            put(
                "injectionScan",
                com.stragent.data.StrAgentAbilities.injectionScanJson(
                    com.stragent.core.security.InjectionScanReport(scannedRows = columns.size, signals = signals),
                ),
            )
        }
    }
}

fun dbSampleTool(): AgentTool = object : AgentTool {
    override val name = "stragent_db_sample"
    override val description = "采样表数据（前 N 行）。参数：connectionId、tableName、rowCount（缺省 5）。"
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("connectionId", buildJsonObject { put("type", "string"); put("description", "连接 ID（db_connect 返回的 connectionId）") }) // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            put("tableName", buildJsonObject { put("type", "string"); put("description", "表名（db_list_tables 列出的名称）") }) // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            put("rowCount", buildJsonObject { put("type", "integer"); put("description", "采样行数（缺省 5）") })
        })
        put("required", JsonArray(listOf(JsonPrimitive("connectionId"), JsonPrimitive("tableName"))))
    }
    override val cacheable = false
    // 数据库是外部实体（网络/文件连接）
    override val openWorld = true
    override fun invoke(args: JsonObject): JsonObject {
        val connId = args.string("connectionId") ?: throw IllegalArgumentException("connectionId 不能为空")
        val tableName = args.string("tableName") ?: throw IllegalArgumentException("tableName 不能为空")
        val rowCount = args.int("rowCount") ?: 5
        val conn = DatabaseConnectionStore.getConnection(connId)
        val ds = DatabaseMetadataScanner.sampleTable(conn, tableName, rowCount)
        // T-202：采样结果回流面——与 db_query 同一套注入扫描口径（T-196 边界清偿）
        val injection = com.stragent.core.security.PromptInjectionHeuristics.scanDataset(ds)
        return buildJsonObject {
            put("columns", JsonArray(ds.columns.map { JsonPrimitive(it) }))
            put("rowCount", ds.rows.size)
            put(
                "injectionScan",
                com.stragent.data.StrAgentAbilities.injectionScanJson(injection),
            )
            put("rows", JsonArray(ds.rows.map { row ->
                JsonArray(row.map { JsonPrimitive(it) })
            }))
        }
    }
}

fun dbColumnStatsTool(): AgentTool = object : AgentTool {
    override val name = "stragent_db_column_stats"
    override val description = "统计列的分布（count/distinct/null/min/max for numbers）。参数：connectionId、tableName、columnName。"
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("connectionId", buildJsonObject { put("type", "string"); put("description", "连接 ID（db_connect 返回的 connectionId）") }) // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            put("tableName", buildJsonObject { put("type", "string"); put("description", "表名（db_list_tables 列出的名称）") }) // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            put("columnName", buildJsonObject { put("type", "string"); put("description", "columnName 参数") }) // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        })
        put("required", JsonArray(listOf(JsonPrimitive("connectionId"), JsonPrimitive("tableName"), JsonPrimitive("columnName"))))
    }
    override val cacheable = false
    // 数据库是外部实体（网络/文件连接）
    override val openWorld = true
    override fun invoke(args: JsonObject): JsonObject {
        val connId = args.string("connectionId") ?: throw IllegalArgumentException("connectionId 不能为空")
        val tableName = args.string("tableName") ?: throw IllegalArgumentException("tableName 不能为空")
        val columnName = args.string("columnName") ?: throw IllegalArgumentException("columnName 不能为空")
        val conn = DatabaseConnectionStore.getConnection(connId)
        val table = DatabaseConnectionStore.quoteIdentifier(tableName)
        val column = DatabaseConnectionStore.quoteIdentifier(columnName)
        val sql = "SELECT COUNT(*) as cnt, COUNT(DISTINCT $column) as distinct_cnt, " +
            "COUNT(CASE WHEN $column IS NULL THEN 1 END) as null_cnt " +
            "FROM $table"
        val ds = DatabaseMetadataScanner.executeQuery(conn, sql).dataset
        val row = ds.rows.firstOrNull() ?: throw IllegalArgumentException("查询无结果")
        return buildJsonObject {
            put("column", columnName)
            put("totalCount", row[0])
            put("distinctCount", row[1])
            put("nullCount", row[2])
        }
    }
}

/** 所有数据库工具 */
fun dbTools(): List<AgentTool> = listOf(
    dbConnectTool(),
    dbListTablesTool(),
    dbQueryTool(),
    dbDescribeTool(),
    dbSampleTool(),
    dbColumnStatsTool(),
)

/**
 * db 工具的目录授权判定（T-190）：默认隐藏——未使用数据库的部署不下发 db_* 6 条
 * （D24 P1-9：省 token 且缩攻击面）；env `STRAGENT_DB_TOOLS=1` 或 prefs
 * `tools.db.enabled=true`（运行时热切换：prefs.set 后下一次 tools/list 生效）
 * 任一为真即下发。**目录隐藏 ≠ 能力删除**（T-155 同语义）：调度面全量保留，
 * 直接调用仍可达；与受控计算两档同一套「目录收放」授权语义。
 */
internal fun dbToolsAuthorized(prefs: PrefsStore): Boolean {
    if (com.stragent.core.config.Config.get("STRAGENT_DB_TOOLS") == "1") return true
    return prefs.get("tools.db.enabled", "false").trim().equals("true", ignoreCase = true)
}
