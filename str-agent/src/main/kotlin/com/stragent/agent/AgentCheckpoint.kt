package com.stragent.agent

import com.stragent.core.ai.AgentChartCard
import com.stragent.core.ai.AgentFindingCard
import com.stragent.core.dataset.Dataset
import com.stragent.core.ports.ToolCall
import com.stragent.core.ports.TurnMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.UUID

/**
 * 持久化执行（T-050）——把「跑到一半的 agent 任务」落盘，进程重启后从最后一步续跑。
 *
 * 目录布局：`<root>/<runKey 前 32 位 sha256>/<stage 前 32 位 sha256>.json`。
 * 用哈希而不是原文当文件名：stage 里会带章节号/条线名/模型名，直接拼路径既有
 * 非法字符风险，也会把任务内容暴露在目录名里。runKey 本身由「数据集内容 + 插件 +
 * 报告类型 + 目标篇幅」派生（[runKey]），所以**同一份数据重跑才续，换了数据立刻换代**。
 *
 * 写入纪律：临时文件 + 原子 rename（失败退回普通 move），成功后尽量收紧为 0600
 * （POSIX 平台；Windows 上 POSIX 视图不可用会被跳过）。checkpoint 里有报告正文和
 * 工具结果片段，不该让同机其他用户读到。
 *
 * 失败语义：**checkpoint 是增强，不是依赖**。落盘失败不打断报告生成（[save] 返回
 * false），损坏/版本不符的状态按「没有状态」处理并删除。真正决定续跑的是
 * [AgentLoop] 里的签名校验：任务/模型/提示词任一变化 → 旧状态作废，从头开始。
 */
class AgentCheckpointStore(val root: File?) {

    val enabled: Boolean get() = root != null

    fun forRun(runKey: String): AgentRunCheckpoints = AgentRunCheckpoints(this, runKey)

    /**
     * T-156：孤儿回收——清理「目录内最新文件早于 maxAgeMs」的 run 目录。
     *
     * 背景（D24 审查 P2-11）：checkpoint 清理只挂在「成功过门禁 / 回落经典」路径
     * （AgentIntegratedOrchestrator），取消/崩溃残留目录此前**无限累积**（唯一删除
     * 入口 clearRun 只按 runKey 定向删）。启动时调用一次：过期整删，近期写过的
     * 活跃任务不受影响。按「目录内最新文件的 mtime」判定——目录 mtime 随文件增删
     * 变化不可靠。返回删除的目录数（供启动日志）。
     */
    fun sweepOrphans(maxAgeMs: Long = DEFAULT_SWEEP_AGE_MS, now: Long = System.currentTimeMillis()): Int {
        val base = root ?: return 0
        var removed = 0
        for (dir in base.listFiles() ?: emptyArray()) {
            if (!dir.isDirectory) continue
            val newest = dir.listFiles()?.mapNotNull { it.lastModified() }?.maxOrNull() ?: dir.lastModified()
            if (now - newest > maxAgeMs) {
                runCatching { dir.deleteRecursively() }
                removed++
            }
        }
        return removed
    }

    /** 一次任务的全部阶段文件（成功过门禁 / 回落经典后清理） */
    fun clearRun(runKey: String) {
        val dir = runDir(runKey) ?: return
        dir.listFiles()?.forEach { file -> runCatching { file.delete() } }
        runCatching { dir.delete() }
    }

    internal fun fileFor(runKey: String, stage: String): File? {
        val dir = runDir(runKey) ?: return null
        return File(dir, sha256(stage).take(32) + ".json")
    }

    private fun runDir(runKey: String): File? {
        val base = root ?: return null
        return File(base, sha256(runKey).take(32))
    }

    companion object {
        /** 状态格式版本：字段不兼容时整体作废（旧版本文件不会被误读） */
        const val VERSION = 1

        /** T-156 孤儿回收默认窗口：7 天没写过的 run 目录视为孤儿 */
        const val DEFAULT_SWEEP_AGE_MS = 7L * 24 * 60 * 60 * 1000

        fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))

        fun sha256(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }

        /**
         * 任务级幂等键：同一份数据 + 同一个插件 + 同一个报告类型与目标篇幅 → 同一个键，
         * 进程重启后可续；任一项变化 → 新键，绝不把上一份任务的中间状态接过来。
         *
         * 数据集按**内容**哈希（列名/类型/每个单元格）而不是文件路径或导入时间：
         * 同一份 CSV 重新导入（importedAt 变化）仍可续跑。
         */
        fun runKey(dataset: Dataset, plugin: PluginManifest, typeId: String, targetChars: Int): String {
            val digest = MessageDigest.getInstance("SHA-256")
            fun field(text: String) {
                digest.update(text.toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
            field("stragent-run-v${VERSION}")
            field(dataset.name)
            field(dataset.delimiter.toString())
            field(dataset.truncated.toString())
            field(dataset.sourceRows.toString())
            field(dataset.columns.size.toString())
            dataset.columns.forEach { field(it) }
            dataset.columnTypes.forEach { field(it.name) }
            field(dataset.rows.size.toString())
            dataset.rows.forEach { row ->
                field(row.size.toString())
                row.forEach { field(it) }
            }
            field(plugin.id)
            field(plugin.kind)
            field(plugin.systemPrompt)
            field(plugin.writerPrompt ?: "")
            field(plugin.chaptered.toString())
            field(plugin.model ?: "")
            field(plugin.maxSteps.toString())
            field(plugin.temperature.toString())
            plugin.beats.forEach { beat ->
                field(beat.name)
                field(beat.prompt)
                field(beat.tools?.joinToString(",") ?: "*")
            }
            field(typeId)
            field(targetChars.toString())
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** 一次任务（runKey）下的阶段集合：每个阶段一份独立 JSON，互不覆盖 */
class AgentRunCheckpoints internal constructor(
    val store: AgentCheckpointStore,
    val runKey: String,
) {
    fun stage(name: String): AgentCheckpoint = AgentCheckpoint(store, runKey, name)

    /** 整次任务收尾（成功过门禁 / 回落经典）：删掉全部阶段，下一次重跑重新计费 */
    fun clearAll() = store.clearRun(runKey)
}

/**
 * 单个阶段的断点句柄。`AgentCheckpoint.NONE` 是默认值：store 根为 null 时
 * load 恒 null、save/clear 空转——所有既有调用点零行为差异。
 */
class AgentCheckpoint internal constructor(
    private val store: AgentCheckpointStore,
    private val runKey: String,
    val stage: String,
) {
    val enabled: Boolean get() = store.enabled

    fun load(): JsonObject? {
        val file = store.fileFor(runKey, stage) ?: return null
        if (!file.isFile) return null
        val parsed = runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull()
        if (parsed == null) runCatching { file.delete() } // 损坏状态：删除，视为没有
        return parsed
    }

    /** @return 是否成功落盘（失败不抛出：checkpoint 是增强，不是依赖） */
    fun save(state: JsonObject): Boolean {
        val file = store.fileFor(runKey, stage) ?: return false
        val dir = file.parentFile ?: return false
        val tmp = File(dir, file.name + ".tmp-" + UUID.randomUUID())
        return runCatching {
            Files.createDirectories(dir.toPath())
            tmp.writeText(state.toString())
            // 0600：报告正文与工具结果不该被同机其他用户读到（Windows 上不支持则跳过）
            runCatching {
                Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------"))
            }
            runCatching {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            true
        }.getOrElse { false }.also { if (tmp.exists()) tmp.delete() }
    }

    fun clear() {
        val file = store.fileFor(runKey, stage) ?: return
        runCatching { file.delete() }
    }

    companion object {
        val NONE = AgentCheckpoint(AgentCheckpointStore(null), "", "none")
    }
}

/** 一次循环的完整可续状态（text / native 共用，只有 messages 的编码不同） */
internal data class CheckpointState(
    val mode: String,
    val nextStep: Int,
    val repairs: Int,
    val totalTokens: Int,
    val primed: String?,
    val messages: List<TurnMessage>,
    val steps: List<AgentStep>,
    val toolCalls: Int,
    val plan: List<String>,
    val findings: List<AgentFindingCard>,
    val charts: List<AgentChartCard>,
    val trace: List<AgentTraceEntry>,
    val cache: Map<String, JsonObject>,
)

/**
 * 状态编解码。字段名用短横线外的普通标识符，null 用 JSON null 显式落盘
 * （省略字段与「值为 null」在读回时必须可区分，primed 就靠这个）。
 */
internal object AgentCheckpointCodec {

    fun encodeState(state: CheckpointState, signature: String): JsonObject = buildJsonObject {
        put("version", AgentCheckpointStore.VERSION)
        put("signature", signature)
        put("mode", state.mode)
        put("nextStep", state.nextStep)
        put("repairs", state.repairs)
        put("totalTokens", state.totalTokens)
        put("primed", state.primed)
        put("messages", buildJsonArray {
            state.messages.forEach { message ->
                add(buildJsonObject {
                    put("role", message.role)
                    put("content", message.content)
                    if (message.toolCalls.isNotEmpty()) {
                        put("tool_calls", buildJsonArray {
                            message.toolCalls.forEach { call ->
                                add(buildJsonObject {
                                    put("id", call.id)
                                    put("name", call.name)
                                    put("arguments", call.arguments)
                                })
                            }
                        })
                    }
                    if (message.toolCallId != null) put("tool_call_id", message.toolCallId)
                })
            }
        })
        put("steps", encodeSteps(state.steps))
        put("toolCalls", state.toolCalls)
        put("plan", buildJsonArray { state.plan.forEach { add(it) } })
        put("findings", buildJsonArray {
            state.findings.forEach { finding ->
                add(buildJsonObject {
                    put("title", finding.title)
                    put("detail", finding.detail)
                    put("evidence", finding.evidence)
                })
            }
        })
        put("charts", encodeCharts(state.charts))
        put("trace", encodeTrace(state.trace))
        put("cache", JsonObject(state.cache))
        put("completed", false)
    }

    fun decodeState(state: JsonObject): CheckpointState = CheckpointState(
        mode = state.text("mode") ?: error("checkpoint.mode 缺失"), // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        nextStep = state.int("nextStep") ?: error("checkpoint.nextStep 缺失"), // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        repairs = state.int("repairs") ?: 0,
        totalTokens = state.int("totalTokens") ?: 0,
        primed = state.text("primed"),
        messages = decodeMessages(state["messages"] as? JsonArray ?: error("checkpoint.messages 缺失")), // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        steps = decodeSteps(state["steps"] as? JsonArray ?: JsonArray(emptyList())),
        toolCalls = state.int("toolCalls") ?: 0,
        plan = (state["plan"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
        findings = (state["findings"] as? JsonArray)?.map { element ->
            val obj = element as? JsonObject ?: error("checkpoint.findings 项不是对象") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            AgentFindingCard(obj.text("title") ?: "", obj.text("detail") ?: "", obj.text("evidence") ?: "")
        } ?: emptyList(),
        charts = decodeCharts(state["charts"] as? JsonArray ?: JsonArray(emptyList())),
        trace = (state["trace"] as? JsonArray)?.map { element ->
            val obj = element as? JsonObject ?: error("checkpoint.trace 项不是对象") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            AgentTraceEntry(
                obj.int("step") ?: 0,
                obj.text("tool") ?: "",
                obj.text("thought") ?: "",
                obj.bool("ok") ?: false,
                obj.text("error"),
            )
        } ?: emptyList(),
        cache = (state["cache"] as? JsonObject)?.mapValues { (_, value) ->
            value as? JsonObject ?: error("checkpoint.cache 项不是对象") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        } ?: emptyMap(),
    )

    fun encodeCompleted(signature: String, outcome: AgentOutcome): JsonObject = buildJsonObject {
        put("version", AgentCheckpointStore.VERSION)
        put("signature", signature)
        put("completed", true)
        put("outcome", encodeOutcome(outcome))
    }

    fun decodeCompleted(state: JsonObject): AgentOutcome =
        decodeOutcome(state["outcome"] as? JsonObject ?: error("checkpoint.outcome 缺失")) // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）

    fun encodeOutcome(outcome: AgentOutcome): JsonObject = buildJsonObject {
        put("output", outcome.output)
        put("steps", encodeSteps(outcome.steps))
        put("totalTokens", outcome.totalTokens)
        put("toolCalls", outcome.toolCalls)
        put("plan", buildJsonArray { outcome.plan.forEach { add(it) } })
        put("findings", buildJsonArray {
            outcome.findings.forEach { finding ->
                add(buildJsonObject {
                    put("title", finding.title)
                    put("detail", finding.detail)
                    put("evidence", finding.evidence)
                })
            }
        })
        put("charts", encodeCharts(outcome.charts))
        put("trace", encodeTrace(outcome.trace))
        put("qualityNotes", buildJsonArray { outcome.qualityNotes.forEach { add(it) } })
        put("gateRevisions", outcome.gateRevisions)
    }

    fun decodeOutcome(state: JsonObject): AgentOutcome = AgentOutcome(
        output = state["output"] as? JsonObject ?: error("checkpoint.outcome.output 缺失"), // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        steps = decodeSteps(state["steps"] as? JsonArray ?: JsonArray(emptyList())),
        totalTokens = state.int("totalTokens") ?: 0,
        toolCalls = state.int("toolCalls") ?: 0,
        plan = (state["plan"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
        findings = (state["findings"] as? JsonArray)?.map { element ->
            val obj = element as? JsonObject ?: error("checkpoint.outcome.findings 项不是对象") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            AgentFindingCard(obj.text("title") ?: "", obj.text("detail") ?: "", obj.text("evidence") ?: "")
        } ?: emptyList(),
        charts = decodeCharts(state["charts"] as? JsonArray ?: JsonArray(emptyList())),
        trace = (state["trace"] as? JsonArray)?.map { element ->
            val obj = element as? JsonObject ?: error("checkpoint.outcome.trace 项不是对象") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            AgentTraceEntry(
                obj.int("step") ?: 0,
                obj.text("tool") ?: "",
                obj.text("thought") ?: "",
                obj.bool("ok") ?: false,
                obj.text("error"),
            )
        } ?: emptyList(),
        qualityNotes = (state["qualityNotes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
        gateRevisions = state.int("gateRevisions") ?: 0,
    )

    private fun encodeSteps(steps: List<AgentStep>): JsonArray = buildJsonArray {
        steps.forEach { step ->
            add(buildJsonObject {
                put("tool", step.tool)
                put("argsSummary", step.argsSummary)
                put("resultSummary", step.resultSummary)
                put("ok", step.ok)
            })
        }
    }

    private fun decodeSteps(array: JsonArray): List<AgentStep> = array.map { element ->
        val obj = element as? JsonObject ?: error("checkpoint.steps 项不是对象") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        AgentStep(
            tool = obj.text("tool") ?: "",
            argsSummary = obj.text("argsSummary") ?: "",
            resultSummary = obj.text("resultSummary") ?: "",
            ok = obj.bool("ok") ?: false,
        )
    }

    private fun encodeCharts(charts: List<AgentChartCard>): JsonArray = buildJsonArray {
        charts.forEach { chart ->
            add(buildJsonObject {
                put("type", chart.type)
                put("title", chart.title)
                put("data", buildJsonArray {
                    chart.data.forEach { (label, value) ->
                        add(buildJsonObject { put("label", label); put("value", value) })
                    }
                })
                put("description", chart.description)
            })
        }
    }

    private fun decodeCharts(array: JsonArray): List<AgentChartCard> = array.map { element ->
        val obj = element as? JsonObject ?: error("checkpoint.charts 项不是对象") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        AgentChartCard(
            type = obj.text("type") ?: "bar",
            title = obj.text("title") ?: "图表", // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            data = (obj["data"] as? JsonArray)?.mapNotNull { point ->
                val po = point as? JsonObject ?: return@mapNotNull null
                val label = po.text("label") ?: return@mapNotNull null
                val value = (po["value"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                label to value
            } ?: emptyList(),
            description = obj.text("description") ?: "",
        )
    }

    private fun encodeTrace(trace: List<AgentTraceEntry>): JsonArray = buildJsonArray {
        trace.forEach { entry ->
            add(buildJsonObject {
                put("step", entry.step)
                put("tool", entry.tool)
                put("thought", entry.thought)
                put("ok", entry.ok)
                if (entry.error != null) put("error", entry.error)
            })
        }
    }

    private fun decodeMessages(array: JsonArray): List<TurnMessage> = array.map { element ->
        val obj = element as? JsonObject ?: error("checkpoint.messages 项不是对象") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        TurnMessage(
            role = obj.text("role") ?: error("checkpoint.messages.role 缺失"), // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            content = obj.text("content") ?: "",
            toolCalls = (obj["tool_calls"] as? JsonArray)?.map { callElement ->
                val call = callElement as? JsonObject ?: error("checkpoint.messages.tool_calls 项不是对象") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                ToolCall(call.text("id") ?: "", call.text("name") ?: "", call.text("arguments") ?: "")
            } ?: emptyList(),
            toolCallId = obj.text("tool_call_id"),
        )
    }
}

internal fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
