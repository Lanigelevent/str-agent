package com.stragent.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * docx 正文按文档顺序全量抽取（T-096 步骤 4）：段落/表格交替顺序、嵌套表格压平、
 * 空文档、表头全空表的解析不中断。
 */
class DocxBodyParserTest {

    private fun docxOf(document: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(document.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private val ns = """xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main""""

    private fun body(vararg elements: String) = """<?xml version="1.0" encoding="UTF-8"?>
        <w:document $ns>
          <w:body>
            ${elements.joinToString("\n")}
          </w:body>
        </w:document>"""

    private fun p(text: String) = "<w:p><w:r><w:t>$text</w:t></w:r></w:p>"

    private fun table(vararg rows: List<String>) =
        "<w:tbl>" + rows.joinToString("") { row ->
            "<w:tr>" + row.joinToString("") { cell ->
                "<w:tc><w:p><w:r><w:t>$cell</w:t></w:r></w:p></w:tc>"
            } + "</w:tr>"
        } + "</w:tbl>"

    @Test
    fun `段落与表格按文档顺序交替出现`() {
        val doc = body(
            p("第一段：背景介绍。"),
            table(listOf("门店", "销售额"), listOf("华东", "1200"), listOf("华北", "800")),
            p("第二段：分析结论。"),
            table(listOf("季度", "利润"), listOf("Q1", "300")),
        )
        val elements = parseDocxBody(ByteArrayInputStream(docxOf(doc)))
        assertEquals(4, elements.size)
        assertEquals(DocxBodyElement.Paragraph("第一段：背景介绍。"), elements[0])
        val t1 = elements[1] as DocxBodyElement.Table
        assertEquals(3, t1.rows.size)
        assertEquals(listOf("门店", "销售额"), t1.rows[0])
        assertEquals(DocxBodyElement.Paragraph("第二段：分析结论。"), elements[2])
        val t2 = elements[3] as DocxBodyElement.Table
        assertEquals(2, t2.rows.size)
    }

    @Test
    fun `单元格内多 run 跨段拼接`() {
        // 同一单元格内多个 w:r run 应拼接为一个单元格值
        val raw = """<?xml version="1.0" encoding="UTF-8"?>
            <w:document $ns>
              <w:body>
                <w:tbl>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>门</w:t></w:r><w:r><w:t>店</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>销售额</w:t></w:r></w:p></w:tc>
                  </w:tr>
                </w:tbl>
              </w:body>
            </w:document>"""
        val elements = parseDocxBody(ByteArrayInputStream(docxOf(raw)))
        val t = elements.filterIsInstance<DocxBodyElement.Table>().single()
        assertEquals("门店", t.rows[0][0])
        assertEquals("销售额", t.rows[0][1])
    }

    @Test
    fun `空段落不产出、空文档返回空列表`() {
        val doc = body(p("   "), p(""), "<w:p/>")
        val elements = parseDocxBody(ByteArrayInputStream(docxOf(doc)))
        assertEquals(0, elements.size)
    }

    @Test
    fun `嵌套表格压平进所在单元格不丢数据`() {
        val inner = table(listOf("内1", "内2"))
        val raw = """<?xml version="1.0" encoding="UTF-8"?>
            <w:document $ns>
              <w:body>
                ${p("外文前")}
                <w:tbl>
                  <w:tr>
                    <w:tc>
                      <w:p><w:r><w:t>外单元格</w:t></w:r></w:p>
                      $inner
                    </w:tc>
                    <w:tc><w:p><w:r><w:t>另一格</w:t></w:r></w:p></w:tc>
                  </w:tr>
                </w:tbl>
                ${p("外文后")}
              </w:body>
            </w:document>"""
        val elements = parseDocxBody(ByteArrayInputStream(docxOf(raw)))
        assertEquals(3, elements.size)
        assertEquals(DocxBodyElement.Paragraph("外文前"), elements[0])
        val outer = elements[1] as DocxBodyElement.Table
        assertTrue(outer.rows[0][0].contains("外单元格"), "外层单元格文字保留：${outer.rows[0][0]}")
        assertTrue(outer.rows[0][0].contains("内1") && outer.rows[0][0].contains("内2"), "嵌套表内容压平进单元格：${outer.rows[0][0]}")
        assertEquals("另一格", outer.rows[0][1])
        assertEquals(DocxBodyElement.Paragraph("外文后"), elements[2])
    }

    @Test
    fun `压缩炸弹——maxUncompressedBytes 超限时失败而不是读进内存`() {
        val doc = body(p("前文"))
        val bytes = docxOf(doc)
        assertFailsWith<Exception> {
            parseDocxBody(ByteArrayInputStream(bytes), maxUncompressedBytes = 8)
        }
    }
}
