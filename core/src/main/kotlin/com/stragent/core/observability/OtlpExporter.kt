package com.stragent.core.observability

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * OTLP/HTTP JSON 编码（T-056 / D17 G-16）——按 OpenTelemetry Protocol 1.3 的
 * `resourceSpans[].scopeSpans[].spans[]` 结构生成。
 *
 * 口径细节（容易写错的地方，这里钉死）：
 *  - `traceId`/`spanId` 是**小写 hex**；`startTimeUnixNano`/`endTimeUnixNano` 是
 *    **字符串形式的整数**（OTLP JSON 映射要求 64 位整数用字符串，否则 JS 客户端会丢精度）；
 *  - `kind` 是枚举数字（1=INTERNAL、2=SERVER），`status.code` 是 0/1/2；
 *  - 属性值是 tagged union：`stringValue` / `boolValue` / `intValue`（字符串）/
 *    `doubleValue`（数字）；
 *  - 空属性不写 `attributes` 字段（而不是写一个空数组）。
 */
object OtlpJson {

    const val SCOPE_NAME = "com.stragent"
    const val SCOPE_VERSION = "1.0.0"

    fun envelope(resource: Map<String, String>, spans: List<OtelSpan>): JsonObject = buildJsonObject {
        put(
            "resourceSpans",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("resource", buildJsonObject { put("attributes", attributes(resource)) })
                        put(
                            "scopeSpans",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("scope", buildJsonObject {
                                            put("name", SCOPE_NAME)
                                            put("version", SCOPE_VERSION)
                                        })
                                        put("spans", JsonArray(spans.map { spanJson(it) }))
                                    },
                                ),
                            ),
                        )
                    },
                ),
            ),
        )
    }

    private fun spanJson(span: OtelSpan): JsonObject = buildJsonObject {
        put("traceId", span.traceId)
        put("spanId", span.spanId)
        span.parentSpanId?.let { put("parentSpanId", it) }
        put("name", span.name)
        put("kind", span.kind.wire)
        put("startTimeUnixNano", span.startedAtNanos.toString())
        put("endTimeUnixNano", (span.endedAtNanos ?: span.startedAtNanos).toString())
        val attrs = span.attributesSnapshot()
        if (attrs.isNotEmpty()) put("attributes", attributes(attrs))
        put(
            "status",
            buildJsonObject {
                put("code", span.status.wire)
                span.statusMessage?.let { put("message", it) }
            },
        )
    }

    private fun attributes(values: Map<String, Any>): JsonArray = JsonArray(
        values.map { (key, value) ->
            buildJsonObject {
                put("key", key)
                put("value", attributeValue(value))
            }
        },
    )

    private fun attributeValue(value: Any): JsonObject = buildJsonObject {
        when (value) {
            is Boolean -> put("boolValue", value)
            is Int, is Long -> put("intValue", value.toString())
            is Double -> put("doubleValue", value)
            else -> put("stringValue", value.toString())
        }
    }
}

/**
 * traces/metrics 两个信号共用的 POST 助手（T-065 抽出）——一份 HTTP 逻辑，两处复用。
 * 失败只回 false（调用方决定记不记告警），绝不抛给业务线程。
 */
internal object OtlpHttp {
    fun post(url: String, headers: Map<String, String>, body: ByteArray, timeoutMs: Int): Boolean {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            // 读掉响应体，避免连接池里留半截流
            (if (code >= 400) connection.errorStream else connection.inputStream)?.use { it.readBytes() }
            code in 200..299
        } catch (_: Exception) {
            false
        } finally {
            runCatching { connection.disconnect() }
        }
    }
}

/**
 * OTLP/HTTP 导出器：POST `application/json` 到 `<collector>/v1/traces`。
 *
 * 兼容目标：Jaeger 1.5x+（`--collector.otlp.enabled`）、Tempo 的 OTLP HTTP 口
 * （4318）、OpenTelemetry Collector。失败**只影响可观测性**：返回 false，
 * 由 [OtelTraces] 记一次告警，绝不抛给业务线程。
 *
 * 不做 gzip：JSON + 小批量（一次报告几十个 span），压缩收益不值一个额外编码依赖。
 */
class OtlpHttpSpanExporter(
    /** 实际 POST 的 traces URL（`…/v1/traces`）——日志/诊断与装配测试要用 */
    val endpoint: String,
    private val headers: Map<String, String> = emptyMap(),
    private val timeoutMs: Int = 5_000,
) : SpanExporter {

    override fun export(resource: Map<String, String>, spans: List<OtelSpan>): Boolean {
        if (spans.isEmpty()) return true
        val body = OtlpJson.envelope(resource, spans).toString().toByteArray(Charsets.UTF_8)
        return OtlpHttp.post(endpoint, headers, body, timeoutMs)
    }
}

/**
 * 本地 JSONL 导出器：每批一行 `{"resource":{…},"spans":[…]}`，离线排障/演示用。
 *
 * 与 HTTP 导出器的数据**逐字同构**（同一个 [OtlpJson.envelope] 生成），
 * 把 resource 展平成一行只是为了 `grep`/`jq` 方便。
 */
class FileSpanExporter(private val file: File) : SpanExporter {

    override fun export(resource: Map<String, String>, spans: List<OtelSpan>): Boolean = try {
        val line = OtlpJson.envelope(resource, spans).toString()
        synchronized(this) {
            file.parentFile?.mkdirs()
            file.appendText(line + "\n", Charsets.UTF_8)
        }
        true
    } catch (_: Exception) {
        false
    }
}

/** 测试/嵌入式用：把最近一批 span 留在内存里（不做任何 IO） */
class CollectingSpanExporter : SpanExporter {
    private val batches = mutableListOf<List<OtelSpan>>()

    @Synchronized
    override fun export(resource: Map<String, String>, spans: List<OtelSpan>): Boolean {
        batches.add(spans)
        return true
    }

    @Synchronized
    fun spans(): List<OtelSpan> = batches.flatten()

    @Synchronized
    fun clear() = batches.clear()
}
