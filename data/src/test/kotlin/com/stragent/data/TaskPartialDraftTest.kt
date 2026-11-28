package com.stragent.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * T-172 · 增量可用产物测试。
 *
 * 覆盖：①正文增量按 (章, 次) 归档，同章重写覆盖旧片段；②`tasks/get` 带草稿
 * （`partialDraft` + `partialChars` + `partialVerified=false`）；③没有增量时**不带**草稿字段
 * （不编造「空草稿」）；④草稿回传有上限（长报告不撑爆轮询响应）。
 */
class TaskPartialDraftTest {

    @Test
    fun `正文增量按章归档，同章重写覆盖旧片段`() {
        val store = McpTaskStore()
        val task = store.create("t", "s", null)
        store.recordPartial(task.id, chapter = 1, attempt = 1, text = "第一章旧")
        store.recordPartial(task.id, chapter = 2, attempt = 1, text = "第二章")
        assertEquals("第一章旧\n\n第二章", store.partialDraft(task.id))

        // 同章重写（attempt 变大）→ 旧片段作废
        store.recordPartial(task.id, chapter = 1, attempt = 2, text = "第一章新")
        val draft = store.partialDraft(task.id)!!
        assertTrue(draft.contains("第一章新"), "重写后应是新稿：$draft")
        assertTrue(!draft.contains("第一章旧"), "旧片段必须作废：$draft")

        // 迟到的旧帧（attempt 更小）→ 忽略
        store.recordPartial(task.id, chapter = 1, attempt = 1, text = "第一章更旧")
        assertTrue(!store.partialDraft(task.id)!!.contains("更旧"), "迟到的旧帧必须忽略")
    }

    @Test
    fun `没有增量时不带草稿字段`() {
        val store = McpTaskStore()
        val task = store.create("t", "s", null)
        val snapshot = store.snapshot(task)
        assertNull(snapshot["partialDraft"], "没有增量就不该有草稿字段：$snapshot")
        assertNull(snapshot["partialVerified"])
    }

    @Test
    fun `草稿随快照给出并标明未过门禁`() {
        val store = McpTaskStore()
        val task = store.create("t", "s", null)
        store.recordPartial(task.id, chapter = 1, attempt = 1, text = "已成稿的一段")
        val snapshot = store.snapshot(task)
        assertEquals("已成稿的一段", snapshot["partialDraft"]!!.jsonPrimitive.content)
        assertEquals(6, snapshot["partialChars"]!!.jsonPrimitive.content.toInt())
        assertEquals("false", snapshot["partialVerified"]!!.jsonPrimitive.content, "草稿未过门禁，必须如实标注")
    }

    @Test
    fun `草稿回传有上限——长报告不撑爆轮询响应`() {
        val store = McpTaskStore()
        val task = store.create("t", "s", null)
        val long = "字".repeat(20_000)
        store.recordPartial(task.id, chapter = 1, attempt = 1, text = long)
        val snapshot = store.snapshot(task)
        val draft = snapshot["partialDraft"]!!.jsonPrimitive.content
        assertEquals(McpTaskStore.MAX_PARTIAL_CHARS, draft.length, "回传被截断")
        assertEquals(20_000, snapshot["partialChars"]!!.jsonPrimitive.content.toInt(), "真实长度照给")
    }
}
