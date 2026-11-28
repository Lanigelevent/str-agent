package com.stragent.bench.suites

import com.stragent.bench.fixtures.MapPrefs
import com.stragent.bench.fixtures.findFixtures
import com.stragent.bench.model.SuiteResult
import com.stragent.core.brief.buildDatasetBrief
import com.stragent.core.report.ReportLib
import com.stragent.core.stats.datasetOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 套件二 · brief-accuracy（数据摘要精度）
 *
 * 复用 `brief-baseline.json` 金标：三组偏好（行业×类型×风格）逐个样本重建摘要，
 * 与金标逐字节比对。摘要是「数字可溯源」的源头——报告里每个数都必须能在摘要中找到，
 * 因此摘要本身的确定性是壁垒的第一道闸门。
 */
fun runBriefAccuracy(): SuiteResult {
    val fx = findFixtures()
    val baseline = Json.parseToJsonElement(
        File(fx, "baseline/brief-baseline.json").readText(),
    ).jsonObject
    val sampleDir = File(fx, "sample").absolutePath
    val cache = mutableMapOf<String, com.stragent.core.dataset.Dataset>()

    fun ds(name: String): com.stragent.core.dataset.Dataset =
        cache.getOrPut(name) { datasetOf(name, sampleDir) }

    var passed = 0
    var total = 0
    val failures = mutableListOf<String>()

    for ((groupKey, groupEl) in baseline) {
        val parts = groupKey.split("×")
        if (parts.size != 3) continue
        val (industry, type, style) = parts
        val prefs = MapPrefs().apply {
            ReportLib.setIndustry(this, industry)
            ReportLib.setReportType(this, type)
            ReportLib.setReportStyle(this, style)
        }
        val group = groupEl.jsonObject
        for ((sampleName, sampleEl) in group) {
            total += 1
            val expected = sampleEl.jsonObject["brief"]!!.jsonPrimitive.content
            val actual = buildDatasetBrief(ds(sampleName), prefs)
            if (expected == actual) {
                passed += 1
            } else if (failures.size < 10) {
                val cut = expected.indices.firstOrNull { expected[it] != actual.getOrNull(it) } ?: 0
                failures.add("$groupKey/$sampleName @$cut")
            }
        }
    }
    return SuiteResult(
        name = "brief-accuracy",
        passed = passed,
        total = total,
        failures = failures,
        notes = "brief-baseline.json 逐字节比对（3 组偏好 × 全样本）。摘要确定性是数字溯源壁垒的第一道闸门。",
    )
}
