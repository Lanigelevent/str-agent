package com.stragent.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * T-154 · 响应侧统一字节闸。
 *
 * 请求侧 8MB 有闸（T-059），响应侧此前没有对应物（D24 审查 P1-8）——
 * 逐工具上限是第一道防线，本闸是兜底：某个工具将来放宽上限时，
 * 响应体积不会静默放开。超限走 T-079 机器包成 isError（不截断——半截数据比明确报错更危险）。
 */
class ResponseCapTest {

    @Test
    fun `超限回执拒绝——错误信息给缩围与导出两条出路`() {
        // 5MB 的 text 字段 → 信封超 4MB 闸
        val big = buildJsonObject {
            put("content", kotlinx.serialization.json.JsonArray(listOf(
                buildJsonObject { put("type", "text"); put("text", "x".repeat(5 * 1024 * 1024)) },
            )))
        }
        val e = assertFailsWith<IllegalArgumentException> { ResponseCap.check(big) }
        // 期望文案**与生产同源**（T-139 纪律）：生产按 locale 选表，断言若写死中文
        // 在英文宿主上必红。故从消息表取「前半句 / 后半句」两段稳定片段来断言。
        val template = com.stragent.core.i18n.Messages.get("error.response_too_large")
        val head = template.substringBefore("{0}")
        val tail = template.substringAfter("{1}")
        assertTrue(e.message!!.contains(head), "回执应写明响应上限：${e.message}")
        assertTrue(e.message!!.contains(tail), "回执应给出缩围与导出两条出路：${e.message}")
    }

    @Test
    fun `正常回执不误伤——1MB 通过`() {
        val normal = buildJsonObject {
            put("content", kotlinx.serialization.json.JsonArray(listOf(
                buildJsonObject { put("type", "text"); put("text", "x".repeat(1024 * 1024)) },
            )))
        }
        ResponseCap.check(normal) // 不抛 = 通过
    }

    @Test
    fun `信封总量计——structuredContent 与 text 双发都计入`() {
        // text 1.5MB + structuredContent 1.5MB ≈ 3MB < 4MB：单看任一侧都该过，总量也过
        val payload = "y".repeat((1.5 * 1024 * 1024).toInt())
        val env = buildJsonObject {
            put("content", kotlinx.serialization.json.JsonArray(listOf(
                buildJsonObject { put("type", "text"); put("text", payload) },
            )))
            put("structuredContent", buildJsonObject { put("data", payload) })
        }
        ResponseCap.check(env) // 不抛
        // 再翻一倍 → 超 4MB → 拒
        val payload2 = "y".repeat((2.5 * 1024 * 1024).toInt())
        val env2 = buildJsonObject {
            put("content", kotlinx.serialization.json.JsonArray(listOf(
                buildJsonObject { put("type", "text"); put("text", payload2) },
            )))
            put("structuredContent", buildJsonObject { put("data", payload2) })
        }
        val e = assertFailsWith<IllegalArgumentException> { ResponseCap.check(env2) }
        assertTrue(e.message!!.contains("响应上限"), e.message)
    }
}