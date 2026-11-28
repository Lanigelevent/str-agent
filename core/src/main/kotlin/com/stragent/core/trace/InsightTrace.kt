package com.stragent.core.trace

import com.stragent.core.observability.GenAiAttrs
import com.stragent.core.observability.OtelSpan
import com.stragent.core.observability.OtelSpanContext
import com.stragent.core.observability.TraceIds
import com.stragent.core.observability.epochNanos
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/** 一次阶段跃迁（事件式记录，保留原有 JSON 形态；区间语义由 OTel 阶段 span 承担） */
data class TraceSpan(
    val stage: String,
    val detail: String?,
    val atMs: Long,
)

/**
 * 一次 generateInsight 调用的可观测轨迹（D10 阶段 0 可观测性）。
 *
 * 记录阶段跃迁（stage）、各阶段耗时、最终引擎、token 数与是否自主生成。
 * 落盘后可用于：回归比对、性能剖析、与外部 SOTA（D10 阶段 6 评测）对标耗时/token。
 *
 * 设计纪律：trace 只是「录制」，不侵入分析逻辑；任何 LLM/工具调用失败都不影响
 * trace 的产出（finally 式 finish）。
 *
 * ## OTel span 树（T-056 / D17 G-16）
 *
 * 除了原有的事件式 `spans` 列表，trace 同时维护一棵 span 树：
 *
 * ```
 * report.generate                    ← 根（gen_ai.operation.name / usage / cost）
 *   └ report.stage:analyzing         ← 相邻两次 mark 之间就是该阶段的区间
 *   └ report.stage:chapter-1
 *   └ …
 * ```
 *
 * 若上游 MCP 客户端在 `_meta.traceparent` 里给了父上下文，根 span 直接挂到
 * 那条 trace 下（[parent]）；否则新开一条 trace。[otelSpans] 交导出器批量上报。
 *
 * 成本口径：只有配置了每 1K token 单价（[costPer1kTokens]）才写 `str_agent.cost.usd`；
 * 未配置就**不写**——把未知成本写成 0 是编造。
 */
data class InsightTrace(
    val dataset: String,
    val startedAtMs: Long,
    val spans: MutableList<TraceSpan> = mutableListOf(),
    var engineId: String? = null,
    var totalTokens: Int = 0,
    var finishedAtMs: Long = 0,
    var autonomous: Boolean = false,
    /** 上游链路（MCP `_meta.traceparent` 解析结果）：报告 span 树挂到调用方 trace 下 */
    val parent: OtelSpanContext? = null,
    /** 每 1K token 的估算单价（USD）；null = 未配置，不写成本 */
    val costPer1kTokens: Double? = null,
    /** 本链路 trace id（与 [parent] 相同；无上游时新生成） */
    val traceId: String = parent?.traceId ?: TraceIds.traceId(),
) {
    // 采样标志随父上下文继承（T-065）：同一 trace 的决定在根上做一次，
    // 报告根与阶段 span 都必须与 tools/call span 同进同退
    private val sampled: Boolean = parent?.sampled ?: true

    /**
     * T-081：时长测量走单调时钟（nanoTime 差值）——`currentTimeMillis` 差值有两个缺陷：
     * Windows 粒度 ~15.6ms 把亚刻度时长量化成 0（T-080 的 OtelMetrics 抖动根因），
     * 且墙钟可被 NTP 回拨（负时长）。墙钟字段 [startedAtMs]/[finishedAtMs] 保留，
     * 只承担展示/审计语义，不再参与时长计算。
     */
    private val startedNanos: Long = System.nanoTime()
    private var finishedNanos: Long = 0L

    private val rootSpan: OtelSpan = OtelSpan(
        traceId = traceId,
        spanId = TraceIds.spanId(),
        parentSpanId = parent?.spanId,
        name = "report.generate",
        kind = OtelSpan.Kind.INTERNAL,
        startedAtNanos = startedAtMs * 1_000_000,
        sampled = sampled,
    ).set("str_agent.dataset.name", dataset)

    /** 已结束的阶段 span（按结束顺序） */
    private val stageSpans = mutableListOf<OtelSpan>()
    private var openStage: OtelSpan? = null

    val rootSpanId: String get() = rootSpan.spanId

    fun mark(stage: String, detail: String? = null) {
        val now = epochNanos()
        openStage?.let { previous ->
            previous.end(now)
            stageSpans.add(previous)
        }
        openStage = OtelSpan(
            traceId = traceId,
            spanId = TraceIds.spanId(),
            parentSpanId = rootSpan.spanId,
            name = otelStageName(stage),
            kind = OtelSpan.Kind.INTERNAL,
            startedAtNanos = now,
            sampled = sampled,
        )
            .set("str_agent.stage", stage)
            .also { span -> detail?.let { span.set("str_agent.stage.detail", it.take(MAX_DETAIL)) } }
        spans.add(TraceSpan(stage, detail, System.currentTimeMillis()))
    }

    fun finish(engineId: String?, totalTokens: Int, autonomous: Boolean) {
        this.engineId = engineId
        this.totalTokens = totalTokens
        this.autonomous = autonomous
        this.finishedAtMs = System.currentTimeMillis()
        this.finishedNanos = System.nanoTime()

        val now = epochNanos()
        openStage?.let { open ->
            open.end(now)
            stageSpans.add(open)
            openStage = null
        }
        rootSpan
            // GenAI 语义约定：操作名与 token 用量（本服务只拿得到 total_tokens，
            // 输入/输出拆分不在 LlmClient 契约里——不拆成两个编出来的数）。
            // T-164：属性名走单一映射层（GenAiAttrs），conventions 改名只动一处。
            .set(GenAiAttrs.OPERATION_NAME, "generate")
            .set(GenAiAttrs.SYSTEM, "str_agent")
            .set(GenAiAttrs.USAGE_TOTAL_TOKENS, totalTokens.toLong())
            .set("str_agent.engine.id", engineId ?: "unknown")
            .set("str_agent.autonomous", autonomous)
            .set("str_agent.stages", spans.size.toLong())
            .set("str_agent.duration_ms", durationMs())
        costPer1kTokens?.takeIf { totalTokens > 0 }?.let { price ->
            val usd = totalTokens / 1000.0 * price
            rootSpan
                .set("str_agent.cost.usd", Math.round(usd * 1_000_000.0) / 1_000_000.0)
                .set("str_agent.cost.basis", "configured price per 1K tokens")
        }
        rootSpan.ok().end(now)
    }

    /** 完整 span 树（阶段在前、根在后）——导出器按此顺序提交 */
    fun otelSpans(): List<OtelSpan> = stageSpans + rootSpan

    fun durationMs(): Long = if (finishedNanos == 0L) 0L else (finishedNanos - startedNanos) / 1_000_000

    fun toJson(): JsonObject = buildJsonObject {
        put("dataset", JsonPrimitive(dataset))
        put("startedAt", JsonPrimitive(Instant.ofEpochMilli(startedAtMs).toString()))
        put("durationMs", JsonPrimitive(durationMs()))
        put("engineId", JsonPrimitive(engineId ?: "unknown"))
        put("totalTokens", JsonPrimitive(totalTokens))
        put("autonomous", JsonPrimitive(autonomous))
        // T-056：链路标识随 JSON 一起给出——宿主能把这份 trace 与导出到 Jaeger/Tempo
        // 的 span 树对上（parentSpanId 非空 = 由外部 MCP 客户端发起）
        put("traceId", JsonPrimitive(traceId))
        put("rootSpanId", JsonPrimitive(rootSpan.spanId))
        parent?.let { put("parentSpanId", JsonPrimitive(it.spanId)) }
        put(
            "spans",
            JsonArray(
                spans.map { s ->
                    buildJsonObject {
                        put("stage", JsonPrimitive(s.stage))
                        s.detail?.let { put("detail", JsonPrimitive(it)) }
                        put("atMs", JsonPrimitive(s.atMs))
                    }
                },
            ),
        )
    }

    private fun otelStageName(stage: String): String {
        val name = "report.stage:$stage"
        return if (name.length <= MAX_SPAN_NAME) name else name.take(MAX_SPAN_NAME)
    }

    private companion object {
        const val MAX_DETAIL = 240
        const val MAX_SPAN_NAME = 120
    }
}
