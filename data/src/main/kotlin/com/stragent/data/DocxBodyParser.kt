package com.stragent.data

import com.stragent.core.security.SafeXml
import java.io.InputStream
import java.util.zip.ZipFile
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * docx 正文按文档顺序全量抽取（T-096）——零依赖 ZIP + JDK SAX 路线（与 [OdfDocxParser] 同纪律）。
 *
 * 与 [parseDocxTables] 的区别：后者只取最大表（表格分析用）；本解析器保留**文档顺序**——
 * 段落（w:p，表格外）与表格（w:tbl，整体）交替出现，供正文总结/追问使用。
 *
 * 口径：
 * - 表格单元格内的文字计入所在表格（w:tc 累积），不双重计为正文段落；
 * - 嵌套表格（表中表）**不丢**：内层单元格 tab 分列、行尾换行，直接压平进外层单元格文本；
 * - 空白段落不产出；段落文本 = 其中全部 w:t run 直接拼接（与既有单元格解析口径一致）；
 * - 标题样式（w:pStyle）原样作为段落文本（不做级别解释——标题即正文，总结层自行感知）。
 */
sealed interface DocxBodyElement {
    /** 正文段落（表格外 w:p 的拼接文本） */
    data class Paragraph(val text: String) : DocxBodyElement

    /** 表格（行矩阵，首行为表头的形态由调用方判定——本解析器不臆测表头） */
    data class Table(val rows: List<List<String>>) : DocxBodyElement
}

/**
 * word/document.xml → 按文档顺序的元素列表（[DocxBodyElement.Paragraph] /
 * [DocxBodyElement.Table]）。空文档返回空列表（调用方决定报错还是降级）。
 */
fun parseDocxBody(
    input: InputStream,
    maxUncompressedBytes: Long = ZipBudget.DEFAULT_MAX_BYTES,
): List<DocxBodyElement> {
    val tmp = kotlin.io.path.createTempFile(suffix = ".docx").toFile()
    tmp.outputStream().use { input.copyTo(it) }
    return try {
        ZipFile(tmp).use { zip ->
            val entry = zip.getEntry("word/document.xml")
                ?: throw IllegalArgumentException("这份 docx 缺少 document.xml，不是有效的 Word 文档") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            parseDocxBodyXml(ZipBudget(maxUncompressedBytes).open(zip.getInputStream(entry)))
        }
    } finally {
        tmp.delete()
    }
}

private fun parseDocxBodyXml(input: InputStream): List<DocxBodyElement> {
    val elements = mutableListOf<DocxBodyElement>()
    // 表格按嵌套层管理：tableStack 每层一份行矩阵；栈空 = 在表格外
    val tableStack = ArrayDeque<MutableList<MutableList<String>>>()
    // 行栈：w:tr 结束即移除——外层表格行在内层表格解析期间仍驻留（按文档序生命周期保持）
    val rowStack = ArrayDeque<MutableList<String>>()
    // 单元格缓冲栈：外层表格单元格不因内层表格/单元格而丢失（内层单元格结束时压平回上层）
    val cellStack = ArrayDeque<StringBuilder>()
    // 表格外 w:p 的段落缓冲；w:p 内 w:t 累积，w:p 结束时发出
    var paragraph: StringBuilder? = null

    val handler = object : DefaultHandler() {
        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            when (qName) {
                "w:tbl" -> tableStack.addLast(mutableListOf())
                "w:tr" -> {
                    val row = mutableListOf<String>()
                    rowStack.addLast(row)
                    tableStack.lastOrNull()?.add(row)
                }
                "w:tc" -> {
                    cellStack.addLast(StringBuilder())
                    rowStack.lastOrNull()?.add("")
                }
                "w:p" -> if (tableStack.isEmpty()) {
                    paragraph = StringBuilder()
                }
                "w:tab" -> {
                    if (cellStack.isNotEmpty()) cellStack.last().append('\t')
                    else paragraph?.append(' ')
                }
                "w:br" -> {
                    if (cellStack.isNotEmpty()) cellStack.last().append('\n')
                    else paragraph?.append(' ')
                }
            }
        }

        override fun characters(ch: CharArray?, start: Int, length: Int) {
            val buf = cellStack.lastOrNull() ?: paragraph
            buf?.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            when (qName) {
                "w:tc" -> {
                    val cell = cellStack.removeLastOrNull() ?: return
                    if (cellStack.isEmpty()) {
                        // 顶层单元格：写回行槽
                        val row = rowStack.lastOrNull() ?: return
                        if (row.isNotEmpty()) row[row.size - 1] = cell.toString()
                    } else {
                        // 嵌套在外层单元格里：tab 分列压平进外层单元格文本（不丢数据）
                        cellStack.last().append(cell.toString()).append('\t')
                    }
                }
                "w:tr" -> {
                    // 嵌套表的行尾换行（外层表格行不追加——其单元格文本已就位）
                    if (tableStack.size > 1 && cellStack.isNotEmpty()) {
                        cellStack.last().append('\n')
                    }
                    rowStack.removeLastOrNull()
                }
                "w:tbl" -> {
                    val rows = tableStack.removeLastOrNull() ?: return
                    if (tableStack.isEmpty()) {
                        elements.add(DocxBodyElement.Table(rows))
                    }
                    // 否则：内层行矩阵已在 w:tc/w:tr 压平成文本累积进外层单元格，无需另发元素
                }
                "w:p" -> {
                    paragraph?.let { buf ->
                        val text = buf.toString().trim()
                        if (text.isNotEmpty()) elements.add(DocxBodyElement.Paragraph(text))
                    }
                    paragraph = null
                }
            }
        }
    }
    SafeXml.newSaxParser().parse(input, handler)
    return elements
}
