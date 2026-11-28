package com.stragent.core.observability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 头采样器离线单测（T-065）。
 *
 * 钉住的口径：
 *  1. `hash01` 已知答案与确定性（同一 traceId 决定不变）；
 *  2. 比率边界：0 全不采、1 全采、中间比率在同一 traceId 上稳定；
 *  3. parent-based：有上游时**只**看上游 sampled 标志，比率再小/再大都不改判；
 *  4. 环境解析：未配置/非法/越界 → null（按全采走），由装配方告警。
 */
class OtelSamplerTest {

    @Test
    fun `hash01 已知答案、边界与确定性`() {
        // 前 16 位 hex 全 0 → 0.0；全 f → 接近 1 但达不到
        assertEquals(0.0, OtelSampler.hash01("0".repeat(32)))
        val allF = OtelSampler.hash01("f".repeat(32))
        // 16^16-1 在 double 里恰好舍入成 2^64 → 除出 1.0：decide 用 `< ratio`，
        // 该 id 只在 ratio=1.0（短路全采）时被采——确定性不受影响
        assertTrue(allF > 0.999 && allF <= 1.0, "全 f 应接近 1：$allF")
        // 确定性：同一 id 两次一致
        val id = "4bf92f3577b34da6a3ce929d0e0e4736"
        assertEquals(OtelSampler.hash01(id), OtelSampler.hash01(id))
        // 区间合法
        repeat(50) {
            val v = OtelSampler.hash01(TraceIds.traceId())
            assertTrue(v >= 0.0 && v < 1.0, "hash01 应在 [0,1)：$v")
        }
    }

    @Test
    fun `比率判定——0 全不采、1 全采、同 id 决定不变`() {
        val zero = assertNotNull(OtelSampler.fromRatio(0.0))
        repeat(20) { i ->
            val id = TraceIds.traceId()
            assertFalse(zero.decide(id, hasParent = false, parentSampled = false), "ratio=0 应全不采：$id")
        }
        val all = assertNotNull(OtelSampler.fromRatio(1.0))
        repeat(20) {
            assertTrue(all.decide(TraceIds.traceId(), hasParent = false, parentSampled = false), "ratio=1 应全采")
        }
        val half = assertNotNull(OtelSampler.fromRatio(0.5))
        val id = "4bf92f3577b34da6a3ce929d0e0e4736"
        assertEquals(half.decide(id, false, false), half.decide(id, false, false), "同一 id 决定必须一致")
    }

    @Test
    fun `parent-based——有上游时只遵从上游标志`() {
        val zero = assertNotNull(OtelSampler.fromRatio(0.0))
        val all = assertNotNull(OtelSampler.fromRatio(1.0))
        val id = TraceIds.traceId()
        // 上游说不采样 → 比率 1.0 也不采；上游说采样 → 比率 0.0 也采
        assertFalse(all.decide(id, hasParent = true, parentSampled = false), "上游未采样：比率再大也不采")
        assertTrue(zero.decide(id, hasParent = true, parentSampled = true), "上游已采样：比率再小也采")
    }

    @Test
    fun `环境解析——未配置、非法、越界一律 null`() {
        assertNull(OtelSampler.fromEnvironment(emptyMap()))
        assertNull(OtelSampler.fromEnvironment(mapOf(OtelSampler.ENV to "abc")))
        assertNull(OtelSampler.fromEnvironment(mapOf(OtelSampler.ENV to "-0.5")))
        assertNull(OtelSampler.fromEnvironment(mapOf(OtelSampler.ENV to "1.5")))
        assertNull(OtelSampler.fromEnvironment(mapOf(OtelSampler.ENV to "  ")))
        assertNotNull(OtelSampler.fromEnvironment(mapOf(OtelSampler.ENV to "0.25")))
        assertNotNull(OtelSampler.fromEnvironment(mapOf(OtelSampler.ENV to "0")))
        assertNotNull(OtelSampler.fromEnvironment(mapOf(OtelSampler.ENV to "1.0")))
    }
}
