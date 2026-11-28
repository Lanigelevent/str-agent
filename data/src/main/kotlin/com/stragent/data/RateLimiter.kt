package com.stragent.data

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 令牌桶限流器（D15 T4）。
 *
 * 纯 JDK 实现，零依赖。算法：
 *  - 桶容量 capacity：最多存储 capacity 个令牌
 *  - 补充速率 refillRate：每秒补充 refillRate 个令牌
 *  - tryAcquire()：尝试取 1 个令牌，成功 true / 失败 false
 *  - 时间驱动补充：基于上次补充时间戳计算应补充的令牌数
 *
 * 诚实边界：
 *  - 不是分布式限流（仅单进程内）
 *  - 无滑窗/漏桶的精确性（令牌桶是近似算法）
 *  - 线程安全用 AtomicLong + CAS（无锁，但有竞争）
 *
 * 用于 McpBridge：默认 capacity=10, refillRate=1/s
 * 即：瞬时并发 10 个请求可放行，之后每秒补充 1 个名额。
 */
open class RateLimiter(
    private val capacity: Long = 10,
    private val refillRatePerSecond: Double = 1.0,
) {
    private val tokens = AtomicLong(capacity)
    private val lastRefillNanos = AtomicReference(System.nanoTime())

    /**
     * 尝试获取 1 个令牌。
     * @return true=放行, false=被限流
     */
    open fun tryAcquire(): Boolean {
        refill()
        while (true) {
            val current = tokens.get()
            if (current <= 0) return false
            if (tokens.compareAndSet(current, current - 1)) return true
        }
    }

    /**
     * 获取当前可用令牌数（近似值，多线程下可能略有偏差）。
     */
    open fun availableTokens(): Long {
        refill()
        return tokens.get().coerceAtLeast(0)
    }

    /**
     * 基于时间戳补充令牌。
     * 计算从上次补充到现在的秒数，乘以补充速率，加到桶中（不超过容量）。
     */
    private fun refill() {
        val now = System.nanoTime()
        val last = lastRefillNanos.get()
        val elapsedNanos = now - last
        if (elapsedNanos <= 0) return  // 时钟回拨，跳过

        val elapsedSeconds = elapsedNanos / 1_000_000_000.0
        val tokensToAdd = (elapsedSeconds * refillRatePerSecond).toLong()
        if (tokensToAdd <= 0) return

        // CAS 更新上次补充时间
        if (lastRefillNanos.compareAndSet(last, now)) {
            // 补充令牌，不超过容量
            while (true) {
                val current = tokens.get()
                val newTokens = (current + tokensToAdd).coerceAtMost(capacity)
                if (tokens.compareAndSet(current, newTokens)) break
            }
        }
    }

    companion object {
        /**
         * 创建一个不限流的空实现（rate limiter = null 时的占位）。
         * 实际上直接传 null 更好，但某些接线需要一个"总是放行"的对象。
         */
        fun unlimited(): RateLimiter = object : RateLimiter(capacity = 1, refillRatePerSecond = 1.0) {
            override fun tryAcquire(): Boolean = true
            override fun availableTokens(): Long = Long.MAX_VALUE
        }
    }
}
