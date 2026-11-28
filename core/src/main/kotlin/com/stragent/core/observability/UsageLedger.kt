package com.stragent.core.observability

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 用量与成本账本（T-171 · 成本可观测）。
 *
 * 现状与缺口：仓内已有 token 口径（`LlmClient` 契约的 `totalTokens`，一路流到报告回执与
 * OTel 的 `gen_ai.usage.total_tokens`），也有成本估算单价（`STRAGENT_COST_PER_1K_TOKENS`），
 * 但估算**只落在 OTel span 上**——没配 OTel 导出的人看不到；也**没有分面**（按工具 / 按引擎），
 * 一次报告到底花了多少只能翻日志。
 *
 * 本账本补的就是这三件事：
 *  1. **累计**：进程内累计每次工具调用的用量（按工具、按引擎分面）；
 *  2. **成本估算**：按配置单价换算，`basis` 明写 `estimate`（估算）或 `unpriced`（未配单价）；
 *  3. **零成本路径**：不配任何东西也有结构化数字（tokens / calls），只是没有金额。
 *
 * 纪律（与仓内「不编造」一致）：
 *  - **没有单价就不写金额**——把未知成本写成 0 是编造，`unpriced` 是唯一诚实的写法；
 *  - **金额永远是估算**：真实账单需要厂商侧数据，本服务拿不到——`basis` 字段恒标明；
 *  - **同源**：账本吃的是与 OTel、回执**同一份** token 数（工具回执里的 `totalTokens`），
 *    不新增第二套计数口径；
 *  - **只读**：账本只累计与查询，不参与任何计费或限流决策。
 */
class UsageLedger(
    /** 每 1K token 的估算单价（USD）；null = 未配置 → 不写金额 */
    private val costPer1kTokens: Double? = null,
) {

    /** 一个分面桶：调用次数与 token 数 */
    private data class Bucket(var calls: Long = 0L, var tokens: Long = 0L)

    private val byTool = ConcurrentHashMap<String, Bucket>()
    private val byEngine = ConcurrentHashMap<String, Bucket>()
    private val lock = Any()

    /**
     * 记一次工具调用的用量。
     *
     * @param tool 工具名（分面键）
     * @param tokens 本次消耗的 token 数（≤0 不计——不知道就不记）
     * @param engineId 报告引擎 id（可选；无则不记引擎分面）
     */
    fun record(tool: String, tokens: Int, engineId: String? = null) {
        if (tokens <= 0) return
        synchronized(lock) {
            byTool.getOrPut(tool) { Bucket() }.let {
                it.calls += 1
                it.tokens += tokens
            }
            if (!engineId.isNullOrBlank()) {
                byEngine.getOrPut(engineId) { Bucket() }.let {
                    it.calls += 1
                    it.tokens += tokens
                }
            }
        }
    }

    /** 按模型的 LLM 调用统计（T-232：模型名来自 ChatResult.model，精确名不丢） */
    private data class ModelBucket(var calls: Long = 0L, var tokens: Long = 0L, var errors: Long = 0L)

    private val byModel = ConcurrentHashMap<String, ModelBucket>()

    /**
     * 记一次 **LLM 客户端层**的真实调用（T-232）。
     *
     * 与 [record]（回执层的工具/引擎语义分面）**是两个采集点，不相加**：
     *  - 这里记的是「模型服务实际被调用了几次、花了多少 token」——成本归因的权威口径；
     *  - [record] 记的是「哪次工具调用产出了多少 token」——用于按工具/引擎看支出结构。
     * 一次报告里同一个 token 会在两个分面各出现一次，合计时要选一个口径，不能求和。
     */
    fun recordLlmCall(model: String?, tokens: Int, ok: Boolean) {
        val key = model?.takeIf { it.isNotBlank() } ?: "unknown"
        synchronized(lock) {
            byModel.getOrPut(key) { ModelBucket() }.let { bucket ->
                bucket.calls += 1
                bucket.tokens += tokens.coerceAtLeast(0)
                if (!ok) bucket.errors += 1
            }
        }
    }

    /** 累计 token 总数 */
    fun totalTokens(): Long = synchronized(lock) { byTool.values.sumOf { it.tokens } }

    /** 成本估算（USD）；未配单价 → null（不写 0） */
    fun estimateUsd(tokens: Long): Double? {
        val rate = costPer1kTokens ?: return null
        if (tokens <= 0) return null
        return tokens / 1000.0 * rate
    }

    /**
     * 账本快照（回执与 /ui 驾驶舱共用同一形状）。
     *
     * 金额口径：`costBasis` = `estimate`（有单价，按估算）/ `unpriced`（无单价，不写金额）。
     */
    fun snapshot(): JsonObject = synchronized(lock) {
        val totalsTokens = byTool.values.sumOf { it.tokens }
        val totalsCalls = byTool.values.sumOf { it.calls }
        buildJsonObject {
            put(
                "totals",
                buildJsonObject {
                    put("calls", totalsCalls)
                    put("tokens", totalsTokens)
                    put("costBasis", if (costPer1kTokens != null) "estimate" else "unpriced")
                    costPer1kTokens?.let { put("rateUsdPer1kTokens", it) }
                    estimateUsd(totalsTokens)?.let { put("estCostUsd", round6(it)) }
                },
            )
            put(
                "byTool",
                JsonArray(
                    byTool.entries.sortedByDescending { it.value.tokens }.map { (tool, bucket) ->
                        buildJsonObject {
                            put("tool", JsonPrimitive(tool))
                            put("calls", bucket.calls)
                            put("tokens", bucket.tokens)
                            estimateUsd(bucket.tokens)?.let { put("estCostUsd", round6(it)) }
                        }
                    },
                ),
            )
            put(
                "byEngine",
                JsonArray(
                    byEngine.entries.sortedByDescending { it.value.tokens }.map { (engine, bucket) ->
                        buildJsonObject {
                            put("engine", JsonPrimitive(engine))
                            put("calls", bucket.calls)
                            put("tokens", bucket.tokens)
                            estimateUsd(bucket.tokens)?.let { put("estCostUsd", round6(it)) }
                        }
                    },
                ),
            )
            put(
                "byModel",
                JsonArray(
                    byModel.entries.sortedByDescending { it.value.tokens }.map { (model, bucket) ->
                        buildJsonObject {
                            put("model", JsonPrimitive(model))
                            put("calls", bucket.calls)
                            put("tokens", bucket.tokens)
                            put("errors", bucket.errors)
                            estimateUsd(bucket.tokens)?.let { put("estCostUsd", round6(it)) }
                        }
                    },
                ),
            )
            put(
                "note",
                JsonPrimitive("byTool/byEngine 来自回执层（语义分面），byModel 来自 LLM 客户端层（实际调用）——两个采集点不相加；金额恒为估算"),  // lint:allow zh-first（快照口径说明，用户面可见）
            )
        }
    }

    /** 单次调用的用量块（随回执给出；金额口径同上） */
    fun usageBlock(tool: String, tokens: Int, engineId: String? = null): JsonObject = buildJsonObject {
        put("tool", JsonPrimitive(tool))
        put("calls", 1)
        put("tokens", tokens)
        engineId?.let { put("engine", JsonPrimitive(it)) }
        put("costBasis", if (costPer1kTokens != null) "estimate" else "unpriced")
        costPer1kTokens?.let { put("rateUsdPer1kTokens", it) }
        estimateUsd(tokens.toLong())?.let { put("estCostUsd", round6(it)) }
    }

    private fun round6(value: Double): Double = Math.round(value * 1_000_000.0) / 1_000_000.0
}
