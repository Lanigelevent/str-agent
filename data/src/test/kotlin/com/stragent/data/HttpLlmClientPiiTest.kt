package com.stragent.data

import com.stragent.core.ports.PrefsStore
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * T-195 · PII Shield 咽喉点集成——真 HTTP 桩。
 *
 * 开启时：出站载荷含占位符且不含原文（端点视野脱敏）；入站按同一份映射还原（调用方看到原文）。
 * 关闭时：载荷原样（零行为差异负控）。流式通道终稿还原（增量帧可含跨帧片段——诚实边界）。
 */
class HttpLlmClientPiiTest {

    private class MapPrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private class Stub(val url: String, val requests: MutableList<String>, val server: HttpServer) {
        fun close() { server.stop(0) }
    }

    /** 整包桩：记录请求体，回固定 content（可含占位符供还原验证） */
    private fun stub(content: String): Stub {
        val requests = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/chat/completions") { exchange ->
            requests += String(exchange.requestBody.readBytes(), Charsets.UTF_8)
            val body = """{"choices":[{"message":{"role":"assistant","content":"$content"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}}"""
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return Stub("http://127.0.0.1:${server.address.port}", requests, server)
    }

    /** 流式桩：两帧增量（占位符被劈成两半）+ [DONE] */
    private fun streamStub(): Stub {
        val requests = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/chat/completions") { exchange ->
            requests += String(exchange.requestBody.readBytes(), Charsets.UTF_8)
            val frame1 = """{"choices":[{"delta":{"content":"客户 [["}}],"usage":{}}"""
            val frame2 = """{"choices":[{"delta":{"content":"P1]] 已入库"}}],"usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}"""
            val done = "data: [DONE]" + "\n\n"
            val payload = ("data: " + frame1 + "\n\n" + "data: " + frame2 + "\n\n" + done)

            val bytes = payload.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return Stub("http://127.0.0.1:${server.address.port}", requests, server)
    }

    private fun prefs(shield: Boolean): MapPrefs = MapPrefs().apply {
        set("stepfun.apiKey", "sk-test")
        if (shield) set("privacy.pii.shield", "true")
    }

    @Test
    fun `开启——出站载荷只见占位符，入站还原回原文`() {
        val s = stub("客户 [[P1]] 已记录。")
        val client = HttpLlmClient(prefs(shield = true), baseUrlOverride = s.url)
        try {
            val result = client.chat("m", listOf("user" to "客户手机 13812345678 请归档"), 0.7, 100)
            assertTrue(result.text.contains("13812345678"), "入站应还原：${result.text}")
            val outbound = s.requests.single()
            assertTrue(outbound.contains("[[P1]]"), "出站应为占位符")
            assertTrue(!outbound.contains("13812345678"), "出站不得含原文")
        } finally {
            s.close()
        }
    }

    @Test
    fun `关闭——零行为差异（载荷原样）`() {
        val s = stub("报告正文")
        val client = HttpLlmClient(prefs(shield = false), baseUrlOverride = s.url)
        try {
            val result = client.chat("m", listOf("user" to "客户手机 13812345678"), 0.7, 100)
            assertEquals("报告正文", result.text)
            assertTrue(s.requests.single().contains("13812345678"), "关闭态载荷原样")
        } finally {
            s.close()
        }
    }

    @Test
    fun `流式——终稿还原（跨帧占位符拼回后还原）`() {
        val s = streamStub()
        val client = HttpLlmClient(prefs(shield = true), baseUrlOverride = s.url)
        try {
            val result = client.streamChat("m", listOf("user" to "客户手机 13812345678"), 0.7, 100) { }
            assertEquals("客户 13812345678 已入库", result.text, "终稿必须还原：${result.text}")
        } finally {
            s.close()
        }
    }
}
