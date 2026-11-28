package com.stragent.core.verify

import com.stragent.core.report.buildAllowedNumbers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-043：样本行数值不作为引用依据。
 *
 * 样本段只服务于「字段形态 / 取值样式」；整行 CSV 的逗号会把多个值合并成一个 token，
 * 逐值授权不可靠。verifyReport 与 ReviewerAgent 的白名单都只取样本段之前的文本。
 */
class CitableBriefTest {

    private val brief = """
        分组求和（金额）
        - 合计 6,739
        | 均值 | 187.19 |
        前 3 行样本：
        （样本只用于看字段形态与取值样式，样本行的单个数值不得作为报告论据引用。）
        ```
        日期,金额,数量
        2025-01,460,3950
        ```
    """.trimIndent()

    @Test
    fun `样本段被排除在可引用文本之外`() {
        val citable = citableBriefText(brief)
        assertTrue(citable.contains("6,739"))
        assertFalse(citable.contains("460"), "样本值不应进入可引用文本：$citable")
        assertFalse(citable.contains("行样本"))
    }

    @Test
    fun `统计值仍可溯源，样本值被判未溯源`() {
        val ok = verifyReport("合计 6,739。", brief)
        assertEquals(0, ok.unsourced.size, "统计值应放行：${ok.unsourced}")
        val bad = verifyReport("样本里有一行金额 460。", brief)
        assertEquals(1, bad.unsourced.size, "样本值应被拦下：${bad.unsourced}")
        assertEquals("460", bad.unsourced.single().value)
    }

    @Test
    fun `没有样本段时行为不变`() {
        val plain = "分组求和（金额）\n- 合计 6,739"
        assertEquals(plain, citableBriefText(plain))
        assertEquals(0, verifyReport("合计 6,739。", plain).unsourced.size)
    }

    @Test
    fun `审稿白名单与 verifyReport 同口径`() {
        val allowed = buildAllowedNumbers(brief)
        assertTrue(allowed.contains(6739.0))
        assertFalse(allowed.contains(460.0))
        assertFalse(allowed.contains(3950.0))
    }
}
