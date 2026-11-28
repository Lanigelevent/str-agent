package com.stragent.core.ai

import com.stragent.core.dataset.Dataset
import com.stragent.core.dataset.TextContent
import com.stragent.core.dataset.TextSegment
import com.stragent.core.ports.ChatResult as PortChatResult
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.PrefsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-096 综述报告测试：三态×长短场景矩阵（单发/分章/混合双轨）、claim 级门禁
 * （白名单外数字→修订→gateFailed 恒输出）、锚点有效性、表格路径零差异负控。
 */
class TextReviewTest {

    class FakePrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
    }

    /** 按 system 角色标记分流；记录全部调用（钉步序用） */
    private class RoutingLlm(private val route: (List<Pair<String, String>>) -> String) : LlmClient {
        val calls = java.util.Collections.synchronizedList(mutableListOf<List<Pair<String, String>>>())
        override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int): PortChatResult {
            calls += messages
            return PortChatResult(route(messages), 50)
        }
    }

    private fun userOf(call: List<Pair<String, String>>) = call.first { it.first == "user" }.second
    private fun systemOf(call: List<Pair<String, String>>) = call.first { it.first == "system" }.second

    private fun textDataset(vararg segs: String, name: String = "报告.md"): Dataset = Dataset(
        name = name, importedAt = 1,
        columns = emptyList(), rows = emptyList(), columnTypes = emptyList(),
        text = TextContent(segs.mapIndexed { i, s -> TextSegment(i + 1, s) }),
    )

    private fun longSegs(n: Int, chars: Int = 3500): List<String> =
        List(n) { i -> "第${i + 1}部分。" + "内容句子充数。".repeat(chars / 7) }

    private val pointsJson =
        """{"points":[{"text":"销售额增长 45.3（段 1）","quote":"销售额增长 45.3","segment":1}],"keyNumbers":["45.3"],"summary":"要点概述"}"""

    /** 综述生成应答：clean 无违规；bad 带白名单外数字 77777 */
    private fun genReply(report: String) = report

    private fun defaultRoute(
        genReport: String = "## 概述\n材料显示销售额增长 45.3（段 1），整体向好。",
        reviseReport: String = "## 概述\n材料显示销售额增长 45.3（段 1），整体向好。",
        chunkReply: (String) -> String = { pointsJson },
    ): (List<Pair<String, String>>) -> String = { messages ->
        when {
            systemOf(messages).contains("文本分析员") -> chunkReply(userOf(messages))
            systemOf(messages).contains("修订员") -> reviseReport
            systemOf(messages).contains("资深数据分析师") -> genReport
            else -> "[]" // 语义审查等
        }
    }

    private fun runReview(ds: Dataset, llm: LlmClient) = generateTextReview(
        llm, FakePrefs(), "m", "m", ds,
        onStage = null, cancelSignal = { false }, onBody = null,
        memory = emptyList(), semantic = emptyList(),
    )

    // ---------- 场景矩阵 ----------

    @Test
    fun `纯文本短材料——单发路由且篇幅自适应为短报告`() {
        val ds = textDataset("销售额增长 45.3（第一季度）。", "成本保持稳定。")
        val llm = RoutingLlm(defaultRoute())
        val out = runReview(ds, llm)
        assertEquals(450, out.targetChars, "<2K 字材料 → 短报告目标")
        assertTrue(out.report.contains("销售额"), "报告正文非空")
        assertEquals("classic", out.engineId)
        assertFalse(out.gateFailed)
        val genCalls = llm.calls.count { systemOf(it).contains("资深数据分析师") }
        assertEquals(1, genCalls, "短材料单发成文")
    }

    @Test
    fun `纯文本长材料——程序定章节逐章生成`() {
        val ds = textDataset(*longSegs(12).toTypedArray())
        val llm = RoutingLlm(defaultRoute())
        val out = runReview(ds, llm)
        assertEquals(2000, out.targetChars, ">30K 字材料 → 分章 2000 字")
        assertTrue(out.chapters != null && out.chapters!!.size >= 3, "章节骨架：概述+内容要点组+结论")
        assertEquals(out.chapters!!.size, llm.calls.count { systemOf(it).contains("资深数据分析师") })
        val outline = llm.calls.first { systemOf(it).contains("资深数据分析师") }
        assertTrue(userOf(outline).contains("<allowed_numbers>"), "白名单随材料进章节提示词")
        assertTrue(out.report.contains("## 概述"), "章节拼接保留标题")
    }

    @Test
    fun `混合材料——表格摘要与文本要点双轨进提示词`() {
        val ds = Dataset(
            name = "季报", importedAt = 1,
            columns = listOf("月份", "销售额"),
            rows = listOf(listOf("1月", "100"), listOf("2月", "150")),
            columnTypes = listOf(com.stragent.core.csv.ColumnType.TEXT, com.stragent.core.csv.ColumnType.NUMBER),
            text = TextContent(listOf(TextSegment(1, "第二季度毛利率 30%，渠道扩张。"))),
        )
        val llm = RoutingLlm(
            defaultRoute(
                genReport = "## 概述\n第二季度毛利率 30%（段 1），渠道扩张推进。",
                chunkReply = { user ->
                    if (user.contains("毛利率")) {
                        """{"points":[{"text":"毛利率 30% 渠道扩张","quote":"毛利率 30%","segment":1}],"keyNumbers":["30%"],"summary":"要点"}"""
                    } else {
                        pointsJson
                    }
                },
            ),
        )
        val out = runReview(ds, llm)
        val genCall = llm.calls.first { systemOf(it).contains("资深数据分析师") }
        val user = userOf(genCall)
        assertTrue(user.contains("表格统计摘要"), "混合表格 brief 进提示词")
        assertTrue(user.contains("毛利率 30%"), "文本要点进提示词")
        assertFalse(out.gateFailed)
    }

    // ---------- 零编造门禁 ----------

    @Test
    fun `白名单外数字——修订一轮后过闸且 gateRevisions 记账`() {
        val ds = textDataset("销售额增长 45.3（第一季度）。", "成本保持稳定。")
        val llm = RoutingLlm(
            defaultRoute(
                genReport = "总额 77777 元（段 1）。",
                reviseReport = "总额 45.3 元（段 1）。",
                chunkReply = { pointsJson },
            ),
        )
        val out = runReview(ds, llm)
        assertEquals(1, out.gateRevisions, "硬问题 → 一轮修订")
        assertFalse(out.gateFailed, "修订稿干净 → 过闸")
        assertFalse(out.report.contains("77777"), "终稿不含清单外数字")
    }

    @Test
    fun `修订两轮仍失败——gateFailed 恒输出且报告保留不丢稿`() {
        val ds = textDataset("销售额增长 45.3（第一季度）。", "成本保持稳定。")
        val bad = "总额 77777 元（段 1）。"
        val llm = RoutingLlm(
            defaultRoute(genReport = bad, reviseReport = bad, chunkReply = { pointsJson }),
        ) // 修订也返回同一份坏稿
        val out = runReview(ds, llm)
        assertEquals(2, out.gateRevisions)
        assertTrue(out.gateFailed, "修不干净 = true，不静默放行")
        assertTrue(out.report.contains("77777"), "报告保留（不丢稿）")
        assertTrue(out.gateIssues.isNotEmpty())
    }

    @Test
    fun `锚点越界——硬问题触发修订`() {
        val ds = textDataset("销售额增长 45.3。")
        val llm = RoutingLlm(
            defaultRoute(genReport = "增长见（段 99）。", chunkReply = { pointsJson }),
        )
        val out = runReview(ds, llm)
        assertTrue(out.gateRevisions >= 1, "段 99 越界 → 硬问题 → 修订")
        assertFalse(out.report.contains("段 99"), "修订稿已修正锚点")
    }

    @Test
    fun `anchorIssuesOf——越界报且范围内过`() {
        assertTrue(anchorIssuesOf("见段 1 与段 2。", 2).isEmpty())
        val issues = anchorIssuesOf("见段 5 与段 0。", 2)
        assertEquals(2, issues.size, "段 5 与段 0 都越界")
        assertTrue(issues[0].contains("段 5"))
    }

    // ---------- 负控与守卫 ----------

    @Test
    fun `表格路径零差异——纯表格数据集走经典管线提示词无综述标记`() {
        val ds = Dataset(
            name = "t", importedAt = 1,
            columns = listOf("月份", "销售额"),
            rows = listOf(listOf("1月", "100"), listOf("2月", "150")),
            columnTypes = listOf(com.stragent.core.csv.ColumnType.TEXT, com.stragent.core.csv.ColumnType.NUMBER),
        )
        val llm = RoutingLlm { messages ->
            when {
                systemOf(messages).contains("文本分析员") -> error("表格路径不该有要点提取")
                else -> "首轮报告正文"
            }
        }
        val orch = InsightOrchestrator(llm, FakePrefs(), model = "test")
        val out = orch.generateInsight(ds)
        assertTrue(out.report.contains("首轮报告正文"))
        assertTrue(llm.calls.none { systemOf(it).contains("资深数据分析师，正在为一份「材料」") }, "经典分支不带综述角色块")
    }

    @Test
    fun `无文本数据集直接调综述分支报错`() {
        val ds = Dataset("t", 1, listOf("a"), listOf(listOf("1")), emptyList())
        val e = assertFailsWith<IllegalStateException> {
            generateTextReview(
                RoutingLlm(defaultRoute()), FakePrefs(), "m", "m", ds,
                null, { false }, null, emptyList(), emptyList(),
            )
        }
        assertTrue(e.message!!.contains("综述分支"))
    }

    @Test
    fun `取消——入口拦截零调用`() {
        val ds = textDataset("材料。")
        val llm = RoutingLlm(defaultRoute())
        assertFailsWith<GenerationCancelled> {
            generateTextReview(
                llm, FakePrefs(), "m", "m", ds,
                null, { true }, null, emptyList(), emptyList(),
            )
        }
        assertEquals(0, llm.calls.size)
    }

    @Test
    fun `长材料要点失败块——缺口如实进结论章材料`() {
        val bad = "缺口标记"
        val segs = longSegs(12).mapIndexed { i, s -> if (i == 6) s + bad else s }
        val ds = textDataset(*segs.toTypedArray())
        val llm = RoutingLlm(
            defaultRoute(
                chunkReply = { user -> if (user.contains(bad)) "###坏" else pointsJson },
            ),
        )
        val out = runReview(ds, llm)
        val conclCall = llm.calls.last { userOf(it).contains("结论与疑点") }
        assertTrue(userOf(conclCall).contains("要点提取失败"), "缺口如实写入未答问题材料")
        assertFalse(out.gateFailed)
    }
}
