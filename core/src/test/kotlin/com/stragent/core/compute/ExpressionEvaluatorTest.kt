package com.stragent.host

import com.stragent.core.compute.ExpressionEvaluator
import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset
import com.stragent.core.dataset.datasetFromParsed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 受控表达式求值器的单测（core 层）。
 *
 * 守两条线：
 *  ① **口径与 tier-1 一致**：数值解析走同一套 parseNumber；count 是参与统计的样本数；
 *  ② **越权构造一律拒绝**：没有函数定义/循环/IO/网络 —— 语法层面就不支持。
 */
class ExpressionEvaluatorTest {

    private val csv = """
        地区,销售额,订单数,成本
        华东,1000,10,600
        华北,300,5,200
        华东,200,8,100
        华南,50,1,40
    """.trimIndent()

    private val dataset: Dataset = datasetFromParsed("t", 1, Csv.parseDelimited(csv))

    private fun eval(code: String): Double =
        (ExpressionEvaluator.evaluate(code, dataset) as kotlinx.serialization.json.JsonPrimitive).content.toDouble()

    @Test
    fun `基础聚合与 tier-1 口径一致`() {
        assertEquals(1550.0, eval("sum(销售额)"), 1e-9)
        assertEquals(387.5, eval("avg(销售额)"), 1e-9)
        assertEquals(4.0, eval("count()"), 1e-9)
        assertEquals(50.0, eval("min(销售额)"), 1e-9)
        assertEquals(1000.0, eval("max(销售额)"), 1e-9)
        assertEquals(250.0, eval("median(销售额)"), 1e-9)
        assertEquals(3.0, eval("distinct(地区)"), 1e-9)
    }

    @Test
    fun `组合表达式——策展工具做不到的那部分`() {
        // 平均客单价 = 销售额/订单数 的平均
        val avgTicket = eval("avg(销售额 / 订单数)")
        assertEquals((100.0 + 60.0 + 25.0 + 50.0) / 4, avgTicket, 1e-9)
        // 毛利中位数（毛利：400 / 100 / 100 / 10 → 中位 100）
        assertEquals(100.0, eval("median(销售额 - 成本)"), 1e-9)
        // 达标行数
        assertEquals(1.0, eval("count(销售额 > 500)"), 1e-9)
        // 毛利合计（400 + 100 + 100 + 10）
        assertEquals(610.0, eval("sum(销售额 - 成本)"), 1e-9)
    }

    @Test
    fun `脏值与空值不参与统计（与 tier-1 的 count 口径一致）`() {
        val dirty = datasetFromParsed(
            "t2",
            1,
            Csv.parseDelimited("a,b\n1,10\n,20\nxx,30\n40,50\n"),
        )
        val value = (ExpressionEvaluator.evaluate("sum(a)", dirty) as kotlinx.serialization.json.JsonPrimitive).content.toDouble()
        assertEquals(41.0, value, 1e-9, "只有 1 与 40 是可解析的数值")
    }

    @Test
    fun `越权与非法构造一律拒绝`() {
        assertFailsWith<ExpressionEvaluator.ComputeException> { eval("__import__('os')") }
        assertFailsWith<ExpressionEvaluator.ComputeException> { eval("sum(销售额) + eval(1)") }
        assertFailsWith<ExpressionEvaluator.ComputeException> { eval("open('/etc/passwd')") }
        assertFailsWith<ExpressionEvaluator.ComputeException> { eval("不存在的列") }
        assertFailsWith<ExpressionEvaluator.ComputeException> { eval("sum(不存在的列)") }
        assertFailsWith<ExpressionEvaluator.ComputeException> { eval("sum(sum(销售额))") }
        assertFailsWith<ExpressionEvaluator.ComputeException> { eval("销售额") }
        assertFailsWith<ExpressionEvaluator.ComputeException> { eval("") }
    }

    @Test
    fun `错误信息是中文且可读`() {
        val error = assertFailsWith<ExpressionEvaluator.ComputeException> { eval("sum(没有这列)") }
        assertTrue(error.message!!.contains("没有这列"), "应指出列名：${error.message}")
        assertTrue(error.message!!.contains("可用列"), "应列出可用列：${error.message}")
    }
}
