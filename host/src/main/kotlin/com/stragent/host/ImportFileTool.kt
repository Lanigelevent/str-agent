package com.stragent.host

import com.stragent.core.config.Config
import com.stragent.core.ports.AgentTool
import com.stragent.core.csv.Csv
import com.stragent.core.csv.ColumnType
import com.stragent.core.dataset.Dataset
import com.stragent.core.dataset.SamplingInfo
import com.stragent.core.dataset.validRowsAndDensity
import com.stragent.core.io.TextEncoding
import com.stragent.core.jsonl.JsonlSampler
import com.stragent.data.AbilityDatasets
import com.stragent.data.SessionRegistry
import com.stragent.data.datasetIdOf
import com.stragent.data.parseXlsxFirst
import com.stragent.data.parseOdsFirst
import com.stragent.data.parseDocxTables
import com.stragent.data.parseFlatOds
import com.stragent.data.datasetFromTable
import com.stragent.data.tableToCsv
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/**
 * 多格式导入工具 —— D10 阶段 4 多格式输入落地；T-054 起接大 CSV 文件；T-058 接 JSONL / Parquet / 对象存储。
 *
 * 支持：
 *  - 本地 `.csv` / `.tsv` / `.txt`（确定性抽样 + 编码探测，百万行可分析）
 *  - 本地 `.jsonl` / `.ndjson`（全量字段并集 + 编码探测 + 坏行定位）
 *  - 本地 `.parquet`（DuckDB `read_parquet`，schema 直接来自文件，不猜）
 *  - 对象存储只读：`s3://bucket/key` / `gs://bucket/key` / `https://…`（先落临时文件，再走同一导入链路）
 *  - 本地 `.xlsx` / `.xls` / `.ods` / `.fods` / `.docx`（既有零依赖解析器）
 *  - 纯文本 PDF 的表格提取（独立工具 `stragent_import_pdf`）
 *
 * 复用已有的零依赖解析器（ZIP + JDK SAX + kotlinx-serialization）与宿主的 DuckDB 引擎，
 * 不引入 Apache POI / PDFBox / 新的云 SDK。
 *
 * 诚实边界：
 *  - 大文件按**确定性系统抽样**取 1 万行：报告里的统计是样本口径，不是全量；
 *    文件指纹与样本→源行号映射随数据集返回，数字可回链（T-054）
 *  - 编码探测只覆盖 UTF-8 / UTF-8(BOM) / UTF-16(BOM) / GB18030，其余回落 ISO-8859-1 并明说
 *  - JSONL 每行必须是对象；坏行直接失败并给出行号，不做「跳过坏行」的静默处理
 *  - Parquet 不处理目录/多文件数据集；嵌套类型按 VARCHAR 形式入表，不展开
 *  - 对象存储只支持 GET；S3 凭据只从环境变量/系统属性读，GCS 仅公开对象（私有桶用预签名 https）
 *  - PDF 只做纯文本行级提取（不做 PDFBox 级的表格结构解析）
 *  - docx 只取最大表格（与 OdfDocxParser 既有行为一致）；不支持 .doc（老格式）
 *  - 导入后格式统一为内存数据集（与 stragent_import_table 同一注册表）
 */
fun importFileTool(
    datasets: AbilityDatasets,
    registry: SessionRegistry? = null,
): AgentTool = object : AgentTool {
    override val name = "stragent_import_file"
    override val description =
        "导入 csv / tsv / txt / jsonl / ndjson / parquet / xlsx / xls / ods / fods / docx 文件为数据集，" +
            "也支持对象存储只读地址（s3://bucket/key、gs://bucket/key、https://…，先下载到本机再导入）。" +
            "自动解析表格并完成统计分析，返回数据集编号与列清单。与 stragent_import_table 同一通道——同一内容重复导入返回同一 datasetId。" +
            "大表（超过 1 万行）自动做**确定性系统抽样**并回报文件指纹与行号映射，数字只代表样本口径。" +
            "文本源会回报探测到的编码与依据；JSONL 回报全量字段并集与坏行行号；Parquet 回报文件自带 schema。" +
            "支持多工作表（xlsx/ods 取第一个表）、docx 取最大表格。"
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put("filePath", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "文件路径（.csv / .tsv / .txt / .jsonl / .ndjson / .parquet / .xlsx / .xls / .ods / .fods / .docx）" +
                            "或对象存储地址（s3:// / gs:// / https://），必填",
                    )
                })
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "数据集名称（缺省为文件名）")
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
            ?: throw IllegalArgumentException("filePath 不能为空")
        val explicitName = args.string("name")

        if (ObjectStorage.isRemote(filePath)) {
            val tempDir = Files.createTempDirectory("str-agent-import-").toFile()
            try {
                val fetched = ObjectStorage.fetch(filePath, tempDir)
                val remote = RemoteSource(fetched.sourceUri, fetched.store, fetched.bytes)
                val name = explicitName ?: fetched.suggestedName.ifBlank { "remote_dataset" }
                return importLocal(fetched.file, name, fetched.extension, datasets, remote)
            } finally {
                tempDir.deleteRecursively()
            }
        }

        val file = File(filePath)
        if (!file.exists()) {
            throw IllegalArgumentException("文件不存在：$filePath")
        }

        val name = explicitName ?: file.nameWithoutExtension
        return importLocal(file, name, file.extension.lowercase(), datasets, null)
    }
}

/** 远程来源信息（只进回包的 `source` 块；查询串已在下载层去掉） */
internal data class RemoteSource(val uri: String, val store: String, val bytes: Long)

/** 按扩展名分发：本地文件与下载后的远程对象走同一条链路 */
private fun importLocal(
    file: File,
    rawName: String,
    ext: String,
    datasets: AbilityDatasets,
    remote: RemoteSource?,
): JsonObject {
    val name = rawName.ifBlank { file.nameWithoutExtension.ifBlank { "dataset" } }
    val normalized = ext.lowercase()
    if (normalized.isEmpty()) {
        throw IllegalArgumentException(
            "无法判断文件格式（路径没有扩展名）：${file.name}；请把文件名/对象路径带上 .csv / .jsonl / .parquet 等扩展名",
        )
    }
    if (normalized in SAMPLED_EXTS) {
        return importSampledText(file, name, normalized, datasets, remote)
    }
    when (normalized) {
        "jsonl", "ndjson" -> return importJsonl(file, name, normalized, datasets, remote)
        "parquet" -> return importParquet(file, name, datasets, remote)
    }

    if (file.length() > 64L * 1024 * 1024) {
        throw IllegalArgumentException("文件过大（${file.length() / 1024 / 1024}MB），上限 64MB。")
    }

    val table = when (normalized) {
        "xlsx", "xls" -> {
            file.inputStream().use { parseXlsxFirst(it) }
        }
        "ods" -> {
            file.inputStream().use { parseOdsFirst(it) }
        }
        "fods" -> {
            file.inputStream().use { parseFlatOds(it).firstOrNull()?.table }
                ?: throw IllegalArgumentException("这份 fods 里没有工作表")
        }
        "docx" -> {
            file.inputStream().use { parseDocxTables(it) }
        }
        else -> {
            throw IllegalArgumentException(
                "不支持的文件格式「.$normalized」：请用 .csv / .tsv / .txt / .jsonl / .ndjson / .parquet / " +
                    ".xlsx / .xls / .ods / .fods / .docx 格式，或 s3:// / gs:// / https:// 地址。",
            )
        }
    }

    val dataset = datasetFromTable(name, System.currentTimeMillis(), table)
        ?: throw IllegalArgumentException("文件解析成功但数据为空（没有有效行或列）。")

    // 转为 CSV 文本用于幂等键（与 stragent_import_table 同一通道）
    val csv = tableToCsv(table)
    val datasetId = datasetIdOf(csv)
    datasets.put(datasetId, dataset)
    val (validRows, density) = validRowsAndDensity(dataset)

    return buildJsonObject {
        put("datasetId", datasetId)
        put("name", dataset.name)
        put("sourceFormat", normalized)
        put("rows", dataset.sourceRows)
        put("columns", dataset.columns.size)
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
        put("truncated", dataset.truncated)
        put("validRows", validRows)
        put("density", density)
        put("injectionScan", com.stragent.data.StrAgentAbilities.injectionScanJson(com.stragent.core.security.PromptInjectionHeuristics.scanDataset(dataset)))
        addRemote(remote)
        datasets.persistedState(datasetId)?.let { put("persisted", it) }
    }
}

/** 走确定性抽样的纯文本表格扩展名 */
private val SAMPLED_EXTS = setOf("csv", "tsv", "txt")

/**
 * 大文本表格导入（T-054 / T-058）：.csv / .tsv / .txt 走确定性系统抽样，并按探测到的编码解码。
 *
 * 为什么单独一条：`stragent_import_table` 的入参是**表格文本**，百万行进不了 JSON；
 * 这一路按路径流式扫描两遍（数行 + 算指纹 → 收集样本行），JVM 堆里只放 1 万行样本。
 * 同一文件 + 同一目标行数 → 同一 datasetId（幂等）；文件改一个字节 → 指纹变、行号映射
 * 旧报告自然对不上，不需要额外的失效逻辑。
 *
 * 编码（T-058）：先探测再解码，回包里的 `encoding` 给出编码名与判定依据；
 * 同一份字节在 UTF-8 与 GB18030 下解析出的表可能不同，用户必须看得见用的是哪套。
 */
private fun importSampledText(
    file: File,
    name: String,
    ext: String,
    datasets: AbilityDatasets,
    remote: RemoteSource?,
): JsonObject {
    val detection = TextEncoding.detect(file)
    val sample = com.stragent.core.csv.CsvSampler.sampleFile(file, charset = detection.charset)
    val plan = sample.plan
    // 幂等键：指纹 + 抽样参数（不是样本文本——不同目标行数的样本不该共用一份数据集）
    val datasetId = com.stragent.data.datasetIdOf(
        "${plan.fingerprint}|${SamplingInfo.METHOD_SYSTEMATIC}|${plan.targetRows}",
    )
    val sampling = if (plan.sampled) {
        SamplingInfo(
            targetRows = plan.targetRows,
            step = plan.step,
            totalRows = plan.totalRows,
            fingerprint = plan.fingerprint,
            rowNumbers = plan.rowNumbers,
        )
    } else {
        null
    }
    val dataset = Dataset(
        name = name,
        importedAt = System.currentTimeMillis(),
        columns = sample.columns,
        rows = sample.rows,
        columnTypes = sample.columnTypes,
        delimiter = sample.delimiter,
        truncated = plan.sampled,
        sourceRows = plan.totalRows,
        sampling = sampling,
    )
    datasets.put(datasetId, dataset)
    val (validRows, density) = validRowsAndDensity(dataset)

    return buildJsonObject {
        put("datasetId", datasetId)
        put("name", dataset.name)
        put("sourceFormat", ext)
        put("rows", dataset.sourceRows)
        put("analyzedRows", dataset.rows.size)
        put("columns", dataset.columns.size)
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
        put("truncated", dataset.truncated)
        put("validRows", validRows)
        put("density", density)
        put("injectionScan", com.stragent.data.StrAgentAbilities.injectionScanJson(com.stragent.core.security.PromptInjectionHeuristics.scanDataset(dataset)))
        put("fingerprint", plan.fingerprint)
        put("fileBytes", plan.sourceBytes)
        addEncoding(detection.name, detection.reason, bomBytes = detection.bomBytes)
        addRemote(remote)
        datasets.persistedState(datasetId)?.let { put("persisted", it) }
        if (sampling != null) {
            put(
                "sampling",
                buildJsonObject {
                    put("method", sampling.method)
                    put("step", sampling.step)
                    put("targetRows", sampling.targetRows)
                    put("sampleRows", dataset.rows.size)
                    put("totalRows", sampling.totalRows)
                    // 与 list_rows / 报告回执同一口径：抽样块自带走指纹，三处互相可核对
                    put("fingerprint", sampling.fingerprint)
                    put(
                        "notice",
                        "大文件已做确定性系统抽样：每 ${sampling.step} 行取 1 行，共 ${dataset.rows.size} 行。",
                    )
                },
            )
            put(
                "notice",
                "本次分析只基于抽样出的 ${dataset.rows.size} 行（源文件 ${plan.totalRows} 行）——" +
                    "统计与报告都是**样本口径**，不是全量；行号回链见 stragent_list_rows 的 sourceRow。",
            )
        }
    }
}

/**
 * JSONL / NDJSON 导入（T-058）：每行一个 JSON 对象。
 *
 * 回包把「怎么读的」讲清楚：编码探测依据、全量字段并集、嵌套字段、坏行位置。
 * 抽样语义与 CSV 完全一致（固定步长、起点第 1 个非空行、指纹 = 文件字节 SHA-256）。
 */
private fun importJsonl(
    file: File,
    name: String,
    ext: String,
    datasets: AbilityDatasets,
    remote: RemoteSource?,
): JsonObject {
    val sample = JsonlSampler.sampleFile(file)
    val plan = sample.plan
    val datasetId = datasetIdOf("${plan.fingerprint}|${SamplingInfo.METHOD_SYSTEMATIC}|${plan.targetRows}")
    val sampling = if (plan.sampled) {
        SamplingInfo(
            targetRows = plan.targetRows,
            step = plan.step,
            totalRows = plan.totalRows,
            fingerprint = plan.fingerprint,
            rowNumbers = plan.rowNumbers,
        )
    } else {
        null
    }
    val dataset = Dataset(
        name = name,
        importedAt = System.currentTimeMillis(),
        columns = sample.columns,
        rows = sample.rows,
        columnTypes = sample.columnTypes,
        delimiter = ',',
        truncated = plan.sampled,
        sourceRows = plan.totalRows,
        sampling = sampling,
    )
    datasets.put(datasetId, dataset)
    val (validRows, density) = validRowsAndDensity(dataset)
    val blankNote = if (plan.blankLines > 0) "本次跳过 ${plan.blankLines} 个空白行；" else ""
    val jsonlNote = "JSONL 每行是一个对象；对象/数组值按紧凑 JSON 文本入表（不展开），空白行不计行号；$blankNote" +
        "字段并集来自全量扫描，行号 = 第 N 个非空 JSON 行。"

    return buildJsonObject {
        put("datasetId", datasetId)
        put("name", dataset.name)
        put("sourceFormat", ext)
        put("rows", dataset.sourceRows)
        put("analyzedRows", dataset.rows.size)
        put("columns", dataset.columns.size)
        put(
            "columnList",
            JsonArray(
                sample.fields.take(50).map { field ->
                    buildJsonObject {
                        put("name", field.name)
                        put("type", if (field.type == ColumnType.NUMBER) "number" else "text")
                        put("nested", field.nested)
                    }
                },
            ),
        )
        put("columnListTruncated", sample.fields.size > 50)
        put("truncated", dataset.truncated)
        put("validRows", validRows)
        put("density", density)
        put("injectionScan", com.stragent.data.StrAgentAbilities.injectionScanJson(com.stragent.core.security.PromptInjectionHeuristics.scanDataset(dataset)))
        put("fingerprint", plan.fingerprint)
        put("fileBytes", plan.sourceBytes)
        addEncoding(plan.encoding.name, plan.encoding.reason, plan.encoding.bomBytes, plan.replacementChars)
        put(
            "schema",
            buildJsonObject {
                put("source", "jsonl")
                put("inferredBy", "全量扫描 ${plan.schemaScanLines} 个非空 JSON 行，字段并集按首次出现排序")
                put(
                    "columns",
                    JsonArray(
                        sample.fields.take(200).map { field ->
                            buildJsonObject {
                                put("name", field.name)
                                put("type", if (field.type == ColumnType.NUMBER) "number" else "text")
                                put("nested", field.nested)
                                put("numeric", field.numeric)
                            }
                        },
                    ),
                )
                put("schemaTruncated", sample.fields.size > 200)
                put(
                    "nestedFields",
                    JsonArray(sample.fields.filter { it.nested }.map { JsonPrimitive(it.name) }),
                )
            },
        )
        addRemote(remote)
        datasets.persistedState(datasetId)?.let { put("persisted", it) }
        if (sampling != null) {
            put(
                "sampling",
                buildJsonObject {
                    put("method", sampling.method)
                    put("step", sampling.step)
                    put("targetRows", sampling.targetRows)
                    put("sampleRows", dataset.rows.size)
                    put("totalRows", sampling.totalRows)
                    put("fingerprint", sampling.fingerprint)
                    put(
                        "notice",
                        "大文件已做确定性系统抽样：每 ${sampling.step} 个非空 JSON 行取 1 行，共 ${dataset.rows.size} 行。",
                    )
                },
            )
            put(
                "notice",
                "本次分析只基于抽样出的 ${dataset.rows.size} 行（源文件 ${plan.totalRows} 个非空 JSON 行）——" +
                    "统计与报告都是**样本口径**，不是全量；行号回链见 stragent_list_rows 的 sourceRow。$jsonlNote",
            )
        } else {
            put("notice", jsonlNote)
        }
    }
}

/**
 * Parquet 导入（T-058）：schema 来自文件本身（DuckDB DESCRIBE），抽样与 CSV 同一语义。
 * 编码不适用——Parquet 是自描述二进制格式，回包明说这一点而不是留空。
 */
private fun importParquet(
    file: File,
    name: String,
    datasets: AbilityDatasets,
    remote: RemoteSource?,
): JsonObject {
    val result = ParquetImport.import(file, name)
    val dataset = result.dataset
    datasets.put(result.datasetId, dataset)
    val (validRows, density) = validRowsAndDensity(dataset)

    return buildJsonObject {
        put("datasetId", result.datasetId)
        put("name", dataset.name)
        put("sourceFormat", "parquet")
        put("rows", dataset.sourceRows)
        put("analyzedRows", dataset.rows.size)
        put("columns", dataset.columns.size)
        put(
            "columnList",
            JsonArray(
                result.columns.take(50).map { column ->
                    buildJsonObject {
                        put("name", column.name)
                        put("type", if (column.type == ColumnType.NUMBER) "number" else "text")
                        put("parquetType", column.parquetType)
                        put("nullable", column.nullable)
                    }
                },
            ),
        )
        put("columnListTruncated", result.columns.size > 50)
        put("truncated", dataset.truncated)
        put("validRows", validRows)
        put("density", density)
        put("injectionScan", com.stragent.data.StrAgentAbilities.injectionScanJson(com.stragent.core.security.PromptInjectionHeuristics.scanDataset(dataset)))
        put("fingerprint", result.fingerprint)
        put("fileBytes", result.sourceBytes)
        put(
            "schema",
            buildJsonObject {
                put("source", "parquet")
                put("inferredBy", "DuckDB DESCRIBE：直接读取 Parquet 自带类型，不做猜测")
                put(
                    "columns",
                    JsonArray(
                        result.columns.take(200).map { column ->
                            buildJsonObject {
                                put("name", column.name)
                                put("parquetType", column.parquetType)
                                put("type", if (column.type == ColumnType.NUMBER) "number" else "text")
                                put("nullable", column.nullable)
                            }
                        },
                    ),
                )
                put("schemaTruncated", result.columns.size > 200)
            },
        )
        put("encoding", "不适用：Parquet 是自描述二进制格式（schema 随文件走，无文本编码）")
        addRemote(remote)
        datasets.persistedState(result.datasetId)?.let { put("persisted", it) }
        if (result.sampled) {
            val sampling = dataset.sampling!!
            put(
                "sampling",
                buildJsonObject {
                    put("method", sampling.method)
                    put("step", sampling.step)
                    put("targetRows", sampling.targetRows)
                    put("sampleRows", dataset.rows.size)
                    put("totalRows", sampling.totalRows)
                    put("fingerprint", sampling.fingerprint)
                    put("notice", "Parquet 已按文件顺序做确定性系统抽样：每 ${sampling.step} 行取 1 行。")
                },
            )
            put(
                "notice",
                "本次分析只基于抽样出的 ${dataset.rows.size} 行（源文件 ${result.totalRows} 行）——" +
                    "统计与报告都是**样本口径**，不是全量；行号回链见 stragent_list_rows 的 sourceRow。",
            )
        }
    }
}

private fun JsonObjectBuilder.addEncoding(
    name: String,
    reason: String,
    bomBytes: Int = 0,
    replacementChars: Int = 0,
) {
    put(
        "encoding",
        buildJsonObject {
            put("name", name)
            put("reason", reason)
            put("bomBytes", bomBytes)
            put("replacementChars", replacementChars)
            if (replacementChars > 0) {
                put("warning", "解码时出现 $replacementChars 个替换字符（U+FFFD）：文件里可能有字节不属于探测出的编码")
            }
        },
    )
}

internal fun JsonObjectBuilder.addRemote(remote: RemoteSource?) {
    if (remote == null) return
    put(
        "source",
        buildJsonObject {
            put("uri", remote.uri)
            put("store", remote.store)
            put("bytes", remote.bytes)
            put("queryRedacted", true)
            put("notice", "远程对象已下载到本机临时文件后导入；临时文件在导入结束即删除，数据集本体已入库")
        },
    )
}

/**
 * PDF 文本导入工具 —— 从 PDF 文件提取文本行并转为数据集。
 *
 * 纯 JDK 实现（不依赖 PDFBox）：PDF 文件解析为字节流后，
 * 提取 BT/ET 文本块中的 Tj/TJ 操作的文本内容。
 *
 * 诚实边界：
 *  - 只提取纯文本行（不做表格结构解析）
 *  - 不支持扫描件（需要 OCR）
 *  - 不支持加密 PDF
 *  - 复杂布局（多栏、合并单元格）可能解析不准
 */
fun importPdfTool(
    datasets: AbilityDatasets,
    registry: SessionRegistry? = null,
    /** OCR 视觉通道（T-115）：宿主在 env STRAGENT_OCR=1 时传入；null = 扫描件保持既有诚实报错 */
    ocrVision: com.stragent.core.ports.VisionClient? = null,
): AgentTool = object : AgentTool {
    override val name = "stragent_import_pdf"
    override val description =
        "导入 PDF 文件为数据集。从 PDF 中提取文本行，按 Tab 或连续空格分割为表格。" +
        "适合结构化文本 PDF（如报表导出）。扫描件在宿主开启 OCR（STRAGENT_OCR=1）时逐页视觉转写；不支持加密 PDF。" +
        "返回数据集编号与列清单。"
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put("filePath", buildJsonObject {
                    put("type", "string")
                    put("description", "PDF 文件路径，必填")
                })
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "数据集名称（缺省为文件名）")
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
            ?: throw IllegalArgumentException("filePath 不能为空")
        val name = args.string("name") ?: File(filePath).nameWithoutExtension

        val file = File(filePath)
        if (!file.exists()) {
            throw IllegalArgumentException("文件不存在：$filePath")
        }

        val pdfExtract = extractPdfTextWithOcr(file, ocrVision)
        val textLines = pdfExtract.lines
        val ocrUsed = pdfExtract.ocrUsed
        if (textLines.isEmpty()) {
            throw IllegalArgumentException(
                "PDF 中没有提取到文本。可能原因：扫描件（需 OCR）、加密 PDF、或纯图片 PDF。",
            )
        }

        // 尝试按 Tab 或多空格分割为表格
        val rows = textLines.map { line ->
            val cells = if (line.contains("\t")) {
                line.split("\t")
            } else if (line.contains("  ")) {
                line.split(Regex(" {2,}"))
            } else {
                listOf(line.trim())
            }
            cells.map { it.trim() }.filter { it.isNotEmpty() }
        }.filter { it.isNotEmpty() }

        if (rows.size < 2) {
            throw IllegalArgumentException("PDF 提取到 ${rows.size} 行有效文本，不足 2 行（需要表头+数据）。")
        }

        val header = rows[0]
        val body = rows.drop(1).map { row ->
            header.indices.map { row.getOrNull(it) ?: "" }
        }
        val columnTypes = Csv.inferTypes(body, header.size)

        val dataset = Dataset(
            name = name,
            importedAt = System.currentTimeMillis(),
            columns = header,
            rows = body,
            columnTypes = columnTypes,
            delimiter = '\t',
            truncated = false,
            sourceRows = body.size,
        )

        // 转 CSV 用于幂等键
        val csvLines = mutableListOf(header.joinToString(",") { escapeCsv(it) })
        body.forEach { row -> csvLines.add(row.joinToString(",") { escapeCsv(it) }) }
        val csv = csvLines.joinToString("\n")
        val datasetId = datasetIdOf(csv)
        datasets.put(datasetId, dataset)
        val (validRows, density) = validRowsAndDensity(dataset)

        return buildJsonObject {
            put("datasetId", datasetId)
            put("name", dataset.name)
            put("sourceFormat", "pdf")
            put("ocr", ocrUsed)
            put("rows", dataset.sourceRows)
            put("columns", dataset.columns.size)
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
            put("validRows", validRows)
            put("density", density)
        put("injectionScan", com.stragent.data.StrAgentAbilities.injectionScanJson(com.stragent.core.security.PromptInjectionHeuristics.scanDataset(dataset)))
            put("notice", "PDF 文本提取按行分割，复杂布局可能不准。")
        }
    }

    private fun escapeCsv(field: String): String =
        if (field.contains(',') || field.contains('"') || field.contains('\n')) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }
}

/**
 * 从 PDF 文件提取文本行——复用 Apache PDFBox（Apache-2.0，行业标准解法）。
 *
 * 零依赖纪律的有记录例外（D4 P-139）：自研对象级解析器（含 FlateDecode 解压、
 * ToUnicode CMap bfchar/bfrange 解析、逐页内容流扫描）在 CID 字体的 Word 导出
 * PDF 上仍无法正确解码——真实材料（V1.11 总结报告 268 页）实测产出 14 段/1.08M
 * 字符/0 句号乱码，且逐字符拆分后仍不可用。PDFTextStripper 自动处理 CMap/CID/
 * 字体子集等全部编码问题。
 * 诚实边界：图片型扫描件仍不支持——抽取为空/不可读时如实报错并建议改用 docx/xlsx。
 */
internal fun extractPdfText(file: File): List<String> {
    val doc = org.apache.pdfbox.Loader.loadPDF(file)
    val text = try {
        val stripper = org.apache.pdfbox.text.PDFTextStripper()
        stripper.sortByPosition = true
        stripper.getText(doc)
    } finally {
        doc.close()
    }
    val lines = text.split('\n').map { cleanPdfText(it) }.filter { it.isNotBlank() }
    if (readableRatio(lines) < 0.5) {
        throw IllegalArgumentException(
            "PDF 中没有提取到可读文本（图片型扫描件或编码异常，超出文本层抽取能力）。建议改用原始 docx/xlsx 导入，或转存 txt 后导入。", // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        )
    }
    return lines
}

/** 可读字符（CJK 与 ASCII 可打印）占比——扫描件守门：抽出的是文本还是垃圾 */
private fun readableRatio(lines: List<String>): Double {
    val text = lines.joinToString("")
    if (text.isEmpty()) return 0.0
    var ok = 0
    for (c in text) {
        val isCjk = c.code in 0x4E00..0x9FFF || c.code in 0x3000..0x303F || c.code in 0xFF00..0xFFEF
        val isAsciiPrintable = c.code in 0x20..0x7E
        if (isCjk || isAsciiPrintable) ok++
    }
    return ok.toDouble() / text.length
}

/** 找匹配的右括号（处理嵌套转义） */


/** 解码 PDF 字符串（处理转义） */


/** 清理 PDF 提取文本中的杂讯 */
private fun cleanPdfText(s: String): String {
    return s.replace(Regex("\\s+"), " ").trim()
}

/** PDF 文本抽取结果：lines 与是否走了 OCR 兜底（回执如实区分两种来源） */
internal data class PdfExtractResult(val lines: List<String>, val ocrUsed: Boolean)

/** OCR 兜底默认页数上限：268 页真实文档 ≈ 268 次视觉调用，成本上限显式化（env 可调） */
private const val OCR_MAX_PAGES_DEFAULT = 100

/**
 * 扫描件 PDF 的视觉 OCR 兜底（T-115）——硬文档解析差距的落地。
 *
 * 文本层抽取先行（快速路径行为零变化）；仅当文本层失败（可读比 < 0.5 的诚实报错）且
 * 宿主装配了 OCR 视觉通道（env STRAGENT_OCR=1 + 视觉模型可用）时，才逐页栅格化
 * （PDFBox PDFRenderer，2x ≈144 DPI）→ 既有 VisionClient 逐页转写 → 文本行。
 * 零新依赖（PDFBox 已在 D4 P-139 记录为例外依赖）。
 *
 * 成本纪律：OCR 是**每页一次视觉调用**——页数上限 [maxPages]（env STRAGENT_OCR_MAX_PAGES，
 * 默认 100）显式兜底，超出报中文错并指引调高；未启用/不可用时保持既有诚实报错（建议 docx/txt）。
 * 诚实边界：视觉转写是生成物——其数字同样要过下游门禁（材料回填/白名单），不在本层豁免。
 */
internal fun extractPdfTextWithOcr(
    file: File,
    ocrVision: com.stragent.core.ports.VisionClient?,
    maxPages: Int = Config.get("STRAGENT_OCR_MAX_PAGES")?.toIntOrNull()?.coerceAtLeast(1) ?: OCR_MAX_PAGES_DEFAULT,
): PdfExtractResult {
    try {
        return PdfExtractResult(extractPdfText(file), ocrUsed = false)
    } catch (e: IllegalArgumentException) {
        // 文本层失败：仅在 OCR 通道就位时兜底；否则原样上抛（既有行为零变化）
        if (ocrVision == null || !ocrVision.isAvailable()) throw e
    }
    val doc = org.apache.pdfbox.Loader.loadPDF(file)
    try {
        if (doc.numberOfPages > maxPages) {
            throw IllegalArgumentException(
                "扫描件共 ${doc.numberOfPages} 页，超过 OCR 页数上限 $maxPages" + // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                    "（每页一次视觉调用，成本显式化）。可设 STRAGENT_OCR_MAX_PAGES 调高上限，或拆分导入。", // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            )
        }
        val renderer = org.apache.pdfbox.rendering.PDFRenderer(doc)
        // T-129 有界扇出：页与页独立，按页序归并（PDFRenderer 非线程安全——渲染留在串行侧，
        // 视觉调用并行；取消/失败语义与串行一致）
        val pageTexts = com.stragent.core.ai.Parallel.mapLimited((0 until doc.numberOfPages).toList()) { i ->
            val img = renderer.renderImage(i, 2.0f)
            val baos = java.io.ByteArrayOutputStream()
            javax.imageio.ImageIO.write(img, "png", baos)
            val base64 = java.util.Base64.getEncoder().encodeToString(baos.toByteArray())
            val r = ocrVision.describe(
                com.stragent.core.ports.VisionRequest(
                    image = com.stragent.core.ports.VisionImage.Inline(base64, "image/png"),
                    prompt = "逐字转写图片中的全部文字：保持原有换行顺序，不要总结、不要添加、不要遗漏。", // lint:allow zh-first（用户面中文输出/提示词，D19/D21/T-115 语言定位）
                    maxTokens = 4096,
                ),
            )
            r.text.lines().map { cleanPdfText(it) }.filter { it.isNotBlank() }
        }
        val out = pageTexts.flatten()
        if (out.isEmpty()) {
            throw IllegalArgumentException(
                "OCR 逐页转写未得到任何文字（图片可能为空白或非文字内容）。", // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            )
        }
        return PdfExtractResult(out, ocrUsed = true)
    } finally {
        doc.close()
    }
}
