package com.stragent.data

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * T-166 · MCP Tasks 扩展与官方 `io.modelcontextprotocol/tasks` 的 wire 格式对齐。
 *
 * 规范原文：`modelcontextprotocol/ext-tasks` 仓 `schema/2026-07-28/schema.ts`
 * （官方文档页 `modelcontextprotocol.io/extensions/tasks/overview.md`）。
 *
 * 覆盖：能力协商形状（`capabilities.extensions`）/ `CreateTaskResult`
 * （`resultType:"task"` + Task 字段）/ `GetTaskResult`（DetailedTask 状态专属字段）/
 * `UpdateTaskResult` 与 `CancelTaskResult`（**空确认**）/ failed 的 `error` 是
 * JSON-RPC 错误对象 / 中途输入（`input_required` + `inputRequests` → `tasks/update`
 * 交付答案 → 任务继续跑完）/ 未知 key 的答案被忽略。
 *
 * 边界（诚实）：`notifications/tasks` 与 `subscriptions/listen`（官方 MAY）未实现——
 * 轮询是默认路径，官方亦如此。
 */
class McpTasksExtTest {

    private class MapPrefs : com.stragent.core.ports.PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private fun bridge(vararg tools: com.stragent.core.ports.AgentTool): McpBridge =
        McpBridge(AbilityDatasets(), MapPrefs(), extraTools = tools.toList())

    /** 起一个异步任务（`_meta.task`），可声明官方扩展能力 */
    private fun startTask(
        bridge: McpBridge,
        tool: String,
        declareExtension: Boolean = false,
    ): JsonObject = bridge.dispatch(
        "tools/call",
        buildJsonObject {
            put("name", tool)
            put("arguments", JsonObject(emptyMap()))
            put(
                "_meta",
                buildJsonObject {
                    put("task", buildJsonObject { })
                    if (declareExtension) {
                        put(
                            "io.modelcontextprotocol/clientCapabilities",
                            buildJsonObject {
                                put(
                                    "extensions",
                                    buildJsonObject { put("io.modelcontextprotocol/tasks", buildJsonObject { }) },
                                )
                            },
                        )
                    }
                },
            )
        },
    )

    private fun getTask(bridge: McpBridge, taskId: String): JsonObject =
        bridge.dispatch("tasks/get", buildJsonObject { put("taskId", taskId) })

    private fun status(task: JsonObject): String = task["status"]!!.jsonPrimitive.content

    private fun awaitStatus(bridge: McpBridge, taskId: String, expected: String, timeoutMs: Long = 5_000): JsonObject {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var last: JsonObject? = null
        while (System.nanoTime() < deadline) {
            last = getTask(bridge, taskId)
            if (status(last) == expected) return last
            Thread.sleep(10)
        }
        throw AssertionError("任务 $taskId 未在 ${timeoutMs}ms 内进入 $expected：$last")
    }

    // ---------------- 能力协商 ----------------

    @Test
    fun `能力协商——extensions 下按官方 id 公布 tasks 扩展，旧形状仍在`() {
        val bridge = bridge()
        try {
            val init = bridge.dispatch("initialize", JsonObject(emptyMap()))
            val capabilities = init["capabilities"]!!.jsonObject
            val extensions = capabilities["extensions"]!!.jsonObject
            assertTrue(
                extensions.containsKey("io.modelcontextprotocol/tasks"),
                "官方扩展 id 必须出现在 capabilities.extensions：$capabilities",
            )
            assertEquals(
                0,
                extensions["io.modelcontextprotocol/tasks"]!!.jsonObject.size,
                "官方口径：扩展能力对象是空对象",
            )
            // 旧形状保留（仓内既有客户端按它读三件套）
            val tasks = capabilities["tasks"]!!.jsonObject
            assertEquals("true", tasks["get"]!!.jsonPrimitive.content)
            assertEquals("true", tasks["update"]!!.jsonPrimitive.content)
            assertEquals("true", tasks["cancel"]!!.jsonPrimitive.content)

            // server/discover 与 initialize 公布同一份能力
            val discover = bridge.dispatch("server/discover", JsonObject(emptyMap()))
            assertTrue(
                discover["capabilities"]!!.jsonObject["extensions"]!!.jsonObject
                    .containsKey("io.modelcontextprotocol/tasks"),
                "server/discover 也要公布扩展能力：$discover",
            )
        } finally {
            bridge.stop()
        }
    }

    // ---------------- CreateTaskResult ----------------

    @Test
    fun `创建任务——官方 CreateTaskResult 形状（resultType=task + Task 字段）`() {
        val bridge = bridge(SlowTool(steps = 1))
        try {
            val handle = startTask(bridge, "slow_tool")
            assertEquals("task", handle["resultType"]!!.jsonPrimitive.content, "官方判别字段：$handle")
            assertTrue(handle["taskId"]!!.jsonPrimitive.content.startsWith("task_"))
            assertEquals("working", handle["status"]!!.jsonPrimitive.content)
            assertNotNull(handle["createdAt"]!!.jsonPrimitive.content, "createdAt 必须在场")
            assertNotNull(handle["lastUpdatedAt"]!!.jsonPrimitive.content, "lastUpdatedAt 必须在场")
            assertTrue(handle.containsKey("ttlMs"), "ttlMs 必须在场：$handle")
            assertNotNull(handle["pollIntervalMs"]!!.jsonPrimitive.content, "pollIntervalMs 必须在场")
            // 仓内扩展字段仍在（客户端指引）
            assertEquals("tasks/get", handle["pollWith"]!!.jsonPrimitive.content)
            assertEquals("slow_tool", handle["tool"]!!.jsonPrimitive.content)
        } finally {
            bridge.stop()
        }
    }

    // ---------------- GetTaskResult ----------------

    @Test
    fun `轮询任务——官方 GetTaskResult 形状 + 状态专属字段`() {
        val bridge = bridge(SlowTool(steps = 1))
        try {
            val taskId = startTask(bridge, "slow_tool")["taskId"]!!.jsonPrimitive.content
            val done = awaitStatus(bridge, taskId, "completed")
            assertEquals("complete", done["resultType"]!!.jsonPrimitive.content)
            assertEquals("completed", status(done))
            assertNotNull(done["createdAt"]!!.jsonPrimitive.content)
            assertNotNull(done["lastUpdatedAt"]!!.jsonPrimitive.content)
            assertTrue(done.containsKey("ttlMs"), "ttlMs 必须在场：$done")
            assertTrue(done.containsKey("pollIntervalMs"), "pollIntervalMs 必须在场：$done")
            assertNotNull(done["result"], "completed 必须带 result：$done")
            // 状态专属字段只在对应状态出现
            assertNull(done["inputRequests"], "completed 不带 inputRequests：$done")
            assertNull(done["error"], "completed 不带 error：$done")
        } finally {
            bridge.stop()
        }
    }

    // ---------------- 空确认 ----------------

    @Test
    fun `tasks update 与 tasks cancel——官方口径是空确认`() {
        val bridge = bridge(SlowTool(steps = 1))
        try {
            val taskId = startTask(bridge, "slow_tool")["taskId"]!!.jsonPrimitive.content
            val updateAck = bridge.dispatch(
                "tasks/update",
                buildJsonObject { put("taskId", taskId) },
            )
            assertEquals("complete", updateAck["resultType"]!!.jsonPrimitive.content)
            assertEquals(1, updateAck.size, "空确认不带其他字段：$updateAck")

            val cancelAck = bridge.dispatch(
                "tasks/cancel",
                buildJsonObject { put("taskId", taskId) },
            )
            assertEquals("complete", cancelAck["resultType"]!!.jsonPrimitive.content)
            assertEquals(1, cancelAck.size, "空确认不带其他字段：$cancelAck")
        } finally {
            bridge.stop()
        }
    }

    // ---------------- failed 的 error 形状 ----------------

    @Test
    fun `失败任务——error 是 JSON-RPC 错误对象（code + message）`() {
        val bridge = bridge(SlowTool(steps = 1, failWith = "模拟失败"))
        try {
            val taskId = startTask(bridge, "slow_tool")["taskId"]!!.jsonPrimitive.content
            val failed = awaitStatus(bridge, taskId, "failed")
            val error = failed["error"]!!.jsonObject
            assertEquals("模拟失败", error["message"]!!.jsonPrimitive.content)
            assertTrue(error.containsKey("code"), "error 必须带 code：$failed")
            assertNull(failed["result"], "失败任务不带 result：$failed")
        } finally {
            bridge.stop()
        }
    }

    // ---------------- 中途输入（input_required → tasks/update → 继续） ----------------

    @Test
    fun `中途需要输入——任务进 input_required，交付答案后跑完`() {
        val bridge = bridge(ElicitingTool())
        try {
            val handle = startTask(bridge, "eliciting_tool", declareExtension = true)
            val taskId = handle["taskId"]!!.jsonPrimitive.content

            val waiting = awaitStatus(bridge, taskId, "input_required")
            val requests = waiting["inputRequests"]!!.jsonObject
            assertEquals(1, requests.size, "待答问题表：$waiting")
            val key = requests.keys.first()
            val request = requests[key]!!.jsonObject
            assertEquals("elicitation/create", request["method"]!!.jsonPrimitive.content)
            assertTrue(request["params"]!!.jsonObject.containsKey("requestedSchema"), "问题必须带 schema：$request")

            // 交付答案（官方形态：inputResponses 按 key 对应）
            val ack = bridge.dispatch(
                "tasks/update",
                buildJsonObject {
                    put("taskId", taskId)
                    put(
                        "inputResponses",
                        buildJsonObject {
                            put(
                                key,
                                buildJsonObject {
                                    put("action", "accept")
                                    put("content", buildJsonObject { put("ok", true) })
                                },
                            )
                        },
                    )
                },
            )
            assertEquals("complete", ack["resultType"]!!.jsonPrimitive.content)

            val done = awaitStatus(bridge, taskId, "completed")
            val result = done["result"]!!.jsonObject
            val structured = result["structuredContent"]!!.jsonObject
            assertEquals("true", structured["answered"]!!.jsonPrimitive.content, "带上答案后应跑完：$done")
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `未知 key 的答案被忽略——不打断任务，也不误判为已交付`() {
        val bridge = bridge(ElicitingTool())
        try {
            val taskId = startTask(bridge, "eliciting_tool", declareExtension = true)["taskId"]!!.jsonPrimitive.content
            awaitStatus(bridge, taskId, "input_required")

            bridge.dispatch(
                "tasks/update",
                buildJsonObject {
                    put("taskId", taskId)
                    put("inputResponses", buildJsonObject { put("ask_not_pending", buildJsonObject { }) })
                },
            )
            // 未知 key 不应把任务推进到 working（仍在等待）
            assertEquals("input_required", status(getTask(bridge, taskId)))
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `多问流程——前一问的答案在重跑时仍能命中`() {
        val bridge = bridge(TwoStepTool())
        try {
            val taskId = startTask(bridge, "two_step_tool", declareExtension = true)["taskId"]!!.jsonPrimitive.content

            val first = awaitStatus(bridge, taskId, "input_required")
            val key1 = first["inputRequests"]!!.jsonObject.keys.first()
            bridge.dispatch(
                "tasks/update",
                buildJsonObject {
                    put("taskId", taskId)
                    put("inputResponses", buildJsonObject { put(key1, acceptAnswer("一")) })
                },
            )

            // 第二问：重跑时第一问已命中，任务改问下一个问题
            val second = awaitStatus(bridge, taskId, "input_required")
            val key2 = second["inputRequests"]!!.jsonObject.keys.first()
            assertTrue(key2 != key1, "第二问应是新的 key：$second")
            bridge.dispatch(
                "tasks/update",
                buildJsonObject {
                    put("taskId", taskId)
                    put("inputResponses", buildJsonObject { put(key2, acceptAnswer("二")) })
                },
            )

            val done = awaitStatus(bridge, taskId, "completed")
            val structured = done["result"]!!.jsonObject["structuredContent"]!!.jsonObject
            assertTrue(structured["answers"]!!.jsonPrimitive.content.contains("一"), "第一问答案应保留：$done")
            assertTrue(structured["answers"]!!.jsonPrimitive.content.contains("二"), "第二问答案应带上：$done")
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `等待输入时被取消——立即收口，不占着工作线程等到超时`() {
        val bridge = bridge(ElicitingTool())
        try {
            val taskId = startTask(bridge, "eliciting_tool", declareExtension = true)["taskId"]!!.jsonPrimitive.content
            awaitStatus(bridge, taskId, "input_required")

            bridge.dispatch("tasks/cancel", buildJsonObject { put("taskId", taskId) })

            val cancelled = awaitStatus(bridge, taskId, "cancelled", timeoutMs = 3_000)
            assertEquals("cancelled", status(cancelled))
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `未声明扩展能力的客户端——不走任务输入流（提问点按既有语义处理）`() {
        val bridge = bridge(ElicitingTool())
        try {
            val taskId = startTask(bridge, "eliciting_tool", declareExtension = false)["taskId"]!!.jsonPrimitive.content
            // 未开启任务输入流 → 提问点不转成 input_required，任务按失败如实收口
            // （真实工具走的是既有直发通道：SSE 提问 + 等待应答；这里替身直接抛控制流）
            val failed = awaitStatus(bridge, taskId, "failed")
            assertNotNull(failed["error"], "应如实落失败：$failed")
            assertNull(failed["inputRequests"], "非任务输入流不带 inputRequests：$failed")
        } finally {
            bridge.stop()
        }
    }

    // ---------------- 测试替身 ----------------

    /** 官方形态的应答：`{action: "accept", content: {...}}` */
    private fun acceptAnswer(text: String): JsonObject = buildJsonObject {
        put("action", "accept")
        put("content", buildJsonObject { put("text", text) })
    }

    /** 模拟提问点：没有答案就抛 MRTR 控制流，有答案就返回 */
    private class ElicitingTool : com.stragent.core.ports.AgentTool {
        override val name = "eliciting_tool"
        override val description = "测试替身：中途提问"
        override val parametersSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        }
        override val cacheable = false
        override val readOnly = true
        override val destructive = false

        override fun invoke(args: JsonObject): JsonObject {
            val key = "ask_test_fixed"
            val schema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("ok", buildJsonObject { put("type", "boolean") })
                })
            }
            val answer = MrtrContext.current()?.lookup(key)
                ?: throw McpInputRequiredException(key, "请确认口径", schema)
            return buildJsonObject {
                put("answered", answer["content"]!!.jsonObject["ok"]!!.jsonPrimitive)
            }
        }
    }

    /** 让任务多跑一会儿，便于观察中间状态 */
    private class SlowTool(private val steps: Int, private val failWith: String? = null) :
        com.stragent.core.ports.AgentTool {
        override val name = "slow_tool"
        override val description = "测试替身：慢任务"
        override val parametersSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        }
        override val cacheable = false
        override val readOnly = true
        override val destructive = false

        override fun invoke(args: JsonObject): JsonObject {
            val latch = CountDownLatch(1)
            latch.await(50, TimeUnit.MILLISECONDS)
            failWith?.let { throw IllegalStateException(it) }
            return buildJsonObject { put("ok", true); put("steps", steps) }
        }
    }

    /** 连续问两问的替身（验证「前一问的答案在重跑时仍能命中」） */
    private class TwoStepTool : com.stragent.core.ports.AgentTool {
        override val name = "two_step_tool"
        override val description = "测试替身：连续两问"
        override val parametersSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        }
        override val cacheable = false
        override val readOnly = true
        override val destructive = false

        override fun invoke(args: JsonObject): JsonObject {
            val ctx = MrtrContext.current()
            val first = ctx?.lookup(KEY_ONE)
                ?: throw McpInputRequiredException(KEY_ONE, "问题一", schema())
            val second = ctx?.lookup(KEY_TWO)
                ?: throw McpInputRequiredException(KEY_TWO, "问题二", schema())
            return buildJsonObject {
                put("answers", first.toString() + " / " + second.toString())
            }
        }

        private fun schema(): JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("text", buildJsonObject { put("type", "string") }) })
        }

        companion object {
            const val KEY_ONE = "ask_two_step_1"
            const val KEY_TWO = "ask_two_step_2"
        }
    }
}
