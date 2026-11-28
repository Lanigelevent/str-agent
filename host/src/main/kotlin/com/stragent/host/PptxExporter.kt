package com.stragent.host

import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 真 PPTX 导出器（T-169）——**模板底座 + 补丁**方案，纪律与 [DocxExporter] 同源。
 *
 * 为什么不是手写 PresentationML：PPTX 的最小包要连着 slideMaster / slideLayout / theme
 * 三层关系与 rels 链，任何一处对不上，PowerPoint / WPS 打开就是「需要修复」。本仓此前
 * 三次手写尝试全部踩坑（D4 P-221），因此改为：
 *
 *  - **底座**（入库资源 `pptx/template.pptx`）——由 `scripts/make-pptx-template.py` 用
 *    python-pptx 1.0.2 生成并逐字节对拍（改模板的唯一入口是 `--write`）；
 *  - **Kotlin 端只做三处补丁**：`[Content_Types].xml`（加 slide 覆盖项与 png 默认类型）、
 *    `ppt/_rels/presentation.xml.rels`（加 slide 关系）、`ppt/presentation.xml`
 *    （加 `p:sldIdLst`），外加**追加幻灯片部件**（`ppt/slides/slideN.xml` + 各自 rels）。
 *
 * 幻灯片用**非占位符文本框**（自己的坐标与字号），因此不受底座版式继承影响。
 *
 * 内容映射（与 docx / html 同一 Markdown 子集）：
 *  - 报告标题 → 封面页；每个章节 → 一页（标题 + 正文条目）；
 *  - `##`/`###` → 小标题；`- ` → 项目符号；**Markdown 表格 → PPT 原生表格**
 *    （`graphicFrame` + `a:tbl`，用户拍板升级）；
 *  - **图表内嵌**（T-138 补强轮）：SVG → PNG（batik）→ `ppt/media` 部件 + `p:pic`；
 *  - 单页条目过多（> [MAX_LINES_PER_SLIDE]）自动续页，续页标题带「（续 N）」。
 *
 * 诚实边界：
 *  - 图表内嵌依赖**系统字体**（栅格化）→ 含图的 pptx **不再字节级确定**；
 *  - 表格是原生表格，但单元格不自动换行（超宽会溢出格框）；
 *  - 无 docProps 校正之外的元数据写入（app.xml 的幻灯片计数只作提示，PowerPoint 会重算）；
 *  - 无 PowerPoint 自动化验证（本机无 Office）——用「zip 部件齐备 + XML 良构 +
 *    python-pptx 读回」三重校验代替。
 */
object PptxExporter {

    /** 单页最多条目数（超出续页） */
    private const val MAX_LINES_PER_SLIDE = 14

    /** 幻灯片尺寸（16:9，EMU）——与底座 `scripts/make-pptx-template.py` 的宽高一致 */
    private const val SLIDE_WIDTH = 12192000
    private const val SLIDE_HEIGHT = 6858000

    private const val XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
    private const val FIXED_ENTRY_TIME = 315_532_800_000L

    private const val NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val NS_P = "http://schemas.openxmlformats.org/presentationml/2006/main"

    private const val SLIDE_REL_TYPE =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide"
    private const val SLIDE_LAYOUT_REL_TYPE =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout"
    private const val IMAGE_REL_TYPE =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"
    private const val SLIDE_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.presentationml.slide+xml"

    /** 幻灯片内边距与正文区（EMU） */
    private const val MARGIN_X = 685800
    private const val CONTENT_WIDTH = 10820400
    private const val TITLE_Y = 457200
    private const val TITLE_HEIGHT = 1143000
    private const val BODY_Y = 1752600
    private const val BODY_HEIGHT = 4572000
    private const val BODY_HEIGHT_WITH_BLOCKS = 2600000

    data class ExportSection(
        val title: String,
        val content: String,
        /** 图表配置（ECharts JSON）：pptx 内嵌为图片 */
        val chartOptions: List<JsonObject> = emptyList(),
    )

    data class ExportRequest(val title: String, val sections: List<ExportSection>)

    /** 导出到 `.pptx` 文件，返回文件句柄 */
    fun exportToFile(request: ExportRequest, outputDir: File): File {
        outputDir.mkdirs()
        // createTempFile 的前缀至少 3 字符——短标题（如「A」）补齐，避免 IllegalArgumentException
        val prefix = (sanitizeFileName(request.title) + "-").padEnd(3, '_')
        val file = File.createTempFile(prefix, ".pptx", outputDir)
        file.writeBytes(exportToBytes(request))
        return file
    }

    /** 导出为 pptx 字节流（测试与内存交付走这里） */
    fun exportToBytes(request: ExportRequest): ByteArray {
        if (request.sections.isEmpty()) {
            throw IllegalArgumentException("没有可导出的章节：sections 不能为空")  // lint:allow zh-first（用户面导出文案与错误提示，与 docx 导出器同源）
        }
        val parts = LinkedHashMap(loadTemplateParts())
        val slides = buildSlides(request)
        patchParts(parts, slides)
        return writeZip(parts)
    }

    // ---------- 底座 ----------

    /** 读取入库的模板底座（缺资源即失败——不留「空模板兜底」这种假出口） */
    private fun loadTemplateParts(): Map<String, ByteArray> {
        val stream = PptxExporter::class.java.getResourceAsStream("/pptx/template.pptx")
            ?: PptxExporter::class.java.classLoader?.getResourceAsStream("pptx/template.pptx")
            ?: throw IllegalStateException("PPTX 模板底座缺失：pptx/template.pptx（用 scripts/make-pptx-template.py --write 生成）")  // lint:allow zh-first（用户面导出文案与错误提示，与 docx 导出器同源）
        val parts = LinkedHashMap<String, ByteArray>()
        ZipInputStream(stream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                parts[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        return parts
    }

    // ---------- 幻灯片模型 ----------

    /** 一页幻灯片：标题（可空=封面页）+ 正文条目 + 表格 + 图 */
    private data class Slide(
        val title: String?,
        val lines: List<SlideLine>,
        val tables: List<ReportTables.Table> = emptyList(),
        val charts: List<ExportCharts.Embedded> = emptyList(),
    )

    /** 正文条目（决定字号与项目符号） */
    private enum class LineKind { HEADING, BULLET, BODY, NOTE }

    private data class SlideLine(val text: String, val kind: LineKind)

    private fun buildSlides(request: ExportRequest): List<Slide> {
        val slides = mutableListOf<Slide>()
        slides.add(
            Slide(
                null,
                listOf(
                    SlideLine(request.title.ifBlank { "报告" }, LineKind.HEADING),  // lint:allow zh-first（用户面导出文案与错误提示，与 docx 导出器同源）
                    SlideLine("共 ${request.sections.size} 个章节", LineKind.NOTE),  // lint:allow zh-first（用户面导出文案与错误提示，与 docx 导出器同源）
                    SlideLine("由 str-agent 导出", LineKind.NOTE),  // lint:allow zh-first（用户面导出文案与错误提示，与 docx 导出器同源）
                ),
            ),
        )
        request.sections.forEachIndexed { index, section ->
            val heading = section.title.ifBlank { "章节${index + 1}" }  // lint:allow zh-first（用户面导出文案与错误提示，与 docx 导出器同源）
            val body = sectionBody(section)
            val charts = ExportCharts.embedAll(section.chartOptions)
            val skipped = section.chartOptions.size - charts.size
            val lines = if (skipped > 0) {
                body.lines + SlideLine(
                    "（本节有 $skipped 个图表配置未嵌入 PPT：当前仅支持柱状图/折线图/饼图/散点图；完整图表见 HTML 仪表盘导出）",  // lint:allow zh-first（用户面导出文案，与 docx/pdf 同源）
                    LineKind.NOTE,
                )
            } else {
                body.lines
            }
            if (lines.isEmpty() && body.tables.isEmpty() && charts.isEmpty()) {
                slides.add(Slide(heading, emptyList()))
                return@forEachIndexed
            }
            val chunks = if (lines.isEmpty()) listOf(emptyList()) else lines.chunked(MAX_LINES_PER_SLIDE)
            chunks.forEachIndexed { chunkIndex, chunk ->
                val pageTitle = if (chunkIndex == 0) heading else "$heading（续 ${chunkIndex + 1}）"  // lint:allow zh-first（用户面导出文案与错误提示，与 docx 导出器同源）
                // 表格与图只挂在首页（续页只放文字）
                val firstPage = chunkIndex == 0
                slides.add(
                    Slide(
                        pageTitle,
                        chunk,
                        if (firstPage) body.tables else emptyList(),
                        if (firstPage) charts else emptyList(),
                    ),
                )
            }
        }
        return slides
    }

    /** 章节正文 → 文字条目 + 原生表格（Markdown 子集，与 docx 导出同一口径） */
    private data class Body(val lines: List<SlideLine>, val tables: List<ReportTables.Table>)

    private fun sectionBody(section: ExportSection): Body {
        val lines = mutableListOf<SlideLine>()
        val tables = mutableListOf<ReportTables.Table>()
        val raw = section.content.lines()
        var i = 0
        while (i < raw.size) {
            val line = raw[i].trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("|") -> {
                    val table = ReportTables.firstMarkdownTable(raw.drop(i).joinToString("\n"))
                    if (table != null) {
                        tables.add(table)
                        while (i < raw.size && raw[i].trimStart().startsWith("|")) i += 1
                        continue
                    }
                    lines.add(SlideLine(line, LineKind.BODY))
                }
                line.startsWith("### ") -> lines.add(SlideLine(line.substring(4), LineKind.HEADING))
                line.startsWith("## ") -> lines.add(SlideLine(line.substring(3), LineKind.HEADING))
                line.startsWith("- ") || line.startsWith("* ") -> lines.add(SlideLine(line.substring(2), LineKind.BULLET))
                else -> lines.add(SlideLine(line, LineKind.BODY))
            }
            i += 1
        }
        return Body(lines, tables)
    }

    // ---------- 三处补丁 ----------

    private fun patchParts(parts: MutableMap<String, ByteArray>, slides: List<Slide>) {
        val contentTypes = parts["[Content_Types].xml"]?.toString(Charsets.UTF_8)
            ?: throw IllegalStateException("模板底座损坏：缺 [Content_Types].xml")  // lint:allow zh-first（用户面导出文案与错误提示，与 docx 导出器同源）
        val presentationRels = parts["ppt/_rels/presentation.xml.rels"]?.toString(Charsets.UTF_8)
            ?: throw IllegalStateException("模板底座损坏：缺 ppt/_rels/presentation.xml.rels")  // lint:allow zh-first（用户面导出文案与错误提示，与 docx 导出器同源）
        val presentation = parts["ppt/presentation.xml"]?.toString(Charsets.UTF_8)
            ?: throw IllegalStateException("模板底座损坏：缺 ppt/presentation.xml")  // lint:allow zh-first（用户面导出文案与错误提示，与 docx 导出器同源）

        var nextRelId = maxRelId(presentationRels) + 1
        val contentTypeOverrides = StringBuilder()
        val relEntries = StringBuilder()
        val sldIdEntries = StringBuilder()
        // 内嵌图：media 部件全局共用，幻灯片 rels 各自引用
        val mediaIndex = java.util.concurrent.atomic.AtomicInteger(0)
        var hasImage = false

        slides.forEachIndexed { index, slide ->
            val number = index + 1
            val relId = "rId$nextRelId"
            nextRelId += 1
            contentTypeOverrides.append(
                "<Override PartName=\"/ppt/slides/slide$number.xml\" ContentType=\"$SLIDE_CONTENT_TYPE\"/>",
            )
            relEntries.append(
                "<Relationship Id=\"$relId\" Type=\"$SLIDE_REL_TYPE\" Target=\"slides/slide$number.xml\"/>",
            )
            sldIdEntries.append("<p:sldId id=\"${255 + number}\" r:id=\"$relId\"/>")

            val slideRels = StringBuilder()
            val imageRelIds = mutableListOf<String>()
            slide.charts.forEach { chart ->
                val mediaName = "ppt/media/image${mediaIndex.incrementAndGet()}.png"
                parts[mediaName] = chart.png
                hasImage = true
                val imageRelId = "rId${imageRelIds.size + 2}"
                imageRelIds.add(imageRelId)
                slideRels.append(
                    "<Relationship Id=\"$imageRelId\" Type=\"$IMAGE_REL_TYPE\" Target=\"../media/${mediaName.removePrefix("ppt/media/")}\"/>",
                )
            }

            parts["ppt/slides/slide$number.xml"] = slideXml(slide, imageRelIds).toByteArray(Charsets.UTF_8)
            parts["ppt/slides/_rels/slide$number.xml.rels"] = slideRelsXml(slideRels.toString()).toByteArray(Charsets.UTF_8)
        }

        val pngDefault = if (hasImage) "<Default Extension=\"png\" ContentType=\"image/png\"/>" else ""
        parts["[Content_Types].xml"] = insertBefore(
            contentTypes,
            "</Types>",
            pngDefault + contentTypeOverrides.toString(),
        ).toByteArray(Charsets.UTF_8)
        parts["ppt/_rels/presentation.xml.rels"] = insertBefore(
            presentationRels,
            "</Relationships>",
            relEntries.toString(),
        ).toByteArray(Charsets.UTF_8)
        parts["ppt/presentation.xml"] = insertSldIdList(
            presentation,
            sldIdEntries.toString(),
        ).toByteArray(Charsets.UTF_8)
    }

    /** 现有关系里的最大 rId 序号（+1 即为新关系 id，避免与底座冲突） */
    private fun maxRelId(relsXml: String): Int {
        var max = 0
        var index = relsXml.indexOf("Id=\"rId")
        while (index >= 0) {
            var digits = StringBuilder()
            var i = index + 7
            while (i < relsXml.length && relsXml[i].isDigit()) {
                digits.append(relsXml[i])
                i += 1
            }
            if (digits.isNotEmpty()) {
                val value = digits.toString().toIntOrNull() ?: 0
                if (value > max) max = value
            }
            index = relsXml.indexOf("Id=\"rId", index + 1)
        }
        return max
    }

    private fun insertBefore(xml: String, marker: String, fragment: String): String {
        val at = xml.indexOf(marker)
        if (at < 0) throw IllegalStateException("模板底座结构异常：找不到 $marker")  // lint:allow zh-first（用户面导出文案与错误提示，与 docx 导出器同源）
        return xml.substring(0, at) + fragment + xml.substring(at)
    }

    /** `p:sldIdLst` 必须排在 `p:sldMasterIdLst` 之后、`p:sldSz` 之前（CT_Presentation 顺序约束） */
    private fun insertSldIdList(xml: String, entries: String): String {
        val list = "<p:sldIdLst>$entries</p:sldIdLst>"
        val existingStart = xml.indexOf("<p:sldIdLst")
        if (existingStart >= 0) {
            val close = xml.indexOf("</p:sldIdLst>", existingStart)
            if (close < 0) {
                val selfClosing = xml.indexOf("/>", existingStart)
                if (selfClosing < 0) throw IllegalStateException("模板底座结构异常：p:sldIdLst 未闭合")  // lint:allow zh-first（用户面导出文案与错误提示，与 docx 导出器同源）
                return xml.substring(0, existingStart) + list + xml.substring(selfClosing + 2)
            }
            return xml.substring(0, existingStart) + list + xml.substring(close + "</p:sldIdLst>".length)
        }
        val masterListEnd = xml.indexOf("</p:sldMasterIdLst>")
        if (masterListEnd < 0) throw IllegalStateException("模板底座结构异常：缺 p:sldMasterIdLst")  // lint:allow zh-first（用户面导出文案与错误提示，与 docx 导出器同源）
        val at = masterListEnd + "</p:sldMasterIdLst>".length
        return xml.substring(0, at) + list + xml.substring(at)
    }

    // ---------- 幻灯片 XML ----------

    private fun slideXml(slide: Slide, imageRelIds: List<String>): String {
        val sb = StringBuilder(4096)
        sb.append(XML_DECL)
        sb.append("<p:sld xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\"><p:cSld><p:spTree>")
        sb.append("<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>")
        sb.append("<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/>")
        sb.append("<a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>")
        if (slide.title == null) {
            // 封面页：报告标题居中 + 副标题
            sb.append(textBox(2, "CoverTitle", 914400, 2286000, 10363200, 1828800, listOf(SlideLine(slide.lines.firstOrNull()?.text ?: "报告", LineKind.HEADING)), 4400, true, "center"))  // lint:allow zh-first（用户面导出文案与错误提示，与 docx 导出器同源）
            val notes = slide.lines.drop(1).map { SlideLine(it.text, LineKind.NOTE) }
            sb.append(textBox(3, "CoverSubtitle", 914400, 4343400, 10363200, 914400, notes, 1800, false, "center"))
        } else {
            val hasBlocks = slide.tables.isNotEmpty() || slide.charts.isNotEmpty()
            val bodyHeight = if (hasBlocks) BODY_HEIGHT_WITH_BLOCKS else BODY_HEIGHT
            sb.append(textBox(2, "SlideTitle", MARGIN_X, TITLE_Y, CONTENT_WIDTH, TITLE_HEIGHT, listOf(SlideLine(slide.title, LineKind.HEADING)), 2800, true, "left"))
            sb.append(textBox(3, "SlideBody", MARGIN_X, BODY_Y, CONTENT_WIDTH, bodyHeight, slide.lines, 1800, false, "left"))
            // 表格与图：正文下方顺排
            var y = BODY_Y + bodyHeight + 120000
            var shapeId = 4
            slide.tables.forEach { table ->
                val height = tableRowHeight(table)
                sb.append(tableFrame(shapeId++, table, y, height))
                y += height + 120000
            }
            slide.charts.forEachIndexed { index, chart ->
                val aspect = chart.height.toDouble() / chart.width.coerceAtLeast(1)
                var cx = CONTENT_WIDTH
                var cy = (cx * aspect).toInt()
                if (cy > 2200000) {
                    cy = 2200000
                    cx = (cy / aspect).toInt()
                }
                sb.append(picture(shapeId++, chart, imageRelIds[index], y, cx, cy))
                y += cy + 120000
            }
        }
        sb.append("</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>")
        return sb.toString()
    }

    /** 幻灯片部件的 rels：指向底座的第一套版式 + 每张内嵌图 */
    private fun slideRelsXml(extra: String): String = XML_DECL +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"$SLIDE_LAYOUT_REL_TYPE\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
        extra +
        "</Relationships>"

    private fun textBox(
        id: Int,
        name: String,
        x: Int,
        y: Int,
        cx: Int,
        cy: Int,
        lines: List<SlideLine>,
        baseSize: Int,
        bold: Boolean,
        align: String,
    ): String {
        val sb = StringBuilder(1024)
        sb.append("<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"$name\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>")
        sb.append("<p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>")
        sb.append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>")
        sb.append("<p:txBody><a:bodyPr wrap=\"square\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\" rtlCol=\"0\">")
        sb.append("<a:normAutofit/></a:bodyPr><a:lstStyle/>")
        lines.forEach { line -> sb.append(paragraph(line, baseSize, bold, align)) }
        sb.append("</p:txBody></p:sp>")
        return sb.toString()
    }

    private fun paragraph(line: SlideLine, baseSize: Int, bold: Boolean, align: String): String {
        val (size, isBold, bullet) = when (line.kind) {
            LineKind.HEADING -> Triple(baseSize, true, false)
            LineKind.BULLET -> Triple(baseSize, false, true)
            LineKind.BODY -> Triple(baseSize, false, false)
            LineKind.NOTE -> Triple((baseSize - 2).coerceAtLeast(1200), false, false)
        }
        val pPr = StringBuilder("<a:pPr")
        if (bullet) pPr.append(" marL=\"285750\" indent=\"-285750\"")
        pPr.append(" algn=\"$align\">")
        if (bullet) pPr.append("<a:buFont typeface=\"Arial\"/><a:buChar char=\"•\"/>")
        else pPr.append("<a:buNone/>")
        pPr.append("</a:pPr>")
        return "<a:p>$pPr" +
            "<a:r><a:rPr lang=\"zh-CN\" altLang=\"en-US\" sz=\"$size\"${if (isBold) " b=\"1\"" else ""} dirty=\"0\">" +
            "<a:solidFill><a:srgbClr val=\"1F2933\"/></a:solidFill>" +
            "<a:latin typeface=\"+mn-lt\"/><a:ea typeface=\"+mn-ea\"/><a:cs typeface=\"+mn-cs\"/>" +
            "</a:rPr><a:t>${xmlEsc(line.text)}</a:t></a:r></a:p>"
    }

    // ---------- 原生表格（graphicFrame + a:tbl） ----------

    /** 表格总高：每行 0.3 英寸（285,750 EMU） */
    private fun tableRowHeight(table: ReportTables.Table): Int =
        (table.rows.size + 1) * 285750

    /** 原生表格：表头深蓝底白字，正文深字；不依赖表样式（缺样式也不会不可读） */
    private fun tableFrame(id: Int, table: ReportTables.Table, y: Int, height: Int): String {
        val cols = table.headers.size.coerceAtLeast(1)
        val colWidth = CONTENT_WIDTH / cols
        val sb = StringBuilder(1024)
        sb.append("<p:graphicFrame><p:nvGraphicFramePr>")
        sb.append("<p:cNvPr id=\"$id\" name=\"表格 ${id}\"/><p:cNvGraphicFramePr><a:graphicFrameLocks noGrp=\"1\"/></p:cNvGraphicFramePr><p:nvPr/>")  // lint:allow zh-first（图形名（对象名），与 docx/pdf 同源）
        sb.append("</p:nvGraphicFramePr>")
        sb.append("<p:xfrm><a:off x=\"$MARGIN_X\" y=\"$y\"/><a:ext cx=\"$CONTENT_WIDTH\" cy=\"$height\"/></p:xfrm>")
        sb.append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/table\">")
        sb.append("<a:tbl><a:tblPr firstRow=\"1\" bandRow=\"1\"/><a:tblGrid>")
        repeat(cols) { sb.append("<a:gridCol w=\"$colWidth\"/>") }
        sb.append("</a:tblGrid>")
        sb.append(tableRow(table.headers, colWidth, header = true))
        table.rows.forEach { row -> sb.append(tableRow(row, colWidth, header = false)) }
        sb.append("</a:tbl></a:graphicData></a:graphic></p:graphicFrame>")
        return sb.toString()
    }

    private fun tableRow(cells: List<String>, colWidth: Int, header: Boolean): String {
        val sb = StringBuilder(256)
        sb.append("<a:tr h=\"285750\">")
        cells.forEach { cell ->
            sb.append("<a:tc>")
            if (header) sb.append("<a:tcPr><a:solidFill><a:srgbClr val=\"4E79A7\"/></a:solidFill></a:tcPr>")
            sb.append("<a:txBody><a:bodyPr/><a:lstStyle/><a:p>")
            sb.append("<a:r><a:rPr lang=\"zh-CN\" altLang=\"en-US\" sz=\"1000\"${if (header) " b=\"1\"" else ""}>")
            sb.append("<a:solidFill><a:srgbClr val=\"${if (header) "FFFFFF" else "1F2933"}\"/></a:solidFill>")
            sb.append("<a:latin typeface=\"+mn-lt\"/><a:ea typeface=\"+mn-ea\"/></a:rPr>")
            sb.append("<a:t>${xmlEsc(cell)}</a:t></a:r></a:p></a:txBody>")
            sb.append("</a:tc>")
        }
        sb.append("</a:tr>")
        return sb.toString()
    }

    // ---------- 内嵌图（p:pic） ----------

    private fun picture(
        id: Int,
        chart: ExportCharts.Embedded,
        relId: String,
        y: Int,
        cx: Int,
        cy: Int,
    ): String = "<p:pic><p:nvPicPr>" +
        "<p:cNvPr id=\"$id\" name=\"图表 ${id}\"/><p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/>" +  // lint:allow zh-first（图形名（对象名），与 docx/pdf 同源）
        "</p:nvPicPr>" +
        "<p:blipFill><a:blip r:embed=\"$relId\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>" +
        "<p:spPr><a:xfrm><a:off x=\"$MARGIN_X\" y=\"$y\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
        "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>"

    // ---------- 输出 ----------

    /** 确定性 ZIP：条目顺序 = 底座顺序 + 新增部件顺序，条目时间固定 */
    private fun writeZip(parts: Map<String, ByteArray>): ByteArray {
        val buffer = ByteArrayOutputStream(64 * 1024)
        ZipOutputStream(buffer).use { zip ->
            parts.forEach { (name, bytes) ->
                val entry = ZipEntry(name)
                entry.time = FIXED_ENTRY_TIME
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    private fun sanitizeFileName(name: String): String {
        val sb = StringBuilder(name.length)
        for (ch in name) {
            val keep = ch.code in 0x4E00..0x9FA5 || ch in 'a'..'z' || ch in 'A'..'Z' ||
                ch in '0'..'9' || ch == '_' || ch == '-'
            sb.append(if (keep) ch else '_')
            if (sb.length >= 50) break
        }
        val cleaned = sb.toString().trim('_')
        return cleaned.ifBlank { "export" }
    }

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
}
