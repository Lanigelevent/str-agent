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

/**
 * 线性回归工具集成测试（D10 阶段 5 统计方法扩充）。
 *
 * 验证维度：
 *  1. 完美线性数据 → slope/intercept/R² 精确
 *  2. 弱相关数据 → 0 < R² < 1
 *  3. 错误列名给中文报错
 *  4. 非数值列报错
 *  5. 溯源重放
 */
class RegressionToolTest {

    private fun tempHome(): File =
        File(System.getProperty("java.io.tmpdir"), "str-agent-reg-" + System.nanoTime()).also { it.mkdirs() }

    private fun bootstrap(): StrAgentHostBootstrap =
        StrAgentHostBootstrap.create(tempHome(), llm = FakeLlmClient())

    private fun allTools(b: StrAgentHostBootstrap) =
        abilityTools(b.abilityDatasets) + pluginTools(b.plugin, b.abilityDatasets, b.memory, b.semantic) +
            listOf(
                regressionTool(b.abilityDatasets, b.sessionRegistry),
                provenanceTool(
                    recorder = b.provenance,
                    datasets = b.abilityDatasets,
                    replayTools = com.stragent.data.abilityTools(b.abilityDatasets) +
                        listOf(regressionTool(b.abilityDatasets, b.sessionRegistry)),
                ),
            )

    private fun tool(b: StrAgentHostBootstrap, name: String) =
        allTools(b).first { it.name == name }

    private fun import(b: StrAgentHostBootstrap, csv: String): String {
        val args = buildJsonObject { put("csv", csv); put("name", "test") }
        return tool(b, "stragent_import_table").invoke(args)
            .let { (it["datasetId"] as? JsonPrimitive)?.content ?: error("missing datasetId") }
    }

    // y = 2 + 3*x (完美线性)
    private val linearCsv: String = buildString {
        appendLine("x,y")
        for (i in 1..10) {
            appendLine("$i,${2 + 3 * i}")
        }
    }

    // y = 2*x + noise
    private val noisyCsv: String = buildString {
        appendLine("x,y")
        appendLine("1,3")
        appendLine("2,7")
        appendLine("3,5")
        appendLine("4,11")
        appendLine("5,8")
        appendLine("6,16")
        appendLine("7,12")
        appendLine("8,20")
        appendLine("9,15")
        appendLine("10,25")
    }

    private fun regression(
        b: StrAgentHostBootstrap,
        datasetId: String,
        xColumn: String,
        yColumn: String,
    ): JsonObject {
        val args = buildJsonObject {
            put("datasetId", datasetId)
            put("xColumn", xColumn)
            put("yColumn", yColumn)
        }
        return tool(b, "stragent_regression").invoke(args)
    }

    @Test
    fun perfect_linear_data_gives_exact_slope_intercept() {
        val b = bootstrap()
        try {
            val dsId = import(b, linearCsv)
            val result = regression(b, dsId, "x", "y")
            assertEquals("x", result["xColumn"]!!.jsonPrimitive.content)
            assertEquals("y", result["yColumn"]!!.jsonPrimitive.content)
            assertEquals(3.0, result["slope"]!!.jsonPrimitive.content.toDouble(), 0.001, "slope 应为 3")
            assertEquals(2.0, result["intercept"]!!.jsonPrimitive.content.toDouble(), 0.001, "intercept 应为 2")
            assertEquals(1.0, result["rSquared"]!!.jsonPrimitive.content.toDouble(), 0.001, "R² 应为 1")
            assertEquals(10, result["sampleSize"]!!.jsonPrimitive.content.toInt())
            assertTrue(result["significant"]!!.jsonPrimitive.content.toBoolean(), "完美线性应显著")
            val equation = result["equation"]!!.jsonPrimitive.content
            assertTrue(equation.contains("3"), "方程应含 slope=3")
        } finally {
            b.close()
        }
    }

    @Test
    fun noisy_data_has_r_squared_between_0_and_1() {
        val b = bootstrap()
        try {
            val dsId = import(b, noisyCsv)
            val result = regression(b, dsId, "x", "y")
            val r2 = result["rSquared"]!!.jsonPrimitive.content.toDouble()
            assertTrue(r2 > 0.0, "R² 应 > 0")
            assertTrue(r2 < 1.0, "R² 应 < 1（有噪声）")
            assertEquals(10, result["sampleSize"]!!.jsonPrimitive.content.toInt())
        } finally {
            b.close()
        }
    }

    @Test
    fun predictions_array_has_correct_size() {
        val b = bootstrap()
        try {
            val dsId = import(b, linearCsv)
            val result = regression(b, dsId, "x", "y")
            val predictions = result["predictions"]!!.jsonArray
            assertEquals(10, predictions.size, "应有 10 个预测值")
            val first = predictions[0].jsonObject
            assertTrue(first.containsKey("x"))
            assertTrue(first.containsKey("actual"))
            assertTrue(first.containsKey("predicted"))
        } finally {
            b.close()
        }
    }

    @Test
    fun bad_column_gives_chinese_error() {
        val b = bootstrap()
        try {
            val dsId = import(b, linearCsv)
            val args = buildJsonObject {
                put("datasetId", dsId)
                put("xColumn", "不存在的列")
                put("yColumn", "y")
            }
            try {
                tool(b, "stragent_regression").invoke(args)
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
    fun non_numeric_column_gives_error() {
        val csv = "name,score\nAlice,90\nBob,85\nCharlie,95\n"
        val b = bootstrap()
        try {
            val dsId = import(b, csv)
            val args = buildJsonObject {
                put("datasetId", dsId)
                put("xColumn", "name")
                put("yColumn", "score")
            }
            try {
                tool(b, "stragent_regression").invoke(args)
                throw AssertionError("should have thrown")
            } catch (e: IllegalArgumentException) {
                val msg = e.message ?: ""
                assertTrue(msg.contains("不是数值列") || msg.contains("数值列"), "应报非数值列: $msg")
            }
        } finally {
            b.close()
        }
    }

    @Test
    fun notice_contains_method_limitations() {
        val b = bootstrap()
        try {
            val dsId = import(b, linearCsv)
            val result = regression(b, dsId, "x", "y")
            val notice = result["notice"]!!.jsonPrimitive.content
            assertTrue(notice.contains("一元"), "notice 应说明只做一元回归")
            assertTrue(notice.contains("p 值"), "notice 应提及 p 值近似")
        } finally {
            b.close()
        }
    }
}
