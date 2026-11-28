package com.stragent.host

import com.stragent.agent.AGENT_HARNESS_CONTRACT
import com.stragent.agent.AgentLoop
import com.stragent.agent.PluginManifest
import com.stragent.core.ports.ToolRegistry
import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.LlmClient
import com.stragent.data.AbilityDatasets
import com.stragent.data.ABILITY_COLUMN_PROFILE
import com.stragent.data.ABILITY_GROUP_AGGREGATE
import com.stragent.data.ABILITY_IMPORT_TABLE
import com.stragent.data.ABILITY_LIST_ROWS
import com.stragent.data.abilityTools
import com.stragent.data.execAbility
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 对外端口：stragent_tools_list（目录）与 stragent_tools_call（执行）——
 * 外部 Agent（stepfun.ts 注释里的 A2A 方向）经这两个方法使用Str Agent的工具面；
 * 原四条能力契约原样保留（AbilityGoldenTest 继续守着）。
 */
class ToolsPortTest {
    private val datasets = AbilityDatasets()
    private var seededId = ""

    private fun seed(): String {
        if (seededId.isEmpty()) {
            val csv = "门店,销售额\n华东,1200.5\n华南,800\n华东,300\n华南,220.25"
            val response = execAbility(datasets, ABILITY_IMPORT_TABLE, """{"csv":"$csv","name":"门店销售"}""")
            seededId = ((response["result"] as JsonObject)["datasetId"] as kotlinx.serialization.json.JsonPrimitive).content
        }
        return seededId
    }

    private val goldens = true
    private fun goldenJsonObject(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `tools_list——四条能力契约在目录里（描述与 schema 来自契约定义）`() {
        val response = execAbility(datasets, "stragent_tools_list", "{}")
        assertEquals("true", response["ok"].toString())
        val tools = ((response["result"] as JsonObject)["tools"]) as JsonArray
        val names = tools.map { ((((it as JsonObject)["name"]) as kotlinx.serialization.json.JsonPrimitive).content) }
        assertEquals(
            listOf(
                ABILITY_IMPORT_TABLE, ABILITY_COLUMN_PROFILE,
                ABILITY_GROUP_AGGREGATE, ABILITY_LIST_ROWS,
            ),
            names,
        )
    }

    @Test
    fun `tools_call——经工具面执行 group_aggregate（列名校验同能力层）`() {
        val id = seed()
        val response = execAbility(
            datasets, "stragent_tools_call",
            """{"name":"stragent_get_group_aggregate","args":{"datasetId":"$id","dimension":"门店","metric":"销售额"}}""",
        )
        assertEquals("true", response["ok"].toString())
        val items = response["result"]!!.let { (it as JsonObject)["items"] as JsonArray }
        assertEquals(2, items.size)
        // 未知工具给可行动的中文错误
        val unknown = execAbility(datasets, "stragent_tools_call", """{"name":"no_such","args":{}}""")
        assertEquals("false", unknown["ok"].toString())
        assertTrue(unknown["error"].toString().contains("未知工具: no_such"))
    }

    @Test
    fun `能力错误原样透传（tools_call 的 error 不被吞）`() {
        val response = execAbility(datasets, "stragent_tools_call", """{"name":"stragent_import_table","args":{"csv":"   "}}""")
        assertEquals("false", response["ok"].toString())
        assertTrue(response["error"].toString().contains("csv 为空"))
    }

    @Test
    fun `agent 插件走 AbilityTools——import 后 list_rows 可用`() {
        val id = seed()
        val tools = abilityTools(datasets)
        assertEquals(4, tools.size)
        val rows = tools.find { it.name == "stragent_list_rows" }!!.invoke(
            buildJsonObject { put("datasetId", id); put("limit", 2.0) },
        )
        assertTrue(rows.toString().contains("\"row\":1"))
    }

    @Test
    fun `AgentLoop 与能力工具兼容——import 后循环内查数再 finish`() {
        val id = seed()
        val registry = ToolRegistry.of(abilityTools(datasets))
        val manifest = PluginManifest(
            id = "ability-test", name = "能力测试", kind = "report",
            systemPrompt = "查华东的销售额合计后 finish。",
            tools = listOf(ABILITY_COLUMN_PROFILE, ABILITY_GROUP_AGGREGATE, ABILITY_LIST_ROWS),
            maxSteps = 5,
        )
        val llm = object : LlmClient {
            var round = 0
            override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int): ChatResult {
                round += 1
                val last = messages.last().second
                return if (round == 1) {
                    ChatResult("""{"thought":"查分组","tool":"stragent_get_group_aggregate","args":{"datasetId":"$id","dimension":"门店","metric":"销售额"}}""", 10)
                } else {
                    // 工具结果回填后应能看到华东 1500.5
                    assertTrue(messages.any { it.second.contains("1500.5") }, "工具结果应回填: $last")
                    ChatResult("""{"tool":"finish","args":{"report":"华东合计 1500.5"}}""", 10)
                }
            }
        }
        val outcome = AgentLoop(llm).run(manifest, registry, "m", "任务", AGENT_HARNESS_CONTRACT)
        assertEquals("华东合计 1500.5", outcome!!.output["report"]?.toString()?.trim('"'))
        assertEquals(1, outcome.toolCalls)
    }
}
