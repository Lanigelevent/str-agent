package com.stragent.core.observability

import com.stragent.core.config.Config
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 结构化日志行 → OTel LogRecord 的桥（T-066，补齐 T-056 边界⑥的最后一个信号）。
 *
 * 源头是 [StructuredLogger] 的既有 JSON 行（timestamp/level/logger/message/fields）；
 * 本桥**解析**这些行并重编码为 OTLP/HTTP JSON 的 `resourceLogs[].scopeLogs[].logRecords[]`。
 * 严重级映射：TRACE→1、DEBUG→5、INFO→9、WARN→13、ERROR→17（OTel severityNumber
 * 各档的起始值）。解析失败的行跳过（日志是增强，坏行绝不抛给业务）。
 */
object OtelLogsJson {
    const val SCOPE_NAME = "com.stragent"
    const val SCOPE_VERSION = "1.0.0"

    /** StructuredLogger.Level → OTel severityNumber（各档起始值） */
    fun severityNumber(level: String): Int = when (level.uppercase()) {
        "TRACE" -> 1
        "DEBUG" -> 5
        "INFO" -> 9
        "WARN" -> 13
        "ERROR" -> 17
        else -> 9 // 未知级别按 INFO 记，不丢行
    }

    /**
     * 解析一行 [StructuredLogger] JSON 并编码为 OTLP logRecord；坏行返回 null（跳过）。
     * `logger` 名进 attributes（`log.logger`），`fields` 逐项进 attributes（`log.field.<k>`）。
     */
    fun logRecordFromLine(line: String): JsonObject? = try {
        val parsed = Json.parseToJsonElement(line).let { it as? JsonObject } ?: return null
        val timestamp = (parsed["timestamp"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
        val level = (parsed["level"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
        val message = (parsed["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
        val logger = (parsed["logger"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
        val nanos = java.time.OffsetDateTime.parse(timestamp).toInstant().let {
            it.epochSecond * 1_000_000_000L + it.nano
        }
        buildJsonObject {
            put("timeUnixNano", nanos.toString())
            put("observedTimeUnixNano", nanos.toString())
            put("severityNumber", severityNumber(level))
            put("severityText", level.uppercase())
            put("body", buildJsonObject { put("stringValue", message) })
            val attrs = LinkedHashMap<String, String>()
            if (logger.isNotEmpty()) attrs["log.logger"] = logger
            (parsed["fields"] as? JsonObject)?.forEach { (k, v) -> attrs["log.field.$k"] = v.toString().removeSurrounding("\"") }
            if (attrs.isNotEmpty()) put("attributes", OtlpMetricsJson.metricAttributes(attrs))
        }
    } catch (_: Exception) {
        null
    }

    /** 完整 resourceLogs envelope */
    fun envelope(resource: Map<String, String>, records: List<JsonObject>): JsonObject = buildJsonObject {
        put(
            "resourceLogs",
            kotlinx.serialization.json.JsonArray(
                listOf(
                    buildJsonObject {
                        put("resource", buildJsonObject {
                            put("attributes", OtlpMetricsJson.metricAttributes(resource))
                        })
                        put(
                            "scopeLogs",
                            kotlinx.serialization.json.JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("scope", buildJsonObject {
                                            put("name", SCOPE_NAME)
                                            put("version", SCOPE_VERSION)
                                        })
                                        put("logRecords", kotlinx.serialization.json.JsonArray(records))
                                    },
                                ),
                            ),
                        )
                    },
                ),
            ),
        )
    }
}

/**
 * 异步日志导出器集合：**有界队列 + 单守护线程 + 攒批发送**，与 span/metrics 同纪律。
 *
 * 输入是**已渲染的 StructuredLogger JSON 行**（低频：本项目日志量小），worker 每
 * 500ms 轮询攒批（最多 [batchSize] 条）发一次。队列满丢行计数（绝不阻塞业务）；
 * 导出失败只回 false 并记一次告警。`drain` 判据沿用 T-064 的「已提交 == 已完成」。
 */
class OtelLogs(
    private val exporter: LogsExporter,
    val resource: Map<String, String> = mapOf("service.name" to "str-agent"),
    /** 攒批上限（条） */
    val batchSize: Int = 64,
    queueCapacity: Int = 256,
    private val log: StructuredLogger = StructuredLogger.getLogger("OtelLogs"),
) : AutoCloseable {

    /** logs 导出器契约：实现吞掉网络异常只回 false（与 span/metrics 同纪律） */
    interface LogsExporter : AutoCloseable {
        fun export(resource: Map<String, String>, body: ByteArray): Boolean

        override fun close() {}
    }

    private val queue = ArrayBlockingQueue<String>(queueCapacity)
    private val submitted = AtomicLong(0)
    private val completed = AtomicLong(0)
    private val stopped = AtomicBoolean(false)
    private val dropped = AtomicLong(0)
    private val failureLogged = AtomicBoolean(false)

    private val worker = Thread({
        while (true) {
            val first = try {
                queue.poll(500, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            } ?: continue
            val batch = ArrayList<String>(batchSize)
            batch.add(first)
            queue.drainTo(batch, batchSize - 1)
            try {
                exportBatch(batch)
            } finally {
                completed.addAndGet(batch.size.toLong())
            }
            if (stopped.get()) break
        }
    }, "otel-logs-exporter").apply { isDaemon = true }

    init {
        worker.start()
    }

    private fun exportBatch(batch: List<String>) {
        val records = batch.mapNotNull { OtelLogsJson.logRecordFromLine(it) }
        if (records.isEmpty()) return
        val body = OtelLogsJson.envelope(resource, records).toString().toByteArray(Charsets.UTF_8)
        val ok = try {
            exporter.export(resource, body)
        } catch (_: Throwable) {
            false
        }
        if (!ok && failureLogged.compareAndSet(false, true)) {
            log.warn("OTLP logs 导出失败（后续同类失败不再逐条告警）", "records" to records.size.toString())
        }
    }

    /** 提交一行渲染后的日志 JSON（非阻塞；满则丢行计数） */
    fun offer(line: String) {
        if (stopped.get()) return
        if (!queue.offer(line)) {
            val total = dropped.incrementAndGet()
            if (total == 1L || total % 100 == 0L) {
                log.warn("OTLP logs 队列已满，丢弃日志行", "dropped" to total.toString())
            }
        } else {
            submitted.incrementAndGet()
        }
    }

    fun droppedLines(): Long = dropped.get()

    /** 等到所有已提交行都导出完成（测试 / 进程退出前收尾）；T-064 同款判据 */
    fun drain(timeoutMs: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (queue.isEmpty() && submitted.get() == completed.get()) return true
            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return queue.isEmpty() && submitted.get() == completed.get()
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { worker.interrupt() }
        runCatching { worker.join(3_000) }
        // 收尾：把队列剩余行直接同步导出（worker 已停）
        runCatching {
            val rest = ArrayList<String>(queue.size)
            queue.drainTo(rest)
            if (rest.isNotEmpty()) exportBatch(rest)
        }
        runCatching { exporter.close() }
    }

    /** OTLP/HTTP logs 导出器：POST 到 `<collector>/v1/logs`（Jaeger/Tempo/Collector 4318 口） */
    class OtlpHttpLogExporter(
        /** 实际 POST 的 logs URL（`…/v1/logs`）——诊断与装配测试要用 */
        val endpoint: String,
        private val headers: Map<String, String> = emptyMap(),
        private val timeoutMs: Int = 5_000,
    ) : LogsExporter {
        override fun export(resource: Map<String, String>, body: ByteArray): Boolean =
            OtlpHttp.post(endpoint, headers, body, timeoutMs)
    }

    /** 本地 JSONL 导出器：每批一行 resourceLogs envelope，离线排障/演示用 */
    class FileLogExporter(private val file: java.io.File) : LogsExporter {
        override fun export(resource: Map<String, String>, body: ByteArray): Boolean = try {
            synchronized(this) {
                file.parentFile?.mkdirs()
                file.appendText(String(body, Charsets.UTF_8) + "\n", Charsets.UTF_8)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 测试/嵌入式用：最近一批编码体留在内存里（不做任何 IO） */
    class CollectingLogExporter : LogsExporter {
        private val bodies = mutableListOf<String>()

        @Synchronized
        override fun export(resource: Map<String, String>, body: ByteArray): Boolean {
            bodies.add(String(body, Charsets.UTF_8))
            return true
        }

        @Synchronized
        fun bodies(): List<String> = bodies.toList()
    }
}

/**
 * 结构化日志 → OTel logs 的多路桥（T-066）：把当前全局 sink 包一层——原通道
 * （stderr 或宿主注入的 sink）**逐字不变**，每行同时投给 [OtelLogs] 异步导出。
 *
 * 纪律：日志是增强，不是依赖——桥的任何异常都不影响原日志输出；stdio 传输的
 * stdout 纯净性天然保持（StructuredLogger 只写 stderr/宿主 sink，桥不碰 stdout）。
 * `install` 幂等（重复安装返回 false，不叠加）。
 */
class OtelLogBridge(private val logs: OtelLogs) {
    private val previous: (String) -> Unit = StructuredLogger.globalOut()
    private val installed = AtomicBoolean(false)

    fun install(): Boolean {
        if (!installed.compareAndSet(false, true)) return false
        StructuredLogger.setGlobalOut { line ->
            try {
                previous(line)
            } catch (_: Throwable) {
            }
            try {
                logs.offer(line)
            } catch (_: Throwable) {
            }
        }
        return true
    }

    fun close() {
        if (!installed.compareAndSet(true, false)) return
        StructuredLogger.setGlobalOut(previous)
    }
}

/**
 * logs 侧环境装配（T-066）：`OTEL_EXPORTER_OTLP_LOGS_ENDPOINT`（优先）＞
 * 基础 `OTEL_EXPORTER_OTLP_ENDPOINT`（自动追加 `/v1/logs`，配置基础端点即三信号
 * 全启用）＞ `STRAGENT_OTEL_LOGS_FILE`；一个都没配 = null（零成本，不装桥）。
 */
object OtelLogsEnvironment {
    const val LOGS_ENDPOINT = "OTEL_EXPORTER_OTLP_LOGS_ENDPOINT"
    const val LOGS_FILE = "STRAGENT_OTEL_LOGS_FILE"

    /** 端点优先于文件；都没有返回 null */
    fun exporterFrom(env: Map<String, String> = System.getenv()): OtelLogs.LogsExporter? {
        val logsUrl = env[LOGS_ENDPOINT]?.trim()?.takeIf { it.isNotEmpty() }
            ?: env[OtelEnvironment.ENDPOINT]?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { base -> if (base.endsWith("/")) base + "v1/logs" else "$base/v1/logs" }
        if (logsUrl != null) {
            return OtelLogs.OtlpHttpLogExporter(logsUrl, OtelEnvironment.parseHeaders(env[OtelEnvironment.HEADERS]))
        }
        val file = env[LOGS_FILE]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return OtelLogs.FileLogExporter(java.io.File(file))
    }

    /** resource 复用 traces/metrics 同一口径（service.name/version） */
    fun fromEnvironment(
        env: Map<String, String> = System.getenv(),
        serviceVersion: String = "dev",
    ): OtelLogs? {
        val exporter = exporterFrom(env) ?: return null
        val resource = linkedMapOf(
            "service.name" to (env[OtelEnvironment.SERVICE_NAME]?.trim()?.takeIf { it.isNotEmpty() } ?: "str-agent"),
            "service.version" to serviceVersion,
        )
        return OtelLogs(exporter, resource)
    }
}
