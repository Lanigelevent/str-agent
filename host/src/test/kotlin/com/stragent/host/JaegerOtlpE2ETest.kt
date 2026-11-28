package com.stragent.host

import com.stragent.core.observability.OtelTraces
import com.stragent.core.observability.OtlpHttpSpanExporter
import com.stragent.data.McpBridge
import com.stragent.data.abilityTools
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * T-056 ⑦ · 真实收集器端到端：一份报告的 span 树在 **Jaeger** 里可见。
 *
 * 这套用例**需要外部环境**（默认跳过，`Assume` 缺省视为跳过而不是「假绿」）：
 *
 * ```
 * docker run -d --name str-agent-jaeger -e COLLECTOR_OTLP_ENABLED=true \
 *   -p 4318:4318 -p 16686:16686 jaegertracing/all-in-one:1.57
 * STRAGENT_OTEL_E2E_ENDPOINT=http://127.0.0.1:4318 \
 * STRAGENT_OTEL_E2E_UI=http://127.0.0.1:16686 \
 * ./gradlew :host:test --tests 'com.stragent.host.JaegerOtlpE2ETest'
 * ```
 *
 * 验的是**真实链路**：OTLP/HTTP 导出器 → Jaeger 的 OTLP 接收端 → Jaeger 查询 API，
 * 而不是「我们自己造了个 JSON 看它像不像」。断言父子关系（CHILD_OF）、阶段 span、
 * GenAI 属性与 `_meta.traceparent` 指定的 trace id 一致。
 *
 * 不证明：Tempo 的相同行为需要 Tempo 环境（两者都吃 OTLP/HTTP，协议层同一份数据）。
 */
class JaegerOtlpE2ETest {

    private val csv = "地区,销售额,订单数\n华东,1000,10\n华北,300,5\n"

    private fun httpPost(port: Int, token: String, body: String): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val status = conn.responseCode
        val stream = if (status >= 400) conn.errorStream else conn.inputStream
        val text = stream?.readBytes()?.decodeToString() ?: ""
        conn.disconnect()
        return status to text
    }

    private fun rpc(id: Int, method: String, params: JsonObject): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        put("params", params)
    }.toString()

    /** 查 Jaeger：还没索引到返回 null（调用方轮询） */
    private fun fetchTrace(ui: String, traceId: String): JsonObject? {
        val conn = URL("$ui/api/traces/$traceId").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        return try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.readBytes().decodeToString()
            val data = Json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return null
            if (data.isEmpty()) null else data[0].jsonObject
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun JsonObject.span(name: String): JsonObject? =
        this["spans"]?.jsonArray
            ?.map { it.jsonObject }
            ?.singleOrNull { it["operationName"]?.jsonPrimitive?.content == name }

    private fun JsonObject.tag(key: String): String? =
        this["tags"]?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { it["key"]?.jsonPrimitive?.content == key }
            ?.get("value")?.jsonPrimitive?.content

    @Test
    fun `一份报告的 span 树在 Jaeger 里可查——父子关系、阶段与 gen_ai 属性都到位`() {
        val endpoint = System.getenv("STRAGENT_OTEL_E2E_ENDPOINT")
        val ui = System.getenv("STRAGENT_OTEL_E2E_UI")
        if (endpoint.isNullOrBlank() || ui.isNullOrBlank()) {
            println("跳过：未设置 STRAGENT_OTEL_E2E_ENDPOINT / STRAGENT_OTEL_E2E_UI（见类注释的 docker 命令）")
            return
        }

        val exporter = OtlpHttpSpanExporter("$endpoint/v1/traces", timeoutMs = 5_000)
        val traces = OtelTraces(
            exporter,
            resource = mapOf("service.name" to "str-agent-e2e", "service.version" to "e2e"),
        )
        val home: File = Files.createTempDirectory("str-agent-jaeger").toFile()
        try {
            StrAgentHostBootstrap.create(home, llm = FakeLlmClient()).use { bootstrap ->
                val tools = abilityTools(bootstrap.abilityDatasets) + pluginTools(
                    bootstrap.plugin, bootstrap.abilityDatasets, bootstrap.memory,
                    otel = traces, costPer1kTokens = 0.01,
                )
                val bridge = McpBridge(
                    bootstrap.abilityDatasets, bootstrap.prefs,
                    extraTools = tools, otel = traces,
                )
                val port = bridge.start(0)
                try {
                    val token = McpBridge.currentToken(bootstrap.prefs)
                    val (importStatus, importText) = httpPost(
                        port, token,
                        rpc(
                            1, "tools/call",
                            buildJsonObject {
                                put("name", "stragent_import_table")
                                put("arguments", buildJsonObject {
                                    put("csv", csv)
                                    put("name", "jaeger样本")
                                })
                            },
                        ),
                    )
                    assertEquals(200, importStatus, importText)
                    val datasetId = Json.parseToJsonElement(importText).jsonObject["result"]!!
                        .jsonObject["structuredContent"]!!.jsonObject["datasetId"]!!.jsonPrimitive.content

                    val traceId = "d".repeat(32)
                    val parentSpanId = "e".repeat(16)
                    val (reportStatus, reportText) = httpPost(
                        port, token,
                        rpc(
                            2, "tools/call",
                            buildJsonObject {
                                put("name", "stragent_generate_report")
                                put("arguments", buildJsonObject { put("datasetId", datasetId) })
                                put("_meta", buildJsonObject {
                                    put("traceparent", "00-$traceId-$parentSpanId-01")
                                })
                            },
                        ),
                    )
                    assertEquals(200, reportStatus, reportText)
                    assertTrue(traces.drain(10_000), "span 批次应在超时前送出")

                    // Jaeger 索引有延迟：轮询查询 API（最多 ~10s）
                    var trace: JsonObject? = null
                    repeat(20) {
                        trace = fetchTrace(ui, traceId)
                        if (trace != null) return@repeat
                        Thread.sleep(500)
                    }
                    val found = assertNotNull(trace, "Jaeger 里应能按 trace id 查到这份报告（$traceId）")
                    assertEquals(traceId, found["traceID"]?.jsonPrimitive?.content)

                    val toolSpan = assertNotNull(found.span("tools/call stragent_generate_report"), "应有 tools/call span")
                    val reportSpan = assertNotNull(found.span("report.generate"), "应有报告根 span")
                    val stages = found["spans"]!!.jsonArray.map { it.jsonObject }
                        .filter { it["operationName"]!!.jsonPrimitive.content.startsWith("report.stage:") }
                    assertTrue(stages.isNotEmpty(), "应有阶段 span：$stages")

                    // 父子关系：report.generate 的 CHILD_OF 指向 tools/call
                    val parentRef = reportSpan["references"]!!.jsonArray.map { it.jsonObject }
                        .single { it["refType"]!!.jsonPrimitive.content == "CHILD_OF" }
                    assertEquals(
                        toolSpan["spanID"]!!.jsonPrimitive.content,
                        parentRef["spanID"]!!.jsonPrimitive.content,
                        "报告根应挂在 tools/call 下",
                    )
                    // 阶段挂到报告根
                    stages.forEach { stage ->
                        val ref = stage["references"]!!.jsonArray.map { it.jsonObject }
                            .single { it["refType"]!!.jsonPrimitive.content == "CHILD_OF" }
                        assertEquals(reportSpan["spanID"]!!.jsonPrimitive.content, ref["spanID"]!!.jsonPrimitive.content)
                    }

                    // GenAI 语义与 token 计数
                    assertEquals("generate", reportSpan.tag("gen_ai.operation.name"))
                    assertEquals("str_agent", reportSpan.tag("gen_ai.system"))
                    assertTrue((reportSpan.tag("gen_ai.usage.total_tokens")?.toLongOrNull() ?: 0L) > 0)
                    assertEquals("execute_tool", toolSpan.tag("gen_ai.operation.name"))
                    assertEquals("stragent_generate_report", toolSpan.tag("gen_ai.tool.name"))

                    // 服务名来自导出的 resource（Jaeger 的 processes）
                    val services = found["processes"]!!.jsonObject.values
                        .map { it.jsonObject["serviceName"]!!.jsonPrimitive.content }
                    assertTrue(services.contains("str-agent-e2e"), "resource service.name 应到达 Jaeger：$services")
                } finally {
                    bridge.stop()
                }
            }
        } finally {
            traces.close()
            home.deleteRecursively()
        }
    }
}
