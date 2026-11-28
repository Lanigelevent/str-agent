package com.stragent.agent

import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.ToolCall
import com.stragent.core.ports.ToolSpec
import com.stragent.core.ports.ToolTurn
import com.stragent.core.ports.TurnMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-049：AgentLoop 的原生工具循环（OpenAI `tools` 协议）。
 *
 * 这组用例钉四件事：
 *  ① 模型发 tool_calls → 工具执行 → assistant.tool_calls / tool.tool_call_id 逐条回填；
 *     一轮多个调用（并行工具调用）逐个执行，不只取第一个；
 *  ② 工具参数坏 JSON / 缺必填项 → 作为工具结果回传给模型修，整轮不崩、不浪费步数；
 *  ③ **端点不支持时必须回落文本协议**：首轮已付费的纯文本回复直接接续解析，
 *     不再多打一次模型调用（省一次钱，也省一轮延迟）；
 *  ④ 步数见底用 `tool_choice = finish` 强推收尾；取消信号每步都查。
 *
 * 既有 46 项内核测试用的是默认 `supportsNativeTools = false` 的 fake——它们覆盖的正是
 * 「文本协议行为不变」那一半，本套件不重复。
 */
class AgentNativeLoopTest {

    private class NativeLlm(
        private val turns: MutableList<ToolTurn>,
        /** 回落文本协议后，文本通道的预设回复（按调用序消费） */
        private val textReplies: MutableList<String> = mutableListOf(),
        private val nativeSupported: Boolean = true,
    ) : LlmClient {
        val calls = mutableListOf<List<TurnMessage>>()
        val choices = mutableListOf<String>()
        val seenTools = mutableListOf<List<ToolSpec>>()
        val textCalls = mutableListOf<List<Pair<String, String>>>()

        override fun supportsNativeTools(model: String): Boolean = nativeSupported

        override fun chat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
        ): ChatResult {
            textCalls += messages
            check(textReplies.isNotEmpty()) { "脚本里没有文本通道的回复（第 ${textCalls.size} 次文本调用）" }
            return ChatResult(textReplies.removeAt(0), 5)
        }

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
            choices += toolChoice
            seenTools += tools
            check(turns.isNotEmpty()) { "脚本里没有更多轮次了（第 ${calls.size} 次调用）" }
            return turns.removeAt(0)
        }
    }

    private val probe = object : AgentTool {
        var executed = 0
        override val name = "probe"
        override val description = "测试探针"
        override val parametersSchema = buildJsonObject {
            put("type", "object")
            put("required", buildJsonArray { add("k") })
        }
        override fun invoke(args: JsonObject): JsonObject =
            buildJsonObject {
                executed += 1
                put("ok", true)
                put("echo", args.toString())
            }
    }

    private val registry = ToolRegistry.of(listOf(probe))

    private fun manifest(maxSteps: Int = 5) = PluginManifest(
        id = "test-native", name = "原生测试", kind = "report",
        systemPrompt = "做一份测试报告。", tools = null, maxSteps = maxSteps,
    )

    private fun call(id: String, name: String, args: String) = ToolCall(id, name, args)

    private fun turn(vararg calls: ToolCall, tokens: Int = 10) =
        ToolTurn("", calls.toList(), tokens, truncated = false, native = true)

    private fun finishTurn(report: String, tokens: Int = 10) =
        ToolTurn("", listOf(call("finish_1", "finish", """{"report":"$report"}""")), tokens, native = true)

    @Test
    fun `原生——tool_calls 驱动工具，assistant 与 tool 消息逐条对账`() {
        val llm = NativeLlm(
            mutableListOf(
                turn(call("c1", "probe", """{"k":1}"""), tokens = 30),
                finishTurn("## 结论 42", tokens = 20),
            ),
        )
        val outcome = AgentLoop(llm).run(manifest(), registry, "m", "任务", AGENT_HARNESS_CONTRACT)!!
        assertEquals("## 结论 42", outcome.output["report"]?.toString()?.trim('"'))
        assertEquals(1, outcome.toolCalls)
        assertEquals(1, probe.executed)
        assertEquals(50, outcome.totalTokens, "两轮 token 都要入账")
        assertEquals(1, outcome.steps.size)
        assertEquals(2, outcome.trace.size)

        // 系统契约是原生版：不再要求「每轮只输出一个 JSON 对象」，也不再把工具目录塞进提示词
        val system = llm.calls[0][0].content
        assertTrue(system.contains("function calling"), system.take(200))
        assertTrue(system.contains("finish"))
        assertFalse(system.contains("每轮只输出一个 JSON 对象"), "两套协议指令不能混在一起")
        // 工具目录走 tools 数组：注册表工具 + 状态工具 + finish
        val names = llm.seenTools[0].map { it.name }
        assertTrue(names.containsAll(listOf("probe", "submit_plan", "record_finding", "suggest_chart", "finish")), "$names")

        // 第二轮回填：assistant.tool_calls(c1) + role=tool(tool_call_id=c1)
        val second = llm.calls[1]
        val assistant = second.first { it.role == "assistant" && it.toolCalls.isNotEmpty() }
        assertEquals(listOf("c1"), assistant.toolCalls.map { it.id })
        val toolResult = second.first { it.role == "tool" }
        assertEquals("c1", toolResult.toolCallId)
        assertTrue(toolResult.content.contains("\"echo\""), toolResult.content)
        assertEquals(listOf("auto", "auto"), llm.choices)
    }

    @Test
    fun `原生——一轮多个 tool_call 逐个执行（并行调用不丢）`() {
        val llm = NativeLlm(
            mutableListOf(
                turn(call("c1", "probe", """{"k":1}"""), call("c2", "probe", """{"k":2}"""), tokens = 10),
                finishTurn("ok", tokens = 10),
            ),
        )
        val outcome = AgentLoop(llm).run(manifest(), registry, "m", "任务", AGENT_HARNESS_CONTRACT)!!
        assertEquals(2, probe.executed, "同一轮的两个调用都要执行")
        assertEquals(2, outcome.toolCalls)
        assertEquals(2, outcome.steps.size)
        val toolMessages = llm.calls[1].filter { it.role == "tool" }
        assertEquals(listOf("c1", "c2"), toolMessages.map { it.toolCallId })
    }

    @Test
    fun `原生——坏 JSON 或缺必填项作为工具结果回填，整轮不崩`() {
        val llm = NativeLlm(
            mutableListOf(
                turn(call("c1", "probe", "{oops"), tokens = 10),
                turn(call("c2", "probe", "{}"), tokens = 10),
                finishTurn("补救完成", tokens = 10),
            ),
        )
        val outcome = AgentLoop(llm).run(manifest(), registry, "m", "任务", AGENT_HARNESS_CONTRACT)!!
        assertEquals("补救完成", outcome.output["report"]?.toString()?.trim('"'))
        assertEquals(0, probe.executed, "参数坏了不执行，也不该计工具调用")
        assertEquals(0, outcome.toolCalls)
        val afterBadJson = llm.calls[1].last { it.role == "tool" }
        assertEquals("c1", afterBadJson.toolCallId)
        assertTrue(afterBadJson.content.contains("参数不是合法 JSON"), afterBadJson.content)
        val afterMissing = llm.calls[2].last { it.role == "tool" }
        assertEquals("c2", afterMissing.toolCallId)
        assertTrue(afterMissing.content.contains("缺少必填参数: k"), afterMissing.content)
    }

    @Test
    fun `回落——端点不支持原生协议：接续首轮文本回复，不再多打一次模型调用`() {
        val llm = NativeLlm(
            mutableListOf(
                ToolTurn(
                    """{"tool":"finish","args":{"report":"回落正文"}}""",
                    emptyList(),
                    20,
                    native = false,
                ),
            ),
        )
        val outcome = AgentLoop(llm).run(manifest(), registry, "m", "任务", AGENT_HARNESS_CONTRACT)!!
        assertEquals("回落正文", outcome.output["report"]?.toString()?.trim('"'))
        assertEquals(20, outcome.totalTokens, "回落那次调用的 token 不能丢账")
        assertEquals(1, llm.calls.size, "首轮回复已经付过费，不许重打")
        assertEquals(0, llm.textCalls.size, "不应再走一次文本通道")
    }

    @Test
    fun `回落——首轮纯文本不是协议 JSON 时由文本循环的修复提示接管`() {
        val llm = NativeLlm(
            turns = mutableListOf(ToolTurn("我直接说结论：销售额不错。", emptyList(), 20, native = false)),
            textReplies = mutableListOf("""{"tool":"finish","args":{"report":"修复后的正文"}}"""),
        )
        val outcome = AgentLoop(llm).run(manifest(), registry, "m", "任务", AGENT_HARNESS_CONTRACT)!!
        assertEquals("修复后的正文", outcome.output["report"]?.toString()?.trim('"'))
        assertEquals(25, outcome.totalTokens, "首轮 20 + 修复轮 5 都要入账")
        assertEquals(1, llm.calls.size, "回落本身不额外调用原生通道")
        assertEquals(1, llm.textCalls.size)
        val repair = llm.textCalls[0]
        assertTrue(repair.any { it.first == "assistant" && it.second == "我直接说结论：销售额不错。" }, "回落的首轮原文必须垫回历史")
        assertTrue(repair.any { it.second.contains("不是合法的单个 JSON 对象") }, "由文本协议的修复提示接管")
    }

    @Test
    fun `原生——只说话不调工具：纠正提示最多两次，仍不守约则交还 null`() {
        val llm = NativeLlm(
            mutableListOf(
                ToolTurn("写完了（但没调 finish）", emptyList(), 10, native = true),
                ToolTurn("还是只说话", emptyList(), 10, native = true),
                ToolTurn("依然只说话", emptyList(), 10, native = true),
            ),
        )
        assertNull(AgentLoop(llm).run(manifest(), registry, "m", "任务", AGENT_HARNESS_CONTRACT))
        assertEquals(3, llm.calls.size, "首轮 + 2 次修复")
        assertTrue(
            llm.calls[1].any { it.role == "user" && it.content.contains("没有调用任何工具") },
            "第二次调用必须带纠正提示",
        )
    }

    @Test
    fun `原生——步数见底用 tool_choice 强推 finish`() {
        val llm = NativeLlm(
            mutableListOf(
                turn(call("c1", "probe", """{"k":1}"""), tokens = 10),
                finishTurn("ok", tokens = 10),
            ),
        )
        val outcome = AgentLoop(llm).run(manifest(maxSteps = 2), registry, "m", "任务", AGENT_HARNESS_CONTRACT)!!
        assertEquals(listOf("auto", "finish"), llm.choices, "最后一轮必须强制调用 finish")
        assertEquals(1, outcome.toolCalls)
    }

    @Test
    fun `原生——cancelSignal 每步检查（一次模型调用都不发）`() {
        val llm = NativeLlm(mutableListOf())
        assertFailsWith<AgentCancelled> {
            AgentLoop(llm).run(manifest(), registry, "m", "任务", AGENT_HARNESS_CONTRACT, cancelSignal = { true })
        }
        assertEquals(0, llm.calls.size)
    }

    @Test
    fun `原生——finish 参数坏 JSON：错误回填后仍可重新 finish`() {
        val llm = NativeLlm(
            mutableListOf(
                ToolTurn("", listOf(call("f1", "finish", "{bad")), 10, native = true),
                finishTurn("补救产物", tokens = 10),
            ),
        )
        val outcome = AgentLoop(llm).run(manifest(), registry, "m", "任务", AGENT_HARNESS_CONTRACT)!!
        assertEquals("补救产物", outcome.output["report"]?.toString()?.trim('"'))
        val firstResult = llm.calls[1].first { it.role == "tool" }
        assertEquals("f1", firstResult.toolCallId)
        assertTrue(firstResult.content.contains("不是合法 JSON"), firstResult.content)
    }
}
