package com.stragent.data

import com.stragent.core.ports.AgentTool
import com.stragent.core.ports.ProgressBus
import com.stragent.core.ai.GenerationCancelled
import com.stragent.core.ports.PrefsStore
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * T-056 / D17 G-15 · MCP Tasks 扩展（`_meta.task` 异步调用 + `tasks/get|update|cancel`）。
 *
 * 钉住的语义：
 *  1. **异步与同步逐字一致**：同一工具、同一套 session/progress/溯源/会话指针语义，
 *     只是结果从「响应体」搬到「任务表」，`structuredContent` 完全一致；
 *  2. **取消是协作式的**：`tasks/cancel` 立刻可见 `cancelRequested`，工具在可停点
 *     自行退出后状态才变 `cancelled`；工具不理会取消就如实记 `completed`，不假装；
 *  3. **过期纪律**：`working` 任务不因 TTL 被删（否则没法取消/取结果），终态任务
 *     保留默认 15 分钟，`tasks/update` 只延不缩；
 *  4. 未知/过期任务、缺参一律 -32602（不是内部错误）；
 *  5. 会话隔离：取消 A 会话的任务不影响 B；
 *  6. HTTP 线上真实可轮询（与 stdio 共用同一 dispatch）。
 */
class McpTaskTest {

    private class MapPrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    /** 可配置的慢工具：报告进度、可选响应取消、可指定失败 */
    private class SlowTool(
        override val name: String = "slow_tool",
        private val steps: Int = 2,
        private val stepSleepMs: Long = 10,
        private val honourCancel: Boolean = true,
        private val failWith: String? = null,
        private val output: String = "done",
    ) : AgentTool {
        override val description = "测试用：带进度与协作式取消的长任务"
        override val parametersSchema = buildJsonObject { put("type", "object") }
        val sawSignal = AtomicBoolean(false)

        override fun invoke(args: JsonObject): JsonObject {
            val signal = McpRequestCancellation.signal()
            repeat(steps) { i ->
                ProgressBus.emit("step ${i + 1}/$steps")
                Thread.sleep(stepSleepMs)
                if (honourCancel && signal()) {
                    sawSignal.set(true)
                    throw GenerationCancelled("测试工具响应取消")
                }
            }
            failWith?.let { throw IllegalArgumentException(it) }
            return buildJsonObject { put("output", output) }
        }
    }

    /** 阻塞到释放为止的工具：给「取消与完成」的竞态提供确定性时序 */
    private class BlockingTool(
        override val name: String,
        private val honourCancel: Boolean,
        /** 非 null 时先等这个门再开始轮询取消——让「cancelRequested 可见但状态未变」可确定复现 */
        private val pollGate: CountDownLatch? = null,
    ) : AgentTool {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        override val description = "测试用：阻塞到显式释放"
        override val parametersSchema = buildJsonObject { put("type", "object") }

        override fun invoke(args: JsonObject): JsonObject {
            val signal = McpRequestCancellation.signal()
            started.countDown()
            pollGate?.await(5, TimeUnit.SECONDS)
            while (!release.await(5, TimeUnit.MILLISECONDS)) {
                if (honourCancel && signal()) throw GenerationCancelled("测试工具响应取消")
            }
            return buildJsonObject { put("released", true) }
        }
    }

    private fun bridge(vararg tools: AgentTool, store: McpTaskStore = McpTaskStore()): Pair<McpBridge, String> {
        val prefs = MapPrefs()
        val bridge = McpBridge(AbilityDatasets(), prefs, extraTools = tools.toList(), taskStore = store)
        return bridge to McpBridge.currentToken(prefs)
    }

    private fun startTask(
        bridge: McpBridge,
        tool: String,
        args: JsonObject = JsonObject(emptyMap()),
        ttl: String? = null,
    ): JsonObject = bridge.dispatch(
        "tools/call",
        buildJsonObject {
            put("name", tool)
            put("arguments", args)
            put(
                "_meta",
                buildJsonObject {
                    put("task", buildJsonObject { ttl?.let { put("ttl", it) } })
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
        fail("任务 $taskId 未在 ${timeoutMs}ms 内进入 $expected：$last")
    }

    // ---- HTTP helpers（与 McpConformanceTest 同形制） ----

    private class HttpResult(val status: Int, val text: String)

    private fun post(port: Int, token: String, body: String): HttpResult {
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
        return HttpResult(status, text)
    }

    @Test
    fun `能力声明——tasks 三件套随实现一起公布`() {
        val (bridge, _) = bridge()
        try {
            val init = bridge.dispatch("initialize", JsonObject(emptyMap()))
            val tasks = init["capabilities"]!!.jsonObject["tasks"]!!.jsonObject
            assertEquals("true", tasks["get"]!!.jsonPrimitive.content)
            assertEquals("true", tasks["update"]!!.jsonPrimitive.content)
            assertEquals("true", tasks["cancel"]!!.jsonPrimitive.content)
            assertTrue(init["capabilities"]!!.jsonObject.containsKey("tools"), "tools 能力必须仍在：$init")
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `异步与同步逐字一致——structuredContent、content、会话语义都相同`() {
        val (bridge, _) = bridge(SlowTool(steps = 1))
        try {
            val sync = bridge.dispatch(
                "tools/call",
                buildJsonObject { put("name", "slow_tool"); put("arguments", JsonObject(emptyMap())) },
            )
            val handle = startTask(bridge, "slow_tool")
            assertTrue(handle["taskId"]!!.jsonPrimitive.content.startsWith("task_"), "应返回任务句柄：$handle")
            assertEquals("tasks/get", handle["pollWith"]!!.jsonPrimitive.content)
            assertEquals("slow_tool", handle["tool"]!!.jsonPrimitive.content)

            val taskId = handle["taskId"]!!.jsonPrimitive.content
            val done = awaitStatus(bridge, taskId, "completed")
            val async = done["result"]!!.jsonObject
            assertEquals(
                sync["structuredContent"], async["structuredContent"],
                "异步结果必须与同步结果一致：sync=$sync async=$async",
            )
            assertEquals(sync["content"].toString(), async["content"].toString())
            assertNull(done["error"], "成功任务不应有 error：$done")
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `进度进快照——长任务轮询能看到走到第几步`() {
        val (bridge, _) = bridge(SlowTool(steps = 3, stepSleepMs = 20))
        try {
            val handle = startTask(bridge, "slow_tool")
            val taskId = handle["taskId"]!!.jsonPrimitive.content
            val done = awaitStatus(bridge, taskId, "completed")
            val progress = done["progress"]!!.jsonObject
            assertEquals("3", progress["count"]!!.jsonPrimitive.content, "三条进度应全部记账：$done")
            assertEquals("step 3/3", progress["message"]!!.jsonPrimitive.content)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `协作式取消——cancelRequested 立刻可见，工具退出后状态才变 cancelled`() {
        val gate = CountDownLatch(1)
        val tool = BlockingTool("coop_tool", honourCancel = true, pollGate = gate)
        val (bridge, _) = bridge(tool)
        try {
            val handle = startTask(bridge, "coop_tool")
            val taskId = handle["taskId"]!!.jsonPrimitive.content
            assertTrue(tool.started.await(5, TimeUnit.SECONDS), "任务线程应已启动")

            val ack = bridge.dispatch("tasks/cancel", buildJsonObject { put("taskId", taskId) })
            assertEquals("complete", ack["resultType"]!!.jsonPrimitive.content, "官方口径：tasks/cancel 是空确认")
            assertEquals(1, ack.size, "空确认不带其他字段：$ack")
            val afterCancel = getTask(bridge, taskId)
            assertEquals("true", afterCancel["cancelRequested"]!!.jsonPrimitive.content)
            assertEquals("working", status(afterCancel), "工具还没退出时状态必须如实保持 working")

            gate.countDown()
            val cancelled = awaitStatus(bridge, taskId, "cancelled")
            assertNull(cancelled["result"], "取消的任务不应带结果：$cancelled")
            assertTrue(cancelled["statusMessage"]!!.jsonPrimitive.content.contains("取消"), "应说明取消原因：$cancelled")
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `取消不假装——工具不理会取消自己跑完就记 completed`() {
        val tool = BlockingTool("stubborn_tool", honourCancel = false)
        val (bridge, _) = bridge(tool)
        try {
            val handle = startTask(bridge, "stubborn_tool")
            val taskId = handle["taskId"]!!.jsonPrimitive.content
            assertTrue(tool.started.await(5, TimeUnit.SECONDS), "任务线程应已启动")

            bridge.dispatch("tasks/cancel", buildJsonObject { put("taskId", taskId) })
            tool.release.countDown()
            val done = awaitStatus(bridge, taskId, "completed")
            assertEquals("true", done["cancelRequested"]!!.jsonPrimitive.content, "取消请求仍要留痕")
            assertNotNull(done["result"], "工具确实产出结果，就应如实交付：$done")
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `会话隔离——取消 A 会话的任务不波及 B 会话`() {
        val a = BlockingTool("iso_tool_a", honourCancel = true)
        val b = BlockingTool("iso_tool_b", honourCancel = true)
        val (bridge, _) = bridge(a, b)
        try {
            val taskA = McpSessionContext.with("s-a") { startTask(bridge, "iso_tool_a") }["taskId"]!!.jsonPrimitive.content
            val taskB = McpSessionContext.with("s-b") { startTask(bridge, "iso_tool_b") }["taskId"]!!.jsonPrimitive.content
            assertTrue(a.started.await(5, TimeUnit.SECONDS) && b.started.await(5, TimeUnit.SECONDS), "两个任务都应启动")

            bridge.dispatch("tasks/cancel", buildJsonObject { put("taskId", taskA) })
            awaitStatus(bridge, taskA, "cancelled")
            assertEquals("working", status(getTask(bridge, taskB)), "B 会话的任务不应被 A 的取消波及")

            b.release.countDown()
            val doneB = awaitStatus(bridge, taskB, "completed")
            assertNotNull(doneB["result"], "B 应正常完成：$doneB")
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `失败如实落盘——工具抛错 → failed + error，不重试不伪装`() {
        val (bridge, _) = bridge(SlowTool(steps = 1, failWith = "模拟失败"))
        try {
            val handle = startTask(bridge, "slow_tool")
            val taskId = handle["taskId"]!!.jsonPrimitive.content
            val failed = awaitStatus(bridge, taskId, "failed")
            // 官方口径：error 是 JSON-RPC 错误对象（code + message），不是裸字符串
            val error = failed["error"]!!.jsonObject
            assertEquals("模拟失败", error["message"]!!.jsonPrimitive.content)
            assertTrue(error.containsKey("code"), "error 必须带 code：$failed")
            assertNull(failed["result"], "失败任务不应带结果：$failed")
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `未知或过期任务——tasks get update cancel 一律 -32602 而不是内部错误`() {
        val (bridge, _) = bridge()
        try {
            for (method in listOf("tasks/get", "tasks/update", "tasks/cancel")) {
                val missing = assertFailsWith<McpInvalidParamsException>("$method 对未知任务应报参数错误") {
                    bridge.dispatch(method, buildJsonObject { put("taskId", "task_missing") })
                }
                assertTrue(missing.message!!.contains("未知或已过期"), "$method：${missing.message}")
                assertFailsWith<McpInvalidParamsException>("$method 缺 taskId 应报参数错误") {
                    bridge.dispatch(method, JsonObject(emptyMap()))
                }
            }
            // 异步路径不吞「工具名未知」：马上就能判定的错误仍同步抛出
            assertFailsWith<McpInvalidParamsException> {
                startTask(bridge, "no_such_tool")
            }
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `过期纪律——working 不因 TTL 删除，终态保留，update 只延不缩（假时钟）`() {
        val now = AtomicLong(1_000_000L)
        val store = McpTaskStore(clock = { now.get() })

        val working = store.create("t", "s", ttlMs = 60_000)
        now.addAndGet(10 * 60_000)
        assertNotNull(store.get(working.id), "working 任务不因 TTL 过期（否则没法取消/取结果）")

        val task = store.create("t", "s", ttlMs = 60_000)
        store.complete(task.id, buildJsonObject { put("ok", true) })
        assertNotNull(store.get(task.id))

        val t0 = now.get()
        now.set(t0 + 10 * 60_000)
        assertNotNull(store.update(task.id, ttlMs = 30 * 60_000), "未过期时续期应成功")
        now.set(t0 + 20 * 60_000)
        assertNotNull(store.get(task.id), "续期后原到期点之后仍可读")
        now.set(t0 + 41 * 60_000)
        assertNull(store.get(task.id), "延后的到期点之后应清理")

        // 已过期后再 update：按「未知或已过期」处理，不复活
        assertNull(store.update(task.id, ttlMs = 30 * 60_000), "过期任务不可复活")
    }

    @Test
    fun `有界——满了先淘汰最老终态，全在途时拒绝新建（不排队）`() {
        val now = AtomicLong(1_000_000L)
        val store = McpTaskStore(maxTasks = 2, clock = { now.get() })
        val a = store.create("t", "s", null).also { store.complete(it.id, buildJsonObject { put("n", "a") }) }
        now.addAndGet(10)
        val b = store.create("t", "s", null).also { store.complete(it.id, buildJsonObject { put("n", "b") }) }
        now.addAndGet(10)
        val c = store.create("t", "s", null)
        assertEquals(2, store.size())
        assertNull(store.get(a.id), "最老的终态任务应先被淘汰")
        assertNotNull(store.get(b.id))
        assertNotNull(store.get(c.id))

        val single = McpTaskStore(maxTasks = 1, clock = { now.get() })
        single.create("t", "s", null)
        val rejected = assertFailsWith<IllegalStateException> { single.create("t", "s", null) }
        assertTrue(rejected.message!!.contains("上限"), "拒绝要说明原因：${rejected.message}")
    }

    @Test
    fun `HTTP——线上异步调用、轮询、取消与 stdio 同一套语义`() {
        val tool = BlockingTool("http_tool", honourCancel = true)
        val (bridge, token) = bridge(tool)
        val port = bridge.start(0)
        try {
            val started = post(
                port, token,
                """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"http_tool","arguments":{},"_meta":{"task":{}}}}""",
            )
            assertEquals(200, started.status, started.text)
            val taskId = Json.parseToJsonElement(started.text).jsonObject["result"]!!
                .jsonObject["taskId"]!!.jsonPrimitive.content
            assertTrue(tool.started.await(5, TimeUnit.SECONDS))

            val polled = post(
                port, token,
                """{"jsonrpc":"2.0","id":2,"method":"tasks/get","params":{"taskId":"$taskId"}}""",
            )
            assertEquals(200, polled.status, polled.text)
            assertEquals("working", Json.parseToJsonElement(polled.text).jsonObject["result"]!!
                .jsonObject["status"]!!.jsonPrimitive.content)

            val cancel = post(
                port, token,
                """{"jsonrpc":"2.0","id":3,"method":"tasks/cancel","params":{"taskId":"$taskId"}}""",
            )
            assertEquals(200, cancel.status, cancel.text)
            assertEquals("complete", Json.parseToJsonElement(cancel.text).jsonObject["result"]!!
                .jsonObject["resultType"]!!.jsonPrimitive.content)

            val deadline = System.nanoTime() + 5_000_000_000L
            var finalStatus = ""
            while (System.nanoTime() < deadline) {
                val again = post(
                    port, token,
                    """{"jsonrpc":"2.0","id":4,"method":"tasks/get","params":{"taskId":"$taskId"}}""",
                )
                finalStatus = Json.parseToJsonElement(again.text).jsonObject["result"]!!
                    .jsonObject["status"]!!.jsonPrimitive.content
                if (finalStatus == "cancelled") break
                Thread.sleep(10)
            }
            assertEquals("cancelled", finalStatus, "HTTP 轮询应看到任务被取消")
        } finally {
            bridge.stop()
        }
    }
}
