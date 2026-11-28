package com.stragent.core.ai

import com.stragent.core.dataset.Dataset
import com.stragent.core.config.Config
import com.stragent.core.dataset.TextContent
import com.stragent.core.ports.DatasetStore
import com.stragent.core.ports.LlmClient
import java.security.MessageDigest

/** 单条要点：quote-first 纪律——要点必须携带原文短引用（≤50 字），下游只许改写不许引入新主张 */
data class TextPoint(
    val text: String,
    val quote: String,
    val segmentIndex: Int,
)

/** 数字白名单条目（claim 级回链）：number=原样摘录的数字串，anchor=「段 N」或「段 N-M」 */
data class TextNumberAnchor(val number: String, val anchor: String)

/**
 * 文本总结结果。恒输出纪律（T-032 同源）：空列表也是事实（「没这路信息」≠「字段缺失」）。
 * [totalTokens] 口径：本次与历史（kv 缓存命中）累计的模型调用 token 数——缓存续传
 * 不重复计费，也不把已花的账抹掉；只统计 map/归并/CoD 的 chatStep 调用。
 */
data class TextSummary(
    val summary: String,
    val points: List<TextPoint>,
    val numbersWithAnchors: List<TextNumberAnchor>,
    val failedChunks: List<Int>,
    val route: String,
    val chunkCount: Int,
    val durationMs: Long,
    val totalTokens: Int,
)

/**
 * 长文本总结编排（T-096）——map-reduce 主干 + 行业强化。
 *
 * 三层路由（行 LangChain 三策略，refine 弃用如实记录）：
 * - **stuff**：材料 ≤ [TEXT_STUFF_THRESHOLD] 字符 → 全文一次调用（省调用次数，小文档最优）；
 * - **map-reduce**：逐块要点（顺序处理，见下「并发」）→ 归并终稿；
 * - **层级归并**：块数 > [TEXT_HIERARCHICAL_THRESHOLD] → 要点分组先归并为节摘要再终稿。
 *
 * 零编造纪律（T-096）：
 * - quote-first：map 要点必须携带原文短引用，归并只许改写要点（提示词硬约束）；
 * - `<allowed_numbers>`：全部要点关键数字进白名单注入归并提示词，清单外数字一律不许出现；
 * - 调用失败/解析失败不编造：失败块如实进 [TextSummary.failedChunks]，不假装完整；
 * - 全部块失败（或终稿仍为空）时抛 [IllegalStateException]——无材料宁可报错，不许模型凭空写一篇总结；
 *
 * 块要点缓存（行业 memoization 纪律）：key=`textpoints.<材料哈希>.<块哈希>`——取消/重启续传
 * 不重复计费（复用 T-050 签名断点续传纪律）；缓存命中块的 token 计入 [TextSummary.totalTokens]。
 *
 * **并发（诚实边界）**：map 阶段**顺序**处理——core 的同步层纪律下，[onStage] 经调用方
 * 线程局部传播（ProgressBus ThreadLocal），外部线程池会让进度帧静默丢失；且取消检查
 * 落在每次调用之前最简单可靠。计划中「并发 3」未做，并发由调用方（host 工具层）决定
 * 是否在外层批量发起；核心侧保持确定性，便于钉步序测试。
 */
fun summarizeText(
    llm: LlmClient,
    model: String,
    dataset: Dataset,
    focus: String? = null,
    maxWords: Int = 800,
    kv: DatasetStore? = null,
    cancelSignal: () -> Boolean = { false },
    onStage: ((String) -> Unit)? = null,
    /** 受众画像（T-105，SciTeller persona 先例）：写给谁；空 = 不注入 */
    audience: String? = null,
    /** 调用方附加输出要求（T-105）：总结口径等；空 = 不注入 */
    requirements: String? = null,
): TextSummary {
    val content = dataset.text
        ?: throw IllegalStateException("数据集没有文本部分——stragent_import_text 导入的文本/混合数据集才有正文段落")
    if (content.totalChars > TEXT_MAX_MATERIAL_CHARS) {
        throw IllegalStateException(
            "文本材料 ${content.totalChars} 字符超过上限 $TEXT_MAX_MATERIAL_CHARS——请拆分导入",
        )
    }
    val started = System.nanoTime()
    val normalized = TextChunker.normalizeSegments(content.segments)
    // D25 安全审查：分块正文进提示词前做注入中和（存储段原文不动，命中载荷以
    // [已过滤:Rn] 占位——与 T-067 简报格净化同纪律：检出与净化同面）。
    val neutralized = com.stragent.core.security.PromptInjectionHeuristics.neutralizeContent(TextContent(normalized))
    val chunks = TextChunker.aggregateChunks(neutralized)
    if (chunks.size > TEXT_MAX_CHUNKS) {
        throw IllegalStateException(
            "文本材料需要 ${chunks.size} 个分块，超过上限 $TEXT_MAX_CHUNKS——请拆分导入",
        )
    }

    val route = if (content.totalChars <= TEXT_STUFF_THRESHOLD) "stuff" else "map-reduce"

    // ---- map：逐块要点（stuff 路由 = 单块全文） ----
    val mapInputs: List<TextChunk> = if (route == "stuff") {
        listOf(
            TextChunk(
                index = 0,
                text = normalized.joinToString("\n\n") { "${it.index}. ${it.text}" },
                segmentIndexes = normalized.map { it.index },
                tableProjection = false,
            ),
        )
    } else {
        chunks
    }

    // ---- T-105 输出适配：persona/requirements 块（空 = 不注入，逐字节兼容） ----
    val outputHints = buildString {
        if (!audience.isNullOrBlank()) append(com.stragent.core.report.ReportLib.outputPersonaBlock(audience))
        if (!requirements.isNullOrBlank()) append(com.stragent.core.report.ReportLib.outputRequirementsBlock(requirements))
    }
    // audience/requirements 合成视角：非空时 BM25 相关度重排生效（focus=看什么，persona/requirements=写给谁/怎么写）
    val viewFocus = listOfNotNull(
        focus?.takeIf { it.isNotBlank() },
        audience?.takeIf { it.isNotBlank() },
        requirements?.takeIf { it.isNotBlank() },
    ).joinToString("；").ifBlank { null } // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）

    var totalTokens = 0
    val mapped = mapChunkPoints(llm, model, mapInputs, content, viewFocus, kv, cancelSignal, onStage, outputHints)
    val pointsByChunk = HashMap(mapped.pointsByChunk)
    val failed = mapped.failedChunks.toMutableList()
    totalTokens += mapped.totalTokens

    // ---- 要点汇总（保留块内顺序） ----
    val allPoints = mapInputs.flatMap { pointsByChunk[it.index]?.points ?: emptyList() }

    // 全部块失败：无材料可归并——宁可报错也不许凭空成文（零编造硬门禁）。
    // 注意判据是「全部调用失败」而不是「要点为空」：块调用成功但材料没有可要点化的内容
    // （如纯空白投影）是合法结果，由模型在归并时如实收口，不在这里误杀。
    if (failed.size == mapInputs.size) {
        throw IllegalStateException(
            "文本总结失败：全部 ${mapInputs.size} 块要点提取均失败——材料未被读入，未生成任何总结",
        )
    }

    // ---- 白名单收集（锚=块覆盖的段号区间；表格投影块锚也是段号，投影在导入侧生成） ----
    val numbersWithAnchors = mapInputs.flatMap { c ->
        val segs = c.segmentIndexes
        (pointsByChunk[c.index]?.keyNumbers ?: emptyList()).map { n ->
            TextNumberAnchor(n, anchorFor(segs))
        }
    }.distinctBy { it.number }

    // ---- 相关度重排（lost-in-the-middle 缓解）：focus 非空时按 BM25 对要点相关度排序，
    //      最相关置首、次相关置尾，最不相关沉在中部 ----
    val orderedPoints = if (viewFocus.isNullOrBlank()) {
        allPoints
    } else {
        val index = Bm25Index(
            allPoints.mapIndexed { i, p -> Bm25Index.Doc("p$i", p.segmentIndex, p.text + " " + p.quote) }
        )
        val rank = index.score(viewFocus).associate { it.id to it.score }
        val sorted = allPoints.withIndex()
            .sortedByDescending { (i, _) -> rank["p$i"] ?: 0.0 } // Kotlin 排序稳定：同分保持块内顺序
            .map { it.value }
        val front = ArrayList<TextPoint>(sorted.size / 2 + 1)
        val back = ArrayList<TextPoint>(sorted.size / 2)
        sorted.forEachIndexed { i, p -> if (i % 2 == 0) front += p else back += p }
        front + back.reversed()
    }

    val sortedNumbers = numbersWithAnchors
        .sortedWith(compareBy({ anchorStart(it.anchor) }, { it.number }))

    // ---- reduce：归并终稿（stuff 路由直接出稿，无独立归并调用） ----
    var summaryText: String
    if (route == "stuff") {
        // stuff：map 调用的响应即含摘要（单次调用拿要点+摘要）；缺摘要但有要点时补一次归并兜底
        summaryText = pointsByChunk[0]?.summary.orEmpty()
        if (summaryText.isBlank() && allPoints.isNotEmpty()) {
            onStage?.invoke("归并要点为总结")
            val rescue = reduceOnce(llm, model, orderedPoints, sortedNumbers.map { it.number }, viewFocus, maxWords, "终稿", outputHints = outputHints)
            totalTokens += rescue.totalTokens
            summaryText = rescue.content
        }
    } else {
        onStage?.invoke("归并要点为总结")
        val hierarchical = mapInputs.size > TEXT_HIERARCHICAL_THRESHOLD
        if (hierarchical) {
            // 层级：要点先按每组 HIERARCHICAL_GROUP 块归并为节摘要，再总成终稿
            val blockPoints = mapInputs.mapNotNull { pointsByChunk[it.index] }
            val groups = blockPoints.chunked(TEXT_HIERARCHICAL_GROUP)
            val sections = groups.mapIndexed { gi, g ->
                if (cancelSignal()) throw GenerationCancelled("文本总结已取消")
                onStage?.invoke("层级归并（第 ${gi + 1}/${groups.size} 组）")
                val r = reduceOnce(llm, model, g.flatMap { it.points }, g.flatMap { it.keyNumbers }, viewFocus, 400, "节", outputHints = outputHints)
                totalTokens += r.totalTokens
                r.content
            }
            val r = reduceOnce(llm, model, orderedPoints, sortedNumbers.map { it.number }, viewFocus, maxWords, "终稿", sections, outputHints)
            totalTokens += r.totalTokens
            summaryText = r.content
        } else {
            val r = reduceOnce(llm, model, orderedPoints, sortedNumbers.map { it.number }, viewFocus, maxWords, "终稿", outputHints = outputHints)
            totalTokens += r.totalTokens
            summaryText = r.content
        }
    }

    // ---- Chain-of-Density：同长度加密实体 2 轮（模型空响应则保留上一轮稿，不许把稿写没） ----
    var finalText = summaryText
    if (finalText.isNotBlank() && maxWords >= 400) {
        repeat(TEXT_COD_ROUNDS) { round ->
            if (cancelSignal()) throw GenerationCancelled("文本总结已取消")
            onStage?.invoke("密度优化（第 ${round + 1}/$TEXT_COD_ROUNDS 轮）")
            val r = codRound(llm, model, finalText, maxWords, cancelSignal, outputHints)
            totalTokens += r.totalTokens
            if (r.content.isNotBlank()) finalText = r.content
        }
    }

    // 终稿兜底：任何路径（含 stuff 兜底归并 + CoD 空响应）后仍为空——不返回一份空白总结交差
    if (finalText.isBlank()) {
        throw IllegalStateException("文本总结失败：模型未返回任何总结正文")
    }

    return TextSummary(
        summary = finalText,
        points = orderedPoints,
        numbersWithAnchors = sortedNumbers,
        failedChunks = failed,
        route = route,
        chunkCount = mapInputs.size,
        durationMs = (System.nanoTime() - started) / 1_000_000,
        totalTokens = totalTokens,
    )
}

/** 单块的要点提取结果（JSON 解析产物）；[totalTokens] 随 kv 缓存落盘，续传时累计不抹零 */
internal data class ChunkPoints(
    val points: List<TextPoint>,
    val keyNumbers: List<String>,
    val summary: String = "",
    val totalTokens: Int = 0,
)

/** map 阶段产物：块→要点映射、失败块清单、token 账目 */
internal data class TextMapPhase(
    val pointsByChunk: Map<Int, ChunkPoints>,
    val failedChunks: List<Int>,
    val totalTokens: Int,
)

/**
 * map 阶段（逐块要点提取）——summarizeText 与综述报告（TextReview）共用的单一事实源。
 * 缓存键、失败容错、token 记账与 summarizeText 主干完全一致。
 */
internal fun mapChunkPoints(
    llm: LlmClient,
    model: String,
    mapInputs: List<TextChunk>,
    content: TextContent,
    focus: String?,
    kv: DatasetStore?,
    cancelSignal: () -> Boolean,
    onStage: ((String) -> Unit)?,
    outputHints: String = "",
): TextMapPhase {
    var totalTokens = 0
    val cachePrefix = "textpoints." + materialHash(content) + "."
    val pointsByChunk = HashMap<Int, ChunkPoints>()
    val failed = mutableListOf<Int>()
    // 先串行查缓存（廉价），未命中子集再并行调用（T-129 有界扇出，默认并发 3）
    val pending = mutableListOf<TextChunk>()
    for (chunk in mapInputs) {
        if (cancelSignal()) throw GenerationCancelled("文本总结已取消")
        onStage?.invoke("分段要点（第 ${chunk.index + 1}/${mapInputs.size} 块）")
        val cached = kv?.get(cachePrefix + chunkHash(chunk.text))?.let { parseChunkPoints(it) }
        if (cached != null) {
            pointsByChunk[chunk.index] = cached
            totalTokens += cached.totalTokens
        } else {
            pending += chunk
        }
    }
    // 协作式取消在 Parallel 内部逐任务自查（HTTP 调用中不可中断是既有边界）；进度按提交序
    // 失败判定在串行收集处做（并行 lambda 内不碰共享可变集合）
    Parallel.mapLimitedCollecting(pending, cancelSignal = cancelSignal) { chunk ->
        summarizeChunk(llm, model, chunk, focus, cancelSignal, outputHints)
    }.forEachIndexed { j, r ->
        val chunk = pending[j]
        val pts = r.getOrNull()
        if (pts == null) {
            failed += chunk.index
        } else {
            pointsByChunk[chunk.index] = pts
            totalTokens += pts.totalTokens
            kv?.put(cachePrefix + chunkHash(chunk.text), encodeChunkPoints(pts))
        }
    }
    return TextMapPhase(pointsByChunk, failed, totalTokens)
}

// ==================== 常量 ====================
// T-098：三个规模阈值 env 可调（仿 STRAGENT_DATASET_MAX_ENTRIES 先例）——
// 百万上下文模型的部署可放宽（长文单发/更大材料），默认保守服务最弱调用方。
// 进程级读取一次；数值防呆下限防误配。

private fun envInt(name: String, fallback: Int, min: Int): Int =
    Config.get(name)?.toIntOrNull()?.coerceAtLeast(min) ?: fallback

/** stuff 直通阈值（字符数）；env STRAGENT_TEXT_STUFF_CHARS（下限 1_000） */
val TEXT_STUFF_THRESHOLD = envInt("STRAGENT_TEXT_STUFF_CHARS", 30_000, 1_000)

/** 块数上限（超出建议拆分导入）；env STRAGENT_TEXT_MAX_CHUNKS（下限 8） */
val TEXT_MAX_CHUNKS = envInt("STRAGENT_TEXT_MAX_CHUNKS", 96, 8)

/** 材料总字符上限；env STRAGENT_TEXT_MAX_CHARS（下限 50_000） */
val TEXT_MAX_MATERIAL_CHARS = envInt("STRAGENT_TEXT_MAX_CHARS", 4_000_000, 50_000)

/** 嵌入模型名（T-100 混合检索）；env STRAGENT_TEXT_EMBEDDING_MODEL 可调 */
val TEXT_EMBEDDING_MODEL: String
    get() = Config.get("STRAGENT_TEXT_EMBEDDING_MODEL")?.takeIf { it.isNotBlank() } ?: "text-embedding-3-small"

/** 重排模型名（T-101 精排）；env STRAGENT_TEXT_RERANK_MODEL 可调 */
val TEXT_RERANK_MODEL: String
    get() = Config.get("STRAGENT_TEXT_RERANK_MODEL")?.takeIf { it.isNotBlank() } ?: "jina-reranker-v2-base-multilingual"

/** 层级归并触发块数 */
const val TEXT_HIERARCHICAL_THRESHOLD = 40

/** 层级归并每组块数 */
const val TEXT_HIERARCHICAL_GROUP = 40

/** Chain-of-Density 轮数 */
const val TEXT_COD_ROUNDS = 2

// ==================== 内部实现 ====================

private fun summarizeChunk(
    llm: LlmClient,
    model: String,
    chunk: TextChunk,
    focus: String?,
    cancelSignal: () -> Boolean,
    outputHints: String = "",
): ChunkPoints? {
    var attempt = 0
    while (attempt < 2) {
        attempt++
        try {
            val system = """你是严谨的文本分析员。从给定文本块中提取要点，输出 JSON（不要多余文字）：
                |{"points":[{"text":"要点（一句话，可含数字）","quote":"支撑该要点的原文短引用（≤50字，逐字摘自原文）","segment":段号}],"keyNumbers":["块中的关键数字（原样摘录）"],"summary":"本块 100 字概述"}
                |纪律：①要点必须来自原文，quote 必须逐字摘自原文（≤50 字）；②segment 填该块内段号；③没有要点的块给空数组；④不编造。""".trimMargin()
            val user = buildString {
                if (outputHints.isNotBlank()) append(outputHints).append("\n")
                if (!focus.isNullOrBlank()) append("分析视角：").append(focus).append("\n\n")
                append(chunk.text)
            }
            val result = chatStep(
                llm, model,
                listOf("system" to system, "user" to user),
                ChatOptions(maxTokens = 1600),
                cancelSignal = cancelSignal,
            )
            val pts = parseChunkPoints(result.content)
            return pts.copy(totalTokens = result.totalTokens)
        } catch (e: GenerationCancelled) {
            throw e
        } catch (e: Exception) {
            if (attempt >= 2) return null
        }
    }
    return null
}

/**
 * 解析模型输出的要点 JSON。容错：剥离 ```json 围栏、截取首个 '{' 到末个 '}'；
 * points 里缺 text 的条目跳过；segment 兼容数字与字符串两种 JSON 形态。
 */
internal fun parseChunkPoints(jsonText: String): ChunkPoints {
    val text = stripCodeFence(jsonText)
    val obj = kotlinx.serialization.json.Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject
        ?: throw IllegalStateException("要点 JSON 不是对象")
    fun str(o: kotlinx.serialization.json.JsonObject?, key: String): String? =
        (o?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
    fun intOf(el: kotlinx.serialization.json.JsonElement?): Int? =
        when (el) {
            is kotlinx.serialization.json.JsonPrimitive -> el.content.toIntOrNull()
            else -> null
        }
    val points = (obj["points"] as? kotlinx.serialization.json.JsonArray).orEmpty()
        .mapNotNull { item ->
            val o = item as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val t = str(o, "text")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TextPoint(
                text = t,
                quote = str(o, "quote") ?: "",
                segmentIndex = intOf(o["segment"]) ?: 1,
            )
        }
    val keyNumbers = (obj["keyNumbers"] as? kotlinx.serialization.json.JsonArray).orEmpty()
        .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { s -> s.isNotBlank() } }
    val totalTokens = intOf(obj["totalTokens"]) ?: 0
    return ChunkPoints(points, keyNumbers, str(obj, "summary") ?: "", totalTokens)
}

/** 剥 markdown 代码围栏并围出 JSON 主体（真实模型常把 JSON 包进 ```json …```） */
private fun stripCodeFence(raw: String): String {
    var s = raw.trim()
    if (s.startsWith("```")) {
        s = s.removePrefix("```")
        if (s.startsWith("json", ignoreCase = true)) s = s.removePrefix("json")
        val end = s.lastIndexOf("```")
        if (end >= 0) s = s.substring(0, end)
    }
    val start = s.indexOf('{')
    val last = s.lastIndexOf('}')
    return if (start >= 0 && last > start) s.substring(start, last + 1) else s.trim()
}

private fun reduceOnce(
    llm: LlmClient,
    model: String,
    points: List<TextPoint>,
    keyNumbers: List<String>,
    focus: String?,
    maxWords: Int,
    label: String,
    sectionDrafts: List<String>? = null,
    outputHints: String = "",
): ChatResult {
    val allowed = allowedNumbersBlock(keyNumbers)
    val system = """你是严谨的总结撰写人。基于给定的要点清单撰写${label}总结。硬性纪律：
        |①只允许使用要点与 <allowed_numbers> 清单中的数字——清单外一个数字都不许出现；②要点没有覆盖的内容不要写（不编造）；③每个关键数字就近标注出处（如「段 3-5」）；④不写无依据的推断。""".trimMargin()
    val user = buildString {
        if (outputHints.isNotBlank()) append(outputHints).append("\n")
        if (!focus.isNullOrBlank()) append("分析视角：").append(focus).append("\n\n")
        append("<allowed_numbers>").append(allowed).append("</allowed_numbers>\n\n")
        if (sectionDrafts != null) {
            append("节摘要：\n")
            sectionDrafts.forEachIndexed { i, s -> append("第 ").append(i + 1).append(" 节：").append(s).append('\n') }
        } else {
            append("要点清单：\n")
            points.forEachIndexed { i, p ->
                append(i + 1).append(". ").append(p.text).append("（quote：").append(p.quote)
                    .append("；段 ").append(p.segmentIndex).append("）\n")
            }
        }
        append("\n字数上限：约 ").append(maxWords).append(" 字。")
    }
    return chatStep(
        llm, model,
        listOf("system" to system, "user" to user),
        ChatOptions(maxTokens = (maxWords * 3).coerceAtLeast(1200)),
    )
}

private fun codRound(
    llm: LlmClient,
    model: String,
    draft: String,
    maxWords: Int,
    cancelSignal: () -> Boolean,
    outputHints: String = "",
): ChatResult {
    val system = "你是总结润色员。对给定总结做一轮「密度优化」：在不明显增加字数的前提下，把遗漏的重要实体/数字/细节合并进摘要；删除空话。只输出优化后的总结正文。"
    // T-106 AR 精确反馈（Retkowski 2501.00233）：实际 vs 目标字数随 prompt 送达，提升长度依从
    val actualChars = draft.replace(Regex("\\s"), "").length
    val user = buildString {
        if (outputHints.isNotBlank()) append(outputHints).append("\n")
        append("当前总结：\n").append(draft).append("\n\n字数上限：约 ").append(maxWords)
            .append(" 字（当前约 ").append(actualChars).append(" 字，密度优化保持同量级）。") // lint:allow zh-first（用户面中文输出/提示词，T-095 语言定位）
    }
    return chatStep(
        llm, model,
        listOf("system" to system, "user" to user),
        ChatOptions(maxTokens = (maxWords * 3).coerceAtLeast(1200)),
        cancelSignal = cancelSignal,
    )
}

fun allowedNumbersBlock(numbers: List<String>): String {
    val distinct = numbers.filter { it.isNotBlank() }.distinct()
    if (distinct.isEmpty()) return "（材料中没有关键数字——总结中不要出现任何具体数字）"
    return "本材料只允许使用下面这些数字：${distinct.joinToString("、")}。清单外一个不许出现；派生必须就近写算式。"
}

private fun anchorFor(segmentIndexes: List<Int>): String {
    val first = segmentIndexes.firstOrNull() ?: 1
    val last = segmentIndexes.lastOrNull() ?: first
    return if (first == last) "段 $first" else "段 $first-$last"
}

private fun anchorStart(anchor: String): Int =
    Regex("""\d+""").find(anchor)?.value?.toIntOrNull() ?: Int.MAX_VALUE

private fun materialHash(content: TextContent): String =
    sha12(content.segments.joinToString("\u0000") { "${it.index}:${it.kind}:${it.text}" })

internal fun chunkHash(text: String): String = sha12(text)

private fun sha12(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }.take(12)

private fun encodeChunkPoints(p: ChunkPoints): String {
    val points = p.points.joinToString(",") { pt ->
        """{"text":${jsonStr(pt.text)},"quote":${jsonStr(pt.quote)},"segment":${pt.segmentIndex}}"""
    }
    val nums = p.keyNumbers.joinToString(",") { jsonStr(it) }
    return """{"points":[$points],"keyNumbers":[$nums],"summary":${jsonStr(p.summary)},"totalTokens":${p.totalTokens}}"""
}

private fun jsonStr(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "").replace("\t", "\\t") + "\""
