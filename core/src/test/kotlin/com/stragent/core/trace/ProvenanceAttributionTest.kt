package com.stragent.core.trace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-073 · 追问归因的核心单测：数值面提取/归一 + 步骤对拍 + 清单往返。
 *
 * 三个真问题：
 *  ①数值面 token 是否按写明的规则归一（千分位/末尾零/百分号）；
 *  ②对拍是否按位置下标命中正确步骤，且旧步骤（numTokens=null）不误命中；
 *  ③numTokens 进出 JSON 往返无损，旧清单 JSON（无该字段）读回 null。
 */
class ProvenanceAttributionTest {

    @Test
    fun `数值面归一规则——千分位与末尾零与百分号`() {
        val surface = numericSurface(
            JsonPrimitive("""{"a":"12,345.00","b":"35.2%","c":"240.0","d":"-0.50"}"""),
        )
        assertTrue(surface.contains("12345"), "千分位剥掉：$surface")
        assertTrue(surface.contains("35.2"), "百分号剥掉：$surface")
        assertTrue(surface.contains("240"), "小数末尾零归一：$surface")
        assertTrue(surface.contains("-0.5"), "负数与末尾零：$surface")
        assertEquals(listOf("12345", "35.2", "240", "-0.5"), surface, "去重且保持插入序")
    }

    @Test
    fun `数值面从长文本与嵌套结构里抽取`() {
        val text = JsonPrimitive("华东 100 元，华南 80，合计 240（占 41.7%），订单 1,000 单")
        val surface = numericSurface(text)
        assertTrue(surface.containsAll(listOf("100", "80", "240", "41.7", "1000")))
    }

    @Test
    fun `数值面上限 64 与长 token 丢弃`() {
        val longNumber = "1" + "0".repeat(30) // 31 位：超过 24 字符上限，丢弃
        val many = (1..70).joinToString(" ") { "$it" }
        val surface = numericSurface(JsonPrimitive(many + " " + longNumber))
        assertEquals(64, surface.size, "上限 64：${surface.size}")
        assertTrue(surface.none { it.length > 24 }, "长 token 丢弃")
    }

    @Test
    fun `步骤对拍——按下标命中并回传命中的数字`() {
        val steps = listOf(
            stepWith(numTokens = listOf("100", "80", "60")),
            stepWith(numTokens = null), // 旧清单步骤：无数值面，跳过
            stepWith(numTokens = listOf("240")),
        )
        val refs = steps.stepIndicesMentioning(listOf("240", "60"))
        assertEquals(2, refs.size)
        assertEquals(0 to listOf("60"), refs[0])
        assertEquals(2 to listOf("240"), refs[1])
    }

    @Test
    fun `步骤对拍——空引用与无命中都是空表`() {
        val steps = listOf(stepWith(numTokens = listOf("100")))
        assertTrue(steps.stepIndicesMentioning(emptyList()).isEmpty(), "空引用集")
        assertTrue(steps.stepIndicesMentioning(listOf("777")).isEmpty(), "无命中")
    }

    @Test
    fun `清单往返——numTokens 进出 JSON 无损且旧清单读回 null`() {
        val with = stepWith(numTokens = listOf("100", "240"))
        val restored = ProvenanceStep.fromJson(with.toJson())
        assertEquals(listOf("100", "240"), restored.numTokens, "新清单往返保留")
        assertNull(ProvenanceStep.fromJson(stepWith(null).toJson()).numTokens, "未记录读回 null")

        // 旧清单 JSON（T-073 前形制，无 numTokens 字段）→ null，不报错
        val oldJson = Json.parseToJsonElement(
            """{"tool":"x","args":{},"argsHash":"a","resultHash":"r","datasetHash":"d","elapsedMs":1}""",
        ).jsonObject
        assertNull(ProvenanceStep.fromJson(oldJson).numTokens, "旧清单读回 null")
    }

    private fun stepWith(numTokens: List<String>?): ProvenanceStep = ProvenanceStep(
        tool = "t",
        args = kotlinx.serialization.json.JsonObject(emptyMap()),
        argsHash = "a",
        resultHash = "r",
        datasetHash = "d",
        elapsedMs = 1L,
        numTokens = numTokens,
    )
}
