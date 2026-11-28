package com.stragent.core.ai

import com.stragent.core.ports.ChatResult as PortChatResult
import com.stragent.core.ports.LlmClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-113 查询分解器测试：产出解析（剥编号/去重/剔原问题/长度过滤）、
 * ≥2 才算分解成功（subQueries=null 语义）、分解轻调用的 token 如实入账。
 */
class QueryDecomposerTest {

    class ScriptedLlm(private val reply: () -> String) : LlmClient {
        val calls = java.util.Collections.synchronizedList(mutableListOf<List<Pair<String, String>>>())
        override fun chat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
        ): PortChatResult {
            calls += messages
            return PortChatResult(reply(), 55)
        }
    }

    // ---------- parseSubQueries（纯函数面） ----------

    @Test
    fun `解析——编号与项目符剥离且按行成条`() {
        val subs = LlmQueryDecomposer.parseSubQueries(
            "1. 承建单位名称\n2. 负责人兼任职务",
            "承建单位负责人是谁",
        )
        assertEquals(listOf("承建单位名称", "负责人兼任职务"), subs)
    }

    @Test
    fun `解析——去重且剔与原问题等值的行`() {
        val subs = LlmQueryDecomposer.parseSubQueries(
            "承建情况\n承建情况\n项目名称",
            "承建单位负责人是谁",
        )
        assertEquals(listOf("承建情况", "项目名称"), subs)
    }

    @Test
    fun `解析——超长行丢弃且条数封顶四条`() {
        val longLine = "超".repeat(61)
        val raw = (1..5).joinToString("\n") { "$it. 事实点$it" } + "\n" + longLine
        val subs = LlmQueryDecomposer.parseSubQueries(raw, "原问题")
        assertEquals(4, subs.size, "MAX_SUB_QUERIES 封顶")
        assertTrue(subs.none { it.contains("超".repeat(10)) }, "61 字超长行被丢弃")
    }

    // ---------- decompose（端口行为） ----------

    @Test
    fun `分解成功——两行产出进 subQueries 且 token 如实入账`() {
        val llm = ScriptedLlm { "1. 承建单位名称\n2. 负责人兼任职务" }
        val d = LlmQueryDecomposer.decompose(llm, "m", "承建单位负责人是谁", { false })
        assertEquals(listOf("承建单位名称", "负责人兼任职务"), d.subQueries)
        assertEquals(55, d.totalTokens)
    }

    @Test
    fun `非多跳——单行产出解析不出两个子查询则 subQueries 为 null`() {
        val llm = ScriptedLlm { "承建单位名称" }
        val d = LlmQueryDecomposer.decompose(llm, "m", "承建单位是谁", { false })
        assertEquals(null, d.subQueries, "单事实问题：分解器如实放弃，调用方走单查询")
        assertEquals(55, d.totalTokens, "放弃也记账（调用发生了）")
    }
}
