package com.stragent.host

/**
 * 报告 Markdown 子集的**共享解析**（T-167）——xlsx/docx/pptx/pdf 四个导出器共用一份
 * 表格抽取，避免同一份报告在四种格式里解析口径漂移（C-12 单一权威源纪律）。
 *
 * 覆盖的 Markdown 子集与 [HtmlDashboardExporter.markdownToHtml] 一致：标题、段落、
 * `|` 表格、`- ` 列表行；粗体/斜体等行内标记按纯文本处理（与现有 HTML 导出同口径）。
 */
internal object ReportTables {

    data class Table(val headers: List<String>, val rows: List<List<String>>)

    /** 取正文里第一张 Markdown 表格；没有表格返回 null（与 XlsxExporter 原口径逐字一致） */
    fun firstMarkdownTable(content: String): Table? {
        val lines = content.lines()
        val start = lines.indexOfFirst { it.trimStart().startsWith("|") }
        if (start < 0) return null
        val block = mutableListOf<List<String>>()
        var index = start
        while (index < lines.size && lines[index].trimStart().startsWith("|")) {
            block.add(splitTableRow(lines[index]))
            index += 1
        }
        if (block.isEmpty()) return null
        val headers = block.first().map { clampCell(it) }
        val bodyStart = if (block.size > 1 && isSeparatorRow(block[1])) 2 else 1
        val rows = block.drop(bodyStart).map { row -> row.map { clampCell(it) } }
        if (headers.isEmpty()) return null
        return Table(headers, rows)
    }

    /** `| a | b |` → [a, b]（不处理单元格内的转义竖线——报告表格不产生它） */
    fun splitTableRow(line: String): List<String> {
        var text = line.trim()
        if (text.startsWith("|")) text = text.substring(1)
        if (text.endsWith("|")) text = text.substring(0, text.length - 1)
        return text.split("|").map { it.trim() }
    }

    fun isSeparatorRow(row: List<String>): Boolean =
        row.isNotEmpty() && row.all { cell -> cell.isNotEmpty() && cell.all { it == '-' || it == ':' || it == ' ' } }

    /** 单元格截断（与 xlsx 同上限：Excel 单格 32767；四个导出器共用同一上限） */
    fun clampCell(value: String): String =
        if (value.length <= MAX_CELL_CHARS) value else value.take(MAX_CELL_CHARS - 5) + "…（截断）"  // lint:allow zh-first（截断标记文案，与 XlsxExporter 原口径同源）

    private const val MAX_CELL_CHARS = 32_767
}
