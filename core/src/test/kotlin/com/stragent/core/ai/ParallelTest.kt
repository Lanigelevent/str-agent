package com.stragent.core.ai

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * T-129 有界扇出测试：保序（与串行逐字节一致）、并发上限生效、双失败模式、取消传播。
 */
class ParallelTest {

    @Test
    fun `保序——乱序完成仍按输入序归并`() {
        // 前面的任务睡得久（乱序完成），结果必须仍按输入序
        val out = Parallel.mapLimited(listOf(120, 40, 80, 10)) { sleepMs ->
            Thread.sleep(sleepMs.toLong())
            "r$sleepMs"
        }
        assertEquals(listOf("r120", "r40", "r80", "r10"), out, "保序归并")
    }

    @Test
    fun `并发上限——同时进行中任务数不超限`() {
        val inFlight = AtomicInteger(0)
        val maxSeen = AtomicInteger(0)
        val out = Parallel.mapLimited((1..12).toList(), concurrency = 3) { i ->
            val now = inFlight.incrementAndGet()
            maxSeen.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            Thread.sleep(30)
            inFlight.decrementAndGet()
            i
        }
        assertEquals((1..12).toList(), out)
        assertTrue(maxSeen.get() <= 3, "进行中峰值 ≤ 并发上限（实际 ${maxSeen.get()}）")
    }

    @Test
    fun `collecting——逐块失败不互相影响`() {
        val out = Parallel.mapLimitedCollecting(listOf("a", "b", "c")) { s ->
            if (s == "b") throw IllegalStateException("块 b 失败")
            "$s-完成"
        }
        assertEquals("a-完成", out[0].getOrNull())
        assertTrue(out[1].isFailure)
        assertEquals("c-完成", out[2].getOrNull())
    }

    @Test
    fun `failFast——首个异常原样上抛`() {
        assertFailsWith<IllegalStateException> {
            Parallel.mapLimited(listOf(1, 2, 3)) { i ->
                if (i == 2) throw IllegalStateException("第 2 个失败")
                i
            }
        }
    }

    /**
     * failFast 的**实质**：首个失败后**剩余任务不再执行**（T-144）。
     *
     * 修复前的实现复用「收集式」——收集式的契约是「每个任务都被尝试」，所以它必须
     * 等齐全部任务才返回：首个失败**不会**取消剩余，且异常要等所有任务跑完才抛。
     * 实测（修复前）：5 个任务里第 1 个立刻失败、其余 4 个各睡 200ms →
     * `executed` 计数是 **5**（全部执行）。修复后应 ≤ 3（并发上限 2，最多 2 个在跑，
     * 失败时另一个可能刚启动；其余排队中的**不会**启动）。
     *
     * 这条断言是 failFast 的**可执行契约**——否则「failFast」只是个名字。
     */
    @Test
    fun `failFast——首个失败后剩余任务不再执行`() {
        val executed = AtomicInteger(0)
        assertFailsWith<IllegalStateException> {
            Parallel.mapLimited(listOf(1, 2, 3, 4, 5), concurrency = 2) { i ->
                executed.incrementAndGet()
                if (i == 1) throw IllegalStateException("第 1 个失败")
                Thread.sleep(300)
                i
            }
        }
        assertTrue(
            executed.get() <= 3,
            "首个失败后剩余任务不该继续执行（并发上限 2，实得 executed=${executed.get()}）",
        )
    }

    /**
     * failFast 要**早**：首个失败后不该被最慢的任务拖住。
     *
     * 修复前的实现要等所有任务跑完才抛——5 个任务各睡 300ms ⇒ 至少 300ms；
     * 修复后首个失败即刻返回（其余被取消），耗时应远小于最慢任务。
     */
    @Test
    fun `failFast——首个失败后不等最慢的任务`() {
        val start = System.nanoTime()
        assertFailsWith<IllegalStateException> {
            Parallel.mapLimited(listOf(1, 2, 3, 4, 5), concurrency = 2) { i ->
                if (i == 1) throw IllegalStateException("第 1 个失败")
                Thread.sleep(600)
                i
            }
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(
            elapsedMs < 500,
            "首个失败后不该等最慢的任务（实得 ${elapsedMs}ms，最慢任务 600ms）",
        )
    }

    @Test
    fun `取消——任务执行前自查并传播`() {
        assertFailsWith<GenerationCancelled> {
            Parallel.mapLimited(listOf(1, 2), cancelSignal = { true }) { it }
        }
    }

    @Test
    fun `串行退化——并发 1 结果一致`() {
        val out = Parallel.mapLimited(listOf(1, 2, 3), concurrency = 1) { it * 2 }
        assertEquals(listOf(2, 4, 6), out)
    }
}
