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
 * T-116 多粒度检索索引测试（RAPTOR 零成本变体）：主题词散布多段的宽泛问题，
 * 细层 top-K 装不下主题全跨距——粗层最优 map 块**保证整块回填**把远端主题段带进窗。
 * 负控：multiGranularity=false 单层行为，远端段不进窗。
 */
class FollowupMultiGranularityTest {

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

    private fun textDataset(vararg segs: String): Dataset = Dataset(
        name = "报告.md", importedAt = 1,
        columns = emptyList(), rows = emptyList(), columnTypes = emptyList(),
        text = TextContent(segs.mapIndexed { i, s -> TextSegment(i + 1, s) }),
    )

    private fun userOf(call: List<Pair<String, String>>) = call.first { it.first == "user" }.second

    /** ~204 字/段（>150 → 每段独立成句组；全段含主题词「管廊」「巡检」；无问题里的「情况/如何」） */
    private fun seg(i: Int): String {
        val head = "管廊项目第%03d标段巡检批次记录：本日对该区域管廊本体结构与排水设施开展例行巡检，".format(i)
        val tail = "巡检发现的零星缺陷已当场处置并登记台账，现场材料堆放有序，照明与通风设备运行正常。" +
            "后续按计划开展下一批次巡检工作。".repeat(6)
        return head + tail
    }

    @Test
    fun `粗层整块回填——主题词散布四十段的宽泛问题远端段进窗`() {
        val ds = textDataset(*Array(40) { i -> seg(i + 1) })
        val llm = AnswerLlm()
        val out = askFollowup(llm, FakePrefs(), ds, emptyList(), "管廊巡检情况如何", "m")
        val user = userOf(llm.calls.single())
        assertTrue(user.contains("第006标段"), "细层 top-6 命中的近端段在窗")
        assertTrue(user.contains("第017标段"), "粗层最优块整块回填：远端主题段进窗")
        assertFalse(out.gateFailed)
        assertEquals(1, llm.calls.size, "多粒度是纯结构层：零额外 LLM 调用")
    }

    @Test
    fun `单层负控——multiGranularity 为 false 时远端段不进窗`() {
        val ds = textDataset(*Array(40) { i -> seg(i + 1) })
        val llm = AnswerLlm()
        val out = askFollowup(
            llm, FakePrefs(), ds, emptyList(), "管廊巡检情况如何", "m",
            multiGranularity = false,
        )
        val user = userOf(llm.calls.single())
        assertTrue(user.contains("第006标段"), "细层 top-6 近端段照常")
        assertFalse(user.contains("第017标段"), "单层行为：远端段不进窗（对照上例）")
    }

    @Test
    fun `短材料零变化——块数不足两层时结构层不介入`() {
        val ds = textDataset("管廊项目第一标段巡检记录。", "管廊项目第二标段巡检记录。")
        val llm = AnswerLlm()
        val out = askFollowup(llm, FakePrefs(), ds, emptyList(), "管廊巡检情况如何", "m")
        val user = userOf(llm.calls.single())
        assertTrue(user.contains("第一标段") && user.contains("第二标段"), "短材料细层已全覆盖")
        assertFalse(out.gateFailed)
    }
}
