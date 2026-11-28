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
import kotlin.test.assertFalse

/**
 * 假设检验工具集成测试（D10 阶段 5 统计方法扩充）。
 *
 * 验证维度：
 *  1. t 检验：显著差异的两组数据
 *  2. t 检验：无显著差异的两组数据
 *  3. 卡方检验：关联的列
 *  4. 错误类型报错
 *  5. 缺参数报错
 */
class HypothesisTestToolTest {

    private fun tempHome(): File =
        File(System.getProperty("java.io.tmpdir"), "str-agent-ht-" + System.nanoTime()).also { it.mkdirs() }

    private fun bootstrap(): StrAgentHostBootstrap =
        StrAgentHostBootstrap.create(tempHome(), llm = FakeLlmClient())

    private fun allTools(b: StrAgentHostBootstrap) =
        abilityTools(b.abilityDatasets) + pluginTools(b.plugin, b.abilityDatasets, b.memory, b.semantic) +
            listOf(
                hypothesisTestTool(b.abilityDatasets, b.sessionRegistry),
                provenanceTool(
                    recorder = b.provenance,
                    datasets = b.abilityDatasets,
                    replayTools = com.stragent.data.abilityTools(b.abilityDatasets) +
                        listOf(hypothesisTestTool(b.abilityDatasets, b.sessionRegistry)),
                ),
            )

    private fun tool(b: StrAgentHostBootstrap, name: String) =
        allTools(b).first { it.name == name }

    private fun import(b: StrAgentHostBootstrap, csv: String): String {
        val args = buildJsonObject { put("csv", csv); put("name", "test") }
        return tool(b, "stragent_import_table").invoke(args)
            .let { (it["datasetId"] as? JsonPrimitive)?.content ?: error("missing datasetId") }
    }

    // 分组 + 数值列：A 组低，B 组高（差异显著）
    private val tTestCsv: String = buildString {
        appendLine("group,value")
        appendLine("A,100")
        appendLine("A,102")
        appendLine("A,98")
        appendLine("A,101")
        appendLine("A,99")
        appendLine("B,200")
        appendLine("B,198")
        appendLine("B,202")
        appendLine("B,199")
        appendLine("B,201")
    }

    // 分组 + 数值列：两组接近（无差异）
    private val tTestNoDiffCsv: String = buildString {
        appendLine("group,value")
        appendLine("A,100")
        appendLine("A,101")
        appendLine("A,99")
        appendLine("A,100")
        appendLine("B,100")
        appendLine("B,101")
        appendLine("B,99")
        appendLine("B,100")
    }

    // 两个分类列：完全关联
    private val chiSquareCsv: String = buildString {
        appendLine("region,product")
        appendLine("East,Widget")
        appendLine("East,Widget")
        appendLine("East,Widget")
        appendLine("West,Gadget")
        appendLine("West,Gadget")
        appendLine("West,Gadget")
        appendLine("East,Widget")
        appendLine("West,Gadget")
    }

    // 两个分类列：均匀分布（无关联）
    private val chiSquareUniformCsv: String = buildString {
        appendLine("region,product")
        appendLine("East,Widget")
        appendLine("East,Gadget")
        appendLine("West,Widget")
        appendLine("West,Gadget")
        appendLine("East,Widget")
        appendLine("East,Gadget")
        appendLine("West,Widget")
        appendLine("West,Gadget")
    }

    private fun hypothesis(
        b: StrAgentHostBootstrap,
        datasetId: String,
        testType: String,
        groupColumn: String,
        valueColumn: String? = null,
        columnColumn: String? = null,
    ): JsonObject {
        val args = buildJsonObject {
            put("datasetId", datasetId)
            put("testType", testType)
            put("groupColumn", groupColumn)
            valueColumn?.let { put("valueColumn", it) }
            columnColumn?.let { put("columnColumn", it) }
        }
        return tool(b, "stragent_hypothesis_test").invoke(args)
    }

    @Test
    fun t_test_significant_difference() {
        val b = bootstrap()
        try {
            val dsId = import(b, tTestCsv)
            val result = hypothesis(b, dsId, "t-test", "group", "value")
            assertEquals("t-test", result["testType"]!!.jsonPrimitive.content)
            assertEquals("A", result["groupA"]!!.jsonPrimitive.content)
            assertEquals("B", result["groupB"]!!.jsonPrimitive.content)
            assertEquals(5, result["sampleA"]!!.jsonPrimitive.content.toInt())
            assertEquals(5, result["sampleB"]!!.jsonPrimitive.content.toInt())
            assertTrue(result["significant"]!!.jsonPrimitive.content.toBoolean(), "差异大应显著")
            assertTrue(result["pValue"]!!.jsonPrimitive.content.toDouble() < 0.05)
            val interp = result["interpretation"]!!.jsonPrimitive.content
            assertTrue(interp.contains("显著"), "解读应含「显著」")
        } finally {
            b.close()
        }
    }

    @Test
    fun t_test_no_significant_difference() {
        val b = bootstrap()
        try {
            val dsId = import(b, tTestNoDiffCsv)
            val result = hypothesis(b, dsId, "t-test", "group", "value")
            assertFalse(result["significant"]!!.jsonPrimitive.content.toBoolean(), "无差异不应显著")
            assertTrue(result["pValue"]!!.jsonPrimitive.content.toDouble() >= 0.05)
        } finally {
            b.close()
        }
    }

    @Test
    fun chi_square_significant_association() {
        val b = bootstrap()
        try {
            val dsId = import(b, chiSquareCsv)
            val result = hypothesis(b, dsId, "chi-square", "region", null, "product")
            assertEquals("chi-square", result["testType"]!!.jsonPrimitive.content)
            assertTrue(result["significant"]!!.jsonPrimitive.content.toBoolean(), "关联的列应显著")
            assertTrue(result["pValue"]!!.jsonPrimitive.content.toDouble() < 0.05)
            assertTrue(result["chiSquare"]!!.jsonPrimitive.content.toDouble() > 0)
            assertTrue(result["degreesOfFreedom"]!!.jsonPrimitive.content.toInt() >= 1)
            // 应含 observed 和 expected
            assertTrue(result.containsKey("observed"), "应含观察频数")
            assertTrue(result.containsKey("expected"), "应含期望频数")
            val observed = result["observed"]!!.jsonArray
            assertTrue(observed.isNotEmpty(), "观察频数不应为空")
        } finally {
            b.close()
        }
    }

    @Test
    fun chi_square_no_association() {
        val b = bootstrap()
        try {
            val dsId = import(b, chiSquareUniformCsv)
            val result = hypothesis(b, dsId, "chi-square", "region", null, "product")
            assertFalse(result["significant"]!!.jsonPrimitive.content.toBoolean(), "均匀分布不应显著")
        } finally {
            b.close()
        }
    }

    @Test
    fun bad_test_type_gives_error() {
        val b = bootstrap()
        try {
            val dsId = import(b, tTestCsv)
            val args = buildJsonObject {
                put("datasetId", dsId)
                put("testType", "anova")
                put("groupColumn", "group")
                put("valueColumn", "value")
            }
            try {
                tool(b, "stragent_hypothesis_test").invoke(args)
                throw AssertionError("should have thrown")
            } catch (e: IllegalArgumentException) {
                val msg = e.message ?: ""
                assertTrue(msg.contains("不支持"), "应报不支持的类型: $msg")
            }
        } finally {
            b.close()
        }
    }

    @Test
    fun t_test_missing_value_column_gives_error() {
        val b = bootstrap()
        try {
            val dsId = import(b, tTestCsv)
            val args = buildJsonObject {
                put("datasetId", dsId)
                put("testType", "t-test")
                put("groupColumn", "group")
                // 不传 valueColumn
            }
            try {
                tool(b, "stragent_hypothesis_test").invoke(args)
                throw AssertionError("should have thrown")
            } catch (e: IllegalArgumentException) {
                val msg = e.message ?: ""
                assertTrue(msg.contains("valueColumn") || msg.contains("数值列"), "应提示缺 valueColumn: $msg")
            }
        } finally {
            b.close()
        }
    }

    @Test
    fun chi_square_missing_column_column_gives_error() {
        val b = bootstrap()
        try {
            val dsId = import(b, chiSquareCsv)
            val args = buildJsonObject {
                put("datasetId", dsId)
                put("testType", "chi-square")
                put("groupColumn", "region")
                // 不传 columnColumn
            }
            try {
                tool(b, "stragent_hypothesis_test").invoke(args)
                throw AssertionError("should have thrown")
            } catch (e: IllegalArgumentException) {
                val msg = e.message ?: ""
                assertTrue(msg.contains("columnColumn") || msg.contains("列维度"), "应提示缺 columnColumn: $msg")
            }
        } finally {
            b.close()
        }
    }

    @Test
    fun notice_contains_assumptions() {
        val b = bootstrap()
        try {
            val dsId = import(b, tTestCsv)
            val result = hypothesis(b, dsId, "t-test", "group", "value")
            val notice = result["notice"]!!.jsonPrimitive.content
            assertTrue(notice.contains("方差齐性") || notice.contains("Student"), "notice 应说明假设")
        } finally {
            b.close()
        }
    }
}
