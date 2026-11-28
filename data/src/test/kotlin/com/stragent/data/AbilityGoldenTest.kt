package com.stragent.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 四条能力的金标对拍：goldens 来自本仓库 vendoring 的 ability-goldens.json
 * （data/src/test/resources，由早期跨语言对拍采集后随仓库提交，不再依赖外部
 * stepx 服务器）；Kotlin 侧逐字段对拍（datasetId 哈希、全量口径、share 置
 * null 语义、错误文案全部来自金标，不是手抄的契约）。
 */
class AbilityGoldenTest {
    private val goldens: JsonObject = Json.parseToJsonElement(
        this::class.java.classLoader.getResource("ability-goldens.json")!!.readText(),
    ).jsonObject

    private val csv = listOf(
        "门店,销售额",
        "华东,1200.5",
        "华南,800",
        "华东,300",
        "华北,",
        "华南,220.25",
    ).joinToString("\n")

    private val datasets = AbilityDatasets()

    private fun exec(method: String, params: JsonObject): JsonObject = execAbility(datasets, method, params.toString())

    private fun golden(name: String): JsonObject = goldens[name]!! as JsonObject

    private fun assertResultEquals(goldenName: String, actual: JsonObject) {
        val expected = golden(goldenName)
        assertEquals(expected, actual, "金标不匹配: $goldenName")
        assertTrue(actual["ok"]!!.toString() == "true")
    }

    private fun assertErrorEquals(goldenName: String, actual: JsonObject) {
        assertEquals(golden(goldenName), actual, "错误文案不匹配: $goldenName")
    }

    @Test
    fun `import——幂等哈希 id 与全量口径（validRows 与 density 来自活服务器）`() {
        val actual = exec(
            ABILITY_IMPORT_TABLE,
            buildJsonObject {
                put("csv", csv)
                put("name", "门店销售")
            },
        )
        assertResultEquals("import", actual)
        // 幂等：同内容重导返回同一 datasetId，注册表里只有一份
        exec(ABILITY_IMPORT_TABLE, buildJsonObject { put("csv", csv); put("name", "门店销售") })
        assertEquals(1, datasets.size())
    }

    @Test
    fun `column_profile——单列画像（PERCENTILE INC 四分位）与全列概要`() {
        exec(ABILITY_IMPORT_TABLE, buildJsonObject { put("csv", csv); put("name", "门店销售") })
        assertResultEquals(
            "profileSingle",
            exec(
                ABILITY_COLUMN_PROFILE,
                buildJsonObject { put("datasetId", "ds_e63e5002bd70"); put("column", "销售额") },
            ),
        )
        assertResultEquals(
            "profileAll",
            exec(ABILITY_COLUMN_PROFILE, buildJsonObject { put("datasetId", "ds_e63e5002bd70") }),
        )
        assertErrorEquals(
            "profileMissingColumn",
            exec(ABILITY_COLUMN_PROFILE, buildJsonObject { put("datasetId", "ds_e63e5002bd70"); put("column", "不存在") }),
        )
    }

    @Test
    fun `group_aggregate——sum 的 share、avg 置 null、维度类型校验`() {
        exec(ABILITY_IMPORT_TABLE, buildJsonObject { put("csv", csv); put("name", "门店销售") })
        assertResultEquals(
            "aggregateSum",
            exec(
                ABILITY_GROUP_AGGREGATE,
                buildJsonObject { put("datasetId", "ds_e63e5002bd70"); put("dimension", "门店"); put("metric", "销售额") },
            ),
        )
        assertResultEquals(
            "aggregateAvg",
            exec(
                ABILITY_GROUP_AGGREGATE,
                buildJsonObject {
                    put("datasetId", "ds_e63e5002bd70"); put("dimension", "门店"); put("metric", "销售额"); put("mode", "avg")
                },
            ),
        )
        assertErrorEquals(
            "aggregateBadDim",
            exec(
                ABILITY_GROUP_AGGREGATE,
                buildJsonObject { put("datasetId", "ds_e63e5002bd70"); put("dimension", "销售额"); put("metric", "门店") },
            ),
        )
        // sum 结果里 share 都是有限数、avg 里全为 null（不给错的数）
        val sumItems = golden("aggregateSum")["result"]!!.let { it as JsonObject }["items"] as JsonArray
        assertTrue(sumItems.all { ((it as JsonObject)["share"] as? JsonNull) == null })
        val avgItems = golden("aggregateAvg")["result"]!!.let { it as JsonObject }["items"] as JsonArray
        assertTrue(avgItems.all { (it as JsonObject)["share"] is JsonNull })
    }

    @Test
    fun `list_rows——分页偏移与行号语义`() {
        exec(ABILITY_IMPORT_TABLE, buildJsonObject { put("csv", csv); put("name", "门店销售") })
        assertResultEquals(
            "listRows",
            exec(
                ABILITY_LIST_ROWS,
                buildJsonObject { put("datasetId", "ds_e63e5002bd70"); put("offset", 1.0); put("limit", 2.0) },
            ),
        )
        assertErrorEquals(
            "missingDataset",
            exec(ABILITY_LIST_ROWS, buildJsonObject { put("datasetId", "ds_no_such") }),
        )
    }

    @Test
    fun `错误路径——空 csv、仅表头、未知能力（文案与活服务器逐字一致）`() {
        assertErrorEquals(
            "importEmpty",
            exec(ABILITY_IMPORT_TABLE, buildJsonObject { put("csv", "   ") }),
        )
        assertErrorEquals(
            "importHeaderOnly",
            exec(ABILITY_IMPORT_TABLE, buildJsonObject { put("csv", "a,b\n") }),
        )
        assertErrorEquals(
            "unknownMethod",
            exec(ABILITY_NO_SUCH, buildJsonObject { }),
        )
    }

    @Test
    fun `契约清单——四条方法的 description 与 inputSchema 由契约定义生成`() {
        assertEquals(4, ABILITY_CONTRACTS.size)
        assertEquals(setOf(ABILITY_IMPORT_TABLE, ABILITY_COLUMN_PROFILE, ABILITY_GROUP_AGGREGATE, ABILITY_LIST_ROWS), ABILITY_CONTRACTS.map { it.method }.toSet())
        // /search 的 schema 与 description 来自 ABILITY_CONTRACTS 定义
        assertTrue(ABILITY_CONTRACTS.all { it.description.length > 80 })
        assertTrue(ABILITY_CONTRACTS.all { it.inputSchemaJson.contains("required") })
    }

    private companion object {
        const val ABILITY_NO_SUCH = "stragent_nothing"
    }
}
