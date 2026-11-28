package com.stragent.bench.external

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-057：比较器口径测试。
 *
 * 这些用例不是「随便写的样例」，每条都对应官方 evaluate.py 的一条规则：
 * 浮点容差、整数精确、空值归一 0、按列匹配（预测可多列）、condition_cols 选列、
 * 多 gold 任一命中、ignore_order 排序。
 */
class GoldComparatorTest {

    private fun table(vararg columns: String, rows: List<List<Cell>>) = Table(columns.toList(), rows)

    private fun gold(csv: String) = GoldComparator.parseGoldCsv(csv)

    @Test
    fun `浮点列按 rel 1e-9 abs 1e-2 比较`() {
        val pred = table("v", rows = listOf(listOf(Cell.NumCell(3.0))))
        val g = gold("v\n3.009\n")
        assertTrue(GoldComparator.compareMulti(pred, listOf(g), ConditionCols.Empty, ignoreOrder = false))
        val g2 = gold("v\n3.02\n")
        assertFalse(GoldComparator.compareMulti(pred, listOf(g2), ConditionCols.Empty, ignoreOrder = false))
    }

    @Test
    fun `整数列精确比较 浮点容差不适用`() {
        val pred = table("v", rows = listOf(listOf(Cell.NumCell(1.0001))))
        val g = gold("v\n1\n")
        assertFalse(GoldComparator.compareMulti(pred, listOf(g), ConditionCols.Empty, ignoreOrder = false))
        val exact = table("v", rows = listOf(listOf(Cell.IntCell(1))))
        assertTrue(GoldComparator.compareMulti(exact, listOf(g), ConditionCols.Empty, ignoreOrder = false))
    }

    @Test
    fun `空值归一为 0 空字符串不等价`() {
        val missing = table("v", rows = listOf(listOf(Cell.Missing)))
        val g = gold("v\n0\n")
        assertTrue(GoldComparator.compareMulti(missing, listOf(g), ConditionCols.Empty, ignoreOrder = false))

        val emptyText = table("v", rows = listOf(listOf(Cell.TextCell(""))))
        val textGold = gold("v\nx\n")
        assertFalse(GoldComparator.compareMulti(emptyText, listOf(textGold), ConditionCols.Empty, ignoreOrder = false))
    }

    @Test
    fun `预测可以有多余列 按列向量匹配`() {
        val pred = table(
            "noise", "answer",
            rows = listOf(listOf(Cell.TextCell("x"), Cell.IntCell(7))),
        )
        val g = gold("answer\n7\n")
        assertTrue(GoldComparator.compareMulti(pred, listOf(g), ConditionCols.Empty, ignoreOrder = false))
    }

    @Test
    fun `condition_cols 只比选中列 且下标从 0 开始`() {
        val g = gold("a,b,c\n1,2,3\n")
        // 只比 b 列：预测的 a/c 列写错也不影响
        val good = table("x", rows = listOf(listOf(Cell.IntCell(2))))
        assertTrue(GoldComparator.compareMulti(good, listOf(g), ConditionCols.Flat(listOf(1)), ignoreOrder = false))
        val bad = table("x", rows = listOf(listOf(Cell.IntCell(1))))
        assertFalse(GoldComparator.compareMulti(bad, listOf(g), ConditionCols.Flat(listOf(1)), ignoreOrder = false))
    }

    @Test
    fun `ignore_order 排序后比较`() {
        val pred = table(
            "v",
            rows = listOf(listOf(Cell.IntCell(3)), listOf(Cell.IntCell(1)), listOf(Cell.IntCell(2))),
        )
        val g = gold("v\n1\n2\n3\n")
        assertTrue(GoldComparator.compareMulti(pred, listOf(g), ConditionCols.Empty, ignoreOrder = true))
        assertFalse(GoldComparator.compareMulti(pred, listOf(g), ConditionCols.Empty, ignoreOrder = false))
    }

    @Test
    fun `多条 gold 任一命中即通过 嵌套 condition_cols 按 gold 对应`() {
        val g1 = gold("a,b\n1,9\n")
        val g2 = gold("a,b\n2,8\n")
        val pred = table("x", rows = listOf(listOf(Cell.IntCell(8))))
        // g1 的 b 列、g2 的 b 列都要能对上；pred 的 8 能对上 g2
        assertTrue(
            GoldComparator.compareMulti(
                pred,
                listOf(g1, g2),
                ConditionCols.PerGold(listOf(listOf(1), listOf(1))),
                ignoreOrder = false,
            ),
        )
        assertFalse(
            GoldComparator.compareMulti(
                pred,
                listOf(g1, g2),
                ConditionCols.PerGold(listOf(listOf(0), listOf(0))),
                ignoreOrder = false,
            ),
        )
    }

    @Test
    fun `金标列类型按 pandas 规则推断`() {
        // 有空值 → 整数列升为浮点（pandas 默认跳过空白行，空值要用引号空字段）
        val t1 = gold("v\n1\n\"\"\n2\n")
        assertEquals(Cell.NumCell(1.0), t1.rows[0][0])
        assertEquals(Cell.Missing, t1.rows[1][0])
        // 纯整数列 → 整数
        val t2 = gold("v\n1\n2\n")
        assertEquals(Cell.IntCell(1), t2.rows[0][0])
        // 混合非数值 → 整列文本，保留前导零
        val t3 = gold("v\n007\nabc\n")
        assertEquals(Cell.TextCell("007"), t3.rows[0][0])
        // 引号与 CRLF
        val t4 = gold("a,b\r\n\"x,y\",2\r\n")
        assertEquals(Cell.TextCell("x,y"), t4.rows[0][0])
        assertEquals(Cell.IntCell(2), t4.rows[0][1])
    }

    @Test
    fun `文本字段前后空格是数据 不被 trim`() {
        val t = gold("name,v\n\"PAPA SUCIS \",1\n")
        assertEquals(Cell.TextCell("PAPA SUCIS "), t.rows[0][0])
        val trimmed = table("name", rows = listOf(listOf(Cell.TextCell("PAPA SUCIS"))))
        assertFalse(GoldComparator.compareMulti(trimmed, listOf(t), ConditionCols.Flat(listOf(0)), ignoreOrder = false))
        val exact = table("name", rows = listOf(listOf(Cell.TextCell("PAPA SUCIS "))))
        assertTrue(GoldComparator.compareMulti(exact, listOf(t), ConditionCols.Flat(listOf(0)), ignoreOrder = false))
    }

    @Test
    fun `列数不同时向量长度必须一致`() {
        val pred = table("v", rows = listOf(listOf(Cell.IntCell(1)), listOf(Cell.IntCell(2))))
        val g = gold("v\n1\n")
        assertFalse(GoldComparator.compareMulti(pred, listOf(g), ConditionCols.Empty, ignoreOrder = false))
    }
}
