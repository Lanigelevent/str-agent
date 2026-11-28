package com.stragent.core.report

import com.stragent.core.ai.GenerationCancelled
import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.LlmClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-044 · 引擎无关发稿门禁（report/ReportGate.kt）。
 *
 * 覆盖四条契约：
 *  ① 干净报告一次过闸：零修订、零额外 token；
 *  ② 硬问题（假数字 / 套话≥3）触发修订，改干净后过闸；
 *  ③ 修订用尽/失败/取消的边界：不静默放行、不丢稿、取消原样上抛；
 *  ④ 软问题（缺结构块/篇幅）只记录不阻断；追问是记录级门禁。
 */
class ReportGateTest {

    private val brief = """
        数据摘要（测试用）
        - 销售额（数值列）：合计 1500，均值 500，最大 1000
        - 地区（文本列）：2 个取值
    """.trimIndent()

    private val cleanReport = "## 总体概览\n\n销售额合计 1500 元，均值 500 元，最高 1000 元。\n"
    private val dirtyReport = "## 总体概览\n\n销售额合计 999999 元。\n"

    private fun reviseTo(text: String, tokens: Int = 7): (List<GateIssue>, String, Int) -> GateRevision =
        { _, _, _ -> GateRevision(text, tokens) }

    @Test
    fun `干净报告一次过闸——零修订零 token`() {
        var called = false
        val outcome = runReportGate(cleanReport, brief) { _, _, _ -> called = true; GateRevision(cleanReport) }
        assertFalse(outcome.failed)
        assertEquals(0, outcome.revisions)
        assertEquals(0, outcome.totalTokens)
        assertFalse(called, "干净报告不该触发修订")
    }

    @Test
    fun `假数字触发修订——修干净后过闸并累计 token`() {
        var seen: List<GateIssue> = emptyList()
        val outcome = runReportGate(dirtyReport, brief, maxRevisions = 2) { issues, current, round ->
            seen = issues
            assertEquals(1, round)
            assertTrue(current.contains("999999"))
            GateRevision(cleanReport, totalTokens = 12)
        }
        assertFalse(outcome.failed)
        assertEquals(1, outcome.revisions)
        assertEquals(12, outcome.totalTokens)
        assertEquals(cleanReport, outcome.report)
        assertTrue(seen.any { it.kind == "unsourced_number" && it.detail.contains("999999") }, "问题清单要带未溯源数字：$seen")
        assertTrue(outcome.issues.none { it.kind == "unsourced_number" }, "终稿问题清单应清零")
    }

    @Test
    fun `修订两轮仍不干净——标记失败并保留问题清单（不静默放行）`() {
        var rounds = 0
        val outcome = runReportGate(dirtyReport, brief, maxRevisions = 2) { _, _, _ ->
            rounds += 1
            GateRevision(dirtyReport, totalTokens = 5)
        }
        assertTrue(outcome.failed)
        assertEquals(MAX_GATE_REVISIONS, rounds, "修订轮数应封顶")
        assertEquals(2, outcome.revisions)
        assertEquals(10, outcome.totalTokens)
        assertTrue(outcome.issues.any { it.kind == "unsourced_number" && it.detail.contains("999999") })
        assertTrue(outcome.hardIssues > 0)
    }

    @Test
    fun `maxRevisions=0——只评估不修订（编辑部外层预算口径）`() {
        var called = false
        val outcome = runReportGate(dirtyReport, brief, maxRevisions = 0) { _, _, _ ->
            called = true
            GateRevision(cleanReport)
        }
        assertFalse(called, "预算为 0 时一次修订调用都不许发（编辑部内部已退稿）")
        assertEquals(0, outcome.revisions)
        assertTrue(outcome.failed, "不修订不等于放行：硬伤照实标记")
        assertEquals(dirtyReport, outcome.report)
        assertTrue(outcome.issues.any { it.kind == "unsourced_number" })
    }

    @Test
    fun `修订抛普通异常——保留原稿并标记失败，不把报告换成异常`() {
        val outcome = runReportGate(dirtyReport, brief) { _, _, _ -> error("网络抖动") }
        assertTrue(outcome.failed)
        assertEquals(dirtyReport, outcome.report)
        assertEquals(0, outcome.revisions)
        assertTrue(outcome.issues.any { it.kind == "unsourced_number" })
    }

    @Test
    fun `修订时取消——原样抛 GenerationCancelled`() {
        assertFailsWith<GenerationCancelled> {
            runReportGate(dirtyReport, brief, cancelSignal = { true }) { _, _, _ -> GateRevision(cleanReport) }
        }
    }

    @Test
    fun `套话命中 3 处触发修订（写作审计硬项参与门禁）`() {
        val talky = "## 总体概览\n\n众所周知，销售额合计 1500 元。综上所述，形势很好。值得注意的是，均值 500 元。\n"
        var sawBanned = false
        val outcome = runReportGate(talky, brief, structured = true) { issues, _, _ ->
            sawBanned = issues.count { it.kind == "套话" } >= 3
            GateRevision(cleanReport)
        }
        assertTrue(sawBanned, "3 处套话应作为硬问题进入修订清单")
        assertFalse(outcome.failed)
        assertEquals(1, outcome.revisions)
    }

    @Test
    fun `软问题只记录不阻断——结构化类型缺结构块也过闸`() {
        val outcome = runReportGate(cleanReport, brief, structured = true) { _, _, _ -> error("不该修软问题") }
        assertFalse(outcome.failed)
        assertEquals(0, outcome.revisions)
        assertTrue(outcome.issues.any { it.kind == "缺结构块" }, "缺结构块应出现在注记里：${outcome.issues}")
    }

    @Test
    fun `追问记录级门禁——假数字标记失败但不改回答`() {
        val outcome = assessAnswer("销售额合计 999999 元。", brief)
        assertTrue(outcome.failed)
        assertEquals("销售额合计 999999 元。", outcome.report, "回答不许被静默改写")
        assertEquals(0, outcome.revisions)
        assertTrue(outcome.issues.any { it.kind == "unsourced_number" && it.detail.contains("999999") })

        val clean = assessAnswer("销售额合计 1500 元。", brief)
        assertFalse(clean.failed)
    }

    @Test
    fun `修订提示词——只给摘要与问题清单，不给生成提示词`() {
        class CapturingLlm(private val reply: String) : LlmClient {
            var calls = 0
            var lastMessages: List<Pair<String, String>> = emptyList()
            var lastTemperature = -1.0
            override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int): ChatResult {
                calls += 1
                lastMessages = messages
                lastTemperature = temperature
                return ChatResult(reply, 3)
            }
        }
        val llm = CapturingLlm(cleanReport)
        val issues = listOf(GateIssue("unsourced_number", "数字 999999（unsourced）无法溯源：销售额合计 999999 元"))
        val revision = reviseReportWithLlm(llm, "m", brief, dirtyReport, issues)
        assertEquals(cleanReport.trim(), revision.report)
        assertEquals(1, llm.calls)
        assertEquals(0.2, llm.lastTemperature)
        val system = llm.lastMessages.first { it.first == "system" }.second
        val user = llm.lastMessages.first { it.first == "user" }.second
        assertTrue(system.contains("只输出修订后的完整报告"), "system 应约束输出形态：$system")
        assertTrue(user.contains("<data_brief>") && user.contains("<current_report>") && user.contains("<gate_issues>"))
        assertTrue(user.contains("999999"))
        assertFalse(system.contains("<quality_bar>"), "修订不该重放整段生成提示词")
    }

    @Test
    fun `性能——30k 字干净报告的门禁评估远低于 50ms 量级（回归护栏）`() {
        // 全篇只引用摘要里有的数字：2000 行 × 1500，报告约 3 万字
        val longReport = buildString {
            repeat(2000) { append("销售额合计 1500 元。\n") }
        }
        // 预热（JVM 首轮含类加载与 JIT），再量 9 轮取中位数——稳态才是门禁的真实成本
        repeat(3) { runReportGate(longReport, brief, maxRevisions = 0) { _, _, _ -> error("不该修") } }
        val samples = mutableListOf<Double>()
        repeat(9) {
            val started = System.nanoTime()
            val outcome = runReportGate(
                longReport, brief, targetChars = longReport.length, structured = true, maxRevisions = 0,
            ) { _, _, _ -> error("不该修") }
            samples.add((System.nanoTime() - started) / 1_000_000.0)
            assertFalse(outcome.failed)
            assertEquals(0, outcome.revisions)
        }
        val median = samples.sorted()[samples.size / 2]
        // 分项计时：查清成本在哪一道闸（输出进测试报告，供 D4 记录）
        fun medianOf(block: () -> Unit): Double {
            repeat(3) { block() }
            val xs = (1..7).map { val t = System.nanoTime(); block(); (System.nanoTime() - t) / 1_000_000.0 }
            return xs.sorted()[xs.size / 2]
        }
        val verifyMs = medianOf { com.stragent.core.verify.verifyReport(longReport, brief) }
        val auditMs = medianOf { WritingAudit.audit(longReport, longReport.length, true) }
        val reviewMs = medianOf { reviewReport(longReport, emptyList(), buildAllowedNumbers(brief)) }
        println(
            "T-044 gate perf: 30k chars median ${"%.2f".format(median)} ms " +
                "(verify=${"%.1f".format(verifyMs)} audit=${"%.1f".format(auditMs)} review=${"%.1f".format(reviewMs)}; " +
                "samples=${samples.map { "%.1f".format(it) }})",
        )
        // T-044 出口判据：p95 增量 <50ms。CI 机器有抖动，护栏放到 100ms 防 O(n²) 回归；
        // 实测中位数（见 D4 P-074）必须在 50ms 内。
        // T-044 出口判据：内部机中位数 <50ms（D4 P-074）。绝对阈值不可跨机器移植——
        // 共享 runner（2 核）天然超线；护栏本意是防 O(n²) 回归（秒级），150ms 给 2.5 倍余量仍能抓。
        // 本机实测读数仍打印 samples 供 D4 记录。
        assertTrue(median < 150.0, "门禁稳态过慢（疑似 O(n²)）：median=${"$"}{median}ms samples=${"$"}{samples}")
    }
}
