package com.stragent.core.observability

import com.stragent.core.config.Config
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 本服务埋点的指标名与标准直方图边界（data 与 host 共用同一常量，测试同源引用）。
 */
object StrAgentMetrics {
    const val TOOL_CALLS = "str_agent.tool.calls"
    const val TOOL_DURATION = "str_agent.tool.duration"
    const val REPORT_CALLS = "str_agent.report.calls"
    const val REPORT_DURATION = "str_agent.report.duration"
    const val REPORT_TOKENS = "str_agent.report.tokens"

    /** D25 可观测性收口（gap-3）：发稿门禁结果计数——AD-028 核心质量信号此前只进单次回执 */
    const val GATE_RESULTS = "str_agent.gate.results"
    const val LLM_CALLS = "str_agent.llm.calls"

    /** 时延直方图边界（ms）：覆盖毫秒级工具到分钟级报告 */
    val DURATION_BOUNDS_MS = listOf(1.0, 5.0, 10.0, 25.0, 60.0, 100.0, 250.0, 500.0, 1_000.0, 2_500.0, 5_000.0, 10_000.0, 30_000.0, 60_000.0, 120_000.0, 300_000.0)

    /**
     * T-232：把自由形态的模型名收敛到**有限族标签**，避免 OTel series 溢出。
     *
     * 模型名带日期/版本后缀（"gpt-4o-2024-08-06"、"step-3.5-flash"），原样做
     * metric label 会让每次模型升级新增一条 series，长跑后无界。收敛到「族」后
     * series 上界固定（白名单 + other），按**精确模型名**的成本分面仍由
     * `UsageLedger.byModel` 提供（JSON 数组没有 series 溢出问题，不丢精度）。
     */
    fun modelFamilyLabel(model: String?): String {
        val lower = model?.trim()?.lowercase() ?: return "unknown"
        val families = listOf(
            "step", "glm", "gpt", "claude", "qwen", "kimi", "deepseek",
            "minimax", "gemini", "llama", "mistral", "o1", "o3",
        )
        return families.firstOrNull { family ->
            lower == family || lower.startsWith("$family-") || lower.startsWith("$family/") ||
                lower.startsWith("$family.")
        } ?: "other"
    }
}

/** series 键：属性键值对按名归一（键排序、同名同组合同 series） */
private fun seriesKeyOf(attributes: Map<String, String>): String =
    attributes.entries
        .filter { it.key.isNotEmpty() }
        .sortedBy { it.key }
        .joinToString("\u0001") { (k, v) -> "$k\u0000$v" }

/** 溢出 series 的固定属性（OTel SDK 同款 `otel.metric.overflow`） */
private val OVERFLOW_ATTRIBUTES: Map<String, String> = mapOf("otel.metric.overflow" to "true")

/** 溢出 series 在 map 里的键（用属性键拼接串会与真实属性组合撞车，用专用键隔离） */
private const val OVERFLOW_SERIES_KEY = "\u0000overflow"

/** 一次导出的数据点基型：属性 + 起止时间戳 */
sealed interface MetricPoint {
    val attributes: Map<String, String>
    val startTimeNanos: Long
    val timeNanos: Long
}

/** Sum 仪表的数据点（64 位整数累计值） */
data class MetricSumPoint(
    override val attributes: Map<String, String>,
    override val startTimeNanos: Long,
    override val timeNanos: Long,
    val value: Long,
) : MetricPoint

/** 直方图数据点：count/sum/min/max + 桶计数与显式边界 */
data class MetricHistogramPoint(
    override val attributes: Map<String, String>,
    override val startTimeNanos: Long,
    override val timeNanos: Long,
    val count: Long,
    val sum: Double,
    val min: Double,
    val max: Double,
    val bucketCounts: List<Long>,
    val explicitBounds: List<Double>,
) : MetricPoint

/** 单个仪表的快照（导出编码的输入；测试也用它断言聚合值） */
sealed interface MetricInstrumentData {
    val name: String
    val unit: String?
    val description: String?
    val points: List<MetricPoint>

    /** 累加型（Sum）仪表快照 */
    data class Sum(
        override val name: String,
        override val unit: String? = null,
        override val description: String? = null,
        override val points: List<MetricSumPoint>,
    ) : MetricInstrumentData

    /** 直方图仪表快照 */
    data class Histogram(
        override val name: String,
        override val unit: String? = null,
        override val description: String? = null,
        override val points: List<MetricHistogramPoint>,
    ) : MetricInstrumentData
}

/**
 * 单调递增计数器（Sum 聚合，cumulative）。线程安全：每 series 一个 AtomicLong。
 */
class OtelCounter internal constructor(
    val name: String,
    val unit: String? = null,
    val description: String? = null,
    private val maxSeries: Int,
    private val startTimeNanos: Long,
) {
    private val series = ConcurrentHashMap<String, Series>()
    private val seriesCount = AtomicLong(0)

    /** [value] 为负直接拒绝（Sum 单调；取差值是收集端的事） */
    fun add(value: Long, attributes: Map<String, String> = emptyMap()) {
        if (value < 0) return
        seriesFor(attributes).counter.addAndGet(value)
    }

    /** 便捷重载：非整数直接截断（计数口径都是整数） */
    fun add(value: Double, attributes: Map<String, String> = emptyMap()) = add(value.toLong(), attributes)

    fun snapshot(): MetricInstrumentData {
        val points = series.values.map { s ->
            MetricSumPoint(s.attributes, startTimeNanos, epochNanos(), s.counter.get())
        }
        return MetricInstrumentData.Sum(name, unit, description, points)
    }

    private fun seriesFor(attributes: Map<String, String>): Series {
        val key = seriesKeyOf(attributes)
        series[key]?.let { return it }
        synchronized(this) {
            series[key]?.let { return it }
            if (seriesCount.get() >= maxSeries) return overflowSeries()
            val created = Series(attributes, AtomicLong(0))
            series[key] = created
            seriesCount.incrementAndGet()
            return created
        }
    }

    private fun overflowSeries(): Series {
        series[OVERFLOW_SERIES_KEY]?.let { return it }
        val created = Series(OVERFLOW_ATTRIBUTES, AtomicLong(0))
        series[OVERFLOW_SERIES_KEY] = created
        return created
    }

    private class Series(val attributes: Map<String, String>, val counter: AtomicLong)
}

/**
 * 固定显式边界的直方图（cumulative）。n 个边界 → n+1 个桶：桶 i 计 `b(i-1) ≤ v < b(i)`
 * （OTel 规则：v ≥ 边界进后一桶，即 `index = 边界中 ≤ v 的个数`），最后一桶收全部
 * 超出上界的值。min/max/sum 是自启动以来的累计值。
 */
class OtelHistogram internal constructor(
    val name: String,
    val unit: String? = null,
    val description: String? = null,
    private val boundaries: List<Double>,
    private val maxSeries: Int,
    private val startTimeNanos: Long,
) {
    init {
        require(boundaries.isNotEmpty() && boundaries.zipWithNext().all { (a, b) -> a < b }) {
            "直方图边界必须非空且严格递增"
        }
    }

    private val series = ConcurrentHashMap<String, Series>()
    private val seriesCount = AtomicLong(0)

    /** 记录一个观测值；NaN/Infinity 拒绝（OTLP JSON 无法表达） */
    fun record(value: Double, attributes: Map<String, String> = emptyMap()) {
        if (value.isNaN() || value.isInfinite()) return
        val s = seriesFor(attributes)
        synchronized(s) {
            s.count.incrementAndGet()
            s.sum.add(value)
            s.min.updateAndGet { prev -> if (value < prev) value else prev }
            s.max.updateAndGet { prev -> if (value > prev) value else prev }
            val index = boundaries.count { value >= it }
            s.buckets[index].incrementAndGet()
        }
    }

    fun snapshot(): MetricInstrumentData {
        val points = series.values.map { s ->
            MetricHistogramPoint(
                attributes = s.attributes,
                startTimeNanos = startTimeNanos,
                timeNanos = epochNanos(),
                count = s.count.get(),
                sum = s.sum.sum(),
                min = s.min.get(),
                max = s.max.get(),
                bucketCounts = s.buckets.map { it.get() },
                explicitBounds = boundaries,
            )
        }
        return MetricInstrumentData.Histogram(name, unit, description, points)
    }

    private fun seriesFor(attributes: Map<String, String>): Series {
        val key = seriesKeyOf(attributes)
        series[key]?.let { return it }
        synchronized(this) {
            series[key]?.let { return it }
            if (seriesCount.get() >= maxSeries) return overflowSeries()
            val created = Series(attributes, boundaries.size + 1)
            series[key] = created
            seriesCount.incrementAndGet()
            return created
        }
    }

    private fun overflowSeries(): Series {
        series[OVERFLOW_SERIES_KEY]?.let { return it }
        val created = Series(OVERFLOW_ATTRIBUTES, boundaries.size + 1)
        series[OVERFLOW_SERIES_KEY] = created
        return created
    }

    private class Series(val attributes: Map<String, String>, bucketCount: Int) {
        val count = AtomicLong(0)
        val sum = java.util.concurrent.atomic.DoubleAdder()
        val min = java.util.concurrent.atomic.AtomicReference(Double.MAX_VALUE)
        val max = java.util.concurrent.atomic.AtomicReference(-Double.MAX_VALUE)
        val buckets = Array(bucketCount) { AtomicLong(0) }
    }
}

/**
 * metrics 注册表：**有界 series + 异步周期导出 + close 收尾**。
 *
 * 仪表按需创建（`counter()`/`histogram()` 同名幂等，重复创建拿到同一实例）；
 * 周期线程每 [intervalMs] 导一次全量快照；`close()` 做最后一次收尾导出并关闭导出器。
 * 与 span 导出同纪律：导出失败只影响可观测数据，绝不阻塞业务（周期线程 runCatching）。
 */
class OtelMetrics(
    private val exporter: MetricsExporter,
    val resource: Map<String, String> = mapOf("service.name" to "str-agent"),
    /** 周期导出间隔（ms） */
    val intervalMs: Long = 60_000,
    /** 每仪表的 series 上限（属性组合数；超限并入溢出 series） */
    private val maxSeries: Int = 256,
    private val log: StructuredLogger = StructuredLogger.getLogger("OtelMetrics"),
) : AutoCloseable {

    private val startedAtNanos = epochNanos()
    private val instruments = ConcurrentHashMap<String, Any>()
    private val stopped = AtomicBoolean(false)

    /** 周期导出线程：间隔一到导一次全量快照；stop 后退出（收尾导出由 close 做） */
    private val worker = Thread({
        while (!stopped.get()) {
            try {
                Thread.sleep(intervalMs)
            } catch (_: InterruptedException) {
                break
            }
            if (stopped.get()) break
            runCatching { collectAndExport() }
        }
    }, "otel-metrics-exporter").apply { isDaemon = true }

    init {
        worker.start()
    }

    /** 取或建计数器（同名幂等） */
    fun counter(name: String, unit: String? = null, description: String? = null): OtelCounter =
        register(name, unit, description) { n, u, d -> OtelCounter(n, u, d, maxSeries, startedAtNanos) }

    /** 取或建直方图（同名幂等；边界以后来者为准） */
    fun histogram(
        name: String,
        unit: String? = null,
        description: String? = null,
        boundaries: List<Double> = StrAgentMetrics.DURATION_BOUNDS_MS,
    ): OtelHistogram = register(name, unit, description) { n, u, d -> OtelHistogram(n, u, d, boundaries, maxSeries, startedAtNanos) }

    private inline fun <reified T> register(name: String, unit: String?, description: String?, crossinline factory: (String, String?, String?) -> T): T {
        val created = instruments.compute(name) { _, existing ->
            when (existing) {
                null -> factory(name, unit, description)
                is T -> existing
                else -> {
                    log.warn("指标名已被其它类型的仪表占用，沿用既有实例", "name" to name)
                    existing
                }
            }
        }
        @Suppress("UNCHECKED_CAST")
        return created as T
    }

    /** 全量快照（测试也用它断言聚合值，不必走编码） */
    fun snapshot(): List<MetricInstrumentData> = instruments.values.mapNotNull { instrument ->
        when (instrument) {
            is OtelCounter -> instrument.snapshot()
            is OtelHistogram -> instrument.snapshot()
            else -> null
        }
    }

    /**
     * 立即导出一次全量快照；**没有数据点时不发**（空 envelope 是噪声）。
     * stopped 后返回 false（收尾导出走 [exportSnapshotNow]，不受此限）。
     */
    fun collectAndExport(): Boolean {
        if (stopped.get()) return false
        return exportSnapshotNow()
    }

    private fun exportSnapshotNow(): Boolean {
        val data = snapshot()
        if (data.none { it.points.isNotEmpty() }) return true
        val body = OtlpMetricsJson.envelope(resource, data).toString().toByteArray(Charsets.UTF_8)
        return exporter.export(resource, body)
    }

    /** metrics 导出器契约：实现必须吞掉网络异常只回 false——与 [SpanExporter] 同纪律 */
    interface MetricsExporter : AutoCloseable {
        /** 发送一段已编码的 OTLP metrics JSON；返回是否 2xx */
        fun export(resource: Map<String, String>, body: ByteArray): Boolean

        override fun close() {}
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { worker.interrupt() }
        runCatching { worker.join(3_000) }
        runCatching { exportSnapshotNow() }
        runCatching { exporter.close() }
    }
}

/**
 * OTLP/HTTP JSON 编码（metrics，T-065）——按 OTLP 1.3 的
 * `resourceMetrics[].scopeMetrics[].metrics[]` 结构生成。
 *
 * 口径与 span 编码对齐（[OtlpJson]）：
 *  - 64 位整数用**字符串**（`asInt` / `count` / 时间戳），JS 客户端不丢精度；
 *  - 属性是 tagged union（这里统一 stringValue）；
 *  - `aggregationTemporality = 2`（CUMULATIVE）；`sum.isMonotonic = true`；
 *  - 直方图 `bucketCounts` 是字符串数组、`explicitBounds` 是数字数组；
 *    min/max 只在 count > 0 时写（OTel SDK 同款）。
 */
object OtlpMetricsJson {
    const val SCOPE_NAME = "com.stragent"
    const val SCOPE_VERSION = "1.0.0"
    private const val TEMPORALITY_CUMULATIVE = 2

    fun envelope(resource: Map<String, String>, data: List<MetricInstrumentData>): JsonObject = buildJsonObject {
        put(
            "resourceMetrics",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("resource", buildJsonObject { put("attributes", metricAttributes(resource)) })
                        put(
                            "scopeMetrics",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("scope", buildJsonObject {
                                            put("name", SCOPE_NAME)
                                            put("version", SCOPE_VERSION)
                                        })
                                        put("metrics", JsonArray(data.map { metricJson(it) }))
                                    },
                                ),
                            ),
                        )
                    },
                ),
            ),
        )
    }

    private fun metricJson(instrument: MetricInstrumentData): JsonObject = buildJsonObject {
        put("name", instrument.name)
        instrument.unit?.let { put("unit", it) }
        instrument.description?.let { put("description", it) }
        when (instrument) {
            is MetricInstrumentData.Sum -> put(
                "sum",
                buildJsonObject {
                    put("dataPoints", JsonArray(instrument.points.map { point ->
                        buildJsonObject {
                            put("attributes", metricAttributes(point.attributes))
                            put("startTimeUnixNano", point.startTimeNanos.toString())
                            put("timeUnixNano", point.timeNanos.toString())
                            put("asInt", point.value.toString())
                        }
                    }))
                    put("aggregationTemporality", TEMPORALITY_CUMULATIVE)
                    put("isMonotonic", true)
                },
            )
            is MetricInstrumentData.Histogram -> put(
                "histogram",
                buildJsonObject {
                    put("dataPoints", JsonArray(instrument.points.map { point ->
                        buildJsonObject {
                            put("attributes", metricAttributes(point.attributes))
                            put("startTimeUnixNano", point.startTimeNanos.toString())
                            put("timeUnixNano", point.timeNanos.toString())
                            put("count", point.count.toString())
                            put("sum", point.sum)
                            if (point.count > 0) {
                                put("min", point.min)
                                put("max", point.max)
                            }
                            put("bucketCounts", JsonArray(point.bucketCounts.map { kotlinx.serialization.json.JsonPrimitive(it.toString()) }))
                            put("explicitBounds", JsonArray(point.explicitBounds.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                            put("aggregationTemporality", TEMPORALITY_CUMULATIVE)
                        }
                    }))
                    put("aggregationTemporality", TEMPORALITY_CUMULATIVE)
                },
            )
        }
    }

    /** 跨信号共用（logs 的 logRecords 也用同一 tagged-union 形制，T-066 起 internal） */
    internal fun metricAttributes(values: Map<String, String>): JsonArray = JsonArray(
        values.map { (key, value) ->
            buildJsonObject {
                put("key", key)
                put("value", buildJsonObject { put("stringValue", value) })
            }
        },
    )
}

/** OTLP/HTTP metrics 导出器：POST `application/json` 到 `<collector>/v1/metrics`（Jaeger/Tempo/Collector 4318 口） */
class OtlpHttpMetricsExporter(
    /** 实际 POST 的 metrics URL（`…/v1/metrics`）——日志/诊断与装配测试要用 */
    val endpoint: String,
    private val headers: Map<String, String> = emptyMap(),
    private val timeoutMs: Int = 5_000,
) : OtelMetrics.MetricsExporter {
    override fun export(resource: Map<String, String>, body: ByteArray): Boolean =
        OtlpHttp.post(endpoint, headers, body, timeoutMs)
}

/** 本地 JSONL 导出器：每批一行 resourceMetrics envelope，离线排障/演示用 */
class FileMetricsExporter(private val file: File) : OtelMetrics.MetricsExporter {
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

/** 测试/嵌入式用：把最近一批编码体留在内存里（不做任何 IO） */
class CollectingMetricsExporter : OtelMetrics.MetricsExporter {
    private val bodies = mutableListOf<String>()

    @Synchronized
    override fun export(resource: Map<String, String>, body: ByteArray): Boolean {
        bodies.add(String(body, Charsets.UTF_8))
        return true
    }

    @Synchronized
    fun bodies(): List<String> = bodies.toList()

    @Synchronized
    fun clear() = bodies.clear()
}

/**
 * metrics 侧的环境装配（T-065）——与 [OtelEnvironment] 的 traces 侧同形：
 * HTTP 端点优先，文件兜底；一个都没配 = null（零成本）。
 *
 * | 变量 | 作用 |
 * |---|---|
 * | `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT` | 完整 metrics URL（优先） |
 * | `OTEL_EXPORTER_OTLP_ENDPOINT` | 基础 URL，导出到 `<base>/v1/metrics` |
 * | `STRAGENT_OTEL_METRICS_FILE` | 不回传时落本地 JSONL（离线排障/演示） |
 */
object OtelMetricsEnvironment {
    const val METRICS_ENDPOINT = "OTEL_EXPORTER_OTLP_METRICS_ENDPOINT"
    const val METRICS_FILE = "STRAGENT_OTEL_METRICS_FILE"

    /** 端点优先于文件；都没有返回 null */
    fun exporterFrom(env: Map<String, String> = System.getenv()): OtelMetrics.MetricsExporter? {
        val metricsUrl = env[METRICS_ENDPOINT]?.trim()?.takeIf { it.isNotEmpty() }
            ?: env[OtelEnvironment.ENDPOINT]?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { base -> if (base.endsWith("/")) base + "v1/metrics" else "$base/v1/metrics" }
        if (metricsUrl != null) {
            return OtlpHttpMetricsExporter(metricsUrl, OtelEnvironment.parseHeaders(env[OtelEnvironment.HEADERS]))
        }
        val file = env[METRICS_FILE]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return FileMetricsExporter(File(file))
    }

    /**
     * 按环境变量装配注册表；一个都没配返回 null（零成本）。
     * resource 复用 traces 侧的口径（service.name/version）。
     */
    fun fromEnvironment(
        env: Map<String, String> = System.getenv(),
        serviceVersion: String = "dev",
    ): OtelMetrics? {
        val exporter = exporterFrom(env) ?: return null
        val resource = linkedMapOf(
            "service.name" to (env[OtelEnvironment.SERVICE_NAME]?.trim()?.takeIf { it.isNotEmpty() } ?: "str-agent"),
            "service.version" to serviceVersion,
        )
        return OtelMetrics(exporter, resource)
    }
}
