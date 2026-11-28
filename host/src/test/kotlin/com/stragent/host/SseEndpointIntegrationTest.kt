package com.stragent.host

import com.stragent.core.ports.UiEventBroadcaster
import com.stragent.data.McpBridge
import com.stragent.core.ports.PrefsStore
import com.stragent.data.AbilityDatasets
import kotlinx.serialization.json.JsonObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S4 SSE 端点集成测试（D11 v3 优化轮）。
 *
 * 验证维度：
 *  1. GET /ui 返回 HTML 内容
 *  2. GET /events 建立 SSE 连接并接收 UiEventBroadcaster 事件（T-045 起需 Bearer）
 *  3. ProgressBus.emit → UiEventBroadcaster → SSE 流完整链路
 *
 * 诚实边界：
 *  - 测试用临时端口（start(0)，系统分配）——固定 7787 曾在 CI 上被 TIME_WAIT 残留占用；start()/stop() 生命周期管理
 *  - SSE 流是阻塞的，测试中用独立线程读取
 *  - `/events` 现在按会话过滤：本测试显式订阅 default 会话并用同会话广播
 */
class SseEndpointIntegrationTest {

    private class TestPrefsStore : PrefsStore {
        private val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    @Test
    fun `GET ui 返回 HTML 内容`() {
        val prefs = TestPrefsStore()
        val datasets = AbilityDatasets()
        val bridge = McpBridge(datasets, prefs, webUiHtml = WebUiPage.html)
        try {
            val port = bridge.start(0)
            val conn = URL("http://127.0.0.1:$port/ui").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            assertEquals(200, conn.responseCode)
            assertEquals("text/html", conn.contentType.substringBefore(";").trim())
            val body = conn.inputStream.bufferedReader().readText()
            assertTrue(body.contains("DOCTYPE"), "HTML 应包含 DOCTYPE")
            assertTrue(body.contains("EventSource"), "HTML 应包含 EventSource")
            conn.disconnect()
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `GET events 建立 SSE 连接并接收广播事件`() {
        val prefs = TestPrefsStore()
        val datasets = AbilityDatasets()
        val token = McpBridge.currentToken(prefs)
        val bridge = McpBridge(datasets, prefs, webUiHtml = WebUiPage.html)
        try {
            val port = bridge.start(0)
            // 在独立线程中连接 /events 并读取 SSE 流
            val received = mutableListOf<String>()
            // 订阅就绪以「connected 帧到达」为准（D25 审查：替代固定 sleep——慢环境下
            // sleep(500) 可能让广播早于订阅建立，链路验证静默退化成只验连接可达）
            val connected = CountDownLatch(1)
            val bothEvents = CountDownLatch(2)
            val thread = Thread {
                try {
                    val conn = URL("http://127.0.0.1:$port/events?token=$token&sessionId=default")
                        .openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    val reader = conn.inputStream.bufferedReader()
                    // 读取几行 SSE 数据
                    var lines = 0
                    while (lines < 5) {
                        val line = reader.readLine() ?: break
                        if (line.startsWith("data:")) {
                            val payload = line.removePrefix("data: ").trim()
                            synchronized(received) { received.add(payload) }
                            if (payload.contains("connected")) connected.countDown()
                            if (payload.contains("test_event")) bothEvents.countDown()
                            lines++
                        }
                    }
                    conn.disconnect()
                } catch (_: Exception) {
                    // 连接断开是正常的（stop() 后 reader 退出）
                }
            }
            thread.start()
            // 先等订阅就绪，再广播——顺序由 latch 保证，不靠 sleep
            assertTrue(connected.await(5, TimeUnit.SECONDS), "5s 内应收到 connected 帧（订阅就绪）")
            // 广播事件（与订阅同一会话：T-045 起跨会话不再串流）
            UiEventBroadcaster.broadcast("test_event_1", "default")
            UiEventBroadcaster.broadcast("test_event_2", "default")
            assertTrue(bothEvents.await(5, TimeUnit.SECONDS), "5s 内应收到两条广播事件（链路完整）")
            thread.interrupt()
            thread.join(2000)
            // 断言拆开：connected 与两条广播各自必须到达（原 OR 断言会让广播丢失静默放行）
            val allData = synchronized(received) { received.joinToString("\n") }
            assertTrue(allData.contains("connected"), "应包含 connected 帧: $allData")
            assertTrue(
                allData.contains("test_event_1") && allData.contains("test_event_2"),
                "两条广播都应到达: $allData",
            )
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `GET ui 无 webUiHtml 时端点不存在返回 404`() {
        val prefs = TestPrefsStore()
        val datasets = AbilityDatasets()
        // webUiHtml = null → /ui 端点不注册
        val bridge = McpBridge(datasets, prefs, webUiHtml = null)
        try {
            val port = bridge.start(0)
            val conn = URL("http://127.0.0.1:$port/ui").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            // 没注册 /ui 端点，HttpServer 返回 404
            assertEquals(404, conn.responseCode)
            conn.disconnect()
        } finally {
            bridge.stop()
        }
    }
}
