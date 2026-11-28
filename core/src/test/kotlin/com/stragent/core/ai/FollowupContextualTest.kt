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
 * T-114 块上下文端到端（core 追问文本分支）：
 * ① 上下文补语桥接——问题与目标段无词面重合时，补语里的同义词让 BM25 命中进窗；
 * ② **索引专用纪律**——补语只进索引文本，绝不进材料回填与门禁 brief；
 * ③ 未接线负控零变化；④ 补语生成失败回落（索引文本零变化，回答照常）。
 */
class FollowupContextualTest {

    @org.junit.Before
    fun clearIndexCache() {
        TextIndexCache.clear()
    }


    class FakePrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
    }

    class AnswerLlm(var reply: String = "按材料回答。") : LlmClient {
        val calls = java.util.Collections.synchronizedList(mutableListOf<List<Pair<String, String>>>())
        override fun chat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
        ): PortChatResult {
            calls += messages
            return PortChatResult(reply, 55)
        }
    }

    /** 固定补语替身：目标段给桥接补语，其余给通用补语（真实语义判断由本测试作者给出，P-150 同纪律） */
    private class FixedCtx : com.stragent.core.ports.ChunkContextClient {
        override fun contextsFor(
            llm: LlmClient,
            model: String,
            chunks: List<String>,
            cancelSignal: () -> Boolean,
        ): List<String?> = chunks.map { c ->
            if (c.contains("该部位设计值为十公分")) "桥梁工程桥面铺装设计参数说明" else "工程文档常规段落说明"
        }
    }

    /** 抛异常替身：上下文服务不可用（回落路径触发器） */
    private class ThrowingCtx : com.stragent.core.ports.ChunkContextClient {
        override fun contextsFor(
            llm: LlmClient,
            model: String,
            chunks: List<String>,
            cancelSignal: () -> Boolean,
        ): List<String?> = throw IllegalStateException("上下文服务不可用")
    }

    private fun textDataset(vararg segs: String): Dataset = Dataset(
        name = "报告.md", importedAt = 1,
        columns = emptyList(), rows = emptyList(), columnTypes = emptyList(),
        text = TextContent(segs.mapIndexed { i, s -> TextSegment(i + 1, s) }),
    )

    private fun userOf(call: List<Pair<String, String>>) = call.first { it.first == "user" }.second

    /** 填充段：与问题/目标均无 bigram 重合（隔离命中面） */
    private fun filler(i: Int) = "第${i}项议题讨论情况记录条目，内容涉及日常事务安排与例行检查工作。"

    // ---------- ① 桥接进窗 ----------

    @Test
    fun `上下文桥接——问题与目标段无词面重合经补语命中进窗`() {
        val ds = textDataset(
            "本工程为桥梁工程，含桥面系施工内容。",           // 段 1（问题词面命中：桥面）
            *Array(8) { i -> filler(i + 1) },              // 段 2-9
            "该部位设计值为十公分。",                        // 段 10（目标：与问题零 bigram 重合）
        )
        val llm = AnswerLlm()
        val out = askFollowup(
            llm, FakePrefs(), ds, emptyList(), "桥面铺装厚度是多少", "m",
            chunkContext = FixedCtx(),
        )
        assertEquals(10, out.contextualChunks, "全部块获得补语")
        val user = userOf(llm.calls.single())
        assertTrue(user.contains("<text_material>"), "文本分支材料块在")
        assertTrue(user.contains("该部位设计值为十公分"), "补语桥接：目标段进窗")
        assertTrue(user.contains("段 10"), "目标段锚随行")
        assertFalse(
            user.contains("桥梁工程桥面铺装设计参数说明"),
            "索引专用纪律：补语不进材料回填（材料里只有原文）",
        )
        assertFalse(out.gateFailed)
    }

    // ---------- ②③④ 负控与回落 ----------

    @Test
    fun `未接线负控——无补语时零词面重合段不进窗`() {
        val ds = textDataset(
            "本工程为桥梁工程，含桥面系施工内容。",
            *Array(8) { i -> filler(i + 1) },
            "该部位设计值为十公分。",
        )
        val llm = AnswerLlm()
        val out = askFollowup(llm, FakePrefs(), ds, emptyList(), "桥面铺装厚度是多少", "m")
        assertEquals(0, out.contextualChunks, "未启用：计数 0")
        assertEquals(1, llm.calls.size, "词面命中段 1 → 不触发改写路径，只有回答调用")
        val user = userOf(llm.calls.single())
        assertFalse(user.contains("该部位设计值为十公分"), "单查询召不回零重合段（对照①）")
        assertTrue(user.contains("桥面系施工内容"), "词面命中的段 1 照常进窗")
    }

    @Test
    fun `补语失败回落——索引文本零变化回答照常`() {
        val ds = textDataset(
            "本工程为桥梁工程，含桥面系施工内容。",
            *Array(8) { i -> filler(i + 1) },
            "该部位设计值为十公分。",
        )
        val llm = AnswerLlm()
        val out = askFollowup(
            llm, FakePrefs(), ds, emptyList(), "桥面铺装厚度是多少", "m",
            chunkContext = ThrowingCtx(),
        )
        assertEquals(0, out.contextualChunks, "失败回落：计数 0")
        assertEquals(1, llm.calls.size, "回落单查询：只有回答调用")
        val user = userOf(llm.calls.single())
        assertTrue(user.contains("桥面系施工内容"), "词面命中段照常进窗")
        assertFalse(user.contains("该部位设计值为十公分"), "回落 = 索引文本零变化")
        assertFalse(out.gateFailed)
    }
}
