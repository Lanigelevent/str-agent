package com.stragent.host

import com.stragent.core.ports.AgentTool
import com.stragent.core.ports.schemaArrayField
import com.stragent.core.ports.schemaField
import com.stragent.core.ports.schemaObject
import com.stragent.core.stats.WhatIfOperation
import com.stragent.core.stats.findGroupByColumn
import com.stragent.core.stats.findWhatIfColumn
import com.stragent.core.stats.whatIf
import com.stragent.data.AbilityDatasets
import com.stragent.data.SessionRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 场景模拟工具 —— D10 阶段 5 第二落地。
 *
 * 对已导入的数据集做 what-if 分析：对指定列施加变换（乘/加/设值），
 * 重算 sum/avg/min/max/count，输出 before/after 对比与变化幅度。
 *
 * **纯 Kotlin 零依赖、确定性**——与溯源清单纪律兼容。
 *
 * 诚实边界（不冒称）：
 *  - 只做单列变换（多列联动走 stragent_compute）
 *  - 不做条件变换（"只对华东加价"走 stragent_compute_python）
 *  - 变换后的数据不落盘、不覆盖原数据集——只是推算
 */
fun whatIfTool(
    datasets: AbilityDatasets,
    registry: SessionRegistry? = null,
): AgentTool = object : AgentTool {
    override val name = "stragent_whatif"
    override val description =
        "对已导入的数据集做场景模拟（what-if）：对指定列施加变换（乘/加/设值），" +
        "重算关键统计量，输出变换前后的对比与变化幅度。" +
        "支持按分组列拆分对比。纯确定性，可重放复核。" +
        "变换后的数据不落盘——只是推算。"
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put("datasetId", buildJsonObject {
                    put("type", "string")
                    put("description", "数据集编号（缺省取会话当前数据集）")
                })
                put("column", buildJsonObject {
                    put("type", "string")
                    put("description", "要变换的数值列名（缺省取第一个数值列）")
                })
                put("operation", buildJsonObject {
                    put("type", "string")
                    put("description", "变换操作：multiply（乘）/ add（加）/ set（设为），缺省 multiply")
                })
                put("factor", buildJsonObject {
                    put("type", "number")
                    put("description", "变换因子：multiply=乘数（1.1=涨10%）、add=加数、set=设值")
                })
                put("groupBy", buildJsonObject {
                    put("type", "string")
                    put("description", "分组列名（缺省=全局统计，不分组）")
                })
            },
        )
        put("required", JsonArray(listOf(JsonPrimitive("factor"))))
    }

    override val cacheable = false

    /** T-053：返回结构（before/after 为分组统计，delta 为变化量） */
    override val outputSchema = schemaObject(
        "column" to schemaField("string"),
        "operation" to schemaField("string", "multiply / add / set"),
        "factor" to schemaField("number"),
        "groupBy" to schemaField("string", "提供分组列时出现"),
        "before" to schemaArrayField(STAT_SCHEMA, "变换前分组统计"),
        "after" to schemaArrayField(STAT_SCHEMA, "变换后分组统计"),
        "delta" to schemaArrayField(
            schemaObject(
                "group" to schemaField("string"),
                "sumDelta" to schemaField("number"),
                "sumDeltaPct" to schemaField("number", "分母为 0 时省略"),
                "avgDelta" to schemaField("number"),
                "avgDeltaPct" to schemaField("number", "分母为 0 时省略"),
                required = listOf("group", "sumDelta", "avgDelta"),
            ),
            "分组变化量",
        ),
        "notice" to schemaField("string"),
        required = listOf("column", "operation", "factor", "before", "after", "delta"),
    )

    override fun invoke(args: JsonObject): JsonObject {
        val datasetId = resolveDatasetId(args, datasets, registry)
        val dataset = datasets.byId(datasetId)
            ?: throw IllegalArgumentException("数据集不存在：$datasetId（请先用 stragent_import_table 导入）")

        val colName = args.string("column")
        val operationStr = args.string("operation") ?: "multiply"
        val operation = WhatIfOperation.fromId(operationStr)
            ?: throw IllegalArgumentException("不支持的操作「$operationStr」：请用 multiply / add / set")

        val factor = args.double("factor")
            ?: throw IllegalArgumentException("factor 不能为空或不是合法数值")

        val valueIndex = findWhatIfColumn(dataset, colName)
        when (valueIndex) {
            -1 -> throw IllegalArgumentException("数据集中没有数值列，无法做场景模拟。")
            -2 -> {
                val numericCols = dataset.columns.filterIndexed { i, _ -> dataset.columnTypes[i] == com.stragent.core.csv.ColumnType.NUMBER }
                throw IllegalArgumentException(
                    "列「$colName」不是数值列，无法用于场景模拟。" +
                        "数据集中数值列有：" + numericCols.joinToString("、").ifBlank { "（无）" } + "。"
                )
            }
            -3 -> throw IllegalArgumentException(
                "找不到列「$colName」：数据集中的列为 " + dataset.columns.joinToString("、") + "。"
            )
        }

        val groupByStr = args.string("groupBy")
        val groupByIndex = if (groupByStr != null) {
            val idx = findGroupByColumn(dataset, groupByStr)
            if (idx < 0) {
                throw IllegalArgumentException(
                    "找不到分组列「$groupByStr」：数据集中的列为 " + dataset.columns.joinToString("、") + "。"
                )
            }
            idx
        } else {
            -1
        }

        val result = whatIf(dataset, valueIndex, operation, factor, groupByIndex)

        return buildJsonObject {
            put("column", result.column)
            put("operation", result.operation)
            put("factor", result.factor)
            result.groupBy?.let { put("groupBy", it) }
            put("before", JsonArray(result.before.map { s -> statToJson(s) }))
            put("after", JsonArray(result.after.map { s -> statToJson(s) }))
            put("delta", JsonArray(result.delta.map { d -> deltaToJson(d) }))
            put("notice", "场景模拟结果不落盘、不覆盖原数据——只是推算。" +
                "变换只作用于指定列，其他列不变。")
        }
    }

    private fun statToJson(s: com.stragent.core.stats.GroupStat): JsonObject = buildJsonObject {
        put("group", s.group)
        put("count", s.count)
        put("sum", s.sum)
        put("avg", s.avg)
        put("min", s.min)
        put("max", s.max)
    }

    private fun deltaToJson(d: com.stragent.core.stats.GroupDelta): JsonObject = buildJsonObject {
        put("group", d.group)
        put("sumDelta", d.sumDelta)
        if (d.sumDeltaPct.isFinite()) put("sumDeltaPct", d.sumDeltaPct)
        put("avgDelta", d.avgDelta)
        if (d.avgDeltaPct.isFinite()) put("avgDeltaPct", d.avgDeltaPct)
    }
}

/** before/after 共用的分组统计结构 */
private val STAT_SCHEMA = schemaObject(
    "group" to schemaField("string"),
    "count" to schemaField("integer"),
    "sum" to schemaField("number"),
    "avg" to schemaField("number"),
    "min" to schemaField("number"),
    "max" to schemaField("number"),
    required = listOf("group", "count", "sum", "avg", "min", "max"),
)
