package com.stragent.core.ai

import com.stragent.core.dataset.Dataset
import com.stragent.core.dataset.TextContent
import com.stragent.core.dataset.TextSegment
import com.stragent.core.ports.ChatResult as PortChatResult
import com.stragent.core.ports.DecomposeResult
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.PrefsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-113 查询分解端到端（core 追问文本分支）：
 * ① 隐式多跳——原始查询召不回的第二跳源，经子查询检索 + RRF 合并进窗；
 * ② 未分解负控——decomposer=null 单查询行为零变化；
 * ③ 分解器放弃（subQueries=null）零变化且 token 如实入账；
 * ④ 分解器抛异常回落单查询（诚实降级）。
 */
class FollowupDecompTest {

    @org.junit.Before
    fun clearIndexCache() {
        TextIndexCache.clear()
    }


    class FakePrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
    }

    /** 按 system 内容路由：分解调用（含「分解器」）与回答调用（含 text_material）分轨应答 */
    class RoutingLlm(
        private val decomposeReply: (() -> String)? = null,
        private val answerReply: (String) -> String = { "（占位应答）" },
        private val throwOnDecompose: Boolean = false,
    ) : LlmClient {
        val calls = java.util.Collections.synchronizedList(mutableListOf<List<Pair<String, String>>>())
        override fun chat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
        ): PortChatResult {
            calls += messages
            val system = messages.firstOrNull { it.first == "system" }?.second ?: ""
            val user = messages.firstOrNull { it.first == "user" }?.second ?: ""
            if (system.contains("分解器")) {
                if (throwOnDecompose) throw IllegalStateException("分解服务不可用")
                return PortChatResult(decomposeReply?.invoke() ?: "", 55)
            }
            return PortChatResult(answerReply(user), 55)
        }
    }

    /** 固定子查询替身：分解结果按问题路由（真实语义判断由本测试作者给出，P-150 同纪律） */
    private class FixedDecomposer(private val subs: List<String>?) : com.stragent.core.ports.QueryDecomposer {
        override fun decompose(
            llm: LlmClient,
            model: String,
            question: String,
            cancelSignal: () -> Boolean,
        ): DecomposeResult = DecomposeResult(subs, 55)
    }

    /** 抛异常替身：分解服务不可用（诚实降级路径的触发器） */
    private class ThrowingDecomposer : com.stragent.core.ports.QueryDecomposer {
        override fun decompose(
            llm: LlmClient,
            model: String,
            question: String,
            cancelSignal: () -> Boolean,
        ): DecomposeResult = throw IllegalStateException("分解服务不可用")
    }

    private fun textDataset(vararg segs: String): Dataset = Dataset(
        name = "报告.md", importedAt = 1,
        columns = emptyList(), rows = emptyList(), columnTypes = emptyList(),
        text = TextContent(segs.mapIndexed { i, s -> TextSegment(i + 1, s) }),
    )

    private fun userOf(call: List<Pair<String, String>>) = call.first { it.first == "user" }.second

    /** 填充段：与主线无词面重合的话题段（隔离 bigram 命中） */
    private fun filler(i: Int) = "第${i}项议题讨论情况记录条目，内容涉及日常事务安排与例行检查工作。"

    // ---------- ① 隐式多跳进窗 ----------

    @Test
    fun `分解合并——原始查询召不回的第二跳源经子查询进窗`() {
        val ds = textDataset(
            "本工程由华东建设集团承建，项目负责人是张三。",          // 段 1（跳 1：原始查询命中）
            *Array(8) { i -> filler(i + 1) },                     // 段 2-9（填充）
            "张三兼任集团安全生产委员会主任。",                      // 段 10（跳 2：原始查询 bigram 全不重合）
        )
        val question = "承建负责人的其他任职"
        val llm = RoutingLlm(
            answerReply = { "张三兼任集团安全生产委员会主任（段 10），承建单位为华东建设集团（段 1）。" },
        )
        val out = askFollowup(
            llm, FakePrefs(), ds, emptyList(), question, "m",
            decomposer = FixedDecomposer(listOf("承建单位与项目负责人", "张三兼任职务")),
        )
        assertEquals(listOf("承建单位与项目负责人", "张三兼任职务"), out.subQueries, "生效子查询回显")
        val answerCall = llm.calls.last()
        val user = userOf(answerCall)
        assertTrue(user.contains("<text_material>"), "文本分支材料块在")
        assertTrue(user.contains("段 10"), "第二跳源段 10 进窗")
        assertTrue(user.contains("张三兼任集团安全生产委员会主任"), "第二跳源正文随行")
        assertTrue(user.contains("段 1") && user.contains("张三"), "跳 1 源仍在窗")
        assertFalse(out.gateFailed, "回答数字/事实均可回链材料")
        // 调用记账：分解在端口替身层完成（不经 LLM），LLM 侧只有回答一次调用
        assertEquals(1, llm.calls.size)
        assertEquals(110, out.totalTokens, "分解 55（端口替身记账）+ 回答 55 如实入账")
    }

    // ---------- ②③ 零变化负控 ----------

    @Test
    fun `未接线负控——decomposer 为 null 单查询行为零变化`() {
        val ds = textDataset(
            "本工程由华东建设集团承建，项目负责人是张三。",
            *Array(8) { i -> filler(i + 1) },
            "张三兼任集团安全生产委员会主任。",
        )
        val llm = RoutingLlm(answerReply = { "承建单位为华东建设集团（段 1）。" })
        val out = askFollowup(llm, FakePrefs(), ds, emptyList(), "承建负责人的其他任职", "m")
        assertEquals(emptyList(), out.subQueries, "未分解：回显空表")
        assertEquals(1, llm.calls.size, "只有回答一次调用")
        assertFalse(userOf(llm.calls.single()).contains("张三兼任"), "单查询召不回第二跳源（对照①）")
    }

    @Test
    fun `分解器放弃——subQueries 为 null 时检索行为与单查询一致且 token 记账`() {
        val ds = textDataset(
            "本工程由华东建设集团承建，项目负责人是张三。",
            *Array(8) { i -> filler(i + 1) },
            "张三兼任集团安全生产委员会主任。",
        )
        val llm = RoutingLlm(answerReply = { "承建单位为华东建设集团（段 1）。" })
        val out = askFollowup(
            llm, FakePrefs(), ds, emptyList(), "承建负责人的其他任职", "m",
            decomposer = FixedDecomposer(null),
        )
        assertEquals(emptyList(), out.subQueries, "放弃分解：回显空表")
        assertEquals(1, llm.calls.size, "分解在端口替身层完成，LLM 侧只有回答调用")
        assertFalse(userOf(llm.calls.single()).contains("张三兼任"), "放弃后检索与单查询一致")
        assertEquals(110, out.totalTokens, "分解 55（放弃也记账）+ 回答 55")
    }

    // ---------- ④ 失败回落 ----------

    @Test
    fun `分解器抛异常——回落单查询照常回答`() {
        val ds = textDataset("本工程由华东建设集团承建，项目负责人是张三。")
        val llm = RoutingLlm(
            throwOnDecompose = true,
            answerReply = { "承建单位为华东建设集团（段 1）。" },
        )
        val out = askFollowup(
            llm, FakePrefs(), ds, emptyList(), "承建单位是谁", "m",
            decomposer = ThrowingDecomposer(),
        )
        assertEquals(emptyList(), out.subQueries, "失败回落：回显空表")
        assertTrue(out.content.contains("华东建设集团"), "单查询照常回答")
        assertFalse(out.gateFailed)
    }
}
