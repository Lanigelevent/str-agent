package com.stragent.data

import com.stragent.core.ports.PrefsStore
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * MCP 标准桥集成测试：起真 HTTP 服务（localhost:0 随机端口），走 JSON-RPC
 * initialize → tools/list → tools/call 全链验证。HTTP 调用用 HttpURLConnection
 * ——与服务端同一条零依赖路线，测试即真客户端。
 */
class McpBridgeTest {
    private val datasets = AbilityDatasets()

    private class MapPrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private fun rpc(port: Int, token: String, method: String, params: String?, expectStatus: Int = 200): JsonObject {
        val conn = URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.doOutput = true
        val body = buildString {
            append("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"$method\"")
            if (params != null) append(",\"params\":$params")
            append("}")
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        assertEquals(expectStatus, conn.responseCode)
        val text = conn.inputStream.readBytes().decodeToString()
        conn.disconnect()
        return Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun `MCP 全链——initialize、tools_list、tools_call（真 HTTP）`() {
        val prefs = MapPrefs()
        val bridge = McpBridge(datasets, prefs)
        val token = McpBridge.currentToken(prefs)

        // start(0)=系统分配临时端口（测试隔离：单测跑完即 stop；固定 7787 曾在 CI 被 TIME_WAIT 残留占用）
        val port = bridge.start(0)
        try {
            // ① initialize
            val init = rpc(port, token, "initialize", """{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}""")
            val serverInfo = (init["result"] as JsonObject)["serverInfo"] as JsonObject
            assertEquals("Str Agent", (serverInfo["name"] as kotlinx.serialization.json.JsonPrimitive).content)
            // 版本回执必须来自 Gradle 注入（子项目继承根 version）；回归防线：
            // 曾因 :data 不继承根版本而输出 "unspecified"（容器冒烟发现）。
            val version = (serverInfo["version"] as kotlinx.serialization.json.JsonPrimitive).content
            assertEquals(BuildInfo.version, version)
            assertNotEquals("unspecified", version, "serverInfo.version 不能是 Gradle 默认值")

            // ② tools/list：四条能力契约在目录里
            val list = rpc(port, token, "tools/list", null)
            val tools = ((list["result"] as JsonObject)["tools"]) as JsonArray
            val names = tools.map { (((it as JsonObject)["name"]) as kotlinx.serialization.json.JsonPrimitive).content }
            assertEquals(
                listOf(ABILITY_IMPORT_TABLE, ABILITY_COLUMN_PROFILE, ABILITY_GROUP_AGGREGATE, ABILITY_LIST_ROWS),
                names,
            )

            // ③ tools/call：导入 + 分组聚合
            val csv = "门店,销售额\n华东,1200.5\n华南,800\n华东,300"
            val call1 = rpc(
                port, token, "tools/call",
                """{"name":"$ABILITY_IMPORT_TABLE","arguments":{"csv":"$csv","name":"门店销售"}}""",
            )
            val resultText = (((call1["result"] as JsonObject)["content"]) as JsonArray)
                .first().let { (it as JsonObject)["text"] as kotlinx.serialization.json.JsonPrimitive }.content
            assertTrue(resultText.contains("ds_"), "应含 datasetId: $resultText")

            val datasetId = Json.parseToJsonElement(
                Json.parseToJsonElement(resultText).jsonObject["datasetId"]!!.toString(),
            ).let { (it as kotlinx.serialization.json.JsonPrimitive).content }
            val call2 = rpc(
                port, token, "tools/call",
                """{"name":"stragent_get_group_aggregate","arguments":{"datasetId":"$datasetId","dimension":"门店","metric":"销售额"}}""",
            )
            val result2 = (((call2["result"] as JsonObject)["content"]) as JsonArray)
                .first().let { (it as JsonObject)["text"] as kotlinx.serialization.json.JsonPrimitive }.content
            assertTrue(result2.contains("华东") && result2.contains("1500.5"), "应有华东合计 1500.5: $result2")
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `鉴权——无 token 或错 token 一律 401`() {
        val prefs = MapPrefs()
        val bridge = McpBridge(datasets, prefs)
        McpBridge.currentToken(prefs) // 生成 token
        val port = bridge.start(0)
        try {
            val conn = URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer wrong-token")
            conn.doOutput = true
            conn.outputStream.use { it.write("{}".toByteArray()) }
            assertEquals(401, conn.responseCode)
            conn.disconnect()

            // 无鉴权头
            val conn2 = URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection
            conn2.requestMethod = "POST"
            conn2.doOutput = true
            conn2.outputStream.use { it.write("{}".toByteArray()) }
            assertEquals(401, conn2.responseCode)
            conn2.disconnect()
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `token 生成与刷新——32 位 hex（128-bit），刷新后变`() {
        val prefs = MapPrefs()
        val first = McpBridge.currentToken(prefs)
        assertEquals(32, first.length)
        assertTrue(first.all { it in "0123456789abcdef" }, "必须是 hex：$first")
        assertEquals(first, McpBridge.currentToken(prefs), "同 prefs 稳定")
        val second = McpBridge.refreshToken(prefs)
        assertTrue(second != first, "刷新后应变化")
        assertEquals(32, second.length)
    }

    @Test
    fun `监听地址可配置——0_0_0_0 仍可经回环访问（容器端口映射前提）`() {
        val prefs = MapPrefs()
        // 容器场景：绑 0.0.0.0 才能被端口映射访问；鉴权仍强制（这里只验证可达性）
        val bridge = McpBridge(datasets, prefs, bindAddress = "0.0.0.0")
        val token = McpBridge.currentToken(prefs)
        val port = bridge.start(port = 0)
        try {
            val init = rpc(
                port, token, "initialize",
                """{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}""",
            )
            val serverInfo = (init["result"] as JsonObject)["serverInfo"] as JsonObject
            assertEquals("Str Agent", (serverInfo["name"] as kotlinx.serialization.json.JsonPrimitive).content)
        } finally {
            bridge.stop()
        }
    }
}
