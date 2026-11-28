package com.stragent.host

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 真 DOCX 导出器（T-167）—— OOXML 零依赖实现，纪律与 [XlsxExporter] 同源：
 * ZIP + XML 手写、确定性字节（ZIP 条目时间固定）、共享 [ReportTables] 表格抽取。
 *
 * 包结构（ECMA-376 WordprocessingML 最小包）：
 *   [Content_Types].xml / _rels/.rels / word/document.xml / word/styles.xml /
 *   word/_rels/document.xml.rels
 *
 * 内容映射（与 HTML 仪表盘同一 Markdown 子集）：
 *  - 报告标题 → Title 段落；章节标题 → Heading1；正文 `##`/`###` → Heading1/2；
 *  - Markdown 表格 → 真 w:tbl（表头加粗底色）；`- ` 行 → 「• 」段落；
 *  - chartOptions 不嵌入（docx 嵌图需 SVG 栅格化依赖，单独决策轮）——章节末尾如实
 *    注明「本节有 N 个图表配置，未嵌入文档（图表见 HTML 仪表盘导出）」，不假装已嵌入。
 *
 * 边界（诚实）：无 docProps 元数据（Word/WPS/LibreOffice 均可打开）；行内 Markdown
 * 标记（粗体/斜体）按纯文本处理，与 HTML 导出同口径。
 */
object DocxExporter {

    data class ExportSection(
        val title: String,
        val content: String,
        /** 图表配置（ECharts JSON）：docx 不嵌入，仅用于如实注明数量 */
        val chartOptions: List<kotlinx.serialization.json.JsonObject> = emptyList(),
    )

    data class ExportRequest(val title: String, val sections: List<ExportSection>)

    /** 导出到 `.docx` 文件，返回文件句柄 */
    fun exportToFile(request: ExportRequest, outputDir: File): File {
        outputDir.mkdirs()
        // createTempFile 的前缀至少 3 字符——短标题（如「A」）补齐，避免 IllegalArgumentException
        val prefix = (sanitizeFileName(request.title) + "-").padEnd(3, '_')
        val file = File.createTempFile(prefix, ".docx", outputDir)
        file.writeBytes(exportToBytes(request))
        return file
    }

    /** 导出为 docx 字节流（测试与内存交付走这里） */
    fun exportToBytes(request: ExportRequest): ByteArray {
        if (request.sections.isEmpty()) {
            throw IllegalArgumentException("没有可导出的章节：sections 不能为空")  // lint:allow zh-first（用户面导出文案与错误提示，与 xlsx 导出器同源）
        }
        // T-138 补强轮：图表**内嵌**（SVG → PNG → word/media 部件）
        val media = LinkedHashMap<String, ByteArray>()
        val imageRels = LinkedHashMap<String, String>()
        val document = documentXml(request, media, imageRels)
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            writeEntry(zip, "[Content_Types].xml", contentTypes(media.isNotEmpty()))
            writeEntry(zip, "_rels/.rels", ROOT_RELS)
            writeEntry(zip, "word/_rels/document.xml.rels", docRels(imageRels))
            writeEntry(zip, "word/styles.xml", STYLES)
            writeEntry(zip, "word/document.xml", document)
            media.forEach { (name, bytes) -> writeEntry(zip, name, bytes) }
        }
        return buffer.toByteArray()
    }

    // ---------- 内嵌图（word/media） ----------

    /** 图片内容类型（有图才补 png 默认类型） */
    private fun contentTypes(withPng: Boolean): String =
        if (!withPng) CONTENT_TYPES else CONTENT_TYPES.replace(
            "</Types>",
            "<Default Extension=\"png\" ContentType=\"image/png\"/></Types>",
        )

    /** 文档关系：样式 + 每张图一条 image 关系 */
    private fun docRels(imageRels: Map<String, String>): String {
        if (imageRels.isEmpty()) return DOC_RELS
        val extra = imageRels.entries.joinToString("") { (id, target) ->
            "<Relationship Id=\"$id\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"$target\"/>"
        }
        return DOC_RELS.replace("</Relationships>", "$extra</Relationships>")
    }

    /** 图注句（渲染不了的配置才说） */
    private fun chartNote(skipped: Int): String =
        "（本节有 $skipped 个图表配置未嵌入文档：当前仅支持柱状图/折线图/饼图/散点图；完整图表见 HTML 仪表盘导出）"  // lint:allow zh-first（用户面导出文案，与 docx/pdf 同源）

    /**
     * 内嵌图 → Word 的 `w:drawing`（inline 图形 + blip 引用）。
     *
     * EMU：1 像素 = 9525 EMU（96 DPI）；图宽固定 6 英寸（5,715,000 EMU），高按原比例。
     */
    private fun chartDrawing(relId: String, widthEmu: Int, heightEmu: Int, docPrId: Int, name: String): String =
        "<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:drawing>" +
            "<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
            "<wp:extent cx=\"$widthEmu\" cy=\"$heightEmu\"/>" +
            "<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/>" +
            "<wp:docPr id=\"$docPrId\" name=\"$name\"/>" +
            "<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>" +
            "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:pic><pic:nvPicPr><pic:cNvPr id=\"0\" name=\"$name.png\"/><pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip r:embed=\"$relId\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$widthEmu\" cy=\"$heightEmu\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>" +
            "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"

    // ---------- document.xml ----------

    private fun documentXml(
        request: ExportRequest,
        media: MutableMap<String, ByteArray>,
        imageRels: MutableMap<String, String>,
    ): String {
        val sb = StringBuilder(16 * 1024)
        sb.append(XML_DECL)
        sb.append(
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
                "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" " +
                "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
                "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>",
        )
        sb.append(paragraph("Title", listOf(run(request.title, bold = true, sizeHalfPoints = 56)), centered = true))
        var docPrId = 1
        request.sections.forEachIndexed { index, section ->
            val heading = section.title.ifBlank { "章节${index + 1}" }  // lint:allow zh-first（用户面导出文案与错误提示，与 xlsx 导出器同源）
            sb.append(paragraph("Heading1", listOf(run(heading, bold = true))))
            sb.append(sectionBody(section))
            // T-138：内嵌图（每张图一个部件 + 一条关系 + 一个 drawing 段落）
            val charts = ExportCharts.embedAll(section.chartOptions)
            charts.forEach { chart ->
                val mediaName = "word/media/chart${media.size + 1}.png"
                media[mediaName] = chart.png
                val relId = "rId${imageRels.size + 2}"
                imageRels[relId] = mediaName.removePrefix("word/")
                val heightEmu = (CHART_WIDTH_EMU.toLong() * chart.height / chart.width.coerceAtLeast(1)).toInt()
                sb.append(chartDrawing(relId, CHART_WIDTH_EMU, heightEmu, docPrId++, "图表 ${docPrId}"))  // lint:allow zh-first（图形名（对象名），与 docx/pdf 同源）
            }
            val skipped = section.chartOptions.size - charts.size
            if (skipped > 0) sb.append(paragraph(null, listOf(run(chartNote(skipped), italic = true))))
        }
        sb.append("</w:body></w:document>")
        return sb.toString()
    }

    /** 章节正文：Markdown 子集 → 段落/表格（表格行一次性消费整块） */
    private fun sectionBody(section: ExportSection): String {
        val sb = StringBuilder()
        val lines = section.content.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.trimStart().startsWith("|") -> {
                    val table = ReportTables.firstMarkdownTable(lines.drop(i).joinToString("\n"))
                    if (table != null) {
                        sb.append(wordTable(table))
                        while (i < lines.size && lines[i].trimStart().startsWith("|")) i += 1
                        continue
                    }
                    sb.append(paragraph(null, listOf(run(line))))
                }
                line.startsWith("### ") -> sb.append(paragraph("Heading2", listOf(run(line.substring(4), bold = true))))
                line.startsWith("## ") -> sb.append(paragraph("Heading1", listOf(run(line.substring(3), bold = true))))
                line.startsWith("- ") -> sb.append(paragraph(null, listOf(run("• " + line.substring(2)))))
                line.isBlank() -> sb.append(paragraph(null, emptyList()))
                else -> sb.append(paragraph(null, listOf(run(line))))
            }
            i += 1
        }
        if (section.chartOptions.isNotEmpty()) {
            val charts = ExportCharts.embedAll(section.chartOptions)
            val skipped = section.chartOptions.size - charts.size
            if (skipped > 0) sb.append(paragraph(null, listOf(run(chartNote(skipped), italic = true))))
        }
        return sb.toString()
    }

    // ---------- 段落与表格 XML ----------

    private fun paragraph(style: String?, runs: List<String>, centered: Boolean = false): String {
        val pPr = "<w:pPr>" +
            (style?.let { "<w:pStyle w:val=\"$it\"/>" } ?: "") +
            (if (centered) "<w:jc w:val=\"center\"/>" else "") +
            "</w:pPr>"
        return "<w:p>$pPr${runs.joinToString("")}</w:p>"
    }

    private fun run(text: String, bold: Boolean = false, italic: Boolean = false, sizeHalfPoints: Int? = null): String {
        val rPr = "<w:rPr>" +
            (if (bold) "<w:b/>" else "") +
            (if (italic) "<w:i/>" else "") +
            (sizeHalfPoints?.let { "<w:sz w:val=\"$it\"/>" } ?: "") +
            "</w:rPr>"
        return "<w:r>$rPr<w:t xml:space=\"preserve\">${xmlEsc(text)}</w:t></w:r>"
    }

    /** Markdown 表格 → w:tbl：等宽列网格 + 表头加粗底色行 + 数据行 */
    private fun wordTable(table: ReportTables.Table): String {
        val cols = table.headers.size.coerceAtLeast(1)
        val colWidth = 9_360_000 / cols
        val grid = (1..cols).joinToString("") { "<w:gridCol w:w=\"$colWidth\"/>" }
        val headerRow = tableRow(table.headers, header = true)
        val bodyRows = table.rows.joinToString("") { tableRow(it, header = false) }
        return "<w:tbl>" +
            "<w:tblPr><w:tblBorders>" +
            listOf("top", "left", "bottom", "right", "insideH", "insideV").joinToString("") { edge ->
                "<w:$edge w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"D9D9D9\"/>"
            } +
            "</w:tblBorders></w:tblPr>" +
            "<w:tblGrid>$grid</w:tblGrid>" +
            headerRow + bodyRows +
            "</w:tbl>"
    }

    private fun tableRow(cells: List<String>, header: Boolean): String {
        val shading = if (header) "<w:shd w:val=\"clear\" w:fill=\"4E79A7\"/>" else ""
        return "<w:tr>" + cells.joinToString("") { cell ->
            val rPr = if (header) "<w:rPr><w:b/><w:color w:val=\"FFFFFF\"/></w:rPr>" else ""
            "<w:tc><w:tcPr>$shading</w:tcPr>" +
                "<w:p><w:r>$rPr<w:t xml:space=\"preserve\">${xmlEsc(cell)}</w:t></w:r></w:p>" +
                "</w:tc>"
        } + "</w:tr>"
    }

    // ---------- 包结构与静态模板 ----------

    private fun writeEntry(zip: ZipOutputStream, name: String, content: String) {
        writeEntry(zip, name, content.toByteArray(Charsets.UTF_8))
    }

    /** 二进制部件（内嵌图 PNG） */
    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name)
        entry.time = FIXED_ENTRY_TIME
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^\\u4e00-\\u9fa5a-zA-Z0-9_\\-]"), "_").take(50).ifBlank { "export" }

    private fun xmlEsc(value: String): String {
        val sb = StringBuilder(value.length + 16)
        for (ch in value) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> if (ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r') sb.append(' ') else sb.append(ch)
            }
        }
        return sb.toString()
    }

    private const val XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
    private const val FIXED_ENTRY_TIME = 315_532_800_000L

    /** 内嵌图宽度（EMU）——6 英寸（1 英寸 = 914,400 EMU） */
    private const val CHART_WIDTH_EMU = 5_715_000

    private val CONTENT_TYPES = XML_DECL +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
        "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
        "</Types>"

    private val ROOT_RELS = XML_DECL +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
        "</Relationships>"

    private val DOC_RELS = XML_DECL +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
        "</Relationships>"

    /** 样式：Title/Heading1/Heading2/Normal——Word/WPS/LibreOffice 都按标准样式名排版 */
    private val STYLES = XML_DECL +
        "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
        "<w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val=\"22\"/></w:rPr></w:rPrDefault></w:docDefaults>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Heading2\"><w:name w:val=\"heading 2\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/></w:style>" +
        "</w:styles>"
}
