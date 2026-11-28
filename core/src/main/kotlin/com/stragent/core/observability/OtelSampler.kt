package com.stragent.core.observability
import com.stragent.core.config.Config

/**
 * 头采样器（T-065，补 T-056 边界⑥「无采样器（默认全采）」）。
 *
 * 语义（OpenTelemetry 标准的 ParentBased(root=traceidratio)）：
 *  - **有上游 traceparent**：遵从上游的 sampled 标志——上游没让记，我们就不记；
 *    同一条 trace 的决定只在根上做一次，下游一律继承（含报告根/阶段 span）。
 *  - **新 trace 根**：按 traceId 做**确定性**比率判定——同一 traceId 永远得到同一
 *    决定（重放/对账不会因为采样而时有时无）。
 *
 * 配置：环境变量 `STRAGENT_OTEL_SAMPLER` = 0.0–1.0 的浮点数（缺省 **1.0 全采**）。
 * 非法值不猜测：返回 null（按全采走），由装配方打告警。
 *
 * 纪律：采样只影响**导出**，不影响业务——未采样 span 仍在本地创建（便宜），
 * 由 [OtelTraces.export] 统一过滤；MCP 取消、工具执行、回执内容零变化。
 */
class OtelSampler private constructor(private val ratio: Double) {

    /**
     * 采样判定。
     * @param traceId 新 trace 根时为**已生成的** traceId（先 id 后判定），有上游时传上游 traceId
     * @param hasParent 是否有上游 traceparent（合法解析的）
     * @param parentSampled 上游 flags 的 sampled 位
     */
    fun decide(traceId: String, hasParent: Boolean, parentSampled: Boolean): Boolean = when {
        hasParent -> parentSampled
        else -> ratio >= 1.0 || (ratio > 0.0 && hash01(traceId) < ratio)
    }

    companion object {
        const val ENV = "STRAGENT_OTEL_SAMPLER"

        /** 比率 1.0 = 全采（缺省），0.0 = 全不采 */
        val ALL: OtelSampler = OtelSampler(1.0)

        /** 未配置或参数非法 → null（按全采走，装配方告警） */
        fun fromEnvironment(env: Map<String, String> = System.getenv()): OtelSampler? =
            fromRatio(env[ENV]?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull())

        /** 比率越界（<0 或 >1）或 null → null */
        fun fromRatio(ratio: Double?): OtelSampler? = ratio?.takeIf { it in 0.0..1.0 }?.let { OtelSampler(it) }

        /**
         * traceId → [0,1) 的确定性归一值：前 16 位 hex 按位累积后除以 16^16（= 2^64）。
         * 只要求**确定性 + 均匀**（同一 traceId 决定不变），不要求与 Jaeger/OTel SDK 逐位一致；
         * 大数区间的浮点精度损失不影响这两个性质。
         */
        fun hash01(traceId: String): Double {
            require(traceId.length >= 16 && traceId.all { it in "0123456789abcdef" }) {
                "traceId 应为至少 16 位小写 hex：$traceId"
            }
            var value = 0.0
            for (i in 0 until 16) value = value * 16 + Character.digit(traceId[i], 16)
            return value / 1.8446744073709552E19 // 16^16 = 2^64
        }
    }
}
