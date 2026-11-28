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
 * T-096 追问文本分支测试：BM25 命中→父段回填、0 命中如实拒绝、混合双轨材料、
 * 门禁数字回链、表格路径零差异负控、取消。
 */
class FollowupTextTest {

    class FakePrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
    }

    /** 记录消息的假模型；按 user 消息内容路由应答 */
    class ScriptedLlm(private val answer: (String) -> String) : LlmClient {
        val calls = java.util.Collections.synchronizedList(mutableListOf<List<Pair<String, String>>>())
        override fun chat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
        ): PortChatResult {
            calls += messages
            val user = messages.firstOrNull { it.first == "user" }?.second ?: ""
            return PortChatResult(answer(user), 55)
        }
    }

    private fun textDataset(vararg segs: String, name: String = "报告.md"): Dataset = Dataset(
        name = name, importedAt = 1,
        columns = emptyList(), rows = emptyList(), columnTypes = emptyList(),
        text = TextContent(segs.mapIndexed { i, s -> TextSegment(i + 1, s) }),
    )

    private fun userOf(call: List<Pair<String, String>>) = call.first { it.first == "user" }.second
    private fun systemOf(call: List<Pair<String, String>>) = call.first { it.first == "system" }.second

    // ---------- 检索与回填 ----------

    @Test
    fun `命中段回填——问题相关段进材料且邻段窗口随行`() {
        val ds = textDataset(
            "公司背景介绍：成立于 1990 年。",                      // 段 1（无关话题）
            "第二季度销售额 128 万元，环比增长 12%。",              // 段 2（目标）
            "组织架构：设有五个事业部。",                          // 段 3
        )
        val llm = ScriptedLlm { "按材料回答（段 2）" }
        askFollowup(llm, FakePrefs(), ds, emptyList(), "销售额多少", "m")
        assertTrue(llm.calls.size == 1)
        val user = userOf(llm.calls.single())
        assertTrue(user.contains("<text_material>"), "文本分支应有材料块")
        assertTrue(user.contains("段 2"), "命中段锚在材料里")
        assertTrue(user.contains("销售额 128"), "命中段正文在材料里")
        assertTrue(user.contains("段 1") && user.contains("段 3"), "±1 段窗口随行（small-to-big）")
        assertTrue(user.contains("公司背景介绍：成立于 1990 年"), "窗口内的段 1 正文随行")
    }

    @Test
    fun `corrective 改写——0 命中先改写重试一次，仍 0 命中才拒答（拒答回执带改写 token）`() {
        val ds = textDataset("本季度销售额 128 万元。")
        val llm = ScriptedLlm { " revenue growth rate" } // 改写器输出（仍不含材料词）
        val out = askFollowup(llm, FakePrefs(), ds, emptyList(), "股票代码是什么", "m")
        assertEquals(1, llm.calls.size, "只有改写一次轻调用，无回答调用")
        assertTrue(out.content.contains("未检索到") && out.content.contains("改写重试"), "拒答说明 corrective 已尝试")
        assertEquals(55, out.totalTokens, "改写调用 token 如实入账（fake 口径 55）")
        assertFalse(out.gateFailed)
    }

    @Test
    fun `corrective 改写生效——改写后的查询命中材料则正常回答`() {
        val ds = textDataset("本季度销售额 128 万元。")
        val llm = ScriptedLlm { user ->
            // 改写调用的 user = 原问题；回答调用的 user = 命中材料（含 text_material 块）
            if (user.contains("text_material")) "销售额 128 万元（段 1）。" else "销售额多少"
        }
        val out = askFollowup(llm, FakePrefs(), ds, emptyList(), "营收表现", "m")
        assertTrue(out.content.contains("128"), "改写重试命中 → 正常回答")
        assertFalse(out.gateFailed)
    }

    // ---------- 混合与门禁 ----------

    @Test
    fun `混合数据集——命中段落与表格摘要双轨进提示词`() {
        val ds = Dataset(
            name = "季报", importedAt = 1,
            columns = listOf("月份", "销售额"),
            rows = listOf(listOf("1月", "100"), listOf("2月", "150")),
            columnTypes = listOf(com.stragent.core.csv.ColumnType.TEXT, com.stragent.core.csv.ColumnType.NUMBER),
            text = TextContent(listOf(TextSegment(1, "第二季度毛利率 30%，好于上季度。"))),
        )
        val llm = ScriptedLlm { "混合回答" }
        askFollowup(llm, FakePrefs(), ds, emptyList(), "毛利率情况", "m")
        val user = userOf(llm.calls.single())
        assertTrue(user.contains("毛利率 30%"), "命中段进材料")
        assertTrue(user.contains("表格统计摘要"), "混合表格摘要进提示词")
        assertTrue(user.contains("毛利率"), "表格摘要含列统计")
    }

    @Test
    fun `门禁——回答里的数字不在命中材料中则 gateFailed`() {
        val ds = textDataset("本季度销售额 100 万元，增长稳定。")
        val llm = ScriptedLlm { "销售额 999（段 1）" }
        val out = askFollowup(llm, FakePrefs(), ds, emptyList(), "销售额多少", "m")
        assertTrue(out.gateFailed, "999 不在材料里 → 门禁拦截")
        assertTrue(out.gateIssues.isNotEmpty())
    }

    @Test
    fun `门禁通过——回答数字均在材料中且带段锚`() {
        val ds = textDataset("本季度销售额 100 万元，增长稳定。")
        val llm = ScriptedLlm { "销售额 100 万元（段 1）。" }
        val out = askFollowup(llm, FakePrefs(), ds, emptyList(), "销售额多少", "m")
        assertFalse(out.gateFailed)
        assertTrue(out.gateIssues.isEmpty())
    }

    // ---------- 负控与守卫 ----------

    @Test
    fun `表格路径零差异——纯表格数据集走经典分支提示词无文本材料块`() {
        val ds = Dataset(
            name = "表", importedAt = 1,
            columns = listOf("月份", "销售额"),
            rows = listOf(listOf("1月", "100")),
            columnTypes = listOf(com.stragent.core.csv.ColumnType.TEXT, com.stragent.core.csv.ColumnType.NUMBER),
        )
        val llm = ScriptedLlm { "经典回答" }
        askFollowup(llm, FakePrefs(), ds, emptyList(), "销售额多少", "m")
        val user = userOf(llm.calls.single())
        assertTrue(user.contains("统计摘要"), "经典分支的数据摘要块在")
        assertFalse(user.contains("<text_material>"), "经典分支不该出现文本材料块")
        assertFalse(systemOf(llm.calls.single()).contains("zero_fabrication"), "经典分支的 system 不含文本分支纪律块")
    }

    @Test
    fun `取消——入口拦截零调用`() {
        val ds = textDataset("材料。")
        val llm = ScriptedLlm { "不该被调用" }
        assertFailsWith<GenerationCancelled> {
            askFollowup(llm, FakePrefs(), ds, emptyList(), "问题", "m", cancelSignal = { true })
        }
        assertEquals(0, llm.calls.size)
    }

    @Test
    fun `空问题报错`() {
        val ds = textDataset("材料。")
        val llm = ScriptedLlm { "不该被调用" }
        assertFailsWith<IllegalArgumentException> {
            askFollowup(llm, FakePrefs(), ds, emptyList(), "   ", "m")
        }
    }
}
