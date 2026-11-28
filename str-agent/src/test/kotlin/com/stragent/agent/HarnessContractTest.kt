package com.stragent.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-160 · Harness 系统契约钉子（D25 Q7 覆盖率盲区：本文件零直接测试——两份契约常量是
 * 进 system prompt 的固定协议段，措辞漂移会直接改变模型行为面）。
 *
 * 钉的是**行为性子句的存在性**（JSON 单输出 / 数字溯源 / 确定性自检 / finish 收尾 / 缓存），
 * 不是逐字快照——措辞可以演进，子句不能消失。
 */
class HarnessContractTest {

    @Test
    fun `经典契约——五条行为子句齐全`() {
        val c = AGENT_HARNESS_CONTRACT
        assertTrue(c.startsWith("<harness>"), "必须以 harness 标签包裹（提示词边界）")
        assertTrue(c.endsWith("</harness>"), "必须以闭合标签收尾")
        assertTrue(c.contains("只输出一个 JSON 对象"), "子句①单 JSON 输出")
        assertTrue(c.contains("写不出出处") && c.contains("编造"), "子句②数字溯源")
        assertTrue(c.contains("submit_plan") && c.contains("record_finding"), "子句③工作流工具引用")
        assertTrue(c.contains("audit_report"), "子句④确定性自检（finish 前必跑）")
        assertTrue(c.contains("必须用 finish 收尾") || c.contains("用 finish 收尾"), "子句⑤收尾要求")
        assertTrue(c.contains("同参重复调用会直接返回缓存"), "子句⑥生产缓存语义")
    }

    @Test
    fun `原生模式契约——关键子句同在（溯源 自检 收尾 缓存）`() {
        val c = AGENT_HARNESS_CONTRACT_NATIVE
        assertTrue(c.startsWith("<harness>") && c.endsWith("</harness>"), "标签包裹一致")
        assertTrue(c.contains("写不出出处的数字就是编造"), "数字溯源子句")
        assertTrue(c.contains("必须先调 audit_report 自检"), "确定性自检子句")
        assertTrue(c.contains("结束时必须调用 finish"), "收尾子句")
        assertTrue(c.contains("同参重复调用会直接返回缓存"), "缓存语义子句")
    }

    @Test
    fun `两份契约——native 版短于经典版（防复制粘贴事故，非硬比例钉）`() {
        assertTrue(
            AGENT_HARNESS_CONTRACT_NATIVE.length < AGENT_HARNESS_CONTRACT.length,
            "native ${AGENT_HARNESS_CONTRACT_NATIVE.length} 应短于经典 ${AGENT_HARNESS_CONTRACT.length}",
        )
    }
}
