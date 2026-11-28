package com.stragent.core.report

import com.stragent.core.ai.AgentFindingCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ReviewerAgentTest {

    private fun findings(vararg titles: String): List<AgentFindingCard> =
        titles.map { AgentFindingCard(it, "", "") }

    @Test
    fun uncovered_finding_detected() {
        val draft = "本季销售领500万元，增长10%。"
        val fs = findings("华东区域客户留存率分析")
        val allowed = setOf(500.0, 10.0)
        val result = reviewReport(draft, fs, allowed)
        assertEquals(1, result.uncoveredFindings.size)
        assertTrue(result.uncoveredFindings.contains("华东区域客户留存率分析"))
    }

    @Test
    fun covered_finding_not_flagged() {
        val draft = "华东区域客户留存率达到85%。"
        val fs = findings("华东区域客户留存率")
        val allowed = setOf(85.0)
        val result = reviewReport(draft, fs, allowed)
        assertEquals(0, result.uncoveredFindings.size)
    }

    @Test
    fun contradiction_same_subject_different_values_detected() {
        val draft = "销售额增长20%。同时，销售额增长15%。"
        val result = reviewReport(draft, emptyList(), emptySet())
        assertTrue(result.contradictions.isNotEmpty())
        assertTrue(result.hard)
    }

    @Test
    fun no_contradiction_different_subjects() {
        val draft = "销售额增长20%。利润增长15%。"
        val result = reviewReport(draft, emptyList(), emptySet())
        assertFalse(result.hard)
    }

    @Test
    fun unsupported_conclusion_number_not_in_allowed() {
        val draft = "因此，可见市场份额达到45%。"
        val allowed = setOf(30.0, 20.0) // 45 not in allowed
        val result = reviewReport(draft, emptyList(), allowed)
        assertTrue(result.unsupportedConclusions.isNotEmpty())
    }

    @Test
    fun supported_conclusion_not_flagged() {
        val draft = "因此，可见市场份额达到30%。"
        val allowed = setOf(30.0)
        val result = reviewReport(draft, emptyList(), allowed)
        assertEquals(0, result.unsupportedConclusions.size)
    }

    @Test
    fun empty_allowed_numbers_no_conclusion_check() {
        val draft = "因此，可见市场份额达到999%。"
        val result = reviewReport(draft, emptyList(), emptySet())
        assertEquals(0, result.unsupportedConclusions.size)
    }

    @Test
    fun no_findings_no_uncovered() {
        val draft = "报告正文。"
        val result = reviewReport(draft, emptyList(), emptySet())
        assertEquals(0, result.uncoveredFindings.size)
        assertFalse(result.hard)
    }

    @Test
    fun hard_only_when_contradictions() {
        val draft1 = "销售增长20%"
        val draft2 = "销售增长20%。销售增长15%。"
        val r1 = reviewReport(draft1, emptyList(), emptySet())
        val r2 = reviewReport(draft2, emptyList(), emptySet())
        assertFalse(r1.hard)
        assertTrue(r2.hard)
    }

    @Test
    fun issues_list_aggregates_all_kinds() {
        val draft = "销售增长20%。销售增长15%。因此，可见份额达999%。"
        val fs = findings("客户留存率")
        val allowed = setOf(20.0, 15.0)
        val result = reviewReport(draft, fs, allowed)
        assertTrue(result.issues.any { it.kind == "contradiction" })
        assertTrue(result.issues.any { it.kind == "uncovered" })
        assertTrue(result.issues.any { it.kind == "unsupported_conclusion" })
    }

    @Test
    fun buildAllowedNumbers_from_brief() {
        val brief = "销售500元，增长20%"
        val allowed = buildAllowedNumbers(brief)
        // extractNumbers handles scale factors: "500元" -> 500.0, "20%" -> 20.0
        // Just verify the set is not empty
        assertTrue(allowed.isNotEmpty(), "allowed set should not be empty for brief: $brief")
    }

    @Test
    fun ratio_contradiction_detected() {
        val draft = "华东占比30%。华东占比40%。"
        val result = reviewReport(draft, emptyList(), emptySet())
        assertTrue(result.hard)
    }

}
