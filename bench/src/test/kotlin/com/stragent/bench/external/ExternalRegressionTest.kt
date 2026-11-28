package com.stragent.bench.external

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T-057：外部基准回归阈值测试。
 *
 * 口径钉死：相对基线下降 > 5% 才 FAIL；题集/豁免集合变化必须显式重建基线；
 * 基线缺失不自动放行。
 */
class ExternalRegressionTest {

    private fun tempBaseline(): java.io.File =
        Files.createTempDirectory("external-regression").resolve("baseline.json").toFile()

    private fun reg(
        passed: Int,
        total: Int,
        known: List<String> = emptyList(),
        baseline: List<Int>? = null,
        baselineKnown: List<String> = emptyList(),
    ): ExternalRegression.Verdict {
        val file = tempBaseline()
        if (baseline != null) {
            ExternalRegression(file).writeBaseline(baseline[0], baseline[1], baselineKnown)
        }
        return ExternalRegression(file).check(passed, total, known)
    }

    @Test
    fun `下降_4_08_百分点_不告警`() {
        // 基线 49/49 = 100%；当前 47/49 = 95.92%，相对下降 4.08% ≤ 5%
        val v = reg(47, 49, baseline = listOf(49, 49))
        assertTrue(v.ok, v.failures.joinToString())
        assertEquals(4.08, v.dropPct!!, 0.02)
    }

    @Test
    fun `下降超过百分之五告警`() {
        // 46/49 = 93.88%，相对下降 6.12% > 5%
        val v = reg(46, 49, baseline = listOf(49, 49))
        assertFalse(v.ok)
        assertTrue(v.failures.single().contains("6.12%"), v.failures.single())
        assertTrue(v.failures.single().contains("> 5.0%"))
    }

    @Test
    fun `基线通过率不是 100 时按相对下降计算`() {
        // 基线 45/50=90%；当前 42/50=84% → 相对下降 6.67% → FAIL
        val v = reg(42, 50, baseline = listOf(45, 50))
        assertFalse(v.ok)
        assertEquals(6.67, v.dropPct!!, 0.02)
    }

    @Test
    fun `题集变化必须显式重建基线`() {
        val v = reg(49, 50, baseline = listOf(49, 49))
        assertFalse(v.ok)
        assertTrue(v.failures.any { it.contains("题集变化") }, v.failures.joinToString())
    }

    @Test
    fun `豁免集合变化同样拦截`() {
        val v = reg(49, 49, known = listOf("local331"), baseline = listOf(49, 49))
        assertFalse(v.ok)
        assertTrue(v.failures.any { it.contains("已知失败集合变化") }, v.failures.joinToString())
    }

    @Test
    fun `基线缺失不自动放行`() {
        val v = ExternalRegression(tempBaseline()).check(49, 49, listOf("local331"))
        assertFalse(v.ok)
        assertTrue(v.failures.single().contains("基线缺失"))
    }

    @Test
    fun `显式 updateBaseline 才写入初始基线`() {
        val file = tempBaseline()
        val v = ExternalRegression(file).check(49, 49, listOf("local331"), updateBaseline = true)
        assertTrue(v.ok)
        assertTrue(file.isFile)
        val written = ExternalRegression(file).readBaseline()
        assertEquals(49, written.passed)
        assertEquals(49, written.total)
        assertEquals(listOf("local331"), written.knownFailures)
        assertEquals(5.0, written.tolerancePct)
    }

    @Test
    fun `退化时不更新基线`() {
        val file = tempBaseline()
        ExternalRegression(file).writeBaseline(49, 49, emptyList())
        val before = file.readText()
        val v = ExternalRegression(file).check(46, 49, emptyList(), updateBaseline = true)
        assertFalse(v.ok)
        assertEquals(before, file.readText(), "退化时不得覆盖基线")
        assertTrue(v.warnings.any { it.contains("未更新") })
    }

    @Test
    fun `improvement 给 warning 不阻断`() {
        val v = reg(49, 49, baseline = listOf(48, 49))
        assertTrue(v.ok)
        assertNotNull(v.dropPct)
        assertTrue(v.warnings.any { it.contains("高于基线") })
    }
}
