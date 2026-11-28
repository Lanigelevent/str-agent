package com.stragent.core.csv

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 文本类表格解析 —— table-text.ts 移植：JSON / HTML 表格 / Markdown 表格 → ParsedTable。
 * 零依赖（正则 + JSON），服务两个场景：文件导入（.json/.html/.md）与粘贴识别。
 */

/** 把首行当表头的行矩阵收敛成 ParsedTable（公共收尾） */
private fun toTable(header: List<String>, body: List<List<String>>): ParsedTable {
    val seen = mutableMapOf<String, Int>()
    val columns = header.mapIndexed { index, name ->
        val base = if (name.trim().isEmpty()) "列${index + 1}" else name.trim()
        val count = (seen[base] ?: 0) + 1
        seen[base] = count
        if (count == 1) base else "${base}_$count"
    }
    // 统一列宽（HTML / Markdown 来源可能有残缺单元格）
    val rows = body.map { row -> columns.indices.map { index -> (row.getOrNull(index) ?: "").trim() } }
    return ParsedTable(
        columns = columns,
        rows = rows,
        columnTypes = Csv.inferTypes(rows, columns.size),
        delimiter = '\t',
        truncated = false,
        totalRows = rows.size,
    )
}

/** JSON：数组-of-对象（最常见）或数组-of-数组 */
fun parseJsonTable(text: String): ParsedTable {
    val root = runCatching { Json.parseToJsonElement(text) }
        .getOrElse { throw IllegalArgumentException("不是合法的 JSON") }
    var data: JsonElement = root
    // 容忍 { data: [...] } / { rows: [...] } / { items / list } 包装
    if (root is JsonObject) {
        val inner = root["data"] ?: root["rows"] ?: root["items"] ?: root["list"]
        if (inner is JsonArray) data = inner
    }
    if (data !is JsonArray || data.jsonArraySize() == 0) {
        throw IllegalArgumentException("JSON 需要是对象数组（或数组的数组）")
    }
    val array = data as JsonArray
    val first = array.firstOrNull()
    return when {
        first is JsonArray -> {
            // 数组的数组：第一行是表头，数据从第二行开始
            val header = first.mapIndexed { index, _ -> "列${index + 1}" }
            val body = array.drop(1).map { row -> (row as? JsonArray ?: JsonArray(emptyList())).map { stringify(it) } }
            toTable(header, body)
        }
        first is JsonObject -> {
            // 列顺序按首个对象的键序；后续对象补齐缺失键
            val columns = mutableListOf<String>()
            array.forEach { item ->
                if (item is JsonObject) {
                    item.keys.forEach { key -> if (key !in columns) columns.add(key) }
                }
            }
            val body = array.map { item ->
                columns.map { column -> stringify((item as? JsonObject)?.get(column)) }
            }
            toTable(columns, body)
        }
        else -> throw IllegalArgumentException("JSON 需要是对象数组（或数组的数组）")
    }
}

private fun JsonArray.jsonArraySize(): Int = size

private fun stringify(value: JsonElement?): String = when (value) {
    null, is kotlinx.serialization.json.JsonNull -> ""
    is JsonPrimitive -> if (value.isString) value.content else value.content
    is JsonObject -> value.toString()
    is JsonArray -> value.toString()
}

/** HTML 表格：抽出所有 <tr>，单元格取 <td>/<th>，去标签留文本 */
fun parseHtmlTable(text: String): ParsedTable {
    val rows = mutableListOf<List<String>>()
    val trRegex = Regex("<tr[^>]*>([\\s\\S]*?)</tr>", RegexOption.IGNORE_CASE)
    val cellRegex = Regex("<t[hd][^>]*>([\\s\\S]*?)</t[hd]>", RegexOption.IGNORE_CASE)
    for (trMatch in trRegex.findAll(text)) {
        val cells = cellRegex.findAll(trMatch.groupValues[1]).map { stripTags(it.groupValues[1]) }.toList()
        if (cells.isNotEmpty()) rows.add(cells)
    }
    if (rows.size < 2) {
        throw IllegalArgumentException("没有找到 HTML 表格（至少需要表头一行 + 数据一行）")
    }
    return toTable(rows.first(), rows.drop(1))
}

/** Markdown 表格：| a | b | 形式，第二行 |---|---| 分隔行跳过 */
fun parseMarkdownTable(text: String): ParsedTable {
    val rows = mutableListOf<List<String>>()
    for (line in text.lines()) {
        val trimmed = line.trim()
        if (!trimmed.startsWith("|")) continue
        val cells = trimmed.removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
        // 跳过分隔行（|---|:--:|）
        if (cells.all { Regex("^:?-{2,}:?$").matches(it) || it.isEmpty() }) continue
        if (cells.isNotEmpty()) rows.add(cells)
    }
    if (rows.size < 2) {
        throw IllegalArgumentException("没有找到 Markdown 表格（至少需要表头一行 + 数据一行）")
    }
    return toTable(rows.first(), rows.drop(1))
}

/** 去 HTML 标签 + 实体解码（表格单元格够用的最小集） */
private fun stripTags(html: String): String = html
    .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ")
    .replace(Regex("<[^>]+>"), "")
    .replace("&nbsp;", " ")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&amp;", "&")
    .replace(Regex("\\s+"), " ")
    .trim()

/** 粘贴格式种类（csv 由 csv.ts 的 parseDelimited 兜底） */
enum class PasteKind { MARKDOWN, HTML, JSON, CSV }

/**
 * 粘贴探测器：顺序有讲究——先验特征最鲜明的（JSON 结构 / HTML 标签 /
 * Markdown 分隔行），最后才落到 CSV（最宽容的格式，放最后当兜底）。
 */
fun detectPasteKind(text: String): PasteKind {
    val trimmed = text.trim()
    // Java 正则的字符类里 [ 必须转义（JS 的 [[{] 写法在 Java 是未闭合类）
    if (Regex("^\\s*[\\[{]").containsMatchIn(trimmed)) return PasteKind.JSON
    if (Regex("<t[rh][\\s>]", RegexOption.IGNORE_CASE).containsMatchIn(trimmed) ||
        Regex("<table[\\s>]", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)
    ) {
        return PasteKind.HTML
    }
    if (Regex("^\\s*\\|.*\\|", RegexOption.MULTILINE).containsMatchIn(trimmed) &&
        Regex("\\|\\s*:?-{2,}").containsMatchIn(trimmed)
    ) {
        return PasteKind.MARKDOWN
    }
    return PasteKind.CSV
}

/** 按探测结果解析粘贴内容；CSV 返回 null（调用方走 parseDelimited） */
fun parsePasted(text: String): ParsedTable? = when (detectPasteKind(text)) {
    PasteKind.JSON -> parseJsonTable(text)
    PasteKind.HTML -> parseHtmlTable(text)
    PasteKind.MARKDOWN -> parseMarkdownTable(text)
    PasteKind.CSV -> null
}
