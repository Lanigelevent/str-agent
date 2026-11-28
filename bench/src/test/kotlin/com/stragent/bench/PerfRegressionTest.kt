package com.stragent.bench

import java.io.File
import kotlin.test.*

/**
 * D15 T12: PerfRegression 测试
 */
class PerfRegressionTest {

    /** 构造带分布样本的基线文件（T-158b：稳健判据的测试夹具） */
    private fun writeSamplesBaseline(file: File, name: String, baselineMs: Long, samples: List<Long>) {
        file.writeText(
            """[{"name":"$name","baselineMs":$baselineMs,"baselineMemoryMb":0,"samplesMs":$samples}]""",
            Charsets.UTF_8,
        )
    }

    @Test
    fun `有分布样本时——中位作基线，噪声带内的当前值不再假红（Q1 回归钉）`() {
        val baseline = tempBaseline()
        try {
            // 观测分布 434..911（D25 Q1 实测形态）：中位 637、P90=911 → FAIL=1122
            writeSamplesBaseline(baseline, "brief-latency-x", 637, listOf(434, 513, 554, 571, 617, 633, 637, 716, 717, 747, 748, 820, 911))
            val pr = PerfRegression(baseline)
            // 当前 911ms（曾经的 FAIL 实锤值）：点值基线口径 1.58× FAIL；稳健口径 ≤ P90×1.1 → 不假红
            val r = pr.check(listOf(PerfRegression.Metric("brief-latency-x", 637, 911)))
            assertEquals(0, r.failures.size, "观测分布内的值不得 FAIL：${r.failures}")
            // 中位作基线：911/637 = 1.43 ≥ WARN → 应 WARN（降级可见但不拦截）
            assertTrue(r.metrics.first().level == PerfRegression.Metric.Level.WARN, "应 WARN 不应 FAIL")
        } finally {
            baseline.parentFile.delete()
        }
    }

    @Test
    fun `有分布样本时——真退化仍 FAIL（超出观测分布的 1_1 倍）`() {
        val baseline = tempBaseline()
        try {
            writeSamplesBaseline(baseline, "m", 637, listOf(434, 513, 554, 571, 617, 633, 637, 716, 717, 747, 748, 820, 911))
            val pr = PerfRegression(baseline)
            // 1300ms：远超 P90×1.1=1002 → 真 FAIL（门禁的牙齿不松）
            val r = pr.check(listOf(PerfRegression.Metric("m", 637, 1300)))
            assertEquals(1, r.failures.size, "超出观测分布的退化必须 FAIL")
        } finally {
            baseline.parentFile.delete()
        }
    }

    @Test
    fun `无样本条目——既有 1_2 与 1_5 倍率行为零变化`() {
        val baseline = tempBaseline()
        try {
            baseline.writeText("""[{"name":"plain","baselineMs":100,"baselineMemoryMb":0}]""", Charsets.UTF_8)
            val pr = PerfRegression(baseline)
            assertEquals(
                PerfRegression.Metric.Level.FAIL,
                pr.check(listOf(PerfRegression.Metric("plain", 100, 160))).metrics.first().level,
                "150% 应 FAIL（1.5× 口径不变）",
            )
            assertEquals(
                PerfRegression.Metric.Level.WARN,
                pr.check(listOf(PerfRegression.Metric("plain", 100, 125))).metrics.first().level,
                "125% 应 WARN（1.2× 口径不变）",
            )
        } finally {
            baseline.parentFile.delete()
        }
    }

    @Test
    fun `内存维有分布样本时——噪声带内的当前值不再假红（P-231 内存维同款口径）`() {
        val baseline = tempBaseline()
        try {
            // 内存维的观测分布（同机器不同时刻量到的采样峰值）：中位 150、P90=200 → FAIL=300
            baseline.writeText(
                """[{"name":"m","baselineMs":0,"baselineMemoryMb":150,"samplesMemoryMb":[109,130,150,180,200]}]""",
                Charsets.UTF_8,
            )
            val pr = PerfRegression(baseline)
            // 当前 200MB：点值口径 1.33× 不 FAIL；分布口径 ≤ P90×1.1=220 → 也不 FAIL
            val inside = pr.check(listOf(PerfRegression.Metric("m", 0, 0, baselineMemoryMb = 150, currentMemoryMb = 200)))
            assertEquals(0, inside.failures.size, "分布带内的内存值不得 FAIL：${inside.failures}")
            // 当前 400MB：远超 P90×1.1 → 真 FAIL（内存维的牙齿不松）
            val outside = pr.check(listOf(PerfRegression.Metric("m", 0, 0, baselineMemoryMb = 150, currentMemoryMb = 400)))
            assertEquals(1, outside.failures.size, "超出观测分布的内存退化必须 FAIL")
        } finally {
            baseline.parentFile.delete()
        }
    }

    @Test
    fun `刷新基线时分布样本要保留并追加（否则稳健判据退化成点值判据）`() {
        val baseline = tempBaseline()
        try {
            baseline.writeText(
                """[{"name":"m","baselineMs":100,"baselineMemoryMb":50,"samplesMs":[90,100,110],"samplesMemoryMb":[40,50,60]}]""",
                Charsets.UTF_8,
            )
            val pr = PerfRegression(baseline)
            val result = pr.check(
                listOf(PerfRegression.Metric("m", 100, 105, baselineMemoryMb = 50, currentMemoryMb = 55)),
                updateBaseline = true,
            )
            assertTrue(result.ok, "刷新应通过：${result.failures}")
            val text = baseline.readText(Charsets.UTF_8)
            assertTrue(text.contains("samplesMs"), "样本必须留在基线文件里：$text")
            assertTrue(text.contains("samplesMemoryMb"), "内存样本必须留在基线文件里：$text")
            assertTrue(text.contains("105"), "本次实测值要进基线：$text")
            assertTrue(text.contains("90"), "历史样本要保留：$text")
        } finally {
            baseline.delete()
            baseline.parentFile.delete()
        }
    }

    private fun tempBaseline(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "str-agent-test-${System.nanoTime()}")
        dir.mkdirs()
        return File(dir, "perf-baseline.json")
    }

    @Test
    fun `首次运行无基线文件时写入基线并返回通过`() {
        val baseline = tempBaseline()
        try {
            val sut = PerfRegression(baseline)
            val current = listOf(
                PerfRegression.Metric(name = "test-parse", baselineMs = 1000, currentMs = 1000),
            )
            val result = sut.check(current)
            assertTrue(result.ok, "首次运行应通过：${result.warnings}")
            assertTrue(baseline.exists(), "应创建基线文件")
        } finally {
            baseline.delete()
            baseline.parentFile.delete()
        }
    }

    @Test
    fun `退化超过 50% 时标记 FAIL`() {
        val baseline = tempBaseline()
        try {
            // 写入基线
            baseline.writeText("""[{"name":"test-parse","baselineMs":1000,"baselineMemoryMb":100}]""")
            val sut = PerfRegression(baseline)
            val current = listOf(
                PerfRegression.Metric(name = "test-parse", baselineMs = 1000, currentMs = 2000), // 2x = 100% 退化
            )
            val result = sut.check(current)
            assertFalse(result.passed, "2x 退化应 FAIL")
            assertEquals(1, result.failures.size)
        } finally {
            baseline.delete()
            baseline.parentFile.delete()
        }
    }

    @Test
    fun `退化超过 20% 时标记 WARN`() {
        val baseline = tempBaseline()
        try {
            baseline.writeText("""[{"name":"test-parse","baselineMs":1000,"baselineMemoryMb":100}]""")
            val sut = PerfRegression(baseline)
            val current = listOf(
                PerfRegression.Metric(name = "test-parse", baselineMs = 1000, currentMs = 1300), // 1.3x = 30% 退化
            )
            val result = sut.check(current)
            assertTrue(result.passed, "30% 退化应 WARN 而非 FAIL")
            assertEquals(1, result.warnings.size)
        } finally {
            baseline.delete()
            baseline.parentFile.delete()
        }
    }

    @Test
    fun `无退化时静默通过`() {
        val baseline = tempBaseline()
        try {
            baseline.writeText("""[{"name":"test-parse","baselineMs":1000,"baselineMemoryMb":100}]""")
            val sut = PerfRegression(baseline)
            val current = listOf(
                PerfRegression.Metric(name = "test-parse", baselineMs = 1000, currentMs = 900), // 改善
            )
            val result = sut.check(current)
            assertTrue(result.ok)
            assertTrue(result.warnings.isEmpty())
            assertTrue(result.failures.isEmpty())
        } finally {
            baseline.delete()
            baseline.parentFile.delete()
        }
    }

    @Test
    fun `updateBaseline 在 FAIL 时不覆盖`() {
        val baseline = tempBaseline()
        try {
            baseline.writeText("""[{"name":"test-parse","baselineMs":1000,"baselineMemoryMb":100}]""")
            val sut = PerfRegression(baseline)
            val current = listOf(
                PerfRegression.Metric(name = "test-parse", baselineMs = 1000, currentMs = 3000),
            )
            sut.check(current, updateBaseline = true)
            val content = baseline.readText()
            assertFalse(content.contains("3000"), "FAIL 时不应更新基线")
        } finally {
            baseline.delete()
            baseline.parentFile.delete()
        }
    }

    @Test
    fun `updateBaseline 在通过时覆盖`() {
        val baseline = tempBaseline()
        try {
            baseline.writeText("""[{"name":"test-parse","baselineMs":1000,"baselineMemoryMb":100}]""")
            val sut = PerfRegression(baseline)
            val current = listOf(
                PerfRegression.Metric(name = "test-parse", baselineMs = 1000, currentMs = 900),
            )
            sut.check(current, updateBaseline = true)
            val content = baseline.readText()
            assertTrue(content.contains("900"), "通过时应更新基线")
        } finally {
            baseline.delete()
            baseline.parentFile.delete()
        }
    }

    @Test
    fun `写入的基线能原样读回 - 往返对比会真的判退化`() {
        val baseline = tempBaseline()
        try {
            PerfRegression.writeBaseline(
                listOf(
                    PerfRegression.Metric(name = "parse", baselineMs = 0, currentMs = 100),
                    PerfRegression.Metric(name = "resident", baselineMs = 0, currentMs = 0, currentMemoryMb = 200),
                ),
                baseline,
            )
            val result = PerfRegression(baseline).check(
                listOf(
                    PerfRegression.Metric(name = "parse", baselineMs = 0, currentMs = 200),
                    PerfRegression.Metric(name = "resident", baselineMs = 0, currentMs = 0, currentMemoryMb = 200),
                ),
            )
            assertFalse(result.passed, "2x 耗时退化必须 FAIL（读不回基线就会误判为新指标而放行）")
            assertEquals(1, result.failures.size)
            assertTrue(result.failures[0].contains("parse"), "失败项应指名指标：${result.failures}")
        } finally {
            baseline.delete()
            baseline.parentFile.delete()
        }
    }
}
