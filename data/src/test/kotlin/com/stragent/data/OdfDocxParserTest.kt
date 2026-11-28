package com.stragent.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** ODS / fods / docx 表格解析（零依赖 SAX，与 XlsxParser 同路线） */
class OdfDocxParserTest {

    private fun odsContentXml() = """<?xml version="1.0" encoding="UTF-8"?>
    <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
        xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"
        xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">
      <office:body><office:spreadsheet>
        <table:table table:name="销售数据">
          <table:table-row>
            <table:table-cell office:value-type="string"><text:p>门店</text:p></table:table-cell>
            <table:table-cell office:value-type="string"><text:p>销售额</text:p></table:table-cell>
            <table:table-cell office:value-type="date" office:date-value="2023-01-01"><text:p>2023-01-01</text:p></table:table-cell>
          </table:table-row>
          <table:table-row>
            <table:table-cell office:value-type="string"><text:p>华东</text:p></table:table-cell>
            <table:table-cell office:value-type="float" office:value="1200.5"><text:p>1,200.5</text:p></table:table-cell>
            <table:table-cell table:number-columns-repeated="2"/>
          </table:table-row>
          <table:table-row>
            <table:table-cell office:value-type="string"><text:p>华南</text:p></table:table-cell>
            <table:table-cell office:value-type="float" office:value="800"><text:p>800</text:p></table:table-cell>
            <table:table-cell office:value-type="date" office:date-value="2023-03-15"><text:p>2023-03-15</text:p></table:table-cell>
          </table:table-row>
        </table:table>
      </office:spreadsheet></office:body>
    </office:document-content>"""

    private fun zipOf(name: String, content: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `parseOds——数字取 office value、日期取 date value、文本取 text`() {
        val sheets = parseOds(ByteArrayInputStream(zipOf("content.xml", odsContentXml())))
        assertEquals(1, sheets.size)
        assertEquals("销售数据", sheets[0].name)
        val table = sheets[0].table
        assertEquals(listOf("门店", "销售额", "2023-01-01"), table.columns, "日期格以 date-value 作表头值")
        assertEquals(listOf("华东", "1200.5", ""), table.rows[0], "数字取 office:value（非本地化显示文本）；重复空格不展开")
        assertEquals(listOf("华南", "800", "2023-03-15"), table.rows[1])
        assertEquals(com.stragent.core.csv.ColumnType.NUMBER, table.columnTypes[1])
    }

    @Test
    fun `parseFlatOds——单文件 flat XML 同构解析`() {
        val sheets = parseFlatOds(ByteArrayInputStream(odsContentXml().toByteArray(Charsets.UTF_8)))
        assertEquals("销售数据", sheets[0].name)
        assertEquals(2, sheets[0].table.rows.size)
    }

    @Test
    fun `parseDocxTables——取行数最多的表、单元格跨 run 拼接`() {
        val document = """<?xml version="1.0" encoding="UTF-8"?>
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
          <w:body>
            <w:tbl>
              <w:tr><w:tc><w:p><w:r><w:t>名称</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>金额</w:t></w:r></w:p></w:tc></w:tr>
            </w:tbl>
            <w:p><w:r><w:t>中间的段落文字（不是表格）</w:t></w:r></w:p>
            <w:tbl>
              <w:tr><w:tc><w:p><w:r><w:t>门店</w:t></w:r><w:r><w:t>分部</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>销售额</w:t></w:r></w:p></w:tc></w:tr>
              <w:tr><w:tc><w:p><w:r><w:t>华东</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>1200.5</w:t></w:r></w:p></w:tc></w:tr>
              <w:tr><w:tc><w:p><w:r><w:t>华南</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>800</w:t></w:r></w:p></w:tc></w:tr>
            </w:tbl>
          </w:body>
        </w:document>"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(document.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        val table = parseDocxTables(ByteArrayInputStream(out.toByteArray()))
        // 取最大表（3 行），不是 1 行的小表；跨 run 的「门店分部」拼接
        assertEquals(listOf("门店分部", "销售额"), table.columns)
        assertEquals(listOf("华东", "1200.5"), table.rows[0])
    }

    @Test
    fun `空文档报中文错误`() {
        val err = runCatching { parseOds(ByteArrayInputStream(zipOf("content.xml", "<empty/>"))) }
            .exceptionOrNull()
        assertTrue(err is IllegalArgumentException && err.message!!.contains("没有工作表"), "实际: $err")
    }

    @Test
    fun `ods 解压总量超限时报压缩炸弹——不把内容读进内存`() {
        val bytes = zipOf("content.xml", odsContentXml())
        val err = runCatching { parseOds(ByteArrayInputStream(bytes), maxUncompressedBytes = 64) }
            .exceptionOrNull()
        assertNotNull(err, "超限应失败而不是继续解析")
        val chain = generateSequence(err) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
        assertTrue(chain.contains("压缩炸弹"), "错误链应说明解压预算：$chain")
        // 默认预算下同一份文件仍能正常解析
        assertEquals(1, parseOds(ByteArrayInputStream(bytes)).size)
    }

    @Test
    fun `docx 解压总量超限时报压缩炸弹`() {
        val document = """<?xml version="1.0" encoding="UTF-8"?>
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
          <w:body><w:tbl>
            <w:tr><w:tc><w:p><w:r><w:t>名称</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>金额</w:t></w:r></w:p></w:tc></w:tr>
            <w:tr><w:tc><w:p><w:r><w:t>华东</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>1200</w:t></w:r></w:p></w:tc></w:tr>
          </w:tbl></w:body>
        </w:document>"""
        val bytes = zipOf("word/document.xml", document)
        val err = runCatching { parseDocxTables(ByteArrayInputStream(bytes), maxUncompressedBytes = 64) }
            .exceptionOrNull()
        assertNotNull(err, "超限应失败而不是继续解析")
        val chain = generateSequence(err) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
        assertTrue(chain.contains("压缩炸弹"), "错误链应说明解压预算：$chain")
    }
}
