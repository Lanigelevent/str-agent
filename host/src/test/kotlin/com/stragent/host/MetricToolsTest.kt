package com.stragent.host

import com.stragent.data.abilityTools
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-055 · 指标工具面端到端（宿主层）。
 *
 * 证明三件事：
 *  1. 内置指标可直接求值，数值/字段映射/自检都在回包里（确定性可核对）；
 *  2. 用户定义能保存、升版、参与求值，口径冲突与非法定义在写入时就被挡住；
 *  3. 报告摘要会带上可解析且自检通过的指标值——模型被要求写指标名，而不是现编口径；
 *     没有可用指标时摘要不出现空块（非销售数据的简报零变化）。
 */
class MetricToolsTest {

    private fun tempHome(): File =
        File(System.getProperty("java.io.tmpdir"), "str-agent-metric-${System.nanoTime()}").also { it.mkdirs() }

    private fun bootstrap(home: File, llm: com.stragent.core.ports.LlmClient = FakeLlmClient()): StrAgentHostBootstrap =
        StrAgentHostBootstrap.create(home, llm = llm)

    private fun tools(b: StrAgentHostBootstrap) =
        abilityTools(b.abilityDatasets) +
            listMetricsTool(b.metrics, b.abilityDatasets) +
            defineMetricTool(b.metrics) +
            evaluateMetricTool(b.metrics, b.abilityDatasets, b.sessionRegistry) +
            pluginTools(b.plugin, b.abilityDatasets, b.memory, b.semantic, b.metrics)

    private fun tool(b: StrAgentHostBootstrap, name: String) = tools(b).first { it.name == name }

    private fun import(b: StrAgentHostBootstrap, csv: String, name: String = "测试数据"): String {
        val args = buildJsonObject { put("csv", csv); put("name", name) }
        return tool(b, "stragent_import_table").invoke(args)["datasetId"]!!.jsonPrimitive.content
    }

    private fun evaluate(b: StrAgentHostBootstrap, args: JsonObject): JsonObject =
        tool(b, "stragent_evaluate_metric").invoke(args)

    private fun metricArgs(datasetId: String, metric: String, extra: JsonObject = JsonObject(emptyMap())) =
        buildJsonObject {
            put("datasetId", datasetId)
            put("metric", metric)
            extra.forEach { (key, value) -> put(key, value) }
        }

    private val salesCsv = """
        门店,销售额,数量
        东店,1200,10
        西店,800,8
        南店,300,3
        东店,300,3
        西店,200,2
        南店,100,1
    """.trimIndent()

    private val neutralCsv = """
        地区,人数
        华东,10
        华南,8
    """.trimIndent()

    // ---------------- 1. 内置指标求值 ----------------

    @Test
    fun `内置指标可求值——数值、单位、实际表达式与字段映射都在回包`() {
        bootstrap(tempHome()).use { b ->
            val datasetId = import(b, salesCsv)
            val result = evaluate(b, metricArgs(datasetId, "销售额"))

            assertEquals(2900.0, result["value"]!!.jsonPrimitive.doubleOrNull)
            assertEquals("元", result["unit"]!!.jsonPrimitive.content)
            assertEquals(6, result["rows"]!!.jsonPrimitive.intOrNull)
            assertEquals("sum(销售额)", result["compiled"]!!.jsonPrimitive.content, "口径必须能核对")
            assertEquals("销售额", result["fields"]!!.jsonObject["销售额"]!!.jsonPrimitive.content)
            assertTrue(result["passed"]!!.jsonPrimitive.booleanOrNull!!, "内置定义的自检应通过")
            assertTrue(result["tests"]!!.jsonArray.isNotEmpty(), "自检结果必须随回包交出")

            // 同一份数据重复求值：结果逐字一致（确定性，可重放）
            val again = evaluate(b, metricArgs(datasetId, "销售额"))
            assertEquals(result["value"], again["value"], "重复求值必须同值")
        }
    }

    @Test
    fun `维度分组与 topN 生效`() {
        bootstrap(tempHome()).use { b ->
            val datasetId = import(b, salesCsv)
            val grouped = evaluate(
                b,
                metricArgs(
                    datasetId, "销售额",
                    buildJsonObject { put("groupBy", "门店"); put("topN", 2) },
                ),
            )
            val groups = grouped["groups"]!!.jsonArray
            assertEquals(2, groups.size, "topN=2 应只返回两条明细")
            assertEquals(1, grouped["omitted"]!!.jsonPrimitive.intOrNull, "剩余 1 个门店应记为 omitted")
            assertEquals("东店", groups[0].jsonObject["label"]!!.jsonPrimitive.content)
            assertEquals(1500.0, groups[0].jsonObject["value"]!!.jsonPrimitive.doubleOrNull)
            assertEquals(1000.0, groups[1].jsonObject["value"]!!.jsonPrimitive.doubleOrNull)
        }
    }

    @Test
    fun `时间粒度按月分组`() {
        bootstrap(tempHome()).use { b ->
            val datasetId = import(b, "月份,销售额\n2024-01,100\n2024-02,200\n2024-01,50\n", "月度数据")
            val monthly = evaluate(
                b,
                metricArgs(datasetId, "销售额", buildJsonObject { put("grain", "month") }),
            )
            assertEquals("month", monthly["grain"]!!.jsonPrimitive.content)
            val groups = monthly["groups"]!!.jsonArray
            assertEquals(2, groups.size)
            assertEquals("2024-01", groups[0].jsonObject["label"]!!.jsonPrimitive.content)
            assertEquals(150.0, groups[0].jsonObject["value"]!!.jsonPrimitive.doubleOrNull)
            assertEquals(200.0, groups[1].jsonObject["value"]!!.jsonPrimitive.doubleOrNull)
        }
    }

    // ---------------- 2. 用户定义 ----------------

    @Test
    fun `用户指标可定义、升版并参与求值`() {
        bootstrap(tempHome()).use { b ->
            val datasetId = import(b, salesCsv)
            val define = tool(b, "stragent_define_metric")

            val created = define.invoke(
                buildJsonObject {
                    put("name", "双倍销售额")
                    put("expression", "销售额 * 2")
                    put("unit", "元")
                    put("tests", JsonArray(listOf(JsonPrimitive("nonNegative"))))
                },
            )
            assertEquals(1, created["version"]!!.jsonPrimitive.intOrNull)

            val listed = tool(b, "stragent_list_metrics").invoke(buildJsonObject {})
            assertEquals(1, listed["userCount"]!!.jsonPrimitive.intOrNull)
            assertEquals(10, listed["builtinCount"]!!.jsonPrimitive.intOrNull)

            val first = evaluate(b, metricArgs(datasetId, "双倍销售额"))
            assertEquals(5800.0, first["value"]!!.jsonPrimitive.doubleOrNull)

            // 同名再定义（不传 id）→ 复用默认 id、口径变化升版
            val updated = define.invoke(
                buildJsonObject {
                    put("name", "双倍销售额")
                    put("expression", "销售额 * 3")
                },
            )
            assertEquals(2, updated["version"]!!.jsonPrimitive.intOrNull, "口径变化应升版")

            val second = evaluate(b, metricArgs(datasetId, "双倍销售额"))
            assertEquals(8700.0, second["value"]!!.jsonPrimitive.doubleOrNull)
            assertEquals(1, b.metrics.list().size, "同名更新不能产生第二份定义")
        }
    }

    @Test
    fun `口径冲突与非法定义在写入时被拒`() {
        bootstrap(tempHome()).use { b ->
            val define = tool(b, "stragent_define_metric")

            val duplicate = assertFailsWith<IllegalArgumentException> {
                define.invoke(
                    buildJsonObject {
                        put("name", "销售额")
                        put("expression", "sum(销售额)")
                        put("unit", "元")
                    },
                )
            }
            assertTrue(duplicate.message!!.contains("已存在同口径"), duplicate.message)

            val conflict = assertFailsWith<IllegalArgumentException> {
                define.invoke(buildJsonObject { put("name", "销售额"); put("expression", "avg(销售额)") })
            }
            assertTrue(conflict.message!!.contains("口径冲突"), conflict.message)

            val invalid = assertFailsWith<IllegalArgumentException> {
                define.invoke(
                    buildJsonObject {
                        put("name", "波动率")
                        put("expression", "销售额")
                        put("agg", "stddev")
                    },
                )
            }
            assertTrue(invalid.message!!.contains("不合法"), invalid.message)

            assertTrue(b.metrics.list().isEmpty(), "被拒的定义不能落盘")
        }
    }

    @Test
    fun `求值失败与指标不存在都给出可读原因`() {
        bootstrap(tempHome()).use { b ->
            val datasetId = import(b, salesCsv)

            val unknown = assertFailsWith<IllegalArgumentException> {
                evaluate(b, metricArgs(datasetId, "不存在的指标"))
            }
            assertTrue(unknown.message!!.contains("没有名为"), unknown.message)

            // 内置「毛利」需要成本列，这份数据没有——必须诚实失配，不能拿销售额顶替
            val missing = assertFailsWith<IllegalArgumentException> {
                evaluate(b, metricArgs(datasetId, "毛利率"))
            }
            assertTrue(missing.message!!.contains("找不到匹配"), missing.message)
        }
    }

    @Test
    fun `列表工具标注定义在本份数据上的可解析性`() {
        bootstrap(tempHome()).use { b ->
            val datasetId = import(b, salesCsv)
            val listed = tool(b, "stragent_list_metrics").invoke(
                buildJsonObject { put("datasetId", datasetId); put("name", "销售额") },
            )
            val resolution = listed["metrics"]!!.jsonArray.single().jsonObject["resolution"]!!.jsonObject
            assertTrue(resolution["resolved"]!!.jsonPrimitive.booleanOrNull!!)
            assertEquals("销售额", resolution["fields"]!!.jsonObject["销售额"]!!.jsonPrimitive.content)

            val unresolved = tool(b, "stragent_list_metrics").invoke(
                buildJsonObject { put("datasetId", datasetId); put("name", "毛利率") },
            )
            val miss = unresolved["metrics"]!!.jsonArray.single().jsonObject["resolution"]!!.jsonObject
            assertFalse(miss["resolved"]!!.jsonPrimitive.booleanOrNull!!, "缺成本列时应诚实标注解析失败")
        }
    }

    // ---------------- 3. 报告引用指标名 ----------------

    @Test
    fun `报告摘要注入指标口径——数字进白名单、要求写指标名`() {
        val llm = FakeLlmClient()
        bootstrap(tempHome(), llm).use { b ->
            val datasetId = import(b, salesCsv)
            val reportTool = tool(b, "stragent_generate_report")
            val result = reportTool.invoke(buildJsonObject { put("datasetId", datasetId) })

            assertEquals(10, result["metricsAvailable"]!!.jsonPrimitive.intOrNull, "内置指标目录应随回包可见")
            val prompt = llm.prompts.joinToString("\n")
            assertTrue(prompt.contains("指标口径（MetricDef 确定性计算"), "提示词应含指标口径段")
            assertTrue(prompt.contains("销售额：2,900 元"), "指标值应按「名：值 单位」写入摘要：$prompt")
            assertTrue(prompt.contains("不得改写口径"), "提示词必须约束引用方式")
            // 指标段在样本明细之前：值属于可引用统计，而不是样本里抄来的数
            val metricAt = prompt.indexOf("指标口径（MetricDef")
            val sampleAt = prompt.indexOf("前 N 行样本：")
            assertTrue(sampleAt < 0 || metricAt < sampleAt, "指标口径段必须在样本明细之前")
        }
    }

    @Test
    fun `没有可用指标时不产生空块——简报零变化`() {
        val llm = FakeLlmClient()
        bootstrap(tempHome(), llm).use { b ->
            val datasetId = import(b, neutralCsv, "人数")
            val reportTool = tool(b, "stragent_generate_report")
            reportTool.invoke(buildJsonObject { put("datasetId", datasetId) })

            val prompt = llm.prompts.joinToString("\n")
            assertFalse(prompt.contains("指标口径（MetricDef"), "解析不到指标时不应出现空块")
        }
    }
}
