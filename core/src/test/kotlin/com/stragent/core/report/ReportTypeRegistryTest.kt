package com.stragent.core.report

import com.stragent.core.brief.buildDatasetBrief
import com.stragent.core.brief.planBriefBudget
import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset
import com.stragent.core.ports.PrefsStore
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-058 · 报告类型形态登记表（D17 G-19）。
 *
 * 证明：类型的四种行为（结构化判定 / 篇幅系数 / brief 预算 / 章节模板）都由
 * [ReportTypeRegistry] 回答——**新增类型只登记 profile，不改四个分发函数**；
 * 未登记类型走保守默认，不会被误判成短体型。
 */
class ReportTypeRegistryTest {

    private class FakePrefs : PrefsStore {
        private val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
    }

    private fun dataset(): Dataset {
        val text = File("../fixtures/sample/superstore.csv").readText()
        val parsed = Csv.parseDelimited(text)
        val limited = Csv.limitRows(parsed)
        val types = Csv.inferTypes(limited.rows, limited.columns.size)
        return Dataset(
            name = "superstore.csv", importedAt = 1,
            columns = limited.columns, rows = limited.rows, columnTypes = types,
            delimiter = limited.delimiter, truncated = limited.truncated, sourceRows = limited.totalRows,
        )
    }

    private fun typeIdOf(label: String): String = ReportLib.reportTypes.first { it.label.contains(label) }.id


    @Test
    fun `内置 8 类型——参数与旧硬编码逐条对齐`() {
        assertEquals(8, ReportLib.reportTypes.size)
        assertTrue(isStructuredReportType("standard"))
        assertFalse(isStructuredReportType("executive"), "一页纸不注入结构块")
        assertFalse(isStructuredReportType("bulletin"), "数据快讯不注入结构块")
        assertEquals(1.5, ReportTypeRegistry.BUILT_IN.profileOf("deepdive").lengthFactor)
        assertEquals(0.75, ReportTypeRegistry.BUILT_IN.profileOf("executive").briefFactor)
        // 8 个库内类型都有登记（防漏登记悄悄落默认）
        for (type in ReportLib.reportTypes) {
            assertTrue(
                type.id in ReportTypeRegistry.BUILT_IN.entries.map { it.id },
                "库内类型 ${type.id} 未登记形态参数",
            )
        }
    }

    @Test
    fun `新增类型只登记——四处行为同时生效，不改分发函数`() {
        val custom = ReportTypeRegistry.BUILT_IN.with(
            ReportTypeProfile(
                id = "forecast",
                structured = true,
                lengthFactor = 1.7,
                briefFactor = 1.4,
                templateKey = "trend",
            ),
        )
        // ① 结构化判定
        assertTrue(isStructuredReportType("forecast", custom))
        // ② 篇幅系数（同参数下 forecast : standard = 1.7 : 1.1）
        val standard = targetLengthFor("", 100, 5, 2, 1, "800 字", "standard")
        val forecast = targetLengthFor("", 100, 5, 2, 1, "800 字", "forecast", custom)
        val ratio = forecast.chars.toDouble() / standard.chars
        assertTrue(abs(ratio - 1.7 / 1.1) < 0.02, "篇幅比 $ratio 应接近 1.55")
        // ③ brief 预算系数
        val data = dataset()
        val briefStandard = planBriefBudget(data, "retail", "standard")
        val briefForecast = planBriefBudget(data, "retail", "forecast", custom)
        assertTrue(briefForecast.total > briefStandard.total, "brief ${briefForecast.total} > ${briefStandard.total}")
        // ④ 章节模板（trend 模板首章是「整体走向」，standard 是「总体概览」）
        val brief = buildDatasetBrief(data)
        val forecastPlan = planChapters(brief, 40000, "forecast", custom)
        val standardPlan = planChapters(brief, 40000, "standard")
        assertEquals("整体走向", forecastPlan.chapters.first().title)
        assertEquals("总体概览", standardPlan.chapters.first().title)
    }

    @Test
    fun `未登记类型走保守默认——结构化、1_1 系数、standard 模板`() {
        val profile = ReportTypeRegistry.BUILT_IN.profileOf("never-registered")
        assertTrue(profile.structured)
        assertEquals(1.1, profile.lengthFactor)
        assertEquals(1.0, profile.briefFactor)
        assertEquals("standard", profile.templateKey)
        assertTrue(isStructuredReportType("never-registered"))
        // 短体型类型不受默认影响
        val short = targetLengthFor("", 100, 5, 2, 1, "800 字", "executive")
        assertEquals(800, short.chars)
    }

    @Test
    fun `无分组维度时的列级解读特例随 profile 走`() {
        // 只有一列、没有可分组维度：standard 的 {dim} 章应换成「列级指标解读」
        val text = File("../fixtures/sample/single_col.csv").readText()
        val parsed = Csv.parseDelimited(text)
        val limited = Csv.limitRows(parsed)
        val types = Csv.inferTypes(limited.rows, limited.columns.size)
        val data = Dataset(
            name = "single_col.csv", importedAt = 1,
            columns = limited.columns, rows = limited.rows, columnTypes = types,
            delimiter = limited.delimiter, truncated = limited.truncated, sourceRows = limited.totalRows,
        )
        val brief = buildDatasetBrief(data)
        val standard = planChapters(brief, 20000, "standard")
        assertTrue(standard.chapters.any { it.title == "列级指标解读" }, standard.chapters.map { it.title }.toString())
        val deepdive = planChapters(brief, 20000, "deepdive")
        assertTrue(deepdive.chapters.none { it.title == "列级指标解读" }, "deepdive 不做该特例")
    }
}
