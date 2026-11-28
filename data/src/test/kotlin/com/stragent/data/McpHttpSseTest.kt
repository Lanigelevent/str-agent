package com.stragent.data

import com.stragent.core.ports.AgentTool
import com.stragent.core.ports.ProgressBus
import com.stragent.core.ports.PrefsStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import com.stragent.core.i18n.Messages

/**
 * HTTP(SSE) 渐进响应测试 —— 证明「客户端带 progressToken 时，HTTP 传输也能实时收到
 * `notifications/progress`」，并与 stdio 传输逐字对拍（同一帧源，两条接线）。
 *
 * 全部用自造工具（自己按固定顺序发帧）而不是真报告链：
 * 要验的是**传输形状**，不是模型输出；帧序固定才能断言到帧号与文本。
 */
class McpHttpSseTest {

    private class MapPrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    /** 确定性进度源：按固定顺序发 N 帧，再返回一个可辨识的结果 */
    private class FramesTool(
        private val frames: List<String>,
        private val beforeFrames: () -> Unit = {},
        private val afterFrames: () -> Unit = {},
    ) : AgentTool {
        override val name = "sse_probe"
        override val description = "测试用：按固定顺序发进度帧"
        override val parametersSchema = buildJsonObject { put("type", "object") }
        override val cacheable = false
        override fun invoke(args: JsonObject): JsonObject {
            beforeFrames()
            frames.forEach { ProgressBus.emit(it) }
            afterFrames()
            return buildJsonObject { put("text", "probe-done(${frames.size})") }
        }
    }

    private class SseResponse(val status: Int, val contentType: String, val frames: List<JsonObject>)

    private fun post(
        port: Int,
        token: String,
        params: String,
        accept: String?,
        id: Int = 7,
    ): SseResponse {
        val conn = URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        if (accept != null) conn.setRequestProperty("Accept", accept)
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.doOutput = true
        val body = """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":$params}"""
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val status = conn.responseCode
        val contentType = conn.contentType ?: ""
        val text = conn.inputStream.readBytes().decodeToString()
        conn.disconnect()
        val frames = if (contentType.contains("text/event-stream")) {
            text.split("\n\n").mapNotNull { block ->
                val line = block.lineSequence().firstOrNull { it.startsWith("data:") } ?: return@mapNotNull null
                runCatching { Json.parseToJsonElement(line.removePrefix("data:").trim()).jsonObject }.getOrNull()
            }
        } else {
            listOf(Json.parseToJsonElement(text).jsonObject)
        }
        return SseResponse(status, contentType, frames)
    }

    private val callWithToken =
        """{"name":"sse_probe","arguments":{},"_meta":{"progressToken":"tok-7"}}"""

    private fun bridgeWith(vararg tools: AgentTool): Pair<McpBridge, String> {
        val prefs = MapPrefs()
        val bridge = McpBridge(AbilityDatasets(), prefs, extraTools = tools.toList())
        return bridge to McpBridge.currentToken(prefs)
    }

    @Test
    fun `SSE 渐进响应——进度帧实时下发且最后是本请求的结果帧`() {
        val (bridge, token) = bridgeWith(FramesTool(listOf("第1帧", "第2帧", "第3帧")))
        val port = bridge.start(0)
        try {
            val resp = post(port, token, callWithToken, "application/json, text/event-stream")
            assertEquals(200, resp.status)
            assertTrue(resp.contentType.contains("text/event-stream"), "应协商成 SSE: ${resp.contentType}")
            assertEquals(4, resp.frames.size, "3 帧进度 + 1 帧结果：${resp.frames}")

            val progresses = resp.frames.take(3)
            progresses.forEachIndexed { index, frame ->
                assertEquals("notifications/progress", frame["method"]?.toString()?.trim('"'))
                val params = frame["params"] as JsonObject
                assertEquals("tok-7", (params["progressToken"] as kotlinx.serialization.json.JsonPrimitive).content, "token 必须回显")
                assertEquals((index + 1).toLong(), params["progress"]!!.toString().toLong(), "progress 单调递增")
                assertEquals("第${index + 1}帧", (params["message"] as kotlinx.serialization.json.JsonPrimitive).content)
            }
            val last = resp.frames[3]
            assertEquals(7L, last["id"]!!.toString().toLong(), "结果帧 id 回显请求 id")
            assertTrue(last["result"] != null, "结果帧必须带 result：$last")
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `不带 progressToken 仍走单帧 JSON（进度不被静默丢弃也不假装流式）`() {
        val (bridge, token) = bridgeWith(FramesTool(listOf("第1帧")))
        val port = bridge.start(0)
        try {
            val resp = post(port, token, """{"name":"sse_probe","arguments":{}}""", "application/json, text/event-stream")
            assertFalse(resp.contentType.contains("text/event-stream"), "无 token 不该开流")
            assertEquals(1, resp.frames.size)
            assertTrue(resp.frames[0]["result"] != null)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `预计耗时——首个进度帧带 estimatedTotalMs，后续帧不带（T-082）`() {
        val prefs = MapPrefs()
        val bridge = McpBridge(AbilityDatasets(), prefs, extraTools = listOf(FramesTool(listOf("甲", "乙", "丙"))), estimateTotalMs = { 123L })
        val port = bridge.start(0)
        try {
            val resp = post(port, McpBridge.currentToken(prefs), callWithToken, "application/json, text/event-stream")
            val progresses = resp.frames.filter { it["method"]?.toString()?.trim('"') == "notifications/progress" }
            assertEquals(3, progresses.size)
            val first = progresses[0]["params"] as JsonObject
            assertEquals(123L, first["estimatedTotalMs"]!!.toString().toLong(), "首帧必须带估计：$first")
            for (i in 1..2) {
                val params = progresses[i]["params"] as JsonObject
                assertTrue(
                    params["estimatedTotalMs"] == null,
                    "估计只在首帧有信息量，第 ${i + 1} 帧不应重复：$params",
                )
            }
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `预计耗时——无历史时首帧不带该字段（负控：不编造估计）`() {
        val prefs = MapPrefs()
        val bridge = McpBridge(AbilityDatasets(), prefs, extraTools = listOf(FramesTool(listOf("甲", "乙"))), estimateTotalMs = { null })
        val port = bridge.start(0)
        try {
            val resp = post(port, McpBridge.currentToken(prefs), callWithToken, "application/json, text/event-stream")
            val progresses = resp.frames.filter { it["method"]?.toString()?.trim('"') == "notifications/progress" }
            assertEquals(2, progresses.size)
            progresses.forEach { frame ->
                val params = frame["params"] as JsonObject
                assertTrue(params["estimatedTotalMs"] == null, "无历史不得编造估计：$params")
            }
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `客户端不接受 SSE 时不硬开流——按 JSON 回且结果完整`() {
        val (bridge, token) = bridgeWith(FramesTool(listOf("第1帧", "第2帧")))
        val port = bridge.start(0)
        try {
            val resp = post(port, token, callWithToken, "application/json")
            assertFalse(resp.contentType.contains("text/event-stream"), "Accept 没写 event-stream 就不该流式")
            assertEquals(1, resp.frames.size, "仍是单帧 JSON 结果")
            assertTrue(resp.frames[0]["result"] != null)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `跨传输对拍——stdio 与 http 的进度帧 params 逐字一致`() {
        val tool = FramesTool(listOf("甲", "乙", "丙"))
        val (bridge, token) = bridgeWith(tool)

        // stdio 侧
        val stdin = """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":$callWithToken}""" + "\n"
        val stdout = ByteArrayOutputStream()
        McpStdioServer(bridge, ByteArrayInputStream(stdin.toByteArray(Charsets.UTF_8)), stdout).serve()
        val stdioFrames = stdout.toByteArray().decodeToString().lineSequence()
            .filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }
            .toList()

        // http(SSE) 侧
        val port = bridge.start(0)
        val sseFrames = try {
            post(port, token, callWithToken, "application/json, text/event-stream").frames
        } finally {
            bridge.stop()
        }

        val progressParams = { frames: List<JsonObject> ->
            frames.filter { it["method"]?.toString()?.trim('"') == "notifications/progress" }
                .map { it["params"].toString() }
        }
        assertEquals(3, progressParams(stdioFrames).size, "stdio 侧应有 3 帧进度")
        assertEquals(progressParams(stdioFrames), progressParams(sseFrames), "两条传输的进度帧必须同源同形")

        val stdioResult = stdioFrames.firstOrNull { it["result"] != null }
        val sseResult = sseFrames.firstOrNull { it["result"] != null }
        assertEquals(stdioResult?.get("result").toString(), sseResult?.get("result").toString(), "结果载荷也要一致")
    }

    @Test
    fun `客户端中途断开——后续帧静默丢弃但工具执行完且服务可用`() {
        val finished = CountDownLatch(1)
        val clientGone = CountDownLatch(1)
        val (bridge, token) = bridgeWith(
            FramesTool(
                listOf("首帧", "尾帧1", "尾帧2"),
                beforeFrames = { },
                afterFrames = { },
            ).let { probe ->
                object : AgentTool by probe {
                    override fun invoke(args: JsonObject): JsonObject {
                        ProgressBus.emit("首帧")
                        clientGone.await(3, TimeUnit.SECONDS)
                        ProgressBus.emit("尾帧1")
                        ProgressBus.emit("尾帧2")
                        finished.countDown()
                        return buildJsonObject { put("text", "probe-done(3)") }
                    }
                }
            },
        )
        val port = bridge.start(0)
        try {
            val conn = URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.doOutput = true
            conn.outputStream.use {
                it.write("""{"jsonrpc":"2.0","id":7,"method":"tools/call","params":$callWithToken}""".toByteArray(Charsets.UTF_8))
            }
            val reader = conn.inputStream.bufferedReader(Charsets.UTF_8)
            var firstFrame: String? = null
            while (firstFrame == null) {
                val line = reader.readLine() ?: break
                if (line.startsWith("data:")) firstFrame = line
            }
            assertTrue(firstFrame != null && firstFrame.contains("notifications/progress"), "首帧应是进度帧：$firstFrame")
            conn.disconnect()
            clientGone.countDown()

            assertTrue(finished.await(5, TimeUnit.SECONDS), "客户端断开后工具仍要执行完（进度是增强，不是依赖）")

            // 服务没被拖死：下一条普通请求照常返回
            val next = post(port, token, """{"name":"sse_probe","arguments":{}}""", "application/json")
            assertEquals(200, next.status)
            assertTrue(next.frames[0]["result"] != null, "断开之后服务仍可用：${next.frames}")
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `SSE 下的未知工具——流内错误帧而不是挂死`() {
        val (bridge, token) = bridgeWith(FramesTool(listOf("用不上")))
        val port = bridge.start(0)
        try {
            val resp = post(
                port, token,
                """{"name":"no_such_tool","arguments":{},"_meta":{"progressToken":"tok-7"}}""",
                "text/event-stream",
            )
            assertEquals(200, resp.status, "已经开流就只能 200，错误以帧的形式存在")
            assertTrue(resp.contentType.contains("text/event-stream"))
            assertEquals(1, resp.frames.size, "应只有一帧错误帧：${resp.frames}")
            val error = resp.frames[0]["error"] as JsonObject
            // T-056：未知工具是「参数非法」（-32602），不是内部错误（-32603）
            assertEquals(-32602L, error["code"]!!.toString().toLong())
            assertTrue(
                error["message"].toString().contains(Messages.get("error.unknown_tool").substringBefore("{")),
                "错误文案要能读懂：$error",
            )
        } finally {
            bridge.stop()
        }
    }
}
