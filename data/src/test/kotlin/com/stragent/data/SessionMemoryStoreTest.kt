package com.stragent.data

import com.stragent.core.memory.MEMORY_MAX_FACTS
import com.stragent.core.memory.MEMORY_MAX_TEXT_CHARS
import com.stragent.core.ports.DatasetStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 跨会话记忆存储的契约测试。
 *
 * 重点是「跨会话」这两个字要能被复现：**换一个 store 实例（同一目录）还能召回来**，
 * 这才是进程重启后的行为；同实例内内存对象传下去不算。
 */
class SessionMemoryStoreTest {

    private class MapStore : DatasetStore {
        val map = mutableMapOf<String, String>()
        override fun put(key: String, value: String) { map[key] = value }
        override fun get(key: String): String? = map[key]
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): List<String> = map.keys.toList()
    }

    private fun tempDir() = Files.createTempDirectory("str-agent-mem").toFile()

    private val dsA = "ds_aaaaaaaaaaaa"
    private val dsB = "ds_bbbbbbbbbbbb"

    @Test
    fun `跨会话复现——换一个实例落在同一目录仍能召回`() {
        val dir = tempDir()
        val first = SessionMemoryStore(FileDatasetStore(dir))
        assertTrue(first.remember(dsA, "followup", "华东占比最高，达 45.3%", "mcp", savedAt = 1000L))
        // 模拟进程重启：全新目录句柄 + 全新 store 实例
        val second = SessionMemoryStore(FileDatasetStore(dir))
        val recalled = second.recall(dsA)
        assertEquals(1, recalled.size)
        assertEquals("followup", recalled[0].kind)
        assertEquals("华东占比最高，达 45.3%", recalled[0].text)
        assertEquals("mcp", recalled[0].source)
        assertEquals(1000L, recalled[0].savedAt)
    }

    @Test
    fun `四条拒收——空 id、未知 kind、空正文、同文重复`() {
        val store = SessionMemoryStore(MapStore())
        assertFalse(store.remember("", "report", "有内容"))
        assertFalse(store.remember(dsA, "tool_call", "流水不算记忆"))
        assertFalse(store.remember(dsA, "report", "   \n  "))
        assertTrue(store.remember(dsA, "report", "第一轮结论：华东最高"))
        // 同义重排（多个空格 / 换行 / 末尾空白）仍是同一条，不许强调第二遍
        assertFalse(store.remember(dsA, "report", "第一轮结论：华东最高  \n"))
        assertEquals(1, store.recall(dsA).size)
        // 入库的是归一后的那一份（截断与折叠空白口径在 core 定死）
        val longStored = "开".repeat(MEMORY_MAX_TEXT_CHARS + 30)
        assertTrue(store.remember(dsA, "followup", longStored))
        val fat = store.recall(dsA).first { it.kind == "followup" }
        assertEquals(MEMORY_MAX_TEXT_CHARS + 1, fat.text.length)
        assertTrue(fat.text.endsWith("…"))
    }

    @Test
    fun `上限只留新的——灌 40 条剩 30 条且丢的是最旧`() {
        val store = SessionMemoryStore(MapStore())
        for (i in 1..40) {
            assertTrue(store.remember(dsA, "report", "第 $i 轮的问答结论", "mcp", savedAt = i.toLong()))
        }
        val facts = store.recall(dsA)
        assertEquals(MEMORY_MAX_FACTS, facts.size)
        assertEquals("第 40 轮的问答结论", facts.first().text)
        assertFalse(facts.any { it.text == "第 1 轮的问答结论" }, "最旧的必须先被挤出去")
        assertTrue(facts.any { it.text == "第 11 轮的问答结论" })
    }

    @Test
    fun `按内容指纹失效——换数据集召不回，载荷对不上不采信`() {
        val store = SessionMemoryStore(MapStore())
        assertTrue(store.remember(dsA, "report", "A 数据集的结论", "mcp"))
        // 另一份数据 = 另一个键，绝不共享（纪律 c：不给串数据的口子）
        assertEquals(emptyList(), store.recall(dsB))
        assertTrue(store.remember(dsB, "report", "B 数据集的结论", "host"))
        assertEquals("A 数据集的结论", store.recall(dsA).single().text)
        assertEquals("B 数据集的结论", store.recall(dsB).single().text)

        // 手改盘把 A 的载荷写成 B 的 id：读回来必须整片不采信，而不是当成 A 的记忆用
        val tampered = MapStore()
        val writer = SessionMemoryStore(tampered)
        writer.remember(dsA, "report", "被挪用的结论", "mcp")
        val raw = tampered.get(SessionMemoryStore.PREFIX + dsA)!!
        tampered.put(SessionMemoryStore.PREFIX + dsA, raw.replace(dsA, dsB))
        assertEquals(emptyList(), SessionMemoryStore(tampered).recall(dsA))
        // 同理：内容不是 JSON 数组（版本不兼容 / 被人清空过）当没有记忆，不许抛
        tampered.put(SessionMemoryStore.PREFIX + dsA, "{not json")
        assertEquals(emptyList(), SessionMemoryStore(tampered).recall(dsA))
    }

    @Test
    fun `forget 与 clear——不存在要说没有，清了就是清了`() {
        val store = SessionMemoryStore(MapStore())
        store.remember(dsA, "followup", "第一条", "mcp", savedAt = 1L)
        store.remember(dsA, "followup", "第二条", "mcp", savedAt = 2L)
        val id = store.recall(dsA).last().id
        assertTrue(store.forget(dsA, id))
        assertEquals(listOf("第二条"), store.recall(dsA).map { it.text })
        // id 不存在：返回 false，调用方不许拿「已删除」去提示用户
        assertFalse(store.forget(dsA, "不存在的id"))
        assertFalse(store.forget(dsB, id))
        assertTrue(store.clear(dsA))
        assertEquals(emptyList(), store.recall(dsA))
        // clear 空 id 不算成功（防止把「没清」报成「清完了」）
        assertFalse(store.clear(""))
    }
}
