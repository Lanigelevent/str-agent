package com.stragent.core.engine

import com.stragent.core.csv.Csv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 双轨路由的决策测试。
 *
 * 路由是「数字从哪来」的第一道闸门，决策必须可预测：
 * 报告路径恒 tier-1（可逐行回溯）、超阈值走 tier-2、tier-2 不可用回落且**必须显式提示**。
 */
class EngineRouterTest {

    @Test
    fun `报告路径恒走 tier-1（哪怕数据超大）`() {
        assertEquals(
            EngineTier.IN_MEMORY,
            EngineRouter.selectTier(rowCount = 5_000_000, reportPath = true),
        )
    }

    @Test
    fun `超过阈值走 tier-2，阈值内走 tier-1`() {
        assertEquals(EngineTier.IN_MEMORY, EngineRouter.selectTier(rowCount = 10_000))
        assertEquals(EngineTier.COLUMNAR, EngineRouter.selectTier(rowCount = 10_001))
    }

    @Test
    fun `bigMode 显式要求 tier-2`() {
        assertEquals(EngineTier.COLUMNAR, EngineRouter.selectTier(rowCount = 10, bigMode = true))
    }

    @Test
    fun `tier-2 不可用时回落 tier-1`() {
        assertEquals(
            EngineTier.IN_MEMORY,
            EngineRouter.selectTier(rowCount = 100_000, tier2Available = false),
        )
    }

    @Test
    fun `回落 tier-1 且行数超阈值时必须提示截断`() {
        assertTrue(EngineRouter.needsTruncationNotice(100_000, EngineTier.IN_MEMORY))
        assertFalse(EngineRouter.needsTruncationNotice(100_000, EngineTier.COLUMNAR))
        assertFalse(EngineRouter.needsTruncationNotice(10, EngineTier.IN_MEMORY))
    }

    @Test
    fun `默认阈值与 CSV 解析器的 MAX_ROWS 同源（不出现两个一万行口径）`() {
        assertEquals(Csv.MAX_ROWS, EngineRouter.DEFAULT_TIER_THRESHOLD)
    }
}
