package com.stragent.host

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T-107：命名预设注册表（save/expand）+ 真实 268 页 PDF 适配端到端（机制级，收口 T-105 遗留验证项） */
class T107HostProfileRegistryTest {

    private fun bootstrap(llm: com.stragent.core.ports.LlmClient): StrAgentHostBootstrap =
        StrAgentHostBootstrap.create(
            File(System.getProperty("java.io.tmpdir"), "str-agent-t107-" + System.nanoTime()).also { it.mkdirs() },
            llm = llm,
        )

    private fun call(b: StrAgentHostBootstrap, name: String, args: kotlinx.serialization.json.JsonObject) =
        b.mcpBridge.dispatch(
            "tools/call",
            buildJsonObject { put("name", name); put("arguments", args) },
        )

    private fun importText(b: StrAgentHostBootstrap, path: String): String {
        val sc = call(b, "stragent_import_text", buildJsonObject {
            put("filePath", path); put("name", "测试材料")
        })["structuredContent"]!!.jsonObject
        return sc.getValue("datasetId").jsonPrimitive.content
    }

    @Test
    fun `save_profile 落库后按名展开且非法被拒`() {
        val fake = FakeLlmClient()
        val b = bootstrap(fake)
        try {
            val saved = call(b, "stragent_save_profile", buildJsonObject {
                put("name", "工程深挖")
                put("bundle", "{\"reportType\":\"deepdive\",\"depth\":\"deep\",\"audience\":\"工程审阅组\"}")
            })
            assertTrue(saved.toString().contains("\"saved\""), "落库回执：$saved")

            val bad = call(b, "stragent_save_profile", buildJsonObject {
                put("name", "坏值"); put("bundle", "{\"depth\":\"huge\"}")
            })
            assertTrue(bad.toString().contains("profile.depth 无效"), "bundle 值校验落库即拦")

            val f = File.createTempFile("t107", ".txt")
            f.writeText("销售额增长 45.3（第一季度）。成本保持稳定。", Charsets.UTF_8)
            val dsId = importText(b, f.absolutePath)
            call(b, "stragent_generate_report", buildJsonObject {
                put("datasetId", dsId); put("profile", "工程深挖")
            })
            val joined = fake.prompts.joinToString("\n")
            assertTrue(joined.contains("<report_type name=\"专题深挖\">"), "按名展开的 reportType 生效")
            assertTrue(joined.contains("工程审阅组"), "按名展开的 audience 生效")
        } finally {
            b.close()
        }
    }

    @Test
    fun `真实 268 页 PDF——deep 适配档位与注入块端到端`() {
        val realPdf = RealMaterialFixture.requirePdf()
        val fake = FakeLlmClient()
        val b = bootstrap(fake)
        try {
            val dsId = importText(b, realPdf)
            // 同材料双配置：deep 章组（4/组）应比 standard（6/组）产生更多生成调用
            val promptsBefore = fake.prompts.size
            val repStd = call(b, "stragent_generate_report", buildJsonObject {
                put("datasetId", dsId)
            })["structuredContent"]!!.jsonObject
            val promptsStd = fake.prompts.size - promptsBefore

            val promptsBeforeDeep = fake.prompts.size
            val rep = call(b, "stragent_generate_report", buildJsonObject {
                put("datasetId", dsId)
                put("depth", "deep")
                put("audience", "施工方项目经理，非监测专业")
                put("requirements", "只写风险与遗留事项")
            })["structuredContent"]!!.jsonObject
            val promptsDeep = fake.prompts.size - promptsBeforeDeep

            assertEquals("2000", repStd.getValue("targetChars").jsonPrimitive.content, "standard 篇幅轴不变")
            assertEquals("3500", rep.getValue("targetChars").jsonPrimitive.content, "2000×1.75=3500（真实材料 >30K 档）")
            assertTrue(promptsDeep > promptsStd, "deep 章组更细（$promptsDeep > $promptsStd 次生成调用）")
            val joined = fake.prompts.joinToString("\n")
            assertTrue(joined.contains("<output_persona>"), "persona 块到达真实材料 prompt")
            assertTrue(joined.contains("施工方项目经理，非监测专业"), "受众逐字")
            assertTrue(joined.contains("<output_requirements>"), "requirements 块到达")
            assertTrue(joined.contains("只写风险与遗留事项"), "要求逐字")
            assertTrue(joined.contains("受众不改变事实与数字纪律"), "persona 纪律声明在场")
            assertTrue(rep.containsKey("gateFailed"), "门禁字段恒输出")
        } finally {
            b.close()
        }
    }
}
