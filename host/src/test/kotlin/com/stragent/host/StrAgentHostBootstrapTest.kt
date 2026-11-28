package com.stragent.host

import com.stragent.data.McpBridge
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 纯 JVM 宿主的装配与对外形态测试。
 *
 * 这是「去掉 App 之后插件还能不能独立活起来」的守卫：
 * 装配要成、目录要齐、密钥不落 prefs、MCP 桥要真的把能力面端出去。
 */
class StrAgentHostBootstrapTest {

    private fun tempHome(): File = createTempDirectory("str-agent-host-test").toFile()

    @Test
    fun `宿主装配后插件已装载且能力面可枚举`() {
        val bootstrap = StrAgentHostBootstrap.create(tempHome())
        try {
            val ids = bootstrap.plugin.capabilities().map { it.id }
            assertTrue(ids.isNotEmpty(), "插件能力面不能为空")
            assertTrue(ids.contains("report"), "应贡献 report 能力")
            assertTrue(ids.contains("followup"), "应贡献 followup 能力")
            assertTrue(ids.contains("mcp-bridge"), "应贡献 mcp-bridge 服务")
        } finally {
            bootstrap.close()
        }
    }

    @Test
    fun `数据目录结构就位`() {
        val home = tempHome()
        val bootstrap = StrAgentHostBootstrap.create(home)
        try {
            assertTrue(File(home, "prefs.properties").exists(), "偏好文件应落盘")
            assertTrue(File(home, "kv").isDirectory, "键值目录应存在")
            assertTrue(File(home, "plugins").isDirectory, "插件目录应存在")
        } finally {
            bootstrap.close()
        }
    }

    @Test
    fun `密钥走独立文件，不落进 prefs`() {
        val home = tempHome()
        val store = JvmKeyStore(File(home, "api-key"))
        val marker = store.encrypt("sk-test-123")
        // D15 T3：加密后返回 enc: 前缀（AES-256-GCM），不再返回 file- 前缀
        assertTrue(marker.startsWith(JvmKeyStore.ENC_PREFIX), "应写入加密标记 enc: 而不是明文")
        assertEquals("sk-test-123", store.decrypt(marker))
        assertTrue(File(home, "api-key").exists(), "密钥文件应存在")

        val bootstrap = StrAgentHostBootstrap.create(home)
        try {
            bootstrap.stepfun.setApiKey("sk-test-123")
            val prefsText = File(home, "prefs.properties").readText(Charsets.UTF_8)
            assertFalse(prefsText.contains("sk-test-123"), "明文密钥不得出现在 prefs 里")
            // prefs 里应包含加密标记（enc: 或 file- 兼容旧格式）。
            // 注意 Properties.store 会把值里的 ':' 转义成 '\:'，必须按 properties 语义读回再断言。
            val stored = java.util.Properties().apply {
                java.io.FileInputStream(File(home, "prefs.properties")).use { load(it) }
            }.getProperty(com.stragent.data.StepfunConfig.KEY_STORAGE).orEmpty()
            assertTrue(
                stored.startsWith(JvmKeyStore.ENC_PREFIX) || stored.startsWith(JvmKeyStore.FILE_PREFIX),
                "prefs 里应留加密/文件标记，实际存储值：$stored",
            )
        } finally {
            bootstrap.close()
        }
    }

    @Test
    fun `MCP 桥对外暴露四条数据契约与插件的报告、追问能力（真 HTTP）`() {
        val bootstrap = StrAgentHostBootstrap.create(tempHome())
        val token = McpBridge.currentToken(bootstrap.prefs)
        val port = bootstrap.mcpBridge.start(0)
        try {
            val names = rpc(port, token, "tools/list")
                .getValue("result").jsonObject.getValue("tools").jsonArray
                .map { it.jsonObject.getValue("name").jsonPrimitive.content }
            assertTrue(names.contains("stragent_import_table"), "应含数据契约 import_table")
            assertTrue(names.contains("stragent_get_column_profile"), "应含数据契约 column_profile")
            assertTrue(names.contains("stragent_get_group_aggregate"), "应含数据契约 group_aggregate")
            assertTrue(names.contains("stragent_list_rows"), "应含数据契约 list_rows")
            assertTrue(names.contains("stragent_generate_report"), "应含插件能力 generate_report")
            assertTrue(names.contains("stragent_ask_followup"), "应含插件能力 ask_followup")
        } finally {
            bootstrap.close()
        }
    }

    /** 与 data/McpBridgeTest 同一路零依赖真 HTTP 客户端 */
    private fun rpc(port: Int, token: String, method: String): kotlinx.serialization.json.JsonObject {
        val conn = URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.doOutput = true
        conn.outputStream.use {
            it.write("""{"jsonrpc":"2.0","id":1,"method":"$method"}""".toByteArray(Charsets.UTF_8))
        }
        assertEquals(200, conn.responseCode)
        val text = conn.inputStream.readBytes().decodeToString()
        conn.disconnect()
        return Json.parseToJsonElement(text).jsonObject
    }
}
