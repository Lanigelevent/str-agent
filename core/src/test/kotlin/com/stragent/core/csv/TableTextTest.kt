package com.stragent.core.csv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TableTextTest {
    @Test
    fun `parseJsonTable——对象数组按首键序、补缺失键`() {
        val table = parseJsonTable("""[{"月份":"2024-01","销售额":100},{"销售额":"200","利润":5}]""")
        assertEquals(listOf("月份", "销售额", "利润"), table.columns)
        assertEquals(listOf("2024-01", "100", ""), table.rows[0])
        assertEquals(listOf("", "200", "5"), table.rows[1])
        assertEquals(ColumnType.NUMBER, table.columnTypes[1])
    }

    @Test
    fun `parseJsonTable——数组的数组与包装容错`() {
        // TS 口径：数组的数组表头用 列N 占位（首行的值不进表头）
        val nested = parseJsonTable("""[["a","b"],["1","2"]]""")
        assertEquals(listOf("列1", "列2"), nested.columns)
        assertEquals(listOf("1", "2"), nested.rows.single())
        // { data: [...] }、{ rows: [...] }、{ items }、{ list } 包装
        val wrapped = parseJsonTable("""{"data":[{"x":1}]}""")
        assertEquals(listOf("x"), wrapped.columns)
        val rowsKey = parseJsonTable("""{"rows":[{"x":1}]}""")
        assertEquals(1, rowsKey.rows.size)
    }

    @Test
    fun `parseJsonTable——非法输入报中文错误`() {
        assertFailsWith<IllegalArgumentException> { parseJsonTable("not json") }
        assertFailsWith<IllegalArgumentException> { parseJsonTable("""{"a":1}""") }
    }

    @Test
    fun `parseHtmlTable——抽 tr 与 td、去标签解码实体`() {
        val html = "<table><tr><th>名称</th><th>值</th></tr>" +
            "<tr><td>a&nbsp;b</td><td>1&lt;2</td></tr>" +
            "<tr><td>c</td><td>3</td></tr></table>"
        val table = parseHtmlTable(html)
        assertEquals(listOf("名称", "值"), table.columns)
        assertEquals(listOf("a b", "1<2"), table.rows[0])
    }

    @Test
    fun `parseMarkdownTable——跳过分隔行、残缺单元格补齐`() {
        val md = "| A | B |\n|---|:--:|\n| 1 | 2 |\n| 3 |"
        val table = parseMarkdownTable(md)
        assertEquals(listOf("A", "B"), table.columns)
        assertEquals(listOf("3", ""), table.rows[1])
    }

    @Test
    fun `表头去重与列1填充与 TS 同口径`() {
        val md = "| | a |\n|---|---|\n| x | 1 |"
        val table = parseMarkdownTable(md)
        assertEquals(listOf("列1", "a"), table.columns)
        val dup = parseJsonTable("""[{"a":1},{"a":2}]""")
        assertEquals(listOf("a"), dup.columns)
    }

    @Test
    fun `detectPasteKind——先 JSON 与 HTML，Markdown 分隔行，CSV 兜底`() {
        assertEquals(PasteKind.JSON, detectPasteKind("""[{"a":1}]"""))
        assertEquals(PasteKind.JSON, detectPasteKind("""{"rows":[]}"""))
        assertEquals(PasteKind.HTML, detectPasteKind("<table><tr><td>1</td></tr></table>"))
        assertEquals(PasteKind.HTML, detectPasteKind("<TR><TD>x</TD></TR>"))
        assertEquals(PasteKind.MARKDOWN, detectPasteKind("| a | b |\n|---|---|\n| 1 | 2 |"))
        assertEquals(PasteKind.CSV, detectPasteKind("a,b\n1,2"))
        assertEquals(PasteKind.CSV, detectPasteKind("a\tb\n1\t2"))
    }

    @Test
    fun `parsePasted——非 CSV 直接出表，CSV 返回 null 走兜底`() {
        val table = parsePasted("| a |\n|---|\n| 1 |")!!
        assertEquals(listOf("a"), table.columns)
        assertEquals(null, parsePasted("a,b\n1,2"))
    }

    @Test
    fun `列1 填充场景与 inferTypes 走同一套口径`() {
        val table = parseJsonTable("""[{"":1,"b":"x"}]""")
        assertEquals(listOf("列1", "b"), table.columns)
        assertTrue(table.columnTypes[0] == ColumnType.NUMBER)
    }
}
