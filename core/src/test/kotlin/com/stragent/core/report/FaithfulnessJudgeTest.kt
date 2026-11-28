package com.stragent.core.report

import com.stragent.core.ai.GenerationCancelled
import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.LlmClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.test.assertTrue

/**
 * T-102 忠实度二审单元测试：parse 容错（围栏/坏条目）、judge 三态（有据/无据/失败）、
 * opt-in 开关、取消透传。
 */
class FaithfulnessJudgeTest {

    private class ScriptedLlm(private val reply: () -> String) : LlmClient {
        val calls = java.util.Collections.synchronizedList(mutableListOf<List<Pair<String, String>>>())
        override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int): ChatResult {
            calls.add(messages)
            return ChatResult(reply(), 77)
        }
    }

    private val brief = "材料：三季度收入 359 万元，仓储成本同比上升 18%。"

    @Test
    fun `judge——有据回答零问题，坏 JSON 显式 failure`() {
        val good = ScriptedLlm { """{"issues":[]}""" }
        val ok = FaithfulnessJudge.judge(good, "收入 359 万元（段 4）。", brief, "m")
        assertTrue(ok.issues.isEmpty())
        assertEquals(null, ok.failure)
        assertEquals(77, ok.totalTokens)

        val bad = ScriptedLlm { "我看看……报告写得不错" }
        val judged = FaithfulnessJudge.judge(bad, "收入 359 万元。", brief, "m")
        assertTrue(judged.issues.isEmpty())
        assertTrue(judged.failure!!.contains("不可解析"), "坏响应 = 显式未完成（T-062）")
        assertTrue(judged.totalTokens > 0)
    }

    @Test
    fun `judge——审出语义断言问题逐条带 claim 与 why`() {
        val llm = ScriptedLlm {
            """```json
            {"issues":[{"claim":"利润创新高","why":"材料只给了收入与毛利率，未提利润绝对额"}]}
            ```"""
        }
        val out = FaithfulnessJudge.judge(llm, "利润创新高。", brief, "m")
        assertEquals(1, out.issues.size)
        assertTrue(out.issues[0].contains("[忠实度二审]"))
        assertTrue(out.issues[0].contains("利润创新高"))
        assertTrue(out.issues[0].contains("未提利润绝对额"))
    }

    @Test
    fun `judge——调用异常显式 failure 不抛（不阻断出稿）`() {
        val boom = object : LlmClient {
            override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int): ChatResult =
                throw RuntimeException("端点 503")
        }
        val out = FaithfulnessJudge.judge(boom, "报告", brief, "m")
        assertTrue(out.failure!!.contains("端点 503"))
        assertTrue(out.issues.isEmpty())
    }

    @Test
    fun `取消透传`() {
        val llm = ScriptedLlm { "不该被调用" }
        try {
            FaithfulnessJudge.judge(llm, "报告", brief, "m", cancelSignal = { true })
            fail("取消应透传")
        } catch (_: GenerationCancelled) {
        }
        assertEquals(0, llm.calls.size)
    }

    @Test
    fun `parseJudge——剥离围栏与坏条目跳过`() {
        val parsed = FaithfulnessJudge.parseJudge(
            """```json
            {"issues":[{"claim":"甲","why":"乙"},{"bad":1},{"claim":"丙"}]}
            ```""",
        )
        assertEquals(listOf("甲" to "乙"), parsed)
        assertEquals(null, FaithfulnessJudge.parseJudge("完全不是 JSON"))
    }

    @Test
    fun `opt-in 开关——默认关（env 未设时 enabled=false）`() {
        // enabled 是进程级 val：CI/本地未设 env = false（默认关闭是 zero-change 的前提）
        // 真值测试依赖 env 进程注入，这里只钉默认口径
        assertEquals(false, FaithfulnessJudge.enabled)
    }
}
