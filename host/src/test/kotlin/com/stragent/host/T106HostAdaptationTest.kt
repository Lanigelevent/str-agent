package com.stragent.host

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** T-106 host 层：profile bundle/depth 缺省映射/followup audience/注入净化端到端 */
class T106HostAdaptationTest {

    private fun bootstrap(llm: com.stragent.core.ports.LlmClient): StrAgentHostBootstrap =
        StrAgentHostBootstrap.create(
            File(System.getProperty("java.io.tmpdir"), "str-agent-t106-" + System.nanoTime()).also { it.mkdirs() },
            llm = llm,
        )

    private fun call(b: StrAgentHostBootstrap, name: String, args: kotlinx.serialization.json.JsonObject) =
        b.mcpBridge.dispatch(
            "tools/call",
            buildJsonObject { put("name", name); put("arguments", args) },
        )

    private fun importText(b: StrAgentHostBootstrap): String {
        val f = File.createTempFile("t106", ".txt")
        f.writeText("销售额增长 45.3（第一季度）。成本保持稳定。第二季度回落至 12.1。", Charsets.UTF_8)
        val sc = call(b, "stragent_import_text", buildJsonObject {
            put("filePath", f.absolutePath)
        })["structuredContent"]!!.jsonObject
        return sc.getValue("datasetId").jsonPrimitive.content
    }

    @Test
    fun `profile bundle 展开且显式参数优先`() {
        val fake = FakeLlmClient()
        val b = bootstrap(fake)
        try {
            val dsId = importText(b)
            call(b, "stragent_generate_report", buildJsonObject {
                put("datasetId", dsId)
                put("profile", "{\"depth\":\"brief\",\"reportType\":\"executive\"}")
                put("depth", "deep") // 显式覆盖 bundle
                put("audience", "董事会")
            })
            val joined = fake.prompts.joinToString("\n")
            assertTrue(joined.contains("<report_type name=\"一页纸执行摘要\">"), "bundle 的 reportType 生效")
            assertTrue(joined.contains("董事会"), "显式 audience 生效")
        } finally {
            b.close()
        }
    }

    @Test
    fun `profile 非法 JSON 与非法枚举值中文报错`() {
        val fake = FakeLlmClient()
        val b = bootstrap(fake)
        try {
            val dsId = importText(b)
            val r1 = call(b, "stragent_generate_report", buildJsonObject {
                put("datasetId", dsId); put("profile", "不是JSON")
            })
            assertTrue(r1.toString().contains("profile 无效"), "bundle 解析失败中文报错")
            val r2 = call(b, "stragent_generate_report", buildJsonObject {
                put("datasetId", dsId); put("profile", "{\"depth\":\"huge\"}")
            })
            assertTrue(r2.toString().contains("profile.depth 无效"), "bundle 值枚举校验")
        } finally {
            b.close()
        }
    }

    @Test
    fun `summarize depth 映射 maxWords 缺省且无效值报错`() {
        val fake = FakeLlmClient()
        val b = bootstrap(fake)
        try {
            val dsId = importText(b)
            val r1 = call(b, "stragent_summarize_text", buildJsonObject {
                put("datasetId", dsId); put("depth", "brief")
            })
            val s1 = r1["structuredContent"]!!.jsonObject
            assertTrue(s1.getValue("maxWords").jsonPrimitive.content == "400", "brief→400 缺省")
            val r2 = call(b, "stragent_summarize_text", buildJsonObject {
                put("datasetId", dsId); put("depth", "huge")
            })
            assertTrue(r2.toString().contains("depth 无效"), "depth 中文报错")
        } finally {
            b.close()
        }
    }

    @Test
    fun `followup audience 注入 persona 块`() {
        val fake = FakeLlmClient()
        val b = bootstrap(fake)
        try {
            val dsId = importText(b)
            call(b, "stragent_ask_followup", buildJsonObject {
                put("datasetId", dsId); put("question", "销售额是多少？"); put("audience", "风控委员会")
            })
            val joined = fake.prompts.joinToString("\n")
            assertTrue(joined.contains("<output_persona>"), "followup persona 块")
            assertTrue(joined.contains("风控委员会"), "受众逐字")
        } finally {
            b.close()
        }
    }
}
