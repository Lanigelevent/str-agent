package com.stragent.data

import com.stragent.core.csv.ColumnType
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class XlsxParserTest {
    /** 现场构造一个最小 xlsx：字符串列 + 数字列 + 日期列（样式 1 = 内置 14 日期档） */
    private fun buildXlsx(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
            )
            put(
                "xl/workbook.xml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheets><sheet name="销售数据" sheetId="1" r:id="r1"/></sheets>
                </workbook>""",
            )
            put(
                "xl/sharedStrings.xml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <sst count="2" uniqueCount="2">
                  <si><t>华东</t></si>
                  <si><t>产品A</t></si>
                </sst>""",
            )
            put(
                "xl/styles.xml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <cellXfs count="2">
                    <xf numFmtId="0"/>
                    <xf numFmtId="14"/>
                  </cellXfs>
                </styleSheet>""",
            )
            // r 引用 + 缺 r（顺序推断）混合；日期列用样式 s="1"
            put(
                "xl/worksheets/sheet1.xml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" s="1"><v>44927</v></c></row>
                    <row r="2"><c r="A2" t="s"><v>0</v></c><c r="B2"><v>1200.5</v></c><c r="C2" s="1"><v>44958</v></c></row>
                    <row><c t="s"><v>0</v></c><c><v>80</v></c><c s="1"><v>44986</v></c></row>
                  </sheetData>
                </worksheet>""",
            )
        }
        return out.toByteArray()
    }

    @Test
    fun `parseXlsx——字符串池、数字、日期序列与缺 r 推断`() {
        val sheets = parseXlsx(ByteArrayInputStream(buildXlsx()))
        assertEquals(1, sheets.size)
        assertEquals("销售数据", sheets[0].name)
        val table = sheets[0].table
        assertEquals(listOf("华东", "产品A", "2023-01-01"), table.columns, "首行表头；日期序列 44927 = 2023-01-01")
        assertEquals(2, table.rows.size)
        assertEquals(listOf("华东", "1200.5", "2023-02-01"), table.rows[0])
        // 第三行缺 r：列按顺序推断
        assertEquals(listOf("华东", "80", "2023-03-01"), table.rows[1])
        // 数字列被推断为 NUMBER、日期列是文本（yyyy-MM-dd 字符串）
        assertEquals(ColumnType.NUMBER, table.columnTypes[1])
    }

    @Test
    fun `tableToCsv 与 datasetFromTable 走通全链`() {
        val sheets = parseXlsx(ByteArrayInputStream(buildXlsx()))
        val table = sheets[0].table
        val csv = tableToCsv(table)
        // CSV 能被 parseDelimited 无损还原（含逗号字段会加引号——这里没有）
        val reparsed = datasetFromCsv(csv, "销售数据", 1L)!!
        assertEquals(table.columns, reparsed.columns)
        assertEquals(table.rows, reparsed.rows)
        // 空表防护
        assertEquals(null, datasetFromTable("x", 1L, com.stragent.core.csv.ParsedTable(listOf(), emptyList(), emptyList(), ',', false)))
    }

    @Test
    fun `空工作表报中文错误`() {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(
                """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData/></worksheet>"""
                    .toByteArray(),
            )
            zip.closeEntry()
        }
        val err = runCatching { parseXlsxFirst(ByteArrayInputStream(out.toByteArray())) }.exceptionOrNull()
        assertTrue(err is IllegalArgumentException && err.message!!.contains("没有数据行"), "实际: $err")
    }

    @Test
    fun `解压总量超限时报压缩炸弹——不把内容读进内存`() {
        // 同一份正常文件，把预算压到 64 字节：首个 entry 读几行 XML 就会超
        val err = runCatching {
            parseXlsx(ByteArrayInputStream(buildXlsx()), maxUncompressedBytes = 64)
        }.exceptionOrNull()
        assertNotNull(err, "超限应失败而不是继续解析")
        val chain = generateSequence(err) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
        assertTrue(chain.contains("压缩炸弹"), "错误链应说明解压预算：$chain")
        // 默认预算下同一份文件仍能正常解析（预算只在异常输入上起作用）
        assertEquals(1, parseXlsx(ByteArrayInputStream(buildXlsx())).size)
    }
}
