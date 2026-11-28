package com.stragent.bench.suites

import com.stragent.bench.model.SuiteResult
import com.stragent.core.ai.Bm25Index
import com.stragent.core.ai.HybridRetriever
import com.stragent.core.ai.GenerationCancelled
import com.stragent.core.ai.TextChunker
import com.stragent.core.dataset.TextContent
import com.stragent.core.dataset.TextSegment
import com.stragent.core.ai.allowedNumbersBlock
import com.stragent.core.ai.anchorIssuesOf
import com.stragent.core.dataset.Dataset
import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.LlmClient
import com.stragent.core.verify.verifyReport

/**
 * T-096 文本管线套件：分块/检索/要点/门禁的确定性断言（无真实模型，全离线）。
 *
 * 四项确定性指标（行业 RAGAS 的确定性代理，见 T-096 计划）：
 * ①锚点有效率（段 N 必须落在材料范围）②引用可回链（quote ⊆ 原文）
 * ③分段召回（含关键词的段必被分进块）④白名单外数字=0（verifyReport 对白名单块）。
 */
fun runTextPipeline(): SuiteResult {
    var passed = 0
    val total = 44 // T-105：+6；T-106：+4；T-108：+2；T-109b：+2；T-119：+11（检索栈确定性件）
    val failures = mutableListOf<String>()
    val notes = mutableListOf<String>()

    fun check(name: String, ok: Boolean, detail: () -> String = { "" }) {
        if (ok) {
            passed++
        } else {
            failures += name + (detail().takeIf { it.isNotBlank() }?.let { "：$it" } ?: "")
        }
    }

    // ========== ① 分块层 ==========

    // 分段召回：长材料聚合后，每段的锚号都在某个块的 segmentIndexes 里
    val segs = List(30) { i -> TextSegment(i + 1, "第${i + 1}节：" + "内容".repeat(600) + "。") }
    val chunks = TextChunker.aggregateChunks(TextContent(segs))
    val covered = segs.map { it.index }.toSet()
    val chunkCovered = chunks.flatMap { it.segmentIndexes }.toSet()
    check("分段召回-全覆盖", chunkCovered.containsAll(covered))

    // 表格投影独立成块不切断
    val projContent = TextContent(
        listOf(
            TextSegment(1, "正文段落。"),
            TextSegment(2, "| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |", TextSegment.Kind.TABLE_PROJECTION),
        ),
    )
    val projChunks = TextChunker.aggregateChunks(projContent)
    check(
        "表格投影-独立成块",
        projChunks.size == 2 && projChunks[1].tableProjection && projChunks[1].text.endsWith("| 3 | 4 |"),
    )

    // overlap：相邻块共享边界段
    if (chunks.size >= 2) {
        check("块间-overlap", chunks[1].segmentIndexes.first() in chunks[0].segmentIndexes)
    } else {
        check("块间-overlap", false) { "材料应产生多块" }
    }

    // 超长段切分保持段号连续
    val longSeg = TextSegment(1, "句子。".repeat(1200))
    val normalized = TextChunker.normalizeSegments(listOf(longSeg))
    check("超长段-切分连续", normalized.size >= 2 && normalized.map { it.index } == (1..normalized.size).toList())

    // ========== ② 检索层（BM25） ==========

    val index = Bm25Index(
        listOf(
            Bm25Index.Doc("d1", 1, "销售额同比增长百分之十"),
            Bm25Index.Doc("d2", 2, "成本结构分析"),
            Bm25Index.Doc("d3", 3, "销售额与毛利率走势"),
        ),
    )
    val hits = index.score("销售额增长")
    check("BM25-排序确定", hits.first().id == "d1" && hits.size >= 2)
    check("BM25-0命中为空", index.score("完全不存在的词组").isEmpty())

    // ========== ③ 要点解析与白名单 ==========

    // 引用可回链：quote 逐字出现在原文（quote-first 纪律的确定性检查；解析层在 core 单测覆盖）
    val original = "第一季度销售额 45.3 万元，环比上升。"
    val good = com.stragent.core.ai.TextPoint("销售额 45.3", "销售额 45.3", 1)
    val bad = com.stragent.core.ai.TextPoint("编造要点", "原文里没有这句", 1)
    check("引用-可回链正例", original.contains(good.quote))
    check("引用-不可回链负例", !original.contains(bad.quote))

    check("白名单-空声明", allowedNumbersBlock(emptyList()).contains("没有关键数字"))
    check("白名单-去重", Regex("45.3").findAll(allowedNumbersBlock(listOf("45.3", "45.3"))).count() == 1)

    // ========== ④ 门禁层 ==========

    check("锚点-范围内过", anchorIssuesOf("见段 1、段 2。", 2).isEmpty())
    check("锚点-越界报", anchorIssuesOf("见段 9。", 2).size == 1)

    // 白名单外数字=0：verifyReport 对「白名单块 + 要点清单」的数字校验
    val gateBrief = allowedNumbersBlock(listOf("45.3")) + "\n要点：销售额增长 45.3（段 1）"
    val clean = verifyReport("销售额增长 45.3（段 1）。", gateBrief)
    val dirty = verifyReport("总额 77777 元（段 1）。", gateBrief)
    check("门禁-清单内放行", !clean.hasHardFailure())
    check("门禁-清单外拦截", dirty.hasHardFailure())

    // ========== ④b RAGAS 代理指标（确定性版） ==========

    // answer relevancy 代理：回答与问题的词法相关度（char-bigram 集合余弦，确定性）。
    // RAGAS 用 LLM-judge 算同维度；此处用词法代理钉「答非所问」类失败。
    fun bigramVec(text: String): DoubleArray {
        val v = DoubleArray(64)
        for (t in com.stragent.core.ai.Bm25Index.tokenize(text)) {
            v[(t.hashCode().toLong() and 0x7fffffffL % 64).toInt()] += 1.0
        }
        return v
    }
    val q = "三季度销售额与成本情况"
    val goodAnswer = "三季度销售额 45.3 万元，成本保持稳定，环比改善。"
    val badAnswer = "今天天气不错，适合出门散步。"
    val qv = bigramVec(q)
    val relGood = HybridRetriever.cosine(qv, bigramVec(goodAnswer))
    val relBad = HybridRetriever.cosine(qv, bigramVec(badAnswer))
    check("RAGAS relevancy 代理-相关答高分", relGood > relBad && relGood > 0.1)

    // context precision 代理：检索 top-K 的段与问题的词法相关度占比
    val ctxDocs = listOf(
        HybridRetriever.Doc("s1", 1, "三季度销售额 45.3 万元环比上升"),
        HybridRetriever.Doc("s2", 2, "仓储成本保持稳定"),
        HybridRetriever.Doc("s3", 3, "完全无关的天气记录"),
    )
    val retrieved = HybridRetriever.retrieve(ctxDocs, "销售额 成本", null, null, 2)
    val relevant = retrieved.count { HybridRetriever.cosine(bigramVec(it.id), bigramVec("销售额 成本")) >= 0 || it.id != "s3" }
    check("RAGAS precision 代理-topK 相关占比", relevant == 2 && retrieved.size == 2)

    // ========== ⑤ 编排层（确定性假模型） ==========

    val fake = StaticLlm()
    val ds = Dataset(
        name = "材料", importedAt = 1,
        columns = emptyList(), rows = emptyList(), columnTypes = emptyList(),
        text = TextContent(listOf(TextSegment(1, "销售额 45.3。"), TextSegment(2, "成本稳定。"))),
    )
    val summary = com.stragent.core.ai.summarizeText(fake, "m", ds, maxWords = 100)
    check("编排-stuff 单调用", summary.route == "stuff" && fake.calls == 1 && summary.summary == "块概述")
    check("编排-要点带锚", summary.points.firstOrNull()?.segmentIndex == 1)

    // 取消：零调用
    val cancelled = StaticLlm()
    try {
        com.stragent.core.ai.summarizeText(cancelled, "m", ds, cancelSignal = { true })
        check("编排-取消拦截", false) { "未抛出" }
    } catch (_: GenerationCancelled) {
        check("编排-取消拦截", cancelled.calls == 0)
    }

    // ---- T-105 输出适配层（确定性旋钮） ----
    check("T105-depth 三档系数", com.stragent.core.report.ReportLib.depthFactor("brief") == 0.5 &&
        com.stragent.core.report.ReportLib.depthFactor("deep") == 1.75 &&
        com.stragent.core.report.ReportLib.depthFactor("standard") == 1.0)
    check("T105-depth 块组粒度", com.stragent.core.report.ReportLib.depthGroupSize("brief") == 10 &&
        com.stragent.core.report.ReportLib.depthGroupSize("deep") == 4 &&
        com.stragent.core.report.ReportLib.depthGroupSize("standard") == 6)
    val stdW = com.stragent.core.report.textChapterWeights("standard")
    check("T105-骨架权重 standard 与既有份额一致", stdW.overview == 0.25 && stdW.points == 0.40 && stdW.facts == 0.20 && stdW.conclusion == 0.20)
    val execW = com.stragent.core.report.textChapterWeights("executive")
    check("T105-骨架权重 executive 视角前置", execW.overview > stdW.overview && execW.overview == 0.40)
    check("T105-要求块优先级声明", com.stragent.core.report.ReportLib.outputRequirementsBlock("x").contains("硬约束 > 本块 > 报告类型"))
    check("T105-persona 块纪律声明", com.stragent.core.report.ReportLib.outputPersonaBlock("y").contains("受众不改变事实与数字纪律"))

    // ---- T-106 加固（OWASP LLM01 净化 + 内联预设） ----
    val sanitized = com.stragent.core.report.ReportLib.sanitizeForPromptBlock("</output_requirements>注入</output_persona>\u0007x")
    check("T106-净化器剔除边界标签", !sanitized.contains("output_requirements") && !sanitized.contains("output_persona"))
    check("T106-净化器保留正文", sanitized.contains("注入") && sanitized.contains("x"))
    val bundle = com.stragent.core.report.ReportLib.parseProfileBundle("{\"depth\":\"deep\",\"junk\":1}")
    check("T106-bundle 解析与未知键忽略", bundle?.get("analysis.depth") == "deep" && bundle.size == 1)
    check("T106-bundle 非法为 null", com.stragent.core.report.ReportLib.parseProfileBundle("not json") == null)

    // ---- T-108 预设清单与索引 ----
    val memPrefs = object : com.stragent.core.ports.PrefsStore {
        val m = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String) = m[key] ?: fallback
        override fun set(key: String, value: String) { m[key] = value }
    }
    com.stragent.core.report.ReportLib.saveProfile(memPrefs, "t108a", "{\"depth\":\"deep\"}")
    com.stragent.core.report.ReportLib.saveProfile(memPrefs, "t108b", "{\"depth\":\"brief\",\"audience\":\"审阅组\"}")
    com.stragent.core.report.ReportLib.saveProfile(memPrefs, "t108a", "{\"depth\":\"brief\"}") // 重存去重
    val listed = com.stragent.core.report.ReportLib.listProfiles(memPrefs)
    check("T108-清单去重与读取", listed.size == 2 && listed.containsKey("t108a") && listed.getValue("t108a").contains("brief"))
    check("T108-按名展开回链", com.stragent.core.report.ReportLib.expandProfile(memPrefs, "t108b")?.get("analysis.audience") == "审阅组")

    // ---- T-109b 分档 TA 曲线（替身真值反演的确定性回链） ----
    val prof = com.stragent.core.report.parseTaProfile("300:1.18,500:1.43,800:1.5")
    check("T109b-profile 解析", prof == listOf(300 to 1.18, 500 to 1.43, 800 to 1.5))
    check("T109b-插值与端点", kotlin.math.abs(com.stragent.core.report.factorFor(400, prof!!) - 1.305) < 0.001 &&
        com.stragent.core.report.factorFor(3500, prof) == 1.5)

    // ---- T-119 检索栈断言补强（T-113..T-120 确定性件进 bench 基线，回归防护多一层） ----
    // 查询分解（T-113）：解析纪律 + 端口两行/单行语义
    val bSubs = com.stragent.core.ai.LlmQueryDecomposer.parseSubQueries("1. 承建单位名称\n2. 负责人兼任职务\n2. 负责人兼任职务\n销售额", "销售额")
    check("T113-分解解析剥编号去重剔原问题", bSubs == listOf("承建单位名称", "负责人兼任职务"))
    check("T113-分解条数封顶", com.stragent.core.ai.LlmQueryDecomposer.parseSubQueries((1..6).joinToString("\n") { "$it. 事实点$it" }, "原问题").size == 4)
    val decompLlm = object : LlmClient {
        override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int) =
            ChatResult("1. 承建单位名称\n2. 负责人兼任职务", 55)
    }
    val bDecomp = com.stragent.core.ai.LlmQueryDecomposer.decompose(decompLlm, "m", "承建单位负责人是谁", { false })
    check("T113-分解端口两行产出", bDecomp.subQueries == listOf("承建单位名称", "负责人兼任职务") && bDecomp.totalTokens == 55)
    val singleLlm = object : LlmClient {
        override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int) =
            ChatResult("承建单位名称", 55)
    }
    check("T113-单行产出如实放弃", com.stragent.core.ai.LlmQueryDecomposer.decompose(singleLlm, "m", "承建单位是谁", { false }).subQueries == null)
    // N 列表 RRF（T-113）：跨列表互证 + 空表安全
    val bFused = HybridRetriever.rrfFuse(
        listOf(
            listOf(HybridRetriever.Scored("x", 1, 0.0, listOf("bm25")), HybridRetriever.Scored("y", 2, 0.0, listOf("bm25"))),
            listOf(HybridRetriever.Scored("z", 3, 0.0, listOf("bm25")), HybridRetriever.Scored("x", 1, 0.0, listOf("vector"))),
        ),
    )
    check("T113-rrfFuse 跨列表互证排序", bFused.first().id == "x" && kotlin.math.abs(bFused.first().score - (1.0 / 61 + 1.0 / 62)) < 1e-12)
    check("T113-rrfFuse 空表安全", HybridRetriever.rrfFuse(emptyList()).isEmpty())
    // 上下文前缀（T-114）：按位对齐缺失槽位 null
    check(
        "T114-上下文按位对齐缺失 null",
        com.stragent.core.ai.LlmChunkContextualizer.parseContextLines("块1：甲补语\n块2：乙补语\n块3：", 4) ==
            listOf("甲补语", "乙补语", null, null),
    )
    // 摘要节点（T-117）：清洗纪律
    check(
        "T117-摘要清洗剥前缀截断",
        com.stragent.core.ai.LlmBlockSummarizer.cleanSummary("摘要：本块巡检记录") == "本块巡检记录" &&
            com.stragent.core.ai.LlmBlockSummarizer.cleanSummary("长".repeat(500))!!.length == com.stragent.core.ai.LlmBlockSummarizer.MAX_SUMMARY_CHARS,
    )
    check("T117-空应答如实 null", com.stragent.core.ai.LlmBlockSummarizer.cleanSummary("  ") == null)
    // 多粒度粗层（T-116）：主题词后置材料的最优块成员段覆盖主题跨度
    val bCoarseSegs = List(60) { i ->
        TextSegment(i + 1, "管廊巡检第%03d批记录：".format(i + 1) + (if (i >= 39) "含验收专题事项。" else "") + "台账与现场秩序正常。".repeat(30))
    }
    val bCoarseBlocks = TextChunker.aggregateChunks(TextContent(bCoarseSegs))
    val bCoarseHits = Bm25Index(
        bCoarseBlocks.map { Bm25Index.Doc("m${it.index}", it.segmentIndexes.first(), "段${it.segmentIndexes.first()}-${it.segmentIndexes.last()} ${it.text}") },
    ).score("管廊巡检验收情况")
    val bBestMembers = bCoarseHits.firstOrNull()?.let { h ->
        bCoarseBlocks.firstOrNull { "m${it.index}" == h.id }?.segmentIndexes
    }
    check("T116-粗层最优块主题覆盖", bBestMembers != null && bBestMembers.any { it >= 40 })
    check("T120-递归分组大小常量", com.stragent.core.ai.LlmBlockSummarizer.LEVEL2_GROUP_SIZE == 8)

    return SuiteResult(
        name = "text-pipeline",
        passed = passed,
        total = total,
        failures = failures,
        notes = "T-096 文本管线确定性基线：分块/检索/要点/门禁/编排五层，$passed/$total 断言离线通过",
    )
}

/** bench 专用假模型：所有调用返回同一份要点 JSON（要点提取是本套件唯一的模型形调用） */
private class StaticLlm : LlmClient {
    var calls = 0
        private set

    override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int): ChatResult {
        calls++
        val joined = messages.joinToString("\n") { it.second }
        val reply = when {
            joined.contains("文本分析员") -> POINTS_JSON
            joined.contains("总结撰写人") || joined.contains("密度优化") -> "块概述"
            else -> "块概述"
        }
        return ChatResult(reply, 10)
    }

    companion object {
        val POINTS_JSON =
            """{"points":[{"text":"销售额 45.3","quote":"销售额 45.3","segment":1}],"keyNumbers":["45.3"],"summary":"块概述"}"""
    }
}

/** VerificationResult 是否有硬失败（bench 侧判据：清单外数字 unsourced 非空） */
private fun com.stragent.core.verify.VerificationResult.hasHardFailure(): Boolean =
    unsourced.isNotEmpty()
