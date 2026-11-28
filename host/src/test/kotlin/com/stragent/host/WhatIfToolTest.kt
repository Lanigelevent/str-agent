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

class WhatIfToolTest {

    private fun tempHome(): File =
        File(System.getProperty("java.io.tmpdir"), "str-agent-wi-" + System.nanoTime()).also { it.mkdirs() }

    private fun bootstrap(): StrAgentHostBootstrap =
        StrAgentHostBootstrap.create(tempHome(), llm = FakeLlmClient())

    private fun allTools(b: StrAgentHostBootstrap) =
        abilityTools(b.abilityDatasets) + pluginTools(b.plugin, b.abilityDatasets, b.memory, b.semantic) +
            listOf(
                whatIfTool(b.abilityDatasets, b.sessionRegistry),
                provenanceTool(
                    recorder = b.provenance,
                    datasets = b.abilityDatasets,
                    replayTools = com.stragent.data.abilityTools(b.abilityDatasets) +
                        listOf(whatIfTool(b.abilityDatasets, b.sessionRegistry)),
                ),
            )

    private fun tool(b: StrAgentHostBootstrap, name: String) =
        allTools(b).first { it.name == name }

    private fun import(b: StrAgentHostBootstrap, csv: String): String {
        val args = buildJsonObject { put("csv", csv); put("name", "test") }
        return tool(b, "stragent_import_table").invoke(args)
            .let { (it["datasetId"] as? JsonPrimitive)?.content ?: error("missing datasetId") }
    }

    private val csv: String = buildString {
        appendLine("地区,销售额")
        appendLine("华东,100")
        appendLine("华北,200")
        appendLine("华东,50")
    }

    @Test
    fun multiply_10pct_produces_correct_comparison() {
        val b = bootstrap()
        try {
            val dsId = import(b, csv)
            val args = buildJsonObject {
                put("datasetId", dsId)
                put("column", "销售额")
                put("operation", "multiply")
                put("factor", "1.1")
            }
            val result = tool(b, "stragent_whatif").invoke(args)

            assertEquals("销售额", result["column"]!!.jsonPrimitive.content)
            assertEquals("multiply", result["operation"]!!.jsonPrimitive.content)

            val before = result["before"]!!.jsonArray
            val after = result["after"]!!.jsonArray
            val delta = result["delta"]!!.jsonArray
            assertEquals(before.size, after.size)
            assertEquals(before.size, delta.size)

            // before sum = 350, after sum = 385
            val beforeSum = before[0].jsonObject["sum"]!!.jsonPrimitive.content.toDouble()
            val afterSum = after[0].jsonObject["sum"]!!.jsonPrimitive.content.toDouble()
            assertEquals(350.0, beforeSum)
            assertEquals(385.0, afterSum)

            val notice = result["notice"]!!.jsonPrimitive.content
            assertTrue(notice.contains("不落盘"))
        } finally {
            b.close()
        }
    }

    @Test
    fun groupBy_produces_per_group_stats() {
        val b = bootstrap()
        try {
            val dsId = import(b, csv)
            val args = buildJsonObject {
                put("datasetId", dsId)
                put("column", "销售额")
                put("operation", "multiply")
                put("factor", "2")
                put("groupBy", "地区")
            }
            val result = tool(b, "stragent_whatif").invoke(args)

            val before = result["before"]!!.jsonArray
            assertEquals(2, before.size)
            assertEquals("地区", result["groupBy"]!!.jsonPrimitive.content)
        } finally {
            b.close()
        }
    }

    @Test
    fun bad_column_name_gives_chinese_error() {
        val b = bootstrap()
        try {
            val dsId = import(b, csv)
            val args = buildJsonObject {
                put("datasetId", dsId)
                put("column", "不存在")
                put("factor", "1.1")
            }
            try {
                tool(b, "stragent_whatif").invoke(args)
                throw AssertionError("should have thrown")
            } catch (e: IllegalArgumentException) {
                val msg = e.message ?: ""
                assertTrue(msg.contains("找不到") || msg.contains("数值列"))
            }
        } finally {
            b.close()
        }
    }

    @Test
    fun auto_picks_first_numeric_column() {
        val b = bootstrap()
        try {
            val dsId = import(b, csv)
            val args = buildJsonObject {
                put("datasetId", dsId)
                put("factor", "1.5")
            }
            val result = tool(b, "stragent_whatif").invoke(args)
            assertEquals("销售额", result["column"]!!.jsonPrimitive.content)
        } finally {
            b.close()
        }
    }
}
