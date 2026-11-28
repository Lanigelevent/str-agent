package com.stragent.agent

import com.stragent.core.ai.AgentChartCard
import com.stragent.core.ai.AgentFindingCard
import com.stragent.core.ai.ChatOptions
import com.stragent.core.ai.ChatResult
import com.stragent.core.ai.chatStep
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.ToolCall
import com.stragent.core.ports.ToolSpec
import com.stragent.core.ports.TurnMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Agent 循环 —— JSON 文本协议（不依赖任何服务商的 function-calling）。
 *
 * 每轮模型只回一个 JSON 对象：
 *   {"thought":"…","tool":"工具名","args":{…}}      → 执行工具，结果回填
 *   {"tool":"finish","args":{…}}                    → 终结（args 形状由插件 kind 定）
 *
 * 结果回填用 <tool_result> 标签的 user 消息（/chat/completions 唯一通用通道）。
 * 三道护栏：
 *   ① 解析失败 → 修复提示重试至多 2 轮 → 仍失败返回 null（调用方回落经典流水线）
 *   ② 步数预算 maxSteps → 最后一轮强制要求 finish → 仍不出 → null
 *   ③ cancelSignal 每步检查 → 抛 AgentCancelled（调用方决定如何呈现中断）
 *
 * 工具结果与最终产物都受"每个数字可回溯"纪律约束：回填文本超长截断，
 * finish 的 args 原样返回给调用方（报告仍会过 verifyReport/打码，不属于本层）。
 */
data class AgentStep(val tool: String, val argsSummary: String, val resultSummary: String, val ok: Boolean)

/**
 * 单次调用轨迹条目（对标 DeepSeek Harness 的 Trajectory/append-only session log）。
 * 记录每步的 thought / 工具 / 入参摘要 / 成败 / 错误，供回放、排障与「每个数字可回溯」审计。
 */
data class AgentTraceEntry(
    val step: Int,
    val tool: String,
    val thought: String,
    val ok: Boolean,
    val error: String?,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("step", step)
        put("tool", tool)
        if (thought.isNotEmpty()) put("thought", thought.take(200))
        put("ok", ok)
        if (error != null) put("error", error.take(200))
    }
}

/** 发现卡 / 图表卡类型下沉到 core.ai（AgentDataTypes.kt），本模块不再重复定义——保持单一事实源 */
data class AgentOutcome(
    /** finish 工具的 args（report / answer / issues 由插件 kind 决定） */
    val output: JsonObject,
    val steps: List<AgentStep>,
    val totalTokens: Int,
    val toolCalls: Int,
    /** submit_plan 提交的分析计划（Plan-and-Execute 的计划层，UI 展示） */
    val plan: List<String> = emptyList(),
    /** record_finding 记录的发现卡（scratchpad 日志） */
    val findings: List<AgentFindingCard> = emptyList(),
    /** suggest_chart 建议的图表（UI 渲染进报告） */
    val charts: List<AgentChartCard> = emptyList(),
    /** 可观测轨迹（每步 thought/tool/ok/error；UI 回放与溯源审计用） */
    val trace: List<AgentTraceEntry> = emptyList(),
    /** 写作审计注记（T-032：WritingAudit 的软提示——缺结构块/篇幅偏离；硬项已走退稿循环） */
    val qualityNotes: List<String> = emptyList(),
    /** 内部发稿门禁已执行的修订轮数（T-044：编辑部内部退稿轮，供外层门禁记账，避免双重计入） */
    val gateRevisions: Int = 0,
)

/** 取消契约异常真身在 core/ports（T-148 下移），经包内垫片别名可见 */

/** 工具结果回填的字符上限：太长会淹没模型上下文 */
private const val TOOL_RESULT_LIMIT = 6000

/** 解析失败的修复重试上限 */
private const val MAX_REPAIRS = 2

/** 原生模式：模型只说话不调工具时的纠正提示（与文本协议的「修复提示」同职责） */
private const val NATIVE_FINISH_PROMPT =
    "上一次回复没有调用任何工具。如果分析已完成，请立即调用 finish 提交最终产物（args 形状见任务说明）；" +
        "如果还需要数据，请调用相应工具。不要用普通文本结束任务。"

/** 步数预算见底的收尾指令（原生 / 文本各一句；续跑时按最后一条消息去重） */
private const val NATIVE_LIMIT_PROMPT =
    "已达到工具调用步数上限。请立即调用 finish 提交最终产物，不要再调用其他工具。"
private const val TEXT_LIMIT_PROMPT =
    "已达到工具调用步数上限。请立即输出 {\"tool\":\"finish\",\"args\":{…}}，" +
        "基于已获得的信息完成产物，不要再调用其他工具。"

/**
 * 与 `data/StepfunConfig.STEP_ERR_UNSUPPORTED` 同值：str-agent **不依赖 data**
 * （依赖方向纪律），这里只借字符串口径，不引实现。
 */
private const val STEP_ERR_UNSUPPORTED = "unsupported"

/** 原生循环的结局：完成 / 端点不支持（回落文本协议时带上已付费的首轮回复） */
private sealed interface NativeRun {
    data class Done(val outcome: AgentOutcome) : NativeRun
    data class Unsupported(val firstReply: String, val tokens: Int) : NativeRun
}

/**
 * 原生模式的状态工具声明（submit_plan / record_finding / suggest_chart）——
 * 与文本循环内建处理的同名工具一一对应，schema 即模型看到的能力面。
 */
private fun stateToolSpecs(): List<ToolSpec> = listOf(
    ToolSpec(
        name = "submit_plan",
        description = "登记分析计划（3–8 步）：先提交计划再执行，UI 会逐步展示给用户。",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("steps", buildJsonObject {
                    put("type", "array")
                    put("description", "按顺序排列的分析步骤（3–8 步）")
                    put("items", buildJsonObject { put("type", "string") })
                })
            })
            put("required", buildJsonArray { add("steps") })
        },
    ),
    ToolSpec(
        name = "record_finding",
        description = "记录一条发现（含数据依据）：写报告时从发现日志汇编，避免边查边忘。",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("title", buildJsonObject { put("type", "string"); put("description", "一句话发现") })
                put("detail", buildJsonObject { put("type", "string"); put("description", "数据细节（数字须可溯源）") })
                put("evidence", buildJsonObject { put("type", "string"); put("description", "证据：列/分组/行号") })
            })
            put("required", buildJsonArray { add("title"); add("detail") })
        },
    ),
    ToolSpec(
        name = "suggest_chart",
        description = "建议一张图表（bar|line），正文用 {{chart:序号}} 引用，会被渲染成实际图表；上限 5 张。",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("type", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("bar"); add("line") })
                })
                put("title", buildJsonObject { put("type", "string") })
                put("data", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("label", buildJsonObject { put("type", "string") })
                            put("value", buildJsonObject { put("type", "number") })
                        })
                        put("required", buildJsonArray { add("label"); add("value") })
                    })
                })
                put("description", buildJsonObject { put("type", "string") })
            })
            put("required", buildJsonArray { add("type"); add("title"); add("data") })
        },
    ),
)

/** finish 的 args 形状由插件 kind 的登记契约决定（查表，不再按 kind 字符串分支） */
private fun finishSpec(kind: String): ToolSpec {
    val shape = PluginKinds.of(kind)?.finishArgsHint ?: "args.report = 完整 Markdown 报告全文"
    return ToolSpec(
        name = "finish",
        description = "结束任务并提交最终产物：$shape。不要用普通文本结束任务。",
        parameters = buildJsonObject {
            put("type", "object")
            put("description", shape)
            put("additionalProperties", true)
        },
    )
}

class AgentLoop(private val client: LlmClient) {

    /**
     * @param task 用户任务包（brief / 问题 / 报告原文——由调用方按 kind 组装）
     * @param harnessContract 固定的 harness 系统契约（协议说明）
     * 插件提示词取 manifest.systemPrompt（调用方不用二次传递）。
     */
    fun run(
        manifest: PluginManifest,
        registry: ToolRegistry,
        model: String,
        task: String,
        harnessContract: String,
        cancelSignal: () -> Boolean = { false },
        onStep: ((AgentStep) -> Unit)? = null,
        /** 断点句柄（T-050）：默认 NONE = 不落盘，既有调用点零行为差异 */
        checkpoint: AgentCheckpoint = AgentCheckpoint.NONE,
        /**
         * 完成态是否保留：true = 写 completed 状态，下一次同任务直接回放（不重打模型）；
         * false = 完成即删。编辑部各阶段用 true（崩溃后已完成阶段不重复计费），
         * 调用方在整次任务成功过门禁后统一清理。
         */
        keepOnFinish: Boolean = false,
    ): AgentOutcome? {
        val resolvedModel = manifest.model ?: model
        val signature = checkpointSignature(resolvedModel, manifest, task, harnessContract)
        val saved = checkpoint.load()
        val state = saved?.takeIf { candidate ->
            candidate.int("version") == AgentCheckpointStore.VERSION && candidate.text("signature") == signature
        }
        // 旧版本 / 换了任务或模型 / 提示词改过：旧状态一律作废，绝不把上一份任务接过来
        if (saved != null && state == null) checkpoint.clear()

        if (state != null && state.bool("completed") == true) {
            val replayed = runCatching { AgentCheckpointCodec.decodeCompleted(state) }.getOrNull()
            if (replayed != null) {
                if (!keepOnFinish) checkpoint.clear()
                return replayed
            }
            checkpoint.clear() // 完成态损坏：当作没有，重新跑
        }
        val resumed = state?.takeIf { it.bool("completed") != true }
        val savedMode = resumed?.text("mode")

        // 协议模式跟着**已落盘的那次执行**走：上次是文本协议，即使现在端点支持
        // tools 也不能把半截文本会话换成原生消息序列（反之亦然）。
        if (resumed != null && savedMode == "text") {
            return runText(
                manifest, registry, resolvedModel, task, harnessContract, cancelSignal, onStep,
                checkpoint = checkpoint, keepOnFinish = keepOnFinish, signature = signature, resume = resumed,
            )
        }
        if (resumed != null && savedMode == "native" && client.supportsNativeTools(resolvedModel)) {
            return completeNative(
                runNative(
                    manifest, registry, resolvedModel, task, cancelSignal, onStep,
                    checkpoint, keepOnFinish, signature, resumed,
                ),
                manifest, registry, resolvedModel, task, harnessContract, cancelSignal, onStep,
                checkpoint, keepOnFinish, signature,
            )
        }
        if (resumed != null) checkpoint.clear() // 端点已不支持原生协议：原生会话无法接续

        if (client.supportsNativeTools(resolvedModel)) {
            return completeNative(
                runNative(
                    manifest, registry, resolvedModel, task, cancelSignal, onStep,
                    checkpoint, keepOnFinish, signature, null,
                ),
                manifest, registry, resolvedModel, task, harnessContract, cancelSignal, onStep,
                checkpoint, keepOnFinish, signature,
            )
        }
        return runText(
            manifest, registry, resolvedModel, task, harnessContract, cancelSignal, onStep,
            checkpoint = checkpoint, keepOnFinish = keepOnFinish, signature = signature, resume = null,
        )
    }

    /** 原生循环的收尾：拒协议时带着已付费的首轮回复接续文本循环，不重打、不丢 token 账 */
    private fun completeNative(
        native: NativeRun?,
        manifest: PluginManifest,
        registry: ToolRegistry,
        model: String,
        task: String,
        harnessContract: String,
        cancelSignal: () -> Boolean,
        onStep: ((AgentStep) -> Unit)?,
        checkpoint: AgentCheckpoint,
        keepOnFinish: Boolean,
        signature: String,
    ): AgentOutcome? = when (native) {
        null -> null
        is NativeRun.Done -> native.outcome
        is NativeRun.Unsupported -> {
            checkpoint.clear() // 原生首轮没有可迁移的步骤（tool_calls 历史文本协议表达不了）
            runText(
                manifest, registry, model, task, harnessContract, cancelSignal, onStep,
                primedReply = native.firstReply, primedTokens = native.tokens,
                checkpoint = checkpoint, keepOnFinish = keepOnFinish, signature = signature, resume = null,
            )
        }
    }

    /**
     * 原生工具循环（T-049）——工具执行语义与文本循环逐条对齐：
     * 状态工具（submit_plan / record_finding / suggest_chart）、finish、参数校验、
     * 同参缓存、步数预算、取消检查点全部同一口径，只有「模型怎么表达调用」不同。
     *
     * 只返回三种结局：
     *  - [NativeRun.Done]：模型调用 finish；
     *  - [NativeRun.Unsupported]：端点中途表明不支持原生协议（首轮即发生）——回落文本循环；
     *  - null：协议被违反且修复重试用尽 / 预算耗尽（与文本循环同样交还 null，
     *    调用方回落经典流水线，不在这里偷偷续命）。
     */
    private fun runNative(
        manifest: PluginManifest,
        registry: ToolRegistry,
        model: String,
        task: String,
        cancelSignal: () -> Boolean,
        onStep: ((AgentStep) -> Unit)?,
        checkpoint: AgentCheckpoint = AgentCheckpoint.NONE,
        keepOnFinish: Boolean = false,
        signature: String = "",
        resume: JsonObject? = null,
    ): NativeRun? {
        val tools = registry.resolve(manifest.tools)
        val specs = tools.map { ToolSpec(it.name, it.description, it.parametersSchema) } +
            stateToolSpecs() + finishSpec(manifest.kind)
        val system = buildString {
            append(AGENT_HARNESS_CONTRACT_NATIVE)
            append("\n\n").append(manifest.systemPrompt)
        }
        val restored = resume?.let { state -> runCatching { AgentCheckpointCodec.decodeState(state) }.getOrNull() }
        val messages: MutableList<TurnMessage> = restored?.messages?.toMutableList() ?: mutableListOf(
            TurnMessage("system", system),
            TurnMessage("user", task),
        )
        var repairs = restored?.repairs ?: 0
        var totalTokens = restored?.totalTokens ?: 0
        val steps = restored?.steps?.toMutableList() ?: mutableListOf()
        var toolCalls = restored?.toolCalls ?: 0
        val plan = restored?.plan?.toMutableList() ?: mutableListOf()
        val findings = restored?.findings?.toMutableList() ?: mutableListOf()
        val charts = restored?.charts?.toMutableList() ?: mutableListOf()
        val trace = restored?.trace?.toMutableList() ?: mutableListOf()
        val cache = restored?.cache?.toMutableMap() ?: mutableMapOf()
        val startStep = restored?.nextStep ?: 0

        for (stepIndex in startStep until manifest.maxSteps) {
            if (cancelSignal()) throw AgentCancelled()
            val isLastStep = stepIndex == manifest.maxSteps - 1
            val forceFinish = isLastStep && steps.isNotEmpty()
            if (forceFinish && messages.lastOrNull()?.content != NATIVE_LIMIT_PROMPT) {
                messages.add(TurnMessage("user", NATIVE_LIMIT_PROMPT))
            }
            val turn = client.chatTools(
                model = model,
                messages = messages.toList(),
                temperature = manifest.temperature,
                maxTokens = 16384,
                tools = specs,
                toolChoice = if (forceFinish) "finish" else "auto",
            )
            totalTokens += turn.totalTokens
            if (!turn.native) {
                // 端点不认 tools（400 → 客户端已改打纯文本）：只在首轮回落有意义，
                // 中途变卦说明网关状态异常，如实抛错比悄悄降级更诚实
                if (stepIndex > 0) {
                    throw com.stragent.core.ai.StepError(
                        STEP_ERR_UNSUPPORTED,
                        "端点在同一会话中途不再支持原生工具协议，请重试",
                    )
                }
                return NativeRun.Unsupported(turn.text, turn.totalTokens)
            }
            if (turn.toolCalls.isEmpty()) {
                // 模型只说话不调工具：要求它改用 finish（截断也算协议失败——产物不完整）
                if (repairs < MAX_REPAIRS) {
                    repairs += 1
                    if (turn.text.isNotBlank()) messages.add(TurnMessage("assistant", turn.text))
                    messages.add(TurnMessage("user", NATIVE_FINISH_PROMPT))
                    continue
                }
                return aborted(checkpoint)
            }
            messages.add(TurnMessage("assistant", turn.text, turn.toolCalls))
            for (call in turn.toolCalls) {
                if (cancelSignal()) throw AgentCancelled()
                val args = parseArguments(call.arguments)
                if (call.name == "finish") {
                    if (args == null) {
                        trace.add(AgentTraceEntry(stepIndex, "finish", turn.text.take(200), false, "finish.args 不是合法 JSON 对象"))
                        messages.add(toolResultMessage(call, """{"ok":false,"error":"args 不是合法 JSON 对象，请重新调用 finish 提交完整产物"}"""))
                        continue
                    }
                    trace.add(AgentTraceEntry(stepIndex, "finish", turn.text.take(200), true, null))
                    return NativeRun.Done(
                        finishOutcome(
                            AgentOutcome(
                                output = args,
                                steps = steps.toList(),
                                totalTokens = totalTokens,
                                toolCalls = toolCalls,
                                plan = plan.toList(),
                                findings = findings.toList(),
                                charts = charts.toList(),
                                trace = trace.toList(),
                            ),
                            checkpoint, keepOnFinish, signature,
                        ),
                    )
                }
                if (args == null) {
                    trace.add(AgentTraceEntry(stepIndex, call.name, turn.text.take(200), false, "参数不是合法 JSON 对象"))
                    messages.add(toolResultMessage(call, """{"ok":false,"error":"参数不是合法 JSON 对象，请按工具目录的 parameters 重新调用"}"""))
                    continue
                }
                when (call.name) {
                    "submit_plan" -> {
                        val planSteps = (args["steps"] as? JsonArray)
                            ?.mapNotNull { element -> (element as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content }
                            ?.take(12) ?: emptyList()
                        plan.clear()
                        plan.addAll(planSteps)
                        onStep?.invoke(AgentStep(call.name, planSteps.joinToString("；").take(160), "计划 ${planSteps.size} 步", true))
                        trace.add(AgentTraceEntry(stepIndex, call.name, turn.text.take(200), true, null))
                        messages.add(
                            toolResultMessage(
                                call,
                                """{"ok":true,"note":"计划已登记（${planSteps.size} 步）。按计划执行，但允许根据工具结果调整；结束时调用 finish。"}""",
                            ),
                        )
                        continue
                    }
                    "record_finding" -> {
                        val title = args.str("title") ?: "发现"
                        val detail = args.str("detail") ?: ""
                        val evidence = args.str("evidence") ?: ""
                        findings.add(AgentFindingCard(title, detail, evidence))
                        onStep?.invoke(AgentStep(call.name, title.take(160), "发现 ${findings.size} 条", true))
                        trace.add(AgentTraceEntry(stepIndex, call.name, turn.text.take(200), true, null))
                        messages.add(
                            toolResultMessage(
                                call,
                                """{"ok":true,"journalSize":${findings.size}}""",
                            ),
                        )
                        continue
                    }
                    "suggest_chart" -> {
                        val chartData = chartDataOf(args)
                        if (chartData.isEmpty()) {
                            trace.add(AgentTraceEntry(stepIndex, call.name, turn.text.take(200), false, "data 不能为空（[{label,value}]）"))
                            messages.add(toolResultMessage(call, """{"ok":false,"error":"data 不能为空（[{label,value}]）"}"""))
                            continue
                        }
                        if (charts.size >= 5) {
                            trace.add(AgentTraceEntry(stepIndex, call.name, turn.text.take(200), false, "图表已达上限 5 张，请删除不必要的图后再试"))
                            messages.add(toolResultMessage(call, """{"ok":false,"error":"图表已达上限 5 张，请删除不必要的图后再试"}"""))
                            continue
                        }
                        val chartIndex = charts.size
                        charts.add(
                            AgentChartCard(
                                args.str("type") ?: "bar",
                                args.str("title") ?: "图表",
                                chartData,
                                args.str("description") ?: "",
                            ),
                        )
                        onStep?.invoke(AgentStep(call.name, "${args.str("type") ?: "bar"} · ${args.str("title") ?: "图表"}（${chartData.size} 点）", "图表 $chartIndex", true))
                        trace.add(AgentTraceEntry(stepIndex, call.name, turn.text.take(200), true, null))
                        messages.add(
                            toolResultMessage(
                                call,
                                """{"ok":true,"chartIndex":$chartIndex,"note":"在报告正文合适位置写 {{chart:$chartIndex}} 来引用此图，渲染时替换为实际图表。"}""",
                            ),
                        )
                        continue
                    }
                }
                val tool = registry.get(call.name)
                // 参数 Schema 校验：缺必填项 → 针对性错误回传模型修复（与文本循环同一口径）
                val argError = validateArgs(tool?.parametersSchema, args)
                if (argError != null) {
                    trace.add(AgentTraceEntry(stepIndex, call.name, turn.text.take(200), false, argError))
                    messages.add(
                        toolResultMessage(
                            call,
                            """{"ok":false,"error":"参数校验失败：$argError（请按工具目录的 parameters 修正后重试）"}""",
                        ),
                    )
                    continue
                }
                val cacheKey = call.name + "§" + args.toString()
                val outcomeJson: JsonObject = when {
                    tool == null -> buildJsonObject {
                        put("ok", false)
                        put("error", "未知工具: ${call.name}（可用工具见目录）")
                    }
                    tool.cacheable && cache.containsKey(cacheKey) -> buildJsonObject {
                        put("cached", true)
                        put("result", cache[cacheKey]!!)
                    }
                    else -> {
                        val executed = try {
                            tool.invoke(args)
                        } catch (error: Exception) {
                            // T-079（W-3）：错误回执带下一步动作，帮模型按动作自纠而不是盲试
                            buildJsonObject {
                                put("ok", false)
                                put("error", error.message ?: "工具执行失败")
                                put("nextAction", ErrorActions.nextAction(error.message))
                            }
                        }
                        if (tool.cacheable) cache[cacheKey] = executed
                        executed
                    }
                }
                val step = AgentStep(
                    tool = call.name,
                    argsSummary = args.toString().take(160),
                    resultSummary = outcomeJson.toString().take(160),
                    ok = tool != null && outcomeJson["error"] == null,
                )
                steps.add(step)
                onStep?.invoke(step)
                trace.add(AgentTraceEntry(stepIndex, call.name, turn.text.take(200), step.ok, null))
                if (tool != null) toolCalls += 1
                messages.add(toolResultMessage(call, outcomeJson.toString()))
            }
            // 每完成一轮（模型回复 + 全部工具结果回填）落一次盘：丢一轮的工具结果，
            // 不重复已完成的轮次——续跑时这些轮次的模型调用不会再发生
            saveState(
                checkpoint, signature, "native", stepIndex + 1, repairs, totalTokens, null,
                messages, steps, toolCalls, plan, findings, charts, trace, cache,
            )
        }
        return aborted(checkpoint) // 预算耗尽且未 finish
    }

    /**
     * 文本协议循环（T-049 之前的唯一实现）——**行为保持不变**：
     * 原生工具不可用 / 端点拒绝时由这里兜底，既有 46 项内核测试钉的就是这条路径。
     */
    private fun runText(
        manifest: PluginManifest,
        registry: ToolRegistry,
        model: String,
        task: String,
        harnessContract: String,
        cancelSignal: () -> Boolean,
        onStep: ((AgentStep) -> Unit)?,
        /** 端点拒绝原生协议时已付费的首轮纯文本回复（接续使用，不重打一次） */
        primedReply: String = "",
        primedTokens: Int = 0,
        checkpoint: AgentCheckpoint = AgentCheckpoint.NONE,
        keepOnFinish: Boolean = false,
        signature: String = "",
        /** 上次执行落盘的状态（同签名才会传进来）：从 nextStep 继续，已完成轮次不重打模型 */
        resume: JsonObject? = null,
    ): AgentOutcome? {
        val tools = registry.resolve(manifest.tools)
        val catalog = registry.catalogJson(manifest.tools)
        val system = buildString {
            append(harnessContract)
            append("\n\n可用工具（每轮只能调用其中之一，或 finish）：\n").append(catalog)
            append("\n\n").append(manifest.systemPrompt)
        }
        val restored = resume?.let { state -> runCatching { AgentCheckpointCodec.decodeState(state) }.getOrNull() }
        val messages: MutableList<Pair<String, String>> = restored?.messages
            ?.map { it.role to it.content }?.toMutableList()
            ?: mutableListOf(
                "system" to system,
                "user" to task,
            )
        var repairs = restored?.repairs ?: 0
        // 原生协议被端点拒绝时，首轮纯文本回复已经付过费：这里接续解析，token 一并入账
        var totalTokens = restored?.totalTokens ?: primedTokens
        var primed: String? = if (restored != null) restored.primed else primedReply.takeIf { it.isNotBlank() }
        val steps = restored?.steps?.toMutableList() ?: mutableListOf()
        var toolCalls = restored?.toolCalls ?: 0
        /** Plan-and-Execute 的计划层：submit_plan 提交的分析步骤 */
        val plan = restored?.plan?.toMutableList() ?: mutableListOf()
        /** Scratchpad 发现日志：record_finding 记录的发现卡 */
        val findings = restored?.findings?.toMutableList() ?: mutableListOf()
        /** suggest_chart 建议的图表 */
        val charts = restored?.charts?.toMutableList() ?: mutableListOf()
        /** 可观测轨迹（每步一条，供回放/排障/溯源审计） */
        val trace = restored?.trace?.toMutableList() ?: mutableListOf()
        /** 纯工具的结果缓存（同 tool+args 直接回缓存，省预算与费用） */
        val cache = restored?.cache?.toMutableMap() ?: mutableMapOf()
        val startStep = restored?.nextStep ?: 0

        for (stepIndex in startStep until manifest.maxSteps) {
            if (cancelSignal()) throw AgentCancelled()
            val isLastStep = stepIndex == manifest.maxSteps - 1
            if (isLastStep && steps.isNotEmpty() && messages.lastOrNull()?.second != TEXT_LIMIT_PROMPT) {
                // 预算见底：强制要求收尾（续跑时若提示已在最后一条，不重复追加）
                messages.add("user" to TEXT_LIMIT_PROMPT)
            }
            val result = primed?.let { replay ->
                // 回落首轮：不再多打一次模型调用，直接按文本协议解析这份回复
                primed = null
                ChatResult(replay)
            } ?: chatStep(
                client, model, messages.toList(),
                ChatOptions(temperature = manifest.temperature, maxTokens = 16384, continueOnTruncate = true),
                cancelSignal = cancelSignal,
            )
            totalTokens += result.totalTokens
            val parsed = extractJsonObject(result.content)
            if (parsed == null) {
                if (repairs < MAX_REPAIRS) {
                    repairs += 1
                    messages.add("assistant" to result.content)
                    messages.add(
                        "user" to "上一条输出不是合法的单个 JSON 对象（协议要求每轮只回一个 JSON：" +
                            "{\"thought\":…,\"tool\":…,\"args\":…}，终结用 {\"tool\":\"finish\",\"args\":…}）。" +
                            "请重新输出，不要包含 JSON 以外的任何文字。",
                    )
                    continue
                }
                return aborted(checkpoint) // 修复失败：调用方回落经典流水线
            }
            val toolName = parsed.str("tool") ?: ""
            val args = parsed["args"] as? JsonObject ?: JsonObject(emptyMap())
            val thought = parsed.str("thought") ?: ""
            if (toolName == "finish") {
                trace.add(AgentTraceEntry(stepIndex, "finish", thought, true, null))
                return finishOutcome(
                    AgentOutcome(args, steps, totalTokens, toolCalls, plan.toList(), findings.toList(), charts.toList(), trace.toList()),
                    checkpoint, keepOnFinish, signature,
                )
            }
            if (isLastStep) {
                // 最后一轮仍在调工具且没有 finish：放弃（模型不守收尾约定）
                return aborted(checkpoint)
            }
            // suggest_chart：建议图表（有状态，不进缓存）
            if (toolName == "suggest_chart") {
                val type = args.str("type") ?: "bar"
                val title = args.str("title") ?: "图表"
                val chartData = (args["data"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { element ->
                        val obj = element as? JsonObject ?: return@mapNotNull null
                        val label = obj.str("label") ?: return@mapNotNull null
                        val value = obj["value"]?.let { (it as? JsonPrimitive)?.content?.toDoubleOrNull() } ?: return@mapNotNull null
                        label to value
                    }?.take(20) ?: emptyList()
                if (chartData.isEmpty()) {
                    trace.add(AgentTraceEntry(stepIndex, "suggest_chart", thought, false, "data 不能为空（[{label,value}]）"))
                    messages.add("assistant" to result.content)
                    messages.add("user" to "<tool_result tool=\"suggest_chart\">\n{\"ok\":false,\"error\":\"data 不能为空（[{label,value}]）\"}\n</tool_result>")
                    continue
                }
                if (charts.size >= 5) {
                    trace.add(AgentTraceEntry(stepIndex, "suggest_chart", thought, false, "图表已达上限 5 张，请删除不必要的图后再试"))
                    messages.add("assistant" to result.content)
                    messages.add("user" to "<tool_result tool=\"suggest_chart\">\n{\"ok\":false,\"error\":\"图表已达上限 5 张，请删除不必要的图后再试\"}\n</tool_result>")
                    continue
                }
                val chartIndex = charts.size
                charts.add(AgentChartCard(type, title, chartData, args.str("description") ?: ""))
                onStep?.invoke(AgentStep(toolName, "$type · $title（${chartData.size} 点）", "图表 $chartIndex", true))
                trace.add(AgentTraceEntry(stepIndex, "suggest_chart", thought, true, null))
                messages.add("assistant" to result.content)
                messages.add(
                    "user" to "<tool_result tool=\"suggest_chart\">\n{\"ok\":true,\"chartIndex\":$chartIndex," +
                        "\"note\":\"在报告正文合适位置写 {{chart:$chartIndex}} 来引用此图，渲染时替换为实际图表。\"}\n</tool_result>",
                )
                continue
            }
            // 有状态工具：计划/发现日志不进缓存，直接进 state
            if (toolName == "submit_plan") {
                val stepsList = (args["steps"] as? JsonArray)
                    ?.mapNotNull { element -> (element as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content }
                    ?.take(12) ?: emptyList()
                plan.clear()
                plan.addAll(stepsList)
                onStep?.invoke(AgentStep(toolName, stepsList.joinToString("；").take(160), "计划 ${stepsList.size} 步", true))
                trace.add(AgentTraceEntry(stepIndex, "submit_plan", thought, true, null))
                messages.add("assistant" to result.content)
                messages.add(
                    "user" to "<tool_result tool=\"submit_plan\">\n{\"ok\":true,\"note\":\"计划已登记（${stepsList.size} 步）。" +
                        "按计划执行，但允许根据工具结果调整；结束时 finish。\"}\n</tool_result>",
                )
                continue
            }
            if (toolName == "record_finding") {
                val title = args.str("title") ?: "发现"
                val detail = args.str("detail") ?: ""
                val evidence = args.str("evidence") ?: ""
                findings.add(AgentFindingCard(title, detail, evidence))
                onStep?.invoke(AgentStep(toolName, title.take(160), "发现 ${findings.size} 条", true))
                trace.add(AgentTraceEntry(stepIndex, "record_finding", thought, true, null))
                messages.add("assistant" to result.content)
                messages.add(
                    "user" to "<tool_result tool=\"record_finding\">\n{\"ok\":true,\"journalSize\":${findings.size}}\n</tool_result>",
                )
                continue
            }
            val tool = registry.get(toolName)
            // 参数 Schema 校验（必填项缺失 → 针对性错误回传模型修复，避免无效执行/浪费步数预算）
            val argError = validateArgs(tool?.parametersSchema, args)
            if (argError != null) {
                trace.add(AgentTraceEntry(stepIndex, toolName, thought, false, argError))
                messages.add("assistant" to result.content)
                messages.add("user" to "<tool_result tool=\"$toolName\">\n{\"ok\":false,\"error\":\"参数校验失败：$argError（请按工具目录的 parameters 修正后重试）\"}\n</tool_result>")
                continue
            }
            // 纯工具缓存：同 tool+args 的重复调用直接回缓存（生产纪律：重复查询不重复付费）
            val cacheKey = toolName + "§" + args.toString()
            val outcomeJson: JsonObject = when {
                tool == null -> buildJsonObject {
                    put("ok", false)
                    put("error", "未知工具: $toolName（可用工具见目录）")
                }
                tool.cacheable && cache.containsKey(cacheKey) -> buildJsonObject {
                    put("cached", true)
                    put("result", cache[cacheKey]!!)
                }
                else -> {
                    val executed = try {
                        tool.invoke(args)
                    } catch (error: Exception) {
                        buildJsonObject {
                            put("ok", false)
                            put("error", error.message ?: "工具执行失败")
                        }
                    }
                    if (tool.cacheable) cache[cacheKey] = executed
                    executed
                }
            }
            val resultText = outcomeJson.toString().let { if (it.length > TOOL_RESULT_LIMIT) it.take(TOOL_RESULT_LIMIT) + "…（已截断）" else it }
            val step = AgentStep(
                tool = toolName,
                argsSummary = args.toString().take(160),
                resultSummary = resultText.take(160),
                ok = tool != null && outcomeJson["error"] == null,
            )
            steps.add(step)
            onStep?.invoke(step)
            trace.add(AgentTraceEntry(stepIndex, toolName, thought, step.ok, null))
            if (tool != null) toolCalls += 1
            messages.add("assistant" to result.content)
            messages.add("user" to "<tool_result tool=\"$toolName\">\n$resultText\n</tool_result>")
            // 每完成一轮落一次盘：续跑时这一轮的模型调用不会重打（工具结果已在 messages 里）
            saveState(
                checkpoint, signature, "text", stepIndex + 1, repairs, totalTokens, primed,
                messages.map { TurnMessage(it.first, it.second) }, steps, toolCalls, plan, findings, charts, trace, cache,
            )
        }
        return aborted(checkpoint) // 预算耗尽且未 finish
    }

    private fun parseArguments(raw: String): JsonObject? =
        runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()

    // ---- 断点（T-050）：签名、落盘、完成态、失败清理 ----

    /**
     * 阶段签名：模型 / 任务 / harness 契约 / 插件清单任一变化 → 旧状态作废。
     * 任务文本里已包含数据集摘要与报告类型、目标篇幅，提示词变化由 systemPrompt 覆盖。
     */
    private fun checkpointSignature(
        model: String,
        manifest: PluginManifest,
        task: String,
        harnessContract: String,
    ): String = AgentCheckpointStore.sha256(
        buildString {
            append("v").append(AgentCheckpointStore.VERSION).append('\u0000')
            append(model).append('\u0000')
            append(manifest.id).append('\u0000')
            append(manifest.kind).append('\u0000')
            append(manifest.systemPrompt).append('\u0000')
            append(manifest.tools?.joinToString(",") ?: "*").append('\u0000')
            append(manifest.model ?: "").append('\u0000')
            append(manifest.temperature).append('\u0000')
            append(manifest.maxSteps).append('\u0000')
            append(harnessContract).append('\u0000')
            append(task)
        },
    )

    /** 每完成一轮写一份状态（best-effort：落盘失败不打断报告生成） */
    private fun saveState(
        checkpoint: AgentCheckpoint,
        signature: String,
        mode: String,
        nextStep: Int,
        repairs: Int,
        totalTokens: Int,
        primed: String?,
        messages: List<TurnMessage>,
        steps: List<AgentStep>,
        toolCalls: Int,
        plan: List<String>,
        findings: List<AgentFindingCard>,
        charts: List<AgentChartCard>,
        trace: List<AgentTraceEntry>,
        cache: Map<String, JsonObject>,
    ) {
        if (!checkpoint.enabled) return
        checkpoint.save(
            AgentCheckpointCodec.encodeState(
                CheckpointState(
                    mode = mode,
                    nextStep = nextStep,
                    repairs = repairs,
                    totalTokens = totalTokens,
                    primed = primed,
                    messages = messages,
                    steps = steps,
                    toolCalls = toolCalls,
                    plan = plan,
                    findings = findings,
                    charts = charts,
                    trace = trace,
                    cache = cache,
                ),
                signature,
            ),
        )
    }

    /** 成功收尾：keepOnFinish 写完成态（重跑直接回放、不再计费），否则删掉状态 */
    private fun finishOutcome(
        outcome: AgentOutcome,
        checkpoint: AgentCheckpoint,
        keepOnFinish: Boolean,
        signature: String,
    ): AgentOutcome {
        if (checkpoint.enabled) {
            if (keepOnFinish) {
                checkpoint.save(AgentCheckpointCodec.encodeCompleted(signature, outcome))
            } else {
                checkpoint.clear()
            }
        }
        return outcome
    }

    /** 协议失败 / 预算耗尽：状态作废（半截状态续跑只会把失败再演一遍） */
    private fun <T> aborted(checkpoint: AgentCheckpoint): T? {
        checkpoint.clear()
        return null
    }

    /** suggest_chart 的 data 解析（[{label,value}]，最多 20 点）——与文本循环同一口径 */
    private fun chartDataOf(args: JsonObject): List<Pair<String, Double>> =
        (args["data"] as? JsonArray)?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val label = obj.str("label") ?: return@mapNotNull null
            val value = obj["value"]?.let { (it as? JsonPrimitive)?.content?.toDoubleOrNull() }
                ?: return@mapNotNull null
            label to value
        }?.take(20) ?: emptyList()

    private fun toolResultMessage(call: ToolCall, result: String): TurnMessage {
        val text = if (result.length > TOOL_RESULT_LIMIT) result.take(TOOL_RESULT_LIMIT) + "…（已截断）" else result
        return TurnMessage("tool", text, toolCallId = call.id)
    }

    private fun extractJsonObject(text: String): JsonObject? {
        // 剥代码围栏，截取首个 { 到与其配对的 }（模型偶尔会加说明文字）
        val cleaned = text.replace(Regex("```(json)?", setOf(kotlin.text.RegexOption.IGNORE_CASE)), "").trim()
        val start = cleaned.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until cleaned.length) {
            val ch = cleaned[i]
            if (escaped) {
                escaped = false
                continue
            }
            when {
                ch == '\\' && inString -> escaped = true
                ch == '"' -> inString = !inString
                !inString && ch == '{' -> depth += 1
                !inString && ch == '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return runCatching {
                            Json.parseToJsonElement(cleaned.substring(start, i + 1)).jsonObject
                        }.getOrNull()
                    }
                }
            }
        }
        return null
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    /**
     * 轻量参数校验：检查工具 JSON Schema 的 required 字段是否齐全（类型由工具自身在 invoke 时再判）。
     * 返回错误信息或 null。缺失必填项时给模型针对性反馈，触发修复重试而非无效执行。
     */
    private fun validateArgs(schema: JsonObject?, args: JsonObject): String? {
        val required = (schema?.get("required") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: return null
        for (name in required) {
            val v = args[name]
            if (v == null || v is JsonNull) return "缺少必填参数: $name"
        }
        return null
    }
}
