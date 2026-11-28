package com.stragent.host

import com.stragent.core.ports.LlmClient
import com.stragent.data.McpStdioServer
import com.stragent.data.abilityTools
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ForecastToolTest {

    private fun tempHome(): File =
        File(System.getProperty("java.io.tmpdir"), "str-agent-fc-" + System.nanoTime()).also { it.mkdirs() }

    private fun bootstrap(): StrAgentHostBootstrap =
        StrAgentHostBootstrap.create(tempHome(), llm = FakeLlmClient())

    private fun allTools(b: StrAgentHostBootstrap) =
        abilityTools(b.abilityDatasets) + pluginTools(b.plugin, b.abilityDatasets, b.memory, b.semantic) +
            listOf(
                forecastTool(b.abilityDatasets, b.sessionRegistry),
                listSemanticTermsTool(b.semantic),
                provenanceTool(
                    recorder = b.provenance,
                    datasets = b.abilityDatasets,
                    replayTools = com.stragent.data.abilityTools(b.abilityDatasets) +
                        listOf(forecastTool(b.abilityDatasets, b.sessionRegistry)),
                ),
            )

    private fun tool(b: StrAgentHostBootstrap, name: String) =
        allTools(b).first { it.name == name }

    private fun import(b: StrAgentHostBootstrap, csv: String): String {
        val args = buildJsonObject { put("csv", csv); put("name", "test") }
        return tool(b, "stragent_import_table").invoke(args)
            .let { (it["datasetId"] as? JsonPrimitive)?.content ?: error("missing datasetId") }
    }

    private fun forecast(
        b: StrAgentHostBootstrap,
        datasetId: String,
        valueColumn: String? = null,
        dateColumn: String? = null,
        horizon: String? = null,
    ): JsonObject {
        val args = buildJsonObject {
            put("datasetId", datasetId)
            valueColumn?.let { put("valueColumn", it) }
            dateColumn?.let { put("dateColumn", it) }
            horizon?.let { put("horizon", it) }
        }
        return tool(b, "stragent_forecast").invoke(args)
    }

    // 12 months with trend + noise: y = 100 + 10*t + (t % 3) * 2.5
    private val trendCsv: String = buildString {
        appendLine("月份,销售额")
        for (i in 0 until 12) {
            appendLine("2024-" + (i + 1).toString().padStart(2, '0') + "," + (100 + i * 10 + (i % 3) * 2.5))
        }
    }

    private fun rpcFrame(id: Int, toolName: String, args: JsonObject): String {
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "tools/call")
            put("params", buildJsonObject {
                put("name", toolName)
                put("arguments", args)
            })
        }.toString()
    }

    private fun runStdio(bridge: com.stragent.data.McpBridge, lines: List<String>): List<String> {
        val input = ByteArrayInputStream(lines.joinToString("\n").plus("\n").toByteArray(Charsets.UTF_8))
        val output = ByteArrayOutputStream()
        McpStdioServer(bridge, input, output).serve()
        return output.toString(Charsets.UTF_8).trim().lines().filter { it.isNotBlank() }
    }

    private fun toolResult(out: List<String>, index: Int): JsonObject {
        val frame = Json.parseToJsonElement(out[index]).jsonObject
        assertTrue(frame.containsKey("result"), "frame " + index + " not a success response")
        val content = frame["result"]!!.jsonObject
        return Json.parseToJsonElement(
            content["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        ).jsonObject
    }

    @Test
    fun trend_forecast_values_continue_increasing() {
        val b = bootstrap()
        try {
            val dsId = import(b, trendCsv)
            val result = forecast(b, dsId, "销售额", "月份", "3")

            assertEquals("holt", result["method"]!!.jsonPrimitive.content)
            assertEquals(12, result["dataPoints"]!!.jsonPrimitive.content.toInt())

            val fc = result["forecast"]!!.jsonArray
            assertEquals(3, fc.size)

            val v1 = fc[0].jsonObject["value"]!!.jsonPrimitive.content.toDouble()
            val v2 = fc[1].jsonObject["value"]!!.jsonPrimitive.content.toDouble()
            val v3 = fc[2].jsonObject["value"]!!.jsonPrimitive.content.toDouble()
            assertTrue(v1 < v2, "forecast should increase")
            assertTrue(v2 < v3, "forecast should increase")
            assertTrue(kotlin.math.abs(v1 - 220.0) < 20.0, "first forecast ~ 220")

            for (pt in fc) {
                val lower = pt.jsonObject["lower"]!!.jsonPrimitive.content.toDouble()
                val upper = pt.jsonObject["upper"]!!.jsonPrimitive.content.toDouble()
                val value = pt.jsonObject["value"]!!.jsonPrimitive.content.toDouble()
                assertTrue(lower < value, "lower < value")
                assertTrue(value < upper, "value < upper")
            }

            val notice = result["notice"]!!.jsonPrimitive.content
            assertTrue(notice.contains("外推"))
        } finally {
            b.close()
        }
    }

    @Test
    fun insufficient_data_rejects_forecast() {
        val shortCsv = "月份,销售额\n2024-01,100\n2024-02,200\n"
        val b = bootstrap()
        try {
            val dsId = import(b, shortCsv)
            val result = forecast(b, dsId, "销售额", "月份")

            assertEquals("none", result["method"]!!.jsonPrimitive.content)
            assertEquals(2, result["dataPoints"]!!.jsonPrimitive.content.toInt())
            val error = result["error"]!!.jsonPrimitive.content
            assertTrue(error.contains("数据点不足"))
        } finally {
            b.close()
        }
    }

    @Test
    fun forecast_enters_provenance_and_replays() {
        val home = createTempDirectory("str-agent-fc-prov").toFile()
        val b = StrAgentHostBootstrap.create(home, llm = FakeLlmClient())
        try {
            val importArgs = buildJsonObject {
                put("csv", trendCsv)
                put("name", "test")
            }
            val forecastArgs = buildJsonObject {
                put("valueColumn", "销售额")
                put("dateColumn", "月份")
                put("horizon", "3")
            }
            val provArgs = buildJsonObject {}

            val out = runStdio(
                b.mcpBridge,
                listOf(
                    rpcFrame(1, "stragent_import_table", importArgs),
                    rpcFrame(2, "stragent_forecast", forecastArgs),
                    rpcFrame(3, "stragent_provenance", provArgs),
                ),
            )

            val prov = toolResult(out, 2)
            val steps = prov["steps"]!!.jsonArray
            assertTrue(
                steps.any { it.jsonObject["tool"]!!.jsonPrimitive.content == "stragent_forecast" },
                "forecast should be in provenance steps",
            )
            val replay = prov["replay"]!!.jsonArray
            val fcStep = replay.first { it.jsonObject["tool"]!!.jsonPrimitive.content == "stragent_forecast" }
            assertEquals(true, fcStep.jsonObject["ok"]!!.jsonPrimitive.content.toBoolean(),
                "forecast should be replayable")
        } finally {
            b.close()
        }
    }

    @Test
    fun auto_picks_first_numeric_column() {
        val b = bootstrap()
        try {
            val dsId = import(b, trendCsv)
            val result = forecast(b, dsId)

            assertTrue(result.containsKey("method"))
            assertEquals(12, result["dataPoints"]!!.jsonPrimitive.content.toInt())
            assertEquals("销售额", result["valueColumn"]!!.jsonPrimitive.content)
        } finally {
            b.close()
        }
    }

    @Test
    fun bad_column_name_gives_chinese_error() {
        val b = bootstrap()
        try {
            val dsId = import(b, trendCsv)
            val args = buildJsonObject {
                put("datasetId", dsId)
                put("valueColumn", "不存在的列")
            }
            try {
                tool(b, "stragent_forecast").invoke(args)
                throw AssertionError("should have thrown")
            } catch (e: IllegalArgumentException) {
                val msg = e.message ?: ""
                assertTrue(msg.contains("找不到") || msg.contains("数值列"),
                    "should give Chinese error: " + msg)
            }
        } finally {
            b.close()
        }
    }
}
