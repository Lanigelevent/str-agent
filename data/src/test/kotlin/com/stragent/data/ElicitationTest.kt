package com.stragent.data

import com.stragent.core.ports.AgentTool
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * elicitation 双向通道测试（T-056 / D17 G-15）。
 *
 * 验的是**通道语义**而不是 UI：
 *  1. 没有服务端消息通道时**立刻**如实取消（不阻塞、不假装成功）；
 *  2. accept / decline / 客户端错误三种回包都正确收口；
 *  3. 跨会话的响应不能命中别人的在途提问（id + 会话双重校验）；
 *  4. 超时不无限期吊住工作线程；
 *  5. 两条传输端到端：stdio 的 stdout/stdin 与 HTTP 的 GET 流 + POST 回包；
 *  6. HTTP 没有流时同样降级（工具侧拿 cancel + reason，而不是干等）。
 *
 * 边界（诚实）：这是**进程内**单机通道；跨进程转发、真实弹窗 UI 不在本套件范围
 * （客户端行为由 MCP 客户端决定，服务端只保证协议语义）。
 */
class ElicitationTest {

    private class MapPrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    /** 走 broker 提一个问题，把结局原样摊成 JSON 给断言看 */
    private class ElicitProbeTool(private val broker: ElicitationBroker) : AgentTool {
        override val name = "elicit_probe"
        override val description = "测试用：向客户端提问并回传结局"
        override val parametersSchema = buildJsonObject { put("type", "object") }
        override val cacheable = false
        override fun invoke(args: JsonObject): JsonObject {
            val outcome = broker.request(
                McpSessionContext.current(),
                "报告标题叫什么？",
                buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject { put("title", buildJsonObject { put("type", "string") }) })
                    put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("title"))))
                },
                2_000,
            )
            return buildJsonObject {
                put("action", outcome.action.wire)
                outcome.content?.let { put("content", it) }
                outcome.reason?.let { put("reason", it) }
            }
        }
    }

    private fun bridgeWith(vararg tools: AgentTool, broker: ElicitationBroker = ElicitationBroker()): Triple<McpBridge, String, ElicitationBroker> {
        val prefs = MapPrefs()
        val bridge = McpBridge(AbilityDatasets(), prefs, extraTools = tools.toList(), elicitation = broker)
        return Triple(bridge, McpBridge.currentToken(prefs), broker)
    }

    private fun schema() = buildJsonObject { put("type", "object") }

    // ---------------- 1. 无通道降级 ----------------

    @Test
    fun `无服务端消息通道——立即如实取消并说明原因，不阻塞不假装成功`() {
        val broker = ElicitationBroker()
        val started = System.nanoTime()
        val outcome = broker.request("default", "标题叫什么？", schema(), timeoutMs = 60_000)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(ElicitAction.CANCEL, outcome.action, "无通道必须降级为 cancel")
        assertTrue(
            outcome.reason!!.contains("未建立服务端消息通道"),
            "原因要说清楚是通道问题（而不是用户拒绝）：${outcome.reason}",
        )
        assertTrue(elapsedMs < 1_000, "不该按超时等 60 秒（实际 ${elapsedMs}ms）")
        assertEquals(0, broker.pendingCount(), "无通道不得留下在途请求")
        assertFalse(broker.hasChannel("default"))
    }

    // ---------------- 2. 三种回包 ----------------

    private class CapturingChannel {
        val frames = LinkedBlockingQueue<String>()
        val sender: (String) -> Boolean = { payload ->
            frames.offer(payload)
            true
        }

        fun awaitFrame(): JsonObject {
            val line = frames.poll(3, TimeUnit.SECONDS) ?: throw AssertionError("3 秒内没有收到服务端请求帧")
            return Json.parseToJsonElement(line).jsonObject
        }
    }

    @Test
    fun `accept——用户回答原样带回，字段结构不变`() {
        val broker = ElicitationBroker()
        val channel = CapturingChannel()
        broker.register("default", channel.sender)
        val result = AtomicReference<ElicitOutcome?>()
        val worker = thread { result.set(broker.request("default", "标题叫什么？", schema(), 3_000)) }

        val frame = channel.awaitFrame()
        assertEquals("elicitation/create", frame["method"]!!.jsonPrimitive.content)
        assertEquals("标题叫什么？", frame["params"]!!.jsonObject["message"]!!.jsonPrimitive.content)
        val id = frame["id"]!!.jsonPrimitive.content
        assertTrue(id.startsWith("elicit_"), "id 应是随机句柄：$id")
        assertTrue(broker.resolve("default", id, buildJsonObject {
            put("action", "accept")
            put("content", buildJsonObject { put("title", "季度复盘") })
        }), "本会话的响应应命中在途提问")

        worker.join(3_000)
        val outcome = assertNotNull(result.get(), "请求应已返回")
        assertEquals(ElicitAction.ACCEPT, outcome.action)
        assertEquals("季度复盘", outcome.content!!["title"]!!.jsonPrimitive.content)
        assertEquals(0, broker.pendingCount(), "收口后不得留下在途请求")
    }

    @Test
    fun `decline——用户拒绝回答要如实返回，不能被当成失败或撤回`() {
        val broker = ElicitationBroker()
        val channel = CapturingChannel()
        broker.register("default", channel.sender)
        val result = AtomicReference<ElicitOutcome?>()
        val worker = thread { result.set(broker.request("default", "要覆盖吗？", schema(), 3_000)) }

        val id = channel.awaitFrame()["id"]!!.jsonPrimitive.content
        assertTrue(broker.resolve("default", id, buildJsonObject { put("action", "decline") }))
        worker.join(3_000)
        val outcome = assertNotNull(result.get())
        assertEquals(ElicitAction.DECLINE, outcome.action, "decline 与 cancel 是两种语义")
        assertNull(outcome.reason)
    }

    @Test
    fun `客户端返回 JSON-RPC error——按取消收口并保留原始原因`() {
        val broker = ElicitationBroker()
        val channel = CapturingChannel()
        broker.register("default", channel.sender)
        val result = AtomicReference<ElicitOutcome?>()
        val worker = thread { result.set(broker.request("default", "标题叫什么？", schema(), 3_000)) }

        val id = channel.awaitFrame()["id"]!!.jsonPrimitive.content
        assertTrue(broker.reject("default", id, buildJsonObject { put("message", "用户界面已关闭") }))
        worker.join(3_000)
        val outcome = assertNotNull(result.get())
        assertEquals(ElicitAction.CANCEL, outcome.action)
        assertTrue(outcome.reason!!.contains("用户界面已关闭"), "应保留客户端原因：${outcome.reason}")
    }

    // ---------------- 3. 会话隔离 ----------------

    @Test
    fun `跨会话的响应不得命中别人在途的提问（id 对了会话不对也不行）`() {
        val broker = ElicitationBroker()
        val channel = CapturingChannel()
        broker.register("session-A", channel.sender)
        val result = AtomicReference<ElicitOutcome?>()
        val worker = thread { result.set(broker.request("session-A", "标题叫什么？", schema(), 3_000)) }

        val id = channel.awaitFrame()["id"]!!.jsonPrimitive.content
        assertFalse(broker.resolve("session-B", id, buildJsonObject { put("action", "accept") }), "别的会话不得交付")
        assertTrue(result.get() == null, "被盗会话的请求仍应在等")
        assertTrue(broker.resolve("session-A", id, buildJsonObject { put("action", "accept") }), "本会话才命中")

        worker.join(3_000)
        assertEquals(ElicitAction.ACCEPT, result.get()!!.action)
    }

    @Test
    fun `consume 只认响应帧——带 method 的请求帧与未命中 id 都返回 false`() {
        val broker = ElicitationBroker()
        assertFalse(
            broker.consume("default", buildJsonObject { put("jsonrpc", "2.0"); put("id", "x"); put("method", "tools/list") }),
            "请求帧不是响应",
        )
        assertFalse(
            broker.consume("default", buildJsonObject { put("jsonrpc", "2.0"); put("id", "nope"); put("result", buildJsonObject { }) }),
            "未命中的 id 不该交付",
        )
        assertFalse(
            broker.consume("default", buildJsonObject { put("jsonrpc", "2.0"); put("result", buildJsonObject { }) }),
            "没有 id 的帧不是响应",
        )
    }

    // ---------------- 4. 超时与写失败 ----------------

    @Test
    fun `超时——人不来就取消，不把工作线程无限期钉住`() {
        val broker = ElicitationBroker()
        broker.register("default", { true })
        val started = System.nanoTime()
        val outcome = broker.request("default", "标题叫什么？", schema(), timeoutMs = 120)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(ElicitAction.CANCEL, outcome.action)
        assertTrue(outcome.reason!!.contains("未响应"), "${outcome.reason}")
        assertTrue(elapsedMs in 100..2_000, "应按 timeout 返回（实际 ${elapsedMs}ms）")
        assertEquals(0, broker.pendingCount())
    }

    @Test
    fun `发送失败——sender 返回 false 或抛异常都按取消收口，且不留残余`() {
        val refusing = ElicitationBroker()
        refusing.register("default", { false })
        val first = refusing.request("default", "问一句", schema(), 1_000)
        assertEquals(ElicitAction.CANCEL, first.action)
        assertTrue(first.reason!!.contains("发送"), "${first.reason}")
        assertEquals(0, refusing.pendingCount())

        val throwing = ElicitationBroker()
        throwing.register("default", { throw java.io.IOException("管道已断") })
        val second = throwing.request("default", "问一句", schema(), 1_000)
        assertEquals(ElicitAction.CANCEL, second.action)
        assertEquals(0, throwing.pendingCount())
    }

    @Test
    fun `注销只移除同一个发送器——旧连接收尾不能顶掉新连接`() {
        val broker = ElicitationBroker()
        val fake = { _: String -> false }
        val real = { _: String -> true }
        broker.register("default", real)
        broker.unregister("default", fake)
        assertTrue(broker.hasChannel("default"), "旧发送器注销不得误删新连接")
        broker.unregister("default", real)
        assertFalse(broker.hasChannel("default"))
    }

    // ---------------- 5. 能力声明 ----------------

    @Test
    fun `能力声明含 elicitation，且三项 tasks 仍在`() {
        val (bridge, _, _) = bridgeWith()
        try {
            val caps = bridge.dispatch("initialize", JsonObject(emptyMap()))["capabilities"]!!.jsonObject
            assertTrue(caps.containsKey("elicitation"), "声明了工具就必须声明能力：$caps")
            val tasks = caps["tasks"]!!.jsonObject
            listOf("get", "update", "cancel").forEach { assertNotNull(tasks[it], "tasks.$it 丢了：$tasks") }
        } finally {
            bridge.stop()
        }
    }

    // ---------------- 6. stdio 端到端 ----------------

    /** stdout 按行切分（stdio 帧 = 一行一帧） */
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
    fun `stdio 端到端——提问帧走 stdout，客户端响应从 stdin 回来后调用拿到 accept`() {
        val broker = ElicitationBroker()
        val (bridge, _, _) = bridgeWith(ElicitProbeTool(broker), broker = broker)
        val pipeIn = PipedInputStream(64 * 1024)
        val pipeOut = PipedOutputStream(pipeIn)
        val sink = LineSink()
        val server = McpStdioServer(bridge, pipeIn, sink)
        val serverThread = thread(name = "stdio-elicit-serve") { server.serve() }
        try {
            pipeOut.write(
                ("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"elicit_probe","arguments":{}}}""" + "\n")
                    .toByteArray(Charsets.UTF_8),
            )
            pipeOut.flush()

            val questionLine = assertNotNull(sink.lines.poll(3, TimeUnit.SECONDS), "3 秒内应收到 elicitation/create 帧")
            val question = Json.parseToJsonElement(questionLine).jsonObject
            assertEquals("elicitation/create", question["method"]!!.jsonPrimitive.content)
            val id = question["id"]!!.jsonPrimitive.content
            assertTrue(broker.hasChannel(SessionRegistry.DEFAULT_SESSION), "stdio 应自动注册 default 会话通道")

            // 客户端回包在**读线程**就地路由——若排队，工具正等它，必然互等超时
            pipeOut.write(
                ("""{"jsonrpc":"2.0","id":"$id","result":{"action":"accept","content":{"title":"季度复盘"}}}""" + "\n")
                    .toByteArray(Charsets.UTF_8),
            )
            pipeOut.flush()

            val resultLine = assertNotNull(sink.lines.poll(3, TimeUnit.SECONDS), "3 秒内应收到 tools/call 结果帧")
            val result = Json.parseToJsonElement(resultLine).jsonObject["result"]!!.jsonObject
            val body = result["structuredContent"]!!.jsonObject
            assertEquals("accept", body["action"]!!.jsonPrimitive.content, "$resultLine")
            assertEquals("季度复盘", body["content"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        } finally {
            runCatching { pipeOut.close() }
            serverThread.join(3_000)
            assertFalse(serverThread.isAlive, "stdin EOF 后 serve 应退出")
            assertFalse(broker.hasChannel(SessionRegistry.DEFAULT_SESSION), "服务退出应注销通道")
            bridge.stop()
        }
    }

    // ---------------- 7. HTTP 端到端 ----------------

    private fun httpPost(port: Int, token: String, body: String): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val status = conn.responseCode
        val stream = if (status >= 400) conn.errorStream else conn.inputStream
        val text = stream?.readBytes()?.decodeToString() ?: ""
        conn.disconnect()
        return status to text
    }

    /** GET /mcp 的服务端消息流：后台线程把 data 帧塞进队列 */
    private class ServerStream(private val port: Int, private val token: String) {
        val payloads = LinkedBlockingQueue<String>()
        private val connection = AtomicReference<HttpURLConnection?>()
        private val ready = CountDownLatch(1)
        private val readerThread = thread(name = "mcp-server-stream") {
            try {
                val conn = URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection
                connection.set(conn)
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "text/event-stream")
                conn.setRequestProperty("Authorization", "Bearer $token")
                val reader = conn.inputStream.bufferedReader()
                ready.countDown()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith("data:")) payloads.put(line.removePrefix("data:").trim())
                }
            } catch (_: Exception) {
                // 连接在收尾阶段被断开——正常
            } finally {
                ready.countDown()
            }
        }

        fun awaitOpen(timeoutMs: Long = 3_000) {
            assertTrue(ready.await(timeoutMs, TimeUnit.MILLISECONDS), "GET /mcp 未在 ${timeoutMs}ms 内建立")
        }

        fun close() {
            runCatching { connection.get()?.disconnect() }
            readerThread.join(2_000)
        }
    }

    @Test
    fun `HTTP 端到端——GET 流收到提问、POST 回包 202、调用拿到 accept`() {
        val broker = ElicitationBroker()
        val (bridge, token, _) = bridgeWith(ElicitProbeTool(broker), broker = broker)
        val port = bridge.start(0)
        val stream = ServerStream(port, token)
        try {
            stream.awaitOpen()
            // 客户端认为「流已建立」时通道必须已经可用（先注册再发响应头）
            val hasChannel = bridge.elicitation.hasChannel(SessionRegistry.DEFAULT_SESSION)
            assertTrue(hasChannel, "GET 流建立后应已注册到 default 会话")

            val callOutcome = AtomicReference<Pair<Int, String>?>()
            val callThread = thread(name = "http-elicit-call") {
                callOutcome.set(
                    httpPost(
                        port, token,
                        """{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"elicit_probe","arguments":{}}}""",
                    ),
                )
            }

            val payload = stream.payloads.poll(3, TimeUnit.SECONDS)
                ?: throw AssertionError("3 秒内没有收到 elicitation/create 帧")
            val question = Json.parseToJsonElement(payload).jsonObject
            assertEquals("elicitation/create", question["method"]!!.jsonPrimitive.content)
            val id = question["id"]!!.jsonPrimitive.content

            val (ackStatus, ackBody) = httpPost(
                port, token,
                """{"jsonrpc":"2.0","id":"$id","result":{"action":"accept","content":{"title":"季度复盘"}}}""",
            )
            assertEquals(202, ackStatus, "客户端回包应是 202 Accepted 且无 JSON-RPC 回执：$ackBody")
            assertTrue(ackBody.isBlank(), "202 不应带响应体：$ackBody")

            callThread.join(5_000)
            val (status, text) = assertNotNull(callOutcome.get(), "tools/call 应已返回")
            assertEquals(200, status, text)
            val body = Json.parseToJsonElement(text).jsonObject["result"]!!.jsonObject["structuredContent"]!!.jsonObject
            assertEquals("accept", body["action"]!!.jsonPrimitive.content, text)
            assertEquals("季度复盘", body["content"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        } finally {
            stream.close()
            bridge.stop()
        }
    }

    @Test
    fun `HTTP 降级——没有 GET 流时提问立刻取消（不吊死调用）`() {
        val broker = ElicitationBroker()
        val (bridge, token, _) = bridgeWith(ElicitProbeTool(broker), broker = broker)
        val port = bridge.start(0)
        try {
            val started = System.nanoTime()
            val (status, text) = httpPost(
                port, token,
                """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"elicit_probe","arguments":{}}}""",
            )
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(200, status, text)
            val body = Json.parseToJsonElement(text).jsonObject["result"]!!.jsonObject["structuredContent"]!!.jsonObject
            assertEquals("cancel", body["action"]!!.jsonPrimitive.content, text)
            assertTrue(elapsedMs < 2_000, "没有通道应立刻降级（实际 ${elapsedMs}ms）")
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `HTTP GET mcp——未声明 SSE 回 406、未鉴权回 401`() {
        val (bridge, token, _) = bridgeWith()
        val port = bridge.start(0)
        try {
            val conn = URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            assertEquals(406, conn.responseCode, "GET /mcp 不接受 SSE 应回 406")
            conn.disconnect()

            val unauthorized = URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection
            unauthorized.requestMethod = "GET"
            unauthorized.setRequestProperty("Accept", "text/event-stream")
            assertEquals(401, unauthorized.responseCode, "GET /mcp 与 POST 一样必须鉴权")
            unauthorized.disconnect()
        } finally {
            bridge.stop()
        }
    }
}
