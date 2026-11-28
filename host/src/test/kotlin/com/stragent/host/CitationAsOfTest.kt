package com.stragent.host

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T-205 引用时效标注测试：导出自动带「报告元信息」节；溯源面步骤/顶层带数据时点。
 */
class CitationAsOfTest {

    private fun tempDir(): java.nio.file.Path = java.nio.file.Files.createTempDirectory("asof-test")

    private fun step() = com.stragent.core.trace.ProvenanceStep(
        tool = "stragent_session_overview",
        args = buildJsonObject { },
        argsHash = "ah",
        resultHash = "rh",
        datasetHash = "dh",
        elapsedMs = 1L,
    )

    @Test
    fun `导出自动带报告元信息节（数据时点标注）`() {
        val datasets = com.stragent.data.AbilityDatasets()
        val provenance = com.stragent.data.ProvenanceRecorder(datasets)
        datasets.put("ds-1", com.stragent.core.dataset.Dataset(
            name = "销售数据",
            importedAt = 1_700_000_000_000L,
            columns = listOf("city", "amount"),
            rows = listOf(listOf("上海", "12.5")),
            columnTypes = listOf(
                com.stragent.core.csv.ColumnType.TEXT,
                com.stragent.core.csv.ColumnType.NUMBER,
            ),
        ))
        provenance.record("ds-1", step())
        val tool = exportReportTool(outputDir = tempDir().toFile(), datasets = datasets, provenance = provenance)
        val result = tool.invoke(buildJsonObject {
            put("format", "html")
            put("title", "行业报告")
            put("sections", JsonArray(listOf(buildJsonObject {
                put("title", JsonPrimitive("市场综述"))
                put("content", JsonPrimitive("2023 年销售情况综述。"))
            })))
        })
        val html = result["html"]!!.jsonPrimitive.content
        assertTrue(html.contains("报告元信息"), html.take(300))
        assertTrue(html.contains("数据时点"), html.take(300))
        assertTrue(html.contains("销售数据"), html.take(300))
    }

    @Test
    fun `溯源面步骤与顶层带数据时点`() {
        val datasets = com.stragent.data.AbilityDatasets()
        val provenance = com.stragent.data.ProvenanceRecorder(datasets)
        datasets.put("ds-1", com.stragent.core.dataset.Dataset(
            name = "销售数据",
            importedAt = 1_700_000_000_000L,
            columns = listOf("city", "amount"),
            rows = listOf(listOf("上海", "12.5")),
            columnTypes = listOf(
                com.stragent.core.csv.ColumnType.TEXT,
                com.stragent.core.csv.ColumnType.NUMBER,
            ),
        ))
        provenance.record("ds-1", step())
        val tool = provenanceTool(recorder = provenance, replayTools = emptyList(), datasets = datasets)
        val result = tool.invoke(buildJsonObject { put("datasetId", JsonPrimitive("ds-1")) })
        val steps = result["steps"] as JsonArray
        assertEquals("1700000000000", (steps[0].jsonObject["datasetAsOf"] as JsonPrimitive).content)
        assertEquals("销售数据", result["datasetName"]!!.jsonPrimitive.content)
        assertEquals("1700000000000", result["datasetAsOf"]!!.jsonPrimitive.content)
    }
}
