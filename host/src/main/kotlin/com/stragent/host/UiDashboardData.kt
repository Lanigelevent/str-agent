package com.stragent.host

import com.stragent.data.UiDataProvider
import com.stragent.core.chart.ChartSeries
import com.stragent.core.chart.ChartSpec
import com.stragent.core.chart.ChartType
import com.stragent.core.chart.SvgChartRenderer
import com.stragent.core.observability.OtelMetrics
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * /ui 驾驶舱数据源（T-070）——宿主侧 [UiDataProvider] 实现。
 *
 * 三个端点的口径：
 *  - `overview`：版本 / 运行时长 / 报告统计（次数、token 合计、P50/P95——
 *    从最近报告环形缓冲取时长做分位数；OTel metrics 注册表接入时以
 *    `str_agent.report.*` 的快照为准并在响应里标注来源）；
 *  - `datasets`：全部数据集（按导入顺序），含落盘状态；
 *  - `reports`：环形缓冲快照（新的在前），正文全文随条目下发（渲染区用）。
 *
 * 线程安全：全部读走快照（AbilityDatasets/RecentReports 各自保证并发安全）。
 */
class UiDashboardData(
    private val datasets: com.stragent.data.AbilityDatasets,
    private val recentReports: RecentReports,
    private val version: String,
    private val otelMetrics: OtelMetrics? = null,
    /** 用量与成本账本（T-171）：null = 不给用量视图（不编造零） */
    private val usageLedger: com.stragent.core.observability.UsageLedger? = null,
    /** 水位线快照提供器（T-174）：null = 不给水位线视图 */
    private val watermarks: (() -> JsonObject)? = null,
    private val startedNanos: Long = System.nanoTime(),
) : UiDataProvider {

    override fun overview(): JsonObject {
        val reports = recentReports.all()
        val durations = reports.map { it.durationMs }.sorted()
        val calls = reports.size.toLong()
        val tokens = reports.sumOf { it.totalTokens.toLong() }
        return buildJsonObject {
            put("version", version)
            put("uptimeMs", (System.nanoTime() - startedNanos) / 1_000_000)
            put("reports", buildJsonObject {
                put("count", calls)
                put("tokensTotal", tokens)
                put("p50Ms", durations.percentile(0.5))
                put("p95Ms", durations.percentile(0.95))
            })
            put("otelMetricsConfigured", otelMetrics != null)
            // T-171：累计用量与成本（金额恒为估算，basis 明写；未配单价则不写金额）
            usageLedger?.let { put("usage", it.snapshot()) }
            // T-174：水位线（每个数字带上限与口径）
            watermarks?.let { put("watermarks", it()) }
        }
    }

    override fun datasets(): JsonObject = buildJsonObject {
        put(
            "items",
            JsonArray(
                datasets.ids().map { id ->
                    val dataset = datasets.byId(id)
                    buildJsonObject {
                        put("id", id)
                        put("name", dataset?.name ?: "")
                        put("rows", dataset?.rows?.size ?: 0)
                        put("columns", dataset?.columns?.size ?: 0)
                        put("truncated", dataset?.truncated ?: false)
                        put("persisted", datasets.persistedState(id) ?: false)
                    }
                },
            ),
        )
    }

    override fun reports(): JsonObject = buildJsonObject {
        put(
            "items",
            JsonArray(
                recentReports.all().map { entry ->
                    buildJsonObject {
                        put("atMs", entry.atMs)
                        put("datasetId", entry.datasetId)
                        put("datasetName", entry.datasetName)
                        put("engineId", entry.engineId)
                        put("totalTokens", entry.totalTokens)
                        put("gateFailed", entry.gateFailed)
                        entry.traceId?.let { put("traceId", it) }
                        put("durationMs", entry.durationMs)
                        put("report", entry.reportText)
                        // T-086：图表按需渲染成离线 SVG（请求时渲染，RecentReport 存数据本体；
                        // 未知类型如实跳过，不渲染占位符占版面）
                        if (entry.charts.isNotEmpty()) {
                            put("charts", JsonArray(entry.charts.mapNotNull { chart ->
                                val type = ChartType.fromId(chart.type) ?: return@mapNotNull null
                                buildJsonObject {
                                    put("title", chart.title)
                                    if (chart.description.isNotEmpty()) put("description", chart.description)
                                    put("type", type.id)
                                    put(
                                        "svg",
                                        SvgChartRenderer.render(
                                            ChartSpec(
                                                type = type,
                                                title = chart.title,
                                                series = listOf(
                                                    ChartSeries(
                                                        name = chart.title.ifEmpty { "series" },
                                                        values = chart.values,
                                                    ),
                                                ),
                                                categories = chart.labels,
                                            ),
                                        ),
                                    )
                                }
                            }))
                        }
                    }
                },
            ),
        )
    }

    /** 最近邻秩分位（ceil）：P95 取上档——驾驶舱是运维视图，宁取保守值 */
    private fun List<Long>.percentile(q: Double): Long {
        if (isEmpty()) return 0
        val index = (Math.ceil(q * size)).toInt().coerceIn(1, size) - 1
        return this[index]
    }
}
