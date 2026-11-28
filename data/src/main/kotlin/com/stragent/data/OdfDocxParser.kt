package com.stragent.data

import com.stragent.core.csv.Csv
import com.stragent.core.csv.ParsedTable
import com.stragent.core.security.SafeXml
import java.io.InputStream
import java.util.zip.ZipFile
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * ODS（OpenDocument 表格）与 docx（Word 表格）解析 —— 与 XlsxParser 同一条
 * 零依赖路线（ZIP + JDK SAX），补齐导入格式矩阵：
 *   ods  = ZIP 里的 content.xml（table:table 结构）
 *   fods = 单文件 flat XML（同结构，免解 zip）
 *   docx = ZIP 里的 word/document.xml（w:tbl 表格），取最大表
 *
 * 与 XlsxParser 同源纪律：公式取缓存文本、合并单元格取空、
 * LibreOffice 的尾部分页空格子（number-columns-repeated 巨大值）不展开。
 */

/** 行矩阵 → ParsedTable（与 XlsxParser 的收尾同口径：表头去重 + 类型推断；尾部全空列裁掉——ODS 的重复空格不拓宽表格）。供 ODS/docx 两个解析入口与 host 的文本导入共用 */
fun rowsToTable(rawRows: List<List<String>>): ParsedTable {
    val cleaned = rawRows.filter { row -> row.any { it.isNotBlank() } }
    if (cleaned.isEmpty()) throw IllegalArgumentException("这份文档里没有数据行")
    // 裁掉尾部全空列（LibreOffice 的重复空格子会把行撑宽到 1024 列）
    var width = 0
    for (row in cleaned) {
        for (index in row.indices.reversed()) {
            if (row[index].isNotBlank()) {
                if (index + 1 > width) width = index + 1
                break
            }
        }
    }
    val padded = cleaned.map { row -> (0 until width).map { row.getOrNull(it) ?: "" } }
    val header = padded.first().mapIndexed { index, name -> if (name.isBlank()) "列${index + 1}" else name }
    // 表头去重（与 Csv.parseDelimited 口径一致：a / a_2 / a_2_2）
    val seen = mutableMapOf<String, Int>()
    val columns = header.map { name ->
        val count = (seen[name] ?: 0) + 1
        seen[name] = count
        if (count == 1) name else "${name}_$count"
    }
    val body = padded.drop(1).map { row -> columns.indices.map { row.getOrNull(it) ?: "" } }
    return ParsedTable(
        columns = columns,
        rows = body,
        columnTypes = Csv.inferTypes(body, columns.size),
        delimiter = ',',
        truncated = false,
        totalRows = body.size,
    )
}

/** 解析 ODS（ZIP 容器）：全部工作表 */
fun parseOds(
    input: InputStream,
    maxUncompressedBytes: Long = ZipBudget.DEFAULT_MAX_BYTES,
): List<XlsxSheet> {
    val tmp = kotlin.io.path.createTempFile(suffix = ".ods").toFile()
    tmp.outputStream().use { input.copyTo(it) }
    return try {
        ZipFile(tmp).use { zip ->
            val entry = zip.getEntry("content.xml")
                ?: throw IllegalArgumentException("这份 ods 缺少 content.xml，不是有效的 OpenDocument 表格")
            val tables = parseOdfTables(ZipBudget(maxUncompressedBytes).open(zip.getInputStream(entry)))
            if (tables.isEmpty()) throw IllegalArgumentException("这份 ods 里没有工作表")
            tables
        }
    } finally {
        tmp.delete()
    }
}

/** 首个工作表（单表文件的常用路径） */
fun parseOdsFirst(
    input: InputStream,
    maxUncompressedBytes: Long = ZipBudget.DEFAULT_MAX_BYTES,
): ParsedTable =
    parseOds(input, maxUncompressedBytes).firstOrNull()?.table
        ?: throw IllegalArgumentException("这份 ods 里没有工作表")

/** 解析 fods（flat ODF 单文件 XML） */
fun parseFlatOds(input: InputStream): List<XlsxSheet> = parseOdfTables(input)

/** 解析 docx：取正文中**行数最多**的表格（Word 文档常带多个表，最大的才是数据表） */
fun parseDocxTables(
    input: InputStream,
    maxUncompressedBytes: Long = ZipBudget.DEFAULT_MAX_BYTES,
): ParsedTable {
    val tmp = kotlin.io.path.createTempFile(suffix = ".docx").toFile()
    tmp.outputStream().use { input.copyTo(it) }
    return try {
        ZipFile(tmp).use { zip ->
            val entry = zip.getEntry("word/document.xml")
                ?: throw IllegalArgumentException("这份 docx 缺少 document.xml，不是有效的 Word 文档")
            val tables = parseDocxTablesXml(ZipBudget(maxUncompressedBytes).open(zip.getInputStream(entry)))
            val best = tables.maxByOrNull { it.size }
            if (best == null || best.size < 2) {
                throw IllegalArgumentException("没有找到 Word 表格（至少需要表头一行 + 数据一行）")
            }
            rowsToTable(best)
        }
    } finally {
        tmp.delete()
    }
}

/** content.xml / flat XML → 工作表列表（ODF table:table 结构） */
private fun parseOdfTables(input: InputStream): List<XlsxSheet> {
    val sheets = mutableListOf<XlsxSheet>()
    var currentSheetName = ""
    var currentRows = mutableListOf<MutableList<String>>()
    var currentRow: MutableList<String>? = null
    var cellText = StringBuilder()
    var cellValue: String? = null
    var cellValueType = ""
    var cellRepeat = 1
    var inCell = false
    val rowRepeatStack = ArrayDeque<Int>()

    val handler = object : DefaultHandler() {
        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            val name = qName ?: return
            when {
                name == "table:table" -> {
                    if (currentRows.isNotEmpty()) {
                        sheets.add(XlsxSheet(currentSheetName, rowsToTable(currentRows)))
                        currentRows = mutableListOf()
                    }
                    currentSheetName = attributes?.getValue("table:name") ?: "Sheet${sheets.size + 1}"
                }
                name == "table:table-row" -> {
                    val repeat = attributes?.getValue("table:number-columns-repeated")?.toIntOrNull() ?: 1
                    if (repeat > 64) return // LibreOffice 尾部大块空行：跳过
                    currentRow = mutableListOf()
                    rowRepeatStack.addLast(repeat)
                }
                name == "table:table-cell" || name == "table:covered-table-cell" -> {
                    if (currentRow == null) return
                    inCell = true
                    cellText.setLength(0)
                    cellValue = attributes?.getValue("office:value")
                        ?: attributes?.getValue("office:date-value")
                        ?: attributes?.getValue("office:boolean-value")
                    cellValueType = attributes?.getValue("office:value-type") ?: ""
                    cellRepeat = attributes?.getValue("table:number-columns-repeated")?.toIntOrNull() ?: 1
                }
                name == "text:p" || name == "text:h" -> {
                    if (inCell && cellText.isNotEmpty()) cellText.append(' ')
                }
            }
        }

        override fun characters(ch: CharArray?, start: Int, length: Int) {
            if (inCell) cellText.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val name = qName ?: return
            when {
                name == "table:table-cell" || name == "table:covered-table-cell" -> {
                    val row = currentRow ?: return
                    val raw = when (cellValueType) {
                        // 数字/日期/布尔：以 office:value 系属性为准（显示文本可能带本地化千分位）
                        "float", "percentage", "currency", "date", "boolean" ->
                            cellValue ?: cellText.toString().trim()
                        else -> cellText.toString().trim()
                    }
                    val repeat = if (raw.isEmpty() && cellRepeat > 256) 0 else cellRepeat.coerceAtMost(256)
                    // 展开（上限 256 列/行，防 LibreOffice 尾部大 repeat 撑爆内存）
                    for (i in 0 until repeat) row.add(raw)
                    inCell = false
                    cellValueType = ""
                    cellValue = null
                }
                name == "table:table-row" -> {
                    currentRow?.let {
                        val repeat = rowRepeatStack.removeLastOrNull() ?: 1
                        // 重复空行不展开；重复非空行上限 64（分页表头场景几乎不存在）
                        if (it.any { cell -> cell.isNotBlank() }) {
                            for (i in 0 until repeat.coerceAtMost(64)) currentRows.add(it.toMutableList())
                        }
                    }
                    currentRow = null
                }
                name == "table:table" -> {
                    if (currentRows.isNotEmpty()) {
                        sheets.add(XlsxSheet(currentSheetName, rowsToTable(currentRows)))
                        currentRows = mutableListOf()
                    }
                }
            }
        }
    }
    SafeXml.newSaxParser().parse(input, handler)
    if (currentRows.isNotEmpty()) {
        sheets.add(XlsxSheet(currentSheetName, rowsToTable(currentRows)))
    }
    return sheets.filter { it.table.rows.isNotEmpty() }
}

/** document.xml → 各表格的行矩阵（不收敛成 ParsedTable，交给调用方选最大表） */
private fun parseDocxTablesXml(input: InputStream): List<List<List<String>>> {
    val allTables = mutableListOf<MutableList<MutableList<String>>>()
    var currentTable: MutableList<MutableList<String>>? = null
    var currentRow: MutableList<String>? = null
    var currentCell: StringBuilder? = null

    val handler = object : DefaultHandler() {
        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            when (qName) {
                "w:tbl" -> currentTable = mutableListOf()
                "w:tr" -> {
                    currentRow = mutableListOf()
                    currentTable?.add(currentRow!!)
                }
                "w:tc" -> {
                    currentCell = StringBuilder()
                    currentRow?.add("")
                }
            }
        }

        override fun characters(ch: CharArray?, start: Int, length: Int) {
            // w:tc 内的所有文本（w:t run、w:p 段落）都累积进当前单元格
            currentCell?.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            when (qName) {
                "w:tc" -> {
                    // 单元格结束：缓冲写入行尾槽位
                    val row = currentRow ?: return
                    if (row.isNotEmpty()) row[row.size - 1] = currentCell?.toString() ?: ""
                    currentCell = null
                }
                "w:tr" -> currentRow = null
                "w:tbl" -> currentTable?.let { allTables.add(it); currentTable = null }
            }
        }
    }
    SafeXml.newSaxParser().parse(input, handler)
    return allTables
}
