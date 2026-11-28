package com.stragent.host

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T-108：预设清单可发现性（list 工具）+ 生效配置回显（恒输出） */
class T108HostProfileListTest {

    private fun bootstrap(llm: com.stragent.core.ports.LlmClient): StrAgentHostBootstrap =
        StrAgentHostBootstrap.create(
            File(System.getProperty("java.io.tmpdir"), "str-agent-t108-" + System.nanoTime()).also { it.mkdirs() },
            llm = llm,
        )

    private fun call(b: StrAgentHostBootstrap, name: String, args: kotlinx.serialization.json.JsonObject) =
        b.mcpBridge.dispatch(
            "tools/call",
            buildJsonObject { put("name", name); put("arguments", args) },
        )

    @Test
    fun `list_profiles 列出已存预设且空表恒输出`() {
        val b = bootstrap(FakeLlmClient())
        try {
            val empty = call(b, "stragent_list_profiles", buildJsonObject {})
            val emptySc = empty["structuredContent"]!!.jsonObject
            assertEquals("0", emptySc.getValue("count").jsonPrimitive.content, "空表 count=0 恒输出")
            assertTrue(emptySc.getValue("notice").jsonPrimitive.content.contains("stragent_save_profile"), "空表带指引")

            call(b, "stragent_save_profile", buildJsonObject {
                put("name", "深挖预设"); put("bundle", "{\"depth\":\"deep\"}")
            })
            call(b, "stragent_save_profile", buildJsonObject {
                put("name", "深挖预设"); put("bundle", "{\"depth\":\"brief\"}") // 重存覆盖
            })
            call(b, "stragent_save_profile", buildJsonObject {
                put("name", "简报预设"); put("bundle", "{\"depth\":\"brief\",\"audience\":\"审阅组\"}")
            })
            val listed = call(b, "stragent_list_profiles", buildJsonObject {})["structuredContent"]!!.jsonObject
            assertEquals("2", listed.getValue("count").jsonPrimitive.content, "重存去重后 2 条")
            val arr = listed.getValue("profiles").toString()
            assertTrue(arr.contains("深挖预设") && arr.contains("简报预设"), "两预设均在清单")
            assertTrue(arr.contains("depth") && arr.contains("brief"), "重存后的 bundle 生效（覆盖旧值）")
        } finally {
            b.close()
        }
    }

    @Test
    fun `generate_report 回执回显生效配置`() {
        val b = bootstrap(FakeLlmClient())
        try {
            val f = File.createTempFile("t108", ".txt")
            f.writeText("销售额增长 45.3（第一季度）。成本保持稳定。", Charsets.UTF_8)
            val dsId = call(b, "stragent_import_text", buildJsonObject {
                put("filePath", f.absolutePath)
            })["structuredContent"]!!.jsonObject.getValue("datasetId").jsonPrimitive.content

            call(b, "stragent_save_profile", buildJsonObject {
                put("name", "工程档"); put("bundle", "{\"depth\":\"brief\"}")
            })
            val rep = call(b, "stragent_generate_report", buildJsonObject {
                put("datasetId", dsId); put("profile", "工程档"); put("requirements", "只写风险")
            })["structuredContent"]!!.jsonObject
            val echo = rep.getValue("outputProfile").jsonObject
            assertEquals("工程档", echo.getValue("profile").jsonPrimitive.content, "profile 名回显")
            val ov = echo.getValue("overrides").jsonObject
            assertEquals("brief", ov.getValue("analysis.depth").jsonPrimitive.content, "bundle 展开的 depth 在 overrides")
            assertEquals("只写风险", ov.getValue("analysis.requirements").jsonPrimitive.content, "显式 requirements 优先并入")
        } finally {
            b.close()
        }
    }

    @Test
    fun `summarize 回执回显 depth`() {
        val b = bootstrap(FakeLlmClient())
        try {
            val f = File.createTempFile("t108b", ".txt")
            f.writeText("销售额增长 45.3。", Charsets.UTF_8)
            val dsId = call(b, "stragent_import_text", buildJsonObject {
                put("filePath", f.absolutePath)
            })["structuredContent"]!!.jsonObject.getValue("datasetId").jsonPrimitive.content
            val s = call(b, "stragent_summarize_text", buildJsonObject {
                put("datasetId", dsId); put("depth", "deep")
            })["structuredContent"]!!.jsonObject
            assertEquals("deep", s.getValue("depth").jsonPrimitive.content, "depth 回显")
            assertEquals("1500", s.getValue("maxWords").jsonPrimitive.content, "deep→1500 缺省映射")
        } finally {
            b.close()
        }
    }
}
