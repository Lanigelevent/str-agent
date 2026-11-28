package com.stragent.core.report

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IndustryMatchTest {
    @Test
    fun `superstore 列名命中电商`() {
        val rec = recommendIndustry(
            listOf("Order ID", "Order Date", "Category", "Product Name", "Sales", "Discount", "Profit", "Region"),
        )
        assertEquals("ecommerce", rec.industryId)
        assertTrue(rec.confidence >= MATCH_THRESHOLD, "confidence=${rec.confidence}")
        assertTrue(rec.reason.startsWith("命中 "))
    }

    @Test
    fun `无特征列名回落通用（宁可不猜不乱猜）`() {
        val rec = recommendIndustry(listOf("x", "y", "value1", "value2"))
        assertEquals("general", rec.industryId)
        assertEquals("列名里没有足够明显的行业特征，先用通用框架", rec.reason)
    }

    @Test
    fun `强信号与普通信号同时命中时满分（TS 双重计入的原样行为）`() {
        // 'sharpe' 同时在 securities 的普通清单与强信号清单里——TS 的两轮
        // consider 循环会把它推进 strongHits 两次 → strong=2 → 1.0。原样保留。
        val one = recommendIndustry(listOf("sharpe_ratio", "foo", "bar"))
        assertEquals("securities", one.industryId)
        assertEquals(1.0, one.confidence)
        val two = recommendIndustry(listOf("carat", "bar"))
        assertEquals("ecommerce", two.industryId)
        assertEquals(0.8, two.confidence, "只命中强信号清单里的 carat 一次")
    }

    @Test
    fun `拆词口径：下划线、驼峰、整词不拆`() {
        assertEquals(listOf("total", "sales"), columnWords("total_sales"))
        assertEquals(listOf("total", "bill"), columnWords("totalBill"))
        assertEquals(listOf("paperlessbilling"), columnWords("paperlessbilling"))
        assertEquals(listOf("bill", "length", "mm"), columnWords("bill_length_mm"))
        // 单复数兼容：passengers 命中 passenger（强信号）
        val rec = recommendIndustry(listOf("passenger_count", "flight_no", "bar"))
        assertEquals("travel", rec.industryId)
    }
}
