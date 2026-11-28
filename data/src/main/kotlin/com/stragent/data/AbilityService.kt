package com.stragent.data

import com.stragent.core.ports.AgentTool
import com.stragent.core.csv.Csv
import com.stragent.core.csv.ColumnType
import com.stragent.core.dataset.Dataset
import com.stragent.core.dataset.aggregateBy
import com.stragent.core.dataset.summarize
import com.stragent.core.dataset.summarizeColumn
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Str Agent 能力层（原生）—— 四条 stragent_* 能力的权威实现。str agent 作为独立
 * 项目，契约名 stragent_* 由本仓库自定，不再沿用上游 stepx 的契约名。
 *
 * 这四条是 Str Agent 对外（/search + localhost MCP 桥）公开的能力契约（描述/schema/
 * 错误文案是平台侧会展示的内容，逐字保留）。金标已 vendoring 于本仓库
 * data/src/test/resources/ability-goldens.json，AbilityGoldenTest 逐字段对拍。
 *
 * 数据集注册表是**会话内存**（语义与上游 datasets 一致）：
 * 幂等键 = csv 内容 sha256 前 12 位，同内容重导返回同一 datasetId。
 */

const val ABILITY_IMPORT_TABLE = "stragent_import_table"
// T-096：stragent_import_text 导入成功后推进会话指针（McpTaskDispatch POINTER_IMPORT_TOOLS）
const val ABILITY_IMPORT_TEXT = "stragent_import_text"
const val ABILITY_COLUMN_PROFILE = "stragent_get_column_profile"
const val ABILITY_GROUP_AGGREGATE = "stragent_get_group_aggregate"
const val ABILITY_LIST_ROWS = "stragent_list_rows"

/**
 * 能力注册表（会话内存 + 可选持久化）。
 *
 * 默认（[persistence] == null）= 纯会话内存，进程重启即空——测试与嵌入式宿主沿用旧行为。
 * 宿主装配了 [AbilityDatasetStore] 时：put 落盘、未命中回读、[restore] 在启动时回灌，
 * 重启后同一 datasetId 仍可用（datasetId 本身由内容派生，重导也落在同一个键上）。
 */
class AbilityDatasets(private val persistence: AbilityDatasetStore? = null) {
    private val map = ConcurrentHashMap<String, Dataset>()

    /** 导入顺序（最新的在最后）——「会话当前数据集」= 最后导入的那份 */
    private val order = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** 本次进程内 put 的落盘结果（null = 未接持久化） */
    private val persisted = ConcurrentHashMap<String, Boolean>()

    /**
     * 最近一次 put 的 LRU 淘汰名单（T-076；供导入回执如实交代，无淘汰为空表）
     */
    @Volatile
    private var lastEvictions: List<String> = emptyList()

    fun put(id: String, dataset: Dataset) {
        // remove+add 必须整体互斥：HTTP 桥已是多线程（T-030），同内容并发重导时
        // 两次 remove 都落空、两次 add 都进列，order 会留下重复 id
        synchronized(this) {
            map[id] = dataset
            order.remove(id)
            order.add(id)
        }
        persistence?.let {
            val (ok, evicted) = it.saveWithEvictions(id, dataset)
            persisted[id] = ok
            lastEvictions = evicted
        }
    }

    /**
     * 最近一次落盘触发的 LRU 淘汰名单（T-076；无淘汰 / 未接持久化为空表）
     */
    fun lastEvictions(): List<String> = lastEvictions

    /** mustDataset：不存在时给中文错误（平台会把 message 直接展示；文案随契约定义，逐字保留） */
    fun mustDataset(id: String): Dataset =
        byId(id) ?: throw IllegalArgumentException(
            "数据集不存在: $id（请先用 stragent_import_table 或 stragent_import_file 导入；" +
                "同一内容重新导入会返回同一个 datasetId）",
        )

    fun size(): Int = map.size

    /** 按 id 取数据集（不存在返回 null；用于溯源清单等不抛错的场景）。持久化命中时回读到内存。 */
    fun byId(id: String): Dataset? {
        map[id]?.let { return it }
        val loaded = persistence?.load(id) ?: return null
        synchronized(this) {
            map.putIfAbsent(id, loaded)
            if (!order.contains(id)) order.add(id)
        }
        return map[id]
    }

    /** 全部数据集 id（按导入顺序） */
    fun ids(): List<String> = order.toList()

    /**
     * 会话当前数据集 = **最近一次导入**的那份。
     *
     * 供 MCP 工具面使用：外部 agent 的自然工作流是「先 import_table，再连续调用分析工具」，
     * 每次都带 datasetId 是噪音；显式 datasetId 仍可覆盖（见 AnalysisToolSession）。
     */
    fun current(): Dataset? = order.lastOrNull()?.let { byId(it) }

    /** 会话当前数据集的 id（溯源清单用） */
    fun currentId(): String? = order.lastOrNull()

    /**
     * 启动恢复：把持久化的数据集按保存时间回灌内存（最新的成为「当前数据集」）。
     * @return 恢复的数据集条数（0 = 没有持久化或全部损坏）
     */
    fun restore(): Int {
        val loaded = persistence?.loadAll() ?: return 0
        synchronized(this) {
            for ((id, dataset) in loaded) {
                if (!map.containsKey(id)) {
                    map[id] = dataset
                    order.remove(id)
                    order.add(id)
                }
            }
        }
        return loaded.size
    }

    /** 本次 put 是否落盘（null = 该注册表没接持久化） */
    fun persistedState(id: String): Boolean? = persistence?.let { persisted[id] }
}

/** csv 内容 → ds_xxxxxxxxxxxx（sha256 前 12 位，幂等） */
fun datasetIdOf(csv: String): String =
    "ds_" + MessageDigest.getInstance("SHA-256")
        .digest(csv.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(12)

/** 全量口径上移到 core/dataset（能力层与 agent 工具共用同一实现） */

private fun jsonDouble(value: Double): JsonPrimitive = JsonPrimitive(value)

/** JS 数字语义：JSON.stringify(550.0) 是 "550" 而不是 "550.0"——整值转 Long 输出 */
private fun jsNum(value: Double): JsonPrimitive =
    if (value.isFinite() && value == Math.floor(value) && kotlin.math.abs(value) < 9.007199254740992E15) {
        JsonPrimitive(value.toLong())
    } else {
        JsonPrimitive(value)
    }

/**
 * 四条能力的执行器。params 从 JSON 来，返回 JSON——错误抛 IllegalArgumentException
 *（message 即对外 /exec 的 error 字段文案）。
 */
object StrAgentAbilities {
    private val json = Json

    /** 能力描述（POST /search 会展示；随 ABILITY_CONTRACTS 定义，逐字一致） */
    val descriptions: Map<String, String> = ABILITY_CONTRACTS.associate { it.method to it.description }

    /** inputSchema（/search 的 schema 字段；同上生成） */
    val inputSchemas: Map<String, String> = ABILITY_CONTRACTS.associate { it.method to it.inputSchemaJson }

    /**
     * T-067：注入扫描结果的回执 JSON（data 与 host 共用一份形制）。
     * signals 只带前 10 条——明细够定位，病态数据不撑爆回执。
     */
    fun injectionScanJson(scan: com.stragent.core.security.InjectionScanReport): JsonObject = buildJsonObject {
        put("scannedRows", scan.scannedRows)
        put("hitCount", scan.hitCount)
        put(
            "signals",
            JsonArray(
                scan.signals.take(10).map { signal ->
                    buildJsonObject {
                        put("rule", signal.rule)
                        put("ruleName", signal.ruleName)
                        put("location", signal.location)
                        put("sample", signal.sample)
                    }
                },
            ),
        )
    }

    /** stragent_import_table：导入 + 全量统计（幂等） */
    fun importTable(datasets: AbilityDatasets, csv: String?, name: String?): JsonObject {
        val text = csv ?: ""
        if (text.trim().isEmpty()) throw IllegalArgumentException("csv 为空：请传入含表头行的表格文本")
        val table = Csv.parseDelimited(text)
        if (table.rows.isEmpty()) {
            throw IllegalArgumentException("表格没有数据行：只有表头或内容为空，无法分析")
        }
        val datasetId = datasetIdOf(text)
        val dataset = Dataset(
            name = name ?: "未命名数据",
            importedAt = System.currentTimeMillis(),
            columns = table.columns,
            rows = table.rows,
            columnTypes = table.columnTypes,
            delimiter = table.delimiter,
            truncated = table.truncated,
            sourceRows = table.totalRows,
        )
        val (validRows, density) = com.stragent.core.dataset.validRowsAndDensity(dataset)
        // T-067：数据面注入扫描——命中不拦截（数据仍是数据），只做可见性（回执 + 简报样本段过滤）
        val injection = com.stragent.core.security.PromptInjectionHeuristics.scanDataset(dataset)
        datasets.put(datasetId, dataset)
        return buildJsonObject {
            put("datasetId", datasetId)
            put("name", dataset.name)
            put("rows", dataset.sourceRows)
            put("columns", table.columns.size)
            put(
                "columnList",
                JsonArray(
                    table.columns.take(50).mapIndexed { index, columnName ->
                        buildJsonObject {
                            put("name", columnName)
                            put("type", if (table.columnTypes[index] == ColumnType.NUMBER) "number" else "text")
                        }
                    },
                ),
            )
            put("columnListTruncated", table.columns.size > 50)
            put("truncated", table.truncated)
            put("validRows", validRows)
            put("density", jsNum(density))
            // T-067：注入扫描结果（恒输出——「扫过且干净」与「没扫」是两种事实）
            put("injectionScan", injectionScanJson(injection))
            // T-054：接了持久化就明说落盘结果——重启可用 / 超限未落盘，用户都要知道
            datasets.persistedState(datasetId)?.let { persisted ->
                put("persisted", persisted)
                if (!persisted) {
                    // T-074：写失败/超限时诊断要可读（不崩、如实、指明恢复动作）；文案走 i18n
                    put("persistedNote", com.stragent.core.i18n.Messages.get("import.persisted_note"))
                }
            }
            // T-076：LRU 淘汰如实回执——最旧的集让位给本次导入（新导入永远成功）
            val evicted = datasets.lastEvictions()
            if (evicted.isNotEmpty()) {
                put("evicted", JsonArray(evicted.map { JsonPrimitive(it) }))
                put(
                    "evictedNote",
                    // 文案走 i18n（预算制：新代码零新增硬编码中文）
                    com.stragent.core.i18n.Messages.get("import.evicted_note"),
                )
            }
        }
    }

    /** stragent_get_column_profile：单列完整画像或全列概要 */
    fun columnProfile(datasets: AbilityDatasets, datasetId: String?, column: String?): JsonObject {
        val dataset = datasets.mustDataset(datasetId ?: "")
        if (column != null) {
            val index = dataset.columns.indexOf(column)
            if (index == -1) {
                throw IllegalArgumentException("列不存在: $column（该数据集的列: ${dataset.columns.joinToString("、")}）")
            }
            val stat = summarizeColumn(dataset, index)
            return buildJsonObject {
                put("dataset", datasetId)
                put("column", column)
                put("caliber", "count 为实际参与统计的样本数；missing = 空值 + 无法解析的脏值")
                put("name", stat.name)
                put("type", if (stat.type == ColumnType.NUMBER) "number" else "text")
                put("count", stat.count)
                put("missing", stat.missing)
                stat.min?.let { put("min", jsNum(it)) }
                stat.max?.let { put("max", jsNum(it)) }
                stat.sum?.let { put("sum", jsNum(it)) }
                stat.mean?.let { put("mean", jsNum(it)) }
                stat.median?.let { put("median", jsNum(it)) }
                stat.p25?.let { put("p25", jsNum(it)) }
                stat.p75?.let { put("p75", jsNum(it)) }
                stat.distinct?.let { put("distinct", it) }
                stat.topValues?.let { topValues ->
                    put("topValues", JsonArray(topValues.take(5).map { top ->
                        buildJsonObject {
                            put("value", top.value)
                            put("count", top.count)
                        }
                    }))
                }
            }
        }
        val all = summarize(dataset)
        val items = all.take(50).map { stat ->
            buildJsonObject {
                put("name", stat.name)
                put("type", if (stat.type == ColumnType.NUMBER) "number" else "text")
                put("count", stat.count)
                put("missing", stat.missing)
                stat.distinct?.let { put("distinct", it) }
            }
        }
        return buildJsonObject {
            put("dataset", datasetId)
            put("total", all.size)
            put("returned", items.size)
            put("items", JsonArray(items))
        }
    }

    /** stragent_get_group_aggregate：分组聚合（share 分母写明；avg/负值时 share 置 null） */
    fun groupAggregate(
        datasets: AbilityDatasets,
        datasetId: String?,
        dimension: String?,
        metric: String?,
        mode: String?,
        limit: Double?,
    ): JsonObject {
        val dataset = datasets.mustDataset(datasetId ?: "")
        val dimIndex = dataset.columns.indexOf(dimension ?: "")
        if (dimIndex == -1) {
            throw IllegalArgumentException("维度列不存在: $dimension（该数据集的列: ${dataset.columns.joinToString("、")}）")
        }
        if (dataset.columnTypes[dimIndex] != ColumnType.TEXT) {
            throw IllegalArgumentException(
                "维度列 \"$dimension\" 被识别为数值列：分组维度请选文本列（ID/编号类列也不适合做维度）",
            )
        }
        val valueIndex = dataset.columns.indexOf(metric ?: "")
        if (valueIndex == -1) {
            throw IllegalArgumentException("指标列不存在: $metric（该数据集的列: ${dataset.columns.joinToString("、")}）")
        }
        if (dataset.columnTypes[valueIndex] != ColumnType.NUMBER) {
            throw IllegalArgumentException("指标列 \"$metric\" 被识别为文本列：聚合指标请选数值列")
        }
        val modeValue = mode ?: "sum"
        val topN = ((limit ?: 8.0).toInt()).coerceIn(1, 50)
        val items = aggregateBy(dataset, dimIndex, valueIndex, modeValue, topN)
        // aggregateBy 会把 topN 之外的组折成一条「其他 N 项」；total 单独数真实组数
        val labels = dataset.rows.map { (it.getOrNull(dimIndex) ?: "").trim().ifEmpty { "（空）" } }.toSet()
        return buildJsonObject {
            put("dataset", datasetId)
            put("dimension", dimension)
            put("metric", metric)
            put("mode", modeValue)
            put(
                "denominator",
                if (modeValue == "sum") {
                    "share 分母 = 该指标全体合计（存在负值时置 null）"
                } else {
                    "mode=avg：各组均值之和不构成整体，share 恒为 null"
                },
            )
            put("total", labels.size)
            put("returned", items.size)
            put(
                "items",
                JsonArray(
                    items.map { item ->
                        buildJsonObject {
                            put("label", item.label)
                            put("value", jsNum(item.value))
                            put("count", item.count)
                            put("share", if (item.share.isFinite()) jsNum(item.share) else JsonNull)
                        }
                    },
                ),
            )
        }
    }

    /** stragent_list_rows：行级溯源（分页，row 从 1 起不含表头）；抽样数据集带 sourceRow 回链 */
    fun listRows(datasets: AbilityDatasets, datasetId: String?, offset: Double?, limit: Double?): JsonObject {
        val dataset = datasets.mustDataset(datasetId ?: "")
        val offsetValue = (offset ?: 0.0).toInt().coerceAtLeast(0)
        val limitValue = ((limit ?: 10.0).toInt()).coerceIn(1, 50)
        val sampling = dataset.sampling
        val items = mutableListOf<JsonObject>()
        var i = 0
        while (i < limitValue && offsetValue + i < dataset.rows.size) {
            val rowIndex = offsetValue + i
            items.add(
                buildJsonObject {
                    put("row", rowIndex + 1)
                    // T-054：样本行 → 源文件数据行，报告里的「第 N 行」据此回到明细
                    if (sampling != null) {
                        put("sourceRow", sampling.sourceRowOf(rowIndex + 1) ?: (rowIndex + 1))
                    }
                    put("cells", JsonArray(dataset.rows[rowIndex].map { JsonPrimitive(it) }))
                },
            )
            i += 1
        }
        return buildJsonObject {
            put("dataset", datasetId)
            put("total", dataset.rows.size)
            put("returned", items.size)
            put("offset", offsetValue)
            put(
                "rowNumberNote",
                if (sampling != null) {
                    "row 为样本内数据行号（从 1 起，不含表头）；sourceRow 为源文件数据行号（不含表头）"
                } else {
                    "row 为数据行号（从 1 起，不含表头行）"
                },
            )
            if (sampling != null) {
                put(
                    "sampling",
                    buildJsonObject {
                        put("method", sampling.method)
                        put("step", sampling.step)
                        put("targetRows", sampling.targetRows)
                        put("totalRows", sampling.totalRows)
                        put("fingerprint", sampling.fingerprint)
                        put("notice", "本数据集是源文件（${sampling.totalRows} 行）的确定性系统抽样，共 ${dataset.rows.size} 行；统计为样本口径。")
                    },
                )
                put("sourceRows", sampling.totalRows)
            }
            put("items", JsonArray(items))
        }
    }

    /** 统一分发（Android Service 壳与测试都走这里）：未知方法给中文错误。
     *  对外扩展口：stragent_tools_list / stragent_tools_call 把 agent 工具目录开放给
     *  外部 Agent（stepfun.ts 注释里的 A2A 方向——App 作为被调方）。 */
    fun dispatch(datasets: AbilityDatasets, method: String, params: JsonObject, extraTools: List<AgentTool> = emptyList()): JsonObject = when (method) {
        "stragent_tools_list" -> buildJsonObject {
            put("tools", kotlinx.serialization.json.JsonArray(toolsCatalog(datasets, extraTools)))
        }
        "stragent_tools_call" -> {
            val name = params["name"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                ?: throw IllegalArgumentException("name（工具名）不能为空")
            val args = params["args"] as? JsonObject ?: JsonObject(emptyMap())
            // T-053/T-068：只认规范名（旧裸名 1.1.0 起退役，见 McpToolNames）
            val tool = (abilityTools(datasets) + extraTools).find { McpToolNames.matches(it.name, name) }
                ?: throw IllegalArgumentException("未知工具: $name（用 stragent_tools_list 查看目录）")
            tool.invoke(args)
        }
        ABILITY_IMPORT_TABLE -> importTable(datasets, params["csv"]?.primitiveContent(), params["name"]?.primitiveContent())
        ABILITY_COLUMN_PROFILE -> columnProfile(
            datasets,
            params["datasetId"]?.primitiveContent(),
            params["column"]?.primitiveContent(),
        )
        ABILITY_GROUP_AGGREGATE -> groupAggregate(
            datasets,
            params["datasetId"]?.primitiveContent(),
            params["dimension"]?.primitiveContent(),
            params["metric"]?.primitiveContent(),
            params["mode"]?.primitiveContent(),
            params["limit"]?.jsonPrimitive?.doubleOrNull,
        )
        ABILITY_LIST_ROWS -> listRows(
            datasets,
            params["datasetId"]?.primitiveContent(),
            params["offset"]?.jsonPrimitive?.doubleOrNull,
            params["limit"]?.jsonPrimitive?.doubleOrNull,
        )
        else -> throw IllegalArgumentException("未知能力: $method（须先经 /search 发现，不得臆造 method 名）")
    }

    private fun JsonElement?.primitiveContent(): String? =
        (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
}

/** 对外 /exec 的响应形状：{ok, result} 或 {ok:false, error} */
fun execAbility(
    datasets: AbilityDatasets,
    method: String,
    paramsJson: String,
    extraTools: List<AgentTool> = emptyList(),
): JsonObject =
    try {
        val params = if (paramsJson.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(paramsJson).jsonObject
        buildJsonObject {
            put("ok", true)
            put("result", StrAgentAbilities.dispatch(datasets, method, params, extraTools))
        }
    } catch (error: Exception) {
        buildJsonObject {
            put("ok", false)
            put("error", error.message ?: "执行失败")
        }
    }

private val json = Json


