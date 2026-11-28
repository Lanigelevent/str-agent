package com.stragent.core.jsonl

import com.stragent.core.csv.ColumnType
import com.stragent.core.csv.Csv
import com.stragent.core.io.EncodingDetection
import com.stragent.core.io.TextEncoding
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * JSONL 导入（T-058）—— 每行一个 JSON 对象的文本表格，零新依赖。
 *
 * 为什么不用 DuckDB 读 JSON：JSONL 的**字段并集**、**编码**与**坏行定位**都需要
 * 逐行解释；DuckDB 的 `read_json` 报错只给一个位置，用户看不出「第几行、缺什么」。
 * 这里两遍流式扫描，堆里只放目标样本行：
 *  - 第一遍：编码探测 → 严格解析每一行（顺手汇总字段并集与数值性）+ 计算整文件 SHA-256；
 *  - 第二遍：按固定步长收集样本行，样本行号 ↔ 源文件非空 JSON 行号可回链。
 *
 * 口径（与 CSV 抽样同一套语义，写进工具回包）：
 *  - 空白行不计行号、不占字段；行号 = 第 N 个非空 JSON 行（1 起）；
 *  - 步长 = ceil(总行数 / 目标行数)，起点第 1 行；同一文件任何时候抽到的行一致；
 *  - 字段并集来自**全量**扫描（不是只看头部若干行），顺序按首次出现；
 *  - 列类型：该字段所有非空值都能按 CSV 同一套 parseNumber 解析 → number，否则 text；
 *    对象/数组值以紧凑 JSON 文本入表，并标 `nested=true`（不做多级展开，避免凭空造列）。
 *
 * 错误口径（全部带行号与行首预览）：
 *  - 某行不是合法 JSON → 直接失败，指出第 N 个非空行；
 *  - 某行是合法 JSON 但不是对象（数组/数字/字符串）→ 直接失败，说明 JSONL 要求每行是对象。
 */
object JsonlSampler {

    /** 单文件上限与 CSV 抽样一致：两遍扫描只占常数内存 */
    const val MAX_FILE_BYTES: Long = 256L * 1024 * 1024

    /** 单个字段（schema 推断结果，随导入回包给用户看） */
    data class Field(
        val name: String,
        val type: ColumnType,
        /** 值里出现过对象/数组：入表时是紧凑 JSON 文本，不展开 */
        val nested: Boolean,
        /** 该字段在该文件里是否全量都可解析为数字 */
        val numeric: Boolean,
    )

    data class Plan(
        val fingerprint: String,
        val sourceBytes: Long,
        val totalRows: Int,
        val targetRows: Int,
        val step: Int,
        /** 样本第 N 行（1 起）↔ 源文件第几个非空 JSON 行（1 起） */
        val rowNumbers: List<Int>,
        val encoding: EncodingDetection,
        val blankLines: Int,
        /** 解码过程中出现的替换字符数（>0 说明探测的编码没覆盖全部字节） */
        val replacementChars: Int,
        /** 字段并集来自全量扫描时说清楚扫描了多少行 */
        val schemaScanLines: Int,
    ) {
        val sampled: Boolean get() = rowNumbers.size < totalRows
    }

    data class Sample(
        val columns: List<String>,
        val columnTypes: List<ColumnType>,
        val fields: List<Field>,
        val rows: List<List<String>>,
        val plan: Plan,
    )

    /**
     * 从 JSONL 文件抽样。
     *
     * @throws IllegalArgumentException 文件为空/超限/第一行就是坏行等可解释错误
     */
    fun sampleFile(file: File, targetRows: Int = Csv.MAX_ROWS): Sample {
        require(targetRows > 0) { "targetRows 必须为正数" }
        if (!file.isFile) throw IllegalArgumentException("文件不存在：${file.path}")
        if (file.length() > MAX_FILE_BYTES) {
            throw IllegalArgumentException(
                "文件过大（${file.length() / 1024 / 1024}MB，上限 ${MAX_FILE_BYTES / 1024 / 1024}MB）：" +
                    "请先拆分文件或导出为更小的批次",
            )
        }

        val encoding = TextEncoding.detect(file)
        val digest = MessageDigest.getInstance("SHA-256")
        val keyOrder = LinkedHashSet<String>()
        val numeric = mutableMapOf<String, Boolean>()
        val nested = mutableSetOf<String>()
        var replacementChars = 0

        val firstPass = scan(file, encoding, digest) { rowIndex, line ->
            replacementChars += line.count { it == '\uFFFD' }
            val obj = parseObject(line, rowIndex)
            obj.keys.forEach { key ->
                keyOrder.add(key)
                val text = valueToText(obj[key])
                if (text.isNotBlank()) {
                    val ok = Csv.parseNumber(text) != null
                    numeric[key] = (numeric[key] ?: true) && ok
                } else if (!numeric.containsKey(key)) {
                    numeric[key] = true
                }
                if (obj[key] is JsonObject || obj[key] is JsonArray) nested.add(key)
            }
        }
        val blankLines = firstPass.blankLines
        val totalRows = firstPass.rows

        if (totalRows <= 0) {
            throw IllegalArgumentException("文件里没有非空 JSON 行，无法分析（JSONL 要求每行一个 JSON 对象）")
        }
        if (keyOrder.isEmpty()) {
            throw IllegalArgumentException("所有 JSON 行都是空对象（{}），没有可分析的字段")
        }

        val rawKeys = keyOrder.toList()
        val columns = rawKeys.mapIndexed { index, key -> key.ifBlank { "列${index + 1}" } }
        val fields = rawKeys.mapIndexed { index, key ->
            Field(
                name = columns[index],
                type = if (numeric[key] == true && key !in nested) ColumnType.NUMBER else ColumnType.TEXT,
                nested = key in nested,
                numeric = numeric[key] == true,
            )
        }

        val step = ((totalRows + targetRows - 1) / targetRows).coerceAtLeast(1)
        val picked = ArrayList<MutableList<String>>(minOf(totalRows, targetRows))
        val rowNumbers = ArrayList<Int>(minOf(totalRows, targetRows))

        scan(file, encoding, null) { rowIndex, line ->
            if (line.isBlank()) return@scan
            val dataIndex = rowIndex - 1 // 0 起
            if (dataIndex % step != 0) return@scan
            val obj = parseObject(line, rowIndex)
            val row = MutableList(columns.size) { "" }
            rawKeys.forEachIndexed { index, key -> row[index] = valueToText(obj[key]) }
            picked.add(row)
            rowNumbers.add(rowIndex)
        }

        if (picked.size != rowNumbers.size) {
            throw IllegalStateException("抽样行数与解析结果不一致：文件可能在读取过程中被修改，请重试")
        }

        return Sample(
            columns = columns,
            columnTypes = fields.map { it.type },
            fields = fields,
            rows = picked,
            plan = Plan(
                fingerprint = "sha256:" + digest.digest().joinToString("") { "%02x".format(it) },
                sourceBytes = file.length(),
                totalRows = totalRows,
                targetRows = targetRows,
                step = step,
                rowNumbers = rowNumbers,
                encoding = encoding,
                blankLines = blankLines,
                replacementChars = replacementChars,
                schemaScanLines = totalRows,
            ),
        )
    }

    private data class ScanStats(val rows: Int, val blankLines: Int)

    /** 逐条非空逻辑行回调 `(第 N 个非空行, 行文本)`；[digest] 非 null 时把原始字节喂给它。 */
    private fun scan(
        file: File,
        encoding: EncodingDetection,
        digest: MessageDigest?,
        onLine: (Int, String) -> Unit,
    ): ScanStats {
        val raw = BufferedInputStream(file.inputStream(), 64 * 1024)
        val counted = if (digest == null) raw else DigestInputStream(raw, digest)
        val reader: BufferedReader = InputStreamReader(counted, encoding.charset).buffered(64 * 1024)
        var blankLines = 0
        try {
            var rowIndex = 0
            var first = true
            while (true) {
                var line = reader.readLine() ?: break
                if (first) {
                    first = false
                    if (line.startsWith('\uFEFF')) line = line.removePrefix("\uFEFF")
                }
                if (line.isBlank()) {
                    blankLines += 1
                    continue
                }
                rowIndex += 1
                onLine(rowIndex, line)
            }
            return ScanStats(rows = rowIndex, blankLines = blankLines)
        } finally {
            reader.close()
        }
    }

    private fun parseObject(line: String, rowIndex: Int): JsonObject {
        val element: JsonElement = try {
            Json.parseToJsonElement(line)
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "第 $rowIndex 个非空行不是合法 JSON：${e.message?.lineSequence()?.firstOrNull() ?: "解析失败"}" +
                    "（行首：${preview(line)}）",
            )
        }
        return element as? JsonObject ?: throw IllegalArgumentException(
            "第 $rowIndex 个非空行是 JSON ${typeName(element)}，JSONL 要求每行是一个 JSON 对象（{...}）" +
                "（行首：${preview(line)}）",
        )
    }

    private fun valueToText(element: JsonElement?): String = when (element) {
        null, is JsonNull -> ""
        is JsonObject, is JsonArray -> element.toString()
        is JsonPrimitive -> element.content
        else -> element.toString()
    }

    private fun typeName(element: JsonElement): String = when (element) {
        is JsonArray -> "数组"
        is JsonPrimitive -> if (element.isString) "字符串" else "标量"
        else -> "对象"
    }

    private fun preview(line: String): String =
        if (line.length <= 80) line else line.take(80) + "…"
}
