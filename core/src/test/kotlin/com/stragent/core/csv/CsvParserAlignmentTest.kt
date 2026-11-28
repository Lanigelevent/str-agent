package com.stragent.core.csv

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * csv 移植的对齐测试。断言值来自两侧：multi-entry/superstore-entry 的既有契约
 * （TS 侧已全绿），以及 fixtures/sample/ 的同一批边界夹具（Kotlin 直接读同一批文件）。
 */
class CsvParserAlignmentTest {
    private val parser = Csv
    private val sampleDir = File("../fixtures/sample")

    private fun sample(name: String): String = File(sampleDir, name).readText()

    // ---------- 解析口径 ----------

    @Test
    fun `superstore 规模与统计口径`() {
        val t = parser.parseDelimited(sample("superstore.csv"))
        assertEquals(9994, t.rows.size)
        assertEquals(21, t.columns.size)
        // 空首列补名、去空格
        assertEquals("列1", t.columns[0])
        assertEquals("Sales", t.columns[17])
        assertEquals("Profit", t.columns[20])
        // 末行 Order ID（清洗版 +1 年指纹）
        assertEquals("CA-2018-119914", t.rows[9993][2])
        // 类型推断：Profit 65 个脏值仍过 85% 阈值
        assertEquals(ColumnType.NUMBER, t.columnTypes[17])
        assertEquals(ColumnType.NUMBER, t.columnTypes[20])
        assertEquals(ColumnType.TEXT, t.columnTypes[3]) // Order Date（美式文本）
    }

    @Test
    fun `边界样本 quoted 引号三形态`() {
        val t = parser.parseDelimited(sample("quoted.csv"))
        assertEquals("笔记本, 13 寸", t.rows[0][0])
        assertEquals("含\"三年\"保修", t.rows[0][1])
        assertEquals("支持\n多行备注", t.rows[1][1])
        assertEquals(ColumnType.NUMBER, t.columnTypes[2])
    }

    @Test
    fun `边界样本 bom_crlf BOM 与 CRLF 剥离`() {
        val t = parser.parseDelimited(sample("bom_crlf.csv"))
        assertEquals(listOf("产品", "销售额"), t.columns)
        assertEquals(3, t.rows.size)
        assertEquals("120", t.rows[0][1])
    }

    @Test
    fun `边界样本 percent 科学计数法 千分位`() {
        val percent = parser.parseDelimited(sample("percent.csv"))
        // TS 口径：parseNumber 只剥 % 后缀，不做百分制换算（换算在 report-verify 的等价判定里）
        assertEquals(85.0, Csv.parseNumber(percent.rows[0][1]))
        assertEquals(120.5, Csv.parseNumber(percent.rows[1][1]))
        val sci = parser.parseDelimited(sample("scientific.csv"))
        assertEquals(1500.0, Csv.parseNumber(sci.rows[0][1]))
        assertEquals(0.00023, Csv.parseNumber(sci.rows[0][2]))
        // 千分位逗号（标准分组才认）
        assertEquals(1234.0, Csv.parseNumber("1,234"))
        assertEquals(1234.56, Csv.parseNumber("1,234.56"))
        assertNull(Csv.parseNumber("12,34")) // 非标准分组 → 文本
        assertNull(Csv.parseNumber(" "))
    }

    @Test
    fun `边界样本 dup_headers 循环后缀`() {
        val t = parser.parseDelimited(sample("dup_headers.csv"))
        assertEquals(listOf("名称", "数值", "数值_2"), t.columns)
    }

    @Test
    fun `边界样本 overflow 截断到 MAX_ROWS`() {
        val t = parser.parseDelimited(sample("overflow.csv"))
        assertEquals(Csv.MAX_ROWS, t.rows.size)
        assertTrue(t.truncated)
        assertEquals(10050, t.totalRows)
    }

    @Test
    fun `空表与坏表头抛中文错误`() {
        val empty = runCatching { parser.parseDelimited("   \n \n") }.exceptionOrNull()
        assertTrue(empty?.message?.contains("没有解析到任何数据行") == true)
        val badHeader = runCatching { parser.parseDelimited("?,?\n1,2\n") }.exceptionOrNull()
        // ?,? 非全空 → 合法表头（问号是普通字符），不应抛
        assertTrue(badHeader == null)
    }

    @Test
    fun `limitRows 统一限行并重推类型`() {
        val t = parser.parseDelimited(sample("overflow.csv"))
        val limited = Csv.limitRows(t)
        assertEquals(Csv.MAX_ROWS, limited.rows.size)
        assertTrue(limited.truncated)
    }
}
