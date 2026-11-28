package com.stragent.core.report

import com.stragent.core.ai.AgentFindingCard
import com.stragent.core.ai.ChatOptions
import com.stragent.core.ai.GenerationCancelled
import com.stragent.core.ai.chatStep
import com.stragent.core.ports.LlmClient
import com.stragent.core.verify.VerificationResult
import com.stragent.core.verify.countHardIssues
import com.stragent.core.verify.verifyReport

/**
 * 引擎无关的发稿门禁（D17 G-01 / AD-028 / T-044）。
 *
 * 为什么存在：`verifyReport` 曾只在编辑部路径与 `audit_report` 工具里被调用，默认引擎
 * `classic` 不过任何确定性门禁——产品「每个数字可回溯」的承诺在默认配置下不成立。
 * 本文件把三道确定性检查（逐数溯源 / 写作审计 / 语义审校）收成一条**与引擎无关**的
 * 后置管线：任何 `InsightResult` / 追问出口都要经过它，未来新增引擎自动继承。
 *
 * 纪律（与 AD-028 对齐）：
 *  - **硬问题**（未溯源数字 / 算式/关系/字段错误 / 套话≥3 / 矛盾主张）触发修订，
 *    最多 [MAX_GATE_REVISIONS] 轮；仍未清零 → `failed=true` + 结构化问题清单，
 *    **不静默放行**（报告照发，但宿主能看到 `gateFailed`）。
 *  - **软问题**（缺结构块 / 篇幅偏离 / 未覆盖发现 / 结论未溯源 / 定性断言证据缺失 / 待确认算式）
 *    只进 `issues` 与质量注记，不触发修订也不判失败。
 *  - 编辑部路径内部已有退稿循环：外层对它的门禁预算必须为 0（只评估、不再花钱），
 *    避免同一份报告被双重计费。
 *  - 追问回答是对话不是文档：只做**记录级**门禁（`assessAnswer`），不自动改写回答。
 *
 * 整个门禁是纯 Kotlin、确定性、无 LLM 调用；只有 `revise` 回调会花一次模型调用。
 */
const val MAX_GATE_REVISIONS = 2

/** 一条门禁问题（kind 稳定、可机读；line 是宿主/UI 直接展示的一行文本） */
data class GateIssue(val kind: String, val detail: String) {
    val line: String get() = "[$kind] $detail"
}

/** 一轮修订的产物（修订本身由调用方注入，门禁不依赖具体模型实现） */
data class GateRevision(
    val report: String,
    val totalTokens: Int = 0,
    val truncated: Boolean = false,
)

/** 门禁终态：passed = 硬问题已清零；issues 含软问题，始终非丢失 */
data class GateOutcome(
    val report: String,
    val failed: Boolean,
    val issues: List<GateIssue>,
    val revisions: Int,
    val totalTokens: Int = 0,
    val truncated: Boolean = false,
    val hardIssues: Int = 0,
)

/**
 * 报告门禁：`verify → revise(≤maxRevisions) → 写作审计 / 语义审校`。
 *
 * 每轮都跑全部确定性检查（修订可能引入新问题，只查一次修不干净）；
 * `revise` 抛 [GenerationCancelled] 时原样上抛（取消不是失败），其它异常按「修订失败」
 * 处理：保留当前报告并标记失败——已经写好的报告不能因为一次网络抖动而丢掉。
 *
 * @param targetChars 目标篇幅（null = 不做篇幅检查）
 * @param structured 是否结构化报告类型（决定是否检查四段结构块）
 * @param findings Agent 引擎的采编日志（经典路径为空；用于发现覆盖检查）
 * @param language 报告语言档位（T-173）：写作审计的套话词表与结构块标记按语言取
 */
fun runReportGate(
    report: String,
    brief: String,
    targetChars: Int? = null,
    structured: Boolean = false,
    findings: List<AgentFindingCard> = emptyList(),
    maxRevisions: Int = MAX_GATE_REVISIONS,
    cancelSignal: () -> Boolean = { false },
    onRound: ((round: Int, hardIssues: Int) -> Unit)? = null,
    language: ReportLanguage = ReportLanguage.ZH,
    revise: (issues: List<GateIssue>, currentReport: String, round: Int) -> GateRevision,
): GateOutcome {
    var current = report
    var revisions = 0
    var tokens = 0
    var truncated = false

    while (true) {
        val assessed = assessReport(current, brief, targetChars, structured, findings, language)
        if (assessed.hardIssues == 0 || revisions >= maxRevisions) {
            return GateOutcome(
                report = current,
                failed = assessed.hardIssues > 0,
                issues = assessed.issues,
                revisions = revisions,
                totalTokens = tokens,
                truncated = truncated,
                hardIssues = assessed.hardIssues,
            )
        }
        if (cancelSignal()) throw GenerationCancelled()
        onRound?.invoke(revisions + 1, assessed.hardIssues)
        val revised = try {
            revise(assessed.issues, current, revisions + 1)
        } catch (cancelled: GenerationCancelled) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        // 修订失败/空稿：保留已有报告，标记失败（不把一整份报告换成一个异常）
        if (revised == null || revised.report.isBlank()) {
            return GateOutcome(
                report = current,
                failed = true,
                issues = assessed.issues,
                revisions = revisions,
                totalTokens = tokens,
                truncated = truncated,
                hardIssues = assessed.hardIssues,
            )
        }
        current = revised.report
        tokens += revised.totalTokens
        truncated = truncated || revised.truncated
        revisions += 1
    }
}

/**
 * 追问回答的记录级门禁：只评估、不改写（对话回答被静默重写比带标记更糟）。
 * 调用方把 `failed` / `issues` 透传到回执，UI 与外部 agent 可据此提示「回答里
 * 有数字不在本次数据摘要里」。
 */
fun assessAnswer(answer: String, brief: String): GateOutcome {
    val verification = verifyReport(answer, brief)
    val review = reviewReport(answer, emptyList(), buildAllowedNumbers(brief))
    // T-192：追问回答同样过定性断言回链（软问题）
    val claimIssues = checkClaimTracing(answer, brief).map { GateIssue("untraced_claim", "定性断言证据缺失：$it") } // lint:allow zh-first（T-192 审计文案）
    val issues = numericIssues(verification) + claimIssues + review.issues.map { GateIssue(it.kind, it.detail) }
    val hard = countHardIssues(verification) + if (review.hard) 1 else 0
    return GateOutcome(
        report = answer,
        failed = hard > 0,
        issues = issues,
        revisions = 0,
        hardIssues = hard,
    )
}

private data class GateAssessment(val issues: List<GateIssue>, val hardIssues: Int)

private fun assessReport(
    report: String,
    brief: String,
    targetChars: Int?,
    structured: Boolean,
    findings: List<AgentFindingCard>,
    language: ReportLanguage = ReportLanguage.ZH,
): GateAssessment {
    val verification = verifyReport(report, brief)
    val audit = WritingAudit.audit(report, targetChars, structured, language)
    val review = reviewReport(report, findings, buildAllowedNumbers(brief))

    val issues = buildList {
        addAll(numericIssues(verification))
        verification.formula.forEach {
            add(GateIssue("formula", "算式待确认：数字 ${it.value} 出现在算式行但未对上授权值：${it.context}"))
        }
        audit.issues.forEach { add(GateIssue(it.kind, it.detail)) }
        review.issues.forEach { add(GateIssue(it.kind, it.detail)) }
        // T-192：定性断言实体级回链——软问题（显式降级不静默，不触发修订不计 hard）
        checkClaimTracing(report, brief).forEach { add(GateIssue("untraced_claim", "定性断言证据缺失：$it")) } // lint:allow zh-first（T-192 审计文案，与既有 gate 文案同源）
    }
    val hard = countHardIssues(verification) +
        (if (audit.hard) 1 else 0) +
        (if (review.hard) 1 else 0)
    return GateAssessment(issues, hard)
}

/** 数字类硬/软问题的统一措辞（与编辑部退稿意见同口径，便于日志对拍） */
private fun numericIssues(verification: VerificationResult): List<GateIssue> = buildList {
    verification.unsourced.forEach {
        add(GateIssue("unsourced_number", "数字 ${it.value}（${it.kind}）无法溯源：${it.context}"))
    }
    verification.arithmetic.forEach {
        add(GateIssue("arithmetic", "算式错误：写了 ${it.claimed}，应为 ${it.expected}：${it.context}"))
    }
    verification.relations.forEach {
        add(GateIssue("relation", "关系算不通：${it.pair} 声称 ${it.claimed}，应为 ${it.expected}：${it.context}"))
    }
    verification.fields.forEach {
        add(GateIssue("field", "数据里没有的字段名「${it.field}」：${it.context}"))
    }
}

/**
 * 报告修订的通用实现：把门禁问题清单交给模型，要求**只修问题、不重写**。
 *
 * 只给数据摘要 + 当前报告 + 问题清单，不重放整段生成提示词——修订是外科手术，
 * 重放生成提示词会让模型重新构思全篇（丢结构、丢已经写对的发现）。
 */
fun reviseReportWithLlm(
    llm: LlmClient,
    model: String,
    brief: String,
    currentReport: String,
    issues: List<GateIssue>,
    targetChars: Int? = null,
    cancelSignal: () -> Boolean = { false },
): GateRevision {
    val system = buildString {
        append("<role>你是报告修订编辑。给你一份已成稿的数据分析报告和确定性核查发现的问题清单。</role>\n")
        append("<task>逐条修复问题，不要重写全篇：\n")
        append("1. 数字无法溯源：删掉该数字，或换成数据摘要里能查到的数字；\n")
        append("2. 算式/关系算不通：按摘要里的数字重算并改正；\n")
        append("3. 字段名错误：改成摘要里真实存在的列名，或删掉该句；\n")
        append("4. 矛盾主张：保留与摘要一致的那一个口径；\n")
        append("5. 套话/缺结构块：按问题清单改成人话、补齐结构块；\n")
        append("6. 定性断言证据缺失：句子里的主体（如地区/品类名）不在数据摘要里——删除该判断，或改成摘要里有依据的表述。\n") // lint:allow zh-first（T-192 修订指令）
        append("不要新增摘要里没有的数字，不要改章节骨架，不要解释你做了什么。")
        targetChars?.let { append("\n目标篇幅约 $it 字，修订后仍应保持这个量级。") }
        append("</task>\n只输出修订后的完整报告 Markdown 全文。")
    }
    val user = buildString {
        append("<data_brief>\n").append(brief).append("\n</data_brief>\n\n")
        append("<current_report>\n").append(currentReport).append("\n</current_report>\n\n")
        append("<gate_issues>\n")
        issues.forEach { append("- ").append(it.line).append('\n') }
        append("</gate_issues>")
    }
    val inputText = system + "\n" + user
    val result = chatStep(
        llm, model,
        listOf("system" to system, "user" to user),
        ChatOptions(
            temperature = 0.2,
            maxTokens = outputCeilingFor(targetChars ?: currentReport.length, inputText),
        ),
        cancelSignal = cancelSignal,
    )
    return GateRevision(
        report = result.content.trim(),
        totalTokens = result.totalTokens,
        truncated = result.truncated,
    )
}
