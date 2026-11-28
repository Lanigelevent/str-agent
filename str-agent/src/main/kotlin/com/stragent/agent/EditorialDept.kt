package com.stragent.agent

import com.stragent.core.brief.buildDatasetBrief
import com.stragent.core.dataset.Dataset
import com.stragent.core.report.ChapterSpec
import com.stragent.core.report.TargetLength
import com.stragent.core.report.WritingAudit
import com.stragent.core.report.isStructuredReportType
import com.stragent.core.report.planChapters
import com.stragent.core.ai.AgentChartCard
import com.stragent.core.ai.AgentFindingCard
import com.stragent.core.verify.countHardIssues
import com.stragent.core.verify.verifyReport
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.PrefsStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 一个记者条线的执行结果（并行收集后按条线声明序合并） */
private data class BeatRun(val beat: com.stragent.agent.EditorialBeat, val outcome: AgentOutcome?)

/**
 * 编辑部多层级分工 —— 报社/出版社机制的多 Agent 落法（multi-agent planner/executor/
 * critic 协作模式的行业化命名）：
 *
 *   ① 主编选题会（Chief）  不动手，只拆题：submit_plan 登记各条线的采访任务
 *   ② 记者（Reporters）    每人一个条线（分组/时序/质量/相关…），用工具查证，
 *                          发现 record_finding 进**全社共享的采访日志**
 *   ③ 主笔（Writer）       汇编采编日志成稿，finish.args.report = 全文；
 *                          插件开了 chaptered 且目标 ≥2000 字时**分章成稿**再拼接
 *   ④ 资料核查（Fact-check）确定性步骤：verifyReport 逐数溯源 + WritingAudit 写作审计
 *                          ——**都不是 LLM**，硬伤清单退回主笔修（退稿机制），最多 2 轮
 *   ⑤ 主编终审（Chief）    看成稿与日志，publish / 退稿一次（改后必发）
 *
 * 与单 Agent 的区别：分工让每条线的提示词极窄（记者不需要懂风格，主笔不需要懂
 * 查证），共享日志替代单一超长上下文；核查层是确定性代码，无 LLM-as-judge 偏差。
 *
 * 主笔的提示词纪律（T-032）：完整注入 `writingDisciplineBlocks(prefs)`——行业框架 /
 * 报告类型 / 文风 / 去 AI 味 / 专业成文规范 / 方法论与派生统计纪律——与经典单发路径
 * 同一份（此前主笔只有六行浓缩版，用户选的风格与行业库在编辑部报告里失效）。
 * 记者保持窄提示词不动：他们只查证，不管文笔。
 *
 * 全程复用 AgentLoop（每名员工就是一个受限的单 agent），产物统一 AgentOutcome：
 * plan = 主编计划，findings = 全社采编日志，output.report = 终稿。
 *
 * T-050：每个阶段（选题会 / 每条记者线 / 每轮主笔 / 主编终审）各自落 checkpoint，
 * 已完成阶段在进程重启后直接回放、不再重复计费；单条记者线失败只丢该条线——
 * 其余已完成的发现照常合并，缺口写进 `<editorial_gaps>` 让主笔如实交代，
 * 并作为 `[coverage]` 注记回传宿主。全部条线都失败才回落经典流水线。
 */
class EditorialDept(
    private val llm: LlmClient,
    /** 报告类型登记表（T-145）：影响分章模板；默认 `BUILT_IN` = 行为不变 */
    private val types: com.stragent.core.report.ReportTypeRegistry = com.stragent.core.report.ReportTypeRegistry.BUILT_IN,
) {

    fun run(
        dataset: Dataset,
        brief: String,
        target: TargetLength,
        typeId: String,
        prefs: PrefsStore,
        team: PluginManifest,
        caliberNotes: List<com.stragent.core.caliber.CaliberNote>,
        model: String,
        cancelSignal: () -> Boolean = { false },
        onStep: ((AgentStep) -> Unit)? = null,
        onPhase: ((String) -> Unit)? = null,
        memory: List<com.stragent.core.memory.MemoryFact> = emptyList(),
        semantic: List<com.stragent.core.semantic.SemanticTerm> = emptyList(),
        search: com.stragent.core.ports.SearchClient? = null,
        /** 阶段断点（T-050）：null = 不落盘，既有调用点零行为差异 */
        checkpoints: AgentRunCheckpoints? = null,
    ): AgentOutcome? {
        val registry = ToolRegistry.of(buildAgentTools(dataset, brief, search = search))
        fun stage(name: String): AgentCheckpoint = checkpoints?.stage(name) ?: AgentCheckpoint.NONE
        val caliber = com.stragent.core.caliber.caliberBlock(caliberNotes)
        // 记忆块独立成段（core/memory 纪律 a）：记者与主笔都要看得见「此前会话做过什么」，
        // 只给单 Agent 引擎加就变成「换了引擎就失忆」。
        val memoryText = com.stragent.core.memory.memoryBlock(memory) ?: ""
        // 语义层独立成段（core/semantic 纪律 a）：跨数据集指标定义，按列名匹配后注入。
        val semanticText = com.stragent.core.semantic.matchTermsForColumns(
            semantic, dataset.columns,
        ).let { com.stragent.core.semantic.semanticBlock(it) } ?: ""
        var totalTokens = 0
        var toolCalls = 0
        val allSteps = mutableListOf<AgentStep>()
        val allTrace = mutableListOf<AgentTraceEntry>()
        val journal = mutableListOf<AgentFindingCard>()
        val journalCharts = mutableListOf<AgentChartCard>()
        var plan: List<String> = emptyList()
        /** 未完成的记者条线（T-050：部分成功合并——只丢失败条线，不整体回落） */
        val failedBeats = mutableListOf<String>()

        fun staffManifest(staffId: String, staffName: String, prompt: String, tools: List<String>?, steps: Int = team.maxSteps) =
            PluginManifest(
                id = "${team.id}.$staffId",
                name = staffName,
                kind = "report",
                systemPrompt = prompt,
                tools = tools,
                model = team.model,
                temperature = team.temperature,
                maxSteps = steps,
            )

        /** 主笔的 system 提示词：插件 writerPrompt + 完整写作纪律栈（与经典路径同一份）+ 本轮任务指令 */
        fun writerSystem(reviewNotes: String?): String =
            (team.writerPrompt ?: "") +
                com.stragent.core.ai.writingDisciplineBlocks(prefs) +
                "\n（你是主笔。基于下面的采编日志与数据摘要写完整报告；" +
                "每个数字都必须来自日志或摘要；写完必须先 audit_report 自检并修掉硬伤，再 finish。" +
                (reviewNotes?.let { "\n**主编/审校退稿意见（必须逐条处理）**：\n$it\n" } ?: "") +
                "finish.args.report = 完整 Markdown 报告全文。）"

        /** 采编日志块（记者的发现是主笔的骨架） */
        fun journalBlock(): String = buildString {
            append("\n\n<editorial_journal>\n")
            if (journal.isEmpty()) append("（记者尚无记录——基于摘要可直接支撑的部分成稿，其余直说数据不足。）")
            else journal.forEachIndexed { index, finding ->
                append("${index + 1}. ${finding.title}\n${finding.detail}")
                if (finding.evidence.isNotEmpty()) append("（依据：${finding.evidence}）")
                append("\n")
            }
            append("</editorial_journal>")
        }

        /** 图表任务块（T-032 修复：此前主笔根本看不见日志里的图表，{{chart:N}} 引用从不出现） */
        fun chartsBlock(): String {
            if (journalCharts.isEmpty()) return ""
            val lines = journalCharts.take(5).mapIndexed { index, chart ->
                "${index + 1}. ${chart.title}（引用标记：{{chart:${index + 1}}}）"
            }
            return "\n\n<editorial_charts>日志共记录 ${journalCharts.size} 张图表，正文可在合适位置引用（全文 ≤5 张，没有合适位置就不引）：\n" +
                lines.joinToString("\n") + "\n</editorial_charts>"
        }

        /** 计划要点块 */
        fun planBlock(): String =
            if (plan.isEmpty()) ""
            else buildString {
                append("\n\n<editorial_plan>本报告应覆盖以下计划要点：\n")
                plan.forEachIndexed { index, step -> append("${index + 1}. $step\n") }
                append("</editorial_plan>")
            }

        /** 掉队条线块（T-050）：主笔必须如实说明覆盖缺口，不能替失败条线编结论 */
        fun gapsBlock(): String =
            if (failedBeats.isEmpty()) ""
            else buildString {
                append("\n\n<editorial_gaps>\n以下记者条线未能完成（工具或协议故障）：")
                append(failedBeats.joinToString("、"))
                append("。报告必须在「数据与局限」部分如实说明这些角度未覆盖，不得编造其结论。\n</editorial_gaps>")
            }

        /** 主笔任务 = 数据摘要 + 口径 + 记忆 + 采编日志 + 图表 + 计划 + 覆盖缺口 */
        fun writerTask(): String = buildString {
            append(dataTask(dataset, brief, target, typeId, caliber, memoryText, semanticText))
            append(journalBlock())
            append(chartsBlock())
            append(planBlock())
            append(gapsBlock())
        }

        // ---- ① 主编选题会 ----
        onPhase?.invoke("主编选题会")
        val chiefPlan = staffManifest(
            "chief-plan", "${team.name} · 主编",
            team.systemPrompt + "\n（你现在的任务：召开选题会。用 submit_plan 提交 " +
                "${(team.beats.size + 2).coerceAtMost(8)} 步以内的分析计划（每步一句话，指到条线与问题），" +
                "然后立即 finish（args 传空对象即可）。计划会交给各条线记者执行。）",
            tools = emptyList(),
            steps = 3,
        )
        val planOutcome = AgentLoop(llm).run(
            chiefPlan, registry, team.model ?: model, dataTask(dataset, brief, target, typeId, caliber, memoryText, semanticText), AGENT_HARNESS_CONTRACT, cancelSignal, onStep,
            checkpoint = stage("chief-plan"), keepOnFinish = true,
        ) ?: return null
        totalTokens += planOutcome.totalTokens
        allSteps += planOutcome.steps
        allTrace.addAll(planOutcome.trace)
        plan = planOutcome.plan

        // ---- ② 记者（各条线**并行**采访，共享日志按条线序合并——顺序确定，谁先完成不影响）----
        if (cancelSignal()) throw AgentCancelled()
        onPhase?.invoke("记者并行采访 · ${team.beats.size} 条线")
        // T-048：进度处理器跟着协程上下文走——否则 IO 线程上没有调用线程的 ThreadLocal，
        // 6 条线并行的 onStep/阶段进度会静默丢失（只有 UI 广播还在）。
        val progressContext = ProgressBus.asContextElement()
        val beatRuns: List<BeatRun> = kotlinx.coroutines.runBlocking {
            val deferredJobs = team.beats.take(6).mapIndexed { beatIndex, beat ->
                async(kotlinx.coroutines.Dispatchers.IO + progressContext) {
                    val reporterPrompt = beat.prompt +
                        "\n（你是「${beat.name}」条线的记者。用工具查证并把值得写的发现逐条 record_finding" +
                        "（title = 一句话结论；detail = 数据依据与机制；evidence = 列/分组/行号）。" +
                        "查完即 finish，args.report 用一句话交代本条线小结。）"
                    val reporter = staffManifest(
                        "reporter-$beatIndex", "${team.name} · 记者/${beat.name}",
                        reporterPrompt, beat.tools,
                    )
                    if (cancelSignal()) throw AgentCancelled()
                    BeatRun(beat, AgentLoop(llm).run(
                        reporter, registry, team.model ?: model, dataTask(dataset, brief, target, typeId, caliber, memoryText, semanticText), AGENT_HARNESS_CONTRACT, cancelSignal, onStep,
                        checkpoint = stage("beat-$beatIndex"), keepOnFinish = true,
                    ))
                }
            }
            deferredJobs.map { job -> job.await() }
        }
        for (run in beatRuns) {
            val outcome = run.outcome
            if (outcome == null) {
                // T-050：单条线失败只丢该条线，已完成部分照常合并（全部失败才回落）
                failedBeats += run.beat.name
                continue
            }
            totalTokens += outcome.totalTokens
            toolCalls += outcome.toolCalls
            allSteps += outcome.steps
            allTrace.addAll(outcome.trace)
            journal.addAll(outcome.findings)
            journalCharts.addAll(outcome.charts) // 按条线声明序合并——顺序确定，与完成先后无关
        }
        // 取消不回落：即使恰好赶上条线失败，也按取消处理
        if (cancelSignal()) throw AgentCancelled()
        if (failedBeats.size == beatRuns.size) return null // 全部条线都空手：编辑部无米下锅
        if (failedBeats.isNotEmpty()) {
            onPhase?.invoke(
                "记者部分完成：${beatRuns.size - failedBeats.size}/${beatRuns.size} 条线，" +
                    "${failedBeats.size} 条未完成（${failedBeats.joinToString("、")}）",
            )
        }

        // ---- ③ 主笔成稿（采编日志汇编；chaptered 插件在长报告下逐章成稿）----
        val chaptered = team.chaptered && isStructuredReportType(typeId) && target.chars >= CHAPTERED_MIN_CHARS
        var report: String? = null
        var revision = 0
        var reviewNotes: String? = null
        var finalAudit: WritingAudit.Result? = null
        var finalReview: com.stragent.core.report.ReviewResult? = null

        while (revision <= MAX_REVISIONS) {
            val draft: String
            if (revision == 0 && chaptered) {
                val chapterPlan = planChapters(brief, target.chars, typeId, types)
                onPhase?.invoke("主笔分章成稿 · ${chapterPlan.chapters.size} 章")
                val outline = chapterPlan.chapters.mapIndexed { i, c -> "${i + 1}. ${c.title}" }.joinToString("\n")
                val parts = mutableListOf<String>()
                for ((index, chapter) in chapterPlan.chapters.withIndex()) {
                    if (cancelSignal()) throw AgentCancelled()
                    val writer = staffManifest(
                        "writer-ch$index", "${team.name} · 主笔/第${index + 1}章",
                        (team.writerPrompt ?: "") + com.stragent.core.ai.writingDisciplineBlocks(prefs) +
                            chapterNote(chapter, index, chapterPlan.chapters.size, outline, chapterPlan.totalChars, target.chars),
                        tools = null,
                        steps = CHAPTER_STEPS,
                    )
                    val outcome = AgentLoop(llm).run(
                        writer, registry, team.model ?: model, writerTask(), AGENT_HARNESS_CONTRACT, cancelSignal, onStep,
                        checkpoint = stage("writer-ch$index"), keepOnFinish = true,
                    ) ?: return null
                    totalTokens += outcome.totalTokens
                    allSteps += outcome.steps
                    allTrace.addAll(outcome.trace)
                    val part = outcome.output["report"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content?.trim()
                    if (part.isNullOrBlank()) return null
                    parts += part
                }
                draft = parts.joinToString("\n\n")
            } else {
                onPhase?.invoke(if (revision == 0) "主笔成稿" else "主笔修订（第 $revision 轮）")
                val writer = staffManifest(
                    "writer-$revision", "${team.name} · 主笔",
                    writerSystem(reviewNotes), tools = null, // 全部工具（含 audit_report）
                )
                val outcome = AgentLoop(llm).run(
                    writer, registry, team.model ?: model, writerTask(), AGENT_HARNESS_CONTRACT, cancelSignal, onStep,
                    checkpoint = stage("writer-r$revision"), keepOnFinish = true,
                ) ?: return null
                totalTokens += outcome.totalTokens
                toolCalls += outcome.toolCalls
                allSteps += outcome.steps
                allTrace.addAll(outcome.trace)
                val text = outcome.output["report"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content?.trim()
                if (text.isNullOrBlank()) return null
                draft = text
            }
            report = draft

            // ---- ④ 资料核查（确定性退稿：verifyReport 逐数溯源 + WritingAudit 写作审计 + ReviewerAgent 语义审校，都不是 LLM）----
            onPhase?.invoke("资料核查")
            val verification = verifyReport(draft, brief)
            val audit = WritingAudit.audit(
                draft, target.chars, isStructuredReportType(typeId),
                // T-173：写作审计的语言档位（词表与结构块标记按语言取）
                com.stragent.core.report.ReportLanguage.from(prefs.get(com.stragent.core.report.ReportLanguage.PREF_KEY, "zh")),
            )
            finalAudit = audit
            val allowedNumbers = com.stragent.core.report.buildAllowedNumbers(brief)
            val review = com.stragent.core.report.reviewReport(draft, journal.toList(), allowedNumbers)
            finalReview = review
            val hard = countHardIssues(verification)
            if (hard == 0 && !audit.hard && !review.hard) break
            if (revision == MAX_REVISIONS) break // 修不完：照发，警示块由 UI 的语义/溯源显示层呈现
            revision += 1
            // 退稿意见**全量分桶**（T-032 修复：此前数字截 8 条、算式截 3 条，硬伤多时修不完整）
            val numericNotes = verification.unsourced.map { "数字 ${it.value}（${it.kind}）无法溯源" } +
                verification.arithmetic.map { "算式错误：写了 ${it.claimed} 应为 ${it.expected}" }
            val writingNotes = audit.issues.map { "[${it.kind}] ${it.detail}" }
            val reviewNotes0 = review.issues.map { "[${it.kind}] ${it.detail}" }
            reviewNotes = (numericNotes + writingNotes + reviewNotes0).joinToString("\n") { "- $it" }
            onPhase?.invoke("审校退稿（第 $revision 轮：$hard 处数字硬伤 + ${audit.bannedHits} 处套话）")
        }
        val finalReport = report ?: return null

        // ---- ⑤ 主编终审（publish / 退稿一次）----
        onPhase?.invoke("主编终审")
        val chiefReview = staffManifest(
            "chief-review", "${team.name} · 主编终审",
            team.systemPrompt + "\n（你现在的任务：终审。对照采编日志审这篇成稿的口径一致性、结构完整性与" +
                "有没有互相矛盾的结论。输出一个 JSON：{\"tool\":\"finish\",\"args\":{\"decision\":\"publish\"|\"revise\"," +
                "\"notes\":\"退稿意见（publish 时可空）\"}}。决策必须克制：能发就发，只有口径矛盾才退。）",
            tools = emptyList(),
            steps = 3,
        )
        val reviewTask = buildString {
            append("<task>终审以下成稿。</task>\n\n<draft>\n$finalReport\n</draft>\n")
            append("\n<editorial_journal>\n" + journal.joinToString("\n") { "- ${it.title}" } + "\n</editorial_journal>")
        }
        val reviewOutcome = AgentLoop(llm).run(
            chiefReview, registry, team.model ?: model, reviewTask, AGENT_HARNESS_CONTRACT, cancelSignal, onStep,
            checkpoint = stage("chief-review"), keepOnFinish = true,
        )
        var chiefNotes: String? = null
        if (reviewOutcome != null) {
            totalTokens += reviewOutcome.totalTokens
            allSteps += reviewOutcome.steps
            allTrace.addAll(reviewOutcome.trace)
            val decision = reviewOutcome.output["decision"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
            chiefNotes = reviewOutcome.output["notes"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
            if (decision == "revise" && !chiefNotes.isNullOrBlank()) {
                onPhase?.invoke("主编退稿：${chiefNotes.take(40)}")
                val revised = runCatching {
                    AgentLoop(llm).run(
                        staffManifest("writer-chiefrev", "${team.name} · 主笔", writerSystem("主编退稿意见：$chiefNotes"), tools = null),
                        registry, team.model ?: model, writerTask() +
                            "\n\n<current_draft>\n$finalReport\n</current_draft>",
                        AGENT_HARNESS_CONTRACT, cancelSignal, onStep,
                        checkpoint = stage("writer-chiefrev"), keepOnFinish = true,
                    )
                }.getOrNull()
                if (revised != null) {
                    totalTokens += revised.totalTokens
                    allSteps += revised.steps
                    allTrace.addAll(revised.trace)
                    val revisedReport = revised.output["report"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
                    if (!revisedReport.isNullOrBlank()) report = revisedReport.trim()
                }
            }
        }

        return AgentOutcome(
            output = buildJsonObject { put("report", report ?: "") },
            steps = allSteps,
            totalTokens = totalTokens,
            toolCalls = toolCalls,
            plan = plan,
            findings = journal.toList(),
            charts = journalCharts.toList(),
            trace = allTrace,
            // 写作审计注记 + 审校注记（软提示：缺结构块/篇幅偏离/未覆盖发现不退稿但宿主可见）
            qualityNotes = (finalAudit?.issues ?: emptyList()).map { "[${it.kind}] ${it.detail}" } +
                (finalReview?.issues ?: emptyList()).map { "[${it.kind}] ${it.detail}" } +
                failedBeats.map { "[coverage] 条线「$it」未完成，报告已合并其余条线（覆盖缺口需人工留意）" },
            // T-044：内部退稿轮数交给外层门禁记账（外层预算为 0，只评估不重修）
            gateRevisions = revision,
        )
    }

    /** 分章模式下单章的定位说明（骨架来自 planChapters，程序定结构、模型只填内容） */
    private fun chapterNote(
        chapter: ChapterSpec,
        index: Int,
        total: Int,
        outline: String,
        planTotalChars: Int,
        targetChars: Int,
    ): String =
        "\n\n<chapter_position>你在写一份分章生成的长报告的**第 ${index + 1} 章 / 共 $total 章**。\n" +
            "全篇章节骨架：\n$outline\n" +
            "**只写「${chapter.title}」这一章**：不要写其他章的内容，不要重复前面章节讲过的发现，" +
            "也不要预告还没写的章节。你的输出会与其他章直接拼接——不要全篇的开场白与总结段，" +
            "不要写「本报告」这类指代全篇的说法（用「本章」）。\n" +
            "本章目标篇幅约 ${chapter.chars} 字（全篇目标约 $targetChars 字）；支撑不了就短着写，" +
            "宁可少一条发现也不要注水。\n" +
            "本章要点：${chapter.focus}\n" +
            "本章成稿后**直接 finish**（args.report = 本章 Markdown 全文）；" +
            "audit_report 在全文拼接后统一执行，本章不需要调用。</chapter_position>"

    private fun dataTask(
        dataset: Dataset,
        brief: String,
        target: TargetLength,
        typeId: String,
        caliber: String?,
        memory: String = "",
        semantic: String = "",
    ): String =
        buildString {
            append("<task>报告类型：$typeId；目标篇幅约 ${target.chars} 字。</task>\n\n")
            append("<data_brief>\n$brief\n</data_brief>\n")
            if (caliber != null) append("\n$caliber\n")
            if (memory.isNotEmpty()) append("\n$memory\n")
            if (semantic.isNotEmpty()) append("\n$semantic\n")
        }

    companion object {
        /** 审校退稿上限（确定性核查的修复轮数；修不完照发、警示块照显） */
        const val MAX_REVISIONS = 2

        /** 分章成稿的目标字数下限：低于此值一次成稿更划算 */
        const val CHAPTERED_MIN_CHARS = 2000

        /** 每章的 agent 步数预算：一次产出、一次 finish（不需要查证——查证是记者的事） */
        const val CHAPTER_STEPS = 2
    }
}
