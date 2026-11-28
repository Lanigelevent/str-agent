package com.stragent.data

import com.stragent.core.ports.AgentTool
import com.stragent.core.observability.CollectingMetricsExporter
import com.stragent.core.observability.CollectingSpanExporter
import com.stragent.core.observability.MetricInstrumentData
import com.stragent.core.observability.OtelMetrics
import com.stragent.core.observability.OtelSampler
import com.stragent.core.observability.OtelSpan
import com.stragent.core.observability.OtelSpanContext
import com.stragent.core.observability.OtelTraces
import com.stragent.core.observability.StrAgentMetrics
import com.stragent.core.ports.PrefsStore
import com.stragent.core.trace.InsightTrace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 采样与 metrics 的 MCP 集成测试（T-065）。
 *
 * 钉住的语义：
 *  1. **parent-based 采样**：上游 traceparent flags=00（未采样）→ 本服务的 span 树
 *     照常创建、上下文照传（报告根继承未采样标志），但**全部被导出过滤**——
 *     上游选择不上报，我们不偷偷上报；
 *  2. 新 trace 根 + 比率采样器：ratio=0 一个 span 都不出，ratio=1 全出；
 *  3. **metrics 与追踪相互独立**：只配 metrics 不配 traces，工具调用照常计数记时延；
 *  4. 状态分桶：成功记 `ok`，失败记 `error`（取消的负控见边界说明）。
 */
class McpSamplerTest {

    private class MapPrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    /** 与 McpTraceTest 同形：从线程上下文取上游链路，产生报告 span 树 */
    private class FakeReportTool(
        private val otel: OtelTraces?,
        private val fail: Boolean = false,
    ) : AgentTool {
        override val name = "stragent_fake_report"
        override val description = "测试用"
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
            trace.finish("fake-v1", 42, true)
            otel?.export(trace)
            return buildJsonObject {
                put("traceId", trace.traceId)
                parent?.let { put("parentSampled", it.sampled) }
            }
            // 未采样链路上回执照常带 traceId——采样只影响导出，不影响业务回执
        }
    }

    private val upstreamTrace = "1".repeat(32)
    private val upstreamSpan = "2".repeat(16)
    private val upstreamSampled = "00-$upstreamTrace-$upstreamSpan-01"
    private val upstreamUnsampled = "00-$upstreamTrace-$upstreamSpan-00"

    private fun callParams(traceparent: String?): JsonObject = buildJsonObject {
        put("name", "stragent_fake_report")
        put("arguments", buildJsonObject { })
        put("_meta", buildJsonObject { traceparent?.let { put("traceparent", it) } })
    }

    private fun bridgeWith(
        traces: OtelTraces?,
        metrics: OtelMetrics?,
        sampler: OtelSampler? = null,
        tool: AgentTool? = null,
    ): McpBridge = McpBridge(
        AbilityDatasets(),
        MapPrefs(),
        extraTools = listOfNotNull(tool ?: FakeReportTool(traces)),
        otel = traces,
        metrics = metrics,
        sampler = sampler,
    )

    // ---------------- 1. parent-based 采样 ----------------

    @Test
    fun `上游未采样——span 树照常创建但全部被过滤，业务与回执零变化`() {
        val collector = CollectingSpanExporter()
        val traces = OtelTraces(collector)
        try {
            val tool = FakeReportTool(traces)
            val bridge = bridgeWith(traces, metrics = null, sampler = null, tool = tool)
            val result = bridge.dispatch("tools/call", callParams(upstreamUnsampled), "req-1")
            assertTrue(traces.drain(3_000))

            // 业务照常：回执带 traceId，且工具看到上游上下文的 sampled=false
            val structured = result["structuredContent"]!!.jsonObject
            assertEquals(upstreamTrace, structured["traceId"]!!.jsonPrimitive.content)
            assertEquals("false", structured["parentSampled"]!!.jsonPrimitive.content)
            assertNotNull(tool.sawParent)

            // 导出被过滤：一个 span 都不出
            assertEquals(0, collector.spans().size, "上游未采样 → 整棵 span 树不导出（parent-based）")
        } finally {
            traces.close()
        }
    }

    @Test
    fun `新 trace 根按比率判定——0 全不采、1 全采`() {
        val id = "3".repeat(32)
        // ratio=0：collector 空
        run {
            val collector = CollectingSpanExporter()
            val traces = OtelTraces(collector)
            try {
                val bridge = bridgeWith(traces, metrics = null, sampler = OtelSampler.fromRatio(0.0))
                bridge.dispatch("tools/call", callParams(null), "req-1")
                assertTrue(traces.drain(3_000))
                assertEquals(0, collector.spans().size, "ratio=0：新根不导出")
            } finally {
                traces.close()
            }
        }
        // ratio=1：tools/call + 报告树全出（报告根继承新根的采样决定）
        run {
            val collector = CollectingSpanExporter()
            val traces = OtelTraces(collector)
            try {
                val bridge = bridgeWith(traces, metrics = null, sampler = OtelSampler.fromRatio(1.0))
                bridge.dispatch("tools/call", callParams(null), "req-1")
                assertTrue(traces.drain(3_000))
                val spans = collector.spans()
                assertEquals(3, spans.size, "1 个 tools/call + 1 阶段 + 1 报告根")
                val toolSpan = spans.first { it.name.startsWith("tools/call ") }
                val root = spans.first { it.name == "report.generate" }
                assertEquals(toolSpan.traceId, root.traceId, "同一条 trace")
            } finally {
                traces.close()
            }
        }
    }

    // ---------------- 2. metrics 独立于追踪 ----------------

    @Test
    fun `只配 metrics 不配 traces——工具调用照常计数记时延，无 span 产生`() {
        val metricsExporter = CollectingMetricsExporter()
        val metrics = OtelMetrics(metricsExporter, intervalMs = 3_600_000)
        try {
            val bridge = bridgeWith(traces = null, metrics = metrics)
            bridge.dispatch("tools/call", callParams(null), "req-1")
            bridge.dispatch("tools/call", callParams(null), "req-2")

            val sum = assertIs<MetricInstrumentData.Sum>(
                metrics.snapshot().single { it.name == StrAgentMetrics.TOOL_CALLS },
            )
            // 两次成功调用 → 单 series（tool.name + status=ok）累计 2
            assertEquals(1, sum.points.size)
            val point = sum.points.single()
            assertEquals("stragent_fake_report", point.attributes["tool.name"])
            assertEquals("ok", point.attributes["status"])
            assertEquals(2L, point.value)

            val dur = assertIs<MetricInstrumentData.Histogram>(
                metrics.snapshot().single { it.name == StrAgentMetrics.TOOL_DURATION },
            )
            val durPoint = dur.points.single()
            assertEquals(2L, durPoint.count)
            assertTrue(durPoint.sum >= 0.0)
            assertTrue(durPoint.min >= 0.0, "min 不应是初值 MAX_VALUE")
        } finally {
            metrics.close()
        }
    }

    @Test
    fun `失败调用的 status 记 error 与 ok 分桶`() {
        val metrics = OtelMetrics(CollectingMetricsExporter(), intervalMs = 3_600_000)
        try {
            val okTool = FakeReportTool(null)
            val failTool = FakeReportTool(null, fail = true)
            val bridge1 = bridgeWith(traces = null, metrics = metrics, tool = okTool)
            bridge1.dispatch("tools/call", callParams(null), "req-1")
            val bridge2 = bridgeWith(traces = null, metrics = metrics, tool = failTool)
            try {
                bridge2.dispatch("tools/call", callParams(null), "req-2")
            } catch (_: IllegalStateException) {
                // 预期失败
            }

            val sum = assertIs<MetricInstrumentData.Sum>(
                metrics.snapshot().single { it.name == StrAgentMetrics.TOOL_CALLS },
            )
            assertEquals(2, sum.points.size, "ok 与 error 各一个 series")
            val byStatus = sum.points.associate { it.attributes["status"] to it.value }
            assertEquals(1L, byStatus["ok"])
            assertEquals(1L, byStatus["error"])
        } finally {
            metrics.close()
        }
    }
}
