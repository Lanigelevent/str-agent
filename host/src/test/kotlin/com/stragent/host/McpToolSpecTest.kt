package com.stragent.host

import com.stragent.data.abilityTools
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * MCP 工具规范审计测试（D11 v3 S6）。
 *
 * 验证维度：
 *  1. outputSchema 契约：关键工具声明返回结构
 *  2. enum 约束：有限值集参数用 enum 而非自由 string
 *  3. 描述完整性：描述含用途说明
 */
class McpToolSpecTest {

    private fun tempHome() =
        java.io.File(System.getProperty("java.io.tmpdir"), "str-agent-spec-" + System.nanoTime()).also { it.mkdirs() }

    private fun bootstrap() = StrAgentHostBootstrap.create(tempHome(), llm = FakeLlmClient())

    @Test
    fun `chart tool 有 outputSchema 契约`() {
        val b = bootstrap()
        try {
            val tool = chartTool(b.abilityDatasets, b.sessionRegistry)
            assertNotNull(tool.outputSchema, "stragent_render_chart 应声明 outputSchema")
            val props = tool.outputSchema!!["properties"]!!.jsonObject
            assertTrue(props.containsKey("svg"), "outputSchema 应含 svg")
            assertTrue(props.containsKey("echartsOption"), "outputSchema 应含 echartsOption")
            assertTrue(props.containsKey("dataPoints"), "outputSchema 应含 dataPoints")
        } finally {
            b.close()
        }
    }

    @Test
    fun `chart tool chartType 参数有 enum 约束`() {
        val b = bootstrap()
        try {
            val tool = chartTool(b.abilityDatasets, b.sessionRegistry)
            val chartTypeParam = tool.parametersSchema["properties"]!!.jsonObject["chartType"]!!.jsonObject
            assertNotNull(chartTypeParam["enum"], "chartType 应有 enum 约束")
            val enumValues = chartTypeParam["enum"]!!.jsonArray
            assertEquals(8, enumValues.size, "chartType enum 应有 8 个值")
            val ids = enumValues.map { it.jsonPrimitive.content }
            assertTrue(ids.contains("bar"), "enum 应含 bar")
            assertTrue(ids.contains("line"), "enum 应含 line")
            assertTrue(ids.contains("pie"), "enum 应含 pie")
            assertTrue(ids.contains("scatter"), "enum 应含 scatter")
            assertTrue(ids.contains("heatmap"), "enum 应含 heatmap")
            assertTrue(ids.contains("histogram"), "enum 应含 histogram")
            assertTrue(ids.contains("boxplot"), "enum 应含 boxplot")
            assertTrue(ids.contains("radar"), "enum 应含 radar")
        } finally {
            b.close()
        }
    }

    @Test
    fun `chart tool 描述含双轨输出说明`() {
        val b = bootstrap()
        try {
            val tool = chartTool(b.abilityDatasets, b.sessionRegistry)
            val desc = tool.description
            assertTrue(desc.contains("SVG"), "描述应提及 SVG")
            assertTrue(desc.contains("ECharts") || desc.contains("echartsOption") || desc.contains("option"),
                "描述应提及 ECharts option")
        } finally {
            b.close()
        }
    }

    @Test
    fun `AgentTool catalogJson 含 outputSchema 字段`() {
        val b = bootstrap()
        try {
            val tool = chartTool(b.abilityDatasets, b.sessionRegistry)
            val registry = com.stragent.agent.ToolRegistry.of(listOf(tool))
            val catalog = registry.catalogJson(null)
            assertTrue(catalog.contains("outputSchema"), "catalogJson 应含 outputSchema")
            assertTrue(catalog.contains("echartsOption"), "outputSchema 应声明 echartsOption")
        } finally {
            b.close()
        }
    }
}
