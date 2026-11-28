package com.stragent.bench.external

import java.io.File
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * T-057：外部基准回归判定。
 *
 * 规则（D17 G-20）：
 *  - 与入库基线比通过率，**相对下降 > 5%** → FAIL（非零退出，附明细）；
 *  - 已知失败题（manifest.knownFailure，官方 gold 自相矛盾）不计入分母；
 *  - 题集数量 / 豁免集合变化 → FAIL 并要求显式重建基线（否则换分母就能洗掉退化）；
 *  - 数据集缺失由 ExternalBench 显式 SKIP，不进本判定。
 */
class ExternalRegression(private val baselineFile: File = defaultBaselineFile()) {

    data class Baseline(
        val passed: Int,
        val total: Int,
        val tolerancePct: Double,
        val knownFailures: List<String>,
    ) {
        val rate: Double get() = if (total == 0) 0.0 else passed.toDouble() / total.toDouble()
    }

    data class Verdict(
        val ok: Boolean,
        val failures: List<String>,
        val warnings: List<String>,
        val baselineRate: Double?,
        val currentRate: Double,
    ) {
        /** 相对下降百分比；基线缺失/坏掉时为 null。正数表示变差。 */
        val dropPct: Double?
            get() = baselineRate?.takeIf { it > 0 }?.let { (it - currentRate) / it * 100.0 }
    }

    fun check(
        passed: Int,
        total: Int,
        knownFailures: List<String>,
        updateBaseline: Boolean = false,
    ): Verdict {
        val currentRate = if (total == 0) 0.0 else passed.toDouble() / total.toDouble()
        if (total <= 0) {
            return Verdict(
                ok = false,
                failures = listOf("外部基准没有可计分题目（total=0）"),
                warnings = emptyList(),
                baselineRate = null,
                currentRate = currentRate,
            )
        }
        if (!baselineFile.isFile) {
            if (updateBaseline) {
                writeBaseline(passed, total, knownFailures)
                return Verdict(
                    ok = true,
                    failures = emptyList(),
                    warnings = listOf("外部基准基线不存在，已写入初始基线：${baselineFile.absolutePath}"),
                    baselineRate = currentRate,
                    currentRate = currentRate,
                )
            }
            return Verdict(
                ok = false,
                failures = listOf("外部基准基线缺失：${baselineFile.absolutePath}（基线必须入库，不能首跑自动放行）"),
                warnings = emptyList(),
                baselineRate = null,
                currentRate = currentRate,
            )
        }

        val baseline = runCatching { readBaseline() }.getOrElse { error ->
            return Verdict(
                ok = false,
                failures = listOf("外部基准基线损坏：${baselineFile.absolutePath}（${error.message}）"),
                warnings = emptyList(),
                baselineRate = null,
                currentRate = currentRate,
            )
        }

        val failures = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (baseline.total != total) {
            failures += "外部基准题集变化：基线 total=${baseline.total}，当前 total=$total" +
                "（需 --update-baseline 显式重建，不能默默换分母）"
        }
        if (baseline.knownFailures.toSet() != knownFailures.toSet()) {
            failures += "外部基准已知失败集合变化：基线 ${baseline.knownFailures}，当前 $knownFailures"
        }
        if (failures.isEmpty()) {
            val drop = (baseline.rate - currentRate) / baseline.rate * 100.0
            if (drop > baseline.tolerancePct) {
                failures += "外部基准下降 %.2f%% > %.1f%%：基线 %d/%d (%.2f%%) → 当前 %d/%d (%.2f%%)".format(
                    drop, baseline.tolerancePct,
                    baseline.passed, baseline.total, baseline.rate * 100.0,
                    passed, total, currentRate * 100.0,
                )
            } else if (currentRate > baseline.rate) {
                warnings += "外部基准高于基线：%d/%d (%.2f%%) → %d/%d (%.2f%%)，可 --update-baseline 固化".format(
                    baseline.passed, baseline.total, baseline.rate * 100.0,
                    passed, total, currentRate * 100.0,
                )
            }
        }
        if (updateBaseline) {
            // 关键：只有「口径一致且没有退化」才允许覆盖基线；先写后判会把退化洗成新基线。
            if (failures.isEmpty()) {
                writeBaseline(passed, total, knownFailures)
                warnings += "外部基准基线已更新：${baselineFile.absolutePath}"
            } else {
                warnings += "外部基准基线未更新：存在口径变化/退化，先修复"
            }
        }

        return Verdict(
            ok = failures.isEmpty(),
            failures = failures,
            warnings = warnings,
            baselineRate = baseline.rate,
            currentRate = currentRate,
        )
    }

    fun readBaseline(): Baseline {
        val root = Json.parseToJsonElement(baselineFile.readText(Charsets.UTF_8)) as? JsonObject
            ?: throw IllegalArgumentException("顶层必须是 JSON 对象")
        val passed = (root["passed"] as? JsonPrimitive)?.intOrNull
            ?: throw IllegalArgumentException("缺少 passed")
        val total = (root["total"] as? JsonPrimitive)?.intOrNull
            ?: throw IllegalArgumentException("缺少 total")
        val tolerance = (root["tolerancePct"] as? JsonPrimitive)?.doubleOrNull ?: DEFAULT_TOLERANCE_PCT
        val known = (root["knownFailures"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: emptyList()
        return Baseline(passed, total, tolerance, known)
    }

    fun writeBaseline(passed: Int, total: Int, knownFailures: List<String>) {
        baselineFile.parentFile.mkdirs()
        val json = buildJsonObject {
            put("benchmark", "spider2-lite-sqlite-subset")
            put("capability", "sql-execution-only")
            put("passed", passed)
            put("total", total)
            put("tolerancePct", DEFAULT_TOLERANCE_PCT)
            put("knownFailures", buildJsonArray { knownFailures.sorted().forEach { add(JsonPrimitive(it)) } })
            put("recordedAt", Instant.now().toString())
        }
        baselineFile.writeText(json.toString() + "\n", Charsets.UTF_8)
    }

    companion object {
        const val DEFAULT_TOLERANCE_PCT = 5.0

        fun defaultBaselineFile(): File =
            File(ExternalBench.resolveRepoRoot(), "bench/external/baseline/external-baseline.json")
    }
}
