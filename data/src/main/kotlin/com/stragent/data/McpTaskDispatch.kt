package com.stragent.data

import com.stragent.core.ports.AgentTool
import com.stragent.core.ports.ErrorActions
import com.stragent.core.ports.ProgressBus
import com.stragent.core.i18n.Messages
import com.stragent.core.observability.OtelSampler
import com.stragent.core.observability.OtelSpan
import com.stragent.core.observability.OtelSpanContext
import com.stragent.core.observability.StrAgentMetrics
import com.stragent.core.observability.StructuredLogger
import com.stragent.core.observability.TraceIds
import com.stragent.core.observability.W3cTraceparent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * MCP 桥的 **方法路由 + 异步任务 + 工具执行核心**（T-094 拆分单元之一，自 [McpBridge] 迁出）。
 *
 * 这三者共享 executeTool / toolCallEnvelope / tracedTool 同一套执行核心——拆开会形成互相引用的环，
 * 故同属一个单元；与 HTTP 传输完全无关（stdio 与 HTTP 两条传输都经 McpBridge.dispatch 门面到达这里，
 * 是拆分里唯一 transport-independent 的部分）。
 *
 * **行为零变化**：成员自 McpBridge 逐字迁出（T-094 第 1 步），语义与调用时序不变；
 * 单元边界与职责记录见 docs/D7-tasks.md T-094。
 */
private val POINTER_IMPORT_TOOLS = setOf(ABILITY_IMPORT_TABLE, ABILITY_IMPORT_TEXT)

/**
 * T-163：结果缓存提示（2026-07-28 caching：`resultType: "complete"` 的列表结果 MUST
 * 带缓存提示）。ttlMs 是新鲜度提示（类比 HTTP max-age），cacheScope 声明可缓存范围。
 *
 * - `server/discover`：身份/能力/支持集对所有调用方一致 → `public`，一小时；
 * - `tools/list`：目录可随运行时授权收放（catalogHiddenNames）、动态工具可变 →
 *   `private`（按授权上下文缓存），TTL 取短（一分钟）——变化靠客户端重拉，不发通知。
 */
private const val DISCOVER_TTL_MS = 3_600_000L
private const val LIST_TTL_MS = 60_000L

/**
 * T-166：官方 Tasks 扩展标识（能力协商用）——`capabilities.extensions` 的键，
 * 也是客户端在 `_meta."io.modelcontextprotocol/clientCapabilities".extensions`
 * 里的声明键。
 */
private const val TASKS_EXTENSION_ID = "io.modelcontextprotocol/tasks"

/**
 * T-154：响应侧统一字节闸。默认 4MB（请求侧上限 8MB 的一半——回执不该比请求大），
 * 超限抛 [IllegalArgumentException]，由 T-079 机器包成 isError 回执（含 nextAction）。
 * 逐工具上限（list_rows 50 行 / db_query 1000 行每页 / read_segments 8000 字符等）
 * 是第一道防线，本闸是兜底——防某个工具将来放宽上限时静默放开响应体积。
 */
internal object ResponseCap {
    const val CAP_BYTES: Int = 4 * 1024 * 1024

    fun check(envelope: JsonObject) {
        val size = envelope.toString().toByteArray(Charsets.UTF_8).size
        if (size > CAP_BYTES) {
            throw IllegalArgumentException(
                com.stragent.core.i18n.Messages.get("error.response_too_large")
                    .replace("{0}", (size / 1024 / 1024).toString())
                    .replace("{1}", (CAP_BYTES / 1024 / 1024).toString()),
            )
        }
    }
}

internal class McpTaskDispatch(
    private val datasets: AbilityDatasets,
    private val extraTools: List<AgentTool>,
    private val extraToolsProvider: (() -> List<AgentTool>)?,
    private val provenance: ProvenanceRecorder?,
    private val registry: SessionRegistry?,
    private val taskStore: McpTaskStore,
    private val otel: com.stragent.core.observability.OtelTraces?,
    private val metrics: com.stragent.core.observability.OtelMetrics?,
    private val sampler: OtelSampler?,
    private val log: StructuredLogger,
    /** T-155：目录裁剪谓词——返回「当前不应下发的工具名集合」，每次 tools/list 现取（运行时授权可变） */
    private val catalogHiddenNames: () -> Set<String> = { emptySet() },
) {
    /** 当前对外可见的完整工具集（固定工具 + 动态工具） */
    private fun allTools(): List<AgentTool> =
        extraTools + (extraToolsProvider?.invoke() ?: emptyList())

    /**
     * 服务端能力声明（T-056）：只公布**真的实现了**的能力。
     * tools 恒定；tasks 三件套（get/update/cancel）随异步任务一起公布；
     * elicitation 随双向通道一起公布（stdio 自动可用；HTTP 需客户端建立 GET /mcp 流）。
     *
     * T-166：另按官方 Tasks 扩展的协商形状公布 `capabilities.extensions`——
     * `io.modelcontextprotocol/tasks` 的能力对象是**空对象**（官方无扩展专有设置）。
     * 旧的 `capabilities.tasks` 形状保留（仓内既有客户端按它读三件套）。
     */
    private fun capabilities(): JsonObject = buildJsonObject {
        put("tools", buildJsonObject { })
        put("tasks", buildJsonObject {
            put("get", true)
            put("update", true)
            put("cancel", true)
        })
        // elicitation（T-056）：服务端会发起 elicitation/create；客户端可拒绝/取消
        put("elicitation", buildJsonObject { })
        // 官方扩展协商（T-166）：extensions 下按扩展 id 列能力
        put("extensions", buildJsonObject {
            put(TASKS_EXTENSION_ID, buildJsonObject { })
        })
    }

    /**
     * JSON-RPC 方法分发 —— **传输无关**。
     *
     * HTTP（Streamable HTTP）与 stdio 两种传输共用这一份实现，保证两条接线下的
     * 能力面与语义**逐字一致**（dev-agent 形态的关键：能力只打包一次，宿主各自接线）。
     */
    fun dispatch(method: String, params: JsonObject, requestId: String? = null): JsonObject = when (method) {
        // initialize 是兼容壳（T-056）：无状态核心下客户端可以直接 tools/list。
        // 仍按请求版本协商，并把弃用与移除时间写在结果里，客户端能自己决定何时升级。
        "initialize" -> {
            val negotiated = McpProtocol.negotiate(
                (params["protocolVersion"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            )
            buildJsonObject {
                put("resultType", "complete")
                put("protocolVersion", negotiated)
                put("capabilities", capabilities())
                put("serverInfo", buildJsonObject {
                    put("name", "Str Agent")
                    put("version", com.stragent.data.BuildInfo.version)
                })
                put(
                    "supportedVersions",
                    kotlinx.serialization.json.JsonArray(McpProtocol.SUPPORTED.map { JsonPrimitive(it) }),
                )
                put("_meta", buildJsonObject {
                    put("deprecated", buildJsonObject {
                        put("initialize", McpProtocol.INITIALIZE_DEPRECATED)
                        put("removedAfter", McpProtocol.COMPAT_UNTIL)
                        put("alternative", "stateless requests (${McpProtocol.PROTOCOL_HEADER} header)")
                    })
                })
            }
        }

        // 存活探针：无状态核心下客户端不需要握手也能确认服务端可用
        "ping" -> buildJsonObject { put("resultType", "complete") }

        // T-163：2026-07-28 预检 RPC（MUST 实现）。客户端在任何其他请求之前用它查
        // 支持的协议版本、能力与服务身份；stdio 上也是旧版兼容探测的入口。
        // 缓存提示（MUST）：身份/能力对所有调用方一致 → public。
        "server/discover" -> buildJsonObject {
            put("resultType", "complete")
            put("supportedVersions", kotlinx.serialization.json.JsonArray(McpProtocol.SUPPORTED.map { JsonPrimitive(it) }))
            put("capabilities", capabilities())
            put("_meta", buildJsonObject {
                put("io.modelcontextprotocol/serverInfo", buildJsonObject {
                    put("name", "Str Agent")
                    put("version", com.stragent.data.BuildInfo.version)
                })
            })
            put(
                "instructions",
                "数据分析工具集（仅作为工具存在）：报告生成受数字白名单门禁（清单外数字一律拒发）；" +  // lint:allow zh-first（instructions 面向 LLM 的中文引导，与报告文案同源）
                    "异步与进度经 tasks/* 与 notifications/progress；协议版本经 MCP-Protocol-Version 头逐请求声明。",  // lint:allow zh-first（instructions 面向 LLM 的中文引导，与报告文案同源）
            )
            put("ttlMs", DISCOVER_TTL_MS)
            put("cacheScope", "public")
        }

        "notifications/initialized" -> JsonObject(emptyMap())

        // 取消通知：stdio 与直接调用走这里；HTTP 在 handle() 里提前处理（避免回响应体）
        "notifications/cancelled" -> {
            params["requestId"]?.let { McpRequestCancellation.cancel(McpSessionContext.current(), it.toString()) }
            JsonObject(emptyMap())
        }

        "tools/list" -> buildJsonObject {
            put("resultType", "complete")
            put(
                "tools",
                kotlinx.serialization.json.JsonArray(
                    toolsCatalog(datasets, allTools().filter { it.name !in catalogHiddenNames() }).map { tool ->
                        buildJsonObject {
                            put("name", tool["name"]!!)
                            put("description", tool["description"]!!)
                            put("inputSchema", tool["parameters"] as JsonObject)
                            // T-056：输出契约全面化（严格 12 条 + 宽松对象）+ 行为提示随目录下发
                            tool["outputSchema"]?.let { put("outputSchema", it) }
                            tool["annotations"]?.let { put("annotations", it) }
                        }
                    },
                ),
            )
            // T-163：缓存提示（MUST）——目录可随运行时授权收放 → private + 短 TTL
            put("ttlMs", LIST_TTL_MS)
            put("cacheScope", "private")
        }

        "tools/call" -> {
            val name = params["name"]?.let { (it as? JsonPrimitive)?.content } ?: ""
            val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
            // 只认规范名（T-068：旧裸名 1.1.0 起退役；内部裸名→规范名映射见 McpToolNames）
            val tool = (abilityTools(datasets) + allTools()).find { McpToolNames.matches(it.name, name) }
                ?: throw McpInvalidParamsException(
                    // 能映射到规范名的请求名 = 旧裸名：给迁移指引而不是让用户猜
                    if (McpToolNames.exposed(name) != name) {
                        Messages.get("error.unknown_tool").replace("{0}", name) +
                            "（旧版工具名已于 1.1.0 退役，请改用「${McpToolNames.exposed(name)}」）"  // lint:allow zh-first（用户面中文输出，迁自 McpBridge 原基线）
                    } else {
                        Messages.get("error.unknown_tool").replace("{0}", name)
                    },
                )
            // 上游链路（T-056）：`_meta.traceparent`（W3C trace-context）给出调用方 trace，
            // 本服务的 tools/call span 与工具内部 span 都挂上去；坏 header 只是「没有上游链路」。
            val remoteParent = traceparentOf(params)
            // 异步任务（T-056 / D17 G-15）：`_meta.task` 出现即「先回句柄、后轮询」，
            // 连接不再被长任务钉死；工具名未知这种「马上就能判定」的错误仍同步抛出。
            val taskMeta = (params["_meta"] as? JsonObject)?.get("task")
            val wantsTask = taskMeta != null &&
                !(taskMeta is JsonPrimitive && !taskMeta.isString && taskMeta.content == "false")
            if (wantsTask) {
                // T-166：客户端声明了官方 Tasks 扩展能力 → 任务中途需要输入时走
                // 「input_required + tasks/update 交付答案」的新流程；未声明的客户端
                // 维持既有直发通道（不改变既有行为——拒旧等于断供）。
                val taskInputFlow = clientDeclaresTasksExtension(params)
                startTask(tool, name, args, taskMeta as? JsonObject ?: JsonObject(emptyMap()), remoteParent, taskInputFlow)
            } else {
                // T-165：MRTR（2026-07-28）——modern 请求（_meta 声明 2026-07-28）走
                // InputRequiredResult + inputResponses 重试；遗留客户端维持直发通道
                //（拒旧等于断供）。重试时 requestState 必须过校验（HMAC/来源绑定/TTL）。
                val mrtr = mrtrContextOf(params, name, args)
                val call = {
                    val started = System.nanoTime()
                    val ctx = mrtr
                    try {
                        val run = {
                            tracedTool(name, remoteParent) {
                                toolCallEnvelope(name, args, executeTool(tool, args), started)
                            }
                        }
                        if (ctx == null) run() else MrtrContext.with(ctx) { run() }
                    } catch (ask: McpInputRequiredException) {
                        // MRTR 控制流：提问点停机，转 InputRequiredResult 信封（不是 isError）
                        inputRequiredEnvelope(ask, McpToolNames.exposed(name), args, requireNotNull(ctx), McpSessionContext.current())
                    } catch (cancelled: McpRequestCancelledException) {
                        // 取消是协议层事件（-32800），不是工具执行错误——保持外抛
                        throw cancelled
                    } catch (e: Exception) {
                        // T-079（W-3）：工具执行错误按 MCP 规范回 isError 结果（不是协议错误），
                        // 带 nextAction；tracedTool 已在外层记完 metrics/错误状态，这里只包装回执
                        toolErrorEnvelope(e)
                    }
                }
                // 有 id 才登记取消令牌（裸 dispatch 调用没有「另一条连接」来取消它）
                if (requestId == null) call()
                else McpRequestCancellation.withRequest(McpSessionContext.current(), requestId, call)
            }
        }

        // Tasks 扩展（T-056 / D17 G-15；T-166 起对齐官方 wire 格式）：
        //   tasks/get    → `GetTaskResult`（resultType=complete + DetailedTask）
        //   tasks/update → `UpdateTaskResult`（**空确认**；交付 inputResponses）
        //   tasks/cancel → `CancelTaskResult`（**空确认**）
        "tasks/get" -> taskStore.snapshot(liveTask(params))
        "tasks/update" -> {
            val taskId = taskIdOf(params)
            val task = taskStore.get(taskId) ?: throw McpInvalidParamsException(unknownTaskMessage(taskId))
            // 官方：inputResponses 交付给等待输入的任务（未知/已满足的 key 一律忽略）
            val responses = params["inputResponses"] as? JsonObject
            if (responses != null && responses.isNotEmpty()) {
                taskStore.deliverAnswers(
                    taskId,
                    responses.mapValues { (_, value) -> value as? JsonObject ?: JsonObject(emptyMap()) },
                )
            }
            // 兼容：仓内既有语义（非官方字段）——ttlMs 续期保活
            taskTtlOf(params)?.let { taskStore.update(taskId, it) }
            buildJsonObject { put("resultType", "complete") }
        }
        "tasks/cancel" -> {
            val taskId = taskIdOf(params)
            taskStore.requestCancel(taskId) ?: throw McpInvalidParamsException(unknownTaskMessage(taskId))
            buildJsonObject { put("resultType", "complete") }
        }

        else -> throw McpMethodNotFoundException(method)
    }

    // ---- T-165 · MRTR（2026-07-28 Multi Round-Trip Requests） ----

    /**
     * MRTR 上下文开关：modern 请求（`_meta` 声明 `io.modelcontextprotocol/protocolVersion`
     * == [McpProtocol.LATEST]）→ 开上下文（首次调用为空；带 inputResponses/requestState
     * 的重试先过 [McpRequestState.decode] 校验）；遗留请求 → null（直发通道语义不变）。
     *
     * 预答案形态：带 `inputResponses` 但不带 `requestState` = 首次调用就预填答案
     * （spec 口径：客户端没有收到过 InputRequiredResult 就没有 state 可回显）——收下，
     * 不做 state 校验。
     */
    private fun mrtrContextOf(params: JsonObject, tool: String, args: JsonObject): MrtrContext? {
        val meta = params["_meta"] as? JsonObject ?: return null
        val modern = (meta["io.modelcontextprotocol/protocolVersion"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content == McpProtocol.LATEST
        if (!modern) return null
        val caps = meta["io.modelcontextprotocol/clientCapabilities"] as? JsonObject
        val capabilityAllowed = caps == null || caps.containsKey("elicitation")
        val responses = params["inputResponses"] as? JsonObject
        val state = (params["requestState"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when {
            responses == null && state == null -> MrtrContext.open(capabilityAllowed)
            state == null -> MrtrContext.retry(
                responses = responses?.filterValues { it is JsonObject }?.mapValues { it.value as JsonObject } ?: emptyMap(),
                priorAnswers = emptyMap(),
                capabilityAllowed = capabilityAllowed,
            )
            else -> {
                val decoded = McpRequestState.decode(
                    state, "tools/call", McpToolNames.exposed(tool), argsDigest(args), McpSessionContext.current(),
                )
                MrtrContext.retry(
                    responses = responses?.filterValues { it is JsonObject }?.mapValues { it.value as JsonObject } ?: emptyMap(),
                    priorAnswers = decoded.answers.toMap(),
                    capabilityAllowed = capabilityAllowed,
                )
            }
        }
    }

    /**
     * MRTR 信封：提问点 → `resultType: "input_required"` + `inputRequests`（单问：
     * key 内容派生）+ 签发的 requestState（封入本次已消费的答案，下一轮接续）。
     * spec：MUST 至少带 inputRequests/requestState 之一——本实现恒带两者。
     */
    private fun inputRequiredEnvelope(
        ask: McpInputRequiredException,
        tool: String,
        args: JsonObject,
        ctx: MrtrContext,
        sessionId: String,
    ): JsonObject = buildJsonObject {
        put("resultType", "input_required")
        put("inputRequests", buildJsonObject {
            put(ask.key, buildJsonObject {
                put("method", "elicitation/create")
                put("params", buildJsonObject {
                    put("mode", "form")
                    put("message", ask.question)  // lint:allow zh-first（提问文本原样透传，来源是工具入参）
                    put("requestedSchema", ask.requestedSchema)
                })
            })
        })
        put("requestState", McpRequestState.issue(
            "tools/call", tool, argsDigest(args), sessionId, ctx.consumed.toList(),
        ))
    }

    // ---- Tasks 扩展（T-056 / D17 G-15） ----

    /**
     * 异步执行一次 `tools/call`：立即返回任务句柄，执行线程跑完后把完整结果落进任务表。
     *
     * 与同步路径**同一套**执行语义：会话上下文、事件会话、进度回调、协作式取消、
     * 导入指针更新、溯源落账都不变（共用 [executeTool] / [toolCallEnvelope]），
     * 区别只是结果写进任务表、由 `tasks/get` 取走。
     *
     * T-166：返回官方 `CreateTaskResult`（`resultType: "task"` + Task 字段）；
     * [taskInputFlow] 为真时，任务中途需要用户输入会进入 `input_required`，
     * 客户端经 `tasks/update` 交付答案后任务继续（见 [runTaskTool]）。
     */
    private fun startTask(
        tool: AgentTool,
        name: String,
        args: JsonObject,
        taskMeta: JsonObject,
        remoteParent: OtelSpanContext? = null,
        taskInputFlow: Boolean = false,
    ): JsonObject {
        val sessionId = McpSessionContext.current()
        val task = try {
            taskStore.create(McpToolNames.exposed(name), sessionId, taskTtlOf(taskMeta))
        } catch (e: IllegalStateException) {
            // 容量满 = 当前服务状态下无法受理：-32602 让客户端稍后重试，而不是假装拿到任务
            throw McpInvalidParamsException(e.message ?: "任务数已达上限")  // lint:allow zh-first（用户面中文输出，迁自 McpBridge 原基线）
        }
        val worker = Thread({
            try {
                val envelope = McpSessionContext.with(sessionId) {
                    com.stragent.core.ports.UiEventBroadcaster.withSession(sessionId) {
                        ProgressBus.with({ message -> taskStore.recordProgress(task.id, message) }) {
                            // T-172：正文增量 → 任务表的「已成稿章节」（增量可用产物）。
                            // 拼装口径与客户端的正文流协议一致：同章 attempt 变大即覆盖旧片段。
                            com.stragent.core.ai.BodyStream.with(
                                { delta ->
                                    taskStore.recordPartial(task.id, delta.chapter, delta.attempt, delta.text)
                                },
                            ) {
                                // 令牌登记在任务 id 上：`tasks/cancel` 与 `notifications/cancelled`
                                // 都走 McpRequestCancellation，工具拿到的 signal 是同一个
                                McpRequestCancellation.withRequest(sessionId, task.id) {
                                    // 取消可能先于登记到达（快速 tasks/cancel）：登记后立刻自查一次
                                    if (task.cancelRequested) throw McpRequestCancelledException()
                                    runTaskTool(tool, name, args, remoteParent, task, taskInputFlow)
                                }
                            }
                        }
                    }
                }
                // 工具没理会取消、自己跑完了：如实记 completed（结果可用，不假装取消成功）
                taskStore.complete(task.id, envelope)
            } catch (cancelled: McpRequestCancelledException) {
                taskStore.cancel(task.id, cancelled.message)
            } catch (error: Throwable) {
                // 任务线程的失败不能静默：落 FAILED，客户端轮询能看到原因与下一步动作（T-079）
                val message = error.message ?: error.javaClass.simpleName
                log.warn(
                    "异步任务失败",  // lint:allow zh-first（用户面中文输出，迁自 McpBridge 原基线）
                    "task" to task.id,
                    "tool" to task.tool,
                    "error" to message,
                )
                taskStore.fail(task.id, message, ErrorActions.nextAction(message), errorCodeOf(error))
            }
        }, "mcp-task-${task.id}")
        worker.isDaemon = true
        worker.start()
        return taskStore.createResult(task)
    }

    /**
     * T-166：任务体执行（含「中途需要输入」的续跑）。
     *
     * 官方模型：任务在 `input_required` 停下，客户端经 `tasks/update` 交付答案，
     * 服务端**带上答案重跑**这次调用并继续。边界（诚实）：重跑意味着「提问之前
     * 已发生的副作用会重复」——本仓唯一会提问的工具是 `stragent_elicit_input`
     * （只读），实际风险为零；工具侧若要在有副作用的流程里提问，应把提问放在
     * 副作用之前。
     */
    private fun runTaskTool(
        tool: AgentTool,
        name: String,
        args: JsonObject,
        remoteParent: OtelSpanContext?,
        task: McpTaskStore.Task,
        taskInputFlow: Boolean,
    ): JsonObject {
        // 累计已交付的答案（多问流程：前一问的答案必须在重跑时仍可命中）
        var priorAnswers: Map<String, JsonObject> = emptyMap()
        var round = 0
        while (true) {
            val ctx = if (priorAnswers.isEmpty()) {
                MrtrContext.open(capabilityAllowed = true)
            } else {
                MrtrContext.retry(responses = emptyMap(), priorAnswers = priorAnswers, capabilityAllowed = true)
            }
            try {
                return MrtrContext.with(ctx) {
                    tracedTool(name, remoteParent) {
                        val started = System.nanoTime()
                        toolCallEnvelope(name, args, executeTool(tool, args), started)
                    }
                }
            } catch (ask: McpInputRequiredException) {
                // 未启用任务输入流（客户端没声明官方扩展能力）→ 交回上层按既有语义处理
                if (!taskInputFlow) throw ask
                round += 1
                if (round > McpTaskStore.MAX_INPUT_ROUNDS) {
                    throw IllegalStateException("输入轮次超过上限（${McpTaskStore.MAX_INPUT_ROUNDS}）")  // lint:allow zh-first（用户面中文输出，任务失败原因）
                }
                val future = taskStore.awaitInput(task.id, mapOf(ask.key to inputRequestEnvelope(ask)))
                    ?: throw IllegalStateException("任务已不存在，无法等待输入")  // lint:allow zh-first（用户面中文输出，任务失败原因）
                val delivered = awaitAnswers(future, task)
                    ?: throw IllegalStateException("等待客户端输入超时（${McpTaskStore.INPUT_WAIT_MS / 60_000} 分钟）")  // lint:allow zh-first（用户面中文输出，任务失败原因）
                priorAnswers = priorAnswers + delivered
            }
        }
    }

    /**
     * 等客户端交付答案：分片等待 + 自查取消标志。
     *
     * 分片（200ms）而不是一次性长等——否则「任务被取消」这件事要等到超时才被注意到，
     * 工作线程会被白白占住（取消是协作式的，但等待本身可以随时中断）。
     */
    private fun awaitAnswers(
        future: java.util.concurrent.CompletableFuture<Map<String, JsonObject>>,
        task: McpTaskStore.Task,
    ): Map<String, JsonObject>? {
        val deadline = System.nanoTime() + McpTaskStore.INPUT_WAIT_MS * 1_000_000
        while (true) {
            if (task.cancelRequested) throw McpRequestCancelledException()
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000
            if (remainingMs <= 0) return null
            try {
                return future.get(minOf(200L, remainingMs), TimeUnit.MILLISECONDS)
            } catch (timeout: TimeoutException) {
                // 继续等（下一轮自查取消标志与截止时间）
            }
        }
    }

    /** 官方 `inputRequests` 的值形状：服务端请求对象（与 MRTR 信封同形制） */
    private fun inputRequestEnvelope(ask: McpInputRequiredException): JsonObject = buildJsonObject {
        put("method", "elicitation/create")
        put("params", buildJsonObject {
            put("mode", "form")
            put("message", ask.question)  // lint:allow zh-first（提问文本原样透传，来源是工具入参）
            put("requestedSchema", ask.requestedSchema)
        })
    }

    /**
     * T-166：客户端是否声明了官方 Tasks 扩展能力
     * （`_meta."io.modelcontextprotocol/clientCapabilities".extensions."io.modelcontextprotocol/tasks"`）。
     */
    private fun clientDeclaresTasksExtension(params: JsonObject): Boolean {
        val meta = params["_meta"] as? JsonObject ?: return false
        val capabilities = meta["io.modelcontextprotocol/clientCapabilities"] as? JsonObject ?: return false
        val extensions = capabilities["extensions"] as? JsonObject ?: return false
        return extensions.containsKey(TASKS_EXTENSION_ID)
    }

    /** 取在途/未过期任务；不存在统一按 -32602 报「未知或已过期」 */
    private fun liveTask(params: JsonObject): McpTaskStore.Task {
        val taskId = taskIdOf(params)
        return taskStore.get(taskId) ?: throw McpInvalidParamsException(unknownTaskMessage(taskId))
    }

    private fun taskIdOf(params: JsonObject): String =
        (params["taskId"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            ?: throw McpInvalidParamsException("缺少 taskId（tasks/get|update|cancel 的必填参数）")  // lint:allow zh-first（用户面中文输出，迁自 McpBridge 原基线）

    private fun unknownTaskMessage(taskId: String): String =
        "未知或已过期的任务：$taskId（终态任务保留 ${McpTaskStore.DEFAULT_TTL_MS / 60_000} 分钟后清理）"  // lint:allow zh-first（用户面中文输出，迁自 McpBridge 原基线）

    /**
     * 任务 TTL：`ttlMs` 数字优先，其次 `ttl` 的 ISO-8601 时长（`PT30M`）；
     * 都不给返回 null（交给 [McpTaskStore] 用默认值，`tasks/update` 即「续一期」）。
     */
    private fun taskTtlOf(params: JsonObject): Long? {
        (params["ttlMs"] as? JsonPrimitive)?.content?.toLongOrNull()?.let { return it }
        val iso = (params["ttl"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return runCatching { java.time.Duration.parse(iso).toMillis() }.getOrElse {
            throw McpInvalidParamsException("ttl 不是合法的 ISO-8601 时长：$iso")  // lint:allow zh-first（用户面中文输出，迁自 McpBridge 原基线）
        }
    }

    /** 解析 `params._meta.traceparent`（W3C trace-context）；缺失/非法一律 null——不阻断调用 */
    private fun traceparentOf(params: JsonObject): OtelSpanContext? {
        val raw = ((params["_meta"] as? JsonObject)?.get("traceparent") as? JsonPrimitive)
            ?.takeIf { it.isString }?.content ?: return null
        return W3cTraceparent.parse(raw)?.toContext()
    }

    /**
     * T-166：任务失败时给官方 `error.code` 的 JSON-RPC 错误码（按异常类型映射）。
     * 客户端据此区分「改参数能好」（-32602）与「服务端故障」（-32603）。
     */
    private fun errorCodeOf(error: Throwable): Int = when (error) {
        is McpInvalidParamsException -> -32602
        is McpMethodNotFoundException -> -32601
        is McpHeaderMismatchException -> -32020
        is McpUnsupportedProtocolVersionException -> -32022
        is McpRequestCancelledException -> -32800
        else -> McpTaskStore.DEFAULT_ERROR_CODE
    }

    /**
     * 给一次工具调用套上 OTel span（T-056 / D17 G-16）与 metrics 埋点（T-065），并沿
     * [McpTraceContext] 把链路交给工具实现（报告的根 span 挂到这条链上）。
     *
     * - 配置了追踪导出器：建 SERVER span `tools/call <name>`（`gen_ai.operation.name=execute_tool`），
     *   结束时记状态、交给异步导出队列（不阻塞业务）；**采样判定**（T-065）：有上游 traceparent
     *   遵从其 sampled 标志（parent-based），新 trace 根按 [sampler] 比率确定性判定，
     *   未采样的 span 由 [OtelTraces.export] 统一过滤——本地创建便宜，导出才有成本。
     * - 配置了 metrics：记 `str_agent.tool.calls`（tool.name × status）与
     *   `str_agent.tool.duration`——与追踪**相互独立**：只配 metrics 不配 traces 也照常记。
     * - 都没配：不建 span、不记数，只把上游 [remoteParent] 传下去——链路 id 仍一致，零成本。
     *
     * 纪律：可观测是增强，不是依赖——span 的结束/导出与 metrics 记录都在 `finally` 里，
     * 异常原样外抛；metrics 的 status 来自异常类型（**取消 ≠ 失败**，分开计数）。
     */
    private fun <T> tracedTool(toolName: String, remoteParent: OtelSpanContext?, block: () -> T): T {
        val exposed = McpToolNames.exposed(toolName)
        val traces = otel
        // 采样判定（T-065）：先 id 后判定——新根先生成 traceId，再由采样器决定是否导出；
        // 有上游时 span 一律继承父上下文的采样标志（parent-based，决定只在根上做一次）
        val span: OtelSpan? = when {
            traces == null -> null
            remoteParent != null -> OtelSpan.child("tools/call $exposed", remoteParent, OtelSpan.Kind.SERVER)
            sampler == null -> OtelSpan.root("tools/call $exposed", OtelSpan.Kind.SERVER)
            else -> {
                val traceId = TraceIds.traceId()
                OtelSpan(
                    traceId,
                    TraceIds.spanId(),
                    null,
                    "tools/call $exposed",
                    OtelSpan.Kind.SERVER,
                    sampled = sampler.decide(traceId, hasParent = false, parentSampled = false),
                )
            }
        }
        val startedNanos = System.nanoTime()
        var status = "ok"
        span?.apply {
            set("mcp.tool.name", exposed)
            set("mcp.session.id", McpSessionContext.current())
            // T-164：gen_ai 属性名走单一映射层（com.stragent.core.observability.GenAiAttrs）
            set(com.stragent.core.observability.GenAiAttrs.OPERATION_NAME, "execute_tool")
            set(com.stragent.core.observability.GenAiAttrs.TOOL_NAME, exposed)
        }
        try {
            val context = span?.spanContext() ?: remoteParent
            val result = McpTraceContext.with(context, block)
            span?.ok()
            return result
        } catch (error: Throwable) {
            // 取消 ≠ 失败：MCP 协作式取消映射出的异常单独记状态（metrics 口径）。
            // T-165：input_required 也独立记状态——它是 spec 定义的独立结果形态，
            // 不是 isError（工具没失败，只是停下来等用户输入）。
            status = when {
                error is McpRequestCancelledException ||
                    error is com.stragent.core.ports.AgentCancelled ||
                    error is com.stragent.core.ai.GenerationCancelled -> "cancelled"
                error is McpInputRequiredException -> "input_required"
                else -> "error"
            }
            if (error is McpInputRequiredException) span?.ok() else span?.error(error.message ?: error.javaClass.simpleName)
            throw error
        } finally {
            span?.end()
            traces?.export(listOfNotNull(span))
            metrics?.let { m ->
                val durationMs = (System.nanoTime() - startedNanos) / 1_000_000.0
                val attrs = mapOf("tool.name" to exposed, "status" to status)
                m.counter(StrAgentMetrics.TOOL_CALLS).add(1, attrs)
                m.histogram(StrAgentMetrics.TOOL_DURATION).record(durationMs, attrs)
            }
        }
    }

    /**
     * 执行工具并返回原始结果；取消语义统一映射为 [McpRequestCancelledException]
     * （-32800，HTTP 与 stdio 各自的 catch 分支据此回错误帧）。
     */
    private fun executeTool(tool: AgentTool, args: JsonObject): JsonObject = try {
        tool.invoke(args)
    } catch (cancelled: com.stragent.core.ports.AgentCancelled) {
        throw McpRequestCancelledException(cancelled.message ?: Messages.get("error.cancelled"))
    } catch (cancelled: com.stragent.core.ai.GenerationCancelled) {
        throw McpRequestCancelledException(cancelled.message ?: Messages.get("error.cancelled"))
    }

    /**
     * 把工具原始结果包装成 `tools/call` 结果：导入成功推进会话指针、落溯源、
     * text + structuredContent 双发。同步与异步路径共用，保证两条路径逐字一致。
     */
    private fun toolCallEnvelope(name: String, args: JsonObject, result: JsonObject, startedNanos: Long): JsonObject {
        // 导入成功 → 会话指针指向新数据集（按会话分桶，不串别的会话）
        if (McpToolNames.exposed(name) in POINTER_IMPORT_TOOLS && registry != null) {
            (result["datasetId"] as? JsonPrimitive)?.content?.let {
                registry.setCurrent(McpSessionContext.current(), it)
            }
        }
        recordProvenance(McpToolNames.exposed(name), args, result, (System.nanoTime() - startedNanos) / 1_000_000)
        val envelope = buildJsonObject {
            put("resultType", "complete")
            put("content", kotlinx.serialization.json.JsonArray(
                listOf(buildJsonObject { put("type", "text"); put("text", result.toString()) })
            ))
            // T-056：所有工具都声明输出契约，一律双发——text 给人/模型读，
            // structuredContent 给客户端解析（宽松 schema 只承诺对象，不固化字段）
            put("structuredContent", result)
        }
        // T-154：响应侧统一字节闸——请求侧 8MB 有闸（T-059），响应侧此前没有对应物；
        // 超限不截断（半截数据比明确报错更危险），走既有 T-079 机器包成 isError 回执，
        // 提示缩小请求范围或改走导出。
        ResponseCap.check(envelope)
        return envelope
    }

    /**
     * T-079（W-3）：把工具执行异常包装成 `isError` 结果回执（MCP 规范：执行错误进结果、
     * 协议错误走 JSON-RPC error）。回执三件套：
     *   content text    = 错误消息 + 「下一步：动作」（客户端只展示 text 也能看到动作）
     *   structuredContent = {ok:false, error, nextAction}（客户端程序化取动作）
     *   isError = true
     * 调用点在 [tracedTool] 外层——metrics/追踪已按异常记完 error 状态，这里只包装。
     */
    private fun toolErrorEnvelope(e: Exception): JsonObject {
        val message = e.message ?: e.javaClass.simpleName
        val nextAction = ErrorActions.nextAction(message)
        val text = Messages.get("error.next_action_prefix").replace("{0}", "$message\n$nextAction")
        return buildJsonObject {
            // T-163：执行错误也是 Result（isError:true），不是协议错误帧——同样带判别字段
            put("resultType", "complete")
            put(
                "content",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        },
                    ),
                ),
            )
            put(
                "structuredContent",
                buildJsonObject {
                    put("ok", false)
                    put("error", message)
                    put("nextAction", nextAction)
                },
            )
            put("isError", true)
        }
    }

    /**
     * 把一次工具调用记进溯源清单（数据集句柄取自参数，或会话当前数据集）。
     *
     * 审计卫生（T-046）：落账的是**脱敏入参**——密码/密钥字段替换为 `***`，
     * SQL 只留结构与脱敏语句，原文哈希单列 `sqlHash`；会话 id 一并记下。
     * 工具调用本身仍收到原始参数，记录不改执行语义。
     */
    private fun recordProvenance(tool: String, args: JsonObject, result: JsonObject, elapsedMs: Long) {
        val recorder = provenance ?: return
        val datasetId = (args["datasetId"] as? JsonPrimitive)?.content
            ?: registry?.currentId(McpSessionContext.current())
            ?: datasets.currentId()
            ?: return
        val sql = (args["sql"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val safeArgs = ProvenanceSanitizer.sanitizeArgs(args)
        recorder.record(
            datasetId,
            com.stragent.core.trace.ProvenanceStep(
                tool = tool,
                args = safeArgs,
                // 哈希对「实际落账的参数」计算：清单内部自洽（原始 SQL 的哈希在 sqlHash）
                argsHash = com.stragent.core.trace.ProvenanceManifest.hashOf(safeArgs),
                resultHash = com.stragent.core.trace.ProvenanceManifest.hashOf(result),
                datasetHash = "",
                elapsedMs = elapsedMs,
                sessionId = McpSessionContext.current(),
                sqlHash = sql?.let { com.stragent.core.trace.ProvenanceManifest.hashOf(it) },
                sqlMasked = sql?.let { ProvenanceSanitizer.maskSql(it) },
                // T-073：数值面随步骤落账（追问归因的锚点；旧清单无此字段 = 无法匹配）
                numTokens = com.stragent.core.trace.numericSurface(safeArgs, result),
            ),
        )
    }
}
