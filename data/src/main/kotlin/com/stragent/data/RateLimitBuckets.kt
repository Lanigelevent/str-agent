package com.stragent.data

import java.util.concurrent.ConcurrentHashMap

/**
 * T-156（P2-13）：按凭据分桶限流。
 *
 * 背景（D24 审查）：限流是**全局单桶**——一个合法客户端可以耗尽全部配额，
 * 把其它合法用户挤成 429。OAuth 模式按 JWT `sub` 分桶（多用户互不挤占）；
 * 静态 token 模式只有一个凭据，分桶与全局等价（诚实边界，见 McpAuthGate）。
 *
 * 桶数上界 256：超出后并入共享溢出桶。`sub` 是验证过的声明（伪造需要有效
 * 令牌），上界是内存卫生约束，不是安全边界。
 */
internal class RateLimitBuckets(
    capacity: Long,
    refillRatePerSecond: Double,
    private val maxBuckets: Int = 256,
) {
    private val buckets = ConcurrentHashMap<String, RateLimiter>()
    private val overflow = RateLimiter(capacity, refillRatePerSecond)
    private val capacity = capacity
    private val refillRatePerSecond = refillRatePerSecond
    private val lock = Any()

    fun tryAcquire(principal: String): Boolean =
        bucketFor(principal).tryAcquire()

    fun bucketCount(): Int = buckets.size

    private fun bucketFor(principal: String): RateLimiter {
        buckets[principal]?.let { return it }
        synchronized(lock) {
            buckets[principal]?.let { return it }
            if (buckets.size >= maxBuckets) return overflow
            return RateLimiter(capacity, refillRatePerSecond).also { buckets[principal] = it }
        }
    }
}
