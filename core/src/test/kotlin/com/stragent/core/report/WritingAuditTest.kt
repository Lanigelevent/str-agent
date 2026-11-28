package com.stragent.core.report

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WritingAudit（确定性写作审计）单测：套话召回、结构块覆盖、篇幅区间、硬闸阈值，
 * 以及**drift-guard**——审计黑名单与 humanWritingBlock() 提示词逐条对得上。
 */
class WritingAuditTest {

    @Test
    fun `套话——各类样例命中且计数正确`() {
        val r = WritingAudit.audit("综上所述，销售额上行。\n值得注意的是，华东最高。\n", null, structured = false)
        assertEquals(2, r.bannedHits)
        assertTrue(r.issues.all { it.kind == "套话" })
    }

    @Test
    fun `硬闸——2 处放行、3 处触发退稿级`() {
        val two = WritingAudit.audit("综上所述，好。\n总而言之，稳。", null, structured = false)
        assertFalse(two.hard, "2 处不该触发退稿级")
        val three = WritingAudit.audit("综上所述，好。\n总而言之，稳。\n值得注意的是，高。", null, structured = false)
        assertTrue(three.hard, "3 处应触发退稿级")
    }

    @Test
    fun `正则类套话——随着发展与翻译腔可召回`() {
        val r = WritingAudit.audit("随着市场的不断发展，我们对数据进行了分析。", null, structured = false)
        assertTrue(r.bannedHits >= 2, "应命中 随着…发展 与 进行了…分析：${r.issues}")
    }

    @Test
    fun `结构块——结构化类型缺块必报，短体型不查`() {
        val bare = "## 概览\n销售 100 元。\n"
        val structured = WritingAudit.audit(bare, null, structured = true)
        assertEquals(4, structured.issues.count { it.kind == "缺结构块" }, "速览表/适用范围/未答问题/依据标注 各缺一条")
        val unstructured = WritingAudit.audit(bare, null, structured = false)
        assertTrue(unstructured.issues.isEmpty(), "短体型不查结构块：${unstructured.issues}")
    }

    @Test
    fun `篇幅——双向偏离报、区间内不报、无目标不查`() {
        assertTrue(WritingAudit.audit("短。", 1000, structured = false).issues.any { it.kind == "篇幅" })
        val long = WritingAudit.audit("这句没有信息量。".repeat(80), 300, structured = false)
        assertTrue(long.issues.any { it.kind == "篇幅" })
        assertTrue(WritingAudit.audit("x".repeat(700), 1000, structured = false).issues.none { it.kind == "篇幅" })
        assertTrue(WritingAudit.audit("短。", null, structured = false).issues.none { it.kind == "篇幅" }, "无目标不查篇幅")
    }

    @Test
    fun `合规样本——四段齐全零注记`() {
        val clean = """
            ## 总体概览
            Sales 合计 2,297,201.08 元（依据：Sales 列全量统计）。

            | 指标 | 全期值 | 最值定位 | 变化或占比 |
            |---|---|---|---|
            | Sales | 2,297,201.08 | Consumer | (1,161,401.52 ÷ 2,297,201.08) = +50.6% |

            ## 适用范围
            全部 9,929 条订单；未剔除极值。

            ## 数据未能回答的问题
            - 没有成本字段，算不了利润率。
        """.trimIndent()
        val r = WritingAudit.audit(clean, clean.replace(Regex("\\s"), "").length, structured = true)
        assertTrue(r.issues.isEmpty(), "合规样本应零注记：${r.issues}")
        assertFalse(r.hard)
    }

    @Test
    fun `drift-guard——黑名单每条都能在提示词里找到锚点`() {
        val prompt = humanWritingBlock()
        val missing = BANNED_WRITING_RULES.filter { !prompt.contains(it.promptAnchor) }
        assertTrue(missing.isEmpty(), "以下黑名单条目在 humanWritingBlock() 缺少锚点，审计与提示词必须同步改：${missing.map { it.label }}")
    }

    @Test
    fun `黑名单正则验真——关键正则逐条命中各自样例（防 pattern 写错永远不命中）`() {
        val probes = mapOf(
            "随着…的不断发展" to "随着市场的不断发展，销量上行。",
            "在当今…时代" to "在当今数字化时代，数据是资产。",
            "进行了…分析" to "我们对数据进行了深入的分析。",
            "实现了…增长" to "本季度实现了双位数增长。",
            "对…起到了…作用" to "降价对销量起到了拉动作用。",
            "为…奠定了坚实基础" to "为后续扩张奠定了坚实基础。",
            "首先/其次/再次/最后 串段" to "首先，看总量。其次，看结构。最后，给建议。",
        )
        probes.forEach { (label, probe) ->
            val rule = BANNED_WRITING_RULES.first { it.label == label }
            assertTrue(rule.pattern.containsMatchIn(probe), "正则「$label」应命中样例：$probe")
        }
    }
}
