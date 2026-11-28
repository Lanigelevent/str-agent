package com.stragent.core.engine

/**
 * 双轨路由：决定一次分析走 tier-1 还是 tier-2。
 *
 * 路由口径（D10 §阶段1 · Two-tier 路由策略）：
 *  - 默认阈值 10_000 行（与 `Csv.MAX_ROWS` 同源），超过阈值即候选 tier-2；
 *  - **报告路径恒定 tier-1**（`reportPath = true`），保数字确定性可溯源；
 *  - `bigMode = true` 显式要求 tier-2（用户主动要规模时）；
 *  - tier-2 不可用时（如 Android 端尚未下发 DuckDB 原生库）回落 tier-1，
 *    由调用方据 `truncated` 显式提示「只统计了前 N 行」，不静默降级。
 *
 * 纯函数、无状态，可直接单测（路由决策是「数字从哪来」的第一道闸门，必须可预测）。
 */
object EngineRouter {
    /** 默认切档阈值：与 Csv.MAX_ROWS 同值，避免出现两个「1 万行」口径 */
    const val DEFAULT_TIER_THRESHOLD = 10_000

    /**
     * @param rowCount      待分析数据的真实行数
     * @param threshold     切档阈值（默认 10_000）
     * @param bigMode       显式要求列式引擎
     * @param tier2Available tier-2 是否可用（原生库就位 / 依赖可解析）
     * @param reportPath    是否走报告路径（报告恒 tier-1）
     */
    fun selectTier(
        rowCount: Int,
        threshold: Int = DEFAULT_TIER_THRESHOLD,
        bigMode: Boolean = false,
        tier2Available: Boolean = true,
        reportPath: Boolean = false,
    ): EngineTier = when {
        reportPath -> EngineTier.IN_MEMORY
        !tier2Available -> EngineTier.IN_MEMORY
        bigMode -> EngineTier.COLUMNAR
        rowCount > threshold -> EngineTier.COLUMNAR
        else -> EngineTier.IN_MEMORY
    }

    /**
     * 是否需要向用户显式提示「统计口径被截断」。
     *
     * 规则：最终落在 tier-1 且真实行数超过阈值 → 必须提示（不许静默降级）；
     * tier-2 已看到全量 → 不提示。
     */
    fun needsTruncationNotice(rowCount: Int, tier: EngineTier, threshold: Int = DEFAULT_TIER_THRESHOLD): Boolean =
        tier == EngineTier.IN_MEMORY && rowCount > threshold
}
