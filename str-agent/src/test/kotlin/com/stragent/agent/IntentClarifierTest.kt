package com.stragent.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 意图澄清闸（round-32）——确定性启发式的契约与接线。
 *
 * 对标 dev-agent intent-parse 三键纪律：模糊输入不猜着执行。
 * 四条纪律各有一条断言锚（选定解读 / 被拒备选 / 假设清单 / 单问），
 * 无信号时逐字节零行为差异（与 T-008 memory 同款默认参数纪律）。
 */
class IntentClarifierTest {

    @Test
    fun `悬空指代命中——闸块要求声明指代落点`() {
        val a = IntentClarifier.analyze("那个怎么看？")
        assertTrue(a.isVague)
        assertTrue(a.signals.any { it.kind == "anaphora" && it.evidence.contains("那个") })
        val gate = a.gateBlock()
        assertTrue(gate!!.contains("<intent_gate>"))
        assertTrue(gate.contains("选定的解读"), "纪律①：必须先声明选定的解读")
        assertTrue(gate.contains("拒掉的备选解读"), "纪律②：落选解读保留给用户复核")
        assertTrue(gate.contains("假设"), "纪律③：假设与错了的代价")
    }

    @Test
    fun `复合请求命中——闸块要求拆子问题`() {
        val a = IntentClarifier.analyze("分析销售额，然后再看看利润")
        assertTrue(a.signals.any { it.kind == "compound" })
        assertTrue(a.gateBlock()!!.contains("拆成子问题"), "纪律④：复合请求逐个答")
    }

    @Test
    fun `过短命中——多种解读不得猜答只问一问`() {
        val a = IntentClarifier.analyze("占比？")
        assertTrue(a.signals.any { it.kind == "vague" && it.evidence.contains("过短") })
        val gate = a.gateBlock()!!
        assertTrue(gate.contains("只问一个最关键的澄清问题"), "纪律⑤：阻塞歧义只问一问，不猜答")
    }

    @Test
    fun `明确问题零信号——gateBlock 为 null（无注入）`() {
        val a = IntentClarifier.analyze("2023 年华东地区的平均订单金额是多少？")
        assertEquals(emptyList(), a.signals)
        assertNull(a.gateBlock(), "无信号必须返回 null：调用方据此不注入，请求形态不变")
    }

    @Test
    fun `空白输入不误报过短`() {
        assertNull(IntentClarifier.analyze("   ").gateBlock())
        assertNull(IntentClarifier.analyze("").gateBlock())
    }
}
