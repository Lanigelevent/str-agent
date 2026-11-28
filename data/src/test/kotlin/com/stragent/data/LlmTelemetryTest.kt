package com.stragent.data

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * T-232 · LLM 调用链可观测性端到端（数据层，真 HTTP 桩）。
 *
 * 覆盖：① ChatResult.model 带回精确模型名；② usageLedger 按精确模型名记实际用量；
 * ③ llm.calls 指标带**收敛后的 model 族**标签（series 上界固定）；
 * ④ 流式通道也记 llm.calls（T-161 登记的缺口）。
 */
class LlmTelemetryTest {

    private class MapPrefs : com.stragent.core.ports.PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private class Stub(val url: String, val server: HttpServer, val models: MutableList<String>) {
        fun close() { server.stop(0) }
    }

    private fun stub(): Stub {
        val models = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/chat/completions") { exchange ->
            val request = String(exchange.requestBody.readBytes(), Charsets.UTF_8)
            val model = Json.parseToJsonElement(request).jsonObject["model"]?.jsonPrimitive?.content ?: "unknown"
            models += model
            val body = """{"choices":[{"message":{"role":"assistant","content":"报告正文"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}}"""
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return Stub("http://127.0.0.1:${server.address.port}", server, models)
    }

    @Test
    fun `chat——精确模型名进回执与成本账本，收敛族标签进指标`() {
        val s = stub()
        val prefs = MapPrefs().apply { set("stepfun.apiKey", "sk-test") }
        val ledger = com.stragent.core.observability.UsageLedger(costPer1kTokens = 0.002)
        val metrics = com.stragent.core.observability.OtelMetrics(com.stragent.core.observability.CollectingMetricsExporter())
        val client = HttpLlmClient(prefs, baseUrlOverride = s.url).apply {
            usageLedger = ledger
            llmMetrics = metrics
        }
        try {
            val result = client.chat("step-3.5-flash", listOf("system" to "s", "user" to "u"), 0.7, 100)

            assertEquals("step-3.5-flash", result.model, "精确模型名应进回执")
            assertEquals(30, result.totalTokens)

            val byModel = ledger.snapshot()["byModel"]!!.let { it as kotlinx.serialization.json.JsonArray }
                .map { it.jsonObject }
            val bucket = byModel.first { it["model"]!!.jsonPrimitive.content == "step-3.5-flash" }
            assertEquals(1, bucket["calls"]!!.jsonPrimitive.content.toInt())
            assertEquals(30, bucket["tokens"]!!.jsonPrimitive.content.toInt())

            val calls = metrics.snapshot().single { it.name == com.stragent.core.observability.StrAgentMetrics.LLM_CALLS }
            val points = (calls as com.stragent.core.observability.MetricInstrumentData.Sum).points
            val okPoint = points.firstOrNull { it.attributes["outcome"] == "ok" }
            assertNotNull(okPoint, "应有 outcome=ok 的点：$points")
            assertEquals("step", okPoint.attributes["model"], "指标 label 用收敛后的族名，不是精确名")
            assertNotNull(okPoint.attributes["vendor"], "vendor 标签应在场")
        } finally {
            s.close()
        }
    }

    @Test
    fun `流式通道也记 llm calls（T-161 缺口）且模型名进回执`() {
        val s = stub()
        val prefs = MapPrefs().apply { set("stepfun.apiKey", "sk-test") }
        val metrics = com.stragent.core.observability.OtelMetrics(com.stragent.core.observability.CollectingMetricsExporter())
        val client = HttpLlmClient(prefs, baseUrlOverride = s.url).apply {
            llmMetrics = metrics
            usageLedger = com.stragent.core.observability.UsageLedger(null)
        }
        try {
            val chunks = mutableListOf<String>()
            val result = client.streamChat("gpt-4o-2024-08-06", listOf("user" to "u"), 0.7, 100) { chunks += it }

            assertEquals("gpt-4o-2024-08-06", result.model)
            assertEquals("报告正文", chunks.joinToString(""), "增量拼接应等于最终正文")
            val calls = metrics.snapshot().single { it.name == com.stragent.core.observability.StrAgentMetrics.LLM_CALLS }
            val points = (calls as com.stragent.core.observability.MetricInstrumentData.Sum).points
            assertTrue(points.any { it.attributes["outcome"] == "ok" && it.attributes["model"] == "gpt" },
                "流式通道也要记 llm.calls{model=gpt,outcome=ok}：$points")
        } finally {
            s.close()
        }
    }
}
