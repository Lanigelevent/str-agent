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
 * T-117 摘要树端到端（core 追问文本分支）：
 * ① 主题问题（主题词只在摘要里）经摘要节点命中远端块——成员段整块回填进窗；
 * ② **索引专用纪律**——摘要绝不进材料回填与门禁 brief（材料里只有原文）；
 * ③ 回落负控——summarizer=null 时粗层用原块文本（T-116 行为），远端段不进窗。
 */
class FollowupSummaryTreeTest {

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

    /** 固定摘要替身：含目标段的块给主题摘要（含问题主题词），其余给通用摘要（P-150 替身纪律） */
    private class FixedSummarizer : com.stragent.core.ports.BlockSummarizer {
        val calls = mutableListOf<List<String>>()
        override fun summariesFor(
            llm: LlmClient,
            model: String,
            blocks: List<String>,
            cancelSignal: () -> Boolean,
        ): List<String?> = blocks.map { b ->
            calls += blocks
            if (b.contains("第030标段")) "本块汇总桥梁附属结构验收专题内容，含中间验收组织与资料归档安排。"
            else "本块汇总日常巡检记录与现场管理情况。"
        }
    }

    private fun textDataset(vararg segs: String): Dataset = Dataset(
        name = "报告.md", importedAt = 1,
        columns = emptyList(), rows = emptyList(), columnTypes = emptyList(),
        text = TextContent(segs.mapIndexed { i, s -> TextSegment(i + 1, s) }),
    )

    private fun userOf(call: List<Pair<String, String>>) = call.first { it.first == "user" }.second

    /** ~204 字/段（>150 → 每段独立成句组）；全段含「管廊」「巡检」；1-3 段含「开工」；30 段为验收专题目标 */
    private fun seg(i: Int): String {
        val head = "管廊项目第%03d标段巡检批次记录：".format(i)
        val body = if (i <= 3) "本日完成该标段开工条件核查与首件工程验收交底，" else "本日对该区域管廊本体结构与排水设施开展例行巡检，"
        val tail = "巡检发现的零星缺陷已当场处置并登记台账，现场材料堆放有序，照明与通风设备运行正常。" +
            "后续按计划开展下一批次巡检工作。".repeat(6)
        return head + body + tail
    }

    @Test
    fun `摘要节点命中——主题词只在摘要里远端块成员段整块进窗`() {
        val ds = textDataset(*Array(40) { i -> seg(i + 1) })
        val llm = AnswerLlm()
        val summarizer = FixedSummarizer()
        val out = askFollowup(
            llm, FakePrefs(), ds, emptyList(), "开工与桥梁附属结构验收专题情况", "m",
            blockSummarizer = summarizer,
        )
        val user = userOf(llm.calls.single())
        assertTrue(user.contains("第030标段"), "摘要节点命中：远端块成员段整块进窗")
        assertTrue(user.contains("第001标段"), "细层命中的近端段仍在窗")
        assertFalse(
            user.contains("桥梁附属结构验收专题"),
            "索引专用纪律：摘要文本不进材料回填（材料里只有原文）",
        )
        assertFalse(out.gateFailed)
        // 摘要器按块调用（块数由内容长度决定）；缓存语义由单测覆盖
        assertTrue(summarizer.calls.size >= 2, "每块一次轻调用")
    }

    @Test
    fun `回落负控——无摘要器时粗层用原块文本远端段不进窗`() {
        val ds = textDataset(*Array(40) { i -> seg(i + 1) })
        val llm = AnswerLlm()
        val out = askFollowup(llm, FakePrefs(), ds, emptyList(), "开工与桥梁附属结构验收专题情况", "m")
        val user = userOf(llm.calls.single())
        assertTrue(user.contains("第001标段"), "原块粗层（T-116）：开工词面命中的首块整块回填")
        assertFalse(user.contains("第030标段"), "原块索引无主题词：远端块不进窗（对照①）")
    }
}
