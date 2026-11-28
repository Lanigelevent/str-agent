package com.stragent.agent

/**
 * 插件 kind 的输出契约（T-058）。
 *
 * 原先 `AgentLoop.finishSpec(kind)` 用 `when (kind) { "followup" -> …; "review" -> … }`
 * 决定 finish 的参数形状——新增一种 agent 就得改这个分发点。现在 kind 的行为是
 * **登记表里的一行数据**：`kind → FinishShape + 提示文案`，分发点只查表、不认字符串。
 *
 * 扩展方式：`PluginKinds.register(PluginKindSpec("my-kind", FinishShape.REPORT, "args.report = …"))`，
 * 随后 `PluginManifest(kind = "my-kind")` 即可通过校验并被 AgentLoop 正确收尾。
 * 报告引擎另有 [ReportEngineRegistry]（kind → 引擎实现），两者解耦：
 * 一个 kind 可以没有报告引擎（如 followup），也可以有多个引擎变体。
 */
enum class FinishShape {
    /** finish.args.report = 全文（report / editorial） */
    REPORT,

    /** finish.args.answer = 两三句话（followup） */
    ANSWER,

    /** finish.args.issues = 审查发现清单（review） */
    ISSUES,
}

/** 一种插件 kind 的收尾契约 */
data class PluginKindSpec(
    val id: String,
    val finishShape: FinishShape,
    /** finish 工具的 args 说明（模型看到的原文） */
    val finishArgsHint: String,
)

object PluginKinds {
    private val specs = linkedMapOf(
        "report" to PluginKindSpec("report", FinishShape.REPORT, "args.report = 完整 Markdown 报告全文"),
        "editorial" to PluginKindSpec(
            "editorial",
            FinishShape.REPORT,
            "args.report = 完整 Markdown 报告全文（主编 finish 时提交计划即结束，正文由主笔汇总）",
        ),
        "followup" to PluginKindSpec("followup", FinishShape.ANSWER, "args.answer = 回答正文（两三句话，纯文本）"),
        "review" to PluginKindSpec("review", FinishShape.ISSUES, "args.issues = [{kind,quote,why}]（审查发现清单）"),
    )

    /** 登记一种 kind（重复登记 = 覆盖；仅代码可调，用户插件不能自造 kind） */
    @Synchronized
    fun register(spec: PluginKindSpec) {
        specs[spec.id] = spec
    }

    /**
     * 摘除一种 kind（internal：仅测试清理用，不进公开 API/ABI）。
     * D25 审查：全局可变注册表此前没有清理通道——测试登记的 kind 全程驻留，
     * 任何后续同名登记会静默覆盖内建 spec（执行顺序耦合的假状态源）。
     */
    @Synchronized
    internal fun unregisterForTest(id: String) {
        specs.remove(id)
    }

    @Synchronized
    fun of(id: String): PluginKindSpec? = specs[id]

    @Synchronized
    fun all(): List<PluginKindSpec> = specs.values.toList()

    @Synchronized
    fun ids(): Set<String> = specs.keys.toSet()
}
