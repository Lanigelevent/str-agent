package com.stragent.core.ai

import com.stragent.core.dataset.Dataset
import com.stragent.core.dataset.TextContent
import com.stragent.core.dataset.TextSegment
import com.stragent.core.ports.ChatResult as PortChatResult
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.PrefsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T-105 输出适配行为测试：depth 两轴/注入块/长度依从强制/overlay 贯穿/summarize 注入 */
class T105TextAdaptationTest {

    class FakePrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) {
            map[key] = value
        }
    }

    private class RoutingLlm(private val route: (List<Pair<String, String>>) -> String) : LlmClient {
        val calls = java.util.Collections.synchronizedList(mutableListOf<List<Pair<String, String>>>())
        override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int): PortChatResult {
            calls += messages
            return PortChatResult(route(messages), 50)
        }
    }

    private fun userOf(call: List<Pair<String, String>>) = call.first { it.first == "user" }.second
    private fun systemOf(call: List<Pair<String, String>>) = call.first { it.first == "system" }.second

    private fun textDataset(vararg segs: String): Dataset = Dataset(
        name = "t105", importedAt = 1,
        columns = emptyList(), rows = emptyList(), columnTypes = emptyList(),
        text = TextContent(segs.mapIndexed { i, s -> TextSegment(i + 1, s) }),
    )

    private val pointsJson =
        """{"points":[{"text":"销售额增长 45.3（段 1）","quote":"销售额增长 45.3","segment":1}],"keyNumbers":["45.3"],"summary":"要点概述"}"""

    private fun defaultRoute(genReport: String) = { messages: List<Pair<String, String>> ->
        when {
            systemOf(messages).contains("文本分析员") -> pointsJson
            systemOf(messages).contains("修订员") -> genReport
            systemOf(messages).contains("资深数据分析师") -> genReport
            else -> "[]"
        }
    }

    private fun prefsOf(vararg pairs: Pair<String, String>): FakePrefs = FakePrefs().apply { pairs.forEach { (k, v) -> set(k, v) } }

    // ---------- depth 两轴（Tableau verbosity/detail 正交先例） ----------

    @Test
    fun `depth=deep 篇幅轴放大且章组变细`() {
        // 42K 字符 > 30K → map-reduce 多块；deep groupSize=4 组数应多于 standard=6
        val segs = List(12) { i -> "第${i + 1}部分。" + "内容句子充数。".repeat(500) }
        val route = defaultRoute("## 概述\n销售额增长 45.3（段 1）。")
        val outDeep = generateTextReview(
            RoutingLlm(route), prefsOf("analysis.depth" to "deep"), "m", "m", textDataset(*segs.toTypedArray()),
            onStage = null, cancelSignal = { false }, onBody = null, memory = emptyList(), semantic = emptyList(),
        )
        val outStd = generateTextReview(
            RoutingLlm(route), FakePrefs(), "m", "m", textDataset(*segs.toTypedArray()),
            onStage = null, cancelSignal = { false }, onBody = null, memory = emptyList(), semantic = emptyList(),
        )
        assertEquals(3500, outDeep.targetChars, "2000×1.75=3500")
        assertEquals(2000, outStd.targetChars, "standard 系数 1.0")
        assertTrue(outDeep.chapters!!.size > outStd.chapters!!.size, "deep 章组更细（${outDeep.chapters!!.size} > ${outStd.chapters!!.size}）")
    }

    @Test
    fun `reportType 权重表改变章节字数而 depth 不变`() {
        val segs = List(12) { i -> "第${i + 1}部分。" + "内容句子充数。".repeat(500) }
        val llm = RoutingLlm(defaultRoute("## 概述\n销售额增长 45.3（段 1）。"))
        val out = generateTextReview(
            llm, prefsOf("analysis.reportType" to "executive"), "m", "m", textDataset(*segs.toTypedArray()),
            onStage = null, cancelSignal = { false }, onBody = null, memory = emptyList(), semantic = emptyList(),
        )
        assertEquals(2000, out.targetChars, "depth=standard 系数 1.0")
        // executive 权重 overview=0.40：概述章 2000×0.40=800 字上限出现在 user prompt
        val overviewCall = llm.calls.first { userOf(it).contains("概述") && userOf(it).contains("约 800 字") }
        assertTrue(overviewCall.isNotEmpty(), "概述章 800 字目标（0.40 权重）")
    }

    // ---------- persona/requirements 注入（SciTeller + GPT Researcher 先例） ----------

    @Test
    fun `audience 与 requirements 注入 system 且优先级声明在场`() {
        val prefs = prefsOf(
            "analysis.depth" to "deep",
            "analysis.audience" to "施工方项目经理，非监测专业",
            "analysis.requirements" to "只写风险与遗留事项",
        )
        val llm = RoutingLlm(defaultRoute("## 概述\n销售额增长 45.3（段 1）。"))
        val out = generateTextReview(
            llm, prefs, "m", "m", textDataset("销售额增长 45.3（第一季度）。", "成本保持稳定。"),
            onStage = null, cancelSignal = { false }, onBody = null, memory = emptyList(), semantic = emptyList(),
        )
        val sys = llm.calls.map(::systemOf).first { it.contains("<output_persona>") }
        assertTrue(sys.contains("施工方项目经理，非监测专业"), "persona 逐字在场")
        assertTrue(sys.contains("只写风险与遗留事项"), "requirements 逐字在场")
        assertTrue(sys.contains("硬约束 > 本块 > 报告类型 > 行业框架 > 报告风格"), "优先级声明")
        assertTrue(out.targetChars == 787, "450×1.75=787.5→787")
    }

    // ---------- 长度依从（IFEval 式可验证约束，仅显式适配时强制） ----------

    @Test
    fun `显式适配时篇幅偏离升级硬问题并触发修订回灌`() {
        val prefs = prefsOf("analysis.depth" to "deep")
        val llm = RoutingLlm(defaultRoute("短稿。"))
        val out = generateTextReview(
            llm, prefs, "m", "m", textDataset("销售额增长 45.3（第一季度）。", "成本保持稳定。"),
            onStage = null, cancelSignal = { false }, onBody = null, memory = emptyList(), semantic = emptyList(),
        )
        assertEquals(2, out.gateRevisions, "短稿 vs 787 字目标 → 修订 2 轮")
        assertTrue(out.gateFailed, "修订后仍偏离 → gateFailed 恒输出")
        val reviseCall = llm.calls.first { systemOf(it).contains("修订员") }
        assertTrue(userOf(reviseCall).contains("实际"), "修订回灌含实际字数")
        assertTrue(userOf(reviseCall).contains("目标"), "修订回灌含目标字数")
    }

    @Test
    fun `默认档不强制长度——短稿零差异负控`() {
        val llm = RoutingLlm(defaultRoute("短稿。"))
        val out = generateTextReview(
            llm, FakePrefs(), "m", "m", textDataset("销售额增长 45.3（第一季度）。", "成本保持稳定。"),
            onStage = null, cancelSignal = { false }, onBody = null, memory = emptyList(), semantic = emptyList(),
        )
        assertEquals(0, out.gateRevisions, "默认路径不因短稿触发修订（负控）")
        assertFalse(out.gateFailed)
    }

    // ---------- styleOverrides 贯穿（InsightOrchestrator overlay） ----------

    @Test
    fun `styleOverrides 经 InsightOrchestrator 覆盖类型到 prompt`() {
        val llm = RoutingLlm(defaultRoute("## 概述\n销售额增长 45.3（段 1）。"))
        val oc = InsightOrchestrator(llm = llm, prefs = FakePrefs(), model = "m")
        val out = oc.generateInsight(
            textDataset("销售额增长 45.3（第一季度）。", "成本保持稳定。"),
            styleOverrides = mapOf("analysis.reportType" to "executive", "analysis.depth" to "deep"),
        )
        assertTrue(out.report.contains("销售额"), "报告产出")
        val sys = llm.calls.map(::systemOf).first { it.contains("<report_type") }
        assertTrue(sys.contains("一页纸执行摘要"), "类型覆盖生效（executive）")
        assertTrue(out.targetChars == 787, "depth=deep 同步生效")
    }

    // ---------- summarize 注入（map/reduce/cod） ----------

    @Test
    fun `summarizeText requirements 注入 map 与 cod prompt`() {
        val llm = RoutingLlm { pointsJson }
        val out = summarizeText(
            llm = llm, model = "m", dataset = textDataset("销售额增长 45.3。"),
            requirements = "只保留风险相关要点",
        )
        assertTrue(out.summary.isNotBlank())
        val mapCall = llm.calls.first { systemOf(it).contains("文本分析员") }
        assertTrue(userOf(mapCall).contains("<output_requirements>"), "map prompt 注入")
        assertTrue(userOf(mapCall).contains("只保留风险相关要点"), "要求逐字在场")
        val codCall = llm.calls.first { systemOf(it).contains("总结润色员") }
        assertTrue(userOf(codCall).contains("<output_requirements>"), "cod prompt 注入")
    }
}
