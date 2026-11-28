package com.stragent.data

import com.stragent.core.csv.ColumnType
import com.stragent.core.csv.Csv
import com.stragent.core.csv.ParsedTable
import com.stragent.core.security.SafeXml
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * xlsx 解析（零依赖路线）——table-file.ts 里 spreadsheet 分支的替代实现。
 *
 * 为什么不用 Apache POI：POI 的 XSSF 依赖 javax.xml.stream（StAX），Android
 * 运行时没有这套 API，要再捆一个 StAX 实现才勉强能跑——为一个解 zip+XML
 * 的活引入 8MB 依赖与兼容性地雷不值。xlsx 本质是 ZIP 里的几个 XML：
 *   xl/sharedStrings.xml   共享字符串池
 *   xl/styles.xml          数字格式（日期识别靠它）
 *   xl/worksheets/sheetN.xml  行列数据
 * 用 JDK 自带的 SAX 全部可解——真机与 JVM 同一套代码。
 *
 * 覆盖：字符串（共享/内联）、数字、布尔、日期（内置格式 14–22 + 自定义含 y/m/d）。
 * 不覆盖：公式只取缓存值、富文本去标签取纯文本（与导入场景够用）。
 */

/** 解析出的一个工作表：名称 + 表格（首行为表头） */
data class XlsxSheet(val name: String, val table: ParsedTable)

/** 单元格 r="B12" 里的列字母段 */
private val CELL_REF = Regex("^([A-Z]+)")

/** 从 xlsx 字节流解出全部工作表（多 sheet 全解——概要用得上） */
fun parseXlsx(
    input: InputStream,
    maxUncompressedBytes: Long = ZipBudget.DEFAULT_MAX_BYTES,
): List<XlsxSheet> {
    val tmp = kotlin.io.path.createTempFile(suffix = ".xlsx").toFile()
    tmp.outputStream().use { input.copyTo(it) }
    return try {
        ZipFile(tmp).use { zip ->
            val budget = ZipBudget(maxUncompressedBytes)
            val shared = sharedStrings(zip, budget)
            val dateStyles = dateStyleIndexes(zip, budget)
            // sheet 顺序以 workbook.xml 为准，缺省时按文件名排序
            val sheetNames = sheetNames(zip, budget)
            val sheetFiles = zip.entries().asSequence()
                .map { it.name }
                .filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
                // 必须按数字而非字典序：sheet10 字典序会排在 sheet2 前面，与 workbook 名单错位
                .sortedBy { it.substringAfterLast("sheet").substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE }
                .toList()
            sheetFiles.mapIndexed { index, entryName ->
                val name = sheetNames.getOrNull(index) ?: "Sheet${index + 1}"
                val table = parseSheet(budget.open(zip.getInputStream(zip.getEntry(entryName))), shared, dateStyles)
                XlsxSheet(name, table)
            }
        }
    } finally {
        tmp.delete()
    }
}

/** 首个工作表（单表文件的常用路径） */
fun parseXlsxFirst(
    input: InputStream,
    maxUncompressedBytes: Long = ZipBudget.DEFAULT_MAX_BYTES,
): ParsedTable =
    parseXlsx(input, maxUncompressedBytes).firstOrNull()?.table
        ?: throw IllegalArgumentException("这份 xlsx 里没有工作表")

/** 单元格 r="B12" 里的列字母段 */
/** sharedStrings.xml → 字符串池 */
private fun sharedStrings(zip: ZipFile, budget: ZipBudget): List<String> {
    val entry = zip.getEntry("xl/sharedStrings.xml") ?: return emptyList()
    val strings = mutableListOf<String>()
    val handler = object : DefaultHandler() {
        var inShared = false
        val buffer = StringBuilder()

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            if (qName == "si") {
                inShared = true
                buffer.setLength(0)
            }
        }

        override fun characters(ch: CharArray?, start: Int, length: Int) {
            if (inShared) buffer.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            if (qName == "si") {
                strings.add(buffer.toString())
                inShared = false
            }
        }
    }
    SafeXml.newSaxParser().parse(budget.open(zip.getInputStream(entry)), handler)
    return strings
}

/** styles.xml → 日期格式的样式下标集合（内置 14–22 + 自定义含 y/m/d 的 numFmt） */
private fun dateStyleIndexes(zip: ZipFile, budget: ZipBudget): Set<Int> {
    val entry = zip.getEntry("xl/styles.xml") ?: return emptySet()
    val customDateIds = mutableSetOf<Int>()
    val handler = object : DefaultHandler() {
        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            if (qName == "numFmt") {
                val code = attributes?.getValue("formatCode")?.lowercase(Locale.ROOT) ?: return
                // 日期格式：含 y/m/d 的自定义格式（纯时间 h:mm 不含这三者，被排除）
                if (Regex("[ymd]").containsMatchIn(code)) {
                    val id = attributes.getValue("numFmtId")?.toIntOrNull()
                    if (id != null) customDateIds.add(id)
                }
            }
        }
    }
    SafeXml.newSaxParser().parse(budget.open(zip.getInputStream(entry)), handler)
    val result = mutableSetOf<Int>()
    // 收集 numFmtId ∈ 日期档 的 cellXfs 下标
    val dateNumFmtIds = (14..22).toSet() + customDateIds
    val xfsHandler = object : DefaultHandler() {
        var inCellXfs = false
        var index = 0
        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            when (qName) {
                "cellXfs" -> {
                    inCellXfs = true
                    index = 0
                }
                "xf" -> {
                    if (inCellXfs) {
                        val id = attributes?.getValue("numFmtId")?.toIntOrNull() ?: 0
                        if (id in dateNumFmtIds) result.add(index)
                        index += 1
                    }
                }
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            if (qName == "cellXfs") inCellXfs = false
        }
    }
    SafeXml.newSaxParser().parse(budget.open(zip.getInputStream(entry)), xfsHandler)
    return result
}

/** workbook.xml → sheet 名单（按出现顺序） */
private fun sheetNames(zip: ZipFile, budget: ZipBudget): List<String> {
    val entry = zip.getEntry("xl/workbook.xml") ?: return emptyList()
    val names = mutableListOf<String>()
    val handler = object : DefaultHandler() {
        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            if (qName == "sheet") {
                attributes?.getValue("name")?.let { names.add(it) }
            }
        }
    }
    SafeXml.newSaxParser().parse(budget.open(zip.getInputStream(entry)), handler)
    return names
}

/** sheetN.xml → 行矩阵（首行表头） */
private fun parseSheet(input: InputStream, shared: List<String>, dateStyles: Set<Int>): ParsedTable {
    val rows = mutableListOf<MutableList<String>>()
    val handler = object : DefaultHandler() {
        var currentRow: MutableList<String>? = null
        var currentCellType = ""
        var currentStyle = 0
        var cellBuffer = StringBuilder()
        var currentColumn = -1

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            when (qName) {
                "row" -> {
                    currentRow = mutableListOf()
                }
                "c" -> {
                    currentCellType = attributes?.getValue("t") ?: "n"
                    currentStyle = attributes?.getValue("s")?.toIntOrNull() ?: 0
                    cellBuffer.setLength(0)
                    // 列号从 r="B12" 推断，缺 r 时按顺序
                    val ref = attributes?.getValue("r")
                    currentColumn = if (ref != null) {
                        columnIndexOf(CELL_REF.find(ref)?.groupValues?.get(1) ?: "A")
                    } else {
                        (currentRow?.size ?: 0)
                    }
                }
            }
        }

        override fun characters(ch: CharArray?, start: Int, length: Int) {
            cellBuffer.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            when (qName) {
                "c" -> {
                    val row = currentRow ?: return
                    val raw = cellBuffer.toString()
                    val value: String = when (currentCellType) {
                        "s" -> shared.getOrNull(raw.toIntOrNull() ?: -1) ?: ""
                        "inlineStr" -> raw
                        "str" -> raw // 公式缓存字符串
                        "b" -> if (raw == "1") "TRUE" else "FALSE"
                        else -> {
                            // 数字：样式是日期档 → 序列号转 yyyy-MM-dd
                            if (currentStyle in dateStyles) {
                                val serial = raw.toDoubleOrNull()
                                if (serial != null) excelSerialToText(serial) else raw
                            } else {
                                raw
                            }
                        }
                    }
                    while (row.size < currentColumn) row.add("")
                    if (currentColumn >= row.size) row.add(value) else row[currentColumn] = value
                }
                "row" -> {
                    currentRow?.let { rows.add(it) }
                    currentRow = null
                }
            }
        }
    }
    SafeXml.newSaxParser().parse(input, handler)
    // 去掉全空行（xlsx 常在尾部带空 <row/>）
    val cleaned = rows.filter { row -> row.any { it.isNotBlank() } }
    if (cleaned.isEmpty()) throw IllegalArgumentException("这份 xlsx 的工作表没有数据行")
    val header = cleaned.first().mapIndexed { index, name -> if (name.isBlank()) "列${index + 1}" else name }
    val deduped = dedupeHeaders(header)
    val body = cleaned.drop(1).map { row -> deduped.indices.map { row.getOrNull(it) ?: "" } }
    return ParsedTable(
        columns = deduped,
        rows = body,
        columnTypes = Csv.inferTypes(body, deduped.size),
        delimiter = ',',
        truncated = false,
        totalRows = body.size,
    )
}

/** 表头去重（与 Csv.parseDelimited 的口径一致：a / a_2 / a_2_2） */
private fun dedupeHeaders(header: List<String>): List<String> {
    val seen = mutableMapOf<String, Int>()
    return header.map { name ->
        val count = (seen[name] ?: 0) + 1
        seen[name] = count
        if (count == 1) name else "${name}_$count"
    }
}

/** Excel 列字母 → 0 起下标（A=0, B=1, …, AA=26） */
private fun columnIndexOf(letters: String): Int {
    var index = 0
    for (ch in letters) index = index * 26 + (ch - 'A' + 1)
    return index - 1
}

/** Excel 日期序列号 → 文本。1900 日期系统：Excel 认为 1900-02-29（第 60 天）存在，
 *  真实日历没有这一天——序列 ≤59 时多补一天，≥61 用标准偏移（60 不存在）。 */
private fun excelSerialToText(serial: Double): String {
    val days = Math.floor(serial).toLong()
    val fraction = serial - days
    val adjusted = if (days <= 59) days + 1 else days
    // 序列 25569 = 1970-01-01（≥61 段的标准偏移）
    val millis = (adjusted - 25569) * 86_400_000L
    val date = Date(millis)
    val format = if (fraction > 1e-6) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
    } else {
        SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    }
    return format.format(date)
}
