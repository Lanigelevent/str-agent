package com.stragent.host

import com.stragent.core.ports.AgentTool
import com.stragent.core.ports.schemaArrayField
import com.stragent.core.ports.schemaField
import com.stragent.core.ports.schemaObject
import com.stragent.core.stats.autoForecast
import com.stragent.core.stats.extractTimeSeries
import com.stragent.core.stats.findDateColumn
import com.stragent.core.stats.findValueColumn
import com.stragent.data.AbilityDatasets
import com.stragent.data.McpSessionContext
import com.stragent.data.SessionRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 时序预测工具 —— D10 阶段 5 第一落地。
 *
 * 对已导入的数据集做时序预测：自动选择 SES（简单指数平滑）或 Holt 线性趋势法，
 * 取样本内 SSE 更小者，生成 horizon 期预测值与 95% 置信区间。
 *
 * **纯 Kotlin 零依赖、确定性**（无随机成分）——与溯源清单纪律兼容：同一份数据 +
 * 同一参数，预测结果必然一致，可重放复核。
 *
 * 诚实边界（不冒称）：
 *  - Holt-Winters 为加法模型，需至少 2 个完整季节周期；不足时自动回落 Holt
 *  - 不做 ARIMA（需要差分阶数判断与自相关分析）
 *  - 置信区间基于残差标准误差的正态近似（1.96σ），不是精确预测区间
 *  - 数据点 < 3 时拒绝预测（参数估不出来）
 *  - 预测是「基于历史趋势的外推」，不包含未来事件信息——提示词与工具描述均不承诺准确性
 */
fun forecastTool(
    datasets: AbilityDatasets,
    registry: SessionRegistry? = null,
): AgentTool = object : AgentTool {
    override val name = "stragent_forecast"
    override val description =
        "对已导入的数据集做时序预测：自动选择指数平滑、线性趋势或季节性（Holt-Winters 加法）法，" +
        "取样本内 SSE 最小者，生成指定期数的预测值与 95% 置信区间。" +
        "需要至少 3 个数据点（季节性方法需至少 2 个完整周期）；纯确定性，可重放复核。" +
        "预测是基于历史趋势的外推，不包含未来事件信息。"
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "datasetId",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "数据集编号（缺省取会话当前数据集）")
                    },
                )
                put(
                    "valueColumn",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "要预测的数值列名（缺省取第一个数值列）")
                    },
                )
                put(
                    "dateColumn",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "日期列名（缺省自动探测；提供则按日期排序后预测）")
                    },
                )
                put(
                    "horizon",
                    buildJsonObject {
                        put("type", "integer")
                        put("description", "预测期数（1-24，缺省 3）")
                    },
                )
                put(
                    "seasonPeriod",
                    buildJsonObject {
                        put("type", "integer")
                        put("description", "季节周期长度（如月度=12，季度=4；缺省 12。数据不足 2 个周期时自动回落非季节性方法）")
                    },
                )
            },
        )
        // 无必填参数——datasetId 可缺省取会话当前数据集，valueColumn 可缺省取第一个数值列
    }

    override val cacheable = false

    /** T-053：返回结构（时序成功 + 数据点不足的错误分支取并集） */
    override val outputSchema = schemaObject(
        "method" to schemaField("string", "ses / holt / holt-winters / none"),
        "alpha" to schemaField("number"),
        "beta" to schemaField("number"),
        "gamma" to schemaField("number"),
        "sse" to schemaField("number"),
        "rmse" to schemaField("number"),
        "dataPoints" to schemaField("integer"),
        "horizon" to schemaField("integer"),
        "valueColumn" to schemaField("string"),
        "dateColumn" to schemaField("string"),
        "forecast" to schemaArrayField(
            schemaObject(
                "period" to schemaField("integer"),
                "value" to schemaField("number"),
                "lower" to schemaField("number"),
                "upper" to schemaField("number"),
                required = listOf("period", "value", "lower", "upper"),
            ),
            "预测点与 95% 置信区间",
        ),
        "history" to schemaArrayField(schemaField("number")),
        "error" to schemaField("string", "数据点不足时出现"),
        "notice" to schemaField("string"),
        required = listOf("method", "dataPoints", "horizon"),
    )

    override fun invoke(args: JsonObject): JsonObject {
        val datasetId = resolveDatasetId(args, datasets, registry)
        val dataset = datasets.byId(datasetId)
            ?: throw IllegalArgumentException("数据集不存在：$datasetId（请先用 stragent_import_table 导入）")

        val valueColName = args.string("valueColumn")
        val dateColName = args.string("dateColumn")
        // 声明是 integer 就必须按数字读：旧实现用 string() 读，客户端传 6 会被静默忽略
        val horizon = (args.int("horizon") ?: 3).coerceIn(1, 24)
        val seasonPeriod = args.int("seasonPeriod") ?: 12

        val valueIndex = if (valueColName != null) {
            val idx = dataset.columns.indexOfFirst { it.equals(valueColName, ignoreCase = true) }
            if (idx < 0) {
                throw IllegalArgumentException(
                    "找不到列「" + valueColName + "」：数据集中的列为 " +
                        dataset.columns.joinToString("、") + "。"
                )
            }
            if (dataset.columnTypes[idx] != com.stragent.core.csv.ColumnType.NUMBER) {
                val numericCols = dataset.columns.filterIndexed { i, _ -> dataset.columnTypes[i] == com.stragent.core.csv.ColumnType.NUMBER }
                throw IllegalArgumentException(
                    "列「" + valueColName + "」不是数值列，无法用于预测。" +
                        "数据集中数值列有：" + numericCols.joinToString("、").ifBlank { "（无）" } + "。"
                )
            }
            idx
        } else {
            val idx = dataset.columns.indices.firstOrNull { dataset.columnTypes[it] == com.stragent.core.csv.ColumnType.NUMBER } ?: -1
            if (idx < 0) {
                throw IllegalArgumentException("数据集中没有数值列，无法预测。")
            }
            idx
        }

        val dateIndex = findDateColumn(dataset, dateColName)
        val values = extractTimeSeries(dataset, valueIndex, dateIndex)

        if (values.size < 3) {
            return buildJsonObject {
                put("method", "none")
                put("dataPoints", values.size)
                put("horizon", horizon)
                put("error", "数据点不足：至少需要 3 个有效数值点才能预测，当前只有 ${values.size} 个。" +
                    "请检查值列「${dataset.columns[valueIndex]}」是否正确，以及空值/非数值行是否过多。")
            }
        }

        val result = autoForecast(values, horizon, seasonPeriod)

        return buildJsonObject {
            put("method", result.method)
            put("alpha", result.alpha)
            result.beta?.let { put("beta", it) }
            result.gamma?.let { put("gamma", it) }
            if (result.sse.isFinite()) put("sse", result.sse)
            if (result.rmse.isFinite()) put("rmse", result.rmse)
            put("dataPoints", result.dataPoints)
            put("horizon", result.horizon)
            put("valueColumn", dataset.columns[valueIndex])
            if (dateIndex >= 0) put("dateColumn", dataset.columns[dateIndex])
            // 预测点列表
            put(
                "forecast",
                JsonArray(
                    result.forecast.map { pt ->
                        buildJsonObject {
                            put("period", pt.period)
                            put("value", pt.value)
                            put("lower", pt.lower)
                            put("upper", pt.upper)
                        }
                    },
                ),
            )
            // 历史值（供客户端画图对照）
            put("history", JsonArray(result.history.map { num(it) }))
            put(
                "notice",
                "预测基于历史趋势外推，不包含未来事件信息；置信区间为正态近似（1.96×残差标准误差），" +
                    "是统计参考而非精确边界。方法选择依据为样本内 SSE 最小。",
            )
        }
    }

    /** JS 数字语义：JSON.stringify(550.0) 是 "550" */
    private fun num(value: Double): kotlinx.serialization.json.JsonElement {
        if (!value.isFinite()) return JsonNull
        return if (value == Math.floor(value) && kotlin.math.abs(value) < 9.007199254740992E15) {
            JsonPrimitive(value.toLong())
        } else {
            JsonPrimitive(value)
        }
    }
}
