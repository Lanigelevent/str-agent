package com.stragent.data

import com.stragent.core.ports.AgentTool
import com.stragent.core.observability.CollectingSpanExporter
import com.stragent.core.observability.OtelSpan
import com.stragent.core.observability.OtelSpanContext
import com.stragent.core.observability.OtelTraces
import com.stragent.core.ports.PrefsStore
import com.stragent.core.trace.InsightTrace
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * MCP 链路的 OTel 端到端测试（T-056 / D17 G-16）。
 *
 * 钉住的语义：
 *  1. `params._meta.traceparent` 给出上游 trace：本服务产生 `tools/call <name>` SERVER span，
 *     工具内部（报告根 + 阶段）挂到这条链下——**一条 trace 从客户端连到报告阶段**；
 *  2. 工具回包 `traceId` 与上游一致，客户端能据此在 Jaeger/Tempo 里对上号；
 *  3. 坏 traceparent 不阻断调用（只是没有上游链路）；
 *  4. **未配置导出器 = 不建 span，但上下文照传**（追踪是增强不是依赖）；
 *  5. 工具抛错时 span 记 ERROR 且错误照常回给客户端；
 *  6. 异步任务（`_meta.task`）在 worker 线程里同样串链；
 *  7. HTTP 线上同步调用与直接 dispatch 同一口径。
 */
class McpTraceTest {

    private class MapPrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    /** 与宿主 `stragent_generate_report` 同形：从线程上下文取上游链路，报告 span 树挂上去 */
    private class FakeReportTool(
        private val otel: OtelTraces?,
        private val fail: Boolean = false,
    ) : AgentTool {
        override val name = "stragent_fake_report"
        override val description = "测试用：产生一棵报告 span 树"
        override val parametersSchema = buildJsonObject { put("type", "object") }
        override val cacheable = false

        @Volatile
        var sawParent: OtelSpanContext? = null
            private set

        override fun invoke(args: JsonObject): JsonObject {
            val parent = McpTraceContext.current()
            sawParent = parent
            if (fail) throw IllegalStateException("模拟生成失败")
            val trace = InsightTrace("demo.csv", System.currentTimeMillis(), parent = parent)
            trace.mark("analyzing", null)
            trace.mark("writing", null)
            trace.finish("fake-v1", 42, true)
            otel?.export(trace)
            return buildJsonObject {
                put("traceId", trace.traceId)
                parent?.let {
                    put("parentTraceId", it.traceId)
                    put("parentSpanId", it.spanId)
                }
            }
        }
    }

    private val upstreamTrace = "1".repeat(32)
    private val upstreamSpan = "2".repeat(16)
    private val upstream = "00-$upstreamTrace-$upstreamSpan-01"

    private fun callParams(traceparent: String?, task: Boolean = false): JsonObject = buildJsonObject {
        put("name", "stragent_fake_report")
        put("arguments", buildJsonObject { })
        put(
            "_meta",
            buildJsonObject {
                traceparent?.let { put("traceparent", it) }
                if (task) put("task", buildJsonObject { })
            },
        )
    }

    private fun bridgeWith(
        traces: OtelTraces?,
        tool: AgentTool,
        prefs: MapPrefs = MapPrefs(),
    ): McpBridge = McpBridge(
        AbilityDatasets(),
        prefs,
        extraTools = listOf(tool),
        otel = traces,
    )

    // ---------------- 1. 同步链路 ----------------

    @Test
    fun `tools-call 的 traceparent 把报告 span 树接到上游 trace 下`() {
        val collector = CollectingSpanExporter()
        val traces = OtelTraces(collector)
        try {
            val bridge = bridgeWith(traces, FakeReportTool(traces))
            val result = bridge.dispatch("tools/call", callParams(upstream), "req-1")
            assertTrue(traces.drain(3_000))

            val spans = collector.spans()
            assertEquals(4, spans.size, "1 个 tools/call + 2 个阶段 + 1 个报告根：$spans")

            val tool = spans.single { it.name == "tools/call stragent_fake_report" }
            assertEquals(OtelSpan.Kind.SERVER, tool.kind)
            assertEquals(upstreamTrace, tool.traceId)
            assertEquals(upstreamSpan, tool.parentSpanId)
            assertEquals("execute_tool", tool.attributesSnapshot()["gen_ai.operation.name"])
            assertEquals("stragent_fake_report", tool.attributesSnapshot()["gen_ai.tool.name"])
            assertEquals(OtelSpan.Status.OK, tool.status)
            assertNotNull(tool.endedAtNanos)

            val root = spans.single { it.name == "report.generate" }
            assertEquals(tool.traceId, root.traceId)
            assertEquals(tool.spanId, root.parentSpanId, "报告根应挂在 tools/call span 下")
            val stages = spans.filter { it.name.startsWith("report.stage:") }
            assertEquals(2, stages.size)
            stages.forEach { assertEquals(root.spanId, it.parentSpanId) }

            val structured = result["structuredContent"]!!.jsonObject
            assertEquals(upstreamTrace, structured["traceId"]!!.jsonPrimitive.content, "回包 traceId 与上游一致")
            assertEquals(tool.spanId, structured["parentSpanId"]!!.jsonPrimitive.content, "报告挂在本次工具调用的 span 下")
            assertNull(McpTraceContext.current(), "调用结束后线程上下文必须还原")
        } finally {
            traces.close()
        }
    }

    @Test
    fun `坏 traceparent 不阻断调用——工具照常执行，自己开一条 trace`() {
        val collector = CollectingSpanExporter()
        val traces = OtelTraces(collector)
        try {
            val bridge = bridgeWith(traces, FakeReportTool(traces))
            bridge.dispatch("tools/call", callParams("not-a-traceparent"), "req-1")
            assertTrue(traces.drain(3_000))
            val tool = collector.spans().single { it.name == "tools/call stragent_fake_report" }
            assertNull(tool.parentSpanId, "坏 header = 没有上游链路，不是调用失败")
            assertEquals(32, tool.traceId.length)
            assertEquals(OtelSpan.Status.OK, tool.status)
        } finally {
            traces.close()
        }
    }

    @Test
    fun `工具抛错时 tools-call span 记 ERROR，错误按 T-079 包装成 isError 结果回执`() {
        val collector = CollectingSpanExporter()
        val traces = OtelTraces(collector)
        try {
            val bridge = bridgeWith(traces, FakeReportTool(traces, fail = true))
            // T-079：执行错误不再外抛，包装成 isError 结果（span 的 ERROR 判定不受影响——
            // 转换挂在 tracedTool 外层，异常先被 span 记录后才包装）
            val result = bridge.dispatch("tools/call", callParams(upstream), null)
            assertEquals("true", result["isError"]!!.jsonPrimitive.content, "执行错误必须是结果层 isError")
            val structured = result["structuredContent"]!!.jsonObject
            assertEquals("模拟生成失败", structured["error"]!!.jsonPrimitive.content)
            assertTrue(
                structured["nextAction"]!!.jsonPrimitive.content.isNotBlank(),
                "isError 回执必须带非空 nextAction：$structured",
            )
            assertTrue(traces.drain(3_000))
            val tool = collector.spans().single { it.name == "tools/call stragent_fake_report" }
            assertEquals(OtelSpan.Status.ERROR, tool.status)
            assertEquals("模拟生成失败", tool.statusMessage)
        } finally {
            traces.close()
        }
    }

    @Test
    fun `未配置导出器——不建 tools-call span，但上游上下文照传（链路不断）`() {
        val collector = CollectingSpanExporter()
        val toolExporter = OtelTraces(collector)
        try {
            // 桥不接导出器（null），工具自持一个（模拟宿主自己的导出器）
            val bridge = bridgeWith(null, FakeReportTool(toolExporter))
            bridge.dispatch("tools/call", callParams(upstream), "req-1")
            assertTrue(toolExporter.drain(3_000))

            val spans = collector.spans()
            assertEquals(3, spans.size, "只有报告树（2 阶段 + 根），没有被追踪的 tools/call")
            val root = spans.single { it.name == "report.generate" }
            assertEquals(upstreamTrace, root.traceId)
            assertEquals(upstreamSpan, root.parentSpanId, "工具收到的上下文就是上游 span（id 依旧一致）")
            assertFalse(spans.any { it.name.startsWith("tools/call ") })
        } finally {
            toolExporter.close()
        }
    }

    // ---------------- 2. 异步任务 ----------------

    @Test
    fun `异步任务在 worker 里同样串链，结果与同步一致`() {
        val collector = CollectingSpanExporter()
        val traces = OtelTraces(collector)
        try {
            val bridge = bridgeWith(traces, FakeReportTool(traces))
            val created = bridge.dispatch("tools/call", callParams(upstream, task = true), "req-async")
            val taskId = created["taskId"]!!.jsonPrimitive.content
            assertEquals("working", created["status"]!!.jsonPrimitive.content)

            val deadline = System.currentTimeMillis() + 5_000
            var snapshot: JsonObject = created
            while (System.currentTimeMillis() < deadline) {
                snapshot = bridge.dispatch("tasks/get", buildJsonObject { put("taskId", taskId) }, "poll")
                if (snapshot["status"]!!.jsonPrimitive.content != "working") break
                Thread.sleep(10)
            }
            assertEquals("completed", snapshot["status"]!!.jsonPrimitive.content)
            assertTrue(traces.drain(3_000))

            val tool = collector.spans().single { it.name == "tools/call stragent_fake_report" }
            assertEquals(upstreamTrace, tool.traceId, "worker 线程也要拿到上游 trace")
            assertEquals(upstreamSpan, tool.parentSpanId)
            assertEquals(OtelSpan.Status.OK, tool.status)

            val root = collector.spans().single { it.name == "report.generate" }
            assertEquals(tool.spanId, root.parentSpanId)
        } finally {
            traces.close()
        }
    }

    // ---------------- 3. HTTP 线上 ----------------

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

    @Test
    fun `HTTP 线上同步调用同样串链，结构化回包带 traceId`() {
        val collector = CollectingSpanExporter()
        val traces = OtelTraces(collector)
        val prefs = MapPrefs()
        val bridge = bridgeWith(traces, FakeReportTool(traces), prefs)
        val port = bridge.start(0)
        try {
            val body = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 7)
                put("method", "tools/call")
                put("params", callParams(upstream))
            }.toString()
            val (status, text) = httpPost(port, McpBridge.currentToken(prefs), body)
            assertEquals(200, status, text)
            assertTrue(traces.drain(3_000))

            val tool = collector.spans().single { it.name == "tools/call stragent_fake_report" }
            assertEquals(upstreamTrace, tool.traceId)
            assertEquals(upstreamSpan, tool.parentSpanId)

            val structured = Json.parseToJsonElement(text).jsonObject["result"]!!
                .jsonObject["structuredContent"]!!.jsonObject
            assertEquals(upstreamTrace, structured["traceId"]!!.jsonPrimitive.content)
        } finally {
            bridge.stop()
            traces.close()
        }
    }
}
