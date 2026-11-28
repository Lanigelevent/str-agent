package com.stragent.core.ai

import com.stragent.core.dataset.Dataset
import com.stragent.core.dataset.TextContent
import com.stragent.core.dataset.TextSegment
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.PrefsStore
import com.stragent.core.ports.ChatResult as PortChatResult
import java.util.Collections
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-132 导入后预热测试：预热与追问同键（预热后追问命中缓存零重建/零二次 ctx 调用）、
 * 失败静默（预热异常不影响后续追问照常构建）、预热即增强（不调用也一切照旧）。
 */
class WarmupTextIndexTest {

    @BeforeTest
    fun clear() = TextIndexCache.clear()

    class FakePrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
    }

    /** 计数上下文补语调用（线程安全：预热线程与追问线程可能交汇于 FutureTask） */
    class CountingCtx : com.stragent.core.ports.ChunkContextClient {
        val calls = Collections.synchronizedList(mutableListOf<Int>())
        override fun contextsFor(
            llm: LlmClient,
            model: String,
            chunks: List<String>,
            cancelSignal: () -> Boolean,
        ): List<String?> {
            calls += chunks.size
            return chunks.map { "补语说明甲" }
        }
    }

    class AnswerLlm : LlmClient {
        val calls = java.util.Collections.synchronizedList(mutableListOf<List<Pair<String, String>>>())
        override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int): PortChatResult {
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

    @Test
    fun `预热同键——后续追问命中缓存零二次 ctx 调用`() {
        val ds = textDataset("本工程由华东建设集团承建。", "第二段内容 filler。", "第三段内容 filler。")
        val ctx = CountingCtx()
        // 预热（与导入后台同形态）
        warmupTextIndex(ds.text!!, AnswerLlm(), "m", ctx, null, true)
        assertEquals(1, ctx.calls.size, "预热触发一次 ctx 批调用")
        // 追问：同键命中富化条目 → ctx 不再调用
        val llm = AnswerLlm()
        val out = askFollowup(llm, FakePrefs(), ds, emptyList(), "承建单位是谁", "m", chunkContext = ctx)
        assertEquals(1, ctx.calls.size, "追问命中预热条目：零二次 ctx 调用")
        assertTrue(userOf(llm.calls.single()).contains("华东建设集团"), "检索照常")
        assertEquals(3, out.contextualChunks, "计数随条目回显")
    }

    @Test
    fun `预热失败静默——后续追问照常构建`() {
        val ds = textDataset("本工程由华东建设集团承建。", "第二段 filler。")
        val throwingCtx = object : com.stragent.core.ports.ChunkContextClient {
            override fun contextsFor(
                llm: LlmClient,
                model: String,
                chunks: List<String>,
                cancelSignal: () -> Boolean,
            ): List<String?> = throw IllegalStateException("预热期 ctx 不可用")
        }
        // 预热失败：内部回落 null 槽位（降级条目不留缓存）+ 异常吞掉
        warmupTextIndex(ds.text!!, AnswerLlm(), "m", throwingCtx, null, true)
        // 追问（真实 ctx 可用）：照常构建并命中
        val ctx = CountingCtx()
        val out = askFollowup(AnswerLlm(), FakePrefs(), ds, emptyList(), "承建单位是谁", "m", chunkContext = ctx)
        assertEquals(1, ctx.calls.size, "失败不留缓存：追问重新构建")
        assertEquals(2, out.contextualChunks, "追问的 ctx 正常生效")
    }

    @Test
    fun `未预热零变化——不调用预热时追问自建`() {
        TextIndexCache.clear()
        val ds = textDataset("本工程由华东建设集团承建。", "第二段 filler。")
        val ctx = CountingCtx()
        val out = askFollowup(AnswerLlm(), FakePrefs(), ds, emptyList(), "承建单位是谁", "m", chunkContext = ctx)
        assertEquals(1, ctx.calls.size, "不预热：追问首次自建（既有行为）")
        assertEquals(2, out.contextualChunks)
    }
}
