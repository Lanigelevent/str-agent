package com.stragent.host

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** T-105 host 层：参数校验（无效 id 中文报错）+ 覆盖参数到达 prompt + summarize 注入 */
class T105HostAdaptationTest {

    private fun bootstrap(llm: com.stragent.core.ports.LlmClient): StrAgentHostBootstrap =
        StrAgentHostBootstrap.create(
            File(System.getProperty("java.io.tmpdir"), "str-agent-t105-" + System.nanoTime()).also { it.mkdirs() },
            llm = llm,
        )

    private fun call(b: StrAgentHostBootstrap, name: String, args: kotlinx.serialization.json.JsonObject) =
        b.mcpBridge.dispatch(
            "tools/call",
            buildJsonObject { put("name", name); put("arguments", args) },
        )

    private fun importText(b: StrAgentHostBootstrap): String {
        val sc = call(b, "stragent_import_text", buildJsonObject {
            put("filePath", makeTempText("销售额增长 45.3（第一季度）。成本保持稳定。第二季度回落至 12.1。"))
        })["structuredContent"]!!.jsonObject
        return sc.getValue("datasetId").jsonPrimitive.content
    }

    private fun makeTempText(content: String): String {
        val f = File.createTempFile("t105", ".txt")
        f.writeText(content, Charsets.UTF_8)
        return f.absolutePath
    }

    @Test
    fun `无效枚举 id 中文报错并列出合法值`() {
        val fake = FakeLlmClient()
        val b = bootstrap(fake)
        try {
            val dsId = importText(b)
            val r = call(b, "stragent_generate_report", buildJsonObject {
                put("datasetId", dsId); put("reportType", "非存在类型")
            })
            val msg = r.toString()
            assertTrue(msg.contains("reportType 无效"), "中文报错：$msg")
            assertTrue(msg.contains("standard"), "列出合法值")
        } finally {
            b.close()
        }
    }

    @Test
    fun `六覆盖参数到达 generate_report prompt`() {
        val fake = FakeLlmClient()
        val b = bootstrap(fake)
        try {
            val dsId = importText(b)
            call(b, "stragent_generate_report", buildJsonObject {
                put("datasetId", dsId)
                put("reportType", "executive")
                put("style", "concise")
                put("industry", "civil")
                put("depth", "deep")
                put("audience", "施工方项目经理，非监测专业")
                put("requirements", "只写风险与遗留事项")
            })
            val joined = fake.prompts.joinToString("\n")
            assertTrue(joined.contains("<report_type name=\"一页纸执行摘要\">"), "类型覆盖")
            assertTrue(joined.contains("<writing_style name=\"简明扼要\">"), "文风覆盖")
            assertTrue(joined.contains("<industry_framework name=\"土木工程\">"), "行业覆盖")
            assertTrue(joined.contains("<output_persona>"), "persona 块")
            assertTrue(joined.contains("施工方项目经理，非监测专业"), "受众逐字")
            assertTrue(joined.contains("<output_requirements>"), "requirements 块")
            assertTrue(joined.contains("只写风险与遗留事项"), "要求逐字")
            assertTrue(joined.contains("硬约束 > 本块 > 报告类型 > 行业框架 > 报告风格"), "优先级声明")
        } finally {
            b.close()
        }
    }

    @Test
    fun `summarize requirements 到达 prompt 且 audience 参与重排`() {
        val fake = FakeLlmClient()
        val b = bootstrap(fake)
        try {
            val dsId = importText(b)
            call(b, "stragent_summarize_text", buildJsonObject {
                put("datasetId", dsId)
                put("requirements", "只保留风险相关要点")
                put("audience", "风控委员会")
            })
            val joined = fake.prompts.joinToString("\n")
            assertTrue(joined.contains("<output_requirements>"), "summarize 注入 requirements 块")
            assertTrue(joined.contains("只保留风险相关要点"), "要求逐字")
            assertTrue(joined.contains("<output_persona>"), "summarize 注入 persona 块")
        } finally {
            b.close()
        }
    }

    @Test
    fun `depth 无效值与 audience 超长中文报错`() {
        val fake = FakeLlmClient()
        val b = bootstrap(fake)
        try {
            val dsId = importText(b)
            val r1 = call(b, "stragent_generate_report", buildJsonObject {
                put("datasetId", dsId); put("depth", "huge")
            })
            assertTrue(r1.toString().contains("depth 无效"), "depth 中文报错")
            val r2 = call(b, "stragent_summarize_text", buildJsonObject {
                put("datasetId", dsId); put("audience", "长".repeat(301))
            })
            assertTrue(r2.toString().contains("audience 过长"), "audience 上限报错")
        } finally {
            b.close()
        }
    }
}
