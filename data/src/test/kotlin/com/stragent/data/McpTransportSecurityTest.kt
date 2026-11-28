package com.stragent.data

import com.stragent.core.ports.UiEventBroadcaster
import com.stragent.core.ports.PrefsStore
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.stragent.core.i18n.Messages

/**
 * 传输层安全集成测试（T-045 / AD-029 / D17 G-02、G-04）。
 *
 * 全部走**真 loopback HTTP**（`start(0)` 让系统分配端口），不用 mock：
 *  ① `/events` 无 token → 401；带 Bearer 或 `?token=` 才能建流；
 *  ② 事件按会话隔离：A 会话的事件不会出现在 B 的流；
 *  ③ SSE 连接上限：第 9 条 429（Retry-After），同时 `/mcp` 仍可用；
 *  ④ 2 条 SSE 下 `/mcp initialize` p95 < 1s（长连接不饿死短请求）；
 *  ⑤ 限流在鉴权之后：401 不消耗令牌桶；耗尽时 429 带 Retry-After；
 *  ⑥ 远程绑定默认开启限流、回环默认关闭（AD-029 的「默认安全」）。
 */
class McpTransportSecurityTest {

    private class MapPrefs : PrefsStore {
        private val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private data class HttpResult(val status: Int, val retryAfter: String?)

    private val initBody =
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}"""

    private fun postInitialize(port: Int, token: String?): HttpResult {
        val conn = URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 3_000
        conn.readTimeout = 3_000
        conn.setRequestProperty("Content-Type", "application/json")
        if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
        conn.doOutput = true
        conn.outputStream.use { it.write(initBody.toByteArray(Charsets.UTF_8)) }
        val status = conn.responseCode
        val retryAfter = conn.getHeaderField("Retry-After")
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        stream?.use { it.readBytes() }
        conn.disconnect()
        return HttpResult(status, retryAfter)
    }

    private class Sse(val conn: HttpURLConnection, val reader: BufferedReader) {
        /** 读最多 [maxLines] 条 `data:` 行；静默期到时返回（SSE 无 EOF） */
        fun readDataLines(maxLines: Int, timeoutMs: Int): List<String> {
            conn.readTimeout = timeoutMs
            val lines = mutableListOf<String>()
            try {
                while (lines.size < maxLines) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith("data:")) lines.add(line.removePrefix("data:").trim())
                }
            } catch (_: SocketTimeoutException) {
                // 预期：没有更多事件
            }
            return lines
        }

        fun close() {
            runCatching { reader.close() }
            runCatching { conn.disconnect() }
        }
    }

    /** 建一条 SSE 流并读掉 connected 确认帧（读到=订阅已注册） */
    private fun openSse(port: Int, token: String, sessionId: String?): Sse {
        val url = buildString {
            append("http://127.0.0.1:").append(port).append("/events?token=").append(token)
            if (sessionId != null) append("&sessionId=").append(sessionId)
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 3_000
        conn.readTimeout = 3_000
        assertEquals(200, conn.responseCode, "SSE 应建立成功")
        val reader = conn.inputStream.bufferedReader()
        val first = reader.readLine()
        assertTrue(first != null && first.startsWith("data:") && first.contains("connected"),
            "首帧应为 connected：$first")
        return Sse(conn, reader)
    }

    private fun bridgeWithHtml(prefs: PrefsStore): McpBridge =
        McpBridge(AbilityDatasets(), prefs, webUiHtml = "<html><body>ui</body></html>")

    @Test
    fun `events 无 token 返回 401，带 token 才能建流`() {
        val prefs = MapPrefs()
        val token = McpBridge.currentToken(prefs)
        val bridge = bridgeWithHtml(prefs)
        val port = bridge.start(0)
        try {
            val none = URL("http://127.0.0.1:$port/events").openConnection() as HttpURLConnection
            none.requestMethod = "GET"
            assertEquals(401, none.responseCode)
            none.errorStream?.use { it.readBytes() }
            none.disconnect()

            val wrong = URL("http://127.0.0.1:$port/events?token=deadbeefdeadbeef").openConnection() as HttpURLConnection
            wrong.requestMethod = "GET"
            assertEquals(401, wrong.responseCode)
            wrong.errorStream?.use { it.readBytes() }
            wrong.disconnect()

            // 浏览器路径：?token=；同时证明 header 路径也可用（这里用 header）
            val ok = URL("http://127.0.0.1:$port/events?sessionId=default").openConnection() as HttpURLConnection
            ok.requestMethod = "GET"
            ok.setRequestProperty("Authorization", "Bearer $token")
            assertEquals(200, ok.responseCode)
            val first = ok.inputStream.bufferedReader().readLine()
            assertTrue(first != null && first.contains("connected"), "header 鉴权也应建流：$first")
            ok.disconnect()
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `会话隔离——A 会话事件不出现在 B 的流，无会话事件也不串流`() {
        val prefs = MapPrefs()
        val token = McpBridge.currentToken(prefs)
        val bridge = bridgeWithHtml(prefs)
        val port = bridge.start(0)
        var a: Sse? = null
        var b: Sse? = null
        try {
            a = openSse(port, token, "sess-a")
            b = openSse(port, token, "sess-b")

            UiEventBroadcaster.broadcast("A-only-body", "sess-a")
            UiEventBroadcaster.broadcast("B-only-body", "sess-b")
            UiEventBroadcaster.broadcast("no-session-body", null)

            val aLines = a.readDataLines(maxLines = 2, timeoutMs = 1_200).joinToString("\n")
            val bLines = b.readDataLines(maxLines = 2, timeoutMs = 1_200).joinToString("\n")

            assertTrue(aLines.contains("A-only-body"), "A 应收到自己的事件：$aLines")
            assertTrue(!aLines.contains("B-only-body"), "A 不应收到 B 的事件：$aLines")
            assertTrue(!aLines.contains("no-session-body"), "A 不应收到无会话事件：$aLines")

            assertTrue(bLines.contains("B-only-body"), "B 应收到自己的事件：$bLines")
            assertTrue(!bLines.contains("A-only-body"), "B 不应收到 A 的事件：$bLines")
            assertTrue(!bLines.contains("no-session-body"), "B 不应收到无会话事件：$bLines")
        } finally {
            a?.close()
            b?.close()
            bridge.stop()
        }
    }

    @Test
    fun `SSE 连接上限——第 9 条 429，且 mcp 主链仍可用`() {
        val prefs = MapPrefs()
        val token = McpBridge.currentToken(prefs)
        val bridge = bridgeWithHtml(prefs)
        val port = bridge.start(0)
        val streams = mutableListOf<Sse>()
        try {
            repeat(McpBridge.MAX_SSE_CONNECTIONS) { index ->
                streams += openSse(port, token, "cap-session-$index")
            }
            assertEquals(McpBridge.MAX_SSE_CONNECTIONS, bridge.activeSseConnections())

            val rejected = URL("http://127.0.0.1:$port/events?token=$token&sessionId=cap-overflow")
                .openConnection() as HttpURLConnection
            rejected.requestMethod = "GET"
            assertEquals(429, rejected.responseCode)
            assertEquals("1", rejected.getHeaderField("Retry-After"))
            rejected.errorStream?.use { it.readBytes() }
            rejected.disconnect()

            assertEquals(1, bridge.rejectedSseConnections())
            // 连接额度满不影响 /mcp（长连接不占用短请求的通行能力）
            assertEquals(200, postInitialize(port, token).status)
        } finally {
            streams.forEach { it.close() }
            bridge.stop()
        }
    }

    @Test
    fun `两条 SSE 下 mcp initialize 的 p95 小于 1 秒`() {
        val prefs = MapPrefs()
        val token = McpBridge.currentToken(prefs)
        val bridge = bridgeWithHtml(prefs)
        val port = bridge.start(0)
        var a: Sse? = null
        var b: Sse? = null
        try {
            a = openSse(port, token, "latency-session")
            b = openSse(port, token, "latency-session")

            val durations = mutableListOf<Long>()
            repeat(20) {
                val started = System.nanoTime()
                val result = postInitialize(port, token)
                durations += (System.nanoTime() - started) / 1_000_000
                assertEquals(200, result.status)
            }
            val sorted = durations.sorted()
            val p95 = sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.size - 1)]
            println("2 条 SSE 下 /mcp initialize：p95=${p95}ms median=${sorted[sorted.size / 2]}ms 样本=$sorted")
            assertTrue(p95 < 1_000, "2 条 SSE 下 initialize p95 应 <1s，实测 p95=${p95}ms 样本=$sorted")
        } finally {
            a?.close()
            b?.close()
            bridge.stop()
        }
    }

    @Test
    fun `限流在鉴权之后——401 不消耗令牌桶，耗尽时 429 带 Retry-After`() {
        val prefs = MapPrefs()
        val token = McpBridge.currentToken(prefs)
        // 容量 1、不补充：把「是否消耗」放大成二元可见
        val limiter = RateLimiter(capacity = 1, refillRatePerSecond = 0.0)
        val bridge = McpBridge(AbilityDatasets(), prefs, rateLimiter = limiter)
        val port = bridge.start(0)
        try {
            assertEquals(401, postInitialize(port, null).status)
            assertEquals(1, limiter.availableTokens(), "未鉴权请求不得消耗令牌")
            assertEquals(401, postInitialize(port, "wrong").status)
            assertEquals(1, limiter.availableTokens(), "错误 token 不得消耗令牌")

            assertEquals(200, postInitialize(port, token).status)
            assertEquals(0, limiter.availableTokens(), "鉴权通过的请求消耗 1 个令牌")

            val limited = postInitialize(port, token)
            assertEquals(429, limited.status)
            assertEquals("1", limited.retryAfter, "429 必须带 Retry-After")
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `远程绑定默认开启限流、回环默认关闭`() {
        assertNull(McpBridge.defaultRateLimiterFor("127.0.0.1"))
        assertNull(McpBridge.defaultRateLimiterFor("localhost"))
        assertNull(McpBridge.defaultRateLimiterFor("::1"))
        assertNotNull(McpBridge.defaultRateLimiterFor("0.0.0.0"))
        assertNotNull(McpBridge.defaultRateLimiterFor("192.168.1.20"))
        // 远程默认：burst 20（10 req/s 补充）
        assertEquals(20, McpBridge.defaultRateLimiterFor("0.0.0.0")!!.availableTokens())
    }

    @Test
    fun `请求体超过 8MB 返回 413——分块上传走有界读取`() {
        val prefs = MapPrefs()
        val token = McpBridge.currentToken(prefs)
        val bridge = bridgeWithHtml(prefs)
        val port = bridge.start(0)
        try {
            val conn = URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            // 分块发送（不带 Content-Length）：服务端只消费 8MB+1 就应答，不把整段读进内存
            conn.setChunkedStreamingMode(64 * 1024)
            conn.doOutput = true
            val total = McpBridge.MAX_REQUEST_BODY_BYTES + 1
            // 服务端读满上限即回 413，终止块的写可能撞上已关闭的连接——响应才是断言对象
            runCatching {
                conn.outputStream.use { out ->
                    val chunk = ByteArray(64 * 1024) { 'a'.code.toByte() }
                    var written = 0
                    while (written < total) {
                        val n = minOf(chunk.size, total - written)
                        out.write(chunk, 0, n)
                        written += n
                    }
                }
            }
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            assertEquals(413, status, "超过 8MB 应返回 413：$body")
            assertTrue(body.contains(Messages.get("error.request_too_large")), "错误体应说明上限：$body")
            conn.disconnect()
            // 超限请求被拒后 /mcp 主链仍可用
            assertEquals(200, postInitialize(port, token).status)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `声明 Content-Length 超限立即 413——不等 body 上传`() {
        val prefs = MapPrefs()
        val token = McpBridge.currentToken(prefs)
        val bridge = bridgeWithHtml(prefs)
        val port = bridge.start(0)
        try {
            // 只发请求头、不发 body：若实现先读 body 再判断，这里会读超时
            java.net.Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5_000
                val out = socket.getOutputStream()
                out.write(
                    (
                        "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
                            "Authorization: Bearer $token\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${McpBridge.MAX_REQUEST_BODY_BYTES + 1}\r\n\r\n"
                        ).toByteArray(Charsets.US_ASCII),
                )
                out.flush()
                val statusLine = socket.getInputStream().bufferedReader().readLine()
                assertTrue(statusLine != null && statusLine.contains(" 413 "), "声明超限应快速 413：$statusLine")
            }
            assertEquals(200, postInitialize(port, token).status, "拒绝后 /mcp 仍应可用")
        } finally {
            bridge.stop()
        }
    }
}
