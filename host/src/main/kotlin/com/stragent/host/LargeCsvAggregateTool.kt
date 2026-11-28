package com.stragent.host

import com.stragent.core.ports.AgentTool
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
 * 本地 CSV 聚合工具 —— **按 [CsvTierRouting] 自动切档**（T-013 接线）。
 *
 * 为什么需要它：其余分析工具都基于**会话内存里的数据集**。指一个本地文件就能出聚合结果，
 * 是外部 agent 最常见诉求；而规模这条腿以前只有 tier-2 一种走法（写死 DuckDB），
 * 小文件也要起一个列式引擎，宿主没有原生库时干脆全量报错。
 *
 * 现在（工具语义不变，只换后端）：
 *  - ≤ 阈值 → tier-1（与报告路径同一实现，数字逐个可回溯到内存原始行）；
 *  - > 阈值 → tier-2（DuckDB 流式读文件，**数据全程不进 JVM 堆**）；
 *  - tier-2 不可用而文件超阈值 → 回落 tier-1 并显式回报 `truncationNotice`，不静默降级；
 *  - `engine` 参数可指名档位（`memory` / `columnar`），用于双轨对拍与主动要规模。
 *
 * 安全边界（三条）：
 *  1. **只读**：只读取本地 CSV/TSV，不写、不删、不联网；
 *  2. **扩展名白名单**：只接受 `.csv` / `.tsv`，避免被诱导去读任意文件；
 *  3. **需显式路径**：路径由宿主/用户给出，不是模型能凭空猜到的位置。
 *
 * 口径纪律：两档数值解析同规则（tier-1 = `Csv.parseNumber`，tier-2 = `DuckDbSql`），
 * 聚合收尾共用 `core.dataset.finalizeAggregate`，topN 与「其他 N 项」语义一致，
 * 因此同一份文件走哪档得到的数是同一个（由 bench `engine-parity` 逐样本对拍兜底）。
 */
fun largeCsvAggregateTool(): AgentTool = object : AgentTool {
    override val name = "stragent_aggregate_csv"
    override val description =
        "对**本地 CSV/TSV 文件**做分组聚合（自动切档：小文件走 tier-1 内存引擎，" +
        "超过 ${EngineRouter.DEFAULT_TIER_THRESHOLD} 行走 tier-2 列式引擎，数据不进内存）：" +
        "按文本维度列对数值指标列分组，返回各组的值、样本量与占比。" +
        "可用 engine=memory/columnar 指名档位（用于双轨对拍或强制规模路径）。"
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
                    "dimension",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "维度列名（表头原文，如「地区」）")
                    },
                )
                put(
                    "metric",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "指标列名（表头原文，如「销售额」）")
                    },
                )
                put(
                    "mode",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "聚合方式：sum 或 avg（默认 sum）")
                    },
                )
                put(
                    "topN",
                    buildJsonObject {
                        put("type", "number")
                        put("description", "返回前 N 组，其余合并为「其他 N 项」（默认 8）")
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
        put(
            "required",
            JsonArray(
                listOf(
                    JsonPrimitive("csvPath"),
                    JsonPrimitive("dimension"),
                    JsonPrimitive("metric"),
                ),
            ),
        )
    }

    // 读文件 + 聚合有 IO 成本，不进缓存
    override val cacheable = false

    override fun invoke(args: JsonObject): JsonObject {
        val path = args.string("csvPath")
            ?: throw IllegalArgumentException("csvPath 不能为空")
        val dimension = args.string("dimension")
            ?: throw IllegalArgumentException("dimension 不能为空")
        val metric = args.string("metric")
            ?: throw IllegalArgumentException("metric 不能为空")
        val mode = args.string("mode") ?: "sum"
        val topN = args.double("topN")?.toInt()?.coerceIn(1, 200) ?: 8

        val file = resolveCsvFile(path)
        val decision = CsvTierRouting.decide(file, args.string("engine"))
        return if (decision.tier == EngineTier.IN_MEMORY) {
            tier1Aggregate(file, decision, dimension, metric, mode, topN)
        } else {
            tier2Aggregate(file, decision, dimension, metric, mode, topN)
        }
    }

    /** tier-2：DuckDB 流式读文件，百万行也在亚秒级 */
    private fun tier2Aggregate(
        file: File,
        decision: CsvTierRouting.Decision,
        dimension: String,
        metric: String,
        mode: String,
        topN: Int,
    ): JsonObject {
        val result = DuckDbEngine.aggregateCsvByName(file, dimension, metric, mode, topN)
        return buildJsonObject {
            put("engine", decision.engine)
            put("tier", decision.tier.name.lowercase())
            put("tierLabel", decision.tier.label)
            put("csvPath", file.absolutePath)
            put("rowCount", result.rowCount)
            put("sourceRows", result.rowCount)
            put("dimension", result.dimension)
            put("metric", result.metric)
            put("mode", result.mode)
            put("grandTotal", result.grandTotal)
            put("truncatedToTopN", result.truncatedToTopN)
            put("elapsedMs", result.elapsedMs)
            put("routing", routingJson(decision))
            put(
                "items",
                JsonArray(
                    result.items.map { item ->
                        buildJsonObject {
                            put("label", item.label)
                            put("value", item.value)
                            put("count", item.count)
                            put("share", item.share)
                        }
                    },
                ),
            )
        }
    }

    /** tier-1：内存引擎（与报告路径同一实现），输出结构与 tier-2 完全同构 */
    private fun tier1Aggregate(
        file: File,
        decision: CsvTierRouting.Decision,
        dimension: String,
        metric: String,
        mode: String,
        topN: Int,
    ): JsonObject {
        require(mode == "sum" || mode == "avg") { "未知聚合模式：$mode" }
        val started = System.nanoTime()
        val dataset = CsvTierRouting.tier1Dataset(file)
        val engine: AnalysisEngine = InMemoryEngine(dataset)
        val items = engine.aggregateBy(
            CsvTierRouting.columnIndex(dataset, dimension, "维度列"),
            CsvTierRouting.columnIndex(dataset, metric, "指标列"),
            mode,
            topN,
        )
        val notice = CsvTierRouting.truncationNotice(decision, dataset)
        return buildJsonObject {
            put("engine", decision.engine)
            put("tier", decision.tier.name.lowercase())
            put("tierLabel", decision.tier.label)
            put("csvPath", file.absolutePath)
            put("rowCount", dataset.rows.size)
            put("sourceRows", dataset.sourceRows)
            put("dimension", dimension)
            put("metric", metric)
            put("mode", mode)
            put("grandTotal", items.sumOf { it.value })
            put("truncatedToTopN", items.any { it.label.startsWith("其他 ") })
            put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
            put("routing", routingJson(decision))
            notice?.let { put("truncationNotice", it) }
            put(
                "items",
                JsonArray(
                    items.map { item ->
                        buildJsonObject {
                            put("label", item.label)
                            put("value", item.value)
                            put("count", item.count)
                            put("share", item.share)
                        }
                    },
                ),
            )
        }
    }
}

