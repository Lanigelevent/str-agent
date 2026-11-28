package com.stragent.bench

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 评测基座的**退出码口径**：性能回归 FAIL 必须真的让门禁转红。
 *
 * 历史坑（D15 T12 收口时发现）：`BenchMain` 打印了「== 性能回归 FAIL ==」横幅，
 * 但退出码只判 `card.ok`——CI 与本地都把「打印 FAIL 却退出 0」当成通过。
 * 这里把两种失败源（套件硬失败 / 性能回归 FAIL）与放行条件逐一钉住。
 */
class GateExitTest {

    @Test
    fun `套件全过但性能回归 FAIL——门禁必须转红`() {
        assertTrue(shouldFail(scorecardOk = true, perfOk = false), "性能 FAIL 不能只打印横幅")
    }

    @Test
    fun `性能通过但套件硬失败——门禁必须转红`() {
        assertTrue(shouldFail(scorecardOk = false, perfOk = true))
    }

    @Test
    fun `两者都失败——门禁转红`() {
        assertTrue(shouldFail(scorecardOk = false, perfOk = false))
    }

    @Test
    fun `全部通过才放行`() {
        assertFalse(shouldFail(scorecardOk = true, perfOk = true))
    }
}
