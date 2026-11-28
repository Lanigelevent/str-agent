package com.stragent.bench

import com.stragent.bench.suites.compareConcurrency
import com.stragent.bench.suites.ConcurrencyMetrics
import com.stragent.bench.suites.loadConcurrencyBaseline
import com.stragent.bench.suites.nearestRank
import com.stragent.bench.suites.saveConcurrencyBaseline
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-075 · 并发压测基线的门禁内核单测（负控纪律：每个新门禁配一次故意造 FAIL）。
 *
 * 不跑真实 HTTP——门禁是纯函数（compareConcurrency / nearestRank / 基线 IO），
 * 单测钉三件事：
 *  ①宽倍数判定正确（>3× FAIL、>2× WARN、QPS 反向）；
 *  ②**负控**：把指标放大到阈值外，门禁必须红（套件 gatePassed=false）；
 *  ③基线 IO 往返无损 + 缺键不对比（不伪造通过）。
 */
class ConcurrencyGateTest {

    private fun metrics(
        level: Int = 4,
        fastP95: Double = 10.0,
        longP95: Double = 45.0,
        qps: Double = 100.0,
    ) = ConcurrencyMetrics(
        level = level,
        fastP50 = fastP95 / 2,
        fastP95 = fastP95,
        longP50 = longP95 / 2,
        longP95 = longP95,
        errorRate = 0.0,
        qps = qps,
        total = 30,
        errors = 0,
    )

    @Test
    fun `宽倍数判定——超3倍红超2倍黄QPS反向`() {
        val baseline = mapOf("c4.fastP95" to 10.0, "c4.longP95" to 45.0, "c4.qps" to 100.0)

        // 正常：全绿
        val (f0, w0) = compareConcurrency(metrics(), baseline)
        assertTrue(f0.isEmpty() && w0.isEmpty(), "正常指标不该报：$f0 / $w0")

        // 3.5×：FAIL
        val (f1, w1) = compareConcurrency(metrics(fastP95 = 35.0), baseline)
        assertTrue(f1.any { it.contains("fastP95") }, "3.5× 必须红：$f1")
        assertTrue(w1.isEmpty(), "红就不该同时黄：$w1")

        // 2.5×：WARN
        val (f2, w2) = compareConcurrency(metrics(fastP95 = 25.0), baseline)
        assertTrue(f2.isEmpty(), "2.5× 不该红：$f2")
        assertTrue(w2.any { it.contains("fastP95") }, "2.5× 必须黄：$w2")

        // QPS 反向：掉到 1/3 以下红（30 < 100/3≈33.3）；1/3~1/2 之间黄（45 < 50）
        val (f3, _) = compareConcurrency(metrics(qps = 30.0), baseline)
        assertTrue(f3.any { it.contains("qps") }, "QPS 掉到 1/3 以下必须红：$f3")
        val (f4, w4) = compareConcurrency(metrics(qps = 45.0), baseline)
        assertTrue(f4.isEmpty() && w4.any { it.contains("qps") }, "QPS 掉到 1/3~1/2 必须黄：$f4 / $w4")
    }

    @Test
    fun `负控——指标爆表时门禁必须拦`() {
        // 纯负控构造：不再依赖真实套件——放大到硬阈值外，compare 判红即门禁红
        val (f, w) = compareConcurrency(
            metrics(fastP95 = 10_000.0), // 硬阈值 2000 的 5 倍，更是基线 10ms 的 1000 倍
            mapOf("c4.fastP95" to 10.0),
        )
        assertTrue(f.isNotEmpty(), "爆表必须红（套件 gatePassed=false → bench 退出 1）")
        assertTrue(w.isEmpty())
    }

    @Test
    fun `基线缺键不对比——绝不伪造通过`() {
        // 基线只有 fastP95：longP95 / qps 不对比，也不报错
        val (f, w) = compareConcurrency(metrics(longP95 = 99_999.0, qps = 0.01), mapOf("c4.fastP95" to 10.0))
        assertTrue(f.isEmpty() && w.isEmpty(), "缺键不对比：$f / $w")
    }

    @Test
    fun `基线IO往返无损`() {
        val file = File(createTempDirectory("conc-baseline").toFile(), "baseline.json")
        val original = mapOf(
            "c4.fastP50" to 5.0, "c4.fastP95" to 12.0, "c4.qps" to 210.5,
            "c64.longP95" to 45.0,
        )
        saveConcurrencyBaseline(file, original)
        val loaded = loadConcurrencyBaseline(file)
        assertTrue(loaded != null, "写出的基线必须能读回")
        assertEquals(original, loaded, "往返必须无损")
        assertTrue(loadConcurrencyBaseline(File("不存在的路径")) == null, "缺失/坏文件返回 null（首跑写入路径）")
    }

    @Test
    fun `最近邻秩分位——上档口径`() {
        val data = listOf(1L, 2L, 3L, 4L)
        assertEquals(2L, nearestRank(data, 0.5), "P50: ceil(0.5×4)=2 → 第 2 项")
        assertEquals(4L, nearestRank(data, 0.95), "P95: ceil(0.95×4)=4 → 第 4 项（上档）")
        assertEquals(0L, nearestRank(emptyList(), 0.95), "空表返回 0")
    }
}
