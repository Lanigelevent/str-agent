package com.stragent.host

import com.stragent.core.ports.UiEventBroadcaster
import com.stragent.core.ports.NotebookSerializer
import com.stragent.core.ports.NotebookSerializer.NotebookCell
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * S4 Web UI 测试（D11 v3）。
 */
class WebUiTest {

    @Test
    fun `UiEventBroadcaster 订阅后收到事件`() {
        var received: String? = null
        val unsubscribe = UiEventBroadcaster.subscribe { msg -> received = msg }
        try {
            UiEventBroadcaster.broadcast("test event")
            assertNotNull(received)
            assertTrue(received!!.contains("test event"))
        } finally {
            unsubscribe()
        }
    }

    @Test
    fun `无订阅者时广播不报错`() {
        UiEventBroadcaster.broadcast("nobody listening")
    }

    @Test
    fun `取消订阅后不再收到新事件`() {
        var received: String? = null
        val unsubscribe = UiEventBroadcaster.subscribe { msg -> received = msg }
        unsubscribe()
        val before = received
        UiEventBroadcaster.broadcast("after unsubscribe")
        assertTrue(received == before, "should not receive new events after unsubscribe")
    }

    @Test
    fun `NotebookSerializer 序列化工具调用轨迹`() {
        val cells = listOf(
            NotebookCell(
                tool = "stragent_import_table",
                args = buildJsonObject {
                    put("csv", "a,b\n1,2")
                    put("name", "test")
                },
                result = buildJsonObject { put("datasetId", JsonPrimitive("ds_abc")) },
                durationMs = 50,
            ),
            NotebookCell(
                tool = "stragent_render_chart",
                args = buildJsonObject {
                    put("chartType", JsonPrimitive("bar"))
                    put("valueColumn", JsonPrimitive("b"))
                },
                result = null,
                durationMs = 30,
            ),
        )
        val notebook = NotebookSerializer.fromTrace("test analysis", cells)
        val json = notebook.toJson()
        assertEquals(4, json["nbformat"]!!.jsonPrimitive.content.toInt())
        val cellsJson = json["cells"]!!.jsonArray
        assertEquals(2, cellsJson.size)
        assertEquals("stragent_import_table", cellsJson[0].jsonObject["tool"]!!.jsonPrimitive.content)
        assertEquals("tool_call", cellsJson[0].jsonObject["cell_type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `NotebookSerializer 从 ProvenanceSteps 创建`() {
        val steps = listOf(
            com.stragent.core.trace.ProvenanceStep(
                tool = "stragent_import_table",
                args = buildJsonObject { put("name", JsonPrimitive("data")) },
                argsHash = "abc123",
                resultHash = "def456",
                datasetHash = "ghi789",
                elapsedMs = 100,
            ),
        )
        val notebook = NotebookSerializer.fromProvenanceSteps("replay test", steps)
        val json = notebook.toJson()
        assertEquals(1, json["cells"]!!.jsonArray.size)
        assertEquals("stragent_import_table", json["cells"]!!.jsonArray[0].jsonObject["tool"]!!.jsonPrimitive.content)
    }

    @Test
    fun `WebUiPage HTML 从资源加载`() {
        val html = WebUiPage.html
        assertTrue(html.isNotEmpty(), "HTML should not be empty")
        assertTrue(html.contains("DOCTYPE"), "should contain DOCTYPE")
    }

    @Test
    fun `WebUiPage HTML 包含 SSE 和事件连接`() {
        val html = WebUiPage.html
        assertTrue(html.contains("EventSource"), "should contain EventSource")
        assertTrue(html.contains("/events"), "should connect to /events")
    }

    @Test
    fun `WebUiPage 驾驶舱四区齐备且零外部依赖`() {
        val html = WebUiPage.html
        // 四区标签（T-070）
        listOf("tab-overview", "tab-datasets", "tab-reports", "tab-events").forEach { id ->
            assertTrue(html.contains(id), "驾驶舱缺分区：$id")
        }
        // 数据端点接通（页面按名拼接：/ui/data/<name>）
        assertTrue(html.contains("/ui/data/"), "总览必须读数据端点")
        // 动效与可达性纪律
        assertTrue(html.contains("prefers-reduced-motion"), "入场动画必须可关")
        assertTrue(html.contains("prefers-color-scheme"), "暗色主题跟随系统")
    }

    @Test
    fun `WebUiPage 零外部资源——离线环境完整可用（D15 校正的最终收口）`() {
        val html = WebUiPage.html
        assertTrue(!html.contains("cdn."), "不得引用 CDN")
        assertTrue(!html.contains("https://"), "不得有外部链接")
        assertTrue(!html.contains("http://"), "不得有外部链接")
    }
}
