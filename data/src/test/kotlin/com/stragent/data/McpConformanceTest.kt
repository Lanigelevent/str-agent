package com.stragent.data

import com.stragent.core.ports.AgentTool
import com.stragent.core.ports.PrefsStore
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * T-056 / D17 G-15 · MCP 2026-07-28 档案的**本地一致性测试**。
 *
 * 覆盖本服务声明的 MUST 项：版本协商、无状态核心（不需要 initialize / 会话头）、
 * 旧版 12 个月兼容与弃用提示、错误码（-32601 / -32602）、`ping`。
 *
 * 边界（诚实）：这是按本项目声明的档案写的本地套件，不是官方一致性测试
 * （环境里没有官方套件可跑）。它证明确实按文档口径工作，不证明与未来官方
 * 测试逐条一致。
 */
class McpConformanceTest {

    private class MapPrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private class ProbeTool(override val name: String = "probe_tool") : AgentTool {
        override val description = "测试用：回显参数"
        override val parametersSchema = buildJsonObject { put("type", "object") }
        override fun invoke(args: JsonObject): JsonObject =
            buildJsonObject { put("echo", args["v"]?.jsonPrimitive?.content ?: "none") }
    }

    private class HttpResult(
        val status: Int,
        val text: String,
        val headers: Map<String, List<String>>,
    )

    private fun bridge(vararg tools: AgentTool): Pair<McpBridge, String> {
        val prefs = MapPrefs()
        val bridge = McpBridge(AbilityDatasets(), prefs, extraTools = tools.toList())
        return bridge to McpBridge.currentToken(prefs)
    }

    private fun post(
        port: Int,
        token: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpResult {
        val conn = URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $token")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val status = conn.responseCode
        val stream = if (status >= 400) conn.errorStream else conn.inputStream
        val text = stream?.readBytes()?.decodeToString() ?: ""
        val responseHeaders = conn.headerFields.filterKeys { it != null }
        conn.disconnect()
        return HttpResult(status, text, responseHeaders)
    }

    private fun rpc(method: String, params: String = "{}", id: Int = 1): String =
        """{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}"""

    @Test
    fun `无状态核心——不握手、不带会话头也能直接 tools list 与 tools call`() {
        val (bridge, _) = bridge(ProbeTool())
        try {
            val tools = bridge.dispatch("tools/list", JsonObject(emptyMap()))
            assertTrue(tools["tools"]!!.toString().contains("probe_tool"), "无 initialize 也应能列工具：$tools")
            val call = bridge.dispatch(
                "tools/call",
                buildJsonObject {
                    put("name", "probe_tool")
                    put("arguments", buildJsonObject { put("v", "stateless") })
                },
            )
            assertTrue(call["content"].toString().contains("stateless"), "无会话头也应能调用工具：$call")
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `版本协商——默认最新版，受支持版本原样回显，未知版本回最新并列出支持集`() {
        val (bridge, _) = bridge()
        try {
            val latest = bridge.dispatch("initialize", JsonObject(emptyMap()))
            assertEquals(McpProtocol.LATEST, latest["protocolVersion"]!!.jsonPrimitive.content)

            for (version in McpProtocol.SUPPORTED) {
                val result = bridge.dispatch(
                    "initialize",
                    buildJsonObject { put("protocolVersion", version) },
                )
                assertEquals(version, result["protocolVersion"]!!.jsonPrimitive.content, "受支持版本应原样回显")
            }

            val unknown = bridge.dispatch(
                "initialize",
                buildJsonObject { put("protocolVersion", "1999-01-01") },
            )
            assertEquals(McpProtocol.LATEST, unknown["protocolVersion"]!!.jsonPrimitive.content, "未知版本不得回显成已支持")
            assertEquals(
                McpProtocol.SUPPORTED.size,
                unknown["supportedVersions"]!!.toString().split("\",\"").size,
                "应列出完整支持集：$unknown",
            )
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `initialize 是弃用兼容壳——结果里写明移除时间与替代方式`() {
        val (bridge, _) = bridge()
        try {
            val result = bridge.dispatch("initialize", JsonObject(emptyMap()))
            val deprecated = result["_meta"]?.jsonObject?.get("deprecated")?.jsonObject
            assertNotNull(deprecated, "initialize 必须带弃用元数据：$result")
            assertEquals("true", deprecated["initialize"]!!.jsonPrimitive.content)
            assertEquals(McpProtocol.COMPAT_UNTIL, deprecated["removedAfter"]!!.jsonPrimitive.content)
            assertTrue(
                deprecated["alternative"]!!.jsonPrimitive.content.contains(McpProtocol.PROTOCOL_HEADER),
                "替代方式要指向无状态请求头：$deprecated",
            )
            assertNotNull(bridge.dispatch("ping", JsonObject(emptyMap())), "ping 应可用")
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `HTTP——协议版本头回显；未知版本 400 并列出支持集`() {
        val (bridge, token) = bridge(ProbeTool())
        val port = bridge.start(0)
        try {
            val ok = post(port, token, rpc("tools/list"), mapOf(McpProtocol.PROTOCOL_HEADER to "2025-06-18"))
            assertEquals(200, ok.status)
            assertEquals(
                "2025-06-18",
                ok.headers.entries.firstOrNull { it.key.equals(McpProtocol.PROTOCOL_HEADER, ignoreCase = true) }
                    ?.value?.firstOrNull(),
                "响应应回显协商版本：${ok.headers}",
            )

            val noHeader = post(port, token, rpc("tools/list"))
            assertEquals(200, noHeader.status, "缺少版本头按最新版处理")
            assertEquals(
                McpProtocol.LATEST,
                noHeader.headers.entries.firstOrNull { it.key.equals(McpProtocol.PROTOCOL_HEADER, ignoreCase = true) }
                    ?.value?.firstOrNull(),
            )

            val bad = post(port, token, rpc("tools/list"), mapOf(McpProtocol.PROTOCOL_HEADER to "1999-01-01"))
            assertEquals(400, bad.status, "未知版本必须拒绝：${bad.text}")
            // T-163：按 2026-07-28 回 JSON-RPC UnsupportedProtocolVersionError（-32022），
            // supported/requested 在 error.data——客户端据此探测支持集或判时代
            val body = Json.parseToJsonElement(bad.text).jsonObject
            assertEquals(-32022L, body["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toLong())
            val data = body["error"]!!.jsonObject["data"]!!.jsonObject
            assertTrue(data["supported"]!!.toString().contains(McpProtocol.LATEST), "应列出支持集：$data")
            assertEquals("1999-01-01", data["requested"]!!.jsonPrimitive.content)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `HTTP——旧版客户端用会话头时回 Warning 299，且调用仍成功`() {
        val (bridge, token) = bridge(ProbeTool())
        val port = bridge.start(0)
        try {
            val result = post(
                port, token, rpc("tools/call", """{"name":"probe_tool","arguments":{"v":"legacy"}}"""),
                mapOf(
                    McpProtocol.PROTOCOL_HEADER to "2025-03-26",
                    McpProtocol.SESSION_HEADER to "legacy-session",
                ),
            )
            assertEquals(200, result.status, result.text)
            assertTrue(result.text.contains("legacy"), "旧客户端调用仍可用：${result.text}")
            val warning = result.headers.entries.firstOrNull { it.key.equals("Warning", ignoreCase = true) }
                ?.value?.joinToString(" ")
            assertNotNull(warning, "使用弃用头应带 Warning：${result.headers}")
            assertTrue(warning.contains("deprecated"), "Warning 要明说弃用：$warning")
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `错误码——未知方法 404 加 -32601、未知工具 -32602，都不是内部错误`() {
        val (bridge, token) = bridge(ProbeTool())
        val port = bridge.start(0)
        try {
            // T-163：2026-07-28 对未知方法要求 404 + JSON-RPC -32601 体（区别于旧版 HTTP+SSE 的裸 404）
            val unknownMethod = post(port, token, rpc("does/not/exist"))
            assertEquals(404, unknownMethod.status)
            assertEquals(
                -32601L,
                Json.parseToJsonElement(unknownMethod.text).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toLong(),
            )

            val unknownTool = post(port, token, rpc("tools/call", """{"name":"no_such_tool","arguments":{}}"""))
            assertEquals(200, unknownTool.status)
            assertEquals(
                -32602L,
                Json.parseToJsonElement(unknownTool.text).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toLong(),
            )
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `会话头降为可选路由——缺省落 default，具名会话的数据集指针互不串`() {
        val datasets = AbilityDatasets()
        val registry = SessionRegistry(datasets)
        val prefs = MapPrefs()
        val bridge = McpBridge(datasets, prefs, extraTools = listOf(ProbeTool()), registry = registry)
        try {
            registry.setCurrent("s-a", "ds_a")
            registry.setCurrent("s-b", "ds_b")
            val a = McpSessionContext.with("s-a") { registry.currentId(McpSessionContext.current()) }
            val b = McpSessionContext.with("s-b") { registry.currentId(McpSessionContext.current()) }
            assertEquals("ds_a", a)
            assertEquals("ds_b", b)
            assertEquals(
                SessionRegistry.DEFAULT_SESSION,
                McpSessionContext.current(),
                "离开作用域回到默认会话（无状态核心下不改协议语义）",
            )
            // 不带会话头（裸 dispatch）照常可用，默认桶不因具名会话而污染
            assertNotNull(bridge.dispatch("tools/list", JsonObject(emptyMap())))
        } finally {
            bridge.stop()
        }
    }

    // ---- T-163 · 2026-07-28 合规缺口清偿 ----

    private fun toolCallArgs(name: String): String = """{"name":"$name","arguments":{"v":"x"}}"""

    @Test
    fun `server discover——预检 RPC 回支持集、能力、身份与缓存提示`() {
        val (bridge, _) = bridge(ProbeTool())
        try {
            val d = bridge.dispatch("server/discover", JsonObject(emptyMap()))
            assertEquals("complete", d["resultType"]!!.jsonPrimitive.content, "结果判别字段：$d")
            assertTrue(d["supportedVersions"]!!.toString().contains(McpProtocol.LATEST), "支持集：$d")
            assertNotNull(d["capabilities"]!!.jsonObject["tools"], "能力声明：$d")
            val serverInfo = d["_meta"]!!.jsonObject["io.modelcontextprotocol/serverInfo"]!!.jsonObject
            assertEquals("Str Agent", serverInfo["name"]!!.jsonPrimitive.content)
            assertNotNull(serverInfo["version"], "身份带版本：$serverInfo")
            assertEquals(3_600_000L, d["ttlMs"]!!.jsonPrimitive.long, "缓存提示（MUST）：$d")
            assertEquals("public", d["cacheScope"]!!.jsonPrimitive.content, "身份/能力对所有调用方一致：$d")
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `tools list——结果判别与缓存提示（private 加短 TTL，目录随授权可变）`() {
        val (bridge, _) = bridge(ProbeTool())
        try {
            val t = bridge.dispatch("tools/list", JsonObject(emptyMap()))
            assertEquals("complete", t["resultType"]!!.jsonPrimitive.content)
            assertEquals(60_000L, t["ttlMs"]!!.jsonPrimitive.long)
            assertEquals("private", t["cacheScope"]!!.jsonPrimitive.content)
            assertTrue(t["tools"].toString().contains("probe_tool"))
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `路由头——现代请求缺 Mcp-Method 回 400 加 -32020，遗留客户端不受约束`() {
        val (bridge, token) = bridge(ProbeTool())
        val port = bridge.start(0)
        try {
            // 声明 2026-07-28 但不带 Mcp-Method：必需路由头缺失 → 400 + HeaderMismatch
            val missing = post(port, token, rpc("tools/list"), mapOf(McpProtocol.PROTOCOL_HEADER to McpProtocol.LATEST))
            assertEquals(400, missing.status, "必需路由头缺失必须拒绝：${missing.text}")
            assertEquals(
                -32020L,
                Json.parseToJsonElement(missing.text).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toLong(),
            )

            // 补齐路由头后同一请求通过
            val ok = post(
                port, token, rpc("tools/list"),
                mapOf(McpProtocol.PROTOCOL_HEADER to McpProtocol.LATEST, McpProtocol.METHOD_HEADER to "tools/list"),
            )
            assertEquals(200, ok.status, ok.text)

            // 遗留客户端（缺版本头 = 按 2025-03-26 处理；或声明旧版本）不受路由头约束
            val legacyNoHeader = post(port, token, rpc("tools/list"))
            assertEquals(200, legacyNoHeader.status, legacyNoHeader.text)
            val legacyOld = post(port, token, rpc("tools/list"), mapOf(McpProtocol.PROTOCOL_HEADER to "2025-06-18"))
            assertEquals(200, legacyOld.status, legacyOld.text)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `路由头——头体不一致回 -32020，哨兵编码解码后比对`() {
        val (bridge, token) = bridge(ProbeTool())
        val port = bridge.start(0)
        val headers = mapOf(
            McpProtocol.PROTOCOL_HEADER to McpProtocol.LATEST,
            McpProtocol.METHOD_HEADER to "tools/call",
        )
        try {
            // Mcp-Name 与 body.name 不一致 → -32020
            val mismatch = post(
                port, token, rpc("tools/call", toolCallArgs("probe_tool")),
                headers + (McpProtocol.NAME_HEADER to "other_tool"),
            )
            assertEquals(400, mismatch.status, mismatch.text)
            assertEquals(
                -32020L,
                Json.parseToJsonElement(mismatch.text).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toLong(),
            )

            // tools/call 缺 Mcp-Name → -32020（必需头缺失）
            val missingName = post(port, token, rpc("tools/call", toolCallArgs("probe_tool")), headers)
            assertEquals(400, missingName.status, missingName.text)

            // 哨兵编码的 Mcp-Name：服务端解码后与 body.name 相等 → 通过
            val encoded = java.util.Base64.getEncoder().encodeToString("probe_tool".toByteArray(Charsets.UTF_8))
            val sentinel = post(
                port, token, rpc("tools/call", toolCallArgs("probe_tool")),
                headers + (McpProtocol.NAME_HEADER to "=?base64?$encoded?="),
            )
            assertEquals(200, sentinel.status, "解码后应一致：${sentinel.text}")

            // 非法 Base64 → -32020（头值非法）
            val badBase64 = post(
                port, token, rpc("tools/call", toolCallArgs("probe_tool")),
                headers + (McpProtocol.NAME_HEADER to "=?base64?!!!?="),
            )
            assertEquals(400, badBase64.status, badBase64.text)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `坏 JSON——回 400 加 -32700 ParseError`() {
        val (bridge, token) = bridge(ProbeTool())
        val port = bridge.start(0)
        try {
            val bad = post(port, token, """{"jsonrpc":"2.0","id":1,"method": oops""")
            assertEquals(400, bad.status, "协议错误应回 400：${bad.text}")
            assertEquals(
                -32700L,
                Json.parseToJsonElement(bad.text).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toLong(),
            )
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `哨兵解码——纯函数口径（含哨兵冲突值）`() {
        // 普通值原样返回
        assertEquals("get_weather", McpProtocol.decodeHeaderValue("get_weather"))
        // 哨兵形态解码（UTF-8）
        val encoded = java.util.Base64.getEncoder().encodeToString("Hello, 世界".toByteArray(Charsets.UTF_8))
        assertEquals("Hello, 世界", McpProtocol.decodeHeaderValue("=?base64?$encoded?="))
        // 哨兵冲突值（正文值长得像哨兵）：客户端必须编码传输，服务端解码还原
        val literal = "=?base64?literal?="
        assertEquals(literal, McpProtocol.decodeHeaderValue("=?base64?PT9iYXNlNjQ/bGl0ZXJhbD89?="))
    }

    @Test
    fun `路由头纯函数——缺 Mcp-Method 与方法不一致均抛 -32020 对应异常`() {
        val params = Json.parseToJsonElement("""{"name":"probe_tool","arguments":{}}""").jsonObject
        // 缺 Mcp-Method → McpHeaderMismatchException
        assertFailsWith<McpHeaderMismatchException> {
            McpProtocol.validateRoutingHeaders(
                { h -> if (h == McpProtocol.PROTOCOL_HEADER) McpProtocol.LATEST else null },
                "tools/call",
                params,
            )
        }
        // Mcp-Method 与 body method 不一致 → McpHeaderMismatchException
        assertFailsWith<McpHeaderMismatchException> {
            McpProtocol.validateRoutingHeaders(
                { h ->
                    when (h) {
                        McpProtocol.PROTOCOL_HEADER -> McpProtocol.LATEST
                        McpProtocol.METHOD_HEADER -> "tools/list"
                        else -> null
                    }
                },
                "tools/call",
                params,
            )
        }
        // _meta 声明的版本与头不一致 → McpHeaderMismatchException
        val metaParams = Json.parseToJsonElement(
            """{"_meta":{"io.modelcontextprotocol/protocolVersion":"2025-06-18"},"name":"probe_tool"}""",
        ).jsonObject
        assertFailsWith<McpHeaderMismatchException> {
            McpProtocol.validateRoutingHeaders(
                { h -> if (h == McpProtocol.PROTOCOL_HEADER) McpProtocol.LATEST else null },
                "tools/call",
                metaParams,
            )
        }
        // 头缺失（遗留客户端）→ 不校验，直接通过
        McpProtocol.validateRoutingHeaders({ null }, "tools/call", params)
    }
}
