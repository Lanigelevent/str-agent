package com.stragent.core.observability

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * OTel 追踪内核的离线单元测试（T-056 / D17 G-16）。
 *
 * 钉住的口径：
 *  1. W3C trace-context：合法 header 解析/回写一致，坏 header 一律 null（不抛）；
 *  2. span 关系与属性归一（Float→double、数字→int64），状态/结束幂等；
 *  3. OTLP/HTTP JSON 结构：`resourceSpans[].scopeSpans[].spans[]`、64 位时间戳是字符串、
 *     属性是 tagged union、空属性不写字段；
 *  4. 导出器：HTTP（stub collector 收到真实 body/头）、文件 JSONL、失败不抛；
 *  5. 异步批量：有界队列**丢批计数**（不阻塞业务）、drain 收尾；
 *  6. 环境装配：端点优先级、header 解析、成本单价的可选性（未配置 = null）。
 */
class OtelTest {

    private val validTrace = "4bf92f3577b34da6a3ce929d0e0e4736"
    private val validSpan = "00f067aa0ba902b7"

    // ---------------- 1. id 与 traceparent ----------------

    @Test
    fun `trace 与 span id 形制符合 W3C`() {
        repeat(20) {
            val traceId = TraceIds.traceId()
            assertEquals(32, traceId.length)
            assertTrue(TraceIds.isHex(traceId))
            assertFalse(TraceIds.isZero(traceId))
        }
        val spanId = TraceIds.spanId()
        assertEquals(16, spanId.length)
        assertTrue(TraceIds.isHex(spanId))
        assertFalse(TraceIds.isZero(spanId))
        assertTrue(TraceIds.traceId() != TraceIds.traceId(), "两次生成的 trace id 不应相同")
    }

    @Test
    fun `traceparent 解析与回写一致`() {
        val parsed = assertNotNull(W3cTraceparent.parse("00-$validTrace-$validSpan-01"))
        assertEquals(validTrace, parsed.traceId)
        assertEquals(validSpan, parsed.parentSpanId)
        assertTrue(parsed.sampled)
        assertEquals("00-$validTrace-$validSpan-01", parsed.format())

        val unsampled = assertNotNull(W3cTraceparent.parse("00-$validTrace-$validSpan-00"))
        assertFalse(unsampled.sampled)
        assertTrue(unsampled.format().endsWith("-00"))

        val context = parsed.toContext()
        assertEquals(validTrace, context.traceId)
        assertEquals(validSpan, context.spanId)
        assertEquals("00-$validTrace-$validSpan-01", context.traceparent())
    }

    @Test
    fun `坏 traceparent 一律 null——不抛、不阻断调用`() {
        val bad = listOf(
            null,
            "",
            "garbage",
            "00-$validTrace-$validSpan", // 段数不足
            "ff-$validTrace-$validSpan-01", // ff 是保留版本
            "00-$validTrace-$validSpan-01-extra", // 00 版必须恰好 4 段
            "00-${"0".repeat(32)}-$validSpan-01", // 全零 trace id
            "00-$validTrace-${"0".repeat(16)}-01", // 全零 span id
            "00-$validTrace-$validSpan-0", // flags 长度不对
            "00-${validTrace.dropLast(1)}-$validSpan-01", // trace id 长度不对
            "00-${"z".repeat(32)}-$validSpan-01", // 非 hex
        )
        for (header in bad) {
            assertNull(W3cTraceparent.parse(header), "应拒绝：$header")
        }
        // 未来版本（≠00/ff）允许多段：按规范忽略额外字段
        assertNotNull(W3cTraceparent.parse("01-$validTrace-$validSpan-01-whatever"))
    }

    // ---------------- 2. span 关系与属性 ----------------

    @Test
    fun `子 span 继承 traceId 并指向父 span；无父则新开 trace`() {
        val parent = OtelSpanContext(validTrace, validSpan)
        val child = OtelSpan.child("tools/call x", parent, OtelSpan.Kind.SERVER)
        assertEquals(validTrace, child.traceId)
        assertEquals(validSpan, child.parentSpanId)
        assertEquals(16, child.spanId.length)
        assertTrue(child.spanId != validSpan)

        val root = OtelSpan.root("report.generate")
        assertNull(root.parentSpanId)
        assertEquals(32, root.traceId.length)

        // 采样标志继承（T-065）：spanContext 必须带上标志，否则下游拿不到整条 trace 的决定
        val unsampledChild = OtelSpan.child("tools/call y", OtelSpanContext(validTrace, validSpan, sampled = false))
        assertFalse(unsampledChild.sampled, "child 继承父上下文的未采样标志")
        assertFalse(unsampledChild.spanContext().sampled, "spanContext 必须带回采样标志（T-065 修复点）")
        val sampledChild = OtelSpan.child("tools/call z", OtelSpanContext(validTrace, validSpan, sampled = true))
        assertTrue(sampledChild.sampled)
    }

    @Test
    fun `未采样 span 在导出时被过滤——混合批次只发采样部分`() {
        val exporter = CollectingSpanExporter()
        val traces = OtelTraces(exporter)
        try {
            traces.export(listOf(OtelSpan.root("keep-1").end()))
            traces.export(
                listOf(
                    OtelSpan("a".repeat(32), "b".repeat(16), null, "drop-1", sampled = false).end(),
                ),
            )
            traces.export(
                listOf(
                    OtelSpan.root("keep-2").end(),
                    OtelSpan("a".repeat(32), "c".repeat(16), null, "drop-2", sampled = false).end(),
                ),
            )
            assertTrue(traces.drain(3_000))
            assertEquals(
                listOf("keep-1", "keep-2"),
                exporter.spans().map { it.name },
                "未采样 span 绝不能到达导出器",
            )
        } finally {
            traces.close()
        }
    }

    @Test
    fun `属性归一与状态、结束幂等`() {
        val span = OtelSpan.root("x", startedAtNanos = 1_000)
            .set("s", "v")
            .set("i", 42)
            .set("l", 42L)
            .set("f", 1.5f)
            .set("d", 2.5)
            .set("b", true)
            .set("nil", null)
            .set("other", listOf(1, 2))
        val attrs = span.attributesSnapshot()
        assertEquals("v", attrs["s"])
        assertEquals(42, attrs["i"])
        assertEquals(42L, attrs["l"])
        assertEquals(1.5, attrs["f"])
        assertEquals(2.5, attrs["d"])
        assertEquals(true, attrs["b"])
        assertFalse(attrs.containsKey("nil"))
        assertEquals("[1, 2]", attrs["other"])

        span.ok().end(5_000).end(9_000)
        assertEquals(OtelSpan.Status.OK, span.status)
        assertEquals(5_000L, span.endedAtNanos, "end 幂等：第二次不改结束时间")
        assertEquals(4_000L, span.durationNanos)

        val failed = OtelSpan.root("y").error("boom")
        assertEquals(OtelSpan.Status.ERROR, failed.status)
        assertEquals("boom", failed.statusMessage)
    }

    // ---------------- 3. OTLP JSON 结构 ----------------

    @Test
    fun `OTLP envelope 结构与字段口径`() {
        val span = OtelSpan(
            traceId = "a".repeat(32),
            spanId = "b".repeat(16),
            parentSpanId = "c".repeat(16),
            name = "tools/call x",
            kind = OtelSpan.Kind.SERVER,
            startedAtNanos = 1_700_000_000_000_000_000,
        )
            .set("s", "v")
            .set("i", 42)
            .set("d", 1.5)
            .set("b", true)
            .ok()
            .end(1_700_000_000_500_000_000)

        val json = OtlpJson.envelope(mapOf("service.name" to "str-agent"), listOf(span))
        val resourceSpans = json["resourceSpans"]!!.jsonArray
        assertEquals(1, resourceSpans.size)
        val resourceAttributes = resourceSpans[0].jsonObject["resource"]!!
            .jsonObject["attributes"]!!.jsonArray
        assertEquals("service.name", resourceAttributes[0].jsonObject["key"]!!.jsonPrimitive.content)
        assertEquals(
            "str-agent",
            resourceAttributes[0].jsonObject["value"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content,
        )

        val scopeSpan = resourceSpans[0].jsonObject["scopeSpans"]!!.jsonArray[0].jsonObject
        assertEquals(OtlpJson.SCOPE_NAME, scopeSpan["scope"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val spanJson = scopeSpan["spans"]!!.jsonArray[0].jsonObject
        assertEquals("a".repeat(32), spanJson["traceId"]!!.jsonPrimitive.content)
        assertEquals("b".repeat(16), spanJson["spanId"]!!.jsonPrimitive.content)
        assertEquals("c".repeat(16), spanJson["parentSpanId"]!!.jsonPrimitive.content)
        assertEquals("tools/call x", spanJson["name"]!!.jsonPrimitive.content)
        assertEquals(2, spanJson["kind"]!!.jsonPrimitive.content.toInt(), "SERVER = 2")
        // 64 位整数按字符串输出（OTLP JSON 映射要求，避免 JS 客户端丢精度）
        assertEquals("1700000000000000000", spanJson["startTimeUnixNano"]!!.jsonPrimitive.content)
        assertEquals("1700000000500000000", spanJson["endTimeUnixNano"]!!.jsonPrimitive.content)

        val attrs = spanJson["attributes"]!!.jsonArray.associate {
            it.jsonObject["key"]!!.jsonPrimitive.content to it.jsonObject["value"]!!.jsonObject
        }
        assertEquals("v", attrs["s"]!!["stringValue"]!!.jsonPrimitive.content)
        assertEquals("42", attrs["i"]!!["intValue"]!!.jsonPrimitive.content)
        assertEquals("1.5", attrs["d"]!!["doubleValue"]!!.jsonPrimitive.content)
        assertEquals("true", attrs["b"]!!["boolValue"]!!.jsonPrimitive.content)
        assertEquals(1, spanJson["status"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt(), "OK = 1")
    }

    @Test
    fun `无父 span 不写 parentSpanId，空属性不写 attributes`() {
        val span = OtelSpan.root("report.generate").end()
        val spanJson = OtlpJson.envelope(emptyMap(), listOf(span))["resourceSpans"]!!.jsonArray[0]
            .jsonObject["scopeSpans"]!!.jsonArray[0].jsonObject["spans"]!!.jsonArray[0].jsonObject
        assertFalse(spanJson.containsKey("parentSpanId"))
        assertFalse(spanJson.containsKey("attributes"))
        assertEquals("0", spanJson["status"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    // ---------------- 4. 导出器 ----------------

    @Test
    fun `HTTP 导出器发真实 OTLP body 与自定义头，非 2xx 与不可达都只回 false`() {
        val received = AtomicReference<String>()
        val contentType = AtomicReference<String>()
        val tenant = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/traces") { exchange ->
            contentType.set(exchange.requestHeaders.getFirst("Content-Type"))
            tenant.set(exchange.requestHeaders.getFirst("X-Tenant"))
            received.set(String(exchange.requestBody.readBytes(), Charsets.UTF_8))
            val body = "{}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val exporter = OtlpHttpSpanExporter(
                "http://127.0.0.1:${server.address.port}/v1/traces",
                headers = mapOf("X-Tenant" to "t1"),
                timeoutMs = 2_000,
            )
            val span = OtelSpan.root("tools/call x").ok().end()
            assertTrue(exporter.export(mapOf("service.name" to "str-agent"), listOf(span)))
            assertEquals("application/json", contentType.get())
            assertEquals("t1", tenant.get())
            val parsed = Json.parseToJsonElement(assertNotNull(received.get())).jsonObject
            assertTrue(parsed.containsKey("resourceSpans"))
            exporter.close()
        } finally {
            server.stop(0)
        }

        // 非 2xx：false（调用方只记一次告警）
        val failing = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        failing.createContext("/v1/traces") { exchange ->
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        failing.start()
        try {
            val exporter = OtlpHttpSpanExporter(
                "http://127.0.0.1:${failing.address.port}/v1/traces",
                timeoutMs = 2_000,
            )
            assertFalse(exporter.export(mapOf("service.name" to "s"), listOf(OtelSpan.root("x").end())))
        } finally {
            failing.stop(0)
        }

        // 不可达：不抛，返回 false
        val unreachable = OtlpHttpSpanExporter("http://127.0.0.1:1/v1/traces", timeoutMs = 200)
        assertFalse(unreachable.export(mapOf("service.name" to "s"), listOf(OtelSpan.root("x").end())))
    }

    @Test
    fun `文件导出器逐批写 JSONL，数据与 HTTP 同构`() {
        val dir = Files.createTempDirectory("otel-file")
        val file = dir.resolve("spans.jsonl").toFile()
        try {
            val exporter = FileSpanExporter(file)
            assertTrue(exporter.export(mapOf("service.name" to "s"), listOf(OtelSpan.root("a").ok().end())))
            assertTrue(exporter.export(mapOf("service.name" to "s"), listOf(OtelSpan.root("b").ok().end())))
            val lines = file.readLines()
            assertEquals(2, lines.size)
            lines.forEach { line ->
                val parsed = Json.parseToJsonElement(line).jsonObject
                assertTrue(parsed.containsKey("resourceSpans"))
            }
            assertTrue(lines[0].contains("\"name\":\"a\""))
            assertTrue(lines[1].contains("\"name\":\"b\""))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** 先卡住第一批导出，让队列被填满——可确定地观测「丢批」 */
    private class BlockingExporter : SpanExporter {
        val started = CountDownLatch(1)
        private val gate = CountDownLatch(1)
        private val batches = mutableListOf<List<OtelSpan>>()

        override fun export(resource: Map<String, String>, spans: List<OtelSpan>): Boolean {
            started.countDown()
            gate.await(5, TimeUnit.SECONDS)
            synchronized(batches) { batches.add(spans) }
            return true
        }

        fun release() = gate.countDown()

        fun received(): List<List<OtelSpan>> = synchronized(batches) { batches.toList() }
    }

    // ---------------- 5. 异步批量 ----------------

    @Test
    fun `异步导出不阻塞；队列满时丢批计数，drain 等到最后一批完成`() {
        val exporter = BlockingExporter()
        val traces = OtelTraces(exporter, queueCapacity = 1)
        try {
            traces.export(listOf(OtelSpan.root("first").end()))
            assertTrue(exporter.started.await(2, TimeUnit.SECONDS), "导出线程应已开始处理第一批")

            traces.export(listOf(OtelSpan.root("second").end())) // 入队
            traces.export(listOf(OtelSpan.root("third").end())) // 队列满 → 丢弃
            assertEquals(1, traces.droppedBatches(), "容量 1 时第三批必须被丢弃并计数（不阻塞调用方）")

            exporter.release()
            assertTrue(traces.drain(5_000), "drain 应等到队列清空且在途批次完成")
            assertEquals(2, exporter.received().size, "被丢弃的批次永远不会导出")
        } finally {
            traces.close()
        }
    }

    @Test
    fun `drain 只认已提交等于已完成——连续 60 轮都不提前返回`() {
        // 回归 run 1619：旧判据是「队列空 + 在途 0」，worker 刚 take 完、还没把在途加 1
        // 的瞬间会假「已清空」，调用方随即读到 0 个 span（McpTraceTest 偶发 0）。
        val exporter = CollectingSpanExporter()
        val traces = OtelTraces(exporter)
        try {
            repeat(60) { round ->
                traces.export(listOf(OtelSpan.root("round-$round").end()))
                assertTrue(traces.drain(2_000), "第 $round 轮 drain 超时")
                assertEquals(
                    round + 1,
                    exporter.spans().size,
                    "drain 返回 true 时第 $round 批必须已经落到导出器",
                )
            }
        } finally {
            traces.close()
        }
    }

    // ---------------- 6. 环境装配 ----------------

    @Test
    fun `环境变量装配：端点优先级、文件兜底、header 解析`() {
        assertNull(OtelEnvironment.exporterFrom(emptyMap()))
        assertNull(OtelEnvironment.fromEnvironment(emptyMap()))

        val fromBase = OtelEnvironment.exporterFrom(
            mapOf(OtelEnvironment.ENDPOINT to "http://localhost:4318"),
        )
        assertIs<OtlpHttpSpanExporter>(fromBase)
        assertEquals("http://localhost:4318/v1/traces", fromBase.endpoint)

        // 带尾斜杠不产生双斜杠
        val trailing = assertIs<OtlpHttpSpanExporter>(
            OtelEnvironment.exporterFrom(mapOf(OtelEnvironment.ENDPOINT to "http://localhost:4318/")),
        )
        assertEquals("http://localhost:4318/v1/traces", trailing.endpoint)

        // traces 专用端点优先于基础端点
        val direct = assertIs<OtlpHttpSpanExporter>(
            OtelEnvironment.exporterFrom(
                mapOf(
                    OtelEnvironment.ENDPOINT to "http://ignored:4318",
                    OtelEnvironment.TRACES_ENDPOINT to "http://collector:4318/custom/traces",
                ),
            ),
        )
        assertEquals("http://collector:4318/custom/traces", direct.endpoint)

        val dir = Files.createTempDirectory("otel-env")
        try {
            val filePath = dir.resolve("spans.jsonl").toString()
            assertIs<FileSpanExporter>(OtelEnvironment.exporterFrom(mapOf(OtelEnvironment.FILE to filePath)))

            val traces = assertNotNull(
                OtelEnvironment.fromEnvironment(
                    mapOf(OtelEnvironment.FILE to filePath, OtelEnvironment.SERVICE_NAME to "custom-agent"),
                    serviceVersion = "9.9.9",
                ),
            )
            assertEquals("custom-agent", traces.resource["service.name"])
            assertEquals("9.9.9", traces.resource["service.version"])
            traces.export(listOf(OtelSpan.root("x").ok().end()))
            assertTrue(traces.drain(3_000))
            traces.close()
            assertTrue(java.io.File(filePath).readText().contains("\"name\":\"x\""))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `header 与成本单价的解析口径`() {
        assertEquals(
            mapOf("authorization" to "Bearer abc", "x-tenant" to "t1"),
            OtelEnvironment.parseHeaders("authorization=Bearer abc, x-tenant = t1 "),
        )
        assertTrue(OtelEnvironment.parseHeaders(null).isEmpty())
        assertTrue(OtelEnvironment.parseHeaders("bad,=x").isEmpty())

        assertEquals(0.01, OtelEnvironment.costPer1kTokens(mapOf(OtelEnvironment.COST_PER_1K_TOKENS to "0.01")))
        assertEquals(0.0, OtelEnvironment.costPer1kTokens(mapOf(OtelEnvironment.COST_PER_1K_TOKENS to "0")))
        assertNull(OtelEnvironment.costPer1kTokens(mapOf(OtelEnvironment.COST_PER_1K_TOKENS to "-1")))
        assertNull(OtelEnvironment.costPer1kTokens(mapOf(OtelEnvironment.COST_PER_1K_TOKENS to "abc")))
        assertNull(OtelEnvironment.costPer1kTokens(emptyMap()))
    }
}
