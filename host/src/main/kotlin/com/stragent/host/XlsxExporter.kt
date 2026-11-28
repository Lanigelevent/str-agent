package com.stragent.host

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 真 XLSX 导出器（T-054 / D17 G-12）—— OOXML 零依赖实现。
 *
 * 旧实现叫「Excel 导出」但写的是 CSV（且只导首表）：同名不同实，属于 D17 明确
 * 点名的「名不副实」。这里改为写**真正的 .xlsx**：ZIP 里按 OOXML 规范放
 *   [Content_Types].xml / _rels/.rels / xl/workbook.xml / xl/_rels/workbook.xml.rels
 *   xl/styles.xml / xl/worksheets/sheetN.xml
 * 单元格用 inlineStr（无需 sharedStrings 池），数字按严格字面量判定后用 `<v>` 存，
 * 这样 Excel/WPS/LibreOffice 打开即可排序与计算；表头加粗底色。
 *
 * 为什么不用 Apache POI：与 XlsxParser 同一条理由——POI 的 XSSF 依赖 StAX，
 * Android 运行时没有；为一个「拼 ZIP + XML」的活引入 8MB 依赖不值。
 *
 * 口径：
 *  - 第一个工作表固定是「报告概要」：标题 + 每章列数/行数，数据页紧随其后；
 *  - 每章有 Markdown 表格 → 一张数据表；没有表格但有正文 → 一张纯文本页（逐行）；
 *  - 同一输入产出同一份字节（ZIP 条目时间戳固定为 1980-01-01）；
 *  - 单格超过 Excel 上限 32767 字符时截断（不静默：末尾加「…（截断）」）。
 */
object XlsxExporter {

    data class ExportSection(val title: String, val content: String)

    data class ExportRequest(val title: String, val sections: List<ExportSection>)

    /** 导出到 `.xlsx` 文件，返回文件句柄 */
    fun exportToFile(request: ExportRequest, outputDir: File): File {
        val bytes = exportToBytes(request)
        // 唯一文件名（CI 连续 3 次实证 + 并发面）：固定名在共享 temp 目录会被并发导出互相覆盖，
        // 且新写文件可能被外部进程（搜索索引器/杀软）短暂持有导致 ZipFile 打开失败
        //（ZipFile 把任何 IOException 包装成 ZipException——症状是「文件存在但打不开」）。
        outputDir.mkdirs()
        // createTempFile 的前缀至少 3 字符——短标题（如「A」）补齐，避免 IllegalArgumentException
        val prefix = (sanitizeFileName(request.title) + "-").padEnd(3, '_')
        val file = File.createTempFile(prefix, ".xlsx", outputDir)
        file.writeBytes(bytes)
        return file
    }

    /** 导出为 xlsx 字节流（测试与内存交付走这里） */
    fun exportToBytes(request: ExportRequest): ByteArray {
        if (request.sections.isEmpty()) {
            throw IllegalArgumentException("没有可导出的章节：sections 不能为空")
        }
        val sheets = planSheets(request)
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            writeEntry(zip, "[Content_Types].xml", contentTypes(sheets.size))
            writeEntry(zip, "_rels/.rels", ROOT_RELS)
            writeEntry(zip, "xl/workbook.xml", workbook(sheets))
            writeEntry(zip, "xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            writeEntry(zip, "xl/styles.xml", STYLES)
            sheets.forEachIndexed { index, sheet ->
                writeEntry(zip, "xl/worksheets/sheet${index + 1}.xml", worksheet(sheet))
            }
        }
        return buffer.toByteArray()
    }

    // ---------- 工作表规划 ----------

    private data class Sheet(
        val name: String,
        val rows: List<List<String>>,
        /** 表头行下标（0 起）——加粗底色 */
        val headerRows: Set<Int> = emptySet(),
        /** 每列宽度；缺省 16 */
        val widths: List<Int> = emptyList(),
        /** 纯文本页：单元格自动换行 */
        val wrap: Boolean = false,
    )

    private fun planSheets(request: ExportRequest): List<Sheet> {
        val overviewName = "报告概要"
        // 先占概要页名字：章节标题叫「报告概要」时改叫「报告概要(2)」，不能两张同名片
        val used = mutableSetOf(overviewName.lowercase(Locale.ROOT))
        val overviewRows = mutableListOf<List<String>>()
        // 标题行补足三列：表格读回器以首行宽度定列数，单格标题行会把概要正文挤成一列
        overviewRows.add(listOf(clampCell(request.title), "", ""))
        overviewRows.add(emptyList())
        overviewRows.add(listOf("章节", "列数", "数据行数"))
        val dataSheets = mutableListOf<Sheet>()

        request.sections.forEachIndexed { index, section ->
            val title = section.title.ifBlank { "章节${index + 1}" }
            val table = extractMarkdownTable(section.content)
            val contentLines = section.content.lines()
                .dropWhile { it.isBlank() }
                .dropLastWhile { it.isBlank() }
            when {
                table != null -> {
                    val name = uniqueSheetName(used, title, "章节${index + 1}")
                    overviewRows.add(listOf(clampCell(title), table.headers.size.toString(), table.rows.size.toString()))
                    dataSheets.add(
                        Sheet(
                            name = name,
                            rows = listOf(table.headers) + table.rows,
                            headerRows = setOf(0),
                            widths = listOf(if (table.headers.isEmpty()) 16 else 18),
                        ),
                    )
                }
                contentLines.isNotEmpty() -> {
                    val name = uniqueSheetName(used, title, "章节${index + 1}")
                    overviewRows.add(listOf(clampCell(title), "1", contentLines.size.toString()))
                    dataSheets.add(
                        Sheet(
                            name = name,
                            rows = contentLines.map { listOf(clampCell(it)) },
                            widths = listOf(100),
                            wrap = true,
                        ),
                    )
                }
                // 标题与正文都空：概要页照实记 0/0，不凭空造页
                else -> overviewRows.add(listOf(clampCell(title), "0", "0"))
            }
        }

        if (dataSheets.isEmpty()) {
            overviewRows.add(emptyList())
            overviewRows.add(listOf("说明", "报告没有表格或正文内容，只有标题。"))
        }

        val overview = Sheet(
            name = overviewName,
            rows = overviewRows,
            headerRows = setOf(0, 2),
            widths = listOf(32, 10, 10),
        )
        return listOf(overview) + dataSheets
    }

    // 表格抽取与单元格截断共享（T-167）：xlsx/docx/pptx/pdf 四个导出器同一份解析口径
    private fun extractMarkdownTable(content: String) = ReportTables.firstMarkdownTable(content)
    private fun clampCell(value: String) = ReportTables.clampCell(value)

    // ---------- 工作表 XML ----------

    private fun worksheet(sheet: Sheet): String {
        val colCount = maxOf(1, sheet.rows.maxOfOrNull { it.size } ?: 0)
        val rowCount = maxOf(1, sheet.rows.size)
        val sb = StringBuilder(4096)
        sb.append(XML_DECL)
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        sb.append("<dimension ref=\"A1:${columnLetters(colCount - 1)}$rowCount\"/>")
        sb.append("<sheetViews><sheetView workbookViewId=\"0\"/></sheetViews>")
        sb.append("<sheetFormatPr defaultRowHeight=\"15\"/>")
        sb.append("<cols>")
        for (i in 0 until colCount) {
            val width = sheet.widths.getOrNull(i) ?: 16
            sb.append("<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"$width\" customWidth=\"1\"/>")
        }
        sb.append("</cols>")
        sb.append("<sheetData>")
        sheet.rows.forEachIndexed { rowIndex, row ->
            val rowNumber = rowIndex + 1
            sb.append("<row r=\"$rowNumber\">")
            row.forEachIndexed { columnIndex, value ->
                val style = when {
                    rowIndex in sheet.headerRows -> STYLE_HEADER
                    sheet.wrap -> STYLE_WRAP
                    else -> STYLE_DEFAULT
                }
                sb.append(cell(columnLetters(columnIndex) + rowNumber, value, style))
            }
            sb.append("</row>")
        }
        sb.append("</sheetData>")
        sb.append("</worksheet>")
        return sb.toString()
    }

    private fun cell(ref: String, value: String, style: Int): String {
        val styleAttr = if (style == STYLE_DEFAULT) "" else " s=\"$style\""
        if (value.isEmpty()) return "<c r=\"$ref\"$styleAttr/>"
        val number = strictNumber(value)
        if (number != null) return "<c r=\"$ref\"$styleAttr><v>$number</v></c>"
        val preserve = if (needsPreserve(value)) " xml:space=\"preserve\"" else ""
        return "<c r=\"$ref\"$styleAttr t=\"inlineStr\"><is><t$preserve>${xmlEsc(value)}</t></is></c>"
    }

    /** 严格数字字面量：保留前导零/千分位/百分号/科学计数的原样文本，避免把编号变成数字 */
    private fun strictNumber(value: String): String? =
        if (STRICT_NUMBER.matches(value)) value else null

    private fun needsPreserve(value: String): Boolean =
        value.first().isWhitespace() || value.last().isWhitespace() || value.contains('\n') || value.contains('\t')

    private fun xmlEsc(value: String): String {
        val sb = StringBuilder(value.length + 16)
        for (ch in value) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                // XML 1.0 不允许的控制字符：替换为空格（保留 \t \n \r）
                else -> if (ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r') sb.append(' ') else sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** 列下标 → 字母（0=A，26=AA） */
    private fun columnLetters(index: Int): String {
        var remaining = index
        val sb = StringBuilder()
        while (true) {
            sb.append(('A' + remaining % 26))
            remaining = remaining / 26 - 1
            if (remaining < 0) break
        }
        return sb.reverse().toString()
    }

    // ---------- 包结构与工作簿 ----------

    private fun contentTypes(sheetCount: Int): String {
        val sb = StringBuilder()
        sb.append(XML_DECL)
        sb.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        sb.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        sb.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        sb.append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
        for (i in 1..sheetCount) {
            sb.append("<Override PartName=\"/xl/worksheets/sheet$i.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        }
        sb.append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
        sb.append("</Types>")
        return sb.toString()
    }

    private fun workbook(sheets: List<Sheet>): String {
        val sb = StringBuilder()
        sb.append(XML_DECL)
        sb.append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" ")
        sb.append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
        sb.append("<sheets>")
        sheets.forEachIndexed { index, sheet ->
            sb.append("<sheet name=\"${xmlEsc(sheet.name)}\" sheetId=\"${index + 1}\" r:id=\"rId${index + 1}\"/>")
        }
        sb.append("</sheets>")
        sb.append("</workbook>")
        return sb.toString()
    }

    private fun workbookRels(sheetCount: Int): String {
        val sb = StringBuilder()
        sb.append(XML_DECL)
        sb.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        for (i in 1..sheetCount) {
            sb.append(
                "<Relationship Id=\"rId$i\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" " +
                    "Target=\"worksheets/sheet$i.xml\"/>",
            )
        }
        sb.append(
            "<Relationship Id=\"rId${sheetCount + 1}\" " +
                "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>",
        )
        sb.append("</Relationships>")
        return sb.toString()
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, content: String) {
        val entry = ZipEntry(name)
        entry.time = FIXED_ENTRY_TIME
        zip.putNextEntry(entry)
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^\\u4e00-\\u9fa5a-zA-Z0-9_\\-]"), "_").take(50).ifBlank { "export" }

    /** Excel 工作表名：≤31 字符、不含 []:*?/\、首尾不为单引号、同名（忽略大小写）去重 */
    private fun uniqueSheetName(used: MutableSet<String>, raw: String, fallback: String): String {
        var base = raw.replace(Regex("[\\[\\]:*?/\\\\]"), "_").trim().trim('\'').ifBlank { fallback }
        if (base.length > MAX_SHEET_NAME) base = base.take(MAX_SHEET_NAME)
        var name = base
        var suffix = 2
        while (!used.add(name.lowercase(Locale.ROOT))) {
            val tail = "($suffix)"
            name = base.take(MAX_SHEET_NAME - tail.length) + tail
            suffix += 1
        }
        return name
    }

    private const val XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
    private const val MAX_CELL_CHARS = 32_767
    private const val MAX_SHEET_NAME = 31
    private const val STYLE_DEFAULT = 0
    private const val STYLE_HEADER = 1
    private const val STYLE_WRAP = 2

    /** 1980-01-01T00:00:00Z —— ZIP 的最早 DOS 时间，保证同一输入产出同一份字节 */
    private const val FIXED_ENTRY_TIME = 315_532_800_000L

    private val STRICT_NUMBER = Regex("^-?(0|[1-9]\\d{0,14})(\\.\\d+)?$")

    private const val ROOT_RELS =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" " +
            "Target=\"xl/workbook.xml\"/>" +
            "</Relationships>"

    /** 样式：0 默认；1 表头（加粗 + 蓝底）；2 自动换行（纯文本页） */
    private val STYLES =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<fonts count=\"2\">" +
            "<font><sz val=\"11\"/><name val=\"Calibri\"/></font>" +
            "<font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/></font>" +
            "</fonts>" +
            "<fills count=\"3\">" +
            "<fill><patternFill patternType=\"none\"/></fill>" +
            "<fill><patternFill patternType=\"gray125\"/></fill>" +
            "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF4E79A7\"/><bgColor indexed=\"64\"/></patternFill></fill>" +
            "</fills>" +
            "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"3\">" +
            "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>" +
            "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\">" +
            "<alignment wrapText=\"1\" vertical=\"top\"/></xf>" +
            "</cellXfs>" +
            "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
            "</styleSheet>"
}
