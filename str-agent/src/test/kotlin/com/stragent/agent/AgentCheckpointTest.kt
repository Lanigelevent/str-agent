package com.stragent.agent

import com.stragent.core.csv.ColumnType
import com.stragent.core.dataset.Dataset
import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.ToolCall
import com.stragent.core.ports.ToolSpec
import com.stragent.core.ports.ToolTurn
import com.stragent.core.ports.TurnMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-050：断点存储与循环续跑。
 *
 * 这组用例钉五件事：
 *  ① 状态落盘/读回/清理：原子写不残留临时文件，损坏文件当「没有」并删除；
 *  ② 崩溃在两步之间 → 续跑从下一步开始，**已完成的轮次不再打模型**、
 *     已回填的工具结果不再执行（总 token 是整次任务的累计）；
 *  ③ 完成态（keepOnFinish）回放：重跑不再发起任何模型调用；
 *  ④ 任务/提示词变化 → 签名不符，旧状态作废、从头开始（绝不串任务）；
 *  ⑤ 取消保留状态（可续跑），协议模式跟着已落盘的那次执行走。
 */
class AgentCheckpointTest {

    private fun tempStore(): AgentCheckpointStore =
        AgentCheckpointStore(Files.createTempDirectory("stragent-checkpoint-").toFile())

    private class ScriptedLlm(
        private val outputs: MutableList<String>,
        private val tokens: Int = 50,
        private val native: Boolean = false,
    ) : LlmClient {
        val calls = mutableListOf<List<Pair<String, String>>>()
        override fun supportsNativeTools(model: String): Boolean = native
        override fun chat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
        ): ChatResult {
            calls += messages
            check(outputs.isNotEmpty()) { "脚本没有更多文本回复（第 ${calls.size} 次调用）" }
            return ChatResult(outputs.removeAt(0), tokens)
        }
    }

    private fun probeTool(counter: IntArray, onInvoke: () -> Unit = {}): AgentTool = object : AgentTool {
        override val name = "probe"
        override val description = "测试探针"
        override val parametersSchema = buildJsonObject { put("type", "object") }
        override fun invoke(args: JsonObject): JsonObject {
            counter[0] += 1
            onInvoke()
            return buildJsonObject { put("ok", true); put("echo", args.toString()) }
        }
    }

    private fun manifest(maxSteps: Int = 5) = PluginManifest(
        id = "checkpoint-test", name = "断点测试", kind = "report",
        systemPrompt = "写测试报告。", tools = null, maxSteps = maxSteps,
    )

    private fun dataset(cell: String = "1"): Dataset = Dataset(
        name = "t.csv", importedAt = 1,
        columns = listOf("区域", "销售额"),
        rows = listOf(listOf("West", cell), listOf("East", "2")),
        columnTypes = listOf(ColumnType.TEXT, ColumnType.NUMBER),
        sourceRows = 2,
    )

    private fun modeOf(state: JsonObject): String = (state["mode"] as JsonPrimitive).content
    private fun intOf(state: JsonObject, key: String): Int = (state[key] as JsonPrimitive).content.toInt()

    // ---- ① 存储 ----

    @Test
    fun `存储——落盘读回、无临时残留、按任务清理`() {
        val store = tempStore()
        val scope = store.forRun("run-1")
        val a = scope.stage("a")
        val b = scope.stage("b")
        assertTrue(a.save(buildJsonObject { put("nextStep", 1) }))
        assertTrue(b.save(buildJsonObject { put("nextStep", 2) }))
        assertEquals(1, intOf(a.load()!!, "nextStep"))
        assertEquals(2, intOf(b.load()!!, "nextStep"))
        // 另一任务互不相见（runKey 隔离）
        assertNull(store.forRun("run-2").stage("a").load())
        // 原子写：目录里只有正式文件，没有 .tmp- 残留
        assertFalse(store.root!!.walkTopDown().any { it.isFile && it.name.contains(".tmp-") })
        scope.clearAll()
        assertNull(a.load())
        assertNull(b.load())
    }

    @Test
    fun `存储——损坏状态视为没有并删除`() {
        val store = tempStore()
        val stage = store.forRun("run-1").stage("s")
        stage.save(buildJsonObject { put("nextStep", 1) })
        val file = store.root!!.walkTopDown().first { it.isFile }
        file.writeText("{这不是合法 JSON")
        assertNull(stage.load())
        assertFalse(file.exists(), "损坏文件应被删除，避免反复读坏状态")
    }

    @Test
    fun `存储——runKey 随内容、插件、类型变化，重新导入同内容不变`() {
        val plugin = manifest()
        val base = AgentCheckpointStore.runKey(dataset(), plugin, "standard", 3000)
        assertEquals(base, AgentCheckpointStore.runKey(dataset().copy(importedAt = 999), plugin, "standard", 3000))
        assertNotEquals(base, AgentCheckpointStore.runKey(dataset(cell = "9"), plugin, "standard", 3000))
        assertNotEquals(base, AgentCheckpointStore.runKey(dataset(), plugin.copy(id = "other"), "standard", 3000))
        assertNotEquals(base, AgentCheckpointStore.runKey(dataset(), plugin, "deepdive", 3000))
        assertNotEquals(base, AgentCheckpointStore.runKey(dataset(), plugin, "standard", 4000))
    }

    // ---- ② 崩溃续跑 ----

    @Test
    fun `续跑——崩溃后从下一步继续，已完成轮次不重打、工具不重复执行`() {
        val root = Files.createTempDirectory("stragent-checkpoint-").toFile()
        val store = AgentCheckpointStore(root)
        val stage = store.forRun("run-1").stage("s")
        val counter = intArrayOf(0)
        val registry = ToolRegistry.of(listOf(probeTool(counter)))
        var call = 0
        val crashing = object : LlmClient {
            override fun chat(
                model: String,
                messages: List<Pair<String, String>>,
                temperature: Double,
                maxTokens: Int,
            ): ChatResult {
                call += 1
                if (call == 1) return ChatResult("""{"tool":"probe","args":{"k":1}}""", 50)
                throw RuntimeException("模拟进程/网络崩溃")
            }
        }
        assertFailsWith<RuntimeException> {
            AgentLoop(crashing).run(
                manifest(), registry, "m", "任务A", AGENT_HARNESS_CONTRACT,
                checkpoint = stage, keepOnFinish = false,
            )
        }
        assertEquals(1, counter[0], "第一步的工具已执行")
        val saved = stage.load()!!
        assertEquals("text", modeOf(saved))
        assertEquals(1, intOf(saved, "nextStep"))

        // 模拟进程重启：换一个只认目录的新 store 实例，状态必须仍可读
        val reopened = AgentCheckpointStore(root).forRun("run-1").stage("s")
        val resumed = ScriptedLlm(mutableListOf("""{"tool":"finish","args":{"report":"续跑完成"}}"""))
        val outcome = AgentLoop(resumed).run(
            manifest(), registry, "m", "任务A", AGENT_HARNESS_CONTRACT,
            checkpoint = reopened, keepOnFinish = false,
        )!!
        assertEquals("续跑完成", (outcome.output["report"] as JsonPrimitive).content)
        assertEquals(1, counter[0], "已回填的工具结果不得重复执行")
        assertEquals(100, outcome.totalTokens, "总 token = 崩溃前 50 + 续跑 50（整次任务累计）")
        assertEquals(4, resumed.calls[0].size, "续跑首轮带 system+user+assistant+tool_result")
        assertTrue(resumed.calls[0].any { it.second.contains("<tool_result tool=\"probe\">") })
        // keepOnFinish=false：完成即清理，下一次重跑重新生成
        assertNull(reopened.load())
    }

    @Test
    fun `完成态——keepOnFinish 回放不再调用模型`() {
        val store = tempStore()
        val stage = store.forRun("run-1").stage("s")
        val counter = intArrayOf(0)
        val registry = ToolRegistry.of(listOf(probeTool(counter)))
        val first = ScriptedLlm(mutableListOf("""{"tool":"finish","args":{"report":"最终稿"}}"""))
        val outcome = AgentLoop(first).run(
            manifest(), registry, "m", "任务A", AGENT_HARNESS_CONTRACT,
            checkpoint = stage, keepOnFinish = true,
        )!!
        assertEquals(1, first.calls.size)

        val throwing = object : LlmClient {
            override fun chat(
                model: String,
                messages: List<Pair<String, String>>,
                temperature: Double,
                maxTokens: Int,
            ): ChatResult = throw AssertionError("完成态回放不应再调用模型")
        }
        val replayed = AgentLoop(throwing).run(
            manifest(), registry, "m", "任务A", AGENT_HARNESS_CONTRACT,
            checkpoint = stage, keepOnFinish = true,
        )!!
        assertEquals(outcome.output, replayed.output)
        assertEquals(outcome.totalTokens, replayed.totalTokens)
        assertEquals(1, first.calls.size, "回放没有新增模型调用")
    }

    @Test
    fun `签名——换了任务从头开始，旧状态作废`() {
        val store = tempStore()
        val stage = store.forRun("run-1").stage("s")
        val counter = intArrayOf(0)
        val registry = ToolRegistry.of(listOf(probeTool(counter)))
        var call = 0
        val crashing = object : LlmClient {
            override fun chat(
                model: String,
                messages: List<Pair<String, String>>,
                temperature: Double,
                maxTokens: Int,
            ): ChatResult {
                call += 1
                if (call == 1) return ChatResult("""{"tool":"probe","args":{}}""", 50)
                throw RuntimeException("崩溃")
            }
        }
        assertFailsWith<RuntimeException> {
            AgentLoop(crashing).run(manifest(), registry, "m", "任务A", AGENT_HARNESS_CONTRACT, checkpoint = stage)
        }
        val fresh = ScriptedLlm(mutableListOf("""{"tool":"finish","args":{"report":"新任务"}}"""))
        val outcome = AgentLoop(fresh).run(
            manifest(), registry, "m", "任务B", AGENT_HARNESS_CONTRACT,
            checkpoint = stage, keepOnFinish = false,
        )!!
        assertEquals(50, outcome.totalTokens, "旧任务的 token 账不得带进新任务")
        assertEquals(2, fresh.calls[0].size, "新任务从 system+user 重新开始")
        assertTrue(fresh.calls[0].last().second.contains("任务B"))
        assertEquals(1, counter[0], "旧任务崩溃前的工具执行不重放、也不带入新任务")
    }

    @Test
    fun `取消——保留状态，续跑继续`() {
        val store = tempStore()
        val stage = store.forRun("run-1").stage("s")
        val counter = intArrayOf(0)
        var cancelled = false
        val registry = ToolRegistry.of(listOf(probeTool(counter) { cancelled = true }))
        val first = ScriptedLlm(mutableListOf("""{"tool":"probe","args":{}}"""))
        assertFailsWith<AgentCancelled> {
            AgentLoop(first).run(
                manifest(), registry, "m", "任务A", AGENT_HARNESS_CONTRACT,
                cancelSignal = { cancelled }, checkpoint = stage,
            )
        }
        assertEquals(1, counter[0])
        assertEquals(1, intOf(stage.load()!!, "nextStep"), "取消不清状态")
        cancelled = false
        val second = ScriptedLlm(mutableListOf("""{"tool":"finish","args":{"report":"取消后续跑"}}"""))
        val outcome = AgentLoop(second).run(
            manifest(), registry, "m", "任务A", AGENT_HARNESS_CONTRACT,
            cancelSignal = { cancelled }, checkpoint = stage,
        )!!
        assertEquals(1, counter[0])
        assertEquals(100, outcome.totalTokens)
    }

    @Test
    fun `协议模式——文本状态即使端点已支持 tools 也按文本续跑`() {
        val store = tempStore()
        val stage = store.forRun("run-1").stage("s")
        val counter = intArrayOf(0)
        val registry = ToolRegistry.of(listOf(probeTool(counter)))
        var call = 0
        val crashing = object : LlmClient {
            override fun chat(
                model: String,
                messages: List<Pair<String, String>>,
                temperature: Double,
                maxTokens: Int,
            ): ChatResult {
                call += 1
                if (call == 1) return ChatResult("""{"tool":"probe","args":{}}""", 50)
                throw RuntimeException("崩溃")
            }
        }
        assertFailsWith<RuntimeException> {
            AgentLoop(crashing).run(manifest(), registry, "m", "任务A", AGENT_HARNESS_CONTRACT, checkpoint = stage)
        }
        // 第二次：端点改口支持原生 tools，但已落盘的是文本会话
        val textOnly = object : LlmClient {
            override fun supportsNativeTools(model: String): Boolean = true
            override fun chat(
                model: String,
                messages: List<Pair<String, String>>,
                temperature: Double,
                maxTokens: Int,
            ): ChatResult = ChatResult("""{"tool":"finish","args":{"report":"文本续跑"}}""", 50)
            override fun chatTools(
                model: String,
                messages: List<TurnMessage>,
                temperature: Double,
                maxTokens: Int,
                tools: List<ToolSpec>,
                toolChoice: String,
                responseFormat: String?,
            ): ToolTurn = throw AssertionError("文本状态不得改走原生通道")
        }
        val outcome = AgentLoop(textOnly).run(
            manifest(), registry, "m", "任务A", AGENT_HARNESS_CONTRACT,
            checkpoint = stage,
        )!!
        assertEquals("文本续跑", (outcome.output["report"] as JsonPrimitive).content)
        assertEquals(1, counter[0])
    }

    // ---- ③ 原生循环续跑 ----

    @Test
    fun `原生续跑——tool_calls 历史原样接回`() {
        val store = tempStore()
        val stage = store.forRun("run-1").stage("s")
        val counter = intArrayOf(0)
        val registry = ToolRegistry.of(listOf(probeTool(counter)))
        var call = 0
        val crashing = object : LlmClient {
            override fun supportsNativeTools(model: String): Boolean = true
            override fun chat(
                model: String,
                messages: List<Pair<String, String>>,
                temperature: Double,
                maxTokens: Int,
            ): ChatResult = throw AssertionError("端点支持原生协议时不应走文本通道")
            override fun chatTools(
                model: String,
                messages: List<TurnMessage>,
                temperature: Double,
                maxTokens: Int,
                tools: List<ToolSpec>,
                toolChoice: String,
                responseFormat: String?,
            ): ToolTurn {
                call += 1
                if (call == 1) {
                    return ToolTurn("", listOf(ToolCall("call-1", "probe", "{}")), 50, native = true)
                }
                throw RuntimeException("原生崩溃")
            }
        }
        assertFailsWith<RuntimeException> {
            AgentLoop(crashing).run(manifest(), registry, "m", "任务A", AGENT_HARNESS_CONTRACT, checkpoint = stage)
        }
        assertEquals("native", modeOf(stage.load()!!))
        assertEquals(1, counter[0])

        val resumed = object : LlmClient {
            val calls = mutableListOf<List<TurnMessage>>()
            override fun supportsNativeTools(model: String): Boolean = true
            override fun chat(
                model: String,
                messages: List<Pair<String, String>>,
                temperature: Double,
                maxTokens: Int,
            ): ChatResult = throw AssertionError("原生续跑不应改走文本通道")
            override fun chatTools(
                model: String,
                messages: List<TurnMessage>,
                temperature: Double,
                maxTokens: Int,
                tools: List<ToolSpec>,
                toolChoice: String,
                responseFormat: String?,
            ): ToolTurn {
                calls += messages
                return ToolTurn("", listOf(ToolCall("call-2", "finish", """{"report":"原生续跑"}""")), 50, native = true)
            }
        }
        val outcome = AgentLoop(resumed).run(
            manifest(), registry, "m", "任务A", AGENT_HARNESS_CONTRACT,
            checkpoint = stage, keepOnFinish = false,
        )!!
        assertEquals("原生续跑", (outcome.output["report"] as JsonPrimitive).content)
        assertEquals(1, counter[0], "已完成的工具调用不重复执行")
        assertEquals(100, outcome.totalTokens)
        // 接回的消息里 assistant.tool_calls 与 role=tool 都在
        val history = resumed.calls[0]
        assertTrue(history.any { it.role == "assistant" && it.toolCalls.isNotEmpty() })
        assertTrue(history.any { it.role == "tool" && it.toolCallId == "call-1" })
        assertTrue(history.first { it.role == "tool" }.content.contains("echo"))
    }

    @Test
    fun `原生续跑——端点已不支持原生协议时状态作废、从头走文本`() {
        val store = tempStore()
        val stage = store.forRun("run-1").stage("s")
        val counter = intArrayOf(0)
        val registry = ToolRegistry.of(listOf(probeTool(counter)))
        var call = 0
        val crashing = object : LlmClient {
            override fun supportsNativeTools(model: String): Boolean = true
            override fun chat(
                model: String,
                messages: List<Pair<String, String>>,
                temperature: Double,
                maxTokens: Int,
            ): ChatResult = throw AssertionError("端点支持原生协议时不应走文本通道")
            override fun chatTools(
                model: String,
                messages: List<TurnMessage>,
                temperature: Double,
                maxTokens: Int,
                tools: List<ToolSpec>,
                toolChoice: String,
                responseFormat: String?,
            ): ToolTurn {
                call += 1
                if (call == 1) return ToolTurn("", listOf(ToolCall("call-1", "probe", "{}")), 50, native = true)
                throw RuntimeException("原生崩溃")
            }
        }
        assertFailsWith<RuntimeException> {
            AgentLoop(crashing).run(manifest(), registry, "m", "任务A", AGENT_HARNESS_CONTRACT, checkpoint = stage)
        }
        assertEquals("native", modeOf(stage.load()!!))

        // 端点回退到不支持 tools：native 半截会话无法用文本协议表达，状态作废、从头开始
        val text = ScriptedLlm(mutableListOf("""{"tool":"finish","args":{"report":"从零开始"}}"""))
        val outcome = AgentLoop(text).run(
            manifest(), registry, "m", "任务A", AGENT_HARNESS_CONTRACT,
            checkpoint = stage, keepOnFinish = false,
        )!!
        assertEquals("从零开始", (outcome.output["report"] as JsonPrimitive).content)
        assertEquals(2, text.calls[0].size, "从头开始：system+user")
        assertEquals(50, outcome.totalTokens, "旧原生步骤的 token 不得带进新协议会话")
        assertEquals(1, counter[0], "旧原生步骤的工具结果不重放；新会话未调工具")
    }
}
