package com.stragent.core.caliber

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaliberMemoryTest {
    @Test
    fun `候选抽取——信号词命中与不命中`() {
        // 命中：明确划范围
        assertEquals("这个数据华东不含上海", extractCaliberHint("这个数据华东不含上海"))
        // 命中：下定义
        assertTrue(extractCaliberHint("销售额这里指的是含税金额") != null)
        // 命中：纠正前述
        assertTrue(extractCaliberHint("准确说应该是剔除退货之后的") != null)
        // 不命中：普通提问
        assertNull(extractCaliberHint("华东占比多少？"))
        // 不命中：太短（"不含上海"四个字说不清口径）
        assertNull(extractCaliberHint("不含上海"))
        // 「应该是」单独出现也命中——TS 源码的正则就是如此（源码注释说"要共现"，
        // 但 /应该是|其实是|.../ 并无共现要求；行为以代码为准，注释与代码的分歧原样保留）
        assertTrue(extractCaliberHint("这应该是华东的数据吧") != null)
        // 超长截断 + 省略号（8 + 6×40 = 248 字 > 200）
        val longText = "销售额要扣除退货" + "。补充说明：".repeat(40)
        val hint = extractCaliberHint(longText)
        assertTrue(hint != null && hint.length == CALIBER_MAX_NOTE_CHARS + 1 && hint.endsWith("…"))
    }

    @Test
    fun `候选理由——用户有权知道判据`() {
        assertEquals("明确划出范围外", caliberHintReason("华东不包括上海"))
        assertEquals("在下定义", caliberHintReason("口径是含税的"))
        assertNull(caliberHintReason("普通问题"))
    }

    @Test
    fun `caliberBlock——独立块且带三条规则`() {
        assertNull(caliberBlock(emptyList()))
        val block = caliberBlock(listOf(CaliberNote("1", "华东不含上海", "followup", 1)))
        assertTrue(block!!.startsWith("<user_caliber>"))
        assertTrue(block.contains("- 华东不含上海"))
        assertTrue(block.contains("不是数据"))
        assertTrue(block.endsWith("</user_caliber>"))
        assertFalse(block.contains("<allowed_numbers>"))
    }
}
