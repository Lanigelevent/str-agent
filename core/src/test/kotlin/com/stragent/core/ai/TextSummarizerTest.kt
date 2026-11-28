package com.stragent.core.ai

import com.stragent.core.dataset.Dataset
import com.stragent.core.dataset.TextContent
import com.stragent.core.dataset.TextSegment
import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.DatasetStore
import com.stragent.core.ports.LlmClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-096 TextSummarizer 测试：三层路由（stuff/map-reduce/层级）+ quote-first + 白名单 +
 * kv 续传 + 取消 + 失败容错。RoutingLlm 钉步序：按 system 标记路由返回，
 * 步数与顺序均可断言。
 */
class TextSummarizerTest {

    private fun textDataset(
        segments: List<TextSegment>,
        name: String = "样例.txt",
    ): Dataset = Dataset(
        name = name, importedAt = 1,
        columns = emptyList(), rows = emptyList(), columnTypes = emptyList(),
        text = TextContent(TextChunker.normalizeSegments(segments)),
    )

    private fun paragraphs(n: Int, charsPerSeg: Int, marker: String? = null): List<TextSegment> =
        List(n) { i ->
            val base = "第${i + 1}段内容字" + "。" + "充".repeat(charsPerSeg)
            TextSegment(0, if (marker != null && i == n / 2) base + marker else base)
        }

    private fun longText(chars: Int): List<TextSegment> {
        // 每段 ~600 字符，段数按需填充到目标总量
        val n = (chars / 600).coerceAtLeast(1)
        return paragraphs(n, 590)
    }

    private fun jsonPoints(
        vararg items: Triple<String, String, Int>,
        keyNumbers: List<String> = emptyList(),
        summary: String = "本块概述",
    ): String {
        val points = items.joinToString(",") { (t, q, s) -> """{"text":"$t","quote":"$q","segment":$s}""" }
        val nums = keyNumbers.joinToString(",") { "\"$it\"" }
        return """{"points":[$points],"keyNumbers":[$nums],"summary":"$summary"}"""
    }

    /** 按 system 标记路由；记录全部调用的消息（钉步序用） */
    private class RoutingLlm(private val route: (List<Pair<String, String>>, Int) -> String) : LlmClient {
        val calls = java.util.Collections.synchronizedList(mutableListOf<List<Pair<String, String>>>())
        private var n = 0
        override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int): ChatResult {
            calls.add(messages)
            return ChatResult(route(messages, ++n), 123)
        }
    }

    private fun systemOf(call: List<Pair<String, String>>): String = call.first { it.first == "system" }.second
    private fun userOf(call: List<Pair<String, String>>): String = call.last { it.first == "user" }.second

    private class MemStore : DatasetStore {
        val map = linkedMapOf<String, String>()
        override fun put(key: String, value: String) { map[key] = value }
        override fun get(key: String): String? = map[key]
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): List<String> = map.keys.toList()
    }

    private fun tally(result: TextSummary): Int = result.totalTokens

    // ---------- 路由 ----------

    @Test
    fun `stuff 路由——短文单块直通零归并调用`() {
        val ds = textDataset(paragraphs(2, 100))
        val llm = RoutingLlm { messages, _ ->
            val sys = systemOf(messages)
            when {
                sys.contains("文本分析员") -> jsonPoints(
                    Triple("销售额增长 128", "销售额增长 128", 1),
                    keyNumbers = listOf("128", "45.6%"),
                    summary = "材料围绕增长",
                )
                else -> error("stuff 路由不该出现归并调用：" + sys.take(50))
            }
        }
        val out = summarizeText(llm, "m", ds, maxWords = 300)
        assertEquals("stuff", out.route)
        assertEquals(1, out.chunkCount)
        assertEquals(1, llm.calls.size, "stuff + maxWords<400 → 只有 1 次模型调用")
        assertEquals("材料围绕增长", out.summary)
        assertEquals(listOf("128", "45.6%"), out.numbersWithAnchors.map { it.number })
        assertEquals("段 1-2", out.numbersWithAnchors.first().anchor)
        assertEquals(emptyList(), out.failedChunks)
        assertEquals(123, out.totalTokens)
    }

    @Test
    fun `map-reduce 路由——逐块要点再归并再密度优化两轮`() {
        val ds = textDataset(longText(36_000)) // >30K → map-reduce
        val chunkCount = TextChunker.aggregateChunks(ds.text!!).size
        val llm = RoutingLlm { messages, _ ->
            when {
                systemOf(messages).contains("文本分析员") -> jsonPoints(
                    Triple("要点：销售额增长 128", "销售额增长 128", 1),
                    keyNumbers = listOf("128"),
                )
                systemOf(messages).contains("总结撰写人") -> "终稿：销售额增长 128（段 1）"
                systemOf(messages).contains("密度优化") -> "终稿：销售额增长 128（段 1）"
                else -> error("unexpected")
            }
        }
        val out = summarizeText(llm, "m", ds, maxWords = 800)
        assertEquals("map-reduce", out.route)
        assertEquals(chunkCount, out.chunkCount)
        assertEquals(chunkCount + 1 + TEXT_COD_ROUNDS, llm.calls.size, "块调用 + 终稿 + CoD×$TEXT_COD_ROUNDS")
        // 终稿之后一定还有 CoD 调用，且最终正文非空
        assertTrue(llm.calls.takeLast(TEXT_COD_ROUNDS).all { systemOf(it).contains("密度优化") })
        assertEquals("终稿：销售额增长 128（段 1）", out.summary)
        assertEquals(123 * llm.calls.size, tally(out))
        assertEquals(emptyList(), out.failedChunks)
    }

    @Test
    fun `层级路由——块数超过阈值先归并为节再成终稿`() {
        val ds = textDataset(longText(225_000)) // 需 >40 块
        val chunkCount = TextChunker.aggregateChunks(ds.text!!).size
        assertTrue(chunkCount > TEXT_HIERARCHICAL_THRESHOLD, "测试材料应产生超过 $TEXT_HIERARCHICAL_THRESHOLD 块，实际 $chunkCount")
        assertTrue(chunkCount <= TEXT_MAX_CHUNKS)
        val stages = mutableListOf<String>()
        val llm = RoutingLlm { messages, _ ->
            when {
                systemOf(messages).contains("文本分析员") -> jsonPoints(
                    Triple("要点：销售额增长 128", "销售额增长 128", 1),
                    keyNumbers = listOf("128"),
                )
                systemOf(messages).contains("撰写节总结") -> "第n节摘要：销售额增长 128"
                systemOf(messages).contains("撰写终稿总结") -> "层级终稿：销售额增长 128"
                systemOf(messages).contains("密度优化") -> "层级终稿：销售额增长 128"
                else -> error("unexpected")
            }
        }
        val out = summarizeText(llm, "m", ds, maxWords = 800, onStage = { stages += it })
        val sectionCalls = llm.calls.count { systemOf(it).contains("撰写节总结") }
        val expectedSections = (chunkCount + TEXT_HIERARCHICAL_GROUP - 1) / TEXT_HIERARCHICAL_GROUP
        assertEquals(expectedSections, sectionCalls, "层级归并：每组 $TEXT_HIERARCHICAL_GROUP 块归并为节")
        // 终稿调用应携带节摘要
        val finalCall = llm.calls.first { systemOf(it).contains("撰写终稿总结") }
        assertTrue(userOf(finalCall).contains("节摘要"), "终稿用户消息应携带节摘要")
        assertEquals("层级终稿：销售额增长 128", out.summary)
        assertTrue(stages.any { it.startsWith("层级归并") }, "进度应包含层级归并阶段")
    }

    // ---------- 零编造纪律 ----------

    @Test
    fun `白名单——归并提示词只含要点关键数字`() {
        val ds = textDataset(longText(36_000))
        val marker = "内部无关数字 7777"
        val contentWith7777 = ds.text!!.segments.mapIndexed { i, s ->
            if (i == ds.text!!.segments.lastIndex) s.copy(text = s.text + marker) else s
        }
        val ds2 = textDataset(contentWith7777)
        val llm = RoutingLlm { messages, _ ->
            when {
                systemOf(messages).contains("文本分析员") -> jsonPoints(
                    Triple("要点：销售额增长 128", "销售额增长 128", 1),
                    keyNumbers = listOf("128"),
                )
                systemOf(messages).contains("总结撰写人") -> "终稿"
                else -> "终稿"
            }
        }
        summarizeText(llm, "m", ds2, maxWords = 300)
        val reduce = llm.calls.first { systemOf(it).contains("总结撰写人") }
        val user = userOf(reduce)
        assertTrue(user.contains("<allowed_numbers>"))
        assertTrue(user.contains("128"))
        assertFalse(user.contains("7777"), "源文中的数字未进要点，不许出现在白名单与归并材料里")
    }

    @Test
    fun `空关键数字——允许清单退化为无数字提示`() {
        val ds = textDataset(longText(36_000))
        val llm = RoutingLlm { messages, _ ->
            when {
                systemOf(messages).contains("文本分析员") -> jsonPoints(
                    Triple("要点：均衡发展", "均衡发展", 1),
                    keyNumbers = emptyList(),
                )
                else -> "终稿正文"
            }
        }
        summarizeText(llm, "m", ds, maxWords = 300)
        val reduce = llm.calls.first { systemOf(it).contains("总结撰写人") }
        assertTrue(userOf(reduce).contains("（材料中没有关键数字"))
    }

    @Test
    fun `引用纪律——map 提示词要求 quote 逐字摘自原文且不超过五十字`() {
        val ds = textDataset(paragraphs(2, 50))
        var captured = ""
        val llm = RoutingLlm { messages, _ ->
            captured = systemOf(messages)
            jsonPoints(Triple("要点", "quote", 1), keyNumbers = emptyList())
        }
        summarizeText(llm, "m", ds, maxWords = 300)
        assertTrue(captured.contains("quote"))
        assertTrue(captured.contains("逐字摘自原文"))
        assertTrue(captured.contains("≤50 字"))
    }

    @Test
    fun `lost-in-the-middle——focus 非空时最相关置首、次相关置尾`() {
        val ds = textDataset(paragraphs(1, 100))
        val four = listOf(
            Triple("营收 增长 营收 增长 营收 增长 营收 增长", "营收 增长 营收 增长", 1),   // 最相关 A
            Triple("营收 增长 营收 增长 营收 增长", "营收 增长 营收", 1),                 // 次相关 B
            Triple("天气 晴朗 适合 出行", "天气 晴朗", 1),                                // 无关 C
            Triple("营收 增长 稳健", "营收 增长", 1),                                     // 相关 D
        )
        val llm = RoutingLlm { messages, _ ->
            when (systemOf(messages).contains("文本分析员")) {
                true -> jsonPoints(*four.toTypedArray(), keyNumbers = emptyList())
                else -> error("unexpected")
            }
        }
        val out = summarizeText(llm, "m", ds, focus = "营收 增长", maxWords = 300)
        assertEquals("营收 增长 营收 增长 营收 增长 营收 增长", out.points.first().text, "最相关置首")
        assertEquals("营收 增长 营收 增长 营收 增长", out.points.last().text, "次相关置尾")
        assertEquals(4, out.points.size, "要点不丢失")
    }

    // ---------- 失败容错 / 取消 / 续传 ----------

    @Test
    fun `失败容错——坏 JSON 块进 failedChunks 其余块正常不整体回落`() {
        val bad = "坏块标记"
        val ds = textDataset(longText(36_000).let { segs ->
            segs.mapIndexed { i, s -> if (i == 3) s.copy(text = s.text + bad) else s }
        })
        val llm = RoutingLlm { messages, _ ->
            when {
                userOf(messages).contains(bad) -> "###这不是 JSON"
                systemOf(messages).contains("文本分析员") -> jsonPoints(
                    Triple("要点：销售额增长 128", "销售额增长 128", 1),
                    keyNumbers = listOf("128"),
                )
                else -> "终稿：销售额增长 128"
            }
        }
        val out = summarizeText(llm, "m", ds, maxWords = 300)
        // 标记段可能因块间 overlap 出现在两个相邻块，两块都记失败——不整体回落即可
        assertTrue(out.failedChunks.isNotEmpty(), "坏块应进 failedChunks")
        assertTrue(out.failedChunks.size <= 2, "overlap 最多波及两个块：${out.failedChunks}")
        assertTrue(out.summary.isNotBlank())
        assertTrue(out.points.isNotEmpty())
    }

    @Test
    fun `失败容错——模型调用直接抛异常时该块记失败不抛出`() {
        val bad = "炸块标记"
        val ds = textDataset(longText(36_000).let { segs ->
            segs.mapIndexed { i, s -> if (i == 5) s.copy(text = s.text + bad) else s }
        })
        val llm = object : LlmClient {
            val calls = java.util.Collections.synchronizedList(mutableListOf<List<Pair<String, String>>>())
            private var n = 0
            override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int): ChatResult {
                calls.add(messages)
                if (messages.last().second.contains(bad)) throw RuntimeException("上游 500")
                val sys = messages.first { it.first == "system" }.second
                n++
                return if (sys.contains("文本分析员")) ChatResult(jsonPoints(Triple("要点：销售额增长 128", "销售额增长 128", 1), keyNumbers = listOf("128")), 100)
                else ChatResult("终稿：销售额增长 128", 100)
            }
        }
        val out = summarizeText(llm, "m", ds, maxWords = 300)
        // 标记段可能因块间 overlap 出现在两个相邻块，两块都记失败——不整体回落即可
        assertTrue(out.failedChunks.isNotEmpty(), "坏块应进 failedChunks")
        assertTrue(out.failedChunks.size <= 2, "overlap 最多波及两个块：${out.failedChunks}")
        assertTrue(out.summary.isNotBlank())
    }

    @Test
    fun `全部块失败——抛 IllegalStateException 不编造`() {
        val ds = textDataset(longText(36_000))
        val llm = RoutingLlm { _, _ -> "###永远不是 JSON" }
        val e = assertFailsWith<IllegalStateException> { summarizeText(llm, "m", ds, maxWords = 300) }
        assertTrue(e.message!!.contains("失败"))
    }

    @Test
    fun `取消——置位后不发起新的块调用`() {
        val ds = textDataset(longText(36_000))
        val llm = RoutingLlm { messages, _ ->
            when (systemOf(messages).contains("文本分析员")) {
                true -> jsonPoints(Triple("要点", "quote", 1), keyNumbers = emptyList())
                else -> "终稿"
            }
        }
        // T-129 有界扇出后的取消契约：置位后在途窗口（并发 3）内的调用收尾，不再有新提交
        assertFailsWith<GenerationCancelled> {
            summarizeText(llm, "m", ds, maxWords = 300, cancelSignal = { llm.calls.size >= 2 })
        }
        val analysisCalls = llm.calls.count { systemOf(it).contains("文本分析员") }
        assertTrue(analysisCalls in 2..3, "取消置位后块调用收敛在在途窗口内（实际 $analysisCalls）")
        assertTrue(llm.calls.none { systemOf(it).contains("总结撰写人") }, "取消后不许进入归并")
    }

    @Test
    fun `kv 续传——缓存命中的块不再发起模型调用且 token 计入历史账`() {
        val ds = textDataset(longText(36_000))
        val kv = MemStore()
        val recording = RoutingLlm { messages, _ ->
            when {
                systemOf(messages).contains("文本分析员") -> jsonPoints(
                    Triple("要点：销售额增长 128", "销售额增长 128", 1),
                    keyNumbers = listOf("128"),
                )
                else -> "终稿：销售额增长 128"
            }
        }
        val first = summarizeText(recording, "m", ds, maxWords = 300, kv = kv)
        assertTrue(kv.keys().size >= first.chunkCount, "每块一个缓存条目")
        // 第二次：所有 map 调用都返回垃圾——若缓存未命中必然整体失败
        val poison = RoutingLlm { messages, _ ->
            if (systemOf(messages).contains("文本分析员")) "###坏" else "终稿：销售额增长 128"
        }
        val second = summarizeText(poison, "m", ds, maxWords = 300, kv = kv)
        assertEquals(0, second.failedChunks.size, "全部命中缓存，不发起 map 调用")
        assertTrue(poison.calls.none { systemOf(it).contains("文本分析员") })
        assertTrue(second.totalTokens >= first.totalTokens - 123, "缓存 token 计入账本")
        assertEquals(first.points.map { it.text }, second.points.map { it.text }, "缓存要点原样恢复")
    }

    @Test
    fun `stuff 缺摘要兜底——有要点无摘要时补一次归并`() {
        val ds = textDataset(paragraphs(2, 100))
        val llm = RoutingLlm { messages, _ ->
            when {
                systemOf(messages).contains("文本分析员") -> jsonPoints(
                    Triple("要点：均衡发展", "均衡发展", 1),
                    keyNumbers = emptyList(),
                    summary = "",
                )
                systemOf(messages).contains("总结撰写人") -> "兜底终稿"
                else -> error("unexpected")
            }
        }
        val out = summarizeText(llm, "m", ds, maxWords = 300)
        assertEquals("兜底终稿", out.summary)
        assertEquals(2, llm.calls.size, "stuff 初次调用 + 一次兜底归并")
    }

    @Test
    fun `要点为空但调用成功——合法结果不误触硬门禁`() {
        // 块调用全部成功但材料没有可要点化的内容：门禁看的是「全部失败」，不是「要点为空」
        val ds = textDataset(longText(36_000))
        val llm = RoutingLlm { messages, _ ->
            when {
                systemOf(messages).contains("文本分析员") -> jsonPoints() // 空 points
                systemOf(messages).contains("总结撰写人") -> "终稿：材料平铺直叙，无关键要点"
                else -> "终稿：材料平铺直叙，无关键要点"
            }
        }
        val out = summarizeText(llm, "m", ds, maxWords = 300)
        assertEquals(emptyList(), out.failedChunks)
        assertEquals(emptyList(), out.points)
        assertEquals("终稿：材料平铺直叙，无关键要点", out.summary)
    }

    @Test
    fun `stuff 空要点空摘要——模型始终不出一行的零编造兜底抛错`() {
        val ds = textDataset(paragraphs(2, 60))
        val llm = RoutingLlm { messages, _ ->
            when (systemOf(messages).contains("文本分析员")) {
                true -> jsonPoints(summary = "") // 空 points + 空 summary 且无要点可兜底
                else -> error("allPoints 为空时不许发起兜底归并")
            }
        }
        val e = assertFailsWith<IllegalStateException> { summarizeText(llm, "m", ds, maxWords = 300) }
        assertTrue(e.message!!.contains("未返回"))
    }

    // ---------- 表格投影 / CoD ----------

    @Test
    fun `表格投影——投影整体不切断进入 map 管线且锚为段号`() {
        val projection = buildString {
            append("| 产品 | 销售额 |\n|---|---|\n")
            repeat(60) { append("| 产品$it | ${100 + it} |\n") }
        }
        // 正文 >30K 强制 map-reduce 路由——投影在此路由下独立成块、锚为段号
        val segs = paragraphs(48, 680) + TextSegment(0, projection, TextSegment.Kind.TABLE_PROJECTION)
        val ds = textDataset(segs)
        val projSeg = TextChunker.normalizeSegments(ds.text!!.segments).first { it.kind == TextSegment.Kind.TABLE_PROJECTION }
        val llm = RoutingLlm { messages, _ ->
            when {
                systemOf(messages).contains("文本分析员") ->
                    if (userOf(messages).contains("| 产品 |")) {
                        // 只有投影块产出关键数字——锚断言唯一化
                        jsonPoints(
                            Triple("表格：产品 59 销售额 159", "| 产品59 | 159 |", projSeg.index),
                            keyNumbers = listOf("159"),
                        )
                    } else {
                        jsonPoints(Triple("要点：均衡发展", "均衡发展", 1), keyNumbers = emptyList())
                    }
                else -> "终稿：表格包含产品 59 销售额 159"
            }
        }
        val out = summarizeText(llm, "m", ds, maxWords = 300)
        val projCalls = llm.calls.filter { userOf(it).contains("| 产品 |") }
        assertEquals(1, projCalls.size, "投影块独立成块，只出现在一个调用里")
        assertTrue(userOf(projCalls.single()).contains("| 产品59 | 159 |"), "投影未被切断，表尾完整")
        val projAnchor = out.numbersWithAnchors.single { it.number == "159" }
        assertEquals("段 ${projSeg.index}", projAnchor.anchor)
    }

    @Test
    fun `CoD——maxWords 不足 400 时不追加密度优化`() {
        val ds = textDataset(paragraphs(2, 60))
        val llm = RoutingLlm { messages, _ ->
            when (systemOf(messages).contains("文本分析员")) {
                true -> jsonPoints(Triple("要点", "quote", 1), keyNumbers = emptyList(), summary = "概述")
                else -> error("maxWords<400 不应出现其它调用")
            }
        }
        val out = summarizeText(llm, "m", ds, maxWords = 200)
        assertEquals(1, llm.calls.size)
        assertEquals("概述", out.summary)
    }

    @Test
    fun `CoD——模型返回空串时保留上一轮稿不许写没`() {
        val ds = textDataset(longText(36_000))
        val llm = RoutingLlm { messages, _ ->
            when {
                systemOf(messages).contains("文本分析员") -> jsonPoints(
                    Triple("要点：销售额增长 128", "销售额增长 128", 1),
                    keyNumbers = listOf("128"),
                )
                systemOf(messages).contains("总结撰写人") -> "终稿：销售额增长 128"
                systemOf(messages).contains("密度优化") -> ""
                else -> error("unexpected")
            }
        }
        val out = summarizeText(llm, "m", ds, maxWords = 800)
        assertEquals("终稿：销售额增长 128", out.summary, "CoD 空响应不得把终稿覆盖为空")
    }

    @Test
    fun `fence——模型把 JSON 包进代码围栏也能解析`() {
        val ds = textDataset(paragraphs(2, 60))
        val llm = RoutingLlm { messages, _ ->
            when (systemOf(messages).contains("文本分析员")) {
                true -> """```json
                    {"points":[{"text":"要点：均衡发展","quote":"均衡发展","segment":1}],"keyNumbers":[],"summary":"概述"}
                ```"""
                else -> error("unexpected")
            }
        }
        val out = summarizeText(llm, "m", ds, maxWords = 300)
        assertEquals("概述", out.summary)
        assertEquals(1, out.points.size)
    }

    @Test
    fun `materia——数据集没有文本部分时给中文错误`() {
        val ds = Dataset(
            name = "t.csv", importedAt = 1,
            columns = listOf("a"), rows = listOf(listOf("1")), columnTypes = emptyList(),
        )
        val llm = RoutingLlm { _, _ -> "不应被调用" }
        val e = assertFailsWith<IllegalStateException> { summarizeText(llm, "m", ds) }
        assertTrue(e.message!!.contains("文本部分"))
        assertEquals(0, llm.calls.size)
    }

    @Test
    fun `segment 字符串兼容——segment 以字符串 JSON 到达时也能解析`() {
        val parsed = parseChunkPoints("""{"points":[{"text":"p","quote":"q","segment":"3"}],"keyNumbers":["9"],"summary":"s","totalTokens":77}""")
        assertEquals(3, parsed.points.single().segmentIndex)
        assertEquals(77, parsed.totalTokens)
        assertEquals(listOf("9"), parsed.keyNumbers)
    }

    @Test
    fun `白名单块——allowedNumbersBlock 空与非空`() {
        assertTrue(allowedNumbersBlock(emptyList()).contains("没有关键数字"))
        val block = allowedNumbersBlock(listOf("128", "45.6%", "128"))
        assertTrue(block.contains("128"))
        assertTrue(block.contains("45.6%"))
        assertEquals(1, Regex("128").findAll(block).count(), "重复数字只列一次")
    }

    @Test
    fun `进度——onStage 按块推进且阶段顺序正确`() {
        val ds = textDataset(longText(36_000))
        val stages = mutableListOf<String>()
        val llm = RoutingLlm { messages, _ ->
            when {
                systemOf(messages).contains("文本分析员") -> jsonPoints(
                    Triple("要点：销售额增长 128", "销售额增长 128", 1),
                    keyNumbers = listOf("128"),
                )
                else -> "终稿：销售额增长 128"
            }
        }
        summarizeText(llm, "m", ds, maxWords = 300, onStage = { stages += it })
        val analysisStages = stages.filter { it.startsWith("分段要点") }
        assertTrue(analysisStages.isNotEmpty())
        assertEquals(analysisStages.size, analysisStages.zipWithNext().count { (a, b) ->
            (a.substringAfterLast("第 ").substringBefore("/").toInt() + 1) == b.substringAfterLast("第 ").substringBefore("/").toInt()
        } + 1, "块进度严格递增")
        assertTrue(stages.last().startsWith("归并要点") || stages.last().startsWith("密度优化"))
    }
}
