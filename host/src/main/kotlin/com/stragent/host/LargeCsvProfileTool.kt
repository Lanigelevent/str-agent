package com.stragent.host

import com.stragent.core.ports.AgentTool
import com.stragent.core.csv.ColumnType
import com.stragent.core.dataset.ColumnStat
import com.stragent.core.engine.AnalysisEngine
import com.stragent.core.engine.EngineRouter
import com.stragent.core.engine.EngineTier
import com.stragent.core.engine.InMemoryEngine
import com.stragent.engine.duckdb.DuckDbEngine
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 本地 CSV 列画像工具 —— 与 [largeCsvAggregateTool] 同源的**按规模自动切档**通道。
 *
 * 为什么需要：列画像（缺失率 / 分位数 / 去重数 / 高频值）是**判断数据能不能用**的第一步，
 * 大文件上同样要做；接线前它写死 tier-2，宿主没有 DuckDB 原生库时连小文件也一律报错。
 * 现在 ≤ [EngineRouter.DEFAULT_TIER_THRESHOLD] 行走 tier-1（与报告路径同一实现），
 * 超阈值走 tier-2，回落时显式回报 `truncationNotice`。
 *
 * 口径与 tier-1 完全一致（两档共用 `summarizeColumn` 语义）：
 * count 是「实际参与统计的样本数」，missing = 空值 + 脏值，分位数为线性插值（PERCENTILE.INC 同口径）。
 */
fun largeCsvProfileTool(): AgentTool = object : AgentTool {
    override val name = "stragent_profile_csv"
    override val description =
        "对**本地 CSV/TSV 文件**做列画像（按规模自动切档：小文件 tier-1 内存引擎，" +
        "超过 ${EngineRouter.DEFAULT_TIER_THRESHOLD} 行 tier-2 列式引擎，数据不进内存）：" +
        "数值列给 count/missing/min/max/sum/mean/median/p25/p75，文本列给 count/missing/distinct/topValues。" +
        "不指定 column 时画像全部列；可用 engine=memory/columnar 指名档位。"
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "csvPath",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "本地 CSV/TSV 文件的绝对路径（只支持 .csv / .tsv）")
                    },
                )
                put(
                    "column",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "列名（表头原文）；省略则画像全部列")
                    },
                )
                put(
                    "engine",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "档位：auto（默认，按行数自动切）/ memory（tier-1，报告路径口径）/ " +
                                "columnar（tier-2，需要 DuckDB 原生库）",
                        )
                    },
                )
            },
        )
        put("required", JsonArray(listOf(JsonPrimitive("csvPath"))))
    }

    override val cacheable = false

    override fun invoke(args: JsonObject): JsonObject {
        val path = args.string("csvPath")
            ?: throw IllegalArgumentException("csvPath 不能为空")
        val column = args.string("column")
        val file = resolveCsvFile(path)
        val decision = CsvTierRouting.decide(file, args.string("engine"))
        return if (decision.tier == EngineTier.IN_MEMORY) {
            tier1Profile(file, decision, column)
        } else {
            val started = System.nanoTime()
            val stats = DuckDbEngine.profileCsvByName(file, column)
            buildJsonObject {
                put("engine", decision.engine)
                put("tier", decision.tier.name.lowercase())
                put("tierLabel", decision.tier.label)
                put("csvPath", file.absolutePath)
                put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
                put("routing", routingJson(decision))
                put("columns", JsonArray(stats.map { statJson(it) }))
            }
        }
    }

    /**
     * tier-1 画像：与报告路径同一实现（[InMemoryEngine] → `summarizeColumn`）。
     * 小文件在这里不必起列式引擎；超阈值回落时按 `truncationNotice` 显式回报。
     */
    private fun tier1Profile(
        file: File,
        decision: CsvTierRouting.Decision,
        column: String?,
    ): JsonObject {
        val started = System.nanoTime()
        val dataset = CsvTierRouting.tier1Dataset(file)
        val engine: AnalysisEngine = InMemoryEngine(dataset)
        val indexes = if (column == null) {
            dataset.columns.indices.toList()
        } else {
            listOf(CsvTierRouting.columnIndex(dataset, column, "列"))
        }
        val stats = indexes.mapNotNull { engine.profileColumn(it) }
        val notice = CsvTierRouting.truncationNotice(decision, dataset)
        return buildJsonObject {
            put("engine", decision.engine)
            put("tier", decision.tier.name.lowercase())
            put("tierLabel", decision.tier.label)
            put("csvPath", file.absolutePath)
            put("rowCount", dataset.rows.size)
            put("sourceRows", dataset.sourceRows)
            put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
            put("routing", routingJson(decision))
            notice?.let { put("truncationNotice", it) }
            put("columns", JsonArray(stats.map { statJson(it) }))
        }
    }
}

/** 列画像 → JSON（字段与 tier-1 的工具输出一致，便于同构对照） */
private fun statJson(stat: ColumnStat): JsonObject = buildJsonObject {
    put("name", stat.name)
    put("type", if (stat.type == ColumnType.NUMBER) "number" else "text")
    put("count", stat.count)
    put("missing", stat.missing)
    stat.min?.let { put("min", it) }
    stat.max?.let { put("max", it) }
    stat.sum?.let { put("sum", it) }
    stat.mean?.let { put("mean", it) }
    stat.median?.let { put("median", it) }
    stat.p25?.let { put("p25", it) }
    stat.p75?.let { put("p75", it) }
    stat.distinct?.let { put("distinct", it) }
    stat.topValues?.let { top ->
        put(
            "topValues",
            JsonArray(
                top.take(5).map { item ->
                    buildJsonObject {
                        put("value", item.value)
                        put("count", item.count)
                    }
                },
            ),
        )
    }
}

/**
 * 校验并解析本地 CSV 路径 —— 大文件工具的**安全闸门**（只读 + 扩展名白名单 + 必须是普通文件）。
 */
internal fun resolveCsvFile(path: String): File {
    val file = File(path)
    if (!file.isFile) {
        throw IllegalArgumentException("文件不存在或不是普通文件：$path")
    }
    val extension = file.extension.lowercase()
    if (extension !in ALLOWED_CSV_EXTENSIONS) {
        throw IllegalArgumentException(
            "只支持 ${ALLOWED_CSV_EXTENSIONS.joinToString(" / ")} 文件，收到：.$extension",
        )
    }
    return file
}

internal val ALLOWED_CSV_EXTENSIONS = setOf("csv", "tsv")
