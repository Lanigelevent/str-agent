package com.stragent.data

import com.stragent.core.ports.AgentTool
import com.stragent.core.csv.ColumnType
import com.stragent.core.dataset.Dataset
import com.stragent.core.trace.ProvenanceManifest
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T-046 凭据与审计卫生（D17 G-05）验收测试。
 *
 * 四组证据：
 *  ①token 熵与形式——16 字节 CSPRNG → 32 hex，10 万次无重复；
 *  ②常量时间比较的**行为**（时间侧信道本身无法在单测里稳定断言，采用定长摘要 +
 *    `MessageDigest.isEqual` 的实现，行为测试覆盖正确/错误/前缀/长度/空/null）；
 *  ③溯源脱敏——密码与 URL userinfo 不落明文，SQL 只留结构 + 原文哈希；
 *  ④prefs 权限——POSIX 下 600；Windows 无 POSIX 视图时明确打印「宿主责任」。
 */
class CredentialHygieneTest {

    private class MapPrefs : com.stragent.core.ports.PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) {
            map[key] = value
        }

        override fun remove(key: String) {
            map.remove(key)
        }
    }

    @Test
    fun `token 生成——10 万次无重复且为 32 位 hex`() {
        val seen = HashSet<String>(200_000)
        repeat(100_000) {
            val token = McpBridge.generateToken()
            assertEquals(32, token.length, "128-bit = 32 hex")
            assertTrue(token.all { c -> c in "0123456789abcdef" }, "必须是 hex：$token")
            assertTrue(seen.add(token), "出现重复 token：$token")
        }
        assertEquals(100_000, seen.size)
    }

    @Test
    fun `token 比较——正确通过，错误-前缀-长度-空-null 一律拒绝`() {
        val token = McpBridge.generateToken()
        assertTrue(McpBridge.tokenMatches(token, token))
        assertFalse(McpBridge.tokenMatches(token, token.dropLast(1)), "前缀不得通过")
        assertFalse(McpBridge.tokenMatches(token, token + "0"), "超长不得通过")
        assertFalse(McpBridge.tokenMatches(token, token.uppercase()), "大小写不同不得通过")
        assertFalse(McpBridge.tokenMatches(token, ""))
        assertFalse(McpBridge.tokenMatches(token, null))
        assertFalse(McpBridge.tokenMatches(token, "Bearer $token"))
    }

    @Test
    fun `token 指纹——8 位 hex、稳定、与原文不同且低频碰撞`() {
        val token = McpBridge.generateToken()
        val fingerprint = McpBridge.tokenFingerprint(token)
        assertEquals(8, fingerprint.length)
        assertTrue(fingerprint.all { c -> c in "0123456789abcdef" })
        assertEquals(fingerprint, McpBridge.tokenFingerprint(token), "同 token 指纹稳定")
        assertNotEquals(token, fingerprint, "指纹不能是原文")
        // 32-bit 指纹有生日碰撞；1000 个样本的期望碰撞数 ~0.0001，取 990 作宽松下界
        // （既能抓住「指纹恒定/退化」的坏实现，又不会偶发假红）
        val fingerprints = (1..1_000).map { McpBridge.tokenFingerprint(McpBridge.generateToken()) }.toSet()
        assertTrue(fingerprints.size >= 990, "1000 个 32-bit 指纹出现异常碰撞：${fingerprints.size}")
    }

    @Test
    fun `溯源参数脱敏——密码-密钥-URL 凭据与嵌套对象`() {
        val safe = ProvenanceSanitizer.sanitizeArgs(
            buildJsonObject {
                put("url", "jdbc:postgresql://alice:s3cret@db.local:5432/shop?password=abc123&user=alice")
                put("username", "alice")
                put("password", "p@ss")
                put("API_KEY", "sk-live-123")
                put("sql", "SELECT * FROM users WHERE name = '张三' AND age > 18")
                put(
                    "options",
                    buildJsonObject {
                        put("accessToken", "tok-1")
                        put("limit", 10)
                    },
                )
            },
        )
        // 是「规范化键名」匹配：API_KEY 也要被识别
        assertEquals("***", safe["password"]!!.jsonPrimitive.content)
        assertEquals("***", safe["API_KEY"]!!.jsonPrimitive.content)
        assertEquals("alice", safe["username"]!!.jsonPrimitive.content, "用户名保留（审计要回答哪个账号）")
        val url = safe["url"]!!.jsonPrimitive.content
        assertFalse(url.contains("s3cret"), "URL userinfo 口令泄漏：$url")
        assertFalse(url.contains("abc123"), "URL 查询参数口令泄漏：$url")
        assertTrue(url.contains("db.local"), "主机应保留：$url")
        assertTrue(url.contains("alice:***@"), "账号保留、口令替换：$url")
        assertFalse(safe.toString().contains("张三"), "SQL 字面量泄漏：$safe")
        val options = safe["options"]!!.jsonObject
        assertEquals("***", options["accessToken"]!!.jsonPrimitive.content)
        assertEquals("10", options["limit"]!!.jsonPrimitive.content, "非敏感数字不受影响")
    }

    @Test
    fun `SQL 脱敏——字面量与注释替换，标识符保留`() {
        val sql =
            "SELECT col1, amount FROM t2 WHERE name = 'O''Brien' AND amount > 1.5e3 " +
                "-- 客户名：张三\n/* secret hint */ AND id = 0x1F"
        val masked = ProvenanceSanitizer.maskSql(sql)
        assertFalse(masked.contains("O'Brien"), "字符串字面量必须替换：$masked")
        assertFalse(masked.contains("张三"), "注释内容必须去掉：$masked")
        assertFalse(masked.contains("secret hint"), "块注释必须去掉：$masked")
        assertFalse(masked.contains("1.5e3"), "数字字面量必须替换：$masked")
        assertFalse(masked.contains("0x1F"), "十六进制字面量必须替换：$masked")
        assertTrue(masked.contains("col1"), "标识符保留：$masked")
        assertTrue(masked.contains("t2"), "表名保留：$masked")
        assertTrue(masked.contains("name = ?"), "结构保留：$masked")
        assertTrue(masked.contains("amount > ?"), "结构保留：$masked")
        assertTrue(masked.contains("SELECT") && masked.contains("FROM"), "关键字保留：$masked")
    }

    @Test
    fun `工具调用落账——manifest 无明文密码与 SQL，会话与哈希单列`() {
        val datasets = AbilityDatasets()
        datasets.put(
            "ds_audit",
            Dataset(
                name = "audit",
                importedAt = 1L,
                columns = listOf("a"),
                rows = listOf(listOf("1")),
                columnTypes = listOf(ColumnType.NUMBER),
            ),
        )
        val recorder = ProvenanceRecorder(datasets)
        val fakeConnect = fakeTool("stragent_db_connect") { buildJsonObject { put("connectionId", "conn-fake") } }
        val fakeQuery = fakeTool("stragent_db_query") { buildJsonObject { put("rowCount", 1) } }
        val bridge = McpBridge(
            datasets,
            MapPrefs(),
            extraTools = listOf(fakeConnect, fakeQuery),
            provenance = recorder,
        )
        val secretSql = "SELECT * FROM orders WHERE customer = '张三' AND amount > 99"
        bridge.dispatch(
            "tools/call",
            buildJsonObject {
                put("name", "stragent_db_connect")
                put(
                    "arguments",
                    buildJsonObject {
                        put("datasetId", "ds_audit")
                        put("url", "jdbc:postgresql://alice:dbpass@db.local/shop")
                        put("username", "alice")
                        put("password", "dbpass")
                    },
                )
            },
        )
        bridge.dispatch(
            "tools/call",
            buildJsonObject {
                put("name", "stragent_db_query")
                put(
                    "arguments",
                    buildJsonObject {
                        put("datasetId", "ds_audit")
                        put("connectionId", "conn-fake")
                        put("sql", secretSql)
                    },
                )
            },
        )

        val steps = recorder.stepsOf("ds_audit")
        assertEquals(2, steps.size)
        val connect = steps[0]
        assertFalse(connect.args.toString().contains("dbpass"), "密码落进溯源：${connect.args}")
        assertEquals("***", connect.args["password"]!!.jsonPrimitive.content)
        assertNotNull(connect.sessionId, "步骤应带会话 id")
        val query = steps[1]
        assertFalse(query.args.toString().contains("张三"), "SQL 字面量落进溯源：${query.args}")
        assertEquals(ProvenanceManifest.hashOf(secretSql), query.sqlHash, "原文哈希必须可复算")
        assertEquals(ProvenanceSanitizer.maskSql(secretSql), query.sqlMasked)
        assertNotNull(query.sessionId)
        // 清单 JSON 整串也不得含秘密
        val json = recorder.manifest("ds_audit")!!.toJson().toString()
        assertFalse(json.contains("dbpass"))
        assertFalse(json.contains("张三"))
        assertTrue(json.contains("sqlHash"))
    }

    @Test
    fun `旧 16 hex token 一次性轮换为 128-bit，之后保持稳定`() {
        val prefs = MapPrefs()
        prefs.set("mcp.token", "0123456789abcdef") // T-045 及更早的形制（64 bit）
        val upgraded = McpBridge.currentToken(prefs)
        assertEquals(32, upgraded.length, "升级应换成 128-bit")
        assertNotEquals("0123456789abcdef", upgraded, "旧弱 token 不得继续有效")
        assertEquals(upgraded, McpBridge.currentToken(prefs), "迁移后保持稳定（不再反复轮换）")
    }

    @Test
    fun `prefs 文件权限——POSIX 下收紧为 600`() {
        val dir = Files.createTempDirectory("stragent-prefs").toFile()
        try {
            FilePrefs(dir).set("mcp.token", McpBridge.generateToken())
            val path = dir.resolve("prefs.properties").toPath()
            assertTrue(Files.exists(path), "prefs 文件应已落盘")
            val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
            if (view == null) {
                // Windows 没有 POSIX 权限视图，Java 也无 ACL API：ACL 由宿主负责（文档已标注）
                println("无 POSIX 视图（Windows？）：prefs ACL 由宿主负责（T-046 边界）")
            } else {
                val perms = PosixFilePermissions.toString(view.readAttributes().permissions())
                assertEquals("rw-------", perms, "prefs 应仅属主可读写")
            }
            // 重启（重新加载）后仍收紧：老文件升级路径
            FilePrefs(dir).set("other", "v")
            if (view != null) {
                val perms = PosixFilePermissions.toString(view.readAttributes().permissions())
                assertEquals("rw-------", perms, "重写后权限不应放宽")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun fakeTool(name: String, result: () -> JsonObject): AgentTool = object : AgentTool {
        override val name = name
        override val description = "test fake"
        override val parametersSchema = buildJsonObject { put("type", "object") }
        override val cacheable = false
        override fun invoke(args: JsonObject): JsonObject = result()
    }
}
