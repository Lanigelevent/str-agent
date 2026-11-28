package com.stragent.data

import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * XXE 回归测试（T-140）——**外部实体不得被解析**。
 *
 * ## 威胁
 *
 * `data` 模块用 SAX 解析 docx / xlsx / odf 内部的 XML。SAX 在 JDK 里的默认行为是
 * **会**解析外部实体（实测：`load-external-dtd` / `external-general-entities` /
 * `external-parameter-entities` 三项默认全为 `true`），所以一份恶意文档可以：
 *  - 通过 `file://` 实体**读本地文件**（凭据、密钥、配置）；
 *  - 通过 `http://` 实体**发起 SSRF**（打内网）；
 *  - 通过实体膨胀做 **DoS**。
 *
 * 触发路径从 MCP 入口可达：`stragent_import_file` 接受客户端给的本地文件路径，
 * `.docx` → `parseDocxBody` / `parseDocxTables`，`.xlsx` → `parseXlsx`。
 *
 * ## 为什么夹具是「模板 + 运行时替换」而不是静态二进制
 *
 * 实体 URI 必须是**绝对**路径才能可靠解析。静态夹具里写死绝对路径 → 换台机器就失效；
 * 写相对路径 → 解析器可能因为没有 base URI 而**解析不出来**，
 * 于是测试**假绿**（看起来挡住了，其实只是没触发）。
 *
 * 所以：XML 模板进仓（**可 review 的文本**，不是不透明的二进制），
 * 测试在运行时把 `__XXE_ENTITY_URI__` 换成绝对 file:// URI，
 * 指向测试自己写的**哨兵文件**（内容 `XXE_CANARY_9f3a`，无害字符串）。
 *
 * ## 断言口径
 *
 * **不强求抛异常**——防护的实现方式有多种（禁 DOCTYPE / 禁外部实体 / 换解析器），
 * 唯一必须成立的是「**哨兵内容没有进到输出里**」。
 * 同时断言「要么抛异常、要么输出不含哨兵」，两者皆不满足即失败。
 */
class XxeRegressionTest {

    @Test
    fun `docx——外部实体不得被解析（哨兵内容不许进正文）`() {
        val (docx, canary) = buildMaliciousDocx()
        try {
            val outcome = runCatching {
                parseDocxBody(docx.inputStream())
            }
            assertNoLeak(outcome, canary, "parseDocxBody")
        } finally {
            docx.delete()
            canary.delete()
        }
    }

    @Test
    fun `docx 表格——外部实体不得被解析`() {
        val (docx, canary) = buildMaliciousDocx()
        try {
            val outcome = runCatching {
                parseDocxTables(docx.inputStream())
            }
            assertNoLeak(outcome, canary, "parseDocxTables")
        } finally {
            docx.delete()
            canary.delete()
        }
    }

    @Test
    fun `xlsx——外部实体不得被解析（哨兵内容不许进单元格）`() {
        val (xlsx, canary) = buildMaliciousXlsx()
        try {
            val outcome = runCatching {
                parseXlsx(xlsx.inputStream())
            }
            assertNoLeak(outcome, canary, "parseXlsx")
        } finally {
            xlsx.delete()
            canary.delete()
        }
    }

    /**
     * 统一断言：**要么抛异常，要么输出里没有哨兵**。
     *
     * 「抛了异常」也算通过——防护到位时 XML 解析器遇到外部实体引用会直接拒绝，
     * 这是合规行为；关键是**不能**把哨兵内容读出来。
     */
    private fun assertNoLeak(
        outcome: Result<Any>,
        canary: File,
        api: String,
    ) {
        val canaryText = canary.readText()
        outcome.fold(
            onSuccess = { value ->
                val rendered = value.toString()
                assertTrue(
                    !rendered.contains(canaryText),
                    "$api 把外部实体解析出来了——本地文件内容泄漏进了输出：\n$rendered",
                )
            },
            onFailure = { error ->
                // 解析器**拒绝**外部实体是期望行为——但必须是「XML 解析器拒绝」这一种，
                // 不能是别的意外（夹具没建好 / zip 结构不对 / 路径读不到…）：
                // 那种情况下测试会**假绿**（看起来挡住了，其实根本没走到 XXE 那一步）。
                assertTrue(
                    error is org.xml.sax.SAXParseException,
                    "$api 抛了非预期的异常（不是 XML 解析器拒绝外部实体）：" +
                        "${error::class.simpleName}: ${error.message}",
                )
            },
        )
    }

    // ---------------- 兼容档的另一半：合法文档仍必须能解析 ----------------

    /**
     * **不误伤**的证明：没有 DOCTYPE 的合法 OOXML 文档必须照常解析出内容。
     *
     * 兼容档（保留 DOCTYPE、只禁外部实体）比「禁一切 DOCTYPE」宽松，
     * 宽松的代价就是**必须证明它没把正常文档一起挡掉**——否则「挡住 XXE」
     * 可能只是「挡住了所有 XML」。
     *
     * 夹具在测试内自建（与恶意夹具同一套 zip 结构，只差一个 DOCTYPE），
     * 所以这条门禁**不依赖任何外部材料**，任何机器上都能跑。
     */
    @Test
    fun `合法文档——没有 DOCTYPE 的 docx 照常解析出段落与表格`() {
        val docx = File.createTempFile("ok-docx-", ".docx")
        docx.deleteOnExit()
        zip(docx) {
            entry("[Content_Types].xml", CONTENT_TYPES_XML)
            entry("_rels/.rels", RELS_XML)
            entry("word/document.xml", LEGIT_DOCUMENT_XML)
        }
        try {
            val elements = parseDocxBody(docx.inputStream())
            val paragraphs = elements.filterIsInstance<DocxBodyElement.Paragraph>().map { it.text }
            val tables = elements.filterIsInstance<DocxBodyElement.Table>()
            assertTrue(
                paragraphs.any { it.contains("正常段落") },
                "合法文档的段落必须被解析出来：$paragraphs",
            )
            val table = tables.firstOrNull()
            assertTrue(table != null, "合法文档的表格必须被解析出来")
            assertTrue(
                table!!.rows.any { row -> row.any { cell -> cell.contains("表头列") } },
                "表格内容必须被解析出来：${table.rows}",
            )
        } finally {
            docx.delete()
        }
    }

    @Test
    fun `合法文档——没有 DOCTYPE 的 xlsx 照常解析出表头与数据行`() {
        val xlsx = File.createTempFile("ok-xlsx-", ".xlsx")
        xlsx.deleteOnExit()
        zip(xlsx) {
            entry("[Content_Types].xml", CONTENT_TYPES_XML)
            entry("_rels/.rels", RELS_XML)
            entry("xl/workbook.xml", WORKBOOK_XML)
            entry("xl/_rels/workbook.xml.rels", WORKBOOK_RELS_XML)
            entry("xl/worksheets/sheet1.xml", LEGIT_SHEET_XML)
        }
        try {
            val sheets = parseXlsx(xlsx.inputStream())
            val sheet = sheets.firstOrNull()
            assertTrue(sheet != null, "合法工作簿必须被解析出来")
            val table = sheet!!.table
            assertTrue(
                table.columns.any { it.contains("表头列") },
                "表头必须被解析出来：${table.columns}",
            )
            assertTrue(
                table.rows.any { row -> row.any { cell -> cell.contains("单元格内容") } },
                "数据行必须被解析出来：${table.rows}",
            )
        } finally {
            xlsx.delete()
        }
    }

    /** 构造恶意 docx：word/document.xml 带外部实体；返回 (文件, 哨兵文件) */
    private fun buildMaliciousDocx(): Pair<File, File> {
        val canary = writeCanary()
        val xml = readTemplate("fixtures/xxe/docx-document.xml")
            .replace("__XXE_ENTITY_URI__", canary.toURI().toString())
        val docx = File.createTempFile("xxe-docx-", ".docx")
        docx.deleteOnExit()
        zip(docx) {
            entry("[Content_Types].xml", CONTENT_TYPES_XML)
            entry("_rels/.rels", RELS_XML)
            entry("word/document.xml", xml)
        }
        return docx to canary
    }

    /** 构造恶意 xlsx：xl/worksheets/sheet1.xml 带外部实体；返回 (文件, 哨兵文件) */
    private fun buildMaliciousXlsx(): Pair<File, File> {
        val canary = writeCanary()
        val xml = readTemplate("fixtures/xxe/xlsx-sheet1.xml")
            .replace("__XXE_ENTITY_URI__", canary.toURI().toString())
        val xlsx = File.createTempFile("xxe-xlsx-", ".xlsx")
        xlsx.deleteOnExit()
        zip(xlsx) {
            entry("[Content_Types].xml", CONTENT_TYPES_XML)
            entry("_rels/.rels", RELS_XML)
            entry("xl/workbook.xml", WORKBOOK_XML)
            entry("xl/_rels/workbook.xml.rels", WORKBOOK_RELS_XML)
            entry("xl/worksheets/sheet1.xml", xml)
        }
        return xlsx to canary
    }

    private fun writeCanary(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "str-agent-xxe")
        dir.mkdirs()
        val file = File(dir, "canary-${System.nanoTime()}.txt")
        file.writeText(CANARY, Charsets.UTF_8)
        file.deleteOnExit()
        return file
    }

    private fun readTemplate(path: String): String {
        val stream: InputStream = javaClass.classLoader.getResourceAsStream(path)
            ?: fail("找不到夹具模板：$path")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun zip(target: File, build: ZipBuilder.() -> Unit) {
        ZipOutputStream(target.outputStream().buffered()).use { out ->
            ZipBuilder(out).build()
        }
    }

    private class ZipBuilder(private val out: ZipOutputStream) {
        fun entry(name: String, content: String) {
            out.putNextEntry(ZipEntry(name))
            out.write(content.toByteArray(Charsets.UTF_8))
            out.closeEntry()
        }
    }

    private companion object {
        /** 哨兵内容：无害字符串，绝不指向任何真实文件 */
        const val CANARY = "XXE_CANARY_9f3a"

        const val CONTENT_TYPES_XML =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="xml" ContentType="application/xml"/>
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
</Types>"""

        const val RELS_XML =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

        const val WORKBOOK_XML =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets>
</workbook>"""

        const val WORKBOOK_RELS_XML =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
</Relationships>"""

        /** 合法文档（无 DOCTYPE）——证明兼容档不误伤 */
        const val LEGIT_DOCUMENT_XML =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>
    <w:p><w:r><w:t>正常段落内容</w:t></w:r></w:p>
    <w:tbl>
      <w:tr><w:tc><w:p><w:r><w:t>表头列</w:t></w:r></w:p></w:tc></w:tr>
      <w:tr><w:tc><w:p><w:r><w:t>单元格内容</w:t></w:r></w:p></w:tc></w:tr>
    </w:tbl>
  </w:body>
</w:document>"""

        const val LEGIT_SHEET_XML =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <sheetData>
    <row r="1">
      <c r="A1" t="inlineStr"><is><t>表头列</t></is></c>
    </row>
    <row r="2">
      <c r="A2" t="inlineStr"><is><t>单元格内容</t></is></c>
      <c r="B2"><v>42</v></c>
    </row>
  </sheetData>
</worksheet>"""
    }
}
