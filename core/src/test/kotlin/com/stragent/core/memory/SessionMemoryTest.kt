package com.stragent.core.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionMemoryTest {

    @Test
    fun `正文归一——折叠空白、空拒收、超长截断`() {
        assertEquals("华东 不含 上海", normalizeMemoryText("  华东\n 不含   上海 \t"))
        // 空与纯空白不入库
        assertNull(normalizeMemoryText(""))
        assertNull(normalizeMemoryText("   \n\t "))
        // 超长按字截断并留省略号（让读的人知道话没说完）
        val long = "结".repeat(MEMORY_MAX_TEXT_CHARS + 50)
        val cut = normalizeMemoryText(long)
        assertTrue(cut != null && cut.length == MEMORY_MAX_TEXT_CHARS + 1 && cut.endsWith("…"))
        // 重复判定对排版差异不敏感：首尾空白 + 连续空白折叠后同形，就算同一条。
        // 注意「折叠」是多空白变**一个**空白、不是删空白——中文原文里本没空格，
        // 硬插进去是改了内容，那种情况判重反而是错的。
        assertEquals(memoryDedupeKey("report: 华东 占比最高"), memoryDedupeKey("  report:  华东    占比最高 \t\n"))
        // 大小写不敏感：同一句英文结论换个大小写不该存两遍
        assertEquals(memoryDedupeKey("Report: East TOP"), memoryDedupeKey("report: east top"))
        // 数字差一点就是另一条结论，不许判重
        assertNotEquals(memoryDedupeKey("华东占比 45.3%"), memoryDedupeKey("华东占比 46.3%"))
        assertNull(memoryDedupeKey("   "))
    }

    @Test
    fun `memoryBlock——独立块带三条纪律且绝不与可引用数字混源`() {
        assertNull(memoryBlock(emptyList()))
        val block = memoryBlock(listOf(MemoryFact("f1", "followup", "华东占比 45.3%", "mcp", 1L)))
        assertTrue(block!!.startsWith("<session_memory>"))
        assertTrue(block.endsWith("</session_memory>"))
        assertTrue(block.contains("- [followup] 华东占比 45.3%"))
        // 三条纪律逐条在场（缺一条就是给模型开了自证预言的口子）
        assertTrue(block.contains("不是数据"))
        assertTrue(block.contains("以本次为准"))
        assertTrue(block.contains("本次未复核"))
        // 独立块：不引用 brief 的锚点，也不带可引用数字白名单
        assertFalse(block.contains("<allowed_numbers>"))
        assertFalse(block.contains("<data_brief>"))
    }

    @Test
    fun `memoryBlock——新在前、同输入同输出、乱序输入也同输出`() {
        val a = MemoryFact("a", "report", "第一条", "mcp", 100L)
        val b = MemoryFact("b", "followup", "第二条", "host", 200L)
        val ordered = memoryBlock(listOf(a, b))!!
        assertTrue(ordered.indexOf("第二条") < ordered.indexOf("第一条"), "savedAt 大的必须在前面")
        // 确定性：乱序输入 → 逐字节相同（可对拍、可写进测试当锚点）
        assertEquals(memoryBlock(listOf(b, a)), ordered)
        // 同 savedAt 时按 id 兜底，不依赖插入顺序
        val same = listOf(MemoryFact("z", "report", "甲", "mcp", 5L), MemoryFact("y", "report", "乙", "mcp", 5L))
        assertEquals(memoryBlock(same.reversed()), memoryBlock(same))
    }

    @Test
    fun `memoryBlock——预算内整条丢弃，不留半句`() {
        // 每条正文顶到 240 字上限（正文自带编号：块里只渲染 kind + 正文，
        // 想验证「丢的是尾部」必须把序号写进正文，光靠 id 是看不见的）
        val label = "第%02d条："
        val many = (1..20).map {
            MemoryFact("id%02d".format(it), "report", label.format(it) + "数".repeat(MEMORY_MAX_TEXT_CHARS - label.format(1).length), "mcp", 1000L - it)
        }
        val block = memoryBlock(many)!!
        // 逐行数（MULTILINE 必须显式开：Kotlin/Java 的 ^ 默认不是多行模式，
        // 漏开就只会数到第一条，「丢弃了多少条」的断言会假绿）
        val listed = Regex("^- \\[", RegexOption.MULTILINE).findAll(block).count()
        assertTrue(listed < many.size, "全列出就等于没预算这回事")
        // 预算口径本身就是可复算的：每条固定 11 字符前缀 + 240 字正文 + 换行，
        // 列到不能再列为止——多一条就超预算，这正是「整条丢弃」的判据
        val perLine = "- [report] ".length + MEMORY_MAX_TEXT_CHARS + 1
        assertTrue(listed * perLine <= MEMORY_BLOCK_CHAR_BUDGET, "$listed 条不该在预算内")
        assertTrue((listed + 1) * perLine > MEMORY_BLOCK_CHAR_BUDGET, "还能塞一条就说明预算没生效")
        // 被丢的条数必须如实报出来（静默少列 = 用户以为记忆就这么多条）
        val dropped = many.size - listed
        assertTrue(block.contains("另有 $dropped 条更早的记忆因预算未列出"), block.take(120))
        // 丢弃只发生在尾部：列出的都是新的那些
        assertTrue(block.contains(label.format(1)))
        assertFalse(block.contains(label.format(20)))
    }

    @Test
    fun `kind 白名单——只有会话产出两类算记忆`() {
        // report / followup 是「做过什么」；工具调用这类流水不算记忆（会把 30 条额度吃光）
        assertEquals(setOf("report", "followup"), MEMORY_KINDS)
        // 上限常量本身是口径，改了要同时改文档与存储层测试
        assertEquals(30, MEMORY_MAX_FACTS)
        assertEquals(240, MEMORY_MAX_TEXT_CHARS)
        assertTrue(MEMORY_BLOCK_CHAR_BUDGET < MEMORY_MAX_FACTS * (MEMORY_MAX_TEXT_CHARS + 14))
    }
}
