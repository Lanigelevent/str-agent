package com.stragent.host

import com.stragent.core.ports.AgentTool
import com.stragent.core.semantic.MetricCatalog
import com.stragent.core.semantic.MetricDef
import com.stragent.core.semantic.MetricEngine
import com.stragent.core.semantic.MetricTest
import com.stragent.core.semantic.normalizeMetricName
import com.stragent.data.AbilityDatasets
import com.stragent.data.MetricStore
import com.stragent.data.SessionRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 语义层指标体系（T-055 / AD-031）的 MCP 工具面 —— 三条工具：
 *
 *  - `stragent_list_metrics`：内置 + 用户指标一览；给 datasetId 时顺带标注每个定义
 *    在这份数据上能否解析（字段落到哪列、或为什么解析不了）。
 *  - `stragent_define_metric`：保存一条用户指标定义（写 kv `semantic.metrics`）。
 *    同名/别名相交但口径不同的定义会被**写入时拒绝**，不会静默覆盖内置口径。
 *  - `stragent_evaluate_metric`：按定义求值（总值 / 按维度分组 / 按月季年分时），
 *    结果确定性可重放，数值与单位直接可核对。
 *
 * 与 `stragent_list_semantic_terms` 的分工：语义术语是**文本备忘**（不含可引用数字），
 * 指标定义是**可编译口径**（结果是统计值，会进报告摘要的 `<allowed_numbers>`）。
 */
fun listMetricsTool(
    metrics: MetricStore,
    datasets: AbilityDatasets,
): AgentTool = object : AgentTool {
    override val name = "stragent_list_metrics"
    override val description =
        "查询指标定义（内置 10 个销售/财务指标 + 用户自定义）：名称、别名、表达式、单位、" +
            "维度、粒度、自检与版本。传 datasetId 时额外标注每个定义在该数据上的解析结果" +
            "（字段落到哪一列 / 为什么解析不了）。**定义不是数据**：要数字请用 stragent_evaluate_metric。"
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "datasetId",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "可选：按这份数据标注可解析性（缺省不标注）")
                    },
                )
                put(
                    "name",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "按指标名/别名过滤（缺省全部）")
                    },
                )
            },
        )
    }

    // 定义会变（用户随时新增/修改），同参数命中缓存等于看不见刚保存的定义
    override val cacheable = false

    override fun invoke(args: JsonObject): JsonObject {
        val catalog = MetricEngine.resolveCatalog(metrics.list())
        val nameFilter = args.string("name")
        val selected = if (nameFilter != null) {
            listOfNotNull(MetricCatalog.find(nameFilter, catalog.metrics))
        } else {
            catalog.metrics
        }
        val dataset = args.string("datasetId")?.let { datasets.mustDataset(it) }

        return buildJsonObject {
            put("count", selected.size)
            put("builtinCount", catalog.metrics.count { it.builtin })
            put("userCount", catalog.metrics.count { !it.builtin })
            if (dataset != null) put("datasetId", dataset.name)
            put("metrics", JsonArray(selected.map { def ->
                buildJsonObject {
                    put("id", def.id)
                    put("name", def.name)
                    if (def.aliases.isNotEmpty()) put("aliases", JsonArray(def.aliases.map { JsonPrimitive(it) }))
                    if (def.definition.isNotEmpty()) put("definition", def.definition)
                    put("expression", def.expression)
                    put("agg", def.agg)
                    put("unit", def.unit)
                    if (def.dimensions.isNotEmpty()) put("dimensions", JsonArray(def.dimensions.map { JsonPrimitive(it) }))
                    put("grain", def.grain)
                    if (def.tests.isNotEmpty()) {
                        put("tests", JsonArray(def.tests.map { test ->
                            buildJsonObject {
                                put("kind", test.kind)
                                test.value?.let { put("value", it) }
                                test.upper?.let { put("upper", it) }
                                if (test.note.isNotEmpty()) put("note", test.note)
                            }
                        }))
                    }
                    put("version", def.version)
                    put("owner", def.owner)
                    put("builtin", def.builtin)
                    if (dataset != null) {
                        put(
                            "resolution",
                            when (val resolution = MetricEngine.resolve(def, dataset)) {
                                is MetricEngine.Resolution.Resolved -> buildJsonObject {
                                    put("resolved", true)
                                    put("compiled", resolution.compiled)
                                    put(
                                        "fields",
                                        buildJsonObject {
                                            resolution.bindings.forEach { binding ->
                                                put(binding.canonical, binding.column)
                                            }
                                        },
                                    )
                                }

                                is MetricEngine.Resolution.Unresolved -> buildJsonObject {
                                    put("resolved", false)
                                    put("reason", resolution.reason)
                                }
                            },
                        )
                    }
                }
            }))
            put("conflicts", JsonArray(catalog.conflicts.map { conflict ->
                buildJsonObject {
                    put("name", conflict.name)
                    put("left", conflict.left)
                    put("right", conflict.right)
                    put("reason", conflict.reason)
                }
            }))
            put(
                "notice",
                "定义本身不是数据：要把指标变成可引用数字，请调用 stragent_evaluate_metric；" +
                    "报告摘要会自动纳入可解析且自检通过的指标值。",
            )
        }
    }
}

/** 从 JSON 参数读字符串数组（非字符串项忽略） */
private fun JsonObject.stringList(key: String): List<String>? {
    val array = this[key] as? JsonArray ?: return null
    return array.mapNotNull { element ->
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    }
}

/** 从 JSON 参数读「对象 → 字符串数组」映射（字段别名表） */
private fun JsonObject.stringMapOfLists(key: String): Map<String, List<String>>? {
    val obj = this[key] as? JsonObject ?: return null
    val map = LinkedHashMap<String, List<String>>()
    for ((field, value) in obj) {
        val list = (value as? JsonArray)?.mapNotNull { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        } ?: continue
        if (list.isNotEmpty()) map[field] = list
    }
    return map
}

fun defineMetricTool(metrics: MetricStore): AgentTool = object : AgentTool {
    override val name = "stragent_define_metric"
    override val description =
        "保存一条指标定义（跨数据集生效）：名称 + 表达式 + 聚合/单位/维度/粒度/自检。" +
            "表达式用数据列名（可缺省 agg 自动包成 sum(...)），也可直接写聚合表达式 " +
            "（如 sum(销售额) / distinct(订单号)）。同名/别名相交但口径不同会被拒绝并说明冲突；" +
            "修改已有定义请带原 id。定义保存后，报告摘要会自动引用可解析且自检通过的指标值。"
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put("name", buildJsonObject { put("type", "string"); put("description", "指标名（如「净销售额」）") })
                put(
                    "expression",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "字段表达式或完整聚合表达式（列名需与数据一致）")
                    },
                )
                put("agg", buildJsonObject { put("type", "string"); put("description", "裸表达式的聚合方式，缺省 sum（可用：${MetricEngine.AGGREGATES.joinToString("、")}）") })
                put("unit", buildJsonObject { put("type", "string"); put("description", "单位（元/件/% 等），缺省空") })
                put("definition", buildJsonObject { put("type", "string"); put("description", "一句话口径说明（给人看）") })
                put("aliases", buildJsonObject { put("type", "array"); put("description", "别名列表（按名查找与冲突检测用）") })
                put("dimensions", buildJsonObject { put("type", "array"); put("description", "允许的分组维度（规范名；空=不限制）") })
                put("grain", buildJsonObject { put("type", "string"); put("description", "时间粒度：total/month/quarter/year，缺省 total") })
                put("tests", buildJsonObject { put("type", "array"); put("description", "自检列表：nonNegative / notZero / min:0 / max:100 / range:0:100") })
                put("owner", buildJsonObject { put("type", "string"); put("description", "负责方，缺省 user") })
                put("id", buildJsonObject { put("type", "string"); put("description", "修改已有定义时传原 id；新建可省略") })
                put(
                    "fields",
                    buildJsonObject {
                        put("type", "object")
                        put("description", "规范字段名 → 别名数组（跨数据集解析列名用；缺省按表达式里的字面列名解析）")
                    },
                )
            },
        )
        put("required", JsonArray(listOf(JsonPrimitive("name"), JsonPrimitive("expression"))))
    }

    // 写 kv，不能标只读；定义变了，同参数重复调用也不是幂等读
    override val readOnly = false
    override val cacheable = false

    override fun invoke(args: JsonObject): JsonObject {
        val name = args.string("name") ?: throw IllegalArgumentException("name 不能为空")
        val expression = args.string("expression") ?: throw IllegalArgumentException("expression 不能为空")
        val aliases = args.stringList("aliases") ?: emptyList()
        val dimensions = args.stringList("dimensions") ?: emptyList()
        val tests = (args.stringList("tests") ?: emptyList()).map { MetricTest.parse(it) }
        val def = MetricDef(
            id = args.string("id") ?: ("metric_user_" + normalizeMetricName(name)),
            name = name,
            aliases = aliases,
            definition = args.string("definition") ?: "",
            expression = expression,
            agg = args.string("agg") ?: "sum",
            unit = args.string("unit") ?: "",
            dimensions = dimensions,
            grain = args.string("grain") ?: "total",
            tests = tests,
            owner = args.string("owner") ?: "user",
            fields = args.stringMapOfLists("fields") ?: emptyMap(),
        )
        val saved = metrics.upsert(def)
        return buildJsonObject {
            put("saved", true)
            put("id", saved.id)
            put("name", saved.name)
            put("version", saved.version)
            put("expression", saved.expression)
            put("agg", saved.agg)
            put("unit", saved.unit)
            put("grain", saved.grain)
            put(
                "notice",
                "指标定义已保存（跨数据集生效）。报告摘要会自动纳入可解析且自检通过的指标值；" +
                    "用 stragent_evaluate_metric 可当场求值核对。",
            )
        }
    }
}

fun evaluateMetricTool(
    metrics: MetricStore,
    datasets: AbilityDatasets,
    registry: SessionRegistry? = null,
): AgentTool = object : AgentTool {
    override val name = "stragent_evaluate_metric"
    override val description =
        "按指标定义对已导入的数据集求值（确定性、可重放）：返回数值、单位、实际表达式、" +
            "字段映射与自检结果；可按维度分组（groupBy）或按时间粒度（month/quarter/year）分时。" +
            "定义解析不到列时直接报错并说明原因，不会用相近列顶替。"
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put("metric", buildJsonObject { put("type", "string"); put("description", "指标名或别名（内置/用户定义均可）") })
                put("datasetId", buildJsonObject { put("type", "string"); put("description", "数据集编号（缺省取会话当前数据集）") })
                put("groupBy", buildJsonObject { put("type", "string"); put("description", "分组维度（须在指标声明的维度内）") })
                put("grain", buildJsonObject { put("type", "string"); put("description", "时间粒度：total/month/quarter/year（缺省用指标定义的粒度）") })
                put("topN", buildJsonObject { put("type", "integer"); put("description", "分组返回的明细条数，缺省 8") })
            },
        )
        put("required", JsonArray(listOf(JsonPrimitive("metric"))))
    }

    override val outputSchema = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put("metric", buildJsonObject { put("type", "string") })
                put("metricId", buildJsonObject { put("type", "string") })
                put("value", buildJsonObject { put("type", "number") })
                put("unit", buildJsonObject { put("type", "string") })
                put("rows", buildJsonObject { put("type", "integer") })
                put("compiled", buildJsonObject { put("type", "string") })
                put("fields", buildJsonObject { put("type", "object") })
                put("tests", buildJsonObject { put("type", "array") })
                put("passed", buildJsonObject { put("type", "boolean") })
                put("groups", buildJsonObject { put("type", "array") })
                put("omitted", buildJsonObject { put("type", "integer") })
            },
        )
    }

    // 结果依赖当前数据集内容与定义版本：缓存会让「刚改的定义」看不见
    override val cacheable = false

    override fun invoke(args: JsonObject): JsonObject {
        val requested = args.string("metric") ?: throw IllegalArgumentException("metric 不能为空")
        val catalog = MetricEngine.resolveCatalog(metrics.list())
        val def = MetricCatalog.find(requested, catalog.metrics)
            ?: throw IllegalArgumentException(
                "没有名为「$requested」的指标定义。可用指标：" +
                    catalog.metrics.take(12).joinToString("、") { it.name } +
                    if (catalog.metrics.size > 12) " 等 ${catalog.metrics.size} 个" else "",
            )
        val datasetId = resolveDatasetId(args, datasets, registry)
        val dataset = datasets.byId(datasetId)
            ?: throw IllegalArgumentException("数据集不存在：$datasetId（请先用 stragent_import_table 导入）")

        val groupBy = args.string("groupBy")
        val grain = args.string("grain")
        val topN = args.int("topN") ?: 8
        val grouped = if (groupBy != null || grain != null) {
            MetricEngine.evaluateGrouped(def, dataset, groupBy = groupBy, grain = grain, topN = topN)
        } else {
            MetricEngine.evaluateGrouped(def, dataset, groupBy = null, grain = def.grain, topN = topN)
        }
        val total = grouped.total
        return buildJsonObject {
            put("metric", total.name)
            put("metricId", total.metricId)
            put("value", total.value)
            put("unit", total.unit)
            put("rows", total.rows)
            put("compiled", total.compiled)
            put("fields", buildJsonObject { total.fields.forEach { (field, column) -> put(field, column) } })
            put("tests", JsonArray(total.tests.map { test ->
                buildJsonObject {
                    put("kind", test.kind)
                    put("passed", test.passed)
                    put("detail", test.detail)
                }
            }))
            put("passed", total.passed)
            if (grouped.dimension != null) put("groupBy", grouped.dimension)
            put("grain", grouped.grain)
            put("groups", JsonArray(grouped.values.map { value ->
                buildJsonObject {
                    put("label", value.label)
                    put("value", value.value)
                    put("rows", value.rows)
                }
            }))
            put("omitted", grouped.omitted)
            put(
                "notice",
                if (total.passed) {
                    "指标为确定性计算，可重放复核；报告引用时必须写指标名与单位，不得改写口径。"
                } else {
                    "注意：该指标未通过定义自检，不要把结果当作可信口径引用；先核对数据或定义。"
                },
            )
        }
    }
}
