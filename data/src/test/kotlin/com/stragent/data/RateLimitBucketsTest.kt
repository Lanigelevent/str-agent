package com.stragent.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-156（P2-13）· 按凭据分桶限流。
 *
 * 背景：全局单桶下一个合法客户端可耗尽全部配额，把其它用户挤成 429。
 * 分桶后各主体独立补充；桶数上界 256，超出并入共享溢出桶（内存卫生约束）。
 */
class RateLimitBucketsTest {

    @Test
    fun `两个主体互不挤占——A 打满不影响 B`() {
        val buckets = RateLimitBuckets(capacity = 3, refillRatePerSecond = 0.0001)
        // A 消耗自己桶的全部容量
        repeat(3) { assertTrue(buckets.tryAcquire("user-a"), "A 前三次应放行") }
        assertTrue(!buckets.tryAcquire("user-a"), "A 桶打满后拒绝")
        // B 有自己独立的桶
        assertTrue(buckets.tryAcquire("user-b"), "B 不受 A 耗尽影响")
        assertEquals(2, buckets.bucketCount())
    }

    @Test
    fun `桶数上界——超出并入共享溢出桶`() {
        val buckets = RateLimitBuckets(capacity = 10, refillRatePerSecond = 0.0001, maxBuckets = 2)
        assertTrue(buckets.tryAcquire("u1"))
        assertTrue(buckets.tryAcquire("u2"))
        assertEquals(2, buckets.bucketCount())
        // 第 3 个主体走溢出桶（不再新增桶）：容量 10 取满后拒绝
        repeat(10) { assertTrue(buckets.tryAcquire("u3"), "溢出桶前 10 次放行 #$it") }
        assertTrue(!buckets.tryAcquire("u3"), "溢出桶打满后拒绝")
        assertEquals(2, buckets.bucketCount(), "桶数不再增长")
        // 已建桶的主体仍走自己的桶
        assertTrue(buckets.tryAcquire("u1"), "u1 走自己的桶")
    }
}
