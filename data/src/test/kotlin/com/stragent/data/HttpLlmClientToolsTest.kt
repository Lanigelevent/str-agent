package com.stragent.data

import com.stragent.core.ai.StepError
import com.stragent.core.ports.PrefsStore
import com.stragent.core.ports.ToolCall
import com.stragent.core.ports.ToolSpec
import com.stragent.core.ports.TurnMessage
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-049 契约：原生工具协议（tools / tool_choice / response_format）、端点不认时的回落、
 * 错误分类与退避节奏。后端大模型仍由本地桩扮演——测的是**客户端对协议的处置**，
 * 不是模型质量（真实模型抽样在 T-052）。
 *
 * 每条用例的靶心：
 *  ① 请求体形状对、`tool_calls` 解析对、assistant/tool 消息逐条对账（少一个 id 服务端 400）；
 *  ② 端点 400「不认 tools」→ 自动改打纯文本、标 `native = false`，报告不会因此失败；
 *  ③ 429/5xx 重试、401 不重试、`Retry-After` 优先、读超时不重试——退避用注入的
 *     sleep 记录器测量，**不真睡**，所以节奏是可断言的确定值。
 */
class HttpLlmClientToolsTest {

    private class MapPrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private data class Reply(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())

    private class Stub(val server: HttpServer, val received: MutableList<String>) {
        fun port(): Int = server.address.port
        fun stop() = server.stop(0)
    }

    /** 每次请求按序交给 [respond]（body + 第几次请求），返回状态码与整包 JSON */
    private fun stub(respond: (body: String, index: Int) -> Reply): Stub {
        val received = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            val index = synchronized(received) { received += body; received.size - 1 }
            val reply = respond(body, index)
            reply.headers.forEach { (name, value) -> exchange.responseHeaders.set(name, value) }
            exchange.responseHeaders.set("Content-Type", "application/json")
            val bytes = reply.body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        return Stub(server, received)
    }

    private fun client(
        port: Int,
        sleeps: MutableList<Long> = mutableListOf(),
        nativeTools: Boolean = true,
    ): HttpLlmClient {
        val prefs = MapPrefs()
        prefs.set(KEY, "sk-test")
        if (nativeTools) prefs.set(HttpLlmClient.NATIVE_TOOLS_STORAGE, "on")
        return HttpLlmClient(
            prefs,
            baseUrlOverride = "http://127.0.0.1:$port",
            sleep = { sleeps += it },
        )
    }

    private val probeSpec = ToolSpec(
        name = "probe",
        description = "测试探针",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("k", buildJsonObject { put("type", "number") }) })
            put("required", buildJsonArray { add("k") })
        },
    )

    /** OpenAI 形状的工具调用响应 */
    private fun toolCallReply(id: String, name: String, arguments: String, tokens: Int = 33): String =
        buildJsonObject {
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put("message", buildJsonObject {
                        put("role", "assistant")
                        put("content", JsonNull)
                        put("tool_calls", buildJsonArray {
                            add(buildJsonObject {
                                put("id", id)
                                put("type", "function")
                                put("function", buildJsonObject {
                                    put("name", name)
                                    put("arguments", arguments)
                                })
                            })
                        })
                    })
                    put("finish_reason", "tool_calls")
                })
            })
            put("usage", buildJsonObject { put("total_tokens", tokens) })
        }.toString()

    private fun textReply(content: String, tokens: Int = 11): String =
        """{"choices":[{"index":0,"message":{"role":"assistant","content":"$content"},"finish_reason":"stop"}],""" +
            """"usage":{"total_tokens":$tokens}}"""

    @Test
    fun `① 原生请求形态：tools + tool_choice + response_format，tool_calls 解析与消息对账`() {
        val stub = stub { _, index ->
            if (index == 0) {
                Reply(200, toolCallReply("call_1", "probe", """{"k":1}"""))
            } else {
                Reply(200, textReply("完成", tokens = 21))
            }
        }
        try {
            val llm = client(stub.port())
            assertTrue(llm.supportsNativeTools("m"), "开关 on 时能力探测必须为真")
            val first = llm.chatTools(
                model = "m",
                messages = listOf(TurnMessage("system", "s"), TurnMessage("user", "任务")),
                temperature = 0.2,
                maxTokens = 800,
                tools = listOf(probeSpec),
                toolChoice = "required",
                responseFormat = "json_object",
            )
            assertTrue(first.native)
            assertEquals(33, first.totalTokens)
            assertEquals("", first.text)
            assertEquals(listOf("call_1"), first.toolCalls.map { it.id })
            assertEquals("probe", first.toolCalls[0].name)
            assertEquals("""{"k":1}""", first.toolCalls[0].arguments, "arguments 必须保持原始 JSON 字符串")

            val payload = json.parseToJsonElement(stub.received[0]).jsonObject
            assertEquals(false, payload["stream"]!!.jsonPrimitive.booleanOrNull)
            val function = payload["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject
            assertEquals("probe", function["name"]!!.jsonPrimitive.content)
            assertEquals("k", function["parameters"]!!.jsonObject["required"]!!.jsonArray[0].jsonPrimitive.content)
            assertEquals("required", payload["tool_choice"]!!.jsonPrimitive.content)
            assertEquals("json_object", payload["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)

            // 第二轮：assistant.tool_calls + tool.tool_call_id 必须逐条回填（少一个服务端 400）
            val second = llm.chatTools(
                model = "m",
                messages = listOf(
                    TurnMessage("system", "s"),
                    TurnMessage("user", "任务"),
                    TurnMessage("assistant", "", toolCalls = listOf(ToolCall("call_1", "probe", """{"k":1}"""))),
                    TurnMessage("tool", """{"ok":true}""", toolCallId = "call_1"),
                ),
                temperature = 0.2,
                maxTokens = 800,
                tools = listOf(probeSpec),
            )
            assertEquals("完成", second.text)
            assertEquals(21, second.totalTokens)
            val messages = json.parseToJsonElement(stub.received[1]).jsonObject["messages"]!!.jsonArray
            val assistant = messages.first { it.jsonObject["role"]!!.jsonPrimitive.content == "assistant" }.jsonObject
            val call = assistant["tool_calls"]!!.jsonArray[0].jsonObject
            assertEquals("call_1", call["id"]!!.jsonPrimitive.content)
            assertEquals("function", call["type"]!!.jsonPrimitive.content)
            assertEquals("probe", call["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            val toolMessage = messages.first { it.jsonObject["role"]!!.jsonPrimitive.content == "tool" }.jsonObject
            assertEquals("call_1", toolMessage["tool_call_id"]!!.jsonPrimitive.content)
            assertEquals("""{"ok":true}""", toolMessage["content"]!!.jsonPrimitive.content)
        } finally {
            stub.stop()
        }
    }

    @Test
    fun `② 端点不认 tools：自动改打纯文本并标 native=false，报告不因此失败`() {
        val stub = stub { body, index ->
            if (index == 0) {
                assertTrue(body.contains("\"tools\""), "第一次请求必须带 tools（否则测不到回落）")
                Reply(400, """{"error":{"message":"tools are not supported","code":"invalid_request_error"}}""")
            } else {
                Reply(200, textReply("回落正文", tokens = 12))
            }
        }
        try {
            val sleeps = mutableListOf<Long>()
            val turn = client(stub.port(), sleeps).chatTools(
                model = "m",
                messages = listOf(TurnMessage("system", "s"), TurnMessage("user", "任务")),
                temperature = 0.2,
                maxTokens = 800,
                tools = listOf(probeSpec),
                responseFormat = "json_object",
            )
            assertFalse(turn.native, "端点拒绝 tools 时必须标明本次不是原生协议")
            assertEquals("回落正文", turn.text)
            assertEquals(12, turn.totalTokens, "回落的这次调用也花了钱，token 必须如实入账")
            assertTrue(turn.toolCalls.isEmpty())
            assertEquals(2, stub.received.size, "400 不重试，只应多打一次纯文本")
            assertTrue(sleeps.isEmpty(), "400 不是可重试错误")
            val plain = stub.received[1]
            assertFalse(plain.contains("\"tools\""), "回落请求不该再带 tools")
            assertFalse(plain.contains("response_format"), "回落请求不该再带 response_format")
            assertFalse(plain.contains("\"tool_choice\""))
        } finally {
            stub.stop()
        }
    }

    @Test
    fun `②b 回落剥掉 role=tool 与 tool_calls 历史：纯文本请求不会二次 400`() {
        val stub = stub { _, index ->
            if (index == 0) {
                Reply(400, """{"error":{"message":"tools are not supported","code":"invalid_request_error"}}""")
            } else {
                Reply(200, textReply("回落正文", tokens = 12))
            }
        }
        try {
            val turn = client(stub.port()).chatTools(
                model = "m",
                messages = listOf(
                    TurnMessage("system", "s"),
                    TurnMessage("user", "任务"),
                    TurnMessage("assistant", "", toolCalls = listOf(ToolCall("call_0", "probe", "{}"))),
                    TurnMessage("tool", """{"ok":true}""", toolCallId = "call_0"),
                    TurnMessage("user", "继续"),
                ),
                temperature = 0.2,
                maxTokens = 800,
                tools = listOf(probeSpec),
            )
            assertFalse(turn.native)
            val plain = stub.received[1]
            assertFalse(plain.contains("\"tool_calls\""), "assistant.tool_calls 必须整条剥离")
            assertFalse(plain.contains("\"tool_call_id\""), "tool 结果消息必须整条剥离")
            val roles = json.parseToJsonElement(plain).jsonObject["messages"]!!.jsonArray
                .map { it.jsonObject["role"]!!.jsonPrimitive.content }
            assertEquals(listOf("system", "user", "user"), roles, "只保留纯文本轮次")
        } finally {
            stub.stop()
        }
    }

    @Test
    fun `③ 429 两次后成功：按 500ms、1500ms 退避（不真睡）`() {
        val stub = stub { _, index ->
            if (index < 2) Reply(429, """{"error":{"code":"rate_limit_exceeded"}}""")
            else Reply(200, textReply("终于成功", tokens = 9))
        }
        try {
            val sleeps = mutableListOf<Long>()
            val result = client(stub.port(), sleeps).chat("m", listOf("user" to "写"), 0.3, 512)
            assertEquals("终于成功", result.text)
            assertEquals(3, stub.received.size)
            assertEquals(listOf(500L, 1_500L), sleeps, "指数退避节奏必须可测且确定")
        } finally {
            stub.stop()
        }
    }

    @Test
    fun `④ 429 耗尽：quota 分类 + Retry-After 优先且封顶 10 秒`() {
        val stub = stub { _, _ ->
            Reply(429, """{"error":{"code":"rate_limit_exceeded"}}""", mapOf("Retry-After" to "60"))
        }
        try {
            val sleeps = mutableListOf<Long>()
            val error = runCatching {
                client(stub.port(), sleeps).chat("m", listOf("user" to "写"), 0.3, 512)
            }.exceptionOrNull() as? StepError
            assertEquals(STEP_ERR_QUOTA, error?.code)
            assertTrue(error!!.message!!.contains("稍后再试"))
            assertEquals(3, stub.received.size, "最多 3 次尝试（1 次 + 2 次重试）")
            assertEquals(listOf(10_000L, 10_000L), sleeps, "Retry-After 60 秒要被 10 秒上限兜住")
        } finally {
            stub.stop()
        }
    }

    @Test
    fun `⑤ 401 不重试：auth 分类，只打一次请求`() {
        val stub = stub { _, _ ->
            Reply(401, """{"error":{"message":"Invalid API key","type":"invalid_request_error"}}""")
        }
        try {
            val sleeps = mutableListOf<Long>()
            val error = runCatching {
                client(stub.port(), sleeps).chat("m", listOf("user" to "写"), 0.3, 512)
            }.exceptionOrNull() as? StepError
            assertEquals(STEP_ERR_AUTH, error?.code)
            assertEquals(1, stub.received.size, "鉴权错误重试只是把同一个失败再买一遍")
            assertTrue(sleeps.isEmpty())
        } finally {
            stub.stop()
        }
    }

    @Test
    fun `⑥ 5xx 重试耗尽：server 分类，两次退避后如实报错`() {
        val stub = stub { _, _ -> Reply(503, """{"error":{"message":"overloaded"}}""") }
        try {
            val sleeps = mutableListOf<Long>()
            val error = runCatching {
                client(stub.port(), sleeps).chat("m", listOf("user" to "写"), 0.3, 512)
            }.exceptionOrNull() as? StepError
            assertEquals(STEP_ERR_SERVER, error?.code)
            assertTrue(error!!.message!!.contains("暂时不可用"), error.message)
            assertEquals(3, stub.received.size)
            assertEquals(listOf(500L, 1_500L), sleeps)
        } finally {
            stub.stop()
        }
    }

    @Test
    fun `⑦ 流式首包前 429 也退避：还没吐字，重试不会和已显示内容打架`() {
        val stub = stub { _, index ->
            if (index == 0) {
                Reply(429, """{"error":{"code":"rate_limit_exceeded"}}""")
            } else {
                Reply(
                    200,
                    "data: " + """{"choices":[{"index":0,"delta":{"content":"流式正文"},"finish_reason":"stop"}]}""" + "\n\n" +
                        "data: [DONE]\n\n",
                )
            }
        }
        try {
            val sleeps = mutableListOf<Long>()
            val tokens = mutableListOf<String>()
            val result = client(stub.port(), sleeps).streamChat("m", listOf("user" to "写"), 0.3, 512) { tokens += it }
            assertEquals("流式正文", result.text)
            assertEquals(listOf("流式正文"), tokens)
            assertEquals(2, stub.received.size)
            assertEquals(listOf(500L), sleeps)
        } finally {
            stub.stop()
        }
    }

    @Test
    fun `⑧ 超时与连接分类：连接超时可重试，读超时不重试`() {
        val connect = timeoutFailure(java.net.SocketTimeoutException("Connect timed out"))
        assertEquals(STEP_ERR_TIMEOUT, connect.code)
        assertTrue(connect.retryable, "连接建立失败值得退避（15 秒上限，重试总代价可控）")

        val read = timeoutFailure(java.net.SocketTimeoutException("Read timed out"))
        assertEquals(STEP_ERR_TIMEOUT, read.code)
        assertFalse(read.retryable, "读超时已经等了 180 秒（T-062），再等三轮只会拖死用户")

        assertTrue(networkFailure(java.net.ConnectException("Connection refused")).retryable)
        assertTrue(networkFailure(java.net.SocketException("Connection reset")).retryable)
        assertFalse(networkFailure(IllegalStateException("boom")).retryable)
        assertEquals(STEP_ERR_NETWORK, networkFailure(IllegalStateException("boom")).code)
    }

    @Test
    fun `⑩ 空正文错误带 finish_reason 与推理 token 诊断，预算耗尽不给假重试建议`() {
        val blankLength = buildJsonObject {
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put("message", buildJsonObject {
                        put("role", "assistant")
                        put("content", JsonNull)
                    })
                    put("finish_reason", "length")
                })
            })
            put("usage", buildJsonObject {
                put("total_tokens", 16_500)
                put("completion_tokens", 16_384)
                put("completion_tokens_details", buildJsonObject { put("reasoning_tokens", 16_300) })
            })
        }.toString()
        val exhausted = stub { _, _ -> Reply(200, blankLength) }
        try {
            val err = assertFailsWith<StepError> {
                client(exhausted.port()).chat("m", listOf("user" to "写"), 0.7, 16_384)
            }
            assertEquals(STEP_ERR_NETWORK, err.code)
            val message = err.message ?: ""
            assertTrue(message.contains("finish_reason=length"), message)
            assertTrue(message.contains("completion_tokens=16384"), message)
            assertTrue(message.contains("reasoning_tokens=16300"), message)
            assertTrue(message.contains("提高 max_tokens"), "预算耗尽必须给可行动提示：$message")
            assertFalse(message.contains("请重试"), "预算耗尽时「重试」是误诊：$message")
        } finally {
            exhausted.stop()
        }

        val blankStop = buildJsonObject {
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put("message", buildJsonObject {
                        put("role", "assistant")
                        put("content", "")
                    })
                    put("finish_reason", "stop")
                })
            })
            put("usage", buildJsonObject { put("total_tokens", 12) })
        }.toString()
        val transient = stub { _, _ -> Reply(200, blankStop) }
        try {
            val err = assertFailsWith<StepError> {
                client(transient.port()).chat("m", listOf("user" to "写"), 0.7, 512)
            }
            val message = err.message ?: ""
            assertTrue(message.contains("finish_reason=stop"), message)
            assertTrue(message.contains("请重试"), message)
            assertFalse(message.contains("提高 max_tokens"), "非预算耗尽不该误导用户加预算：$message")
        } finally {
            transient.stop()
        }
    }

    @Test
    fun `⑨ 原生开关默认关闭，只有显式 on 才启用`() {
        val stub = stub { _, _ -> Reply(200, textReply("x")) }
        try {
            val off = client(stub.port(), nativeTools = false)
            assertFalse(off.supportsNativeTools("m"), "默认必须是文本协议（未做真实模型质量抽样前不切换）")
            val prefs = MapPrefs()
            prefs.set(KEY, "sk-test")
            prefs.set(HttpLlmClient.NATIVE_TOOLS_STORAGE, "ON")
            val on = HttpLlmClient(prefs, baseUrlOverride = "http://127.0.0.1:${stub.port()}", sleep = {})
            assertTrue(on.supportsNativeTools("m"), "开关大小写不敏感")
        } finally {
            stub.stop()
        }
    }

    private companion object {
        const val KEY = "stepfun.apiKey"
    }
}
