package com.stragent.core.observability

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * OTel metrics 内核离线单测（T-065）。
 *
 * 钉住的口径：
 *  1. 计数器：累计、多 series 隔离、负值拒绝、同名幂等；
 *  2. 直方图：count/sum/min/max、边界值归属（v ≥ 边界进后一桶）、超上界进最后桶；
 *  3. series 上限：超限并入 `otel.metric.overflow` 溢出 series（丢维度不丢数据）；
 *  4. 导出：无数据点不发、envelope 形制（64 位字符串、temporality、min/max 门控）、
 *     周期线程、close 收尾、文件 JSONL、环境装配优先级。
 */
class OtelMetricsTest {

    // ---------------- 1. 计数器 ----------------

    @Test
    fun `计数器累计、多 series 隔离与同名幂等`() {
        val registry = OtelMetrics(CollectingMetricsExporter(), intervalMs = 3_600_000)
        try {
            val c = registry.counter("test.calls", unit = "{call}", description = "d")
            c.add(3)
            c.add(2)
            c.add(1, mapOf("k" to "a"))
            c.add(1, mapOf("k" to "b"))
            c.add(1, mapOf("k" to "a")) // 与第一组同 series
            c.add(-5) // 负值拒绝

            val sum = assertIs<MetricInstrumentData.Sum>(registry.snapshot().single { it.name == "test.calls" })
            assertEquals(3, sum.points.size, "3 个 series：默认 + a + b")
            val byAttr = sum.points.associate { it.attributes["k"] to it.value }
            assertEquals(5L, byAttr[null], "无属性 series 累计 3+2-5=5（负值被拒）")
            assertEquals(2L, byAttr["a"])
            assertEquals(1L, byAttr["b"])

            // 同名幂等：再取拿到同一实例
            registry.counter("test.calls").add(1)
            val again = assertIs<MetricInstrumentData.Sum>(registry.snapshot().single { it.name == "test.calls" })
            assertEquals(6L, again.points.first { it.attributes.isEmpty() }.value)
        } finally {
            registry.close()
        }
    }

    // ---------------- 2. 直方图 ----------------

    @Test
    fun `直方图聚合与桶归属`() {
        val registry = OtelMetrics(CollectingMetricsExporter(), intervalMs = 3_600_000)
        try {
            val h = registry.histogram("test.dur", boundaries = listOf(10.0, 50.0, 100.0))
            h.record(5.0)   // 桶0
            h.record(10.0)  // v ≥ 10 → 桶1（边界值进后一桶）
            h.record(42.0)  // 桶1
            h.record(99.0)  // 桶1
            h.record(500.0) // 超上界 → 最后桶
            h.record(7.0, mapOf("tool" to "other")) // 独立 series

            val hist = assertIs<MetricInstrumentData.Histogram>(registry.snapshot().single { it.name == "test.dur" })
            assertEquals(2, hist.points.size)
            val main = hist.points.first { it.attributes.isEmpty() }
            assertEquals(5L, main.count)
            assertEquals(656.0, main.sum, "5+10+42+99+500")
            assertEquals(5.0, main.min)
            assertEquals(500.0, main.max)
            assertEquals(listOf(1L, 2L, 1L, 1L), main.bucketCounts, "桶0=[-,10)=1（5.0）；桶1=[10,50)=2（10.0,42.0）；桶2=[50,100)=1（99.0）；最后桶≥100=1（500.0）")
            assertEquals(listOf(10.0, 50.0, 100.0), main.explicitBounds)
        } finally {
            registry.close()
        }
    }

    @Test
    fun `直方图拒绝 NaN 与 Infinity`() {
        val registry = OtelMetrics(CollectingMetricsExporter(), intervalMs = 3_600_000)
        try {
            val h = registry.histogram("test.nan")
            h.record(Double.NaN)
            h.record(Double.POSITIVE_INFINITY)
            val hist = assertIs<MetricInstrumentData.Histogram>(registry.snapshot().single { it.name == "test.nan" })
            assertEquals(0, hist.points.size, "没有任何合法观测 → 0 个数据点")
        } finally {
            registry.close()
        }
    }

    // ---------------- 3. series 上限与溢出 ----------------

    @Test
    fun `series 超限并入溢出 series——丢维度不丢数据`() {
        val registry = OtelMetrics(CollectingMetricsExporter(), intervalMs = 3_600_000, maxSeries = 2)
        try {
            val c = registry.counter("cap.calls")
            repeat(3) { i -> c.add(1, mapOf("k" to "v$i")) } // 上限 2：v0/v1 正常，v2 → 溢出
            c.add(1, mapOf("k" to "v0")) // 已有 series 不受上限影响
            val sum = assertIs<MetricInstrumentData.Sum>(registry.snapshot().single { it.name == "cap.calls" })
            assertEquals(3, sum.points.size, "2 个正常 series + 1 个溢出 series")
            val overflow = sum.points.first { it.attributes.containsKey("otel.metric.overflow") }
            assertEquals(1L, overflow.value, "v2 的 1 次并入溢出")
            assertEquals(2L, sum.points.first { it.attributes["k"] == "v0" }.value)
        } finally {
            registry.close()
        }
    }

    // ---------------- 4. 导出与形制 ----------------

    @Test
    fun `无数据点不导出；有数据导出且 envelope 形制合规`() {
        val exporter = CollectingMetricsExporter()
        val registry = OtelMetrics(exporter, intervalMs = 3_600_000)
        try {
            assertTrue(registry.collectAndExport(), "无数据点：返回 true 但不发")
            assertEquals(0, exporter.bodies().size)

            registry.counter("env.calls").add(7)
            registry.histogram("env.dur", boundaries = listOf(1.0, 10.0)).record(0.5)
            assertTrue(registry.collectAndExport())
            assertEquals(1, exporter.bodies().size)

            val parsed = Json.parseToJsonElement(exporter.bodies().single()).jsonObject
            val rm = (parsed["resourceMetrics"] as JsonArray)[0].jsonObject
            val sm = (rm["scopeMetrics"] as JsonArray)[0].jsonObject
            assertEquals("com.stragent", sm["scope"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            val metricList = (sm["metrics"] as JsonArray)
            assertEquals(2, metricList.size)
            val sum = metricList.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == "env.calls" }
            val sumBody = sum["sum"]!!.jsonObject
            assertEquals("2", sumBody["aggregationTemporality"]!!.jsonPrimitive.content, "CUMULATIVE=2")
            assertEquals("true", sumBody["isMonotonic"]!!.jsonPrimitive.content)
            val dp = (sumBody["dataPoints"] as JsonArray)[0].jsonObject
            assertEquals("7", dp["asInt"]!!.jsonPrimitive.content, "64 位整数用字符串")
            val hist = metricList.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == "env.dur" }
            val histBody = hist["histogram"]!!.jsonObject
            val hdp = (histBody["dataPoints"] as JsonArray)[0].jsonObject
            assertEquals("1", hdp["count"]!!.jsonPrimitive.content)
            assertEquals("0.5", hdp["sum"]!!.jsonPrimitive.content, "sum 是数字不是字符串")
            assertEquals(3, (hdp["bucketCounts"] as JsonArray).size, "2 边界 → 3 桶")
            assertEquals(2, (hdp["explicitBounds"] as JsonArray).size)
            assertTrue(hdp.containsKey("min") && hdp.containsKey("max"), "count>0 时写 min/max")
        } finally {
            registry.close()
        }
    }

    @Test
    fun `close 做最后一次收尾导出，周期线程不再触发`() {
        val exporter = CollectingMetricsExporter()
        val registry = OtelMetrics(exporter, intervalMs = 3_600_000)
        registry.counter("close.calls").add(2)
        registry.close()
        assertEquals(1, exporter.bodies().size, "close 收尾导出一次")
        assertTrue(registry.collectAndExport().not(), "close 后 collectAndExport 返回 false（不再导出）")
    }

    @Test
    fun `周期线程按间隔导出`() {
        val exporter = CollectingMetricsExporter()
        val registry = OtelMetrics(exporter, intervalMs = 50)
        try {
            registry.counter("periodic.calls").add(1)
            val deadline = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < deadline && exporter.bodies().isEmpty()) {
                Thread.sleep(10)
            }
            assertTrue(exporter.bodies().isNotEmpty(), "周期线程应在 3s 内导出")
        } finally {
            registry.close()
        }
    }

    @Test
    fun `文件导出器逐批写 JSONL`() {
        val dir = Files.createTempDirectory("otel-metrics-file")
        val file = dir.resolve("metrics.jsonl").toFile()
        try {
            val registry = OtelMetrics(FileMetricsExporter(file), intervalMs = 3_600_000)
            registry.counter("file.calls").add(1)
            assertTrue(registry.collectAndExport())
            registry.close()
            val lines = file.readLines()
            assertEquals(2, lines.size, "collectAndExport 一次 + close 收尾一次 = 2 行（close 恒发最后一次累计快照）")
            assertTrue(lines.all { it.contains("resourceMetrics") })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ---------------- 5. 环境装配 ----------------

    @Test
    fun `环境装配——METRICS_ENDPOINT 优先于基础端点与文件`() {
        assertNull(OtelMetricsEnvironment.exporterFrom(emptyMap()))

        val direct = assertIs<OtlpHttpMetricsExporter>(
            OtelMetricsEnvironment.exporterFrom(mapOf(OtelMetricsEnvironment.METRICS_ENDPOINT to "http://c:4318/custom/metrics")),
        )
        assertEquals("http://c:4318/custom/metrics", direct.endpoint)

        val fromBase = assertIs<OtlpHttpMetricsExporter>(
            OtelMetricsEnvironment.exporterFrom(mapOf(OtelEnvironment.ENDPOINT to "http://c:4318/")),
        )
        assertEquals("http://c:4318/v1/metrics", fromBase.endpoint, "基础端点追加 /v1/metrics")

        val dir = Files.createTempDirectory("otel-metrics-env")
        try {
            assertIs<FileMetricsExporter>(
                OtelMetricsEnvironment.exporterFrom(mapOf(OtelMetricsEnvironment.METRICS_FILE to dir.resolve("m.jsonl").toString())),
            )
            val registry = assertNotNull(
                OtelMetricsEnvironment.fromEnvironment(
                    mapOf(OtelMetricsEnvironment.METRICS_FILE to dir.resolve("m.jsonl").toString(), OtelEnvironment.SERVICE_NAME to "custom"),
                    serviceVersion = "9.9.9",
                ),
            )
            assertEquals("custom", registry.resource["service.name"])
            assertEquals("9.9.9", registry.resource["service.version"])
            registry.close()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}