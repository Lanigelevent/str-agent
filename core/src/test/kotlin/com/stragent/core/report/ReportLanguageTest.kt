package com.stragent.core.report

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * T-173 · 报告语言档位测试。
 *
 * 覆盖：语言解析（缺省中文、未知不静默换语种）/ 提示词注入对应语言的纪律与结构规范 /
 * 写作审计按语言取词表（英文报告里的英文套话能被抓到、中文套话在英文档**不**误伤）/
 * 结构块标记按语言 / 英文词表与英文提示词逐条对得上（drift-guard）。
 */
class ReportLanguageTest {

    @Test
    fun `语言解析——缺省与未知都落中文，不静默换语种`() {
        assertEquals(ReportLanguage.ZH, ReportLanguage.from(null))
        assertEquals(ReportLanguage.ZH, ReportLanguage.from(""))
        assertEquals(ReportLanguage.ZH, ReportLanguage.from("fr"))
        assertEquals(ReportLanguage.EN, ReportLanguage.from("en"))
        assertEquals(ReportLanguage.EN, ReportLanguage.from("EN-US"))
    }

    @Test
    fun `提示词——按语言注入对应的纪律块与结构规范`() {
        val zh = com.stragent.core.ai.writingDisciplineBlocks(MapPrefs(mapOf(ReportLanguage.PREF_KEY to "zh")))
        val en = com.stragent.core.ai.writingDisciplineBlocks(MapPrefs(mapOf(ReportLanguage.PREF_KEY to "en")))
        assertTrue(zh.contains("全文用**中文**写作"), "中文档的语言指令：")
        assertTrue(en.contains("entire report in English"), "英文档的语言指令：")
        assertTrue(en.contains("To sum up"), "英文档应注入英文套话词表：")
        assertTrue(en.contains("Full-period value"), "英文档应注入英文结构规范：")
        assertTrue(!en.contains("综上所述"), "英文档不应混入中文套话词表：")
    }

    @Test
    fun `写作审计——英文套话在英文档被抓，中文套话在英文档不误伤`() {
        val enAudit = WritingAudit.audit(
            "To sum up, the trend is up.\nIt is worth noting that region A leads.",
            targetChars = null,
            structured = false,
            language = ReportLanguage.EN,
        )
        assertEquals(2, enAudit.bannedHits, "英文套话应被抓：${enAudit.issues}")

        val zhText = "综上所述，表现良好。"
        val enNoHit = WritingAudit.audit(zhText, null, structured = false, language = ReportLanguage.EN)
        assertEquals(0, enNoHit.bannedHits, "英文档不该按中文词表判：${enNoHit.issues}")
        val zhHit = WritingAudit.audit(zhText, null, structured = false, language = ReportLanguage.ZH)
        assertEquals(1, zhHit.bannedHits, "中文档照旧：${zhHit.issues}")
    }

    @Test
    fun `结构块标记——按语言查，英文报告用英文标记`() {
        val markers = structureMarkers(ReportLanguage.EN)
        val report = buildString {
            append("| Metric | Full-period value | Peak location | Change |\n")
            append("| Sales | 1000 | row 812 | +20% |\n\n")
            append("## Scope\n\nBased on 100 orders.\n\n")
            append("## Questions the data cannot answer\n\n- Why?\n\n")
            append("Sales rose 20% (Source: full-set statistic).\n")
        }
        val ok = WritingAudit.audit(report, null, structured = true, language = ReportLanguage.EN)
        assertEquals(0, ok.issues.count { it.kind == "缺结构块" }, "英文结构块应齐：${ok.issues}")

        // 同一份中文报告在英文档应报「缺结构块」（标记语言必须跟着报告走）
        val zhReport = report.replace("Full-period value", "全期值").replace("Peak location", "最值定位")
            .replace("Scope", "适用范围").replace("Questions the data cannot answer", "数据未能回答的问题")
            .replace("Source:", "依据：")
        val mismatch = WritingAudit.audit(zhReport, null, structured = true, language = ReportLanguage.EN)
        assertTrue(mismatch.issues.any { it.kind == "缺结构块" }, "语言不匹配应报缺结构块：${mismatch.issues}")
    }

    @Test
    fun `drift-guard——英文黑名单每条都能在英文提示词里找到锚点`() {
        val prompt = humanWritingBlock(ReportLanguage.EN)
        val missing = BANNED_WRITING_RULES_EN.filter { !prompt.contains(it.promptAnchor) }
        assertEquals(emptyList(), missing, "英文黑名单与英文提示词对不上：$missing")
    }

    /** 最小 prefs（语言档位用） */
    private class MapPrefs(private val values: Map<String, String>) : com.stragent.core.ports.PrefsStore {
        override fun get(key: String, fallback: String): String = values[key] ?: fallback
        override fun set(key: String, value: String) = Unit
        override fun remove(key: String) = Unit
    }
}
