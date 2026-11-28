package com.stragent.core.ai

import com.stragent.core.config.Config
import com.stragent.core.dataset.Dataset
import com.stragent.core.dataset.TextContent
import com.stragent.core.memory.MemoryFact
import com.stragent.core.memory.memoryBlock
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.PrefsStore
import com.stragent.core.report.ReportLib
import com.stragent.core.report.WritingAudit
import com.stragent.core.report.humanWritingBlock
import com.stragent.core.report.reviewReport
import com.stragent.core.report.buildAllowedNumbers
import com.stragent.core.semantic.SemanticTerm
import com.stragent.core.semantic.semanticBlock
import com.stragent.core.verify.verifyReport

/**
 * 综述报告（T-096）：text/mixed 数据集的报告生成分支——要点 map（与 TextSummarizer 同源）+
 * 自适应篇幅 + 程序定章节骨架 + 逐章生成 + claim 级门禁。
 *
 * 材料三态：纯文本（rows 空）→ 只用要点池；混合（rows 非空）→ 要点池 + 表格 brief 双轨，
 * 数字白名单自动合并（verifyReport 对门禁 brief 全文取数——表格与正文数字一并放行）；
 * 纯表格不进本函数（generateInsight 入口分流）。
 *
 * 自适应篇幅（短材料不注水）：材料 <2K 字 → 450 字综述；2K–30K → 1000 字；>30K → 2000 字
 * 分章。章节骨架**程序定义**（概述 / 内容要点（按块组）/ 关键事实与数字 / 结论与疑点），
 * 模型只填内容——与经典分章同一纪律。
 *
 * 零编造（claim 级，行业 quote-first + 数字回链纪律）：
 * - 提示词侧：白名单块 + 每个关键结论标注「段 N」锚；
 * - 门禁侧：verifyReport（数字对白名单）+ reviewReport（无依据结论/矛盾）+ 锚点有效性
 *   （引用段号必须落在材料范围）+ WritingAudit（套话/篇幅）；硬问题 → 修订 ≤2 轮，
 *   仍不过 → gateFailed=true 恒输出（T-044 纪律，不丢稿不静默）。
 */
internal fun generateTextReview(
    llm: LlmClient,
    prefs: PrefsStore,
    model: String,
    reviewModel: String,
    dataset: Dataset,
    onStage: ((String, ChapterProgress?) -> Unit)?,
    cancelSignal: () -> Boolean,
    onBody: ((BodyDelta) -> Unit)?,
    memory: List<MemoryFact>,
    semantic: List<SemanticTerm>,
): InsightResult {
    if (cancelSignal()) throw GenerationCancelled()
    val content = dataset.text
        ?: throw IllegalStateException("综述分支要求 dataset.text 非空（纯表格请走经典管线）") // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
    val segmentCount = content.segments.size

    // ---- 自适应篇幅（短材料不注水；长材料分章） ----
    val materialChars = content.totalChars + (if (dataset.rows.isNotEmpty()) 2_000 else 0)
    val baseChars = when {
        materialChars < 2_000 -> 450
        materialChars <= 30_000 -> 1_000
        else -> 2_000
    }
    // T-105：depth 篇幅轴（Tableau verbosity 先例）；standard 系数 1.0 = 逐字节兼容
    val depth = ReportLib.getDepth(prefs)
    val targetChars = (baseChars * ReportLib.depthFactor(depth)).toInt().coerceAtLeast(300)
    val audience = ReportLib.getAudience(prefs)
    val requirements = ReportLib.getRequirements(prefs)
    // audience/requirements 合成视角：非空时 BM25 要点重排生效（STORM 视角驱动先例）
    val viewFocus = listOfNotNull(
        audience.takeIf { it.isNotBlank() },
        requirements.takeIf { it.isNotBlank() },
    ).joinToString("；").ifBlank { null } // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
    val outputHints = buildString {
        if (audience.isNotBlank()) append(ReportLib.outputPersonaBlock(audience))
        if (requirements.isNotBlank()) append(ReportLib.outputRequirementsBlock(requirements))
    }

    // ---- 要点 map（与 TextSummarizer 同源；含表格投影块） ----
    val normalized = TextChunker.normalizeSegments(content.segments)
    val chunks = TextChunker.aggregateChunks(TextContent(normalized))
    val mapInputs = if (content.totalChars <= TEXT_STUFF_THRESHOLD) {
        listOf(
            TextChunk(0, normalized.joinToString("\n\n") { "${it.index}. ${it.text}" }, normalized.map { it.index }, false),
        )
    } else {
        chunks
    }
    onStage?.invoke("要点提取", null) // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
    val mapped = mapChunkPoints(llm, model, mapInputs, content, focus = viewFocus, kv = null, cancelSignal, onStage?.let { cb -> { s: String -> cb(s, null) } }, outputHints)
    val points = mapInputs.flatMap { c -> mapped.pointsByChunk[c.index]?.points ?: emptyList() }
    val keyNumbers = mapInputs.flatMap { c -> mapped.pointsByChunk[c.index]?.keyNumbers ?: emptyList() }.distinct()
    val failedChunks = mapped.failedChunks

    // ---- 混合材料：表格 brief 双轨并入（数字白名单由门禁 brief 全文自动承载） ----
    val tableBrief = if (dataset.rows.isNotEmpty()) com.stragent.core.brief.buildDatasetBrief(dataset, prefs) else ""

    // ---- 门禁 brief：要点（含段锚与关键数字）+ 表格摘要——数字/锚校验的唯一事实源 ----
    val gateBrief = buildString {
        append("<allowed_numbers>")
        append(allowedNumbersBlock(keyNumbers))
        append("</allowed_numbers>\n\n")
        append("要点清单（quote 逐字摘自原文）：\n") // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
        points.forEachIndexed { i, p ->
            append(i + 1).append(". ").append(p.text).append("（quote：").append(p.quote)
                .append("；段 ").append(p.segmentIndex).append("）\n") // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
        }
        if (failedChunks.isNotEmpty()) {
            append("\n（以下块要点提取失败，材料不完整：").append(failedChunks).append("）\n") // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
        }
        if (tableBrief.isNotEmpty()) append("\n表格统计摘要：\n").append(tableBrief) // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
    }

    // ---- 生成：小材料单发成文；大材料程序定章节逐章生成 ----
    val system = buildString {
        append("<role>你是资深数据分析师，正在为一份「材料」（正文段落") // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
        if (dataset.rows.isNotEmpty()) append(" + 数据表格") // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
        append("）撰写综述报告。</role>\n<task>只基于给定要点与表格摘要撰写；每个关键结论标注出处（段 N）。</task>\n") // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
        append(ReportLib.industryPromptBlock(ReportLib.getIndustry(prefs)))
        append(ReportLib.reportTypePromptBlock(ReportLib.getReportType(prefs)))
        append(ReportLib.stylePromptBlock(ReportLib.getReportStyle(prefs)))
        if (audience.isNotBlank()) append(ReportLib.outputPersonaBlock(audience)) // T-105
        if (requirements.isNotBlank()) append(ReportLib.outputRequirementsBlock(requirements)) // T-105
        append(humanWritingBlock())
        append("\n<zero_fabrication>①数字只许来自 <allowed_numbers> 与表格摘要——清单外一个都不许出现；") // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
        append("②要点没有覆盖的内容不要写；③引用段号必须在材料范围内；④材料缺口如实写进「未答问题」，不许推断补齐；") // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
        append("⑤关键定性结论（谁最高/谁增长最快/主要来源）注明依据（依据分组聚合、或「段 N」锚点）——数据摘要里没有的主体不许下判断。</zero_fabrication>") // lint:allow zh-first（用户面中文输出/提示词，T-095/T-192 语言定位）
        val memoryPrompt = memoryBlock(memory)?.let { "\n$it" } ?: ""
        val semanticPrompt = semanticBlock(semantic)?.let { "\n$it" } ?: ""
        append(memoryPrompt)
        append(semanticPrompt)
    }

    var tokens = mapped.totalTokens
    val reportText: String
    val chapterList: List<Pair<String, Int>>?
    if (targetChars <= 600) {
        // ---- 单发成文（短材料） ----
        onStage?.invoke("撰写综述", ChapterProgress(1, 1, "综述")) // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
        val user = buildString {
            append(gateBrief)
            append("\n\n字数上限：约 ").append(targetChars).append(" 字。直接输出报告正文。") // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
        }
        val throttle = onBody?.let { BodyThrottle(1, 1, "综述", 1, it) } // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
        val result = try {
            chatStep(
                llm, model, listOf("system" to system, "user" to user),
                ChatOptions(temperature = 0.7, maxTokens = (targetChars * 3).coerceAtLeast(1200), targetChars = targetChars),
                onToken = throttle?.let { t -> { token: String -> t.onToken(token) } },
                cancelSignal = cancelSignal,
            )
        } finally {
            throttle?.flush()
        }
        tokens += result.totalTokens
        reportText = result.content
        chapterList = null
    } else {
        // ---- 程序定章节：概述 / 内容要点（按块组）/ 关键事实与数字 / 结论与疑点 ----
        val sections = mutableListOf<Triple<String, String, Int>>()  // lint:allow zh-first（注释中文规则盲区）
        // T-105：骨架权重表（STORM 视角驱动先例）+ depth 块组粒度 + TA 目标上调
        //（T-109b：分档 profile 优先（STRAGENT_LENGTH_TA_PROFILE="300:1.18,..."），
        //  否则全局因子 STRAGENT_LENGTH_TA，缺省全关——解析在 TaCalibration）
        val w = com.stragent.core.report.textChapterWeights(ReportLib.getReportType(prefs).id)
        val taProfile = com.stragent.core.report.parseTaProfile(Config.get("STRAGENT_LENGTH_TA_PROFILE"))
        val ta = taProfile?.let { { c: Int -> com.stragent.core.report.factorFor(c, it) } }
            ?: com.stragent.core.report.parseTaFactor(Config.get("STRAGENT_LENGTH_TA"))?.let { f -> { _: Int -> f } }
            ?: { _: Int -> 1.0 }
        val overviewChars = (targetChars * w.overview).toInt()
        sections += Triple("概述", gateBrief, (overviewChars * ta(overviewChars)).toInt()) // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
        val groupSize = ReportLib.depthGroupSize(depth)
        val chunkGroups = mapInputs.chunked(groupSize)
        for ((gi, g) in chunkGroups.withIndex()) {
            val segs = g.flatMap { it.segmentIndexes }
            val pts = g.flatMap { c -> mapped.pointsByChunk[c.index]?.points ?: emptyList() }
            val user = buildString {
                append("本章要点：\n") // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
                pts.forEachIndexed { i, p ->
                    append(i + 1).append(". ").append(p.text).append("（quote：").append(p.quote)
                        .append("；段 ").append(p.segmentIndex).append("）\n") // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
                }
                append("<allowed_numbers>").append(allowedNumbersBlock(g.flatMap { c -> mapped.pointsByChunk[c.index]?.keyNumbers ?: emptyList() }))
                append("</allowed_numbers>")
                append("\n\n本章只写「内容要点（段 ").append(segs.first()).append("-").append(segs.last()).append("）」部分。") // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
            }
            val ptsChars = (targetChars * w.points / chunkGroups.size).toInt()
            sections += Triple("内容要点（段 ${segs.first()}-${segs.last()}）", user, (ptsChars * ta(ptsChars)).toInt()) // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
        }
        if (keyNumbers.isNotEmpty() || tableBrief.isNotEmpty()) {
            val user = buildString {
                append(gateBrief)
                append("\n\n本章只写「关键事实与数字」部分：汇总材料中的关键数字与事实，") // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
                append("每个数字标注出处（段 N 或表格）。") // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
            }
            val factsChars = (targetChars * w.facts).toInt()
            sections += Triple("关键事实与数字", user, (factsChars * ta(factsChars)).toInt()) // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
        }
        sections += Triple(
            "结论与疑点", // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
            gateBrief + "\n\n本章只写「结论与疑点」部分；材料缺口（要点失败块：$failedChunks）如实写进「未答问题」。", // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
            ((targetChars * w.conclusion).toInt().let { (it * ta(it)).toInt() }),
        )

        val parts = mutableListOf<String>()
        var ci = 0
        for ((title, user, chars) in sections) {
            ci++
            if (cancelSignal()) throw GenerationCancelled()
            onStage?.invoke("撰写", ChapterProgress(ci, sections.size, title)) // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
            val msgs = listOf(
                "system" to system,
                "user" to (user + "\n\n本章字数上限：约 ").let { it + chars + " 字。输出本章正文（带「## $title」标题）。" }, // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
            )
            val throttle = onBody?.let { BodyThrottle(ci, sections.size, title, 1, it) }
            val result = try {
                chatStep(
                    llm, model, msgs,
                    ChatOptions(temperature = 0.7, maxTokens = (chars * 3).coerceAtLeast(900), targetChars = chars),
                    onToken = throttle?.let { t -> { token: String -> t.onToken(token) } },
                    cancelSignal = cancelSignal,
                )
            } finally {
                throttle?.flush()
            }
            tokens += result.totalTokens
            parts += result.content
        }
        reportText = parts.joinToString("\n\n")
        chapterList = sections.map { it.first to it.third }
    }

    // ---- claim 级门禁：数字回链 + 引用锚点有效 + 写作审计；硬问题修订 ≤2 轮 ----
    onStage?.invoke("发稿门禁", null) // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
    // T-105 长度依从（IFEval 式可验证约束）：仅显式适配（depth≠standard 或 requirements 非空）时强制
    val enforceLength = depth != "standard" || requirements.isNotBlank()
    var current = reportText
    var revisions = 0
    var gateIssues: List<String> = emptyList()
    var gateFailed = false
    val auditNotes = mutableListOf<String>()
    var hardTotal = 0
    while (true) {
        val anchorIssues = anchorIssuesOf(current, segmentCount)
        val gate = com.stragent.core.report.assessAnswer(current, gateBrief)
        val audit = WritingAudit.audit(current, targetChars, structured = false)
        // T-105 长度依从：仅显式适配时把写作审计的篇幅偏离升级为硬问题（IFEval 式可验证约束；
        // 修订回灌自带「实际 N 字 vs 目标 M 字」——审计 issue 文案已含，无需另造）
        val lenHard = enforceLength && audit.issues.any { it.kind == "篇幅" } // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
        val issues = buildList {
            addAll(gate.issues.map { it.line })
            addAll(anchorIssues)
            audit.issues.forEach { add("写作审计：${it.detail}") } // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
        }
        hardTotal = gate.hardIssues + anchorIssues.size + (if (lenHard) 1 else 0) + (if (audit.hard) 1 else 0)
        auditNotes.clear()
        audit.issues.forEach { auditNotes.add("写作审计：${it.detail}") } // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
        gateIssues = issues
        if (hardTotal == 0 || revisions >= 2) {
            gateFailed = hardTotal > 0
            break
        }
        // 修订：把问题清单回灌重写
        if (cancelSignal()) throw GenerationCancelled()
        revisions++
        onStage?.invoke("门禁修订", ChapterProgress(revisions, 2, "修订")) // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
        val reviseMsgs = listOf(
            "system" to "你是严谨的报告修订员。按问题清单修订报告：清单外数字删除或改为出处内的数字；" + // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
                "无效段号锚修正或删除；无依据结论删除。保持结构与风格，只输出修订后的报告正文。", // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
            "user" to "报告：\n$current\n\n问题清单：\n${issues.joinToString("\n") { "- $it" }}\n\n字数上限：约 $targetChars 字。", // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
        )
        val revised = chatStep(llm, model, reviseMsgs, ChatOptions(temperature = 0.5, maxTokens = (targetChars * 3).coerceAtLeast(1200)))
        tokens += revised.totalTokens
        if (revised.content.isBlank()) break // 修订失败：保留现稿带标记交稿（不丢稿） // lint:allow zh-first（注释中文规则盲区）
        current = revised.content
    }

    // ---- 语义审查（与经典同口径：失败显式进 qualityNotes，不阻断） ----
    val review = try {
        semanticReview(llm, prefs, semanticSample(current), gateBrief, reviewModel)
    } catch (_: Exception) {
        SemanticReviewOutcome(failure = "审查入口异常，未完成") // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
    }
    val semanticNotes = review.failure?.let {
        listOf("语义审查未完成（$it）：本稿仅通过确定性门禁，语义层未审") // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
    }.orEmpty()

    // ---- 忠实度二审（T-102，opt-in：env STRAGENT_FAITHFULNESS_JUDGE=1）----
    // 确定性门禁之外的语义层第二意见；失败显式进 qualityNotes（T-062），不阻断。
    var judgeTokens = 0
    val judgeNotes = if (com.stragent.core.report.FaithfulnessJudge.enabled && !cancelSignal()) {
        val outcome = com.stragent.core.report.FaithfulnessJudge.judge(
            llm, current, gateBrief, reviewModel, cancelSignal,
        )
        judgeTokens = outcome.totalTokens
        outcome.failure?.let { listOf("忠实度二审未完成（$it）：本稿仅过确定性门禁") } // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
            ?: outcome.issues
    } else {
        emptyList()
    }

    return InsightResult(
        report = current,
        totalTokens = tokens + judgeTokens,
        usedWeb = false,
        targetChars = targetChars,
        actualChars = countReportChars(current),
        chapters = chapterList,
        qualityNotes = auditNotes + semanticNotes + judgeNotes,
        gateFailed = gateFailed,
        gateIssues = gateIssues,
        gateRevisions = revisions,
        semantic = review.issues,
    )
}

/** 引用锚点有效性：报告中「段 N」必须落在 1..segmentCount（越界 = 硬问题） */
fun anchorIssuesOf(report: String, segmentCount: Int): List<String> {
    if (segmentCount <= 0) return emptyList()
    val out = mutableListOf<String>()
    for (m in Regex("""段\s*(\d+)""").findAll(report)) { // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
        val n = m.groupValues[1].toIntOrNull() ?: continue
        if (n < 1 || n > segmentCount) {
            out += "引用锚点「段 $n」超出材料范围（共 $segmentCount 段）" // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
        }
    }
    return out.distinct()
}

/** 与经典口径同源的成文字数（去 markdown 符号与空白） */
private fun countReportChars(report: String): Int =
    report.replace(Regex("[#*`>|-]"), "").replace(Regex("\\s"), "").length
