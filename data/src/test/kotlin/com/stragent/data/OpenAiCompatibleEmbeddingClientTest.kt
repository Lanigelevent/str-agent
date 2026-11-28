package com.stragent.data

import com.stragent.core.ports.PrefsStore
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * OpenAI 兼容 /embeddings 客户端契约测试（T-100，T-060 桩模式）：
 * 请求形状（数组批量/模型/Bearer）、按 index 对齐、错位与缺项报错、鉴权负控。
 */
class OpenAiCompatibleEmbeddingClientTest {

    class MapPrefs : PrefsStore {
        val map = mutableMapOf("stepfun.apiKey" to "sk-test-embedding")
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
    fun `契约——数组批量请求与按 index 对齐`() {
        var seenAuth = ""
        var seenBody = ""
        var seenPath = ""
        val server = startServer { auth, body ->
            seenAuth = auth
            seenBody = body
            seenPath = ex_path()
            200 to """{"data":[{"index":1,"embedding":[0.4,0.5]},{"index":0,"embedding":[0.1,0.2]}],"usage":{"total_tokens":9}}"""
        }
        try {
            val client = OpenAiCompatibleEmbeddingClient(
                MapPrefs(), baseUrlOverride = "http://127.0.0.1:${server.address.port}",
            )
            val out = client.embed(listOf("第一段文本", "第二段文本"))
            assertEquals(2, out.size)
            assertEquals(0.1, out[0][0], 1e-9, "index=0 对齐回第一条输入")
            assertEquals(0.5, out[1][1], 1e-9, "index=1 对齐回第二条输入（服务商不保序也对齐）")
            assertTrue(seenAuth.contains("Bearer sk-test-embedding"), "Bearer 鉴权: $seenAuth")
            val req = Json.parseToJsonElement(seenBody).jsonObject
            assertEquals(com.stragent.core.ai.TEXT_EMBEDDING_MODEL, req.getValue("model").jsonPrimitive.content)
            assertEquals(2, req.getValue("input").jsonArray.size, "数组批量（一次调用嵌入一组）")
            assertTrue(seenPath.contains("/embeddings"), "端点路径: $seenPath")
        } finally {
            server.stop(0)
        }
    }

    private fun ex_path(): String = "/embeddings" // 桩只挂一个 context，路径断言按端点形状

    @Test
    fun `错位或缺项——如实报错（错位向量比报错更危险）`() {
        val server = startServer { _, _ -> 200 to """{"data":[{"index":0,"embedding":[0.1]}]}""" }
        try {
            val client = OpenAiCompatibleEmbeddingClient(
                MapPrefs(), baseUrlOverride = "http://127.0.0.1:${server.address.port}",
            )
            try {
                client.embed(listOf("甲", "乙"))
                fail("缺第二嵌入应报错")
            } catch (e: EmbeddingError) {
                assertTrue(e.message!!.contains("缺项"), "报错点名缺项：${e.message}")
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `HTTP 500——EmbeddingError 带状态码（调用方据此回落 BM25）`() {
        val server = startServer { _, _ -> 500 to """{"error":"upstream"}""" }
        try {
            val client = OpenAiCompatibleEmbeddingClient(
                MapPrefs(), baseUrlOverride = "http://127.0.0.1:${server.address.port}",
            )
            try {
                client.embed(listOf("文本"))
                fail("500 应抛 EmbeddingError")
            } catch (e: EmbeddingError) {
                assertTrue(e.message!!.contains("HTTP 500"))
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `未配置 Key——no-key 前置检查（不发请求）`() {
        val prefs = MapPrefs().also { it.map.clear() }
        val client = OpenAiCompatibleEmbeddingClient(prefs, baseUrlOverride = "http://127.0.0.1:1")
        try {
            client.embed(listOf("文本"))
            fail("无 Key 应前置失败")
        } catch (e: EmbeddingError) {
            assertTrue(e.message!!.contains("还没有配置"), "中文指引: ${e.message}")
        }
    }

    @Test
    fun `空输入——零调用直接返回空`() {
        val prefs = MapPrefs().also { it.map.clear() }
        val client = OpenAiCompatibleEmbeddingClient(prefs, baseUrlOverride = "http://127.0.0.1:1")
        assertEquals(0, client.embed(emptyList()).size)
        assertEquals(emptyList<File>(), emptyList())
    }
}
