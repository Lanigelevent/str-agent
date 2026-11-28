package com.stragent.data

import com.stragent.core.ports.AgentCancelled
import com.stragent.core.ports.AgentTool
import com.stragent.core.ai.GenerationCancelled
import com.stragent.core.ports.PrefsStore
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * MCP 协作式取消（`notifications/cancelled`）测试。
 *
 * 分两层：
 *   ① 注册表单测——令牌按「会话 + JSON-RPC id」隔离，signal 可跨线程读；
 *   ② 真传输集成——HTTP 在途 tools/call 收到取消通知后，工具在下一个可停点退出，
 *      客户端拿到 -32800；stdio 用真管道验证「取消通知由读线程就地处理」
 *      （旧实现单线程顺序处理，取消永远排在它要取消的请求后面）；未取消/别的会话不受影响；
 *      取消后注册表清理干净。
 *
 * 用自造工具（自己盯着 signal 退出）而不是真报告链：要验的是取消语义与帧形制，
 * 不依赖模型输出。
 */
class McpRequestCancellationTest {

    private class MapPrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    // ---- ① 注册表 ----

    @Test
    fun `注册表——不在请求内时 signal 恒 false`() {
        assertFalse(McpRequestCancellation.signal().invoke())
        assertFalse(McpRequestCancellation.isCancelled())
    }

    @Test
    fun `注册表——同会话同 id 才命中，块结束即清理`() {
        val seen = CountDownLatch(1)
        var insideSignal = true
        val t = thread {
            McpRequestCancellation.withRequest("s1", "42") {
                insideSignal = McpRequestCancellation.signal().invoke()
                seen.countDown()
            }
        }
        assertTrue(seen.await(2, TimeUnit.SECONDS))
        t.join(2000)
        assertFalse(insideSignal, "块内未取消应为 false")
        // 块结束后令牌已清理：既不能取消，也不会误伤下一条同 id 请求
        assertFalse(McpRequestCancellation.cancel("s1", "42"))
    }

    @Test
    fun `注册表——session 隔离，跨线程 signal 可见`() {
        val ready = CountDownLatch(1)
        val cancelledSeen = CountDownLatch(1)
        var otherSeen = false
        val t = thread {
            McpRequestCancellation.withRequest("A", "7") {
                val sig = McpRequestCancellation.signal()
                ready.countDown()
                // 模拟长任务的可停点：每 5ms 查一次
                while (!sig()) Thread.sleep(5)
                cancelledSeen.countDown()
            }
        }
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        // 别的会话用同一个 id 取消：不该命中
        assertFalse(McpRequestCancellation.cancel("B", "7"))
        otherSeen = McpRequestCancellation.signal().invoke()
        assertFalse(otherSeen)
        // 本会话取消：命中，工作线程在下一个可停点退出
        assertTrue(McpRequestCancellation.cancel("A", "7"))
        assertTrue(cancelledSeen.await(2, TimeUnit.SECONDS))
        t.join(2000)
    }

    // ---- ② 真 HTTP ----

    /** 阻塞到被取消或测试放行：取消时抛 McpRequestCancelledException（真实工具的同款做法） */
    private class SlowProbeTool(
        private val started: CountDownLatch,
        private val release: CountDownLatch,
    ) : AgentTool {
        override val name = "slow_probe"
        override val description = "测试用：阻塞直到取消或放行"
        override val parametersSchema = buildJsonObject { put("type", "object") }
        override val cacheable = false

        override fun invoke(args: JsonObject): JsonObject {
            if (args["block"]?.jsonPrimitive?.content == "false") {
                return buildJsonObject { put("ok", true) }
            }
            val signal = McpRequestCancellation.signal()
            started.countDown()
            // 长任务的可停点：真实报告在每个章/步边界查，这里 5ms 一查
            while (!release.await(5, TimeUnit.MILLISECONDS)) {
                if (signal()) throw McpRequestCancelledException("测试取消")
            }
            return buildJsonObject { put("ok", true) }
        }
    }

    private class ThrowingTool(
        override val name: String,
        private val error: Throwable,
    ) : AgentTool {
        override val description = "测试用：直接抛指定的取消异常"
        override val parametersSchema = buildJsonObject { put("type", "object") }
        override val cacheable = false
        override fun invoke(args: JsonObject): JsonObject = throw error
    }

    private class CallOutcome {
        @Volatile var status: Int = -1
        @Volatile var text: String = ""
        @Volatile var contentType: String = ""
    }

    private fun startCall(
        port: Int,
        token: String,
        session: String?,
        body: String,
        accept: String? = null,
    ): Pair<Thread, CallOutcome> {
        val outcome = CallOutcome()
        val t = thread(name = "test-http-client") {
            val conn = URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (accept != null) conn.setRequestProperty("Accept", accept)
            if (session != null) conn.setRequestProperty(McpBridge.SESSION_HEADER, session)
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            outcome.status = conn.responseCode
            outcome.contentType = conn.contentType ?: ""
            outcome.text = conn.inputStream.readBytes().decodeToString()
            conn.disconnect()
        }
        return t to outcome
    }

    private fun notifyCancelled(port: Int, token: String, session: String?, requestId: Int) {
        val conn = URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $token")
        if (session != null) conn.setRequestProperty(McpBridge.SESSION_HEADER, session)
        conn.doOutput = true
        val body = """{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":$requestId}}"""
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        // 通知不回响应体（MCP 语义）：204 且 body 为空——多写一个 `{}` 会被严格客户端当成回执
        assertEquals(204, conn.responseCode)
        assertEquals("", conn.inputStream.readBytes().decodeToString(), "204 响应不应带 body")
        conn.disconnect()
    }

    private fun callBody(id: Int, block: Boolean) =
        """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"slow_probe","arguments":{"block":"$block"}}}"""

    @Test
    fun `取消在途调用——工具在可停点退出并回 -32800，之后同会话照常可用`() {
        val prefs = MapPrefs()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bridge = McpBridge(AbilityDatasets(), prefs, extraTools = listOf(SlowProbeTool(started, release)))
        val token = McpBridge.currentToken(prefs)
        val port = bridge.start(0)
        try {
            val (worker, outcome) = startCall(port, token, "s1", callBody(42, block = true))
            assertTrue(started.await(2, TimeUnit.SECONDS), "工具应已开始执行")
            notifyCancelled(port, token, "s1", 42)
            worker.join(3000)
            assertFalse(worker.isAlive, "被取消的调用应尽快返回")
            assertEquals(200, outcome.status)
            val error = Json.parseToJsonElement(outcome.text).jsonObject["error"]!!.jsonObject
            assertEquals(-32800, error["code"]!!.jsonPrimitive.content.toInt(), "取消应回 JSON-RPC -32800：${outcome.text}")
            assertEquals(42, Json.parseToJsonElement(outcome.text).jsonObject["id"]!!.jsonPrimitive.content.toInt())

            // 注册表已清理：同一会话再来一次（不阻塞）正常完成，不会带着旧取消标志
            val (second, secondOutcome) = startCall(port, token, "s1", callBody(43, block = false))
            second.join(3000)
            assertEquals(200, secondOutcome.status)
            val text = Json.parseToJsonElement(secondOutcome.text).jsonObject["result"]!!.jsonObject
            assertNotNull(text["content"], "取消之后的新调用应正常返回：${secondOutcome.text}")
        } finally {
            release.countDown()
            bridge.stop()
        }
    }

    @Test
    fun `取消按会话隔离——别的会话同 id 取消不生效`() {
        val prefs = MapPrefs()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bridge = McpBridge(AbilityDatasets(), prefs, extraTools = listOf(SlowProbeTool(started, release)))
        val token = McpBridge.currentToken(prefs)
        val port = bridge.start(0)
        try {
            val (worker, outcome) = startCall(port, token, "session-A", callBody(7, block = true))
            assertTrue(started.await(2, TimeUnit.SECONDS), "工具应已开始执行")
            notifyCancelled(port, token, "session-B", 7)
            Thread.sleep(300)
            assertTrue(worker.isAlive, "别的会话取消不该命中在途请求")
            release.countDown()
            worker.join(3000)
            assertEquals(200, outcome.status)
            val root = Json.parseToJsonElement(outcome.text).jsonObject
            val text = root["result"]!!.jsonObject["content"]!!.jsonArray.first()
                .jsonObject["text"]!!.jsonPrimitive.content
            assertTrue(text.contains("ok"), "未被取消应正常返回：${outcome.text}")
        } finally {
            release.countDown()
            bridge.stop()
        }
    }

    @Test
    fun `SSE 传输下的取消——同一条流上的错误帧也是 -32800`() {
        val prefs = MapPrefs()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bridge = McpBridge(AbilityDatasets(), prefs, extraTools = listOf(SlowProbeTool(started, release)))
        val token = McpBridge.currentToken(prefs)
        val port = bridge.start(0)
        try {
            val body = """{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"slow_probe",""" +
                """"arguments":{"block":"true"},"_meta":{"progressToken":"p1"}}}"""
            val (worker, outcome) = startCall(port, token, "sse", body, accept = "text/event-stream")
            assertTrue(started.await(2, TimeUnit.SECONDS), "工具应已开始执行")
            notifyCancelled(port, token, "sse", 9)
            worker.join(3000)
            assertFalse(worker.isAlive, "取消后 SSE 流应结束")
            assertEquals(200, outcome.status)
            assertTrue(outcome.contentType.contains("text/event-stream"), "应是 SSE 响应：${outcome.contentType}")
            val frames = framesOf(outcome.text)
            val error = frames.lastOrNull()?.get("error")?.jsonObject
            assertEquals(-32800, error?.get("code")?.jsonPrimitive?.content?.toInt(), "取消帧：${outcome.text}")
            assertTrue(frames.none { it["result"] != null }, "被取消的调用不该再回结果帧：${outcome.text}")
        } finally {
            release.countDown()
            bridge.stop()
        }
    }

    /** SSE 帧解析（与服务端同形制：data: 一行一帧，帧间空行） */
    private fun framesOf(text: String): List<JsonObject> =
        text.split("\n\n").mapNotNull { block ->
            val line = block.lineSequence().firstOrNull { it.startsWith("data:") } ?: return@mapNotNull null
            runCatching { Json.parseToJsonElement(line.removePrefix("data:").trim()).jsonObject }.getOrNull()
        }

    /** 把服务端 stdout 的字节流按行切出来（stdio 帧 = 一行一帧） */
    private class LineSink : OutputStream() {
        val lines = LinkedBlockingQueue<String>()
        private val buf = ByteArrayOutputStream()

        @Synchronized
        override fun write(b: Int) {
            buf.write(b)
            drain()
        }

        @Synchronized
        override fun write(b: ByteArray, off: Int, len: Int) {
            buf.write(b, off, len)
            drain()
        }

        private fun drain() {
            while (true) {
                val bytes = buf.toByteArray()
                val idx = bytes.indexOfFirst { it == '\n'.code.toByte() }
                if (idx < 0) return
                lines.put(String(bytes, 0, idx, Charsets.UTF_8))
                buf.reset()
                buf.write(bytes, idx + 1, bytes.size - idx - 1)
            }
        }
    }

    @Test
    fun `stdio 传输——取消通知在途生效，原请求回 -32800`() {
        val prefs = MapPrefs()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bridge = McpBridge(AbilityDatasets(), prefs, extraTools = listOf(SlowProbeTool(started, release)))
        val pipeIn = PipedInputStream(64 * 1024)
        val pipeOut = PipedOutputStream(pipeIn)
        val sink = LineSink()
        val server = McpStdioServer(bridge, pipeIn, sink)
        val serverThread = thread(name = "stdio-serve") { server.serve() }
        try {
            pipeOut.write((callBody(42, block = true) + "\n").toByteArray(Charsets.UTF_8))
            pipeOut.flush()
            assertTrue(started.await(2, TimeUnit.SECONDS), "工具应已开始执行")
            // 旧实现（顺序处理）会把这一行读在调用结束之后，取消永远不命中
            val cancel = """{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":42}}"""
            pipeOut.write((cancel + "\n").toByteArray(Charsets.UTF_8))
            pipeOut.flush()
            val line = sink.lines.poll(3, TimeUnit.SECONDS)
            assertNotNull(line, "取消后应回错误帧")
            val error = Json.parseToJsonElement(line).jsonObject["error"]!!.jsonObject
            assertEquals(-32800, error["code"]!!.jsonPrimitive.content.toInt(), "stdio 取消应回 -32800：$line")
        } finally {
            release.countDown()
            runCatching { pipeOut.close() }
            serverThread.join(3000)
            assertFalse(serverThread.isAlive, "stdin EOF 后 serve 应退出")
            bridge.stop()
        }
    }

    @Test
    fun `取消异常映射——AgentCancelled 与 GenerationCancelled 都回 -32800`() {
        val prefs = MapPrefs()
        val tools = listOf(
            ThrowingTool("throws_agent_cancel", AgentCancelled()),
            ThrowingTool("throws_generation_cancel", GenerationCancelled()),
        )
        val bridge = McpBridge(AbilityDatasets(), prefs, extraTools = tools)
        val token = McpBridge.currentToken(prefs)
        val port = bridge.start(0)
        try {
            listOf("throws_agent_cancel", "throws_generation_cancel").forEachIndexed { i, name ->
                val (worker, outcome) = startCall(
                    port, token, null,
                    """{"jsonrpc":"2.0","id":${100 + i},"method":"tools/call","params":{"name":"$name","arguments":{}}}""",
                )
                worker.join(3000)
                assertEquals(200, outcome.status)
                val error = Json.parseToJsonElement(outcome.text).jsonObject["error"]!!.jsonObject
                assertEquals(-32800, error["code"]!!.jsonPrimitive.content.toInt(), "$name 应映射为取消：${outcome.text}")
            }
        } finally {
            bridge.stop()
        }
    }
}
