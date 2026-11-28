package com.stragent.core.csv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `parseDelimitedFull`（不截断解析）与 `parseDelimited`（10K 截断，TS 契约）的分工测试。
 *
 * 双轨对拍要拿「同一份数据的完整口径」，所以必须有免截断入口；
 * 但**报告路径仍走 parseDelimited** —— 这里的断言就是防止有人顺手把截断删掉。
 */
class CsvFullParseTest {

    private fun bigCsv(rows: Int): String = buildString {
        appendLine("a,b")
        repeat(rows) { appendLine("$it,${it * 2}") }
    }

    @Test
    fun `parseDelimitedFull 不截断且总行数完整`() {
        val full = Csv.parseDelimitedFull(bigCsv(10_050))
        assertEquals(10_050, full.rows.size)
        assertEquals(10_050, full.totalRows)
        assertFalse(full.truncated)
    }

    @Test
    fun `parseDelimited 仍在 MAX_ROWS 截断（与 TS 契约一致，不许改）`() {
        val limited = Csv.parseDelimited(bigCsv(10_050))
        assertEquals(Csv.MAX_ROWS, limited.rows.size)
        assertEquals(10_050, limited.totalRows)
        assertTrue(limited.truncated)
    }

    @Test
    fun `两者对未超阈值的样本结果一致`() {
        val text = "a,b\n1,2\n3,4\n"
        val limited = Csv.parseDelimited(text)
        val full = Csv.parseDelimitedFull(text)
        assertEquals(limited.columns, full.columns)
        assertEquals(limited.rows, full.rows)
        assertEquals(limited.columnTypes, full.columnTypes)
    }
}
