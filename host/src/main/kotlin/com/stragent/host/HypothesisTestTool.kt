package com.stragent.host

import com.stragent.core.ports.AgentTool
import com.stragent.core.ports.schemaArrayField
import com.stragent.core.ports.schemaField
import com.stragent.core.ports.schemaObject
import com.stragent.core.stats.chiSquareTest
import com.stragent.core.stats.independentTTest
import com.stragent.data.AbilityDatasets
import com.stragent.data.SessionRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 假设检验工具 —— D10 阶段 5 统计方法扩充。
 *
 * 支持两种检验：
 *  - t-test：独立样本 t 检验（比较两组数值列的均值差异）
 *  - chi-square：卡方独立性检验（检验两个分类列的关联性）
 *
 * 纯确定性，可重放复核。
 */
fun hypothesisTestTool(
    datasets: AbilityDatasets,
    registry: SessionRegistry? = null,
): AgentTool = object : AgentTool {
    override val name = "stragent_hypothesis_test"
    override val description =
        "对已导入的数据集做假设检验。支持两种检验：" +
        "t-test（独立样本 t 检验，比较两组数值列的均值差异）和 " +
        "chi-square（卡方独立性检验，检验两个分类列的关联性）。" +
        "返回统计量、p 值与结论解读。纯确定性，可重放复核。"
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put("datasetId", buildJsonObject {
                    put("type", "string")
                    put("description", "数据集编号（缺省取会话当前数据集）")
                })
                put("testType", buildJsonObject {
                    put("type", "string")
                    put("description", "检验类型：t-test（独立样本 t 检验）/ chi-square（卡方独立性检验），必填")
                })
                put("groupColumn", buildJsonObject {
                    put("type", "string")
                    put("description", "t-test 的分组列名（文本列）；chi-square 的行维度列名")
                })
                put("valueColumn", buildJsonObject {
                    put("type", "string")
                    put("description", "t-test 的数值列名（数值列）；chi-square 不需要")
                })
                put("columnColumn", buildJsonObject {
                    put("type", "string")
                    put("description", "chi-square 的列维度列名（文本列）；t-test 不需要")
                })
                put("groupA", buildJsonObject {
                    put("type", "string")
                    put("description", "t-test 比较的分组 A 值（缺省取第一个分组值）")
                })
                put("groupB", buildJsonObject {
                    put("type", "string")
                    put("description", "t-test 比较的分组 B 值（缺省取第二个分组值）")
                })
            },
        )
        put("required", JsonArray(listOf(JsonPrimitive("testType"))))
    }

    override val cacheable = false

    /** T-053：t-test 与 chi-square 两个分支的并集（按 testType 取用其中一组字段） */
    override val outputSchema = schemaObject(
        "testType" to schemaField("string", "t-test / chi-square"),
        "groupColumn" to schemaField("string", "t-test"),
        "valueColumn" to schemaField("string", "t-test"),
        "groupA" to schemaField("string"),
        "groupB" to schemaField("string"),
        "meanA" to schemaField("number"),
        "meanB" to schemaField("number"),
        "tStatistic" to schemaField("number"),
        "sampleA" to schemaField("integer"),
        "sampleB" to schemaField("integer"),
        "rowColumn" to schemaField("string", "chi-square"),
        "colColumn" to schemaField("string", "chi-square"),
        "chiSquare" to schemaField("number"),
        "degreesOfFreedom" to schemaField("integer"),
        "rowLabels" to schemaArrayField(schemaField("string")),
        "colLabels" to schemaArrayField(schemaField("string")),
        "observed" to schemaArrayField(schemaArrayField(schemaField("integer")), "观测频数矩阵"),
        "expected" to schemaArrayField(schemaArrayField(schemaField("number")), "期望频数矩阵"),
        "pValue" to schemaField("number"),
        "significant" to schemaField("boolean", "pValue < 0.05"),
        "interpretation" to schemaField("string"),
        "notice" to schemaField("string"),
        required = listOf("testType", "pValue", "significant", "interpretation"),
    )

    override fun invoke(args: JsonObject): JsonObject {
        val datasetId = resolveDatasetId(args, datasets, registry)
        val dataset = datasets.byId(datasetId)
            ?: throw IllegalArgumentException("数据集不存在：$datasetId（请先用 stragent_import_table 导入）")

        val testType = args.string("testType")
            ?: throw IllegalArgumentException("testType 不能为空")

        return when (testType.lowercase()) {
            "t-test", "ttest", "t_test" -> {
                val groupColName = args.string("groupColumn")
                    ?: throw IllegalArgumentException("t 检验需要 groupColumn 参数（分组列名）")
                val valueColName = args.string("valueColumn")
                    ?: throw IllegalArgumentException("t 检验需要 valueColumn 参数（数值列名）")

                val groupIdx = dataset.columns.indexOfFirst { it.equals(groupColName, ignoreCase = true) }
                if (groupIdx < 0) throw IllegalArgumentException("找不到列「$groupColName」：数据集中的列为 " + dataset.columns.joinToString("、") + "。")

                val valueIdx = com.stragent.core.stats.findNumericColumn(dataset, valueColName)
                when (valueIdx) {
                    -1 -> throw IllegalArgumentException("找不到列「$valueColName」：数据集中的列为 " + dataset.columns.joinToString("、") + "。")
                    -2 -> throw IllegalArgumentException("列「$valueColName」不是数值列，无法用于 t 检验。")
                }

                val result = independentTTest(dataset, groupIdx, valueIdx, args.string("groupA"), args.string("groupB"))
                buildJsonObject {
                    put("testType", "t-test")
                    put("groupColumn", dataset.columns[groupIdx])
                    put("valueColumn", dataset.columns[valueIdx])
                    put("groupA", result.groupA)
                    put("groupB", result.groupB)
                    put("meanA", result.meanA)
                    put("meanB", result.meanB)
                    put("tStatistic", result.tStatistic)
                    put("pValue", result.pValue)
                    put("sampleA", result.sampleA)
                    put("sampleB", result.sampleB)
                    put("significant", result.significant)
                    put("interpretation", result.interpretation)
                    put("notice", "假设方差齐性（Student's t）；p 值基于 t 分布查表近似。")
                }
            }
            "chi-square", "chisquare", "chi_square", "chi2" -> {
                val rowColName = args.string("groupColumn")
                    ?: throw IllegalArgumentException("卡方检验需要 groupColumn 参数（行维度列名）")
                val colColName = args.string("columnColumn")
                    ?: throw IllegalArgumentException("卡方检验需要 columnColumn 参数（列维度列名）")

                val rowIdx = dataset.columns.indexOfFirst { it.equals(rowColName, ignoreCase = true) }
                if (rowIdx < 0) throw IllegalArgumentException("找不到列「$rowColName」：数据集中的列为 " + dataset.columns.joinToString("、") + "。")
                val colIdx = dataset.columns.indexOfFirst { it.equals(colColName, ignoreCase = true) }
                if (colIdx < 0) throw IllegalArgumentException("找不到列「$colColName」：数据集中的列为 " + dataset.columns.joinToString("、") + "。")

                val result = chiSquareTest(dataset, rowIdx, colIdx)
                buildJsonObject {
                    put("testType", "chi-square")
                    put("rowColumn", dataset.columns[rowIdx])
                    put("colColumn", dataset.columns[colIdx])
                    put("chiSquare", result.chiSquare)
                    put("pValue", result.pValue)
                    put("degreesOfFreedom", result.degreesOfFreedom)
                    put("significant", result.significant)
                    put("interpretation", result.interpretation)
                    put("rowLabels", JsonArray(result.rowLabels.map { JsonPrimitive(it) }))
                    put("colLabels", JsonArray(result.colLabels.map { JsonPrimitive(it) }))
                    put(
                        "observed",
                        JsonArray(result.observed.map { row ->
                            JsonArray(row.map { JsonPrimitive(it) })
                        }),
                    )
                    put(
                        "expected",
                        JsonArray(result.expected.map { row ->
                            JsonArray(row.map { JsonPrimitive(Math.round(it * 100) / 100.0) })
                        }),
                    )
                    put("notice", "p 值基于卡方分布查表近似；最多保留 8 个类别（超出合并为「其他」）。")
                }
            }
            else -> throw IllegalArgumentException("不支持的检验类型「$testType」：请用 t-test 或 chi-square")
        }
    }
}
