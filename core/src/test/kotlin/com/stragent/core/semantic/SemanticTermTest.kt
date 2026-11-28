package com.stragent.core.semantic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 语义层规则测试 —— 提取候选 / 列名匹配 / 注入块生成 / 版本门控。
 *
 * 与 CaliberMemoryTest 同构：每条纪律（不是数据、不做提炼、跨数据集持久）
 * 都有对应的断言锚，变异体反向验证。
 */
class SemanticTermTest {

    // ---- 提取候选 ----

    @Test
    fun `定义类信号——指的是`() {
        val candidate = extractTermHint("销售额指的是不含退货的净销售金额")
        assertNotNull(candidate)
        assertEquals("销售额", candidate.name)
        assertEquals("definition", candidate.kind)
        assertTrue(candidate.text.contains("不含退货"))
    }

    @Test
    fun `定义类信号——定义为`() {
        val candidate = extractTermHint("GMV定义为成交订单的总金额")
        assertNotNull(candidate)
        assertEquals("GMV", candidate.name)
        assertEquals("definition", candidate.kind)
    }

    @Test
    fun `约束类信号——不含`() {
        val candidate = extractTermHint("销售额不含退货金额")
        assertNotNull(candidate)
        assertEquals("销售额", candidate.name)
        assertEquals("constraint", candidate.kind)
        assertTrue(candidate.text.contains("退货"))
    }

    @Test
    fun `约束类信号——按某算`() {
        val candidate = extractTermHint("利润按净利润算")
        assertNotNull(candidate)
        assertEquals("利润", candidate.name)
        assertEquals("constraint", candidate.kind)
    }

    @Test
    fun `不命中——普通提问`() {
        assertNull(extractTermHint("华东占比多少？"))
    }

    @Test
    fun `不命中——太短`() {
        assertNull(extractTermHint("不含"))
    }

    @Test
    fun `超长截断加省略号`() {
        val longText = "销售额指的是" + "详细说明".repeat(60)
        val candidate = extractTermHint(longText)
        assertNotNull(candidate)
        assertTrue(candidate.text.length <= SEMANTIC_MAX_DEF_CHARS + 1)
        assertTrue(candidate.text.endsWith("…"))
    }

    @Test
    fun `候选理由——用户有权知道判据`() {
        assertNotNull(termHintReason("销售额指的是不含退货"))
        assertNull(termHintReason("普通问题"))
    }

    // ---- 列名匹配 ----

    @Test
    fun `精确匹配优先于子串匹配`() {
        val terms = listOf(
            SemanticTerm("id1", "销售额", aliases = listOf("sales"), definition = "x", source = "manual", createdAt = 0, updatedAt = 0),
            SemanticTerm("id2", "月销售额", definition = "y", source = "manual", createdAt = 0, updatedAt = 0),
        )
        val matched = matchTermsForColumns(terms, listOf("销售额"))
        assertEquals(2, matched.size)
        // 精确匹配排前面
        assertEquals("销售额", matched[0].name)
    }

    @Test
    fun `别名匹配——别名也能匹配列名`() {
        val terms = listOf(
            SemanticTerm("id1", "销售额", aliases = listOf("sales", "revenue"), definition = "x", source = "manual", createdAt = 0, updatedAt = 0),
        )
        val matched = matchTermsForColumns(terms, listOf("sales"))
        assertEquals(1, matched.size)
        assertEquals("销售额", matched[0].name)
    }

    @Test
    fun `子串匹配——术语是列名子串`() {
        val terms = listOf(
            SemanticTerm("id1", "销售额", definition = "x", source = "manual", createdAt = 0, updatedAt = 0),
        )
        val matched = matchTermsForColumns(terms, listOf("月度销售额合计"))
        assertEquals(1, matched.size)
    }

    @Test
    fun `不匹配——无关联列名返回空`() {
        val terms = listOf(
            SemanticTerm("id1", "销售额", definition = "x", source = "manual", createdAt = 0, updatedAt = 0),
        )
        val matched = matchTermsForColumns(terms, listOf("temperature", "humidity"))
        assertTrue(matched.isEmpty())
    }

    @Test
    fun `空列名列表返回全部术语`() {
        val terms = listOf(
            SemanticTerm("id1", "销售额", definition = "x", source = "manual", createdAt = 0, updatedAt = 0),
        )
        val matched = matchTermsForColumns(terms, emptyList())
        assertEquals(1, matched.size)
    }

    // ---- 注入块 ----

    @Test
    fun `semanticBlock——空列表返回 null`() {
        assertNull(semanticBlock(emptyList()))
    }

    @Test
    fun `semanticBlock——含标签和规则`() {
        val terms = listOf(
            SemanticTerm("id1", "销售额", definition = "不含退货的净销售", source = "manual", createdAt = 0, updatedAt = 0),
        )
        val block = semanticBlock(terms)
        assertNotNull(block)
        assertTrue(block.contains("<semantic_layer>"))
        assertTrue(block.contains("销售额"))
        assertTrue(block.contains("不含退货"))
        // 纪律 a：不是数据——必须声明不可直接引用
        assertTrue(block.contains("不是数据"))
    }

    @Test
    fun `semanticBlock——按术语名升序确定性排序`() {
        val terms = listOf(
            SemanticTerm("id3", "利润", definition = "c", source = "manual", createdAt = 0, updatedAt = 0),
            SemanticTerm("id1", "销售额", definition = "a", source = "manual", createdAt = 0, updatedAt = 0),
            SemanticTerm("id2", "成本", definition = "b", source = "manual", createdAt = 0, updatedAt = 0),
        )
        val block = semanticBlock(terms)
        assertNotNull(block)
        // 升序（按归一名的 Unicode 序）：利润(利=U+5229) < 成本(成=U+6210) < 销售额(销=U+9500)
        val profitIdx = block!!.indexOf("- 利润")
        val costIdx = block.indexOf("- 成本")
        val salesIdx = block.indexOf("- 销售额")
        assertTrue(profitIdx < costIdx, "利润应在成本前面：profit=$profitIdx cost=$costIdx")
        assertTrue(costIdx < salesIdx, "成本应在销售额前面：cost=$costIdx sales=$salesIdx")
    }

    @Test
    fun `semanticBlock——超预算整条丢弃不截半句`() {
        // 造一组超长术语，让总字数超过 SEMANTIC_BLOCK_CHAR_BUDGET
        val longDef = "x".repeat(SEMANTIC_MAX_DEF_CHARS)
        val terms = (1..20).map {
            SemanticTerm("id$it", "术语$it", definition = longDef, source = "manual", createdAt = 0, updatedAt = 0)
        }
        val block = semanticBlock(terms)
        assertNotNull(block)
        // 必须有预算截断提示
        assertTrue(block!!.contains("另有") || block.contains("未列出"))
    }

    // ---- 版本门控 ----

    @Test
    fun `isMoreSpecific——约束总是有值`() {
        val old = SemanticTerm("id1", "销售额", definition = "不含退货", source = "manual", createdAt = 0, updatedAt = 0)
        val candidate = TermCandidate("销售额", "还要扣掉折扣", "constraint", "调整计算")
        assertTrue(isMoreSpecific(candidate, old))
    }

    @Test
    fun `isMoreSpecific——重复约束不算更具体`() {
        val old = SemanticTerm("id1", "销售额", definition = "不含退货", constraints = listOf("扣掉折扣"), source = "manual", createdAt = 0, updatedAt = 0)
        val candidate = TermCandidate("销售额", "扣掉折扣", "constraint", "调整计算")
        assertFalse(isMoreSpecific(candidate, old))
    }

    @Test
    fun `isMoreSpecific——更长定义算更具体`() {
        val old = SemanticTerm("id1", "销售额", definition = "短定义", source = "manual", createdAt = 0, updatedAt = 0)
        val candidate = TermCandidate("销售额", "这是一个更长的定义，信息量更大", "definition", "在下定义")
        assertTrue(isMoreSpecific(candidate, old))
    }

    @Test
    fun `isMoreSpecific——更短定义不算更具体`() {
        val old = SemanticTerm("id1", "销售额", definition = "一个很长的定义", source = "manual", createdAt = 0, updatedAt = 0)
        val candidate = TermCandidate("销售额", "短", "definition", "在下定义")
        assertFalse(isMoreSpecific(candidate, old))
    }

    // ---- 归一 ----

    @Test
    fun `normalizeTermName——大小写不敏感`() {
        assertEquals("sales", normalizeTermName("Sales"))
        assertEquals("sales", normalizeTermName(" SALES "))
    }

    @Test
    fun `normalizeTermName——折叠空白`() {
        assertEquals("销售额", normalizeTermName(" 销售额 "))
    }
}
