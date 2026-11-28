package com.stragent.host

import com.stragent.data.abilityTools
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * T-032 收尾 · `qualityNotes` 透传到宿主回执（端到端）。
 *
 * stragent_generate_report 的回执必须**恒带** `qualityNotes` 字段（编辑部路径的写作
 * 审计软提示；agent 报告路径为空数组）——「审过且干净/无注记」与「没有这路信息」
 * 是两种事实，宿主要能区分，所以空数组也输出而不是省略键。
 * 非空注记的产生逻辑（WritingAudit）与编辑部退稿循环在 core / str-agent 单测覆盖。
 */
class ReportQualityNotesTest {

    @Test
    fun `报告回执恒带 qualityNotes 数组字段`() {
        // JDK 临时目录 API（不手工拼路径——tmpdir 由 JVM 托管，叶子名随机生成）
        val home = java.nio.file.Files.createTempDirectory("str-agent-t032").toFile()
        StrAgentHostBootstrap.create(home, llm = FakeLlmClient(report = "## 总体概览\n销售额合计 240 元。\n")).use { b ->
            val tools = abilityTools(b.abilityDatasets) + pluginTools(b.plugin, b.abilityDatasets, b.memory)
            val importTool = tools.first { it.name == "stragent_import_table" }
            val reportTool = tools.first { it.name == "stragent_generate_report" }
            val id = (importTool.invoke(
                buildJsonObject {
                    put("csv", "地区,销售额\n华东,100\n华南,80\n华北,60\n")
                },
            )["datasetId"] as JsonPrimitive).content
            val result: JsonObject = reportTool.invoke(buildJsonObject { put("datasetId", id) })
            assertTrue(result.containsKey("qualityNotes"), "回执必须带 qualityNotes 字段：$result")
            assertTrue(result["qualityNotes"] is JsonArray, "qualityNotes 应是数组：${result["qualityNotes"]}")
        }
    }
}
