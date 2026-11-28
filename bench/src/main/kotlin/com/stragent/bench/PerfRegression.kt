package com.stragent.bench

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * D15 T12: 性能回归自动基线对比
 *
 * 用法：
 *   PerfRegression().check(current)                     — 与基线对比
 *   PerfRegression().check(current, updateBaseline=true) — 通过后覆盖基线
 *
 * 阈值（与 D15 报告口径一致）：
 *   - 退化 ≥ 20% → WARN（ratio ≥ 1.2）
 *   - 退化 ≥ 50% → FAIL（ratio ≥ 1.5）
 *
 * 基线文件：bench/baseline/perf-baseline.json（JSON 数组，可往返读写）。
 */
class PerfRegression(private val baselineFile: File = defaultBaselineFile()) {

    data class Metric(
        val name: String,
        val baselineMs: Long,
        val currentMs: Long,
        val baselineMemoryMb: Long = 0,
        val currentMemoryMb: Long = 0,
        /** 观测分布样本（min-of-5 历史值；D25 Q1：有样本时判据自动稳健化——中位基线 + P90×1.1 的 FAIL 判据） */
        val samplesMs: List<Long> = emptyList(),
        /**
         * 内存维的观测分布样本（同一套稳健口径）。
         *
         * 为什么内存也要分布（P-231）：内存基线是**采样峰值下限**（10ms 采样已用堆），
         * 它连**上一个套件留下的垃圾**一起量进去——同一个解析代码，在不同机器状态 /
         * 不同 GC 时机下量到的峰值能差一倍以上。点值基线在这个维度上假红得比时间维更凶。
         */
        val samplesMemoryMb: List<Long> = emptyList(),
    ) {
        /** 有分布样本时用**中位数**作基线（点值基线压在乐观尾是 Q1 假红的根因） */
        val effectiveBaselineMs: Long
            get() = if (samplesMs.size >= 3) {
                val s = samplesMs.sorted()
                s[s.size / 2]
            } else {
                baselineMs
            }

        /** 内存维的有效基线（同上：有分布取中位） */
        val effectiveBaselineMemoryMb: Long
            get() = if (samplesMemoryMb.size >= 3) {
                val s = samplesMemoryMb.sorted()
                s[s.size / 2]
            } else {
                baselineMemoryMb
            }

        val ratio: Double get() = if (effectiveBaselineMs > 0) currentMs.toDouble() / effectiveBaselineMs.toDouble() else 1.0
        val memoryRatio: Double get() = if (effectiveBaselineMemoryMb > 0) currentMemoryMb.toDouble() / effectiveBaselineMemoryMb.toDouble() else 1.0

        /** 有样本时 FAIL 判据 = max(1.5×中位, P90×1.1)——超出**观测分布**才算退化，噪声带内不假红 */
        private val failThresholdMs: Long
            get() {
                if (samplesMs.size < 3) return Math.round(effectiveBaselineMs * FAIL_RATIO)
                val s = samplesMs.sorted()
                val p90 = s[minOf(s.size - 1, (s.size * 9) / 10)]
                return maxOf(Math.round(effectiveBaselineMs * FAIL_RATIO), Math.round(p90 * 1.1))
            }

        /** 内存维的 FAIL 判据（与时间维同款口径；无内存基线 = 不判） */
        private val failThresholdMemoryMb: Long
            get() {
                if (effectiveBaselineMemoryMb <= 0) return Long.MAX_VALUE
                if (samplesMemoryMb.size < 3) return Math.round(effectiveBaselineMemoryMb * FAIL_RATIO)
                val s = samplesMemoryMb.sorted()
                val p90 = s[minOf(s.size - 1, (s.size * 9) / 10)]
                return maxOf(Math.round(effectiveBaselineMemoryMb * FAIL_RATIO), Math.round(p90 * 1.1))
            }

        val level: Level
            get() = when {
                // effectiveBaselineMs > 0 守卫：纯内存指标（baselineMs=0）的时间维不参与判定
                // （failThreshold 会是 0，0>=0 恒 FAIL——既有往返测试当场抓出）
                (effectiveBaselineMs > 0 && currentMs >= failThresholdMs) ||
                    currentMemoryMb >= failThresholdMemoryMb -> Level.FAIL
                ratio >= WARN_RATIO || memoryRatio >= WARN_RATIO -> Level.WARN
                else -> Level.OK
            }

        enum class Level { OK, WARN, FAIL }

        fun toCsv(): String = listOf(
            name,
            "$baselineMs",
            "$currentMs",
            "%.2f".format(ratio),
            "$baselineMemoryMb",
            "$currentMemoryMb",
            "%.2f".format(memoryRatio),
            level.name,
        ).joinToString(",")
    }

    data class CheckResult(
        val passed: Boolean,
        val warnings: List<String>,
        val failures: List<String>,
        val metrics: List<Metric>,
    ) {
        /** WARN 不阻断（性能波动是常态），只有 FAIL 才使整体不通过 */
        val ok: Boolean get() = passed && failures.isEmpty()
    }

    /**
     * 执行基线对比。
     * @param current 当前运行的性能指标列表（currentMs / currentMemoryMb 为本次实测值）
     * @param updateBaseline 对比通过后是否覆盖基线文件
     */
    fun check(current: List<Metric>, updateBaseline: Boolean = false): CheckResult {
        if (!baselineFile.exists()) {
            writeBaseline(current, baselineFile)
            return CheckResult(
                passed = true,
                warnings = listOf("基线文件不存在，已写入初始基线：${baselineFile.absolutePath}"),
                failures = emptyList(),
                metrics = current,
            )
        }

        val baseline = runCatching { readBaseline() }.getOrElse { error ->
            return CheckResult(
                passed = false,
                warnings = emptyList(),
                failures = listOf("基线文件损坏，无法解析：${baselineFile.absolutePath}（${error.message}）"),
                metrics = current,
            )
        }
        val warnings = mutableListOf<String>()
        val failures = mutableListOf<String>()
        var allPassed = true

        for (c in current) {
            val b = baseline.find { it.name == c.name }
            if (b == null) {
                warnings.add("基线中无 ${c.name} 的记录（新指标，跳过对比）")
                continue
            }
                val enriched = c.copy(
                    baselineMs = b.baselineMs,
                    baselineMemoryMb = b.baselineMemoryMb,
                    samplesMs = b.samplesMs,
                    samplesMemoryMb = b.samplesMemoryMb,
                )
            when (enriched.level) {
                Metric.Level.FAIL -> {
                    failures.add(
                        "FAIL ${c.name}: 基线 ${b.baselineMs}ms/${b.baselineMemoryMb}MB → " +
                            "当前 ${c.currentMs}ms/${c.currentMemoryMb}MB（耗时 %.2fx / 内存 %.2fx）"
                                .format(enriched.ratio, enriched.memoryRatio),
                    )
                    allPassed = false
                }
                Metric.Level.WARN -> {
                    warnings.add(
                        "WARN ${c.name}: 基线 ${b.baselineMs}ms/${b.baselineMemoryMb}MB → " +
                            "当前 ${c.currentMs}ms/${c.currentMemoryMb}MB（耗时 %.2fx / 内存 %.2fx）"
                                .format(enriched.ratio, enriched.memoryRatio),
                    )
                }
                Metric.Level.OK -> { /* 静默通过 */ }
            }
        }

        if (updateBaseline && allPassed) {
            writeBaseline(current, baselineFile)
        } else if (updateBaseline && !allPassed) {
            warnings.add("基线未更新：存在 FAIL 项，需先修复性能退化")
        }

        return CheckResult(
            passed = allPassed,
            warnings = warnings,
            failures = failures,
            metrics = current,
        )
    }

    private fun readBaseline(): List<Metric> {
        val text = baselineFile.readText(Charsets.UTF_8)
        val array = Json.parseToJsonElement(text) as? JsonArray
            ?: throw IllegalArgumentException("顶层必须是 JSON 数组")
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val baselineMs = (obj["baselineMs"] as? JsonPrimitive)?.longOrNull ?: 0L
            val baselineMemoryMb = (obj["baselineMemoryMb"] as? JsonPrimitive)?.longOrNull ?: 0L
            val samplesMs = (obj["samplesMs"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull } ?: emptyList()
            val samplesMemoryMb = (obj["samplesMemoryMb"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull } ?: emptyList()
            Metric(
                name = name,
                baselineMs = baselineMs,
                currentMs = baselineMs,
                baselineMemoryMb = baselineMemoryMb,
                currentMemoryMb = baselineMemoryMb,
                samplesMs = samplesMs,
                samplesMemoryMb = samplesMemoryMb,
            )
        }
    }

    companion object {
        private const val BASELINE_DIR = "bench/baseline"
        private const val BASELINE_FILE = "$BASELINE_DIR/perf-baseline.json"
        private const val WARN_RATIO = 1.2
        private const val FAIL_RATIO = 1.5

        fun defaultBaselineFile(): File =
            File(System.getProperty("user.dir") ?: ".", BASELINE_FILE)

        /**
         * 写入基线（本次实测值即下次对比的基线）。
         *
         * **分布样本要保留并追加**（P-231）：刷新基线时若把 `samplesMs` / `samplesMemoryMb`
         * 抹掉，稳健判据就退化成点值判据——下一次机器状态一变，假红照旧。样本窗口保留
         * 最近 [MAX_SAMPLES] 条，追加本次实测。
         */
        fun writeBaseline(metrics: List<Metric>, file: File = defaultBaselineFile()) {
            file.parentFile.mkdirs()
            val previous = if (file.exists()) runCatching { readBaselineFrom(file) }.getOrDefault(emptyList()) else emptyList()
            val array = buildJsonArray {
                for (m in metrics) {
                    val old = previous.find { it.name == m.name }
                    val timeSamples = (old?.samplesMs.orEmpty() + listOf(m.currentMs)).takeLast(MAX_SAMPLES)
                    val memSamples = (old?.samplesMemoryMb.orEmpty() + listOf(m.currentMemoryMb)).takeLast(MAX_SAMPLES)
                    add(buildJsonObject {
                        put("name", m.name)
                        put("baselineMs", m.currentMs)
                        put("baselineMemoryMb", m.currentMemoryMb)
                        if (timeSamples.isNotEmpty()) {
                            put("samplesMs", buildJsonArray { timeSamples.forEach { add(JsonPrimitive(it)) } })
                        }
                        if (memSamples.isNotEmpty() && m.currentMemoryMb > 0) {
                            put("samplesMemoryMb", buildJsonArray { memSamples.forEach { add(JsonPrimitive(it)) } })
                        }
                    })
                }
            }
            // 缩进输出：基线文件是**要被 review 的**（每次刷新都是一次 diff）——
            // 紧凑单行会让整份文件在 diff 里变成「全文重写」，把真实的变化淹没掉。
            // 缩进宽度与末尾换行沿用本文件既有格式（2 空格 + 末尾换行），
            // 保证「刷新基线」的 diff 只包含数字变化。
            file.writeText(
                Json { prettyPrint = true; prettyPrintIndent = "  " }
                    .encodeToString(JsonArray.serializer(), array) + "\n",
                Charsets.UTF_8,
            )
        }

        /** 样本窗口上限（保留最近 N 条观测） */
        const val MAX_SAMPLES = 13

        private fun readBaselineFrom(file: File): List<Metric> {
            val text = file.readText(Charsets.UTF_8)
            val array = Json.parseToJsonElement(text) as? JsonArray ?: return emptyList()
            return array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                Metric(
                    name = name,
                    baselineMs = (obj["baselineMs"] as? JsonPrimitive)?.longOrNull ?: 0L,
                    currentMs = (obj["baselineMs"] as? JsonPrimitive)?.longOrNull ?: 0L,
                    baselineMemoryMb = (obj["baselineMemoryMb"] as? JsonPrimitive)?.longOrNull ?: 0L,
                    currentMemoryMb = (obj["baselineMemoryMb"] as? JsonPrimitive)?.longOrNull ?: 0L,
                    samplesMs = (obj["samplesMs"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull } ?: emptyList(),
                    samplesMemoryMb = (obj["samplesMemoryMb"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull } ?: emptyList(),
                )
            }
        }
    }
}
