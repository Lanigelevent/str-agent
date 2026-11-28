package com.stragent.host

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertContains
import com.stragent.core.i18n.Messages

/**
 * S5 DatabaseTool 端到端集成测试（D11 v3 优化轮）。
 *
 * 用 DuckDB in-memory 数据库验证完整链路：
 *   connect → 建表插数据 → list_tables → describe → query → sample → column_stats → close
 *
 * 为什么用 DuckDB：engine-duckdb 模块已引入 duckdb_jdbc 1.4.5.0，
 * 纯 JVM 驱动（自带原生库），--offline 缓存可用。
 */
class DatabaseToolIntegrationTest {

    private fun setupTestDb(connId: String) {
        val conn = DatabaseConnectionStore.getConnection(connId)
        conn.createStatement().use { stmt ->
            stmt.execute("CREATE TABLE users (id INTEGER, name VARCHAR, age INTEGER)")
            stmt.execute("INSERT INTO users VALUES (1, 'Alice', 30), (2, 'Bob', 25), (3, 'Charlie', 35)")
        }
        // 连接保持驱动默认 autoCommit=true：每条语句即提交，无需手动 commit
        // （自动提交关闭时 SELECT 会一直挂事务）
    }

    @Test
    fun `connect 建表 list_tables 端到端`() {
        val connectResult = dbConnectTool().invoke(buildJsonObject {
            put("url", JsonPrimitive("jdbc:duckdb:"))
        })
        val connId = connectResult["connectionId"]!!.jsonPrimitive.content
        assertTrue(connId.startsWith("db_"))

        try {
            setupTestDb(connId)

            val listResult = dbListTablesTool().invoke(buildJsonObject {
                put("connectionId", JsonPrimitive(connId))
            })
            val tables = listResult["tables"]!!.jsonArray
            assertTrue(tables.size >= 1, "应至少有 1 张表")
            val tableNames = tables.map { it.jsonPrimitive.content }
            assertContains(tableNames, "users")
        } finally {
            DatabaseConnectionStore.close(connId)
        }
    }

    @Test
    fun `describe 返回列名和类型`() {
        val connId = dbConnectTool().invoke(buildJsonObject {
            put("url", JsonPrimitive("jdbc:duckdb:"))
        })["connectionId"]!!.jsonPrimitive.content

        try {
            setupTestDb(connId)

            val result = dbDescribeTool().invoke(buildJsonObject {
                put("connectionId", JsonPrimitive(connId))
                put("tableName", JsonPrimitive("users"))
            })
            assertEquals("users", result["tableName"]!!.jsonPrimitive.content)
            assertEquals(3, result["columnCount"]!!.jsonPrimitive.content.toInt())
            val cols = result["columns"]!!.jsonArray
            assertEquals("id", cols[0].jsonObject["name"]!!.jsonPrimitive.content)
            assertEquals("name", cols[1].jsonObject["name"]!!.jsonPrimitive.content)
            assertEquals("age", cols[2].jsonObject["name"]!!.jsonPrimitive.content)
        } finally {
            DatabaseConnectionStore.close(connId)
        }
    }

    @Test
    fun `query 返回正确数据行`() {
        val connId = dbConnectTool().invoke(buildJsonObject {
            put("url", JsonPrimitive("jdbc:duckdb:"))
        })["connectionId"]!!.jsonPrimitive.content

        try {
            setupTestDb(connId)

            val result = dbQueryTool().invoke(buildJsonObject {
                put("connectionId", JsonPrimitive(connId))
                put("sql", JsonPrimitive("SELECT name, age FROM users WHERE age > 26 ORDER BY age"))
            })
            val cols = result["columns"]!!.jsonArray
            assertEquals(2, cols.size)
            assertEquals("name", cols[0].jsonPrimitive.content)
            assertEquals("age", cols[1].jsonPrimitive.content)
            assertEquals(2, result["rowCount"]!!.jsonPrimitive.content.toInt())
            val rows = result["rows"]!!.jsonArray
            // Alice (30) and Charlie (35)
            assertEquals("Alice", rows[0].jsonArray[0].jsonPrimitive.content)
            assertEquals("30", rows[0].jsonArray[1].jsonPrimitive.content)
            assertEquals("Charlie", rows[1].jsonArray[0].jsonPrimitive.content)
        } finally {
            DatabaseConnectionStore.close(connId)
        }
    }

    @Test
    fun `sample 返回前 N 行`() {
        val connId = dbConnectTool().invoke(buildJsonObject {
            put("url", JsonPrimitive("jdbc:duckdb:"))
        })["connectionId"]!!.jsonPrimitive.content

        try {
            setupTestDb(connId)

            val result = dbSampleTool().invoke(buildJsonObject {
                put("connectionId", JsonPrimitive(connId))
                put("tableName", JsonPrimitive("users"))
                put("rowCount", JsonPrimitive(2))
            })
            assertEquals(2, result["rowCount"]!!.jsonPrimitive.content.toInt())
        } finally {
            DatabaseConnectionStore.close(connId)
        }
    }

    @Test
    fun `column_stats 返回正确统计`() {
        val connId = dbConnectTool().invoke(buildJsonObject {
            put("url", JsonPrimitive("jdbc:duckdb:"))
        })["connectionId"]!!.jsonPrimitive.content

        try {
            setupTestDb(connId)

            val result = dbColumnStatsTool().invoke(buildJsonObject {
                put("connectionId", JsonPrimitive(connId))
                put("tableName", JsonPrimitive("users"))
                put("columnName", JsonPrimitive("age"))
            })
            assertEquals("age", result["column"]!!.jsonPrimitive.content)
            assertEquals("3", result["totalCount"]!!.jsonPrimitive.content)
            assertEquals("3", result["distinctCount"]!!.jsonPrimitive.content)
            assertEquals("0", result["nullCount"]!!.jsonPrimitive.content)
        } finally {
            DatabaseConnectionStore.close(connId)
        }
    }

    @Test
    fun `query 拒绝非 SELECT 语句`() {
        val connId = dbConnectTool().invoke(buildJsonObject {
            put("url", JsonPrimitive("jdbc:duckdb:"))
        })["connectionId"]!!.jsonPrimitive.content

        try {
            setupTestDb(connId)
            try {
                dbQueryTool().invoke(buildJsonObject {
                    put("connectionId", JsonPrimitive(connId))
                    put("sql", JsonPrimitive("DELETE FROM users WHERE id = 1"))
                })
                throw AssertionError("应拒绝 DELETE")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("禁止") || e.message!!.contains("SELECT"))
            }
        } finally {
            DatabaseConnectionStore.close(connId)
        }
    }

    @Test
    fun `close 后再查询报错`() {
        val connId = dbConnectTool().invoke(buildJsonObject {
            put("url", JsonPrimitive("jdbc:duckdb:"))
        })["connectionId"]!!.jsonPrimitive.content
        DatabaseConnectionStore.close(connId)
        try {
            DatabaseConnectionStore.getConnection(connId)
            throw AssertionError("应报错：连接不存在")
        } catch (e: IllegalArgumentException) {
            // 等值比 contains 更严：顺带锁住 connId 回填与整条文案
            assertEquals(
                Messages.get("error.db.connection_not_found").replace("{0}", connId),
                e.message,
            )
        }
    }
}
