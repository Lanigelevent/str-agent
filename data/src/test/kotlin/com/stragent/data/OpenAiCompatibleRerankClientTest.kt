package com.stragent.data

import com.stragent.core.ports.PrefsStore
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * /rerank 客户端契约测试（T-101，本地桩）：请求形状、按分数降序回下标、覆盖不齐报错、
 * HTTP 失败、no-key 前置。
 */
class OpenAiCompatibleRerankClientTest {

    class MapPrefs : PrefsStore {
        val map = mutableMapOf("stepfun.apiKey" to "sk-test-rerank")
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private fun startServer(handler: (String, String) -> Pair<Int, String>): HttpServer {
        val server = HttpServer.create(java.net.InetSocketAddress(0), 0)
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().decodeToString()
            val (code, resp) = handler(ex.requestHeaders.getFirst("Authorization") ?: "", body)
            ex.sendResponseHeaders(code, resp.toByteArray(Charsets.UTF_8).size.toLong())
            ex.responseBody.use { it.write(resp.toByteArray(Charsets.UTF_8)) }
        }
        server.start()
        return server
    }

    @Test
    fun `契约——请求形状与按 relevance_score 降序回下标（不保序实现也对）`() {
        var seenBody = ""
        val server = startServer { auth, body ->
            seenBody = body
            assertTrue(auth.contains("Bearer sk-test-rerank"), "Bearer: $auth")
            // 故意乱序返回（服务端不保序）——客户端必须按分数重排
            200 to """{"results":[{"index":2,"relevance_score":0.55},{"index":0,"relevance_score":0.91},{"index":1,"relevance_score":0.72}]}"""
        }
        try {
            val client = OpenAiCompatibleRerankClient(MapPrefs(), baseUrlOverride = "http://127.0.0.1:${server.address.port}")
            val out = client.rerank("成本风险", listOf("仓储成本上升", "收入增长", "成本控制"))
            assertEquals(listOf(0, 1, 2), out.map { it.index }, "按分数 0.91→0.72→0.55 回下标")
            assertEquals(0.91, out[0].score, 1e-9, "分数随下标带出（corrective 质量闸用）")
            val req = Json.parseToJsonElement(seenBody).jsonObject
            assertEquals(com.stragent.core.ai.TEXT_RERANK_MODEL, req.getValue("model").jsonPrimitive.content)
            assertEquals("成本风险", req.getValue("query").jsonPrimitive.content)
            assertEquals(3, req.getValue("documents").jsonArray.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `覆盖不齐——如实报错（缺一个候选的重排结果不可信）`() {
        val server = startServer { _, _ -> 200 to """{"results":[{"index":0,"relevance_score":0.9}]}""" }
        try {
            val client = OpenAiCompatibleRerankClient(MapPrefs(), baseUrlOverride = "http://127.0.0.1:${server.address.port}")
            try {
                client.rerank("q", listOf("甲", "乙"))
                fail("覆盖不齐应报错")
            } catch (e: RerankError) {
                assertTrue(e.message!!.contains("覆盖不齐"))
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `HTTP 500——RerankError 带状态码（调用方回落融合序）`() {
        val server = startServer { _, _ -> 500 to """{"error":"x"}""" }
        try {
            val client = OpenAiCompatibleRerankClient(MapPrefs(), baseUrlOverride = "http://127.0.0.1:${server.address.port}")
            try {
                client.rerank("q", listOf("甲"))
                fail("500 应抛 RerankError")
            } catch (e: RerankError) {
                assertTrue(e.message!!.contains("HTTP 500"))
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `no-key 前置检查与空候选`() {
        val prefs = MapPrefs().also { it.map.clear() }
        val client = OpenAiCompatibleRerankClient(prefs, baseUrlOverride = "http://127.0.0.1:1")
        try {
            client.rerank("q", listOf("甲"))
            fail("无 Key 应前置失败")
        } catch (e: RerankError) {
            assertTrue(e.message!!.contains("还没有配置"))
        }
        assertEquals(emptyList(), client.rerank("q", emptyList()), "空候选零调用")
    }
}
