package com.stragent.core.ai

import com.stragent.core.brief.buildDatasetBrief
import com.stragent.core.dataset.Dataset
import com.stragent.core.ports.DatasetStore
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.PrefsStore
import com.stragent.core.ports.SearchClient
import com.stragent.core.report.BriefSlice
import com.stragent.core.report.MAX_GATE_REVISIONS
import com.stragent.core.report.ReportLanguage
import com.stragent.core.report.ReportLib
import com.stragent.core.report.SINGLE_SHOT_CHARS_LIMIT
import com.stragent.core.report.chapterMessages
import com.stragent.core.report.isStructuredReportType
import com.stragent.core.report.outputCeilingFor
import com.stragent.core.report.planChapters
import com.stragent.core.report.reviseReportWithLlm
import com.stragent.core.report.runReportGate
import com.stragent.core.report.targetLengthFor
import kotlinx.serialization.json.JsonObject

/**
 * generateInsight 编排 —— ai-insight.ts generateInsightInner 的移植。
 *
 * 单次 vs 分章的判据：质量（>9,000 字分章），不是 token 够不够。
 * 联网：检索整条链失败时**降级为纯数据分析**，不拖垮整份报告。
 * 分章：并发 3（批处理近似）、每章失败重试一次再留占位、逐章落盘断点续传。
 */
data class InsightResult(
    val report: String,
    val sources: List<SearchSource> = emptyList(),
    val totalTokens: Int = 0,
    val usedWeb: Boolean = false,
    val truncated: Boolean = false,
    val webFailed: Boolean = false,
    val semantic: List<SemanticIssue> = emptyList(),
    val targetChars: Int = 0,
    val actualChars: Int = 0,
    val chapters: List<Pair<String, Int>>? = null,
    /** 章节结构是否由模型自主规划（agent 插件路径恒 true；经典流水线恒 false） */
    val autonomous: Boolean = false,
    /** 生成引擎（classic = 经典流水线；其余为插件 id）——报告卡与版本条显示用 */
    val engineId: String = "classic",
    /** Agent 引擎的分析计划（Plan-and-Execute 计划层；经典路径为空） */
    val agentPlan: List<String> = emptyList(),
    /** Agent 引擎的发现卡（scratchpad 日志；经典路径为空） */
    val agentFindings: List<AgentFindingCard> = emptyList(),
    /** Agent 引擎建议的图表（suggest_chart；经典路径为空） */
    val agentCharts: List<AgentChartCard> = emptyList(),
    /** Agent 引擎的可观测轨迹（每步 thought/tool/args/ok/error；经典路径为空）——对标 DeepSeek Harness Trajectory，用于回放/排障/溯源审计 */
    val agentTrace: List<JsonObject> = emptyList(),
    /** 写作审计注记（T-032：WritingAudit 软提示——缺结构块/篇幅偏离；硬项在编辑部路径已走退稿循环） */
    val qualityNotes: List<String> = emptyList(),
    /**
     * 发稿门禁是否失败（T-044）：硬问题（未溯源数字/算式/关系/字段/套话≥3/矛盾）经
     * ≤2 轮修订仍未清零 = true。报告仍返回——不静默放行，但也不能因为修不干净就丢稿；
     * 宿主与 UI 用 [gateIssues] 呈现问题。字段恒存在（false = 过闸），不是「没有这路信息」。
     */
    val gateFailed: Boolean = false,
    /** 门禁终稿问题清单（含软问题；空列表 = 过闸且无注记） */
    val gateIssues: List<String> = emptyList(),
    /** 门禁修订轮数（经典/单 agent 路径为后置管线轮数；编辑部路径另含内部退稿轮） */
    val gateRevisions: Int = 0,
    /**
     * T-091：引擎回落标记——用户显式选择了非 classic 引擎但注册表未能承接
     * （kind 不被支持 / 插件已删），实际按经典流水线生成。false = 所选引擎
     * 正常执行或本来就用 classic。恒存在的字段（T-032 同纪律）：回落是降级事实，
     * 必须到达客户端而不是只留服务端日志（T-090 警告只到日志面）。
     */
    val engineFallback: Boolean = false,
    /** T-091：回落原因（中文一句话；[engineFallback]=false 时为 null，不编造原因） */
    val engineFallbackReason: String? = null,
)

/** 报告引用的网络来源（stepsearch.ts SearchResultItem 的最小形态） */
data class SearchSource(val title: String, val url: String, val snippet: String = "")

/** 章节生成进度（UI 的「正在分章撰写（第 X / Y 章：…）」） */
data class ChapterProgress(val done: Int, val total: Int, val title: String)

/**
 * 正文增量 —— 报告**写出过程中的字**，不是「写完了第几章」。
 *
 * 为什么要这个事件（T-013 余量 · 阶段 2）：阶段进度（[ChapterProgress]）的粒度是「章」，
 * 一章几千字要等十几秒才跳一格；外部 agent 在这一格里既看不到写了什么，也无从判断
 * 「模型是在正经写还是在绕」。正文按 token 冒出来后，客户端能实时看到内容在长。
 *
 * 口径（三条都要成立，否则客户端拼出来的正文与最终报告对不上）：
 *  - [text] 是**自上一帧以来新增**的正文片段，按 (chapter, attempt, 到达序) 拼接 = 该章正文；
 *  - [chars] 是本章**本次生成**已产出的原始字符数（含 Markdown 标记，非 [countChars] 口径——
 *    流式那一刻还没法去标记，这里只当进度尺用）；
 *  - [attempt] 是这一章的第几次生成尝试（失败重试 = 2）。**同一章的 attempt 一变，
 *    客户端必须丢掉该章已拼的片段重贴**——重写时旧片段是新正文的前缀垃圾。
 *
 * 未分章的单次成文路径固定用 `chapter=1, total=1, title="全文"`。
 */
data class BodyDelta(
    val chapter: Int,
    val total: Int,
    val title: String,
    val attempt: Int,
    val chars: Int,
    val text: String,
)

/** 节流：攒够这么多字符就发一帧（够一帧的正文值得发，太碎就是在刷通知） */
internal const val STREAM_MIN_CHARS = 120

/** 节流：再久没攒够也发一帧（慢模型吐字稀疏时进度不能看起来卡死） */
internal const val STREAM_MAX_WAIT_MS = 400L

/**
 * 正文增量节流器 —— 把 token 级碎片攒成「够一段、或够一定时间」才发一帧。
 *
 * 为什么在 core 而不是在传输层：只有这里知道「当前第几章、第几次生成、本章已写多少字」，
 * 而这三项正是客户端拼回正文、判断进度、识别重写的全部依据。
 * 一次调用的生命周期 = 一章的一次生成（失败重试时换新实例并带上更高的 [attempt]，
 * 客户端据此知道「这一章在重写，把我已贴的丢掉」）。
 */
internal class BodyThrottle(
    private val chapter: Int,
    private val total: Int,
    private val title: String,
    private val attempt: Int,
    private val emit: (BodyDelta) -> Unit,
    private val minChars: Int = STREAM_MIN_CHARS,
    private val maxWaitMs: Long = STREAM_MAX_WAIT_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val buffer = StringBuilder()
    private var chars = 0
    private var lastFlushAt = clock()

    fun onToken(token: String) {
        if (token.isEmpty()) return
        buffer.append(token)
        chars += token.length
        if (buffer.length >= minChars || clock() - lastFlushAt >= maxWaitMs) flush()
    }

    /** 无条件把尾巴发出去（章末必调：不足 minChars 的尾段也是正文，丢了就拼不回全文） */
    fun flush() {
        if (buffer.isEmpty()) return
        emit(BodyDelta(chapter, total, title, attempt, chars, buffer.toString()))
        buffer.setLength(0)
        lastFlushAt = clock()
    }
}

const val FAILED_CHAPTER_MARK = "这一章生成失败"

fun isFailedChapterText(text: String): Boolean = text.contains(FAILED_CHAPTER_MARK)

private const val CHAPTER_CONCURRENCY = 3

/** 实际字数（TS countChars 口径：先去代码块，再去 Markdown 标记，最后数非空白字符） */
fun countChars(report: String): Int =
    report
        .replace(Regex("```[\\s\\S]*?```"), "")
        .replace(Regex("[#*`>|-]"), "")
        .replace(Regex("\\s"), "")
        .length

/** 草稿落盘分隔符：parts 按章序用它在 DatasetStore 里拼成一个字符串（internal——测试共用同一格式） */
internal const val PART_SEP = "\u0003<<SD-PART>>\u0003"

/** 检索词/审查用的轻量模型（省钱：一次几分钱）；只在阶跃服务商下生效 */
const val QUERY_MODEL = "step-3.7-flash"

class InsightOrchestrator(
    private val llm: LlmClient,
    private val prefs: PrefsStore,
    private val drafts: DatasetStore? = null,
    private val search: SearchClient? = null,
    private val model: String = "default",
    /** 审查用模型：分级策略——轻量模型做审查，强模型只负责生成（阶跃才有轻量档） */
    private val reviewModelName: String = model,
    /**
     * 报告类型登记表（T-145）：宿主注册的自定义报告类型经此进入**简报预算 / 章节模板 /
     * 目标字数**三处。默认 `BUILT_IN` = 行为与本改动前逐字节一致。
     */
    private val reportTypes: com.stragent.core.report.ReportTypeRegistry =
        com.stragent.core.report.ReportTypeRegistry.BUILT_IN,
) {
    fun generateInsight(
        dataset: Dataset,
        useWeb: Boolean = false,
        caliberNotes: List<com.stragent.core.caliber.CaliberNote> = emptyList(),
        onStage: ((String, ChapterProgress?) -> Unit)? = null,
        cancelSignal: () -> Boolean = { false },
        onBody: ((BodyDelta) -> Unit)? = null,
        memory: List<com.stragent.core.memory.MemoryFact> = emptyList(),
        semantic: List<com.stragent.core.semantic.SemanticTerm> = emptyList(),
        /** 指标定义（T-055）：可解析且自检通过的指标值会进摘要，报告可直接引用 */
        metrics: List<com.stragent.core.semantic.MetricDef> = emptyList(),
        /** T-105 按调用输出适配：prefs 键→值覆盖（analysis.reportType/style/industry/depth/audience/requirements）；空 = 零差异 */
        styleOverrides: Map<String, String> = emptyMap(),
    ): InsightResult {
        // 协作式取消：入口先查一次（客户端可能在排队/预处理阶段就取消了）
        if (cancelSignal()) throw GenerationCancelled()
        // T-105：按调用覆盖层（Langfuse「持久预设+运行时变量」先例）——空 map = 原 prefs，逐字节零差异
        val prefs: com.stragent.core.ports.PrefsStore =
            if (styleOverrides.isEmpty()) this.prefs else com.stragent.core.ports.PrefsOverlay(this.prefs, styleOverrides)
        // T-173：语言档位从**本次调用的覆盖层**解析——与原 prefs 可能不同（调用方显式指定 language）。
        // 提示词与写作审计必须读同一份，否则「提示词让模型写英文、门禁却按中文标记查」——
        // 端到端实证抓到过这个真 bug（英文报告被误报四处缺结构块）。
        val reportLanguage = ReportLanguage.from(prefs.get(ReportLanguage.PREF_KEY, "zh"))
        // T-096：text/mixed 数据集 → 综述分支（要点 map+程序定章节+claim 级门禁）。
        // 表格链路的 brief/统计/指标对「空表+正文」没有意义；口径/指标/web 检索是表格侧
        // 能力，本分支不支持（诚实边界记 D7）。
        if (dataset.text != null) {
            return generateTextReview(
                llm, prefs, model, reviewModelName, dataset, onStage, cancelSignal, onBody, memory, semantic,
            )
        }
        val brief = buildDatasetBrief(dataset, prefs, metrics, reportTypes)
        val typeId = ReportLib.getReportType(prefs).id
        val styleLength = ReportLib.getReportStyle(prefs).length

        // targetLengthFor 的两个内部口径（TS 在函数里用 summarize 自算——这里对齐复刻）
        val stats = com.stragent.core.dataset.summarize(dataset)
        val numericColumns = stats.count {
            it.type == com.stragent.core.csv.ColumnType.NUMBER && !com.stragent.core.dataset.looksLikeIdColumn(it.name)
        }
        val groupColumns = stats.count {
            it.type == com.stragent.core.csv.ColumnType.TEXT && !com.stragent.core.dataset.looksLikeIdColumn(it.name) &&
                (it.distinct ?: 0) >= 2 && (it.distinct ?: 0) <= 40
        }
        val target = targetLengthFor(
            brief, dataset.rows.size, dataset.columns.size, numericColumns, groupColumns,
            styleLength, typeId, reportTypes,
        )

        var webBlock = ""
        var sources: List<SearchSource> = emptyList()
        var webFailed = false
        if (useWeb && search != null) {
            if (cancelSignal()) throw GenerationCancelled()
            // 检索整条链（生成检索词 → 搜索）失败时降级为纯数据分析，不拖垮整份报告；
            // 但取消不是失败——吞掉它会让客户端取消后照样把整份报告写完
            try {
                onStage?.invoke("planning", null)
                val query = planQuery(brief, cancelSignal)
                onStage?.invoke("searching", null)
                val hits = search.search(query)
                sources = hits.map { SearchSource(it.title, it.url, it.snippet) }
                webBlock = webMaterialBlock(
                    hits.joinToString("\n\n") { "${it.title}：${it.snippet}" },
                    hits.map { it.title to it.url },
                )
            } catch (cancelled: GenerationCancelled) {
                throw cancelled
            } catch (_: Exception) {
                webFailed = true
                sources = emptyList()
                webBlock = ""
            }
        }

        // 语义层注入：跨数据集指标定义，按列名匹配后注入为独立块（纪律 a）
        val semanticBlockStr = if (semantic.isNotEmpty()) {
            val matched = com.stragent.core.semantic.matchTermsForColumns(
                semantic, dataset.columns,
            )
            com.stragent.core.semantic.semanticBlock(matched) ?: ""
        } else ""

        val base = if (target.chars > SINGLE_SHOT_CHARS_LIMIT) {
            val generated = generateByChapters(
                brief, target, typeId,
                datasetId = "${dataset.name}@${dataset.importedAt}",
                useWeb = useWeb,
                webBlock = webBlock,
                caliberNotes = caliberNotes,
                onStage = onStage,
                onBody = onBody,
                memory = com.stragent.core.memory.memoryBlock(memory) ?: "",
                semantic = semanticBlockStr,
                cancelSignal = cancelSignal,
            )
            generated.copy(
                sources = sources,
                usedWeb = useWeb && !webFailed,
                webFailed = webFailed,
            )
        } else {
            if (cancelSignal()) throw GenerationCancelled()
            onStage?.invoke("analyzing", null)
            val messages = insightMessages(prefs, brief, webBlock, target, caliberNotes,
                memory = com.stragent.core.memory.memoryBlock(memory) ?: "",
                semantic = semanticBlockStr)
            val inputText = messages.joinToString("\n") { it.content }
            // 单次成文没有章可依：整份正文按「第 1/1 章」流出，口径与分章路径同构
            val throttle = onBody?.let { BodyThrottle(1, 1, "全文", 1, it) }
            val result = try {
                chatReportStep(
                    llm, model, messages.map { it.role to it.content },
                    outputCeilingFor(target.chars, inputText),
                    target.chars,
                    onToken = throttle?.let { t -> { token -> t.onToken(token) } },
                    cancelSignal = cancelSignal,
                )
            } finally {
                throttle?.flush()
            }
            InsightResult(
                report = result.content,
                sources = sources,
                totalTokens = result.totalTokens,
                usedWeb = useWeb && !webFailed,
                truncated = result.truncated,
                webFailed = webFailed,
                targetChars = target.chars,
                actualChars = countChars(result.content),
            )
        }

        // 正文已出但客户端已取消：不再花一次审查模型调用（门禁本身不花钱，但修订会）
        if (cancelSignal()) throw GenerationCancelled()

        // ---- 发稿门禁（T-044 / AD-028）：引擎无关后置管线，经典路径不再裸奔 ----
        onStage?.invoke("发稿门禁", null)
        val gated = gateReport(base, brief, target, typeId, onStage, cancelSignal, reportLanguage)

        // 修订后的正文才是最终稿：语义审查必须看终稿，否则审的是已废弃的旧版本
        if (cancelSignal()) throw GenerationCancelled()
        // T-062：审查失败不再静默吞成空列表——写进 qualityNotes，让宿主与评测都看得见
        val review = try {
            semanticReview(llm, prefs, semanticSample(gated.report), brief, reviewModelName)
        } catch (_: Exception) {
            SemanticReviewOutcome(failure = "审查入口异常，未完成")
        }
        val semanticNotes = review.failure?.let {
            listOf("语义审查未完成（$it）：本稿仅通过确定性门禁，语义层未审")
        }.orEmpty()
        return gated.copy(
            semantic = review.issues,
            qualityNotes = gated.qualityNotes + semanticNotes,
        )
    }

    /**
     * 经典路径的发稿门禁：`verify → revise(≤2) → 写作审计 / 语义审校`。
     * 门禁问题全量进 `qualityNotes`（不截断——T-032 的教训），修订轮数与终稿字数同步更新。
     */
    private fun gateReport(
        base: InsightResult,
        brief: String,
        target: com.stragent.core.report.TargetLength,
        typeId: String,
        onStage: ((String, ChapterProgress?) -> Unit)?,
        cancelSignal: () -> Boolean,
        language: ReportLanguage,
    ): InsightResult {
        val outcome = runReportGate(
            report = base.report,
            brief = brief,
            targetChars = target.chars,
            structured = isStructuredReportType(typeId),
            findings = base.agentFindings,
            maxRevisions = MAX_GATE_REVISIONS,
            cancelSignal = cancelSignal,
            // T-173：写作审计的语言档位——由本次调用的覆盖层解析（与提示词同源）
            language = language,
            onRound = { round, hard ->
                onStage?.invoke("发稿门禁（第 $round 轮修订：$hard 处硬伤）", null)
            },
        ) { issues, current, _ ->
            reviseReportWithLlm(
                llm = llm,
                model = model,
                brief = brief,
                currentReport = current,
                issues = issues,
                targetChars = target.chars,
                cancelSignal = cancelSignal,
            )
        }
        val lines = outcome.issues.map { it.line }
        return base.copy(
            report = outcome.report,
            totalTokens = base.totalTokens + outcome.totalTokens,
            truncated = base.truncated || outcome.truncated,
            actualChars = countChars(outcome.report),
            qualityNotes = lines,
            gateFailed = outcome.failed,
            gateIssues = lines,
            gateRevisions = outcome.revisions,
        )
    }

    /** 让轻量模型基于数据摘要生成 1 个检索词（ai-insight.ts planQuery） */
    private fun planQuery(brief: String, cancelSignal: () -> Boolean = { false }): String {
        val result = chatStep(
            llm, QUERY_MODEL,
            listOf(
                "system" to (
                    "你是检索专家。根据数据摘要，生成 1 个最适合的网页搜索关键词（中文，20 字以内），" +
                        "用于查找该数据所属领域的分析框架、行业基准或最新趋势。只输出关键词本身，不要任何解释。"
                    ),
                "user" to brief.take(2000),
            ),
            cancelSignal = cancelSignal,
        )
        return result.content
            .replace(Regex("^[\"'「『\\s]+"), "")
            .replace(Regex("[\"'」』\\s]+$"), "")
            .take(40)
    }

    private fun draftKey(signature: String) = "chapter-draft.$signature"

    private fun generateByChapters(
        brief: String,
        target: com.stragent.core.report.TargetLength,
        typeId: String,
        datasetId: String,
        useWeb: Boolean,
        webBlock: String,
        caliberNotes: List<com.stragent.core.caliber.CaliberNote>,
        onStage: ((String, ChapterProgress?) -> Unit)?,
        onBody: ((BodyDelta) -> Unit)? = null,
        memory: String = "",
        semantic: String = "",
        cancelSignal: () -> Boolean = { false },
    ): InsightResult {
        if (cancelSignal()) throw GenerationCancelled()
        val plan = planChapters(brief, target.chars, typeId, reportTypes)
        val total = plan.chapters.size
        val parts = Array(total) { "" }
        var totalTokens = 0
        var truncated = false
        var done = 0
        val failedChapters = mutableSetOf<Int>()

        // 断点续传：签名一致 → 已写完的章直接填回。
        // 签名七段（chapter-draft.ts chapterDraftSignature 口径）：任何一段变了，
        // 旧草稿的计划就对不上新报告，续传只会拼出结构错乱的文章。
        val signature = listOf(
            datasetId,
            typeId,
            ReportLib.getReportStyle(prefs).id,
            ReportLib.getIndustry(prefs).id,
            target.chars.toString(),
            if (useWeb) "web" else "local",
            plan.chapters.joinToString("|") { "${it.title}@${it.chars}" },
        ).joinToString("§")
        if (drafts != null) {
            val raw = drafts.get(draftKey(signature))
            if (raw != null) {
                raw.split(PART_SEP).forEachIndexed { index, text ->
                    if (index < total && text.isNotEmpty() && !isFailedChapterText(text)) {
                        parts[index] = text
                        done += 1
                    }
                }
                if (done > 0) onStage?.invoke("chapter", ChapterProgress(done, total, "接着上次的进度继续"))
            }
        }

        fun persist() {
            if (drafts == null) return
            // 分隔符拼接格式：parts 用 PART_SEP 连接，失败/未写章存空串
            // （读侧按 isNotEmpty && !isFailedChapterText 判"已写完"）
            drafts.put(
                draftKey(signature),
                parts.mapIndexed { i, text -> if (text.isEmpty() || failedChapters.contains(i)) "" else text }
                    .joinToString(PART_SEP),
            )
        }

        val system = insightMessages(prefs, brief, webBlock, target, caliberNotes)[0].content
        val caliber = com.stragent.core.caliber.caliberBlock(caliberNotes) ?: ""

        // 并发 3（suspend 语义在 JVM 用批处理近似：3 个一批顺序跑）
        val pending = (0 until total).filter { parts[it].isEmpty() }
        var cursor = 0
        while (cursor < pending.size) {
            // 协作式取消的唯一可靠停点：每次发起章节模型调用之前
            if (cancelSignal()) throw GenerationCancelled()
            val batch = pending.subList(cursor, minOf(cursor + CHAPTER_CONCURRENCY, pending.size))
            for (index in batch) {
                if (cancelSignal()) {
                    // 本批已写完的章先落盘：取消不是失败，下次可从这里续传
                    persist()
                    throw GenerationCancelled()
                }
                val chapter = plan.chapters[index]
                val msgs = chapterMessages(system, brief, plan, index, webBlock, caliber, memory, semantic)
                val pairs = msgs.map { it.role to it.content }
                val inputText = pairs.joinToString("\n") { it.second }
                var result: ChatResult? = null
                var attempt = 0
                while (attempt < 2 && result == null) {
                    // 每次尝试一个节流器：重试 = 这一章重写，attempt 递增、累计字数从 0 起，
                    // 客户端据此丢掉该章已拼的片段（见 BodyDelta 口径）
                    val throttle = onBody?.let {
                        BodyThrottle(index + 1, total, chapter.title, attempt + 1, it)
                    }
                    try {
                        result = chatReportStep(
                            llm, model, pairs,
                            outputCeilingFor(chapter.chars, inputText),
                            chapter.chars,
                            onToken = throttle?.let { t -> { token -> t.onToken(token) } },
                            cancelSignal = cancelSignal,
                        )
                    } catch (cancelled: GenerationCancelled) {
                        // 取消不是「这一章失败」：既不能重试（再花一次钱），也不能记成失败章
                        throw cancelled
                    } catch (_: Exception) {
                        attempt += 1 // 失败重试一次（TS 纪律：多数失败是网络抖动）
                    } finally {
                        throttle?.flush() // 章末（含失败那次）把尾巴发出去
                    }
                }
                if (result == null) {
                    // 重试也失败才留一行说明；记进 failedChapters：persist 存空串，续传时重新生成这一章
                    failedChapters.add(index)
                    parts[index] = "## ${chapter.title}\n\n（$FAILED_CHAPTER_MARK，可能是网络或服务暂时不可用。" +
                        "重新生成一次通常就能补上；其余章节不受影响。）"
                } else {
                    val text = result.content.trim()
                    totalTokens += result.totalTokens
                    truncated = truncated || result.truncated
                    // 模型偶尔会自己再写一遍 `## 标题`——拼接时会与程序加的标题重复
                    parts[index] = "## ${chapter.title}\n\n${text.replace(Regex("^#{1,3}\\s*"), "")}"
                }
                done += 1
                onStage?.invoke("chapter", ChapterProgress(done, total, chapter.title))
            }
            persist() // 每批落盘（断点续传的粒度）
            cursor += batch.size
        }
        // 全部写完才清草稿：中途清了，中断后就无从续传
        drafts?.remove(draftKey(signature))

        val report = parts.joinToString("\n\n")
        return InsightResult(
            report = report,
            totalTokens = totalTokens,
            truncated = truncated,
            targetChars = target.chars,
            actualChars = countChars(report),
            chapters = plan.chapters.map { it.title to it.chars },
        )
    }
}

/**
 * 报告调用的输出预算回落（T-062）：推理模型需要更大的预算（`outputCeilingFor` 的 floor 32k），
 * 但端点/模型可能以 400 拒绝超过自身上限的 `max_tokens`——此时按
 * [com.stragent.core.report.REPORT_BUDGET_FALLBACK_TOKENS] 再打一次（400 是拒收，不产生正文与 token 费用）。
 * 其他错误原样上抛：不静默降级、不换模型重写。
 */
private fun chatReportStep(
    llm: LlmClient,
    model: String,
    messages: List<Pair<String, String>>,
    ceiling: Int,
    targetChars: Int,
    onToken: ((String) -> Unit)?,
    cancelSignal: () -> Boolean,
): ChatResult = try {
    chatStep(
        llm, model, messages,
        ChatOptions(temperature = 0.7, maxTokens = ceiling, targetChars = targetChars),
        onToken = onToken,
        cancelSignal = cancelSignal,
    )
} catch (e: StepError) {
    if (e.code == STEP_ERR_BAD_REQUEST && ceiling > com.stragent.core.report.REPORT_BUDGET_FALLBACK_TOKENS) {
        chatStep(
            llm, model, messages,
            ChatOptions(
                temperature = 0.7,
                maxTokens = com.stragent.core.report.REPORT_BUDGET_FALLBACK_TOKENS,
                targetChars = targetChars,
            ),
            onToken = onToken,
            cancelSignal = cancelSignal,
        )
    } else {
        throw e
    }
}
