package com.stragent.host

import com.stragent.core.observability.CollectingMetricsExporter
import com.stragent.core.observability.MetricInstrumentData
import com.stragent.core.observability.OtelMetrics
import com.stragent.core.observability.StrAgentMetrics
import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.LlmClient
import com.stragent.data.McpTraceContext
import com.stragent.data.abilityTools
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * T-065 · 报告 metrics 埫点接线端到端测试。
 *
 * 证明的是**接线**：报告生成后 `str_agent.report.calls` / `report.duration` /
 * `report.tokens` 按 `engine.id` 入注册表（classic 引擎、FakeLlmClient），
 * 时延直方图 count=1 且值 > 0；收集器收到含指标名的 envelope（形制细节由
 * core 单测钉住）。不证明真实模型 token 规模（与 OtelReportTraceTest 同边界）。
 */
class OtelMetricsWiringTest {

    private val csv = "地区,销售额,订单数\n华东,1000,10\n华北,300,5\n"

    private fun tempHome(): File = Files.createTempDirectory("str-agent-otel-metrics").toFile()

    /**
     * T-080（CI 稳定性）：给生成路径加 25ms（> Windows `currentTimeMillis` 粒度
     * ~15.6ms，T-076 同先例）。快速假模型报告可能整个落在一个时钟刻度内，
     * durationMs=0 让「min>0」断言在 CI 间歇翻红——本变体把断言变成确定性成立。
     */
    private class SlowFakeLlm(private val delegate: FakeLlmClient = FakeLlmClient()) : LlmClient {
        override fun chat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
        ): ChatResult {
            Thread.sleep(25)
            return delegate.chat(model, messages, temperature, maxTokens)
        }

        override fun streamChat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
            onToken: ((String) -> Unit)?,
        ): ChatResult = delegate.streamChat(model, messages, temperature, maxTokens, onToken)
    }

    @Test
    fun `报告生成把调用数、时延与 token 按引擎记入注册表`() {
        val home = tempHome()
        val metrics = OtelMetrics(CollectingMetricsExporter(), intervalMs = 3_600_000)
        try {
            StrAgentHostBootstrap.create(home, llm = SlowFakeLlm()).use { bootstrap ->
                val tools = abilityTools(bootstrap.abilityDatasets) + pluginTools(
                    bootstrap.plugin, bootstrap.abilityDatasets, bootstrap.memory,
                    otelMetrics = metrics,
                )
                val datasetId = (
                    tools.first { it.name == "stragent_import_table" }
                        .invoke(buildJsonObject { put("csv", csv); put("name", "metrics样本") })["datasetId"]
                        as JsonPrimitive
                    ).content

                val receipt = McpTraceContext.with(null) {
                    tools.first { it.name == "stragent_generate_report" }
                        .invoke(buildJsonObject { put("datasetId", datasetId) })
                }
                assertTrue((receipt["report"] as JsonPrimitive).content.isNotEmpty(), "报告真的生成了")

                val calls = assertIs<MetricInstrumentData.Sum>(
                    metrics.snapshot().single { it.name == StrAgentMetrics.REPORT_CALLS },
                )
                assertEquals(1, calls.points.size)
                assertEquals("classic", calls.points.single().attributes["engine.id"])
                assertEquals(1L, calls.points.single().value)

                // D25 可观测性收口：门禁计数随同一次生成入注册表（gate.passed=true/false 两序列有界）
                val gate = assertIs<MetricInstrumentData.Sum>(
                    metrics.snapshot().single { it.name == StrAgentMetrics.GATE_RESULTS },
                )
                assertTrue(
                    gate.points.single().attributes["gate.passed"] == "true",
                    "成功报告的门禁结果应为 passed=true：${gate.points.single().attributes}",
                )

                val duration = assertIs<MetricInstrumentData.Histogram>(
                    metrics.snapshot().single { it.name == StrAgentMetrics.REPORT_DURATION },
                )
                val durPoint = duration.points.single()
                assertEquals(1L, durPoint.count)
                assertTrue(durPoint.min > 0.0, "报告时延应大于 0：${durPoint.min}")

                val tokens = assertIs<MetricInstrumentData.Sum>(
                    metrics.snapshot().single { it.name == StrAgentMetrics.REPORT_TOKENS },
                )
                assertTrue(tokens.points.single().value > 0, "假模型也报 token")

                metrics.collectAndExport()
            }
        } finally {
            metrics.close()
            home.deleteRecursively()
        }
    }
}
