package com.stragent.core.ai

import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset
import com.stragent.core.ports.ChatResult as PortChatResult
import com.stragent.core.ports.LlmClient
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 报告正文的 **token 级流式**（T-013 余量 · 阶段 2）在编排层的行为验证。
 *
 * 传输层（HttpLlmClient）的 SSE 契约在 `data` 模块另有套件；这里钉的是 **core 侧的四件事**：
 *  ① 节流：碎片攒够一段或等够时间才发一帧，帧数不能等于 token 数（否则把进度总线当字符管道）；
 *  ② 拼接性：按 (章, 次) 到达序拼起来的正文 = 这一章的最终正文（客户端贴的字不能是假的）；
 *  ③ 重写信号：一章重试 → attempt 递增，旧片段不与新正文混拼（这条最容易漏也最伤：
 *     混拼出来的是一段前后不搭的鬼话，而调用方无从发现）；
 *  ④ 反作用：没有订阅者时不得把 token 通道接下去（实现侧就不该因此改成流式请求）。
 */
class BodyStreamTest {
    private val sampleDir = File("../fixtures/sample")

    private fun datasetOf(name: String): Dataset {
        val parsed = Csv.parseDelimited(File(sampleDir, "$name.csv").readText())
        val limited = Csv.limitRows(parsed)
        return Dataset(
            name = "$name.csv", importedAt = 1,
            columns = limited.columns, rows = limited.rows,
            columnTypes = Csv.inferTypes(limited.rows, limited.columns.size),
            delimiter = limited.delimiter, truncated = limited.truncated, sourceRows = limited.totalRows,
        )
    }

    /**
     * 把正文按 [tokenSize] 切片逐段回调的 fake —— 模拟真实 SSE 的逐 token 到达。
     * 同时记录每次 `streamChat` 有没有拿到 token 通道（⑤ 的观测点）。
     */
    private class StreamingLlm(
        private val outputs: MutableList<PortChatResult>,
        private val tokenSize: Int = 7,
    ) : LlmClient {
        val tokenFlags = mutableListOf<Boolean>()

        override fun chat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
        ): PortChatResult = if (outputs.isEmpty()) PortChatResult("(空)", 10) else outputs.removeAt(0)

        override fun streamChat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
            onToken: ((String) -> Unit)?,
        ): PortChatResult {
            tokenFlags.add(onToken != null)
            val result = chat(model, messages, temperature, maxTokens)
            if (onToken != null) result.text.chunked(tokenSize).forEach(onToken)
            return result
        }
    }

    /** 第一次流式半途炸掉、之后正常的 fake —— 用来验证「重写」这件事可被调用方识别 */
    private class FlakyStreamLlm(private val body: String) : LlmClient {
        var streamAttempts = 0

        override fun chat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
        ): PortChatResult = PortChatResult(body, 10)

        override fun streamChat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
            onToken: ((String) -> Unit)?,
        ): PortChatResult {
            streamAttempts += 1
            if (streamAttempts == 1) {
                onToken?.invoke("半途而废的片段")
                throw RuntimeException("流断了")
            }
            if (onToken != null) body.chunked(50).forEach(onToken)
            return PortChatResult(body, 10)
        }
    }

    private fun chapterBody(mark: String): String = "$mark" + "甲".repeat(400)

    @Test
    fun `① 节流器：攒够一段或等够时间才发帧，章末必发尾巴`() {
        val frames = mutableListOf<BodyDelta>()
        var now = 0L
        val throttle = BodyThrottle(
            chapter = 2, total = 5, title = "渠道结构", attempt = 1, emit = { frames += it },
            minChars = 10, maxWaitMs = 100, clock = { now },
        )
        throttle.onToken("aaaa")
        throttle.onToken("bbbb")
        assertTrue(frames.isEmpty(), "没攒够也没超时，不该发帧")

        now = 50
        throttle.onToken("cccc") // 12 字 ≥ minChars → 立即成帧
        assertEquals(1, frames.size)
        assertEquals("aaaabbbbcccc", frames[0].text)
        assertEquals(12, frames[0].chars, "chars 是本章累计，不是本帧新增")
        assertEquals(2, frames[0].chapter)
        assertEquals("渠道结构", frames[0].title)

        now = 150 // 距上次发帧 100ms → 只有 2 字也要发（慢模型不能看起来卡死）
        throttle.onToken("dd")
        assertEquals(2, frames.size)
        assertEquals("dd", frames[1].text)
        assertEquals(14, frames[1].chars)

        throttle.flush()
        assertEquals(2, frames.size, "空尾巴不发空帧")

        throttle.onToken("ee")
        throttle.flush()
        assertEquals(3, frames.size)
        assertEquals("ee", frames[2].text, "章末不足 minChars 的尾段也必须发出去，否则拼不回全文")
        assertEquals(16, frames[2].chars)
    }

    @Test
    fun `② 分章正文逐帧流出：按章有序、拼接等于该章正文、帧数多于章数`() {
        val prefs = OrchestrationTest.FakePrefs()
        val outputs = mutableListOf<PortChatResult>()
        repeat(40) { i -> outputs.add(PortChatResult(chapterBody("第${i + 1} 章："), 100)) }
        outputs.add(PortChatResult("{\"issues\":[]}", 20))
        val llm = StreamingLlm(outputs, tokenSize = 7)
        val deltas = mutableListOf<BodyDelta>()
        val orchestrator = InsightOrchestrator(llm, prefs, model = "test")

        val result = orchestrator.generateInsight(datasetOf("superstore"), onBody = { deltas += it })
        val total = result.chapters!!.size
        assertTrue(total >= 10, "superstore 应走分章路径，章数 $total")

        assertEquals(0, deltas.count { it.total != total }, "每帧都带总章数，客户端才有分母")
        assertEquals((1..total).toList(), deltas.map { it.chapter }.distinct(), "章号必须按序出现、不重不漏")
        deltas.groupBy { it.chapter }.forEach { (chapter, list) ->
            val body = chapterBody("第$chapter 章：")
            assertEquals(body, list.joinToString("") { it.text }, "第 $chapter 章的增量拼接对不上正文")
            assertEquals(body.length, list.last().chars, "末帧的累计字数应等于该章正文字数")
            assertTrue(list.all { it.attempt == 1 }, "一次写成的章不该出现 attempt 2")
            assertEquals(
                list.map { it.chars }, list.map { it.chars }.sorted(),
                "同章的累计字数必须单调递增",
            )
        }
        assertTrue(
            deltas.size > total,
            "帧数应多于章数 —— 否则「token 级流式」退化成了「按章报进度」（$deltas.size 帧 / $total 章）",
        )
        assertEquals(
            result.chapters.map { it.first }.toSet(), deltas.map { it.title }.toSet(),
            "帧里的章名要用规划出来的标题",
        )
    }

    @Test
    fun `③ 单次成文也发正文帧（整份正文按第一章流出）`() {
        val prefs = OrchestrationTest.FakePrefs()
        val body = "## 总体概览\n" + "乙".repeat(200)
        val outputs = mutableListOf(PortChatResult(body, 500), PortChatResult("{\"issues\":[]}", 20))
        val deltas = mutableListOf<BodyDelta>()
        val orchestrator = InsightOrchestrator(StreamingLlm(outputs, tokenSize = 30), prefs, model = "test")

        val result = orchestrator.generateInsight(datasetOf("single_col"), onBody = { deltas += it })
        assertTrue(deltas.isNotEmpty(), "single-shot 路径同样要能流式，否则小数据集的报告仍是黑箱等待")
        assertEquals(listOf(1), deltas.map { it.chapter }.distinct())
        assertEquals(1, deltas.first().total)
        assertEquals("全文", deltas.first().title)
        val joined = deltas.joinToString("") { it.text }
        assertEquals(body, joined, "正文帧拼接 = 模型给的正文")
        assertEquals(result.report, joined, "正文帧拼出来的就是最终报告本身")
    }

    @Test
    fun `④ 章重试时 attempt 递增，旧片段不与新正文混拼`() {
        val prefs = OrchestrationTest.FakePrefs()
        val body = chapterBody("重写后的正文：")
        val llm = FlakyStreamLlm(body)
        val deltas = mutableListOf<BodyDelta>()
        val orchestrator = InsightOrchestrator(llm, prefs, model = "test")

        val result = orchestrator.generateInsight(datasetOf("superstore"), onBody = { deltas += it })
        val first = deltas.filter { it.chapter == 1 }
        assertEquals(listOf(1, 2), first.map { it.attempt }.distinct(), "首试失败重打，attempt 要看得出来")
        // attempt=1 那一帧是废片段；attempt=2 的帧拼起来才等于这一章的正文
        assertEquals("半途而废的片段", first.filter { it.attempt == 1 }.joinToString("") { it.text })
        assertEquals(
            body, first.filter { it.attempt == 2 }.joinToString("") { it.text },
            "重写那一遍必须自成一段可拼接的正文（调用方按 (章, 次) 归并）",
        )
        assertTrue(deltas.filter { it.chapter != 1 }.all { it.attempt == 1 }, "其余章没有重试")
        assertTrue(result.report.contains(body), "重试后的正文进了报告")
    }

    @Test
    fun `⑤ 有订阅者才把 token 通道接下去，没订阅者一律传 null`() {
        val prefs = OrchestrationTest.FakePrefs()

        // 对照：不传 onBody（宿主没订阅 / 客户端没带 progressToken 的常态）
        val quiet = StreamingLlm(mutableListOf(PortChatResult("报告正文。", 500), PortChatResult("{\"issues\":[]}", 20)))
        InsightOrchestrator(quiet, prefs, model = "test").generateInsight(datasetOf("single_col"))
        assertTrue(
            quiet.tokenFlags.none { it },
            "没有正文订阅者时不得建节流器、不得把 token 通道接下去 —— 实现侧不该因此改成流式请求",
        )

        // 正向：报告那一次必须接上；紧随其后的语义审查**不许**接（审查的输出不是正文，
        // 接上去就是往客户端的正文流里插一段 JSON 废文本）
        val loud = StreamingLlm(mutableListOf(PortChatResult("报告正文。", 500), PortChatResult("{\"issues\":[]}", 20)))
        val got = mutableListOf<BodyDelta>()
        InsightOrchestrator(loud, prefs, model = "test").generateInsight(datasetOf("single_col"), onBody = { got += it })
        assertEquals(true, loud.tokenFlags.first(), "有订阅者时报告生成必须走 token 通道")
        assertEquals(1, loud.tokenFlags.count { it }, "只有报告那一次接通道，审查等旁路调用不得接")
        assertTrue(got.isNotEmpty(), "订阅者至少收到一帧正文")
        assertTrue(got.all { !it.text.contains("\"issues\"") }, "正文流里不得混进审查输出")
    }
}
