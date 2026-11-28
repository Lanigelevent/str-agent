package com.stragent.core.report

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-192 · 定性断言实体级证据回链——对抗集（判据③）+ 确定性可重放（判据④）。
 *
 * 负控：编造主体的定性断言（数据摘要里没有该实体）必须被抓；
 * 正控：证据在场的断言不判；锚点句由锚点门禁管辖不重复判；
 * 软问题语义（不计 hard、不判失败）由门禁级测试钉住；
 * 同输入重跑结果一致（溯源可重放复核的确定性基础）。
 */
class ClaimTraceTest {

    private val brief = "数据集「门店销售」4 行 2 列：门店、销售额。分组聚合（门店×销售额）：华东 1500.5、华南 800。"

    @Test
    fun `正控——断言主体在证据语料中，不判未溯源`() {
        val draft = "华东增长最快，华南相对平稳。"
        assertEquals(emptyList(), checkClaimTracing(draft, brief))
    }

    @Test
    fun `负控——编造主体的定性断言被抓`() {
        val draft = "东北大区贡献最大，需要重点投入。"
        val issues = checkClaimTracing(draft, brief)
        assertEquals(1, issues.size, "编造主体必须被抓：" + issues)
        assertTrue(issues[0].contains("东北大区贡献最大"))
    }

    @Test
    fun `锚点句不判——「段 N」引用由锚点门禁管辖`() {
        val draft = "东北大区贡献最大（段 3）。"
        assertEquals(emptyList(), checkClaimTracing(draft, brief))
    }

    @Test
    fun `全通用词句提不出实体，不判`() {
        val draft = "整体表现最好，数据相对稳定。"
        assertEquals(emptyList(), checkClaimTracing(draft, brief))
    }

    @Test
    fun `预筛——全文无线索词直通`() {
        val draft = "平铺直叙的一段话，没有判断词。"
        assertEquals(emptyList(), checkClaimTracing(draft, brief))
    }

    @Test
    fun `确定性——同输入两次结果一致（可重放）`() {
        val draft = "东北大区贡献最大。整体表现最好，数据相对稳定。华东增长最快。"
        assertEquals(checkClaimTracing(draft, brief), checkClaimTracing(draft, brief))
    }

    @Test
    fun `门禁接线——issues 含 untraced_claim 且不计 hard（软问题语义）`() {
        val gate = runReportGate(
            report = "东北大区贡献最大。",
            brief = brief,
            revise = { _, current, _ -> GateRevision(current) },
        )
        assertTrue(gate.issues.any { it.kind == "untraced_claim" }, "issues: " + gate.issues)
        assertTrue(!gate.failed, "软问题不判失败，hardIssues=" + gate.hardIssues)
        assertEquals(0, gate.hardIssues)
    }

    @Test
    fun `追问接线——assessAnswer 含 untraced_claim（软，不判失败）`() {
        val outcome = assessAnswer("东北大区贡献最大。", brief)
        assertTrue(outcome.issues.any { it.kind == "untraced_claim" })
        assertTrue(!outcome.failed)
    }
}
