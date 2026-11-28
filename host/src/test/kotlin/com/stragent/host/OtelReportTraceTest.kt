package com.stragent.host

import com.stragent.core.observability.CollectingSpanExporter
import com.stragent.core.observability.OtelSpan
import com.stragent.core.observability.OtelSpanContext
import com.stragent.core.observability.OtelTraces
import com.stragent.data.McpTraceContext
import com.stragent.data.abilityTools
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * T-056 · 报告生成的 OTel span 树（宿主接线，端到端）。
 *
 * 证明的是**接线**而不是模型质量：
 *  1. `McpTraceContext`（由 MCP 传输层从 `_meta.traceparent` 建立）里的上游 span
 *     真的是报告根 span 的父节点——一条 trace 从客户端连到报告阶段；
 *  2. 根 span 带 `gen_ai.*` 语义与 token 计数，回执带同一个 `traceId`（客户端可对号）；
 *  3. 成本只按**配置的单价**计算并标注口径（未配置不写，见 core 单测）。
 *
 * 不证明：真实模型下的 token 规模与成本精度（需要真实 Key 的抽样验收）。
 */
class OtelReportTraceTest {

    private val csv = "地区,销售额,订单数\n华东,1000,10\n华北,300,5\n"

    private fun tempHome(): File =
        File(System.getProperty("java.io.tmpdir"), "str-agent-otel-${System.nanoTime()}").also { it.mkdirs() }

    @Test
    fun `报告 span 树挂到上游 trace，带 gen_ai token 与配置单价成本`() {
        val home = tempHome()
        val collector = CollectingSpanExporter()
        val traces = OtelTraces(collector)
        try {
            StrAgentHostBootstrap.create(home, llm = FakeLlmClient()).use { bootstrap ->
                val tools = abilityTools(bootstrap.abilityDatasets) + pluginTools(
                    bootstrap.plugin, bootstrap.abilityDatasets, bootstrap.memory,
                    otel = traces, costPer1kTokens = 0.01,
                )
                val datasetId = (
                    tools.first { it.name == "stragent_import_table" }
                        .invoke(buildJsonObject { put("csv", csv); put("name", "otel样本") })["datasetId"]
                        as JsonPrimitive
                    ).content

                val upstream = OtelSpanContext("a".repeat(32), "b".repeat(16))
                val receipt = McpTraceContext.with(upstream) {
                    tools.first { it.name == "stragent_generate_report" }
                        .invoke(buildJsonObject { put("datasetId", datasetId) })
                }
                assertTrue(traces.drain(5_000), "spans 应在超时前导出完")

                val spans = collector.spans()
                val root = spans.single { it.name == "report.generate" }
                assertEquals(upstream.traceId, root.traceId)
                assertEquals(upstream.spanId, root.parentSpanId, "报告根应挂在上游工具的 span 下")
                assertEquals(OtelSpan.Status.OK, root.status)

                val stages = spans.filter { it.name.startsWith("report.stage:") }
                assertTrue(stages.isNotEmpty(), "至少一个阶段 span：$spans")
                stages.forEach { assertEquals(root.spanId, it.parentSpanId) }

                val attrs = root.attributesSnapshot()
                assertEquals("otel样本", attrs["str_agent.dataset.name"])
                assertEquals("generate", attrs["gen_ai.operation.name"])
                assertEquals("str_agent", attrs["gen_ai.system"])
                val tokens = attrs["gen_ai.usage.total_tokens"] as Long
                assertTrue(tokens > 0, "假模型也报 token 数：$attrs")
                val expectedCost = Math.round(tokens / 1000.0 * 0.01 * 1_000_000.0) / 1_000_000.0
                assertEquals(expectedCost, attrs["str_agent.cost.usd"], "成本按 totalTokens × 单价（六位小数）")
                assertEquals("configured price per 1K tokens", attrs["str_agent.cost.basis"])

                // 回执里的 traceId 与导出的 trace 同一条：客户端能拿去 Jaeger/Tempo 对号
                assertEquals(upstream.traceId, (receipt["traceId"] as JsonPrimitive).content)
            }
        } finally {
            traces.close()
            home.deleteRecursively()
        }
    }
}
