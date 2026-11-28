package com.stragent.host

import com.stragent.core.config.Config
import com.stragent.core.ports.AgentTool
import com.stragent.core.csv.ColumnType
import com.stragent.core.dataset.Dataset
import com.stragent.core.dataset.TextContent
import com.stragent.core.dataset.TextSegment
import com.stragent.core.dataset.validRowsAndDensity
import com.stragent.core.io.TextEncoding
import com.stragent.data.AbilityDatasets
import com.stragent.data.SessionRegistry
import com.stragent.data.rowsToTable
import com.stragent.data.parseDocxBody
import com.stragent.data.datasetIdOf
import com.stragent.core.ai.TextChunker
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files

/** 文本数据集约束（与 TextSummarizer 的 TEXT_* 同源，导入侧先落一道闸） */
private const val TEXT_IMPORT_MAX_BYTES = 32L * 1024 * 1024 // 文件 ≤32MB // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
// T-098：段数上限 env 可调（STRAGENT_TEXT_MAX_SEGMENTS，下限 100）；字符上限复用 core 的
// TEXT_MAX_MATERIAL_CHARS（env STRAGENT_TEXT_MAX_CHARS）——同一份材料的两侧闸门同源，不各配各的。
private val TEXT_IMPORT_MAX_SEGMENTS =
    Config.get("STRAGENT_TEXT_MAX_SEGMENTS")?.toIntOrNull()?.coerceAtLeast(100) ?: 5_000
private val TEXT_IMPORT_MAX_CHARS = com.stragent.core.ai.TEXT_MAX_MATERIAL_CHARS
private const val TEXT_IMPORT_MAX_PROJECTION_ROWS = 200  // 表格投影行数上限（超出如实标注截断） // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
private const val TEXT_IMPORT_MAX_CELL_CHARS = 300       // 投影单元格展示上限 // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）

/**
 * `stragent_import_text`——正文/混合内容导入为「文本数据集」（T-096 材料模型）。
 *
 * 支持四种格式：
 * - `.txt` / `.md`：原样解码（编码自动检测，BOM 优先 + UTF8/GB18030 探测），空行分段；**md 原样解析，内嵌表格不拆出**；
 * - `.docx`：按文档顺序**全量抽取**——表格外段落进 text 层；全部表格都被读入：行数最多的
 *   一张表（首最大）进表格部分（可统计），其余表序列化为 Markdown **表格投影段**进 text 层
 *   （整体不切断、投影仅供语义阅读，统计走表格部分）；嵌套表格压平并入所在单元格（标「[表: …]」）；
 *   不支持 `.doc`（老二进制格式）；
 * - `.pdf`：**仅取文本层**（纯 JDK 提取，不做表格抽取）——扫描件/加密 PDF 会如实报错；复杂版式可能不准。
 *
 * 三态产物：纯文本（kind=text，rows 空）/ 混合（kind=mixed）/ 纯表格（kind=table，text 空——
 * docx 只有表格时的合法形态）。纯表格时文本总结不可用（`stragent_summarize_text`），
 * 表格分析照常可用；纯文本时表格分析工具不可用，请用总结/追问/综述报告。
 *
 * 幂等：datasetId = 文件字节指纹（同一文件重复导入返回同一 id；重新编码 = 不同 id，如实）。
 */
fun importTextTool(
    datasets: AbilityDatasets,
    registry: SessionRegistry? = null, // 会话指针推进在 dispatch 层（McpTaskDispatch）；本参数保留与 import_file 同款签名 // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
    /** OCR 视觉通道（T-115）：宿主在 env STRAGENT_OCR=1 时传入；null = 扫描件保持既有诚实报错 */
    ocrVision: com.stragent.core.ports.VisionClient? = null,
    /** 导入后预热回调（T-132）：宿主在 env STRAGENT_TEXT_WARMUP=1 时传入；null = 不预热 */
    warmup: ((com.stragent.core.dataset.Dataset) -> Unit)? = null,
): AgentTool = object : AgentTool {
    override val name = "stragent_import_text"
    override val description =
        "导入正文类内容（txt / md / docx / pdf）为文本数据集——表格+正文以任意比例共存。" + // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            "docx 按文档顺序全量抽取段落与全部表格，行数最多的表可统计，其余表进 text 层投影；" + // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            "pdf 优先取文本层；扫描件在宿主开启 OCR（STRAGENT_OCR=1）时逐页视觉转写。返回材料概况（segments/chars/projections/ocr/表格部分行列）；" + // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            "纯文本数据集请用 stragent_summarize_text 总结（表格分析工具不可用）。" // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put("filePath", buildJsonObject {
                    put("type", "string")
                    put("description", "文件路径：.txt / .md / .docx / .pdf；也支持 s3:// / gs:// / https:// 地址") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                })
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "数据集名称（缺省为文件名）") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                })
            },
        )
        put("required", JsonArray(listOf(JsonPrimitive("filePath"))))
    }

    override val cacheable = false

    // 导入会新增数据集并推进会话指针（不改外部文件，故 destructive=false）
    override val readOnly = false

    override fun invoke(args: JsonObject): JsonObject {
        val filePath = args.string("filePath")
            ?: throw IllegalArgumentException("filePath 不能为空") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        val explicitName = args.string("name")

        if (ObjectStorage.isRemote(filePath)) {
            val tempDir = Files.createTempDirectory("str-agent-import-text-").toFile()
            try {
                val fetched = ObjectStorage.fetch(filePath, tempDir)
                val remote = RemoteSource(fetched.sourceUri, fetched.store, fetched.bytes)
                val name = explicitName ?: fetched.suggestedName.ifBlank { "remote_text" }
                return importTextLocal(fetched.file, name, fetched.extension.lowercase(), datasets, remote, ocrVision, warmup)
            } finally {
                tempDir.deleteRecursively()
            }
        }

        val file = File(filePath)
        if (!file.exists()) {
            throw IllegalArgumentException("文件不存在：$filePath") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        }
        val name = explicitName ?: file.nameWithoutExtension.ifBlank { "text_dataset" }
        return importTextLocal(file, name, file.extension.lowercase(), datasets, null, ocrVision, warmup)
    }
}

private val TEXT_IMPORT_EXTS = setOf("txt", "md", "markdown", "docx", "pdf")

/** 按扩展名分发的本地链路（远程下载后也走这里） */
private fun importTextLocal(
    file: File,
    rawName: String,
    ext: String,
    datasets: AbilityDatasets,
    remote: RemoteSource?,
    ocrVision: com.stragent.core.ports.VisionClient? = null,
    warmup: ((com.stragent.core.dataset.Dataset) -> Unit)? = null,
): JsonObject {
    val normalizedExt = ext.lowercase()
    if (normalizedExt.isEmpty()) {
        throw IllegalArgumentException("无法判断文件格式（路径没有扩展名）：${file.name}；请带上 .txt / .md / .docx / .pdf 扩展名") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
    }
    if (normalizedExt !in TEXT_IMPORT_EXTS) {
        throw IllegalArgumentException(
            "不支持的文件格式「.$normalizedExt」：stragent_import_text 只支持 .txt / .md / .docx / .pdf。" + // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                "表格类文件（csv/xlsx/ods…）请用 stragent_import_file。", // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        )
    }
    if (file.length() > TEXT_IMPORT_MAX_BYTES) {
        throw IllegalArgumentException("文件过大（${file.length() / 1024 / 1024}MB），上限 32MB。") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
    }
    val name = rawName.ifBlank { file.nameWithoutExtension.ifBlank { "text_dataset" } }

    // ---- 第一步：抽段（paragraphs + 投影段，未归一化索引）----
    val rawSegments = mutableListOf<Pair<TextSegment.Kind, String>>()
    var maxTableParsed: com.stragent.core.csv.ParsedTable? = null
    var maxTableOrdinal = 0
    val tableProjectionNotes = mutableListOf<String>()
    var ocrUsed = false
    var importedBytes: ByteArray? = null

    when (normalizedExt) {
        "txt", "md", "markdown" -> {
            val detection = TextEncoding.detect(file)
            // T-130 单次读：bytes 同时供解码与指纹哈希（原实现 readText+readBytes 双读全文件）
            val fileBytes = file.readBytes()
            importedBytes = fileBytes
            val text = String(fileBytes, detection.charset).removePrefix("\uFEFF")
            text.split(Regex("\\n\\s*\\n"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { rawSegments += TextSegment.Kind.PARAGRAPH to it }
        }
        "docx" -> {
            val elements = file.inputStream().use { parseDocxBody(it) }
            val tables = elements.filterIsInstance<com.stragent.data.DocxBodyElement.Table>()
            // 最大数据表 = 首最大（行数并列时取文档序最靠前）
            val maxTable = tables.filter { it.rows.size >= 2 }
                .fold<com.stragent.data.DocxBodyElement.Table, com.stragent.data.DocxBodyElement.Table?>(null) { best, t ->
                    if (best == null || t.rows.size > best.rows.size) t else best
                }
            if (maxTable != null) {
                maxTableOrdinal = tables.indexOfFirst { it === maxTable } + 1
                // 解析失败（如表头全空）→ 降级为投影，不阻塞导入
                maxTableParsed = runCatching { rowsToTable(maxTable.rows) }.getOrNull()
                if (maxTableParsed == null) {
                    tableProjectionNotes += "最大表解析失败，已作为表格投影保留在 text 层" // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                }
            }
            var tableOrdinal = 0
            for (element in elements) {
                when (element) {
                    is com.stragent.data.DocxBodyElement.Paragraph ->
                        rawSegments += TextSegment.Kind.PARAGRAPH to element.text
                    is com.stragent.data.DocxBodyElement.Table -> {
                        tableOrdinal += 1
                        if (element === maxTable && maxTableParsed != null) continue // 已进表格部分 // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                        rawSegments +=
                            TextSegment.Kind.TABLE_PROJECTION to
                                projectionMarkdown(tableOrdinal, element.rows, tableProjectionNotes)
                    }
                }
            }
        }
        "pdf" -> {
            val ocr = extractPdfTextWithOcr(file, ocrVision)
            val lines = ocr.lines
            if (lines.isEmpty()) {
                throw IllegalArgumentException(
                    "PDF 中没有提取到文本。可能原因：扫描件（需 OCR）、加密 PDF、或纯图片 PDF。", // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                )
            }
            ocrUsed = ocr.ocrUsed
            // PDF 每视觉行一段——页脚过滤后按句终符归并到段落粒度
            //（与 docx 段落同构；否则 268 页文档抽出 9383 行直接超段数上限）。
            mergePdfParagraphs(lines).forEach { rawSegments += TextSegment.Kind.PARAGRAPH to it }
        }
    }

    if (rawSegments.isEmpty() && maxTableParsed == null) {
        throw IllegalArgumentException("这份文档里没有可抽取的内容（无正文段落也无表格）。") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
    }

    // ---- 第二步：归一化 + 规模纪律 ----
    val preNormalized = rawSegments.mapIndexed { index, (kind, text) -> TextSegment(index + 1, text, kind) }
    if (preNormalized.size > TEXT_IMPORT_MAX_SEGMENTS) {
        throw IllegalArgumentException(
            "材料解析出 ${preNormalized.size} 个段落，超过上限 $TEXT_IMPORT_MAX_SEGMENTS——请拆分导入。", // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        )
    }
    val totalChars = preNormalized.sumOf { it.text.length }
    if (totalChars > TEXT_IMPORT_MAX_CHARS) {
        throw IllegalArgumentException(
            "材料总 $totalChars 字符，超过上限 $TEXT_IMPORT_MAX_CHARS——请拆分导入。", // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        )
    }
    val normalized = TextChunker.normalizeSegments(preNormalized)
    val text = if (normalized.isEmpty()) null else TextContent(normalized)

    // ---- 第三步：组装数据集（rows=最大表；text=段落+其余表投影）----
    val table = maxTableParsed
    val dataset = Dataset(
        name = name,
        importedAt = System.currentTimeMillis(),
        columns = table?.columns ?: emptyList(),
        rows = table?.rows ?: emptyList(),
        columnTypes = table?.columnTypes ?: emptyList(),
        sourceRows = table?.rows?.size ?: 0,
        text = text,
    )
    val bytes = importedBytes ?: file.readBytes()
    val datasetId = datasetIdOf(
        "text|$normalizedExt|" + String(bytes, Charsets.ISO_8859_1),
    )
    datasets.put(datasetId, dataset)
    // T-132 导入后预热：daemon 线程后台构建索引/ctx/摘要（失败静默，不影响导入回执）
    if (warmup != null && dataset.text != null) {
        val ds = dataset
        Thread({
            try { warmup(ds) } catch (_: Exception) { }
        }, "str-agent-warmup").apply { isDaemon = true; start() }
    }
    val (validRows, density) = validRowsAndDensity(dataset)

    // ---- 第四步：注入扫描（text 层逐段扫；纯表格退化为整表扫）----
    val scan = if (text != null) {
        // T-130 短路：凑满 10 条即停（原实现 take(10) 在 flatMap 之后，全量段落照扫）
        val signals = buildList {
            for (seg in text.segments) {
                addAll(com.stragent.core.security.PromptInjectionHeuristics.scanText(seg.text, "段 ${seg.index}"))
                if (size >= 10) break
            }
        }.take(10)
        com.stragent.core.security.InjectionScanReport(text.segments.size, signals)
    } else {
        com.stragent.core.security.PromptInjectionHeuristics.scanDataset(dataset)
    }

    val kind = when {
        table == null && text != null -> "text"
        table != null && text != null -> "mixed"
        else -> "table"
    }
    val paragraphs = text?.paragraphCount ?: 0
    val projections = text?.segments?.size?.minus(paragraphs) ?: 0
    val notices = mutableListOf<String>()
    if (kind == "text") notices += "纯文本数据集：表格分析工具不可用，请用 stragent_summarize_text 总结正文（综述报告/追问同样可用）" // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
    if (kind == "table") notices += "这份文档只有表格没有正文——文本总结不可用，表格分析可用" // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
    if (normalizedExt == "pdf") notices += "PDF 仅取文本层，不做表格抽取；复杂版式可能不准，明细需人工核对" // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
    if (normalizedExt in setOf("md", "markdown")) notices += "md 原样解析，内嵌表格未拆出（单元格统计请改导 .csv / .xlsx）" // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
    notices += tableProjectionNotes

    return buildJsonObject {
        put("datasetId", datasetId)
        put("name", dataset.name)
        put("sourceFormat", normalizedExt)
        put("kind", kind)
        put("rows", dataset.rows.size)
        put("columns", dataset.columns.size)
        if (dataset.columns.isNotEmpty()) {
            put(
                "columnList",
                JsonArray(
                    dataset.columns.take(50).mapIndexed { index, columnName ->
                        buildJsonObject {
                            put("name", columnName)
                            put("type", if (dataset.columnTypes[index] == ColumnType.NUMBER) "number" else "text")
                        }
                    },
                ),
            )
            put("columnListTruncated", dataset.columns.size > 50)
        }
        put("validRows", validRows)
        put("density", density)
        put(
            "text",
            buildJsonObject {
                put("segments", text?.segments?.size ?: 0)
                put("paragraphs", paragraphs)
                put("projections", projections)
                put("chars", text?.totalChars ?: 0)
            },
        )
        // T-115 OCR 兜底回显：true = 文本层抽取失败后走了逐页视觉转写（成本与来源如实区分）
        put("ocr", ocrUsed)
        if (table != null) {
            put(
                "maxTable",
                buildJsonObject {
                    put("tableOrdinal", maxTableOrdinal)
                    put("rows", table.rows.size)
                    put("columns", table.columns.size)
                },
            )
        }
        put("injectionScan", com.stragent.data.StrAgentAbilities.injectionScanJson(scan))
        if (notices.isNotEmpty()) put("notice", notices.joinToString("；"))
        addRemote(remote)
        datasets.persistedState(datasetId)?.let { put("persisted", it) }
    }
}

/**
 * 表格 → Markdown 投影（行业「统一表格块」纪律：表名+行数上下文，整体成块不切断）。
 * 投影仅供语义阅读，不是统计口径；超过 [TEXT_IMPORT_MAX_PROJECTION_ROWS] 行数据如实截断并标注。
 */
private fun projectionMarkdown(
    tableOrdinal: Int,
    table: List<List<String>>,
    notes: MutableList<String>,
): String {
    val width = table.maxOfOrNull { it.size } ?: 0
    if (width == 0 || table.isEmpty()) {
        notes += "第 $tableOrdinal 个表格为空，已跳过" // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        return "【表$tableOrdinal】（空表，已跳过）" // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
    }
    fun cell(s: String): String =
        s.replace('|', '¦').replace('\n', ' ').replace('\r', ' ').trim()
            .let { if (it.length > TEXT_IMPORT_MAX_CELL_CHARS) it.take(TEXT_IMPORT_MAX_CELL_CHARS) + "…" else it }
    val header = (0 until width).map { cell(table.first().getOrNull(it).orEmpty()) }
    val body = table.drop(1)
    val shown = body.take(TEXT_IMPORT_MAX_PROJECTION_ROWS)
    val truncated = body.size > shown.size
    if (truncated) notes += "第 $tableOrdinal 个表格投影仅列出前 $TEXT_IMPORT_MAX_PROJECTION_ROWS 行（共 ${body.size} 行）；精确统计需导入原表" // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
    return buildString {
        append("【表").append(tableOrdinal).append("】共 ").append(table.size).append(" 行（投影仅供语义阅读）\n") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        append("| ").append(header.joinToString(" | ")).append(" |\n")
        append("|").append("---|".repeat(width)).append('\n')
        for (row in shown) {
            append("| ").append((0 until width).joinToString(" | ") { cell(row.getOrNull(it).orEmpty()) }).append(" |\n")
        }
        if (truncated) append("…（投影仅列出前 $TEXT_IMPORT_MAX_PROJECTION_ROWS 行，共 ${body.size} 行）") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
    }.trimEnd()
}

/**
 * PDF 行归并段落：①过滤「第 N 页 / 共 M 页」页脚；②按句终符（。！？；）或
 * 800 字兆限聚合行到段落粒度。CJK 边界直接拼接（行末断句无空格），
 * 拉丁/数字边界补空格（保护英文表格列间距）。
 */
internal fun mergePdfParagraphs(lines: List<String>): List<String> {
    val body = lines.filterNot { Regex("^第 \\d+ 页 / 共 \\d+ 页$").matches(it) }
    val out = ArrayList<String>()
    val sb = StringBuilder()
    fun flush() {
        if (sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0) }
    }
    for (line in body) {
        if (sb.isNotEmpty()) {
            val prev = sb.last()
            val next = line.first()
            if (prev.code in 0x20..0x7E && next.code in 0x20..0x7E) sb.append(' ')
        }
        sb.append(line)
        val last = sb.last()
        if (sb.length >= 800 || last == '。' || last == '！' || last == '？' || last == '；') flush()
    }
    flush()
    return out.filter { it.isNotBlank() }
}
