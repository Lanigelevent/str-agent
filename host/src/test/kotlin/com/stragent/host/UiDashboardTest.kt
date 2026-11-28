package com.stragent.host

import com.stragent.core.observability.OtelMetrics
import com.stragent.data.AbilityDatasets
import com.stragent.data.McpBridge
import com.stragent.core.ports.PrefsStore
import com.stragent.data.StoredChart
import com.stragent.data.UiDataProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * /ui 驾驶舱数据链路测试（T-070）。
 *
 * 钉住的口径：
 *  1. RecentReports 环形缓冲：容量 10、新的在前、并发 record 不丢；
 *  2. UiDashboardData 三端点形状：overview（版本/运行时长/P50/P95）、datasets、reports；
 *  3. **数据端点必须鉴权**：无 token 401、带 `?token=` 200（与 /events 同一套）；
 *  4. 端到端：报告工具写入环形缓冲后，reports 端点能看到该报告（引擎/token/正文）。
 */
class UiDashboardTest {

    private class MapPrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    // ---------------- 1. 环形缓冲 ----------------

    @Test
    fun `RecentReports 容量 10——新的在前，最旧被挤掉`() {
        val ring = RecentReports(capacity = 10)
        repeat(12) { i ->
            ring.record(
                RecentReport(
                    atMs = i.toLong(), datasetId = "ds_$i", datasetName = "数据$i",
                    engineId = "classic", totalTokens = i, gateFailed = false,
                    traceId = null, reportText = "报告$i", durationMs = i.toLong(),
                ),
            )
        }
        assertEquals(10, ring.size())
        val all = ring.all()
        assertEquals("数据11", all.first().datasetName, "新的在前")
        assertEquals("数据2", all.last().datasetName, "最旧被挤掉")
    }

    // ---------------- 1.5 durationP50（T-082 估计源） ----------------

    @Test
    fun `durationP50——空环 null、奇偶样本最近邻秩取上档（T-082）`() {
        val empty = RecentReports()
        assertNull(empty.durationP50(), "空环如实返回 null（调用方不发估计，不编造）")

        val ring = RecentReports()
        listOf(400L, 100L, 300L, 200L).forEachIndexed { i, d ->
            ring.record(RecentReport(i.toLong(), "ds_$i", "数据$i", "classic", 10, false, null, "r", d))
        }
        // 最近邻秩 ceil(0.5*4)=2 → index 1 → 排序后第 2 个 = 200
        assertEquals(200L, ring.durationP50())

        val odd = RecentReports()
        listOf(300L, 100L, 200L).forEachIndexed { i, d ->
            odd.record(RecentReport(i.toLong(), "ds_$i", "数据$i", "classic", 10, false, null, "r", d))
        }
        // ceil(0.5*3)=2 → index 1 → 200
        assertEquals(200L, odd.durationP50())
    }

    // ---------------- 2. 数据形状 ----------------

    @Test
    fun `overview 形状——版本、运行时长与报告分位数`() {
        val ring = RecentReports()
        val durations = listOf(100L, 200L, 300L, 400L)
        durations.forEachIndexed { i, d ->
            ring.record(RecentReport(i.toLong(), "ds_$i", "数据$i", "classic", 10, false, null, "r", d))
        }
        val provider = UiDashboardData(AbilityDatasets(), ring, "1.1.0")
        val ov = provider.overview()
        assertEquals("1.1.0", ov["version"]!!.jsonPrimitive.content)
        val reports = ov["reports"]!!.jsonObject
        assertEquals(4, reports["count"]!!.jsonPrimitive.content.toInt())
        assertEquals(40, reports["tokensTotal"]!!.jsonPrimitive.content.toInt())
        assertTrue(reports["p50Ms"]!!.jsonPrimitive.content.toLong() >= 100, "P50 在样本区间内")
        assertEquals(400, reports["p95Ms"]!!.jsonPrimitive.content.toLong(), "P95 应取到最大档")
        assertTrue(ov["uptimeMs"]!!.jsonPrimitive.content.toLong() >= 0)
    }

    @Test
    fun `datasets 与 reports 形状——清单与环形快照`() {
        val datasets = AbilityDatasets()
        val ring = RecentReports()
        val provider = UiDashboardData(datasets, ring, "1.1.0")
        // 空：items 恒输出（空数组 = 没有数据，不是没扫）
        assertEquals(0, (provider.datasets()["items"] as JsonArray).size)
        assertEquals(0, (provider.reports()["items"] as JsonArray).size)

        datasets.put("ds_a", com.stragent.core.dataset.Dataset(
            name = "门店", importedAt = 0L,
            columns = listOf("商品", "销售额"),
            rows = listOf(listOf("保温杯", "100")),
            columnTypes = listOf(
                com.stragent.core.csv.ColumnType.TEXT,
                com.stragent.core.csv.ColumnType.NUMBER,
            ),
        ))
        ring.record(RecentReport(1L, "ds_a", "门店", "classic", 42, false, "abc", "正文", 5L))
        val ds = (provider.datasets()["items"] as JsonArray)[0].jsonObject
        assertEquals("门店", ds["name"]!!.jsonPrimitive.content)
        assertEquals(1, ds["rows"]!!.jsonPrimitive.content.toInt())
        val rep = (provider.reports()["items"] as JsonArray)[0].jsonObject
        assertEquals("classic", rep["engineId"]!!.jsonPrimitive.content)
        assertEquals("正文", rep["report"]!!.jsonPrimitive.content)
        assertEquals("abc", rep["traceId"]!!.jsonPrimitive.content)
        assertTrue(rep["charts"] == null, "无图表的报告不应带 charts 键：$rep")
    }

    @Test
    fun `reports 端点图表渲染——SVG 带出、未知类型跳过、标题转义（T-086）`() {
        val ring = RecentReports()
        ring.record(
            RecentReport(
                1L, "ds_a", "门店", "classic", 42, false, null, "正文", 5L,
                charts = listOf(
                    StoredChart(
                        type = "bar", title = "城市销量<script>alert(1)</script>",
                        labels = listOf("北京", "上海"), values = listOf(120.0, 80.0),
                        description = "按城市汇总",
                    ),
                    StoredChart(type = "treemap", title = "未知类型", labels = listOf("x"), values = listOf(1.0)),
                ),
            ),
        )
        val provider = UiDashboardData(AbilityDatasets(), ring, "1.1.0")
        val rep = (provider.reports()["items"] as JsonArray)[0].jsonObject
        val charts = rep["charts"] as JsonArray
        assertEquals(1, charts.size, "未知类型应如实跳过（不是渲染占位符占版面）：$charts")
        val chart = charts[0].jsonObject
        assertEquals("bar", chart["type"]!!.jsonPrimitive.content)
        assertEquals("按城市汇总", chart["description"]!!.jsonPrimitive.content)
        val svg = chart["svg"]!!.jsonPrimitive.content
        assertTrue(svg.startsWith("<svg"), "应产出离线 SVG：${svg.take(120)}")
        assertTrue(svg.contains("&lt;script&gt;"), "标题必须经 XML 转义（注入安全）：$svg")
        assertTrue(!svg.contains("<script>"), "SVG 里不得出现未转义 script 标签")
        assertTrue(svg.contains("120"), "数值面应进入 SVG（柱高/标注）：${svg.take(200)}")
    }

    // ---------------- 3. HTTP 端点鉴权（与 /events 同一套） ----------------

    /** 只挂数据端点的最小桥（webUiHtml 触发 /ui 路由注册；provider 提供数据） */
    private class FixedProvider : UiDataProvider {
        override fun overview(): JsonObject = buildJsonObject { put("version", "test") }
        override fun datasets(): JsonObject = buildJsonObject {}
        override fun reports(): JsonObject = buildJsonObject {}
    }

    private fun httpGet(url: String): Pair<Int, String> {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "GET"
        val status = conn.responseCode
        val stream = if (status >= 400) conn.errorStream else conn.inputStream
        val body = stream?.readBytes()?.decodeToString() ?: ""
        conn.disconnect()
        return status to body
    }

    @Test
    fun `数据端点必须鉴权——无 token 401，带 token 200`() {
        val prefs = MapPrefs()
        val bridge = McpBridge(
            AbilityDatasets(),
            prefs,
            webUiHtml = "<html>dashboard</html>",
            uiDataProvider = FixedProvider(),
        )
        val port = bridge.start(0)
        try {
            val (noAuth, _) = httpGet("http://127.0.0.1:$port/ui/data/overview")
            assertEquals(401, noAuth, "数据端点无 token 必须 401")
            val token = McpBridge.currentToken(prefs)
            val (ok, body) = httpGet("http://127.0.0.1:$port/ui/data/overview?token=$token")
            assertEquals(200, ok)
            assertTrue(body.contains("test"), "应返回 provider 数据：$body")
        } finally {
            bridge.stop()
        }
    }
}
