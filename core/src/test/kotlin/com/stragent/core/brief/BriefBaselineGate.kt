package com.stragent.core.brief

import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset
import com.stragent.core.ports.PrefsStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 门 B：brief-baseline.json 逐字节比对。
 *
 * TS 侧 brief-contract-entry 用三组偏好 × 全部样本 dump 基线，每份存
 * { brief: string; target: number }。Kotlin 侧重建同样输入做字符串精确比对。
 *
 * 结构说明：JSON 是 { "组键": { "样本名": { brief, target } } }，
 * 组键 = "行业/类型/风格"（三组：general/standard/indepth、healthcare/anomaly/professional、
 * finance/trend/concise——从基线键读，不硬编码）。
 */
class BriefBaselineGate {
    private val baselinePath = "../fixtures/baseline/brief-baseline.json"
    private val sampleDir = "../fixtures/sample"
    private val baseline = Json.parseToJsonElement(File(baselinePath).readText()).jsonObject

    class FakePrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
    }

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

    private val datasets = HashMap<String, Dataset>()

    private fun datasetCached(name: String): Dataset =
        datasets.getOrPut(name) { datasetOf(name) }

    @Test
    fun `门 B——全部组 × 全部样本 brief 逐字节一致`() {
        var groups = 0
        var compared = 0
        val failures = mutableListOf<String>()
        for ((groupKey, groupEl) in baseline) {
            val parts = groupKey.split("×")
            require(parts.size == 3) { "组键格式: $groupKey" }
            val (industry, type, style) = parts
            val prefs = FakePrefs()
            com.stragent.core.report.ReportLib.setIndustry(prefs, industry)
            com.stragent.core.report.ReportLib.setReportType(prefs, type)
            com.stragent.core.report.ReportLib.setReportStyle(prefs, style)
            groups += 1
            val group = groupEl.jsonObject
            for ((sampleName, sampleEl) in group) {
                val expected = sampleEl.jsonObject["brief"]!!.jsonPrimitive.content
                val actual = buildDatasetBrief(datasetCached(sampleName), prefs)
                compared += 1
                if (expected != actual) {
                    // 报第一处差异的位置，便于定位
                    val cut = expected.indices.firstOrNull { expected[it] != actual.getOrNull(it) } ?: minOf(expected.length, actual.length)
                    val ctxE = expected.substring(maxOf(0, cut - 30), minOf(expected.length, cut + 30))
                    val ctxA = actual.substring(maxOf(0, cut - 30), minOf(actual.length, cut + 30))
                    failures.add("$groupKey/$sampleName @${cut}: TS「…$ctxE…」KT「…$ctxA…」(len ${expected.length} vs ${actual.length})")
                    if (failures.size >= 6) break
                }
            }
            if (failures.size >= 6) break
        }
        assertTrue(failures.isEmpty(), "前几处差异（已比 $compared 份）：\n" + failures.joinToString("\n"))
        assertEquals(3, groups, "基线应有 3 组偏好")
        assertTrue(compared >= 159, "比对份数 $compared < 159")
    }

    @Test
    fun `基线覆盖数与结构自检`() {
        var total = 0
        for ((_, groupEl) in baseline) total += groupEl.jsonObject.size
        assertTrue(total >= 150, "基线总份数 $total")
    }
}
