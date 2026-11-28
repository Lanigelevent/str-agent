package com.stragent.core.stats

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset
import com.stragent.core.csv.Csv.inferTypes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 门 A：analysis-baseline.json 结构化比对。
 *
 * 比对逻辑（序列化 + 结构化 diff）已抽到 `AnalysisContract` 成为公共单一事实源，
 * 本测试与 `bench/` 评测模块共用同一套口径，杜绝漂移。
 *
 * 跨语言比对口径：字符串精确、整数精确、浮点相对容差 1e-9；
 * TS 侧 undefined 省略 → 基线缺 key 时实际为 null 视为对齐。
 */
class AnalysisBaselineGate {
    private val baselinePath = "../fixtures/baseline/analysis-baseline.json"
    private val sampleDir = "../fixtures/sample"
    private val baseline = Json.parseToJsonElement(File(baselinePath).readText()).jsonObject
    private val baselineNames = baseline.keys.sorted()

    private fun datasetOf(name: String): Dataset {
        val text = File(sampleDir, "$name.csv").readText()
        val parsed = Csv.parseDelimited(text)
        val limited = Csv.limitRows(parsed)
        val types = Csv.inferTypes(limited.rows, limited.columns.size)
        return Dataset(
            name = "$name.csv", importedAt = 1,
            columns = limited.columns, rows = limited.rows, columnTypes = types,
            delimiter = limited.delimiter, truncated = limited.truncated, sourceRows = limited.totalRows,
        )
    }

    private fun analyze(name: String): AnalysisResult = analyze(datasetOf(name))

    @Test
    fun `门 A——全部样本与 analysis-baseline 结构一致`() {
        assertTrue(baselineNames.size >= 50, "基线样本数 ${baselineNames.size} < 50")
        for (name in baselineNames) {
            val expected = baseline[name]!!.jsonObject
            val actual = analysisToJson(analyze(name))
            val diffs = contractCompare(expected, actual)
            // 每个样本单独报，失败时能直接指认
            assertTrue(diffs.isEmpty(), "$name: ${diffs.take(5).joinToString("; ")}")
        }
    }

    @Test
    fun `门 A——覆盖度下限`() {
        // 取 analysis-contract-entry 的 FLOOR
        val floor = mapOf("yoy" to 5, "groupYoY" to 3, "extremes" to 5, "crossTab" to 10, "groupTrend" to 5)
        val modules = listOf("yoy", "groupYoY", "extremes", "crossTab", "groupTrend")
        for (m in modules) {
            val count = baselineNames.count { name ->
                val entry = baseline[name]?.jsonObject
                entry != null && entry[m] !is JsonNull && entry[m] != null
            }
            assertTrue(count >= floor[m]!!, "$m 只覆盖 $count 个样本，低于下限 ${floor[m]}")
        }
    }
}
