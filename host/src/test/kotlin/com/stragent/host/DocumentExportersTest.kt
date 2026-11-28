package com.stragent.host

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * T-167/T-168 · 文档导出（docx/pdf）与交互图表测试。
 *
 * 覆盖：docx 包结构与 Markdown 表格映射 + 确定性字节；PDF 往返
 * （PDFBox 读回提取文本，含中文与表格单元）；交互层（data-* 属性 + 内嵌脚本 +
 * 无外部引用）；ReportTables 与 xlsx 同口径。
 *
 * 边界（诚实）：无 PowerPoint/Word 自动化验证（本地无 Office）——docx 用「zip 部件
 * + XML 良构」结构校验，pdf 用 PDFBox 自家往返；Windows CI 上 msyh 字体存在。
 */
class DocumentExportersTest {

    private val sections = listOf(
        DocxExporter.ExportSection(
            title = "区域销售",
            content = "华东地区销售额最高。\n\n|地区|销售额|\n|---|---|\n|华东|1000|\n|华北|300|\n\n- 要点一\n- 要点二",
            chartOptions = listOf(buildJsonObject { put("dummy", true) }),
        ),
        DocxExporter.ExportSection(title = "结论", content = "整体向好了。"),
    )

    private val title = "季度销售分析"

    // ---------------- docx ----------------

    @Test
    fun `docx 包结构齐备，Markdown 表格映射为 w tbl，确定性字节`() {
        val bytes = DocxExporter.exportToBytes(DocxExporter.ExportRequest(title, sections))
        val names = entryNames(bytes)
        listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml", "word/_rels/document.xml.rels")
            .forEach { part -> assertTrue(names.contains(part), "缺部件 $part：$names") }
        val doc = String(partBytes(bytes, "word/document.xml"), Charsets.UTF_8)
        assertTrue(doc.contains("季度销售分析"), "标题在文档：$doc")
        assertTrue(doc.contains("Heading1") && doc.contains("区域销售"), "章节标题")
        assertTrue(doc.contains("<w:tbl>"), "Markdown 表格 → 真 Word 表格")
        assertTrue(doc.contains("华东") && doc.contains("1000"), "表格单元进文档")
        assertTrue(doc.contains("• 要点一"), "列表行映射 bullet 段落")
        assertTrue(doc.contains("未嵌入文档"), "渲染不了的图表如实注明")
        // 确定性：同输入两次导出字节一致
        val again = DocxExporter.exportToBytes(DocxExporter.ExportRequest(title, sections))
        assertTrue(bytes.contentEquals(again), "docx 导出必须字节级确定")
    }

    @Test
    fun `docx XML 良构——document 与 styles 都可被 XML 解析器解析`() {
        val bytes = DocxExporter.exportToBytes(DocxExporter.ExportRequest(title, sections))
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        listOf("word/document.xml", "word/styles.xml", "[Content_Types].xml", "word/_rels/document.xml.rels").forEach { part ->
            factory.newDocumentBuilder().parse(org.xml.sax.InputSource(ByteArrayInputStream(partBytes(bytes, part))))
        }
    }

    @Test
    fun `docx 空章节拒绝`() {
        val error = assertFailsWith<IllegalArgumentException> {
            DocxExporter.exportToBytes(DocxExporter.ExportRequest(title, emptyList()))
        }
        assertTrue(error.message!!.contains("sections"), error.message)
    }

    @Test
    fun `短标题也能落盘——临时文件名前缀至少 3 字符（docx 与 pdf 同款）`() {
        val dir = java.nio.file.Files.createTempDirectory("short-title").toFile()
        val docx = DocxExporter.exportToFile(
            DocxExporter.ExportRequest("A", listOf(DocxExporter.ExportSection("章节", "正文"))),
            dir,
        )
        assertTrue(docx.isFile && docx.length() > 0, "docx：$docx")
        val pdf = PdfExporter.exportToFile(
            PdfExporter.ExportRequest("A", listOf(PdfExporter.ExportSection("章节", "正文"))),
            dir,
        )
        assertTrue(pdf.isFile && pdf.length() > 0, "pdf：$pdf")
    }

    // ---------------- pdf ----------------

    @Test
    fun `pdf 往返——PDFBox 读回提取中文标题、章节与表格单元`() {
        val bytes = PdfExporter.exportToBytes(PdfExporter.ExportRequest(title, sections.map {
            PdfExporter.ExportSection(it.title, it.content, it.chartOptions)
        }))
        val text = extractedText(bytes)
        assertTrue(text.contains("季度销售分析"), "标题：$text")
        assertTrue(text.contains("区域销售"), "章节标题：$text")
        assertTrue(text.contains("华东地区销售额最高"), "正文段落：$text")
        assertTrue(text.contains("华东") && text.contains("1000"), "表格单元：$text")
        assertTrue(text.contains("要点一"), "bullet 行：$text")
        assertTrue(text.contains("未嵌入 PDF"), "渲染不了的图表如实注明：$text")
    }

    @Test
    fun `pdf 内容确定性——两次导出提取文本一致（字节含随机文档 ID，见诚实边界）`() {
        val req = PdfExporter.ExportRequest(title, sections.map { PdfExporter.ExportSection(it.title, it.content, it.chartOptions) })
        val a = extractedText(PdfExporter.exportToBytes(req))
        val b = extractedText(PdfExporter.exportToBytes(req))
        assertEquals(a, b, "同输入提取文本必须一致")
        assertTrue(a.isNotBlank())
    }

    @Test
    fun `pdf 跨页——长正文自动分页且内容完整`() {
        val long = PdfExporter.ExportSection(
            title = "长章节",
            content = (1..60).joinToString("\n") { "第 $it 段：这里是一段比较长的正文内容，用来把 PDF 撑到多页。" },
        )
        val text = extractedText(PdfExporter.exportToBytes(PdfExporter.ExportRequest("多页测试", listOf(long))))
        assertTrue(text.contains("第 1 段") && text.contains("第 60 段"), "首尾段都在（跨页续排）：${text.length} chars")
    }

    // ---------------- 交互图表 ----------------

    @Test
    fun `交互层——data 属性与内嵌脚本就位，仍无外部引用`() {
        val html = HtmlDashboardExporter.export(HtmlDashboardExporter.ExportRequest(
            title = "交互测试",
            sections = listOf(HtmlDashboardExporter.ExportSection(
                title = "区域销售",
                content = "正文",
                chartOptions = listOf(barOption()),
            )),
        ))
        assertTrue(html.contains("data-name="), "数据形状带系列名属性")
        assertTrue(html.contains("data-v="), "数据形状带数值属性")
        assertTrue(html.contains("chart-tip"), "tooltip 元素与样式")
        assertTrue(html.contains("<script>"), "内嵌交互脚本")
        assertTrue(html.contains("data-legend="), "图例可开合标记")
        // 离线纪律：任何外部引用都不允许（script/img/link 的 src|href 指向 http）
        listOf("src=\"http", "href=\"http", "cdn.").forEach { ref ->
            assertTrue(!html.contains(ref), "不得出现外部引用 $ref")
        }
    }

    @Test
    fun `交互确定性——同输入产出同一份 HTML`() {
        val req = HtmlDashboardExporter.ExportRequest(
            title = "确定性",
            sections = listOf(HtmlDashboardExporter.ExportSection("一", "正文", listOf(barOption()))),
        )
        assertEquals(HtmlDashboardExporter.export(req), HtmlDashboardExporter.export(req))
    }

    @Test
    fun `ReportTables 与 xlsx 同口径——分隔行跳过、表头识别`() {
        val table = ReportTables.firstMarkdownTable("|a|b|\n|---|---|\n|1|2|")!!
        assertEquals(listOf("a", "b"), table.headers)
        assertEquals(listOf(listOf("1", "2")), table.rows)
        assertEquals(null, ReportTables.firstMarkdownTable("没有表格的正文"))
    }

    // ---------------- 内嵌图（T-138 补强轮） ----------------

    @Test
    fun `docx 内嵌图——media 部件 + 图片关系 + w drawing + png 内容类型`() {
        val bytes = DocxExporter.exportToBytes(
            DocxExporter.ExportRequest(
                "带图报告",
                listOf(DocxExporter.ExportSection("数据", "正文", listOf(barOption()))),
            ),
        )
        val names = entryNames(bytes)
        assertTrue(names.contains("word/media/chart1.png"), "内嵌图部件：$names")
        val doc = String(partBytes(bytes, "word/document.xml"), Charsets.UTF_8)
        assertTrue(doc.contains("<w:drawing>"), "文档含图形：$doc")
        assertTrue(doc.contains("r:embed=\"rId2\""), "图片引用关系 id：$doc")
        val rels = String(partBytes(bytes, "word/_rels/document.xml.rels"), Charsets.UTF_8)
        assertTrue(rels.contains("media/chart1.png"), "图片关系指向 media：$rels")
        val contentTypes = String(partBytes(bytes, "[Content_Types].xml"), Charsets.UTF_8)
        assertTrue(contentTypes.contains("Extension=\"png\""), "png 内容类型：$contentTypes")
        assertEquals(0x89.toByte(), partBytes(bytes, "word/media/chart1.png")[0], "PNG 签名")
    }

    @Test
    fun `pdf 内嵌图——PDFBox 读回看得见图片 XObject`() {
        val bytes = PdfExporter.exportToBytes(
            PdfExporter.ExportRequest(
                "带图报告",
                listOf(PdfExporter.ExportSection("数据", "正文", listOf(barOption()))),
            ),
        )
        org.apache.pdfbox.Loader.loadPDF(bytes).use { doc ->
            val page = doc.getPage(0)
            val resources = page.resources
            var images = 0
            for (name in resources.xObjectNames) {
                val xobject = resources.getXObject(name)
                if (xobject is org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject) images++
            }
            assertTrue(images >= 1, "PDF 首页应含内嵌图片 XObject")
        }
    }

    // ---------------- 助手 ----------------

    private fun barOption(): JsonObject = buildJsonObject {
        put("xAxis", buildJsonObject {
            put("data", JsonArray(listOf(JsonPrimitive("华东"), JsonPrimitive("华北"))))
        })
        put("series", JsonArray(listOf(
            buildJsonObject {
                put("name", "销售额")
                put("data", JsonArray(listOf(JsonPrimitive(1000), JsonPrimitive(300))))
            },
            buildJsonObject {
                put("name", "利润")
                put("data", JsonArray(listOf(JsonPrimitive(200), JsonPrimitive(80))))
            },
        )))
    }

    private fun entryNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                names.add(entry.name)
                entry = zip.nextEntry
            }
        }
        return names
    }

    private fun partBytes(bytes: ByteArray, name: String): ByteArray {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == name) return zip.readBytes()
                entry = zip.nextEntry
            }
        }
        throw AssertionError("缺部件：$name")
    }

    private fun extractedText(bytes: ByteArray): String =
        org.apache.pdfbox.Loader.loadPDF(bytes).use { doc ->
            org.apache.pdfbox.text.PDFTextStripper().getText(doc)
        }
}
