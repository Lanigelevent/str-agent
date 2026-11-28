package com.stragent.bench.suites

import com.stragent.bench.fixtures.findFixtures
import com.stragent.bench.model.SuiteResult
import com.stragent.core.stats.analysisToJson
import com.stragent.core.stats.analyze
import com.stragent.core.stats.contractCompare
import com.stragent.core.stats.datasetOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * 套件一 · numeric-accuracy（数值精度）
 *
 * 复用 `analysis-baseline.json` 金标，对每一个样本跑确定性策展分析管线
 * （com.stragent.core.stats.analyze）→ 序列化 → 与金标做结构化比对。
 * 口径与门 A 完全一致（单一事实源：AnalysisContract.contractCompare）。
 *
 * 这是「数字可溯源」壁垒在数据层的最硬证据：无 LLM、纯函数、逐样本可复现。
 */
fun runNumericAccuracy(): SuiteResult {
    val fx = findFixtures()
    val baseline = Json.parseToJsonElement(
        File(fx, "baseline/analysis-baseline.json").readText(),
    ).jsonObject
    val sampleDir = File(fx, "sample").absolutePath
    val names = baseline.keys.sorted()

    var passed = 0
    val failures = mutableListOf<String>()
    for (name in names) {
        val expected = baseline[name]!!.jsonObject
        val actual: JsonObject = analysisToJson(analyze(datasetOf(name, sampleDir)))
        val diffs = contractCompare(expected, actual)
        if (diffs.isEmpty()) {
            passed += 1
        } else if (failures.size < 12) {
            failures.add("$name: ${diffs.take(3).joinToString("; ")}")
        }
    }
    return SuiteResult(
        name = "numeric-accuracy",
        passed = passed,
        total = names.size,
        failures = failures,
        notes = "analysis-baseline.json 逐样本结构化比对（确定性分析管线，无 LLM）。" +
            "比对口径：字符串精确 / 整数精确 / 浮点相对容差 1e-9。",
    )
}
