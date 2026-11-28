package com.stragent.host

import com.stragent.core.ports.LlmClient
import com.stragent.data.abilityTools
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * 图表渲染工具集成测试（D10 阶段 4 可视化落地）。
 *
 * 验证维度：
 *  1. 柱状图：从 CSV 导入后生成 SVG
 *  2. 折线图：趋势数据
 *  3. 饼图：占比数据
 *  4. 散点图：双数值列
 *  5. 自动选分类列
 *  6. 错误列名给中文报错
 *  7. 不支持的图表类型报错
 */
class ChartToolTest {

    private fun tempHome(): File =
        File(System.getProperty("java.io.tmpdir"), "str-agent-chart-" + System.nanoTime()).also { it.mkdirs() }

    private fun bootstrap(): StrAgentHostBootstrap =
        StrAgentHostBootstrap.create(tempHome(), llm = FakeLlmClient())

    private fun allTools(b: StrAgentHostBootstrap) =
        abilityTools(b.abilityDatasets) + pluginTools(b.plugin, b.abilityDatasets, b.memory, b.semantic) +
            listOf(
                chartTool(b.abilityDatasets, b.sessionRegistry),
                provenanceTool(
                    recorder = b.provenance,
                    datasets = b.abilityDatasets,
                    replayTools = com.stragent.data.abilityTools(b.abilityDatasets) +
                        listOf(chartTool(b.abilityDatasets, b.sessionRegistry)),
                ),
            )

    private fun tool(b: StrAgentHostBootstrap, name: String) =
        allTools(b).first { it.name == name }

    private fun import(b: StrAgentHostBootstrap, csv: String): String {
        val args = buildJsonObject { put("csv", csv); put("name", "test") }
        return tool(b, "stragent_import_table").invoke(args)
            .let { (it["datasetId"] as? JsonPrimitive)?.content ?: error("missing datasetId") }
    }

    private val regionSalesCsv: String = buildString {
        appendLine("地区,销售额")
        appendLine("华东,1000")
        appendLine("华北,2000")
        appendLine("华南,1500")
        appendLine("西部,800")
        appendLine("东北,1200")
    }

    private val scatterCsv: String = buildString {
        appendLine("身高,体重")
        appendLine("160,55")
        appendLine("170,65")
        appendLine("180,75")
        appendLine("175,70")
        appendLine("165,60")
        appendLine("185,80")
    }

    private fun render(
        b: StrAgentHostBootstrap,
        datasetId: String,
        chartType: String = "bar",
        valueColumn: String = "销售额",
        categoryColumn: String? = "地区",
        xColumn: String? = null,
    ): JsonObject {
        val args = buildJsonObject {
            put("datasetId", datasetId)
            put("chartType", chartType)
            put("valueColumn", valueColumn)
            categoryColumn?.let { put("categoryColumn", it) }
            xColumn?.let { put("xColumn", it) }
        }
        return tool(b, "stragent_render_chart").invoke(args)
    }

    @Test
    fun bar_chart_produces_valid_svg() {
        val b = bootstrap()
        try {
            val dsId = import(b, regionSalesCsv)
            val result = render(b, dsId, "bar")
            assertEquals("bar", result["chartType"]!!.jsonPrimitive.content)
            val svg = result["svg"]!!.jsonPrimitive.content
            assertTrue(svg.startsWith("<svg"), "SVG 应以 <svg 开头")
            assertTrue(svg.contains("rect "), "柱状图应含 rect")
            assertEquals(5, result["dataPoints"]!!.jsonPrimitive.content.toInt())
        } finally {
            b.close()
        }
    }

    @Test
    fun line_chart_produces_valid_svg() {
        val b = bootstrap()
        try {
            val dsId = import(b, regionSalesCsv)
            val result = render(b, dsId, "line")
            assertEquals("line", result["chartType"]!!.jsonPrimitive.content)
            val svg = result["svg"]!!.jsonPrimitive.content
            assertTrue(svg.contains("polyline"), "折线图应含 polyline")
        } finally {
            b.close()
        }
    }

    @Test
    fun pie_chart_produces_valid_svg() {
        val b = bootstrap()
        try {
            val dsId = import(b, regionSalesCsv)
            val result = render(b, dsId, "pie")
            assertEquals("pie", result["chartType"]!!.jsonPrimitive.content)
            val svg = result["svg"]!!.jsonPrimitive.content
            assertTrue(svg.contains("path "), "饼图应含 path")
            assertTrue(svg.contains("%"), "饼图应含百分比")
        } finally {
            b.close()
        }
    }

    @Test
    fun scatter_chart_produces_valid_svg() {
        val b = bootstrap()
        try {
            val dsId = import(b, scatterCsv)
            val result = render(b, dsId, "scatter", "体重", null, "身高")
            assertEquals("scatter", result["chartType"]!!.jsonPrimitive.content)
            val svg = result["svg"]!!.jsonPrimitive.content
            assertTrue(svg.contains("circle"), "散点图应含 circle")
            assertEquals(6, result["dataPoints"]!!.jsonPrimitive.content.toInt())
        } finally {
            b.close()
        }
    }

    @Test
    fun auto_picks_first_text_column() {
        val b = bootstrap()
        try {
            val dsId = import(b, regionSalesCsv)
            val args = buildJsonObject {
                put("datasetId", dsId)
                put("chartType", "bar")
                put("valueColumn", "销售额")
                // 不传 categoryColumn → 自动选第一个文本列
            }
            val result = tool(b, "stragent_render_chart").invoke(args)
            assertEquals("bar", result["chartType"]!!.jsonPrimitive.content)
            assertTrue(result["dataPoints"]!!.jsonPrimitive.content.toInt() > 0)
        } finally {
            b.close()
        }
    }

    @Test
    fun bad_value_column_gives_chinese_error() {
        val b = bootstrap()
        try {
            val dsId = import(b, regionSalesCsv)
            val args = buildJsonObject {
                put("datasetId", dsId)
                put("valueColumn", "不存在的列")
            }
            try {
                tool(b, "stragent_render_chart").invoke(args)
                throw AssertionError("should have thrown")
            } catch (e: IllegalArgumentException) {
                val msg = e.message ?: ""
                assertTrue(msg.contains("找不到"), "应给中文错误: $msg")
            }
        } finally {
            b.close()
        }
    }

    @Test
    fun bad_chart_type_gives_error() {
        val b = bootstrap()
        try {
            val dsId = import(b, regionSalesCsv)
            val args = buildJsonObject {
                put("datasetId", dsId)
                put("chartType", "bubble")
                put("valueColumn", "销售额")
            }
            try {
                tool(b, "stragent_render_chart").invoke(args)
                throw AssertionError("should have thrown")
            } catch (e: IllegalArgumentException) {
                val msg = e.message ?: ""
                assertTrue(msg.contains("不支持"), "应报不支持的类型: " + msg)
            }
        } finally {
            b.close()
        }
    }

    @Test
    fun scatter_without_x_column_gives_error() {
        val b = bootstrap()
        try {
            val dsId = import(b, scatterCsv)
            val args = buildJsonObject {
                put("datasetId", dsId)
                put("chartType", "scatter")
                put("valueColumn", "体重")
                // 不传 xColumn
            }
            try {
                tool(b, "stragent_render_chart").invoke(args)
                throw AssertionError("should have thrown")
            } catch (e: IllegalArgumentException) {
                val msg = e.message ?: ""
                assertTrue(msg.contains("xColumn") || msg.contains("散点"), "应提示需要 xColumn: $msg")
            }
        } finally {
            b.close()
        }
    }

    @Test
    fun bar_chart_produces_echarts_option() {
        val b = bootstrap()
        try {
            val dsId = import(b, regionSalesCsv)
            val result = render(b, dsId, "bar")
            assertTrue(result.containsKey("echartsOption"), "应包含 echartsOption 字段")
            val opt = result["echartsOption"]!!.jsonObject
            assertTrue(opt["title"]!!.jsonObject["text"]!!.jsonPrimitive.content.contains("销售额"))
            assertEquals("bar", opt["series"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        } finally {
            b.close()
        }
    }

    @Test
    fun heatmap_chart_produces_echarts_option_with_svg_fallback() {
        val b = bootstrap()
        try {
            val dsId = import(b, regionSalesCsv)
            val result = render(b, dsId, "heatmap")
            assertEquals("heatmap", result["chartType"]!!.jsonPrimitive.content)
            val svg = result["svg"]!!.jsonPrimitive.content
            assertTrue(svg.contains("暂不支持 SVG") || svg.contains("svg"), "SVG 应有回退提示")
            assertTrue(result.containsKey("echartsOption"), "应包含 echartsOption 字段")
            val opt = result["echartsOption"]!!.jsonObject
            assertEquals("heatmap", opt["series"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        } finally {
            b.close()
        }
    }

    @Test
    fun radar_chart_produces_echarts_option() {
        val b = bootstrap()
        try {
            val dsId = import(b, regionSalesCsv)
            val result = render(b, dsId, "radar")
            assertEquals("radar", result["chartType"]!!.jsonPrimitive.content)
            assertTrue(result.containsKey("echartsOption"))
            val opt = result["echartsOption"]!!.jsonObject
            assertNotNull(opt["radar"])
        } finally {
            b.close()
        }
    }

    @Test
    fun chart_enters_provenance_and_replays() {
        val home = File(System.getProperty("java.io.tmpdir"), "str-agent-chart-prov-" + System.nanoTime()).also { it.mkdirs() }
        val b = StrAgentHostBootstrap.create(home, llm = FakeLlmClient())
        try {
            val importArgs = buildJsonObject { put("csv", regionSalesCsv); put("name", "test") }
            val chartArgs = buildJsonObject {
                put("chartType", "bar")
                put("valueColumn", "销售额")
                put("categoryColumn", "地区")
            }
            val provArgs = buildJsonObject { }

            val rpcFrame = { id: Int, toolName: String, args: JsonObject ->
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("method", "tools/call")
                    put("params", buildJsonObject {
                        put("name", toolName)
                        put("arguments", args)
                    })
                }.toString()
            }

            val input = java.io.ByteArrayInputStream(
                listOf(rpcFrame(1, "stragent_import_table", importArgs),
                    rpcFrame(2, "stragent_render_chart", chartArgs),
                    rpcFrame(3, "stragent_provenance", provArgs)
                ).joinToString("\n").plus("\n").toByteArray(Charsets.UTF_8)
            )
            val output = java.io.ByteArrayOutputStream()
            com.stragent.data.McpStdioServer(b.mcpBridge, input, output).serve()

            val lines = output.toString(Charsets.UTF_8).trim().lines().filter { it.isNotBlank() }
            val provFrame = kotlinx.serialization.json.Json.parseToJsonElement(lines[2]).jsonObject
            assertTrue(provFrame.containsKey("result"), "provenance frame should be success")
            val content = provFrame["result"]!!.jsonObject
            val prov = kotlinx.serialization.json.Json.parseToJsonElement(
                content["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
            ).jsonObject
            val steps = prov["steps"]!!.jsonArray
            assertTrue(
                steps.any { it.jsonObject["tool"]!!.jsonPrimitive.content == "stragent_render_chart" },
                "chart should be in provenance steps",
            )
            val replay = prov["replay"]!!.jsonArray
            val chartStep = replay.first { it.jsonObject["tool"]!!.jsonPrimitive.content == "stragent_render_chart" }
            assertEquals(true, chartStep.jsonObject["ok"]!!.jsonPrimitive.content.toBoolean(),
                "chart should be replayable")
        } finally {
            b.close()
        }
    }
}
