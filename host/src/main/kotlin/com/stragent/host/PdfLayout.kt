package com.stragent.host

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.PDPageContentStream

/**
 * PDF 排版游标（T-167）：A4 单列流式排版。
 * 每页一个 [PDPageContentStream]；y 游标触底即翻页；表格跨页续排并重复表头。
 */
internal class PdfLayout(
    private val doc: PDDocument,
    private val font: PDFont,
) {
    private var page: PDPage? = null
    private var cs: PDPageContentStream? = null
    private var y = 0f

    private fun newPage() {
        cs?.close()
        page = PDPage(PDRectangle.A4).also { doc.addPage(it) }
        cs = PDPageContentStream(doc, page!!, PDPageContentStream.AppendMode.OVERWRITE, true, true)
        y = PAGE_H - MARGIN
    }

    fun finish() {
        cs?.close()
        cs = null
    }

    // ---------- 文本基元 ----------

    /** 大标题 */
    fun title(text: String) {
        if (page == null) newPage()
        drawWrapped(text, TITLE_SIZE)
        y -= 14f
    }

    /** 章节标题 */
    fun heading(text: String) {
        if (page == null) newPage()
        y -= 4f
        drawWrapped(text, HEADING_SIZE)
        y -= 6f
    }

    /** 排一节正文：Markdown 子集 → 段落/子标题/表格/图表 */
    fun sectionContent(section: PdfExporter.ExportSection) {
        if (page == null) newPage()
        val lines = section.content.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.trimStart().startsWith("|") -> {
                    val table = ReportTables.firstMarkdownTable(lines.drop(i).joinToString("\n"))
                    if (table != null) {
                        tableBlock(table)
                        while (i < lines.size && lines[i].trimStart().startsWith("|")) i += 1
                        continue
                    }
                    paragraphLine(line)
                }
                line.startsWith("### ") -> {
                    y -= 2f
                    drawWrapped(line.substring(4), HEADING_SIZE - 2f)
                    y -= 4f
                }
                line.startsWith("## ") -> {
                    y -= 2f
                    drawWrapped(line.substring(3), HEADING_SIZE)
                    y -= 4f
                }
                line.startsWith("- ") -> paragraphLine("• " + line.substring(2))
                line.isBlank() -> y -= BODY_SIZE * 0.8f
                else -> paragraphLine(line)
            }
            i += 1
        }
        // T-138 补强轮：图表**内嵌**（SVG → PNG → PDFBox 位图），
        // 渲染不了的配置才如实注明（不假装画出来）。
        val charts = ExportCharts.embedAll(section.chartOptions)
        charts.forEach { chart -> chartImage(chart) }
        val skipped = section.chartOptions.size - charts.size
        if (skipped > 0) {
            paragraphLine("（本节有 $skipped 个图表配置未嵌入 PDF：当前仅支持柱状图/折线图/饼图/散点图；完整图表见 HTML 仪表盘导出）")  // lint:allow zh-first（用户面导出文案，与 docx/pptx 同源）
        }
        y -= 6f
    }

    /** 画一张内嵌图：按内容宽度等比缩放，超高则限高；放不下就翻页 */
    private fun chartImage(chart: ExportCharts.Embedded) {
        if (page == null) newPage()
        val aspect = chart.height.toFloat() / chart.width.toFloat().coerceAtLeast(1f)
        var w = CONTENT_W
        var h = w * aspect
        if (h > MAX_CHART_H) {
            h = MAX_CHART_H
            w = h / aspect
        }
        if (y - h < MARGIN) newPage()
        val image = org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject.createFromByteArray(doc, chart.png, "chart")
        cs!!.drawImage(image, MARGIN, y - h, w, h)
        y -= h + 8f
    }

    private fun paragraphLine(text: String) {
        if (page == null) newPage()
        drawWrapped(text, BODY_SIZE)
        y -= 3f
    }

    private fun drawWrapped(text: String, size: Float) {
        if (page == null) newPage()
        for (chunk in wrap(text, size, CONTENT_W)) {
            if (y - size * LEADING < MARGIN) newPage()
            val stream = cs!!
            stream.beginText()
            stream.setFont(font, size)
            stream.newLineAtOffset(MARGIN, y - size)
            stream.showText(chunk)
            stream.endText()
            y -= size * LEADING
        }
        y -= 2f
    }

    /** 按字宽换行（CJK 逐字可断行；缺字形宽 0.6*size 兜底） */
    private fun wrap(text: String, size: Float, maxWidth: Float): List<String> {
        val sb = StringBuilder()
        val out = mutableListOf<String>()
        var w = 0f
        for (ch in text) {
            val cw = try {
                font.getStringWidth(ch.toString()) / 1000f * size
            } catch (e: IllegalArgumentException) {
                0f
            }.let { if (it <= 0f) 0.6f * size else it }
            if (w + cw > maxWidth && sb.isNotEmpty()) {
                out.add(sb.toString())
                sb.setLength(0)
                w = 0f
            }
            sb.append(ch)
            w += cw
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    // ---------- 网格表格 ----------

    /** 网格表格：列宽均分、单元格换行、行高按最高格；跨页续排并重复表头 */
    private fun tableBlock(table: ReportTables.Table) {
        val cols = table.headers.size.coerceAtLeast(1)
        val colW = CONTENT_W / cols
        fun cellLines(cell: String) = wrap(cell.ifEmpty { " " }, TABLE_SIZE, colW - 2 * CELL_PAD)
        val headerCells = table.headers.map { cellLines(it) }
        y -= 2f
        var pass = 0
        while (pass < table.rows.size + 1) {
            val isHeaderPass = pass == 0
            val cells = if (isHeaderPass) headerCells else {
                val raw = table.rows[pass - 1]
                raw.map { cellLines(ReportTables.clampCell(it)) }
            }
            val h = rowHeight(cells)
            if (y - h < MARGIN) {
                newPage()
                if (!isHeaderPass) {
                    // 跨页续排：重复表头
                    y -= rowHeight(headerCells)
                    drawRow(headerCells, rowHeight(headerCells), header = true)
                }
            }
            drawRow(cells, h, header = isHeaderPass)
            pass += 1
        }
        y -= 6f
    }

    private fun rowHeight(cells: List<List<String>>): Float =
        (cells.maxOfOrNull { it.size } ?: 1) * TABLE_SIZE * LEADING + 2 * CELL_PAD

    /** 画一行：网格线 + 单元格文本（单元格内多行文本依次下移） */
    private fun drawRow(cells: List<List<String>>, h: Float, header: Boolean) {
        if (page == null) newPage()
        val stream = cs!!
        val top = y
        val bottom = y - h
        val cols = cells.size
        val colW = CONTENT_W / cols.coerceAtLeast(1)
        stream.setStrokingColor(GRID_GRAY)
        // 竖线（含两端）+ 顶/底横线
        for (i in 0..cols) {
            val x = MARGIN + i * colW
            stream.moveTo(x, top)
            stream.lineTo(x, bottom)
        }
        stream.moveTo(MARGIN, top)
        stream.lineTo(MARGIN + CONTENT_W, top)
        stream.moveTo(MARGIN, bottom)
        stream.lineTo(MARGIN + CONTENT_W, bottom)
        stream.stroke()
        // 单元格文本
        var ty = top - CELL_PAD - TABLE_SIZE
        cells.forEachIndexed { ci, lines ->
            var ly = ty
            for (line in lines) {
                stream.beginText()
                stream.setFont(font, TABLE_SIZE)
                stream.newLineAtOffset(MARGIN + ci * colW + CELL_PAD, ly)
                stream.showText(line)
                stream.endText()
                ly -= TABLE_SIZE * LEADING
            }
        }
        y = bottom
    }

    companion object {
        internal const val PAGE_H = 842f
        internal const val MARGIN = 56f
        private const val PAGE_W_TOTAL = 595f
        internal const val CONTENT_W = PAGE_W_TOTAL - 2 * MARGIN
        private const val TITLE_SIZE = 20f
        private const val HEADING_SIZE = 15f
        private const val BODY_SIZE = 10.5f
        private const val TABLE_SIZE = 9f
        private const val CELL_PAD = 3f
        private const val LEADING = 1.45f
        private const val GRID_GRAY = 0.85f

        /** 内嵌图最大高度（点）——超出的按比例缩，避免一张图占满整页 */
        private const val MAX_CHART_H = 320f
    }
}
