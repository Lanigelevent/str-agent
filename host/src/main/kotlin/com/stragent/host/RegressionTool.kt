package com.stragent.host

import com.stragent.core.ports.AgentTool
import com.stragent.core.ports.schemaArrayField
import com.stragent.core.ports.schemaField
import com.stragent.core.ports.schemaObject
import com.stragent.core.stats.linearRegression
import com.stragent.core.stats.findNumericColumn
import com.stragent.data.AbilityDatasets
import com.stragent.data.SessionRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 线性回归工具 —— D10 阶段 5 统计方法扩充。
 *
 * 对已导入的数据集做一元线性回归（最小二乘法）。
 * 返回斜率/截距/R²/标准误差/t 统计量/p 值。
 * 纯确定性，可重放复核。
 */
fun regressionTool(
    datasets: AbilityDatasets,
    registry: SessionRegistry? = null,
): AgentTool = object : AgentTool {
    override val name = "stragent_regression"
    override val description =
        "对已导入的数据集做一元线性回归（最小二乘法）：y = intercept + slope * x。" +
        "返回斜率、截距、R²、标准误差、t 统计量、p 值与拟合预测值。" +
        "需要至少 3 个有效数据点。纯确定性，可重放复核。"
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put("datasetId", buildJsonObject {
                    put("type", "string")
                    put("description", "数据集编号（缺省取会话当前数据集）")
                })
                put("xColumn", buildJsonObject {
                    put("type", "string")
                    put("description", "自变量列名（数值列），必填")
                })
                put("yColumn", buildJsonObject {
                    put("type", "string")
                    put("description", "因变量列名（数值列），必填")
                })
            },
        )
        put("required", JsonArray(listOf(JsonPrimitive("xColumn"), JsonPrimitive("yColumn"))))
    }

    override val cacheable = false

    /** T-053：返回结构（一元线性回归 + 拟合明细） */
    override val outputSchema = schemaObject(
        "xColumn" to schemaField("string"),
        "yColumn" to schemaField("string"),
        "slope" to schemaField("number"),
        "intercept" to schemaField("number"),
        "rSquared" to schemaField("number"),
        "standardError" to schemaField("number"),
        "tStatistic" to schemaField("number"),
        "pValue" to schemaField("number"),
        "sampleSize" to schemaField("integer"),
        "significant" to schemaField("boolean", "pValue < 0.05"),
        "equation" to schemaField("string"),
        "predictions" to schemaArrayField(
            schemaObject(
                "x" to schemaField("number"),
                "actual" to schemaField("number"),
                "predicted" to schemaField("number"),
                required = listOf("x", "actual", "predicted"),
            ),
        ),
        "notice" to schemaField("string"),
        required = listOf("xColumn", "yColumn", "slope", "intercept", "rSquared", "sampleSize"),
    )

    override fun invoke(args: JsonObject): JsonObject {
        val datasetId = resolveDatasetId(args, datasets, registry)
        val dataset = datasets.byId(datasetId)
            ?: throw IllegalArgumentException("数据集不存在：$datasetId（请先用 stragent_import_table 导入）")

        val xColName = args.string("xColumn")
            ?: throw IllegalArgumentException("xColumn 不能为空")
        val yColName = args.string("yColumn")
            ?: throw IllegalArgumentException("yColumn 不能为空")

        val xIdx = findNumericColumn(dataset, xColName)
        when (xIdx) {
            -1 -> throw IllegalArgumentException("找不到列「$xColName」：数据集中的列为 " + dataset.columns.joinToString("、") + "。")
            -2 -> throw IllegalArgumentException("列「$xColName」不是数值列，无法用于回归。")
        }
        val yIdx = findNumericColumn(dataset, yColName)
        when (yIdx) {
            -1 -> throw IllegalArgumentException("找不到列「$yColName」：数据集中的列为 " + dataset.columns.joinToString("、") + "。")
            -2 -> throw IllegalArgumentException("列「$yColName」不是数值列，无法用于回归。")
        }

        val result = linearRegression(dataset, xIdx, yIdx)
        return buildJsonObject {
            put("xColumn", result.xColumn)
            put("yColumn", result.yColumn)
            put("slope", result.slope)
            put("intercept", result.intercept)
            put("rSquared", result.rSquared)
            put("standardError", result.standardError)
            put("tStatistic", result.tStatistic)
            put("pValue", result.pValue)
            put("sampleSize", result.sampleSize)
            put("significant", result.pValue < 0.05)
            put("equation", "y = ${result.intercept} + ${result.slope} * x")
            put(
                "predictions",
                JsonArray(result.predictions.map { p ->
                    buildJsonObject {
                        put("x", p.first)
                        put("actual", p.second)
                        put("predicted", result.intercept + result.slope * p.first)
                    }
                }),
            )
            put(
                "notice",
                "p 值基于 t 分布查表近似（自由度 ${result.sampleSize - 2}）；" +
                    "只做一元线性回归；不做残差正态性检验。",
            )
        }
    }
}
