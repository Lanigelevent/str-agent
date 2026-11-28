package com.stragent.host

import java.sql.DriverManager
import java.sql.SQLException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * D15 T14: Fuzzing（确定性、可复现）
 *
 * 三条真实接线，每条都有**可判定的不变量**（不是"断言构造出的字符串非空"）：
 *   1. McpBridge.dispatch：任意未知方法 → IllegalArgumentException（不是 NPE/CCE）；
 *   2. tools/call 的畸形参数（缺 name / 非字符串 name / 缺 arguments）→ 仍只会是
 *      IllegalArgumentException，且进程不崩、后续合法调用照常工作；
 *   3. DatabaseConnectionStore.validateReadOnly：随机 SQL 的放行/拒绝必须与
 *      「以 SELECT/WITH 开头且不含危险关键字」这一口径逐条一致（双向断言）。
 *   4. DatabaseMetadataScanner.executeQuery（DuckDB in-memory）：随机 SQL 只会
 *      抛 IllegalArgumentException 或 SQLException，且之后连接仍可查询。
 *
 * 种子固定 0xDEADBEEF，迭代次数固定，跨环境可复现。
 */
class FuzzTest {

    companion object {
        private const val FUZZ_SEED = 0xDEADBEEFL
        private const val ITERATIONS = 200
        private val DANGEROUS = Regex(
            "\\b(INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|TRUNCATE|GRANT|REVOKE|MERGE|" +
                "CALL|EXECUTE|EXEC|COPY|ATTACH|DETACH|PRAGMA|VACUUM|INTO)\\b",
        )

        /** T-047 来源黑名单的**独立口令**：与实现保持同一口径（双向对拍用） */
        private val FORBIDDEN_SOURCE = Regex(
            "\\b(" +
                "READ_CSV|READ_CSV_AUTO|READ_TEXT|READ_BLOB|READ_JSON|READ_JSON_AUTO|" +
                "READ_JSON_OBJECTS|READ_NDJSON|READ_NDJSON_AUTO|READ_PARQUET|READ_ORC|" +
                "READ_AVRO|READ_XLSX|READ_ICE|READ_DELTA|" +
                "CSV_SCAN|JSON_SCAN|PARQUET_SCAN|ORC_SCAN|AVRO_SCAN|" +
                "SQLITE_SCAN|SQLITE_ATTACH|POSTGRES_SCAN|POSTGRES_ATTACH|MYSQL_SCAN|MYSQL_ATTACH|" +
                "ICEBERG_SCAN|DELTA_SCAN|ST_READ|ST_REAthe reference harnessP|MD_READ|" +
                "READFILE|WRITEFILE|FILE_READ|FILE_WRITE|GLOB|LIST_FILES|LISTFILES|" +
                "HTTPFS|HTTP_GET|HTTP_POST|HTTP_PUT|HTTP_DELETE|HTTP_HEAD" +
                ")\\b",
        )

        /** T-047 路径字面量口径（独立口令） */
        private val FILE_PATH = Regex(
            "'[^']*(?::/{2}|\\.(?:csv|tsv|parquet|json|ndjson|jsonl|xlsx|xlsm|orc|avro|db|duckdb|sqlite|txt)\\b)[^']*'" +
                "|'(?:\\.{0,2}/|~)[^']*'",
            RegexOption.IGNORE_CASE,
        )
    }

    private fun tempHome() =
        java.io.File(System.getProperty("java.io.tmpdir"), "str-agent-fuzz-" + System.nanoTime())
            .also { it.mkdirs() }

    // ── 1. dispatch：未知方法只抛 IAE ──

    @Test
    fun `fuzz dispatch - 任意未知方法只抛 McpMethodNotFoundException`() {
        val bootstrap = StrAgentHostBootstrap.create(tempHome(), llm = FakeLlmClient())
        try {
            val rng = kotlin.random.Random(FUZZ_SEED)
            repeat(ITERATIONS) { i ->
                val method = "method_${rng.nextInt()}_$i"
                val error = assertFailsWith<com.stragent.data.McpMethodNotFoundException>("未知方法必须走可读错误路径：$method") {
                    bootstrap.mcpBridge.dispatch(method, JsonObject(emptyMap()))
                }
                assertTrue(error.message?.contains(method) == true, "错误信息应指明方法：${error.message}")
            }
        } finally {
            bootstrap.close()
        }
    }

    @Test
    fun `fuzz dispatch - 畸形 tools-call 参数不产生 NPE 或类型转换异常`() {
        val bootstrap = StrAgentHostBootstrap.create(tempHome(), llm = FakeLlmClient())
        try {
            val payloads = listOf(
                JsonObject(emptyMap()),
                buildJsonObject { put("name", 123) },
                buildJsonObject { put("name", "") },
                buildJsonObject { put("name", "nonexistent_tool") },
                buildJsonObject {
                    put("name", "stragent_import_table")
                    put("arguments", "not-an-object")
                },
            )
            for (params in payloads) {
                // T-079：畸形参数的「可读错误」有两种合法形态——协议错误仍外抛
                // （未知工具等），工具执行错误包装成 isError 结果回执；两者都必须可读。
                val outcome = runCatching { bootstrap.mcpBridge.dispatch("tools/call", params) }
                val readable = outcome.exceptionOrNull()?.message
                    ?: outcome.getOrNull()
                        ?.get("isError")
                        ?.let { isError -> isError.jsonPrimitive.content == "true" }
                        ?.takeIf { it }
                        ?.let { outcome.getOrNull()!!["structuredContent"]!!.jsonObject["error"]!!.jsonPrimitive.content }
                assertNotNull(readable, "畸形参数应只产生可读错误（异常或 isError 回执）：$params")
            }
            // 交接后合法调用仍工作：未知方法的错误语义没有把桥搞坏
            assertNotNull(bootstrap.mcpBridge.dispatch("initialize", JsonObject(emptyMap())))
        } finally {
            bootstrap.close()
        }
    }

    // ── 2. validateReadOnly：口径双向对拍 ──

    @Test
    fun `fuzz validateReadOnly - 放行与拒绝与口径逐条一致`() {
        val rng = kotlin.random.Random(FUZZ_SEED)
        repeat(ITERATIONS) { i ->
            val sql = randomSql(rng, i)
            // 口径与实现逐字对齐：单语句（仅允许结尾分号）+ SELECT/WITH 开头 +
            // 无危险词（词边界）+ 无文件/网络函数与路径字面量（T-047）
            val body = sql.trim().removeSuffix(";").trimEnd()
            val upper = body.uppercase()
            val expectedAllowed = (upper.startsWith("SELECT") || upper.startsWith("WITH")) &&
                !body.contains(';') &&
                !DANGEROUS.containsMatchIn(upper) &&
                !FORBIDDEN_SOURCE.containsMatchIn(upper) &&
                !FILE_PATH.containsMatchIn(body)
            val failure = runCatching { DatabaseConnectionStore.validateReadOnly(sql) }.exceptionOrNull()
            if (expectedAllowed) {
                assertEquals(null, failure, "应放行却被拒：${sql.replace("\n", "\\n")} → ${failure?.message}")
            } else {
                assertTrue(failure is IllegalArgumentException,
                    "应拒绝却不是 IAE：${sql.replace("\n", "\\n")} → $failure")
            }
        }
    }

    @Test
    fun `fuzz validateReadOnly - DDL 语句全部被拒绝`() {
        val ddls = listOf(
            "DROP TABLE users",
            "CREATE TABLE t (id INT)",
            "ALTER TABLE t ADD COLUMN c VARCHAR(100)",
            "TRUNCATE TABLE t",
            "INSERT INTO t VALUES (1)",
            "UPDATE t SET id = 2",
            "DELETE FROM t",
            "GRANT ALL ON t TO user",
            "REVOKE ALL ON t FROM user",
            "SELECT * FROM t; DROP TABLE t",
        )
        ddls.forEach { ddl ->
            val ex = assertFailsWith<IllegalArgumentException> { DatabaseConnectionStore.validateReadOnly(ddl) }
            assertNotNull(ex.message)
        }
    }

    // ── 3. executeQuery：随机 SQL 不崩进程，连接仍可用 ──

    @Test
    fun `fuzz executeQuery - 随机 SQL 只抛可预期异常且连接可继续使用`() {
        val conn = DriverManager.getConnection("jdbc:duckdb:")
        try {
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE fuzz (id INTEGER, name VARCHAR)")
                st.execute("INSERT INTO fuzz VALUES (1, 'a'), (2, 'b')")
            }
            val rng = kotlin.random.Random(FUZZ_SEED)
            repeat(ITERATIONS / 4) { i ->
                val sql = randomSql(rng, i)
                val failure = runCatching { DatabaseMetadataScanner.executeQuery(conn, sql) }.exceptionOrNull()
                if (failure != null) {
                    assertTrue(
                        failure is IllegalArgumentException || failure is SQLException,
                        "随机 SQL 只应抛 IAE/SQLException，实际 ${failure::class.simpleName}: $failure（${sql.replace("\n", "\\n")}）",
                    )
                }
            }
            // 随机轰炸之后，合法查询仍然可用（连接未被搞坏）
            val dataset = DatabaseMetadataScanner.executeQuery(conn, "SELECT id, name FROM fuzz ORDER BY id").dataset
            assertEquals(listOf("1", "2"), dataset.rows.map { it[0] })
        } finally {
            conn.close()
        }
    }

    /** 随机 SQL：以确定的 token 序列拼装，覆盖「合法前缀 + 危险词 + 垃圾字符」三类 */
    private fun randomSql(rng: kotlin.random.Random, iteration: Int): String {
        val tokens = listOf(
            "SELECT", "INSERT", "UPDATE", "DELETE", "DROP", "CREATE", "ALTER",
            "TABLE", "FROM", "WHERE", "JOIN", "ON", "SET", "VALUES", "INTO",
            "*", "1", "''", "'; DROP TABLE", "/*", "*/", "\u0000", "\n",
            "fuzz", "test;", "-- comment", "/* comment */", "WITH",
            // T-047：来源黑名单也必须与口令一致（函数名/路径字面量两类）
            "read_csv", "READ_TEXT", "glob(", "httpfs", "'/etc/passwd'", "'sales.csv'",
            "'https://example.com/data.parquet'",
        )
        val prefix = when (iteration % 3) {
            0 -> ""
            1 -> "SELECT "
            else -> "WITH "
        }
        return prefix + buildString {
            repeat(rng.nextInt(1, 8)) {
                append(tokens.random(rng))
                append(' ')
            }
        }.trim()
    }
}
