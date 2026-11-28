package com.stragent.data

import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * MCP Tasks 扩展的**单机任务表**（T-056 / D17 G-15；T-166 起对齐官方扩展 wire 格式）。
 *
 * 目标：长任务（报告生成、追问、导出、Python、数据库查询）不再把客户端连接
 * 钉死在一个 HTTP 请求上——客户端先拿 `taskId`，再用 `tasks/get` 轮询，
 * `tasks/update` 交付中途输入，`tasks/cancel` 取消。
 *
 * **T-166：与官方 `io.modelcontextprotocol/tasks` 扩展逐字段对齐**（规范原文：
 * `ext-tasks` 仓 `schema/2026-07-28/schema.ts`）——对外形状：
 *
 * | 官方字段 | 本实现 |
 * |---|---|
 * | `taskId` / `status` | 同名同义 |
 * | `statusMessage?` | 扁平字段（仓内原为 `progress.message` 嵌套，另留兼容字段） |
 * | `createdAt` / `lastUpdatedAt` | ISO 8601（`lastUpdatedAt` 为官方名，`updatedAt` 保留兼容） |
 * | `ttlMs: number \| null` | **剩余**存活毫秒数（官方允许该值随任务生命周期变化） |
 * | `pollIntervalMs?` | 建议轮询间隔（客户端 SHOULD 遵守） |
 * | `inputRequests`（`input_required` 时） | 待答问题表（key → 服务端请求对象） |
 * | `result`（`completed` 时） | 原请求的结果（`tools/call` 信封） |
 * | `error`（`failed` 时） | **JSON-RPC error 对象** `{code, message, data?}`（仓内原为裸字符串） |
 *
 * 纪律：
 *  1. **取消是协作式的**：`tasks/cancel` 只置标志（并登记到
 *     [McpRequestCancellation]），真正停下要工具在可停点自查；工具不理会时
 *     任务会保持 `working`，如实反映而不是假装已取消。
 *  2. **过期只作用于终态任务**：`working` / `input_required` 任务不会因为 TTL
 *     到点被删除（删掉就无法取消/取结果/交付输入）；终态任务保留 `ttlMs`
 *     （默认 15 分钟），到点后 `tasks/get` 报「未知或已过期」。
 *  3. **有界**：最多 [maxTasks] 条；满了先清过期终态，再淘汰最老的终态；
 *     全是 `working` 时拒绝新建（不排队、不无限增长）。
 *  4. **T-204 可选落盘恢复**：`storePath` 非空时每次状态跃迁追加 JSONL（创建/终态/
 *     input_required/恢复执行），启动时恢复——终态任务句柄与结果在 TTL 语义内继续可取；
 *     **非终态任务如实转 `failed`**（statusMessage 写明宿主重启中断），不假装可续跑
 *     （执行线程已死，续跑是假话）。落盘是 best-effort：写失败降级内存态
 *     （[persistHealthy] 可观测）；缺省 null = 纯内存态（T-056 原语义不变）。
 *
 * 线程语义：所有字段 `@Volatile`，内表 `ConcurrentHashMap`；快照是读时点副本。
 */
class McpTaskStore(
    private val maxTasks: Int = 128,
    private val defaultTtlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** T-204：JSONL 落盘路径（可选）；null = 纯内存态 */
    private val storePath: java.nio.file.Path? = null,
) {
    /** T-204 落盘降级可观测：storePath 为空或写失败后为 false（best-effort 诚实口径） */
    @Volatile
    var persistHealthy: Boolean = true
        private set

    /** 任务状态（官方 `TaskStatus` 五值） */
    enum class Status(val wire: String) {
        WORKING("working"),
        INPUT_REQUIRED("input_required"),
        COMPLETED("completed"),
        FAILED("failed"),
        CANCELLED("cancelled"),
        ;

        /** 终态：completed / failed / cancelled —— 到达后状态不再变化 */
        val terminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED
    }

    class Task internal constructor(
        val id: String,
        val tool: String,
        val sessionId: String,
        val createdAt: Long,
        ttlMs: Long,
    ) {
        @Volatile var status: Status = Status.WORKING
        @Volatile var updatedAt: Long = createdAt
        @Volatile var expiresAt: Long = createdAt + ttlMs
        @Volatile var cancelRequested: Boolean = false
        @Volatile var progressCount: Long = 0
        @Volatile var progressMessage: String? = null

        /** 官方字段：`statusMessage`——当前状态的人读描述（任意状态都可有） */
        @Volatile var statusMessage: String? = null

        /** 官方字段：`inputRequests`——`input_required` 时待客户端回答的问题表 */
        @Volatile var inputRequests: Map<String, JsonObject> = emptyMap()

        /** 答案交付通道（客户端经 `tasks/update` 交付 → 完成此 future） */
        @Volatile var answers: CompletableFuture<Map<String, JsonObject>>? = null

        /**
         * T-172：已写出的正文片段（章号 → (第几次生成, 片段)）。
         *
         * 拼装口径与客户端的正文流协议**一致**：按章号升序、同章取**最新一次**生成——
         * 重写时旧片段是垃圾，必须丢掉（否则草稿里会出现同一章的两份）。
         */
        val partialFragments = java.util.concurrent.ConcurrentHashMap<Int, Pair<Int, String>>()

        /** 完整的 `tools/call` 结果（content + structuredContent），完成时才有 */
        @Volatile var result: JsonObject? = null

        /** 失败原因（`failed` 状态；官方 `error.message` 的来源） */
        @Volatile var error: String? = null

        /** 失败对应的 JSON-RPC 错误码（官方 `error.code`） */
        @Volatile var errorCode: Int? = null

        /** 取消原因（`cancelled` 状态；官方无 `error` 字段，原因走 `statusMessage`） */
        @Volatile var cancelReason: String? = null

        /**
         * T-079：失败任务的下一步动作（fail 时由 ErrorActions 模板生成；取消不带）。
         */
        @Volatile var nextAction: String? = null
    }

    private val tasks = ConcurrentHashMap<String, Task>()

    // ---- T-204：JSONL 落盘恢复（best-effort） ----

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private val persistLock = Any()

    /** 每次状态跃迁追加一行 JSONL（写失败降级内存态，persistHealthy 可观测） */
    private fun persist(task: Task) {
        if (storePath == null) return
        val line = lineOf(task)
        try {
            synchronized(persistLock) {
                java.nio.file.Files.writeString(
                    storePath,
                    line + "\n",
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND,
                )
            }
        } catch (_: Exception) {
            persistHealthy = false
        }
    }

    /** 任务快照 → JSONL 行（读 @Volatile 字段的时点副本；result 直接嵌 JSON 对象） */
    private fun lineOf(task: Task): String {
        val obj = buildJsonObject {
            put("id", task.id)
            put("tool", task.tool)
            put("sessionId", task.sessionId)
            put("status", task.status.wire)
            put("createdAt", task.createdAt)
            put("updatedAt", task.updatedAt)
            put("expiresAt", task.expiresAt)
            task.statusMessage?.let { put("statusMessage", it) }
            task.error?.let { put("error", it) }
            task.errorCode?.let { put("errorCode", it) }
            task.result?.let { put("result", it) }
        }
        return obj.toString()
    }

    init {
        if (storePath != null) {
            val recovered = mutableListOf<Pair<Long, Task>>()
            runCatching {
                if (java.nio.file.Files.exists(storePath)) {
                    java.nio.file.Files.readAllLines(storePath).forEachIndexed { idx, rawLine ->
                        val line = rawLine.trim()
                        if (line.isEmpty()) return@forEachIndexed
                        runCatching {
                            val obj = json.parseToJsonElement(line).jsonObject
                            fun str(key: String): String? = (obj[key] as? JsonPrimitive)?.content
                            fun lng(key: String): Long? = (obj[key] as? JsonPrimitive)?.content?.toLongOrNull()
                            val id = str("id") ?: return@runCatching
                            val tool = str("tool") ?: return@runCatching
                            val sessionId = str("sessionId") ?: return@runCatching
                            val createdAt = lng("createdAt") ?: return@runCatching
                            val statusWire = str("status") ?: return@runCatching
                            val expiresAt = lng("expiresAt") ?: (createdAt + DEFAULT_TTL_MS)
                            val t = Task(
                                id = id,
                                tool = tool,
                                sessionId = sessionId,
                                createdAt = createdAt,
                                ttlMs = clampTtl(null),
                            )
                            t.updatedAt = lng("updatedAt") ?: createdAt
                            t.expiresAt = expiresAt
                            t.statusMessage = str("statusMessage")
                            t.error = str("error")
                            t.errorCode = lng("errorCode")?.toInt()
                            (obj["result"] as? JsonObject)?.let { t.result = it }
                            t.status = when (statusWire) {
                                Status.COMPLETED.wire -> Status.COMPLETED
                                Status.FAILED.wire -> Status.FAILED
                                Status.CANCELLED.wire -> Status.CANCELLED
                                // 非终态统一落 WORKING，下方如实转 FAILED
                                else -> Status.WORKING
                            }
                            val isTerminal = t.status.terminal
                            if (!isTerminal) {
                                // 非终态：执行线程已死，如实转 failed（不假装可续跑）
                                t.status = Status.FAILED
                                t.error = t.error ?: "宿主重启，任务中断"  // lint:allow zh-first（T-204 恢复态用户面文案，D19/D21 语言定位）
                                t.statusMessage = "宿主重启，任务中断（恢复前状态 ${statusWire}）"  // lint:allow zh-first（T-204 恢复态用户面文案，D19/D21 语言定位）
                                t.expiresAt = clock() + defaultTtlMs
                            }
                            recovered.add((t.updatedAt) to t)
                        }.onFailure {
                            // 损坏行跳过（容错加载），不中断其余行恢复
                        }
                    }
                }
            }.onFailure {
                persistHealthy = false
            }
            // 有界恢复：超出 maxTasks 时保留最新的
            recovered.sortByDescending { it.first }
            recovered.take(maxTasks).forEach { (_, t) -> tasks[t.id] = t }
        }
    }

    /** 新建任务；满了先清理，仍满则抛错（调用方决定错误码） */
    fun create(tool: String, sessionId: String, ttlMs: Long?): Task {
        sweep()
        if (tasks.size >= maxTasks && !evictOldestTerminal()) {
            throw IllegalStateException("任务数已达上限（$maxTasks），请稍后重试")
        }
        val now = clock()
        val task = Task(
            id = "task_" + UUID.randomUUID().toString().replace("-", ""),
            tool = tool,
            sessionId = sessionId,
            createdAt = now,
            ttlMs = clampTtl(ttlMs),
        )
        tasks[task.id] = task
        persist(task)
        return task
    }

    /** 取任务；终态且过期视为不存在（顺手清理） */
    fun get(id: String): Task? {
        sweep()
        val task = tasks[id] ?: return null
        if (task.status.terminal && clock() > task.expiresAt) {
            tasks.remove(id, task)
            return null
        }
        return task
    }

    /** 续期（客户端轮询保活）；上限 [MAX_TTL_MS]，返回 null = 不存在/已过期 */
    fun update(id: String, ttlMs: Long?): Task? {
        val task = get(id) ?: return null
        val extension = clampTtl(ttlMs)
        task.expiresAt = maxOf(task.expiresAt, clock() + extension)
        task.updatedAt = clock()
        return task
    }

    /** 请求取消：只置标志 + 登记协作式取消，状态交给执行线程如实收尾 */
    fun requestCancel(id: String): Task? {
        val task = get(id) ?: return null
        task.cancelRequested = true
        task.updatedAt = clock()
        McpRequestCancellation.cancel(task.sessionId, task.id)
        return task
    }

    fun recordProgress(id: String, message: String) {
        tasks[id]?.let {
            it.progressCount += 1
            it.progressMessage = message
            it.statusMessage = message
            it.updatedAt = clock()
        }
    }

    fun complete(id: String, result: JsonObject) {
        tasks[id]?.let {
            it.result = result
            it.status = Status.COMPLETED
            it.statusMessage = "completed"
            it.updatedAt = clock()
            it.expiresAt = clock() + defaultTtlMs
            persist(it)
        }
    }

    /**
     * 落 FAILED；[nextAction] 由调用方用 ErrorActions 按错误消息生成（T-079），null 时不带出。
     * [code] 是官方 `error.code`（JSON-RPC 错误码）。
     */
    fun fail(id: String, error: String, nextAction: String? = null, code: Int? = null) {
        tasks[id]?.let {
            it.error = error
            it.nextAction = nextAction
            it.errorCode = code
            it.status = Status.FAILED
            it.statusMessage = error
            it.updatedAt = clock()
            it.expiresAt = clock() + defaultTtlMs
            persist(it)
        }
    }

    fun cancel(id: String, reason: String? = null) {
        tasks[id]?.let {
            it.cancelReason = reason
            it.status = Status.CANCELLED
            it.statusMessage = reason ?: "cancelled"
            it.updatedAt = clock()
            it.expiresAt = clock() + defaultTtlMs
            persist(it)
        }
    }

    /**
     * T-166：进入 `input_required`——登记待答问题并返回答案交付通道。
     *
     * 官方语义：任务在等待客户端输入期间，`tasks/get` 带 `inputRequests`；
     * 客户端经 `tasks/update` 交付 `inputResponses` 后任务继续。
     */
    fun awaitInput(id: String, requests: Map<String, JsonObject>): CompletableFuture<Map<String, JsonObject>>? {
        val task = tasks[id] ?: return null
        val future = CompletableFuture<Map<String, JsonObject>>()
        task.inputRequests = requests
        task.answers = future
        task.status = Status.INPUT_REQUIRED
        task.statusMessage = "waiting for client input"
        task.updatedAt = clock()
        persist(task)
        return future
    }

    /**
     * T-166：交付答案（客户端 `tasks/update` 的 `inputResponses`）。
     *
     * 只交付**仍在等待**的 key；未知或已满足的 key 一律忽略（官方口径）。
     * 返回实际接收的条数。
     */
    fun deliverAnswers(id: String, responses: Map<String, JsonObject>): Int {
        val task = tasks[id] ?: return 0
        val future = task.answers ?: return 0
        val accepted = responses.filterKeys { it in task.inputRequests.keys }
        if (accepted.isEmpty()) return 0
        task.inputRequests = task.inputRequests - accepted.keys
        task.status = Status.WORKING
        task.statusMessage = "resuming after client input"
        task.updatedAt = clock()
        persist(task)
        future.complete(accepted)
        return accepted.size
    }

    /** 当前任务数（诊断/测试） */
    fun size(): Int = tasks.size

    /**
     * T-172：记一段正文增量（章号 / 第几次生成 / 片段）。
     *
     * 同章 attempt 变大 → 覆盖旧片段（重写）；attempt 更小（迟到的旧帧）→ 忽略。
     */
    fun recordPartial(id: String, chapter: Int, attempt: Int, text: String) {
        if (text.isEmpty()) return
        tasks[id]?.let { task ->
            val current = task.partialFragments[chapter]
            if (current == null || attempt >= current.first) {
                task.partialFragments[chapter] = attempt to text
                task.updatedAt = clock()
            }
        }
    }

    /** 当前草稿（按章号升序拼接已写出的章节）；没有增量时返回 null */
    fun partialDraft(id: String): String? {
        val task = tasks[id] ?: return null
        if (task.partialFragments.isEmpty()) return null
        return task.partialFragments.entries.sortedBy { it.key }.joinToString("\n\n") { it.value.second }
    }

    /**
     * 官方 `CreateTaskResult`：`resultType: "task"` + `Task` 字段（扁平）。
     * 另带仓内扩展字段（`tool` / `pollWith`）——客户端指引，不与官方字段冲突。
     */
    fun createResult(task: Task): JsonObject = buildJsonObject {
        put("resultType", "task")
        put("taskId", task.id)
        put("status", task.status.wire)
        put("createdAt", iso(task.createdAt))
        put("lastUpdatedAt", iso(task.updatedAt))
        put("ttlMs", remainingTtlMs(task))
        put("pollIntervalMs", POLL_INTERVAL_MS)
        // 仓内扩展（向后兼容 + 轮询指引）
        put("tool", task.tool)
        put("pollWith", "tasks/get")
    }

    /**
     * 官方 `GetTaskResult`：`resultType: "complete"` + `DetailedTask`（状态专属字段内联）。
     * 仓内既有扩展字段（`tool` / `updatedAt` / `expiresAt` / `cancelRequested` /
     * `progress` / `nextAction`）保留——向后兼容，不与官方字段同名冲突。
     */
    fun snapshot(task: Task): JsonObject = buildJsonObject {
        // T-163：结果判别字段（2026-07-28 Result 统一带 resultType）
        put("resultType", "complete")
        // ---- 官方 Task 字段 ----
        put("taskId", task.id)
        put("status", task.status.wire)
        task.statusMessage?.let { put("statusMessage", it) }
        put("createdAt", iso(task.createdAt))
        put("lastUpdatedAt", iso(task.updatedAt))
        put("ttlMs", remainingTtlMs(task))
        put("pollIntervalMs", POLL_INTERVAL_MS)
        // ---- 状态专属字段（DetailedTask）----
        when (task.status) {
            Status.INPUT_REQUIRED -> put("inputRequests", JsonObject(task.inputRequests))
            Status.COMPLETED -> task.result?.let { put("result", it) }
            Status.FAILED -> put("error", errorObject(task))
            else -> Unit
        }
        // ---- 仓内扩展字段（兼容保留）----
        put("tool", task.tool)
        put("updatedAt", iso(task.updatedAt))
        put("expiresAt", iso(task.expiresAt))
        put("cancelRequested", task.cancelRequested)
        // T-172：增量可用产物——当前草稿（**未过数字门禁**，仅供「边跑边看」）
        partialDraft(task.id)?.let { draft ->
            put("partialDraft", draft.take(MAX_PARTIAL_CHARS))
            put("partialChars", draft.length)
            put("partialVerified", false)
        }
        put(
            "progress",
            buildJsonObject {
                put("count", task.progressCount)
                task.progressMessage?.let { put("message", it) }
            },
        )
        task.nextAction?.let { put("nextAction", it) }
    }

    /** 官方 `error`：JSON-RPC 错误对象（`{code, message, data?}`） */
    private fun errorObject(task: Task): JsonObject = buildJsonObject {
        put("code", task.errorCode ?: DEFAULT_ERROR_CODE)
        put("message", task.error ?: "task failed")
        task.nextAction?.let { action ->
            put("data", buildJsonObject { put("nextAction", action) })
        }
    }

    /** 剩余存活毫秒数（官方 `ttlMs`：随生命周期可变；无上限时为 null） */
    private fun remainingTtlMs(task: Task): Long? {
        val remaining = task.expiresAt - clock()
        return if (remaining <= 0) 0L else remaining
    }

    private fun sweep() {
        val now = clock()
        tasks.entries.removeIf { (_, task) -> task.status.terminal && now > task.expiresAt }
    }

    /** 淘汰最老的终态任务；没有终态可淘汰返回 false */
    private fun evictOldestTerminal(): Boolean {
        val oldest = tasks.values
            .filter { it.status.terminal }
            .minByOrNull { it.updatedAt }
            ?: return false
        return tasks.remove(oldest.id, oldest)
    }

    private fun clampTtl(ttlMs: Long?): Long =
        (ttlMs ?: defaultTtlMs).coerceIn(MIN_TTL_MS, MAX_TTL_MS)

    private fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

    companion object {
        const val DEFAULT_TTL_MS = 15 * 60 * 1000L
        const val MIN_TTL_MS = 10_000L
        const val MAX_TTL_MS = 60 * 60 * 1000L

        /** 官方 `pollIntervalMs`：建议客户端的轮询间隔（1 秒） */
        const val POLL_INTERVAL_MS = 1_000L

        /** 任务失败时缺省使用的 JSON-RPC 错误码（内部错误） */
        const val DEFAULT_ERROR_CODE = -32603

        /** 输入等待上限（毫秒）——超时后任务如实失败，不无限期占着工作线程 */
        const val INPUT_WAIT_MS = 10 * 60 * 1000L

        /** 一次任务执行允许的输入轮次上限（防「问-答-再问」无限循环） */
        const val MAX_INPUT_ROUNDS = 5

        /** 草稿回传上限（字符）——防止长报告把轮询响应撑爆（截断如实发生，客户端看得到 partialChars） */
        const val MAX_PARTIAL_CHARS = 8000
    }
}
