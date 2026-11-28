package com.stragent.core.ai

import com.stragent.core.dataset.Dataset
import com.stragent.core.dataset.TextContent
import com.stragent.core.dataset.TextSegment
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.PrefsStore
import com.stragent.core.ports.ChatResult as PortChatResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-120 摘要树递归测试：节点数达阈值（默认 16）且摘要器在场时，下层索引文本按序分组
 * （组 8）向上再摘要——主题词只在上层摘要里的问题经上层节点整块回填进窗（跨多块的
 * 成员段并集）。钉住的语义：①递归按「下层摘要的摘要」生长（真 RAPTOR）；②上层命中
 * 回填单位仍是**成员原文段**；③某层摘要失败 → 该层回落原文本且停止递归（诚实降级）。
 */
class FollowupSummaryTreeLevel2Test {

    @org.junit.Before
    fun clearIndexCache() {
        TextIndexCache.clear()
    }


    class FakePrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
    }

    class AnswerLlm : LlmClient {
        val calls = java.util.Collections.synchronizedList(mutableListOf<List<Pair<String, String>>>())
        override fun chat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
        ): PortChatResult {
            calls += messages
            return PortChatResult("按材料回答。", 55)
        }
    }

    /** 递归替身：L1/L2 都按「是否覆盖第 018 标段」给主题/通用摘要（L2 经下层摘要继承该词） */
    private class RecursiveSummarizer : com.stragent.core.ports.BlockSummarizer {
        val callSizes = mutableListOf<Int>()
        override fun summariesFor(
            llm: LlmClient,
            model: String,
            blocks: List<String>,
            cancelSignal: () -> Boolean,
        ): List<String?> {
            callSizes += blocks.size
            return blocks.map { input ->
                val markers = Regex("第\\d+标段").findAll(input).map { it.value }.toList()
                if (markers.size >= 4 && markers.contains("第018标段")) {
                    "本组汇总桥梁附属结构验收专题，含上述各标段的中间验收组织与资料归档安排。"
                } else {
                    "本块汇总" + markers.take(2).joinToString("、").ifBlank { "巡检" } + "等巡检记录。"
                }
            }
        }
    }

    /** 第二层起抛异常的替身：钉住「该层回落原文本 + 停止递归」 */
    private class FailAtSecondLevel : com.stragent.core.ports.BlockSummarizer {
        var calls = 0
        override fun summariesFor(
            llm: LlmClient,
            model: String,
            blocks: List<String>,
            cancelSignal: () -> Boolean,
        ): List<String?> {
            calls++
            if (calls >= 2) throw IllegalStateException("上层摘要服务不可用")
            return blocks.map { "本块汇总巡检记录。" }
        }
    }

    private fun textDataset(vararg segs: String): Dataset = Dataset(
        name = "报告.md", importedAt = 1,
        columns = emptyList(), rows = emptyList(), columnTypes = emptyList(),
        text = TextContent(segs.mapIndexed { i, s -> TextSegment(i + 1, s) }),
    )

    private fun userOf(call: List<Pair<String, String>>) = call.first { it.first == "user" }.second

    /** ~250 字/段（>150 → 每段独立句组；16 段成一块 → 16 块 ≥ 阈值 16；下沉后回填=L1 块 16×250≈4K 装得进 12K 预算） */
    private fun seg(i: Int): String {
        val head = "管廊项目第%03d标段巡检批次记录：".format(i)
        val body = if (i <= 3) "本日完成该标段开工条件核查与交底，" else "本日对该区域管廊本体结构与排水设施开展例行巡检，"
        return head + body + "巡检发现的零星缺陷已当场处置并登记台账，现场秩序正常。".repeat(8)
    }

    @Test
    fun `递归摘要树——主题词只在上层摘要里跨块成员段整块进窗`() {
        val ds = textDataset(*Array(256) { i -> seg(i + 1) })
        val llm = AnswerLlm()
        val summarizer = RecursiveSummarizer()
        val out = askFollowup(
            llm, FakePrefs(), ds, emptyList(), "开工与桥梁附属结构验收专题情况", "m",
            blockSummarizer = summarizer,
        )
        val user = userOf(llm.calls.single())
        assertTrue(user.contains("第018标段"), "上层摘要节点命中：跨块成员段整块进窗")
        assertTrue(user.contains("第001标段"), "细层命中的近端段仍在窗")
        assertFalse(
            user.contains("桥梁附属结构验收专题"),
            "索引专用纪律：摘要（含上层）不进材料回填",
        )
        assertFalse(out.gateFailed)
        // 层级调用形态：L1=20 块一次调用；L2=3 组一次调用（组 8 截断 8+8+4）；L2 节点 3 < 阈值 → 停
                // 递归形态自洽断言：L1 ≥ 阈值 16 触发一次向上分组；L2 组数 = ceil(L1/8)；L2 < 阈值 → 止步两层
        assertEquals(2, summarizer.callSizes.size, "恰一次向上递归")
        assertTrue(summarizer.callSizes[0] >= 16, "L1 节点数达阈值（实际 ${summarizer.callSizes[0]}）")
        assertEquals((summarizer.callSizes[0] + 7) / 8, summarizer.callSizes[1], "L2 组数 = ceil(L1/8)")
    }

    @Test
    fun `上层摘要失败——该层回落原文本且停止递归仍正常回答`() {
        val ds = textDataset(*Array(256) { i -> seg(i + 1) })
        val llm = AnswerLlm()
        val summarizer = FailAtSecondLevel()
        val out = askFollowup(
            llm, FakePrefs(), ds, emptyList(), "管廊巡检情况如何", "m",
            blockSummarizer = summarizer,
        )
        val user = userOf(llm.calls.single())
        assertTrue(user.contains("第006标段"), "L1 摘要池照常回填")
        assertFalse(out.gateFailed)
        assertEquals(2, summarizer.calls, "L2 失败后停止递归（无 L3 调用）")
    }
}
