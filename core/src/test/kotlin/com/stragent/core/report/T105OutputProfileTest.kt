package com.stragent.core.report

import com.stragent.core.ports.PrefsStore
import com.stragent.core.ports.PrefsOverlay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T-105 输出适配层单测：PrefsOverlay 语义 + ReportLib 扩展（depth/audience/requirements/权重/块） */
class T105OutputProfileTest {

    class FakePrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) {
            map[key] = value
        }
    }

    @Test
    fun `PrefsOverlay 覆盖优先回退 base`() {
        val base = FakePrefs().apply { set("analysis.reportType", "anomaly") }
        val ov = PrefsOverlay(base, mapOf("analysis.reportType" to "executive"))
        assertEquals("executive", ov.get("analysis.reportType", ""))
        assertEquals("anomaly", base.get("analysis.reportType", "")) // base 不被写穿
        assertEquals("default", ov.get("missing.key", "default")) // 未覆盖键回退 base/fallback
        // set/remove 透传 base
        ov.set("k", "v")
        assertEquals("v", base.get("k", ""))
    }

    @Test
    fun `depth 三档系数与块组粒度`() {
        assertEquals(0.5, ReportLib.depthFactor("brief"))
        assertEquals(1.75, ReportLib.depthFactor("deep"))
        assertEquals(1.0, ReportLib.depthFactor("standard"))
        assertEquals(1.0, ReportLib.depthFactor("unknown"))
        assertEquals(10, ReportLib.depthGroupSize("brief"))
        assertEquals(4, ReportLib.depthGroupSize("deep"))
        assertEquals(6, ReportLib.depthGroupSize("standard"))
    }

    @Test
    fun `getDepth 非法值回退 standard`() {
        val prefs = FakePrefs()
        assertEquals("standard", ReportLib.getDepth(prefs))
        prefs.set("analysis.depth", "deep")
        assertEquals("deep", ReportLib.getDepth(prefs))
        prefs.set("analysis.depth", "huge")
        assertEquals("standard", ReportLib.getDepth(prefs))
    }

    @Test
    fun `骨架权重表 standard 与既有硬编码一致且各档全正`() {
        val std = textChapterWeights("standard")
        assertEquals(0.25, std.overview)
        assertEquals(0.40, std.points)
        assertEquals(0.20, std.facts)
        assertEquals(0.20, std.conclusion)
        for (t in listOf("executive", "deepdive", "anomaly", "trend", "quality", "standard", "bulletin")) {
            val w = textChapterWeights(t)
            assertTrue(w.overview > 0 && w.points > 0 && w.facts > 0 && w.conclusion > 0, "$t 权重全正")
        }
    }

    @Test
    fun `persona 与 requirements 块逐字在场且优先级声明完整`() {
        val pb = ReportLib.outputPersonaBlock("施工方项目经理，非监测专业")
        assertTrue(pb.contains("<output_persona>"))
        assertTrue(pb.contains("施工方项目经理，非监测专业"))
        assertTrue(pb.contains("受众不改变事实与数字纪律"))
        val rb = ReportLib.outputRequirementsBlock("只写风险与遗留事项")
        assertTrue(rb.contains("<output_requirements>"))
        assertTrue(rb.contains("只写风险与遗留事项"))
        assertTrue(rb.contains("硬约束 > 本块 > 报告类型 > 行业框架 > 报告风格"))
        assertTrue(rb.contains("不得突破零编造、段锚与数字白名单硬约束"))
    }
}
