package com.stragent.data

import com.stragent.core.ports.AgentTool
import com.stragent.core.ports.schemaArrayField
import com.stragent.core.ports.schemaField
import com.stragent.core.ports.schemaObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 能力层 → Agent 工具的适配：四条 stragent_* 契约原样变成 AgentTool。
 * 工具语义 = 契约语义（schema/描述/错误文案都来自 AbilityContracts 的契约定义）；
 * import_table 之外的能力都需要 datasetId——外部 agent 的工作流是先 import 再查。
 *
 * T-053：四条契约都声明 outputSchema（tools/list 下发、tools/call 双发
 * structuredContent）；import_table 是唯一改状态的一条（导入并推进会话指针）。
 */
fun abilityTools(datasets: AbilityDatasets): List<AgentTool> = ABILITY_CONTRACTS.map { contract ->
    object : AgentTool {
        override val name = contract.method
        override val description = contract.description
        override val parametersSchema = runCatching {
            com.stragent.core.csv.Csv // no-op keep import
            kotlinx.serialization.json.Json.parseToJsonElement(contract.inputSchemaJson) as JsonObject
        }.getOrDefault(buildJsonObject { put("type", "object") })

        override val outputSchema: JsonObject =
            ABILITY_OUTPUT_SCHEMAS[contract.method] ?: AgentTool.PERMISSIVE_OUTPUT_SCHEMA

        override val readOnly: Boolean get() = contract.method != ABILITY_IMPORT_TABLE
        override val cacheable: Boolean get() = contract.method != ABILITY_IMPORT_TABLE

        override fun invoke(args: JsonObject): JsonObject =
            StrAgentAbilities.dispatch(datasets, contract.method, args)
    }
}

/** 对外目录：tools_list 的载荷（规范名 + 描述 + 输入/输出 schema + annotations） */
fun toolsCatalog(datasets: AbilityDatasets, extraTools: List<AgentTool> = emptyList()): List<JsonObject> =
    (abilityTools(datasets) + extraTools).map { tool ->
        val exposedName = McpToolNames.exposed(tool.name)
        buildJsonObject {
            put("name", exposedName)
            put("description", tool.description)
            put("parameters", tool.parametersSchema)
            tool.outputSchema?.let { put("outputSchema", it) }
            put("annotations", McpToolNames.withTitle(tool.annotations, exposedName))
        }
    }

/** 四条能力契约的返回结构（与 AbilityService 的 buildJsonObject 逐字段对拍） */
private val ABILITY_OUTPUT_SCHEMAS: Map<String, JsonObject> = mapOf(
    ABILITY_IMPORT_TABLE to schemaObject(
        "datasetId" to schemaField("string", "内容派生的数据集编号（ds_ 前缀）"),
        "name" to schemaField("string"),
        "rows" to schemaField("integer", "数据行数（不含表头）"),
        "columns" to schemaField("integer"),
        "columnList" to schemaArrayField(
            schemaObject(
                "name" to schemaField("string"),
                "type" to schemaField("string", "number / text"),
                required = listOf("name", "type"),
            ),
            "列清单（最多 50 列）",
        ),
        "columnListTruncated" to schemaField("boolean"),
        "truncated" to schemaField("boolean", "导入是否被行数上限截断"),
        "validRows" to schemaField("integer"),
        "density" to schemaField("number", "有效单元格占比"),
        required = listOf("datasetId", "name", "rows", "columns", "columnList"),
    ),
    ABILITY_COLUMN_PROFILE to schemaObject(
        "dataset" to schemaField("string"),
        "column" to schemaField("string", "单列画像时出现"),
        "caliber" to schemaField("string", "count / missing 的口径说明"),
        "name" to schemaField("string"),
        "type" to schemaField("string"),
        "count" to schemaField("integer"),
        "missing" to schemaField("integer"),
        "min" to schemaField("number"),
        "max" to schemaField("number"),
        "sum" to schemaField("number"),
        "mean" to schemaField("number"),
        "median" to schemaField("number"),
        "p25" to schemaField("number"),
        "p75" to schemaField("number"),
        "distinct" to schemaField("integer"),
        "topValues" to schemaArrayField(
            schemaObject(
                "value" to schemaField("string"),
                "count" to schemaField("integer"),
                required = listOf("value", "count"),
            ),
        ),
        "total" to schemaField("integer", "全列概要时出现"),
        "returned" to schemaField("integer"),
        "items" to schemaArrayField(
            schemaObject(
                "name" to schemaField("string"),
                "type" to schemaField("string"),
                "count" to schemaField("integer"),
                "missing" to schemaField("integer"),
                "distinct" to schemaField("integer"),
                required = listOf("name", "type", "count", "missing"),
            ),
            "全列概要（最多 50 列）",
        ),
        required = listOf("dataset"),
    ),
    ABILITY_GROUP_AGGREGATE to schemaObject(
        "dataset" to schemaField("string"),
        "dimension" to schemaField("string"),
        "metric" to schemaField("string"),
        "mode" to schemaField("string", "sum / avg"),
        "denominator" to schemaField("string", "share 分母口径"),
        "total" to schemaField("integer"),
        "returned" to schemaField("integer"),
        "items" to schemaArrayField(
            schemaObject(
                "label" to schemaField("string"),
                "value" to schemaField("number"),
                "count" to schemaField("integer"),
                "share" to schemaField("number", "avg / 负值时可能为 null"),
                required = listOf("label", "value", "count"),
            ),
        ),
        required = listOf("dataset", "dimension", "metric", "mode", "total", "items"),
    ),
    ABILITY_LIST_ROWS to schemaObject(
        "dataset" to schemaField("string"),
        "total" to schemaField("integer"),
        "returned" to schemaField("integer"),
        "offset" to schemaField("integer"),
        "rowNumberNote" to schemaField("string"),
        "items" to schemaArrayField(
            schemaObject(
                "row" to schemaField("integer", "数据行号（从 1 起，不含表头）"),
                "cells" to schemaArrayField(schemaField("string")),
                required = listOf("row", "cells"),
            ),
        ),
        required = listOf("dataset", "total", "returned", "offset", "items"),
    ),
)
