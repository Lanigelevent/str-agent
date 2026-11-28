package com.stragent.host

import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLTimeoutException
import java.sql.Statement
import java.sql.Types
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-047 SQL 纵深防御（D17 G-03）验收测试。
 *
 * 四组证据：
 *  ①来源黑名单——文件/网络函数（read_csv/read_text/readfile/glob/httpfs…）在
 *    大小写、引号、注释、嵌套子查询、CTE 等变体下都被拒绝；路径字面量（FROM 'x.csv'/
 *    'https://…'/'/etc/passwd'）同样被拒；
 *  ②负控——普通字符串、列名、日期不误伤（防止「一律拒绝」的假安全）；
 *  ③执行上限——外层 LIMIT 与 setMaxRows/setQueryTimeout 真正下达（代理捕获），
 *    大结果集在 50000 行截断并标记 truncated；
 *  ④超时看门狗——真实 DuckDB 慢查询在期限后被 cancel，抛 SQLTimeoutException，
 *    连接仍可继续使用。
 */
class SqlDefenseTest {

    // ── ① 来源黑名单 ──

    @Test
    fun `文件与网络数据源一律拒绝——大小写-引号-注释-嵌套-CET 变体`() {
        val cases = listOf(
            "SELECT * FROM read_csv('/etc/passwd')",
            "select * from READ_CSV_AUTO('/tmp/a.csv')",
            "SELECT * FROM \"read_csv\"('/tmp/a.csv')",
            "SELECT read_text('/etc/passwd')",
            "SELECT * FROM readfile('/etc/passwd')",
            "SELECT * FROM glob('/etc/*')",
            "SELECT * FROM httpfs('https://example.com/x')",
            "SELECT * FROM (SELECT * FROM read_csv('x.csv')) t",
            "WITH x AS (SELECT * FROM read_json_auto('x.json')) SELECT * FROM x",
            "SELECT * FROM parquet_scan('x.parquet')",
            "SELECT * FROM sqlite_scan('x.db', 'users')",
            "SELECT * FROM delta_scan('s3://bucket/table')",
            "SELECT 1 /* read_csv */",
            "SELECT 1 -- read_text('/etc/passwd')",
            // 路径字面量：DuckDB 可把路径当表引用，绕过所有函数名
            "SELECT * FROM 'data.csv'",
            "SELECT * FROM 'https://evil.example/x.parquet'",
            "SELECT * FROM '/etc/passwd'",
            "SELECT * FROM '../secrets.db'",
            "SELECT * FROM '~/.ssh/id_rsa'",
        )
        cases.forEach { sql ->
            val ex = assertFailsWith<IllegalArgumentException>("应拒绝来源引用: $sql") {
                DatabaseConnectionStore.validateReadOnly(sql)
            }
            assertTrue(
                ex.message!!.contains("数据源") || ex.message!!.contains("source"),
                "错误信息应说明来源限制: ${ex.message}",
            )
        }
    }

    @Test
    fun `负控——普通字符串-列名-日期不误伤`() {
        listOf(
            "SELECT * FROM users WHERE name = 'read'",
            "SELECT load_factor, offset_value, download FROM metrics",
            "SELECT * FROM t WHERE created_at = '2024-01-01'",
            "SELECT * FROM t WHERE path = 'api/v1'",
            "SELECT * FROM \"main\".\"users\"",
            "WITH cte AS (SELECT 1 AS a) SELECT * FROM cte",
            "SELECT * FROM range(10)",
            "SELECT * FROM t WHERE note = 'globally good'",
        ).forEach { sql ->
            DatabaseConnectionStore.validateReadOnly(sql)
        }
    }

    // ── ② 连接 id ──

    @Test
    fun `连接 id 是 UUID——不可猜测且互不相同`() {
        val id1 = DatabaseConnectionStore.connect("jdbc:duckdb:", null, null)
        val id2 = DatabaseConnectionStore.connect("jdbc:duckdb:", null, null)
        try {
            val uuid = Regex("db_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
            assertTrue(uuid.matches(id1), "连接 id 应为 UUID：$id1")
            assertTrue(uuid.matches(id2), "连接 id 应为 UUID：$id2")
            assertTrue(id1 != id2, "两次连接 id 不应相同")
        } finally {
            DatabaseConnectionStore.close(id1)
            DatabaseConnectionStore.close(id2)
        }
    }

    // ── ③ 执行上限 ──

    @Test
    fun `语句级防线——外层 LIMIT-行数-超时真正下达`() {
        val rec = Recording()
        val outcome = DatabaseMetadataScanner.executeQuery(fakeConnection(rec), "SELECT 1 -- 结尾行注释")
        assertEquals(0, outcome.dataset.rows.size)
        assertFalse(outcome.truncated)
        assertEquals(DatabaseMetadataScanner.QUERY_TIMEOUT_SECONDS, rec.queryTimeout)
        assertEquals(DatabaseMetadataScanner.MAX_ROWS + 1, rec.maxRows)
        val sent = rec.sql!!
        assertTrue(sent.startsWith("SELECT * FROM ("), "必须外层包裹：$sent")
        assertTrue(sent.contains(") stragent_bounded LIMIT ${DatabaseMetadataScanner.MAX_ROWS + 1}"), "必须外层 LIMIT：$sent")
        assertTrue(sent.contains("-- 结尾行注释\n)"), "行注释后必须换行防止吞掉闭合括号：$sent")
    }

    @Test
    fun `大结果集在 50000 行截断并显式标记`() {
        val connId = DatabaseConnectionStore.connect("jdbc:duckdb:", null, null)
        try {
            val start = System.nanoTime()
            val result = dbQueryTool().invoke(
                buildJsonObject {
                    put("connectionId", connId)
                    put("sql", "SELECT * FROM range(120000)")
                },
            )
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertEquals(DatabaseMetadataScanner.MAX_ROWS, result["rowCount"]!!.jsonPrimitive.content.toInt())
            assertEquals("true", result["truncated"]!!.jsonPrimitive.content)
            assertEquals(
                DatabaseMetadataScanner.MAX_ROWS.toString(),
                result["rowLimit"]!!.jsonPrimitive.content,
            )
            assertTrue(elapsedMs < 5_000, "截断应在 5 秒内完成，实际 ${elapsedMs}ms")

            // 未达上限时不能误报截断
            val small = dbQueryTool().invoke(
                buildJsonObject {
                    put("connectionId", connId)
                    put("sql", "SELECT * FROM range(49999)")
                },
            )
            assertEquals(49_999, small["rowCount"]!!.jsonPrimitive.content.toInt())
            assertEquals("false", small["truncated"]!!.jsonPrimitive.content)
        } finally {
            DatabaseConnectionStore.close(connId)
        }
    }

    @Test
    fun `超时看门狗——慢查询在期限后中断且连接仍可用`() {
        val conn = DriverManager.getConnection("jdbc:duckdb:")
        try {
            val start = System.nanoTime()
            val ex = assertFailsWith<SQLTimeoutException>("慢查询应被超时取消") {
                DatabaseMetadataScanner.executeQuery(
                    conn,
                    "SELECT count(*) FROM range(100000000) a CROSS JOIN range(1000) b",
                    timeoutSeconds = 1,
                )
            }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertTrue(elapsedMs >= 900, "不应在期限前中断：${elapsedMs}ms")
            assertTrue(elapsedMs < 10_000, "应在期限后尽快中断：${elapsedMs}ms")
            assertTrue(ex.message!!.contains("1"), "错误信息应包含超时秒数：${ex.message}")
            // 取消后连接仍可继续用（驱动 cancel 不应把连接弄坏）
            val ok = DatabaseMetadataScanner.executeQuery(conn, "SELECT 42").dataset
            assertEquals("42", ok.rows[0][0])
        } finally {
            conn.close()
        }
    }

    // ── 代理工具 ──

    private class Recording {
        var sql: String? = null
        var queryTimeout: Int = -1
        var maxRows: Int = -1
        var cancelled: Boolean = false
    }

    private fun fakeConnection(rec: Recording): Connection {
        val meta = proxy(ResultSetMetaData::class.java) { method, _ ->
            when (method.name) {
                "getColumnCount" -> 1
                "getColumnLabel" -> "x"
                "getColumnType" -> Types.INTEGER
                else -> defaultValue(method)
            }
        }
        val rs = proxy(ResultSet::class.java) { method, _ ->
            when (method.name) {
                "getMetaData" -> meta
                "next" -> false
                "getString" -> ""
                else -> defaultValue(method)
            }
        }
        val stmt = proxy(Statement::class.java) { method, args ->
            when (method.name) {
                "setQueryTimeout" -> {
                    rec.queryTimeout = args!![0] as Int
                    null
                }
                "setMaxRows" -> {
                    rec.maxRows = args!![0] as Int
                    null
                }
                "executeQuery" -> {
                    rec.sql = args!![0] as String
                    rs
                }
                "cancel" -> {
                    rec.cancelled = true
                    null
                }
                "close" -> null
                "isClosed" -> false
                else -> defaultValue(method)
            }
        }
        return proxy(Connection::class.java) { method, _ ->
            when (method.name) {
                "createStatement" -> stmt
                "isClosed" -> false
                "close" -> null
                else -> defaultValue(method)
            }
        }
    }

    private fun <T> proxy(type: Class<T>, handler: (Method, Array<Any?>?) -> Any?): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            handler(method, args)
        } as T
    }

    private fun defaultValue(method: Method): Any? = when (method.returnType) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Double.TYPE -> 0.0
        java.lang.Float.TYPE -> 0f
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }

    @Test
    fun `负控——PostgreSQL 文件读与内网函数被拒（T-147）`() {
        // 跨引擎黑名单：即使将来白名单扩到 PG，这 7 个函数也必须先被拦
        for (sql in listOf(
            "SELECT pg_read_file('/etc/passwd')",
            "SELECT pg_read_binary_file('/etc/shadow')",
            "SELECT * FROM pg_ls_dir('/etc')",
            "SELECT * FROM dblink('dbname=test', 'select 1')",
            "SELECT pg_sleep(10)",
            "SELECT lo_import('/etc/passwd')",
            "SELECT lo_export(1, '/tmp/x')",
        )) {
            val e = assertFailsWith<IllegalArgumentException> {
                DatabaseConnectionStore.validateReadOnly(sql)
            }
            assertTrue(
                e.message!!.contains("文件/网络数据源", true) || e.message!!.contains("只读", true),
                "拒绝信息要可解释：${e.message}",
            )
        }
    }
}
