package com.stragent.data

import com.stragent.core.ports.AgentTool
import com.stragent.core.ports.PrefsStore
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * T-165 · MRTR（2026-07-28 Multi Round-Trip Requests）测试。
 *
 * 覆盖：
 *  1. modern 首次调用 → InputRequiredResult 信封（resultType/inputRequests/requestState）；
 *  2. 带 inputResponses + 回显 requestState 重试 → 答案消费、调用完成；
 *  3. decline / 预答案（无 state）两条支路；
 *  4. requestState 四重绑定（篡改/过期/参数漂移/会话漂移）全部拒绝；
 *  5. 能力闸：客户端未声明 elicitation → cancel 降级（不发 inputRequests）；
 *  6. 遗留请求（无 _meta）维持直发通道语义（无通道 cancel）；
 *  7. 双问流程（T-077 双问的 MRTR 等价物）：两轮 InputRequiredResult 接续；
 *  8. HTTP 端到端：modern 请求的路由头 + 两步 MRTR。
 *
 * 边界（诚实）：异步任务路径不走 MRTR（工作线程无 ThreadLocal，维持直发）——
 * 异步语义由 McpTaskTest 覆盖；本轮 MRTR 只接同步 tools/call。
 */
/** 测试用提问 Schema（平铺字符串字段） */
private fun askSchema(field: String): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject { put(field, buildJsonObject { put("type", "string") }) })
}

class McpMrtrTest {

    private class MapPrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    /** 单问探针：向客户端问「报告标题？」并把结局摊回 JSON */
    private class AskProbe(private val broker: ElicitationBroker) : AgentTool {
        override val name = "ask_probe"
        override val description = "测试用：提一问并回传结局"
        override val parametersSchema = buildJsonObject { put("type", "object") }
        override val cacheable = false
        override fun invoke(args: JsonObject): JsonObject {
            val outcome = broker.request(
                McpSessionContext.current(),
                (args["question"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "报告标题叫什么？",
                askSchema("title"),
                1_000,
            )
            return buildJsonObject {
                put("action", outcome.action.wire)
                outcome.content?.let { put("content", it) }
                outcome.reason?.let { put("reason", it) }
            }
        }
    }

    /** 双问探针：先问标题、再问副标题（T-077 双问的 MRTR 等价物），终局带两问结果 */
    private class TwoAskProbe(private val broker: ElicitationBroker) : AgentTool {
        override val name = "two_ask_probe"
        override val description = "测试用：连提两问"
        override val parametersSchema = buildJsonObject { put("type", "object") }
        override val cacheable = false
        override fun invoke(args: JsonObject): JsonObject {
            val first = broker.request(McpSessionContext.current(), "第一问：报告标题？", askSchema("title"), 1_000)
            val second = broker.request(McpSessionContext.current(), "第二问：副标题？", askSchema("subtitle"), 1_000)
            return buildJsonObject {
                put("first", first.toJson())
                put("second", second.toJson())
            }
        }
    }

    private fun bridge(vararg tools: AgentTool, broker: ElicitationBroker = ElicitationBroker()): Pair<McpBridge, String> {
        val prefs = MapPrefs()
        val bridge = McpBridge(AbilityDatasets(), prefs, extraTools = tools.toList(), elicitation = broker)
        return bridge to McpBridge.currentToken(prefs)
    }

    private fun meta(caps: JsonObject? = null): JsonObject = buildJsonObject {
        put("io.modelcontextprotocol/protocolVersion", JsonPrimitive(McpProtocol.LATEST))
        caps?.let { put("io.modelcontextprotocol/clientCapabilities", it) }
    }

    private fun callParams(name: String, caps: JsonObject? = null): JsonObject = buildJsonObject {
        put("name", name)
        put("arguments", buildJsonObject { })
        put("_meta", meta(caps))
    }

    /** 重试参数：inputResponses + 回显 requestState（字符串原样回显，spec 口径） */
    private fun retryParams(name: String, key: String, resp: JsonObject, state: String?): JsonObject = buildJsonObject {
        put("name", name)
        put("arguments", buildJsonObject { })
        put("_meta", meta())
        put("inputResponses", buildJsonObject { put(key, resp) })
        state?.let { put("requestState", JsonPrimitive(it)) }
    }

    private fun mrtrCall(bridge: McpBridge, name: String, caps: JsonObject? = null): JsonObject =
        bridge.dispatch("tools/call", callParams(name, caps), "id-mrtr")

    private fun retry(bridge: McpBridge, first: JsonObject, action: String, value: String): JsonObject {
        val key = soleAskKey(first)
        val state = (first["requestState"] as JsonPrimitive).content
        return bridge.dispatch(
            "tools/call",
            retryParams("ask_probe", key, buildJsonObject {
                put("action", action)
                put("content", buildJsonObject { put("title", JsonPrimitive(value)) })
            }, state),
            "id-retry",
        )
    }

    /** 从 InputRequiredResult 里取单问的 key（测试流程恒单问） */
    private fun soleAskKey(result: JsonObject): String {
        val asks = result["inputRequests"]!!.jsonObject
        assertEquals(1, asks.size, "单问信封应只有一个 inputRequest：$result")
        val entry = asks.entries.first()
        val request = entry.value.jsonObject
        assertEquals("elicitation/create", request["method"]!!.jsonPrimitive.content)
        assertEquals("form", request["params"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        return entry.key
    }

    // ---------------- 1. 信封形制 ----------------

    @Test
    fun `modern 首次调用——回 input_required 信封，inputRequests 是 elicitation create 形制`() {
        val (bridge, _) = bridge(AskProbe(ElicitationBroker()))
        try {
            val result = mrtrCall(bridge, "ask_probe")
            assertEquals("input_required", result["resultType"]!!.jsonPrimitive.content, "$result")
            val key = soleAskKey(result)
            assertTrue(key.startsWith("ask_"), "key 内容派生：$key")
            assertTrue(
                (result["requestState"] as? JsonPrimitive)?.contentOrNull?.contains(".") == true,
                "requestState 是 payload.signature 两段：$result",
            )
        } finally {
            bridge.stop()
        }
    }

    // ---------------- 2. 重试消费 ----------------

    @Test
    fun `重试带 inputResponses 加回显 requestState——答案就地消费，调用完成`() {
        val (bridge, _) = bridge(AskProbe(ElicitationBroker()))
        try {
            val first = mrtrCall(bridge, "ask_probe")
            val final = retry(bridge, first, "accept", "季度复盘")
            assertEquals("complete", final["resultType"]!!.jsonPrimitive.content, "重试应完成：$final")
            val body = final["structuredContent"]!!.jsonObject
            assertEquals("accept", body["action"]!!.jsonPrimitive.content, "$final")
            assertEquals("季度复盘", body["content"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `decline 与预答案——decline 原样回传；无 state 的预答案直接完成（spec：没收到过信封就没有 state 可回显）`() {
        val (bridge, _) = bridge(AskProbe(ElicitationBroker()))
        try {
            val first = mrtrCall(bridge, "ask_probe")
            val key = soleAskKey(first)
            val state = (first["requestState"] as JsonPrimitive).content

            val declined = bridge.dispatch(
                "tools/call",
                retryParams("ask_probe", key, buildJsonObject { put("action", "decline") }, state),
                "id-decline",
            )
            assertEquals("decline", declined["structuredContent"]!!.jsonObject["action"]!!.jsonPrimitive.content, "$declined")

            val pre = bridge.dispatch(
                "tools/call",
                retryParams("ask_probe", key, buildJsonObject {
                    put("action", "accept")
                    put("content", buildJsonObject { put("title", JsonPrimitive("预填")) })
                }, null),
                "id-pre",
            )
            assertEquals("complete", pre["resultType"]!!.jsonPrimitive.content, "预答案直接完成：$pre")
            assertEquals("预填", pre["structuredContent"]!!.jsonObject["content"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        } finally {
            bridge.stop()
        }
    }

    // ---------------- 3. requestState 四重绑定 ----------------

    @Test
    fun `篡改 requestState——HMAC 校验失败按 -32602 拒绝`() {
        val (bridge, _) = bridge(AskProbe(ElicitationBroker()))
        try {
            val first = mrtrCall(bridge, "ask_probe")
            val key = soleAskKey(first)
            val state = (first["requestState"] as JsonPrimitive).content
            val tampered = state.dropLast(3) + "AAA"
            val error = assertFailsWith<McpInvalidParamsException> {
                bridge.dispatch(
                    "tools/call",
                    retryParams("ask_probe", key, buildJsonObject { put("action", "accept") }, tampered),
                    "id-tamper",
                )
            }
            assertTrue(error.message!!.contains("校验失败"), "拒绝原因要指明校验失败：${error.message}")
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `过期与参数漂移——TTL 到期与 arguments 改动都拒绝`() {
        val (bridge, _) = bridge(AskProbe(ElicitationBroker()))
        try {
            val first = mrtrCall(bridge, "ask_probe")
            val key = soleAskKey(first)
            val state = (first["requestState"] as JsonPrimitive).content

            // TTL 到期：内部编解码直验（ttl 归零签发的 state 立即过期）
            McpRequestState.ttlMs = 0
            val expired = McpRequestState.issue("tools/call", "ask_probe", argsDigest(JsonObject(emptyMap())), "default", emptyList())
            McpRequestState.ttlMs = 10 * 60_000L
            val expiredError = assertFailsWith<McpInvalidParamsException> {
                McpRequestState.decode(expired, "tools/call", "ask_probe", argsDigest(JsonObject(emptyMap())), "default")
            }
            assertTrue(expiredError.message!!.contains("过期"), "${expiredError.message}")

            // 参数漂移：state 对空 args 签发，重试改 arguments → 四重绑定拒绝
            val drifted = assertFailsWith<McpInvalidParamsException> {
                bridge.dispatch(
                    "tools/call",
                    buildJsonObject {
                        put("name", "ask_probe")
                        put("arguments", buildJsonObject { put("question", JsonPrimitive("换一个问法")) })
                        put("_meta", meta())
                        put("inputResponses", buildJsonObject {
                            put(key, buildJsonObject { put("action", "accept") })
                        })
                        put("requestState", JsonPrimitive(state))
                    },
                    "id-drift",
                )
            }
            assertTrue(drifted.message!!.contains("不匹配"), "${drifted.message}")
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `会话漂移——别的会话回放 state 被拒（principal 绑定）`() {
        val (bridge, _) = bridge(AskProbe(ElicitationBroker()))
        try {
            val fresh = McpSessionContext.with("session-A") { mrtrCall(bridge, "ask_probe") }
            val key = soleAskKey(fresh)
            val replay = assertFailsWith<McpInvalidParamsException> {
                McpSessionContext.with("session-B") {
                    bridge.dispatch(
                        "tools/call",
                        retryParams("ask_probe", key, buildJsonObject { put("action", "accept") }, (fresh["requestState"] as JsonPrimitive).content),
                        "id-replay",
                    )
                }
            }
            assertTrue(replay.message!!.contains("不匹配"), "${replay.message}")
        } finally {
            bridge.stop()
        }
    }

    // ---------------- 4. 能力闸与遗留路径 ----------------

    @Test
    fun `能力闸——客户端未声明 elicitation 时按 cancel 降级，不发 inputRequests`() {
        val (bridge, _) = bridge(AskProbe(ElicitationBroker()))
        try {
            val result = mrtrCall(bridge, "ask_probe", caps = buildJsonObject { })
            assertEquals("complete", result["resultType"]!!.jsonPrimitive.content, "降级是正常完成：$result")
            assertEquals("cancel", result["structuredContent"]!!.jsonObject["action"]!!.jsonPrimitive.content, "$result")
            assertTrue(
                result["structuredContent"]!!.jsonObject["reason"]!!.jsonPrimitive.content.contains("能力"),
                "原因要指向能力缺失：$result",
            )
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `遗留请求——无 _meta 维持直发语义（无通道即 cancel），MRTR 不激活`() {
        val (bridge, _) = bridge(AskProbe(ElicitationBroker()))
        try {
            val result = bridge.dispatch(
                "tools/call",
                buildJsonObject { put("name", "ask_probe"); put("arguments", buildJsonObject { }) },
                "id-legacy",
            )
            val body = result["structuredContent"]!!.jsonObject
            assertEquals("cancel", body["action"]!!.jsonPrimitive.content, "$result")
            assertTrue(body["reason"]!!.jsonPrimitive.content.contains("通道"), "仍是通道语义：$result")
        } finally {
            bridge.stop()
        }
    }

    // ---------------- 5. 双问流程 ----------------

    @Test
    fun `双问——两轮 InputRequiredResult 接续，终局带两问结果（T-077 的 MRTR 等价）`() {
        val (bridge, _) = bridge(TwoAskProbe(ElicitationBroker()))
        try {
            val r1 = mrtrCall(bridge, "two_ask_probe")
            assertEquals("input_required", r1["resultType"]!!.jsonPrimitive.content, "$r1")
            val k1 = soleAskKey(r1)
            val s1 = (r1["requestState"] as JsonPrimitive).content

            // 第二轮：带第一问答案 → 抛出第二问（requestState 封入第一问答案）
            val r2 = bridge.dispatch(
                "tools/call",
                retryParams("two_ask_probe", k1, buildJsonObject {
                    put("action", "accept")
                    put("content", buildJsonObject { put("title", JsonPrimitive("标题A")) })
                }, s1),
                "id-two-2",
            )
            assertEquals("input_required", r2["resultType"]!!.jsonPrimitive.content, "第二问继续停机：$r2")
            val k2 = soleAskKey(r2)
            assertTrue(k2 != k1, "两问 key 必不同（内容派生）：$k1 vs $k2")
            val s2 = (r2["requestState"] as JsonPrimitive).content
            assertTrue(s2 != s1, "第二轮 state 重新签发（封入第一问答案）")

            // 第三次调用：带第二问答案 → 两问齐备，调用完成
            val r3 = bridge.dispatch(
                "tools/call",
                retryParams("two_ask_probe", k2, buildJsonObject {
                    put("action", "accept")
                    put("content", buildJsonObject { put("subtitle", JsonPrimitive("副标B")) })
                }, s2),
                "id-two-3",
            )
            assertEquals("complete", r3["resultType"]!!.jsonPrimitive.content, "两问齐备应完成：$r3")
            val body = r3["structuredContent"]!!.jsonObject
            assertEquals("accept", body["first"]!!.jsonObject["action"]!!.jsonPrimitive.content, "$r3")
            assertEquals("标题A", body["first"]!!.jsonObject["content"]!!.jsonObject["title"]!!.jsonPrimitive.content)
            assertEquals("副标B", body["second"]!!.jsonObject["content"]!!.jsonObject["subtitle"]!!.jsonPrimitive.content)
        } finally {
            bridge.stop()
        }
    }

    // ---------------- 6. HTTP 端到端 ----------------

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

    @Test
    fun `HTTP 端到端——modern 请求两步 MRTR（路由头齐备）`() {
        val (bridge, token) = bridge(AskProbe(ElicitationBroker()))
        val port = bridge.start(0)
        try {
            val headers = mapOf(
                McpProtocol.PROTOCOL_HEADER to McpProtocol.LATEST,
                McpProtocol.METHOD_HEADER to "tools/call",
                McpProtocol.NAME_HEADER to "ask_probe",
            )
            fun post(body: String): Pair<Int, String> {
                val conn = URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $token")
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val status = conn.responseCode
                val stream = if (status >= 400) conn.errorStream else conn.inputStream
                val text = stream?.readBytes()?.decodeToString() ?: ""
                conn.disconnect()
                return status to text
            }

            val body1 = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"ask_probe","arguments":{},
                "_meta":{"io.modelcontextprotocol/protocolVersion":"${McpProtocol.LATEST}"}}}""".replace("\n", "")
            val (s1, t1) = post(body1)
            assertEquals(200, s1, t1)
            val first = Json.parseToJsonElement(t1).jsonObject["result"]!!.jsonObject
            assertEquals("input_required", first["resultType"]!!.jsonPrimitive.content, t1)
            val key = soleAskKey(first)
            val state = first["requestState"]!!.jsonPrimitive.content

            val body2 = """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"ask_probe","arguments":{},
                "_meta":{"io.modelcontextprotocol/protocolVersion":"${McpProtocol.LATEST}"},
                "inputResponses":{"$key":{"action":"accept","content":{"title":"HTTP 答案"}}},
                "requestState":"$state"}}""".replace("\n", "")
            val (s2, t2) = post(body2)
            assertEquals(200, s2, t2)
            val final = Json.parseToJsonElement(t2).jsonObject["result"]!!.jsonObject
            assertEquals("complete", final["resultType"]!!.jsonPrimitive.content, t2)
            assertEquals("accept", final["structuredContent"]!!.jsonObject["action"]!!.jsonPrimitive.content, t2)
            assertEquals("HTTP 答案", final["structuredContent"]!!.jsonObject["content"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        } finally {
            bridge.stop()
        }
    }
}
