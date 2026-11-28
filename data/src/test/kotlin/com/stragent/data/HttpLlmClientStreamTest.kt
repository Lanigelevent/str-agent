package com.stragent.data

import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.PrefsStore
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 报告正文的 **token 级流式通道**契约测试（T-013 余量 · 阶段 2）。
 *
 * 和视觉接口那份一样，这里「后端大模型」由本地桩扮演，但桩必须**吐符合 SSE 线格式的帧**
 * （`data: {chunk}` + `data: [DONE]`）——测的是**客户端对协议的处置**，不是模型质量：
 *  ① 增量拼接 = 最终正文（客户端敢直接把片段贴到屏幕上的前提）；
 *  ② 请求形态（stream / stream_options / Accept）与单次通道互不污染；
 *  ③ **回落纪律**：一个字都没发出去才回落单次通道；状态类错误不回落；
 *     已发出过片段而流又没正常结束 → 如实报中断，绝不把半截正文当完成。
 *
 * ③ 是本套件的靶心：静默截断的报告在用户眼里和写完了没两样，那是假账。
 */
class HttpLlmClientStreamTest {

    private class MapPrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 起一个 OpenAI 兼容的本地桩。
     *
     * [respond] 收到第 n 次请求的 body 与 Accept 头，返回要发的**响应状态 + 行序列**；
     * 行序列按 `text/event-stream` 逐行写出（chunked），写完即正常关闭连接。
     */
    private class Stub(val server: HttpServer, val received: MutableList<String>, val accepts: MutableList<String>) {
        fun port(): Int = server.address.port
        fun stop() = server.stop(0)
    }

    private fun stub(respond: (body: String, accept: String) -> Pair<Int, List<String>>): Stub {
        val received = mutableListOf<String>()
        val accepts = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            val accept = exchange.requestHeaders.getFirst("Accept") ?: ""
            synchronized(received) { received += body; accepts += accept }
            val (status, lines) = respond(body, accept)
            if (status >= 400) {
                val bytes = lines.joinToString("\n").toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                exchange.responseHeaders.set("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(status, 0) // chunked：边写边 flush，逐帧可见
                exchange.responseBody.use { out ->
                    lines.forEach { line ->
                        out.write((line + "\n\n").toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                }
            }
            exchange.close()
        }
        server.start()
        return Stub(server, received, accepts)
    }

    private fun client(port: Int): HttpLlmClient {
        val prefs = MapPrefs()
        prefs.set(KEY, "sk-test")
        return HttpLlmClient(prefs, baseUrlOverride = "http://127.0.0.1:$port")
    }

    /** 一个 SSE 增量帧：`choices[0].delta.content` + `finish_reason`（普通转义串，别用原始串数花括号） */
    private fun chunk(content: String?, finishReason: String? = null): String {
        val delta = if (content == null) "{}" else "{\"content\":\"$content\"}"
        val reason = if (finishReason == null) "null" else "\"$finishReason\""
        return "{\"choices\":[{\"index\":0,\"delta\":$delta,\"finish_reason\":$reason}]}"
    }

    /** 末包含 usage、choices 为空的一帧（OpenAI 在 include_usage 下的实际形状） */
    private fun usage(totalTokens: Int): String =
        "{\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":$totalTokens," +
            "\"total_tokens\":$totalTokens}}"

    @Test
    fun `① 逐片段回调，片段拼接与最终正文逐字一致`() {
        val stub = stub { _, _ ->
            200 to listOf(
                "data: " + chunk("华东地区"),
                ": keep-alive", // SSE 注释行：必须被忽略而不是当成坏帧
                "data: " + chunk("销售额最高，"),
                "data: " + chunk("建议加大投放。", finishReason = "length"),
                "data: " + usage(321),
                "data: [DONE]",
            )
        }
        val tokens = mutableListOf<String>()
        try {
            val result: ChatResult = client(stub.port()).streamChat(
                model = "step-3.7-flash",
                messages = listOf("user" to "分析销售额"),
                temperature = 0.7,
                maxTokens = 1000,
                onToken = { tokens += it },
            )
            assertEquals(listOf("华东地区", "销售额最高，", "建议加大投放。"), tokens)
            assertEquals("华东地区销售额最高，建议加大投放。", result.text)
            assertEquals(321, result.totalTokens, "usage 随末包回来，不该记 0")
            assertTrue(result.truncated, "finish_reason=length 必须如实上报截断（续写判据）")
        } finally {
            stub.stop()
        }
    }

    @Test
    fun `② 流式请求形态：stream true + include_usage + SSE Accept，与单次通道互不污染`() {
        val stub = stub { _, _ -> 200 to listOf("data: " + chunk("正文", "stop"), "data: [DONE]", usage(9)) }
        try {
            client(stub.port()).streamChat("m", listOf("user" to "写"), 0.3, 512) { }
            val payload = json.parseToJsonElement(stub.received.single()).jsonObject
            assertEquals(true, payload["stream"]!!.jsonPrimitive.booleanOrNull)
            assertEquals(
                true,
                payload["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.booleanOrNull,
                "流式下不显式要 usage，token 账会变成 0（假账）",
            )
            assertEquals(512, payload["max_tokens"]!!.jsonPrimitive.content.toInt())
            assertEquals("text/event-stream", stub.accepts.single())
        } finally {
            stub.stop()
        }
    }

    @Test
    fun `③ 只要没发出片段就回落单次通道：网关无视 stream 也不丢报告`() {
        val stub = stub { body, _ ->
            val wantsStream = json.parseToJsonElement(body).jsonObject["stream"]!!.jsonPrimitive.booleanOrNull == true
            if (wantsStream) {
                // 某些网关收下 stream:true 却照样返整包 JSON —— 没有任何 data: 行
                200 to listOf(
                    """{"choices":[{"index":0,"message":{"role":"assistant","content":"整包正文"},"finish_reason":"stop"}]}""",
                )
            } else {
                200 to listOf(
                    """{"choices":[{"index":0,"message":{"role":"assistant","content":"整包正文"},"finish_reason":"stop"}],""" +
                        """"usage":{"total_tokens":42}}""",
                )
            }
        }
        val tokens = mutableListOf<String>()
        try {
            val result = client(stub.port()).streamChat("m", listOf("user" to "写"), 0.3, 512) { tokens += it }
            assertEquals("整包正文", result.text, "流式取不到正文时必须自己救回来，报告不能失败")
            assertEquals(42, result.totalTokens)
            assertEquals(2, stub.received.size, "应先试流式、再回落单次")
            assertFalse(tokens.isEmpty(), "回落后的正文也要交给调用方（片段不能凭空消失）")
            assertEquals(listOf("整包正文"), tokens)
        } finally {
            stub.stop()
        }
    }

    @Test
    fun `④ 已发出片段而流未正常结束：如实报中断，不把半截正文当完成`() {
        val stub = stub { _, _ ->
            // 没有 finish_reason、也没有 data: [DONE] —— 桩把连接正常关断，模拟「正文还没写完就断了」
            200 to listOf("data: " + chunk("第一段写完了，"), "data: " + chunk("第二段写到一半"))
        }
        val tokens = mutableListOf<String>()
        try {
            val error = runCatching {
                client(stub.port()).streamChat("m", listOf("user" to "写"), 0.3, 512) { tokens += it }
            }.exceptionOrNull()
            assertTrue(error != null, "半截正文不得当成正常返回")
            assertTrue(
                error!!.message!!.contains("流式响应中断"),
                "错误要写明是流中断（并带上已写字数），否则用户只看到「报告变短了」：${error.message}",
            )
            assertEquals(listOf("第一段写完了，", "第二段写到一半"), tokens, "中断前收到的片段不得吞掉")
            assertEquals(1, stub.received.size, "已经发过片段就不该重打一遍（会和客户端已显示的内容打架）")
        } finally {
            stub.stop()
        }
    }

    @Test
    fun `⑤ 只有 finish_reason、没发 DONE 哨兵：算正常结束（不同网关都收）`() {
        val stub = stub { _, _ -> 200 to listOf("data: " + chunk("收尾完整", "stop")) }
        try {
            val result = client(stub.port()).streamChat("m", listOf("user" to "写"), 0.3, 512) { }
            assertEquals("收尾完整", result.text)
            assertFalse(result.truncated)
        } finally {
            stub.stop()
        }
    }

    @Test
    fun `⑥ 状态类错误不回落：401 只打一次请求，报中文鉴权提示`() {
        val stub = stub { _, _ ->
            401 to listOf("""{"error":{"message":"Invalid API key","type":"invalid_request_error"}}""")
        }
        try {
            val error = runCatching {
                client(stub.port()).streamChat("m", listOf("user" to "写"), 0.3, 512) { }
            }.exceptionOrNull()
            assertTrue(error != null, "401 必须抛出")
            assertEquals("auth", (error as? com.stragent.core.ai.StepError)?.code, "沿用单次通道的错误分类：$error")
            assertTrue(error.message!!.contains("API Key"), "要给中文可操作提示：${error.message}")
            assertEquals(1, stub.received.size, "回落也只是同一个 401，多打一次没有意义")
        } finally {
            stub.stop()
        }
    }

    @Test
    fun `⑦ onToken 为空 = 明确不要流式：请求形态与单次通道逐字一致`() {
        val stub = stub { _, _ ->
            200 to listOf(
                """{"choices":[{"index":0,"message":{"role":"assistant","content":"单次正文"},"finish_reason":"stop"}],""" +
                    """"usage":{"total_tokens":7}}""",
            )
        }
        try {
            val result = client(stub.port()).streamChat("m", listOf("user" to "写"), 0.3, 512, onToken = null)
            assertEquals("单次正文", result.text)
            assertEquals(7, result.totalTokens)
            assertEquals(1, stub.received.size, "没有订阅者就不必改成流式请求")
            val payload = json.parseToJsonElement(stub.received.single()).jsonObject
            assertEquals(false, payload["stream"]!!.jsonPrimitive.booleanOrNull)
            assertEquals("application/json", stub.accepts.single())
        } finally {
            stub.stop()
        }
    }

    private companion object {
        const val KEY = "stepfun.apiKey"
    }
}
