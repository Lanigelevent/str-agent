package com.stragent.core.observability

import com.stragent.core.config.Config
import com.stragent.core.trace.InsightTrace
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 最小 OpenTelemetry 追踪内核（T-056 / D17 G-16）——零依赖纯 Kotlin。
 *
 * 为什么自己写而不是引 SDK：「能力只打包一次、宿主各自接线」是项目纪律，core 里
 * 塞一个自动埋点的 SDK 会带上 HTTP/gRPC 传输、批量处理器与全局注册表；本项目要的
 * 只是「产生一段合规的 OTLP 数据并交出去」。这里只做三件事：
 *
 *  1. **W3C trace-context**：解析/生成 `traceparent`，让上游 MCP 客户端的 trace
 *     能接上本服务的 span（客户端在 `_meta.traceparent` 里给我们父上下文）；
 *  2. **span 记录**：名字 + 父子关系 + 属性 + 状态 + 起止纳秒；
 *  3. **异步批量导出**：有界队列 + 单守护线程，导出慢/失败绝不阻塞工具调用与报告生成。
 *
 * 合规口径：OTLP/HTTP JSON（`application/json`），字段与 OTLP 1.3 的
 * `resourceSpans[].scopeSpans[].spans[]` 一致（见 [OtlpJson]）。
 *
 * 纪律：**追踪是增强，不是依赖**——没配置导出器时零成本（[OtelTraces] 为 null），
 * 导出失败只记日志，任何 span 操作都不抛给业务。
 */
object TraceIds {
    private val random = SecureRandom()
    private const val HEX = "0123456789abcdef"

    /** 128-bit trace id（32 hex，非全零，满足 W3C 要求） */
    fun traceId(): String = randomHex(32)

    /** 64-bit span id（16 hex） */
    fun spanId(): String = randomHex(16)

    private fun randomHex(chars: Int): String {
        val bytes = ByteArray(chars / 2)
        random.nextBytes(bytes)
        val out = StringBuilder(chars)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return out.toString()
    }

    fun isHex(value: String): Boolean = value.isNotEmpty() && value.all { it in HEX }

    fun isZero(value: String): Boolean = value.all { it == '0' }
}

/** 墙钟纳秒（OTLP 时间戳单位）。`Instant` 在 Windows 上有微秒级精度，足够区分一次调用。 */
fun epochNanos(): Long = Instant.now().let { it.epochSecond * 1_000_000_000L + it.nano }

/** span 的身份与链路（导出/嵌套时只需要这两个 id） */
data class OtelSpanContext(
    val traceId: String,
    val spanId: String,
    val sampled: Boolean = true,
) {
    fun traceparent(): String = "00-$traceId-$spanId-${if (sampled) "01" else "00"}"
}

/**
 * W3C trace-context 的 `traceparent` 头：`00-<32hex>-<16hex>-<2hex flags>`。
 *
 * 只接受合法版本（`ff` 保留、`00` 必须恰好 4 段）；解析失败一律返回 null——
 * 坏 header 不该让调用失败，只是没有上游链路可挂。
 */
data class W3cTraceparent(
    val traceId: String,
    val parentSpanId: String,
    val sampled: Boolean = true,
) {
    fun format(): String = "00-$traceId-$parentSpanId-${if (sampled) "01" else "00"}"

    fun toContext(): OtelSpanContext = OtelSpanContext(traceId, parentSpanId, sampled)

    companion object {
        fun parse(header: String?): W3cTraceparent? {
            val parts = header?.trim()?.split('-') ?: return null
            if (parts.size < 4) return null
            val version = parts[0]
            if (version.length != 2 || !TraceIds.isHex(version) || version == "ff") return null
            // 00 版本只允许 4 段；未来版本允许在 flags 后追加字段（按规范应忽略）
            if (version == "00" && parts.size != 4) return null
            val traceId = parts[1]
            val spanId = parts[2]
            val flags = parts[3]
            if (traceId.length != 32 || !TraceIds.isHex(traceId) || TraceIds.isZero(traceId)) return null
            if (spanId.length != 16 || !TraceIds.isHex(spanId) || TraceIds.isZero(spanId)) return null
            if (flags.length != 2 || !TraceIds.isHex(flags)) return null
            val sampled = (flags.toInt(16) and 0x01) == 1
            return W3cTraceparent(traceId, spanId, sampled)
        }
    }
}

/**
 * 一个 span：身份 + 父子关系 + 属性 + 状态 + 起止时间。
 *
 * [sampled] 是**头采样**决定（T-065）：`true` = 该 trace 应上报，`false` = 只在本地
 * 建对象（便宜）、导出时被 [OtelTraces.export] 过滤。判定规则见 [OtelSampler]：
 * 有上游时继承父上下文的标志（parent-based），新 trace 根按比率确定性判定。
 * 子 span 一律继承父 span 的标志——同一条 trace 内不允许有的导出有的不导出。
 *
 * 线程安全口径：属性写入用 `synchronized`（一个 span 可能被进度回调与结束路径同时碰），
 * 读取（导出）在 `end()` 之后进行，此时不再有写入者。
 */
class OtelSpan(
    val traceId: String,
    val spanId: String,
    val parentSpanId: String?,
    val name: String,
    val kind: Kind = Kind.INTERNAL,
    val startedAtNanos: Long = epochNanos(),
    val sampled: Boolean = true,
) {
    enum class Kind(val wire: Int) {
        INTERNAL(1),
        SERVER(2),
        CLIENT(3),
    }

    enum class Status(val wire: Int) {
        UNSET(0),
        OK(1),
        ERROR(2),
    }

    private val attributes = LinkedHashMap<String, Any>()

    @Volatile
    var endedAtNanos: Long? = null
        private set

    @Volatile
    var status: Status = Status.UNSET
        private set

    @Volatile
    var statusMessage: String? = null
        private set

    fun set(key: String, value: Any?): OtelSpan {
        if (value == null) return this
        val normalized = when (value) {
            is String, is Boolean, is Int, is Long, is Double -> value
            is Float -> value.toDouble()
            is Number -> value.toLong()
            else -> value.toString()
        }
        synchronized(attributes) { attributes[key] = normalized }
        return this
    }

    fun ok(): OtelSpan {
        status = Status.OK
        return this
    }

    fun error(message: String?): OtelSpan {
        status = Status.ERROR
        if (!message.isNullOrBlank()) statusMessage = message.take(MAX_STATUS_MESSAGE)
        return this
    }

    /** 结束 span（幂等）：之后的 [end] 调用不改变结束时间 */
    fun end(atNanos: Long = epochNanos()): OtelSpan {
        if (endedAtNanos == null) endedAtNanos = atNanos
        return this
    }

    /** 上下文要**带上采样标志**——报告根/阶段 span 靠它继承整条 trace 的决定（T-065） */
    fun spanContext(): OtelSpanContext = OtelSpanContext(traceId, spanId, sampled)

    fun attributesSnapshot(): Map<String, Any> = synchronized(attributes) { LinkedHashMap(attributes) }

    val durationNanos: Long get() = (endedAtNanos ?: startedAtNanos) - startedAtNanos

    companion object {
        private const val MAX_STATUS_MESSAGE = 512

        /** 新链路的根 span（没有上游 traceparent 时） */
        fun root(
            name: String,
            kind: Kind = Kind.INTERNAL,
            startedAtNanos: Long = epochNanos(),
        ): OtelSpan = OtelSpan(TraceIds.traceId(), TraceIds.spanId(), null, name, kind, startedAtNanos)

        /**
         * 挂到 [parent] 下的子 span；parent 为 null 等价于 [root]。
         * 采样标志随父上下文继承（T-065）：同一 trace 的决定在根上做一次。
         */
        fun child(
            name: String,
            parent: OtelSpanContext?,
            kind: Kind = Kind.INTERNAL,
            startedAtNanos: Long = epochNanos(),
        ): OtelSpan =
            if (parent == null) {
                root(name, kind, startedAtNanos)
            } else {
                OtelSpan(parent.traceId, TraceIds.spanId(), parent.spanId, name, kind, startedAtNanos, parent.sampled)
            }
    }
}

/** span 导出器（HTTP / 文件 / 测试收集器）；实现必须自己吞掉网络异常并返回 false */
interface SpanExporter : AutoCloseable {
    fun export(resource: Map<String, String>, spans: List<OtelSpan>): Boolean

    override fun close() {}
}

/**
 * 异步 span 导出器：**有界队列 + 单守护线程**。
 *
 * 纪律：`export` 永不阻塞调用方（队列满就丢弃并计数，日志告警）；导出失败只是
 * 少了可观测数据，绝不影响报告/工具调用。[drain] 供测试与进程退出前收尾。
 */
class OtelTraces(
    private val exporter: SpanExporter,
    val resource: Map<String, String> = mapOf("service.name" to "str-agent"),
    private val queueCapacity: Int = 256,
    private val log: StructuredLogger = StructuredLogger.getLogger("Otel"),
) : AutoCloseable {

    private val queue = ArrayBlockingQueue<List<OtelSpan>>(queueCapacity)
    private val dropped = AtomicLong(0)
    // 入队计数与完成计数：drain 用「已提交 == 已完成」判断，而不是「队列空 + 在途 0」。
    // 后者在 worker 刚 take 完、尚未把 inFlight 加 1 的瞬间会出现假「已清空」，
    // 测试随即读到空结果（run 1619 的 McpTraceTest 就是这样偶发失败）。
    private val submitted = AtomicLong(0)
    private val completed = AtomicLong(0)
    private val stopped = AtomicBoolean(false)
    private val failureLogged = AtomicBoolean(false)

    private val worker = Thread({
        while (true) {
            val batch = try {
                queue.take()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            if (batch.isEmpty()) break
            try {
                if (!exporter.export(resource, batch) && failureLogged.compareAndSet(false, true)) {
                    log.warn("OTLP 导出失败（后续同类失败不再逐条告警）", "spans" to batch.size.toString())
                }
            } catch (error: Throwable) {
                if (failureLogged.compareAndSet(false, true)) {
                    log.warn("OTLP 导出异常", "error" to (error.message ?: error.javaClass.simpleName))
                }
            } finally {
                completed.incrementAndGet()
            }
        }
    }, "otel-exporter").apply { isDaemon = true }

    init {
        worker.start()
    }

    /** 提交一批 span（非阻塞；满则丢批并计数）。**未采样的 span 在此过滤**（T-065）——本地对象便宜，导出才有成本。 */
    fun export(spans: List<OtelSpan>) {
        if (spans.isEmpty() || stopped.get()) return
        val keep = spans.filter { it.sampled }
        if (keep.isEmpty()) return
        if (!queue.offer(keep)) {
            val total = dropped.incrementAndGet()
            if (total == 1L || total % 100 == 0L) {
                log.warn("OTLP 导出队列已满，丢弃 span 批次", "droppedBatches" to total.toString(), "capacity" to queueCapacity.toString())
            }
        } else {
            submitted.incrementAndGet()
        }
    }

    /** 导出一次报告生成的完整 span 树（阶段 + 根） */
    fun export(trace: InsightTrace) = export(trace.otelSpans())

    fun droppedBatches(): Long = dropped.get()

    /**
     * 等到**所有已提交批次都导出完成**（测试 / 进程退出前收尾）。
     *
     * 判据用提交/完成计数而不是「队列空 + 在途 0」：worker 从 take 返回到把在途计数加 1
     * 之间有窗口，旧判据会在这条缝里返回 true，调用方随即读到空结果。
     */
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
        // 空批 = 结束哨兵；给足时间把在途批次送出去
        queue.offer(emptyList())
        try {
            worker.join(3_000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        runCatching { exporter.close() }
    }
}

/**
 * 追踪的部署配置（全部走环境变量，缺省**完全不导出**）。
 *
 * | 变量 | 作用 |
 * |---|---|
 * | `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | 完整 traces URL（优先） |
 * | `OTEL_EXPORTER_OTLP_ENDPOINT` | 基础 URL，导出到 `<base>/v1/traces`（Jaeger/Tempo 的 OTLP HTTP 口） |
 * | `STRAGENT_OTEL_FILE` | 不回传时落本地 JSONL（离线排障/演示） |
 * | `OTEL_EXPORTER_OTLP_HEADERS` | `k=v,k2=v2`，租户/鉴权头 |
 * | `OTEL_SERVICE_NAME` | 资源属性 `service.name`，缺省 `str-agent` |
 * | `STRAGENT_COST_PER_1K_TOKENS` | 每 1K token 的估算单价（USD）；不配则不写成本 |
 */
object OtelEnvironment {
    const val ENDPOINT = "OTEL_EXPORTER_OTLP_ENDPOINT"
    const val TRACES_ENDPOINT = "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"
    const val FILE = "STRAGENT_OTEL_FILE"
    const val HEADERS = "OTEL_EXPORTER_OTLP_HEADERS"
    const val SERVICE_NAME = "OTEL_SERVICE_NAME"
    const val COST_PER_1K_TOKENS = "STRAGENT_COST_PER_1K_TOKENS"

    /** 按环境变量装配；一个都没配返回 null（零成本、零日志噪声） */
    fun fromEnvironment(
        env: Map<String, String> = System.getenv(),
        serviceVersion: String = "dev",
    ): OtelTraces? {
        val exporter = exporterFrom(env) ?: return null
        val resource = linkedMapOf(
            "service.name" to (env[SERVICE_NAME]?.trim()?.takeIf { it.isNotEmpty() } ?: "str-agent"),
            "service.version" to serviceVersion,
        )
        return OtelTraces(exporter, resource)
    }

    /** HTTP 端点优先于文件；都没有返回 null */
    fun exporterFrom(env: Map<String, String> = System.getenv()): SpanExporter? {
        val tracesUrl = env[TRACES_ENDPOINT]?.trim()?.takeIf { it.isNotEmpty() }
        val base = env[ENDPOINT]?.trim()?.takeIf { it.isNotEmpty() }
        val url = tracesUrl ?: base?.let { if (it.endsWith("/")) it + "v1/traces" else "$it/v1/traces" }
        if (url != null) return OtlpHttpSpanExporter(url, parseHeaders(env[HEADERS]))
        val file = env[FILE]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return FileSpanExporter(java.io.File(file))
    }

    fun parseHeaders(raw: String?): Map<String, String> =
        raw?.split(',')
            ?.mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) null else part.substring(0, index).trim() to part.substring(index + 1).trim()
            }
            ?.filter { it.first.isNotEmpty() }
            ?.toMap()
            .orEmpty()

    /** 估算单价（USD / 1K tokens）；未配置或非法返回 null——**不编造成本** */
    fun costPer1kTokens(env: Map<String, String> = System.getenv()): Double? =
        env[COST_PER_1K_TOKENS]?.trim()?.toDoubleOrNull()?.takeIf { it >= 0 }
}
