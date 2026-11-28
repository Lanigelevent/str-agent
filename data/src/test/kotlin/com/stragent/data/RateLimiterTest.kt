package com.stragent.data

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals

/**
 * RateLimiter 令牌桶测试（D15 T4）。
 */
class RateLimiterTest {

    @Test
    fun `桶满时放行`() {
        val limiter = RateLimiter(capacity = 5, refillRatePerSecond = 1.0)
        repeat(5) { assertTrue(limiter.tryAcquire(), "前 5 次应放行") }
    }

    @Test
    fun `桶空时拒绝`() {
        val limiter = RateLimiter(capacity = 3, refillRatePerSecond = 0.0)
        repeat(3) { limiter.tryAcquire() }
        assertFalse(limiter.tryAcquire(), "第 4 次应被拒绝")
    }

    @Test
    fun `时间窗口后补充令牌`() {
        val limiter = RateLimiter(capacity = 2, refillRatePerSecond = 10.0)
        repeat(2) { limiter.tryAcquire() }
        assertFalse(limiter.tryAcquire(), "桶空时应拒绝")
        // 等待补充（100ms 应补充约 1 个令牌）
        Thread.sleep(200)
        assertTrue(limiter.tryAcquire(), "200ms 后应补充至少 1 个令牌")
    }

    @Test
    fun `补充封顶在容量`() {
        val limiter = RateLimiter(capacity = 3, refillRatePerSecond = 1000.0)
        // 消耗所有令牌
        repeat(3) { limiter.tryAcquire() }
        // 等待长时间"补充"（100ms × 1000/s = 100 tokens ≫ 容量 3 → 应恰好封顶）
        Thread.sleep(100)
        // 既是上界也是下界：refill 缺失（恒 0）时旧断言 ≤3 也会绿——收紧为恰好封顶
        assertEquals(3L, limiter.availableTokens(), "100ms 后应恰好补充并封顶在容量 3")
    }

    @Test
    fun `unlimited 总是放行`() {
        val limiter = RateLimiter.unlimited()
        repeat(100) { assertTrue(limiter.tryAcquire(), "unlimited 应总是放行") }
    }

    @Test
    fun `availableTokens 初始为容量`() {
        val limiter = RateLimiter(capacity = 10, refillRatePerSecond = 1.0)
        assertEquals(10, limiter.availableTokens())
    }

    @Test
    fun `并发场景下不超发`() {
        val limiter = RateLimiter(capacity = 10, refillRatePerSecond = 0.0)
        val success = java.util.concurrent.atomic.AtomicInteger(0)
        val threads = (1..20).map {
            Thread {
                if (limiter.tryAcquire()) success.incrementAndGet()
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue(success.get() <= 10, "不应超过容量 10，实际 ${success.get()}")
        assertTrue(success.get() >= 1, "至少有 1 个成功")
    }
}
