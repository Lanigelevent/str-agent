package com.stragent.core.report

import com.stragent.core.ports.PrefsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 三库对齐测试：数量、id、分组、prompt 块结构 */
class ReportLibTest {
    class FakePrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
    }

    @Test
    fun `三库数量与顺序`() {
        assertEquals(8, ReportLib.reportTypes.size)
        assertEquals(8, ReportLib.reportStyles.size)
        assertEquals(47, ReportLib.industries.size)
        assertEquals("standard", ReportLib.reportTypes[0].id)
        assertEquals("professional", ReportLib.reportStyles[0].id)
        assertEquals("general", ReportLib.industries[0].id)
    }

    @Test
    fun `分组保持出现顺序（12 组）`() {
        val groups = ReportLib.groupedIndustries()
        assertEquals(12, groups.size)
        assertEquals("常用", groups[0].first)
        assertEquals(1, groups[0].second.size) // 常用只有 general
        assertEquals("金融财务", groups[1].first)
        assertEquals(3, groups[1].second.size) // finance/banking/securities
    }

    @Test
    fun `偏好读写走 PrefsStore`() {
        val prefs = FakePrefs()
        assertEquals("general", ReportLib.getIndustry(prefs).id)
        ReportLib.setIndustry(prefs, "ecommerce")
        assertEquals("ecommerce", ReportLib.getIndustry(prefs).id)
        assertEquals("standard", ReportLib.getReportType(prefs).id)
        ReportLib.setReportType(prefs, "anomaly")
        assertEquals("anomaly", ReportLib.getReportType(prefs).id)
        assertEquals("professional", ReportLib.getReportStyle(prefs).id)
        ReportLib.setReportStyle(prefs, "detective")
        assertEquals("detective", ReportLib.getReportStyle(prefs).id)
    }

    @Test
    fun `prompt 块结构（industry_framework 与 report_type 与 writing_style）`() {
        val ind = ReportLib.industries.find { it.id == "banking" }!!
        val block = ReportLib.industryPromptBlock(ind)
        assertTrue(block.startsWith("\n<industry_framework name=\"银行保险\">\n"))
        assertTrue(block.trimEnd().endsWith("</industry_framework>"))

        val type = ReportLib.reportTypes.find { it.id == "trend" }!!
        assertTrue(ReportLib.reportTypePromptBlock(type).contains("<report_type name=\"趋势研判\">"))

        val style = ReportLib.reportStyles.find { it.id == "concise" }!!
        val sb = ReportLib.stylePromptBlock(style)
        assertTrue(sb.contains("<writing_style name=\"简明扼要\">"))
        assertTrue(sb.contains("<length>全文 300 字以内——这是本文风的**默认倾向**"))
        assertTrue(sb.trimEnd().endsWith("</writing_style>"))
    }

    @Test
    fun `prompt 文案与 TS 源码逐字一致（抽 3 条）`() {
        // banking 的 must_answer 段首句（industries.ts:65）
        val banking = ReportLib.industries.find { it.id == "banking" }!!
        assertTrue(banking.prompt.contains("① 规模：存贷款 / 保费 / 保额的变化；② 质量：不良率、逾期率、理赔率、退保率等风险指标"))
        // anomaly 的 counter_example（report-types.ts:122）
        val anomaly = ReportLib.reportTypes.find { it.id == "anomaly" }!!
        assertTrue(anomaly.prompt.contains("最严重的异常：第 N 行 Sales = N，超出箱须上界（N）N 个 IQR"))
        // detective 的写作纪律（report-styles.ts:111）
        val detective = ReportLib.reportStyles.find { it.id == "detective" }!!
        assertTrue(detective.prompt.contains("从最可疑的异常切入——不要按部就班讲概览，先把最扎眼的数字拎出来"))
    }
}
