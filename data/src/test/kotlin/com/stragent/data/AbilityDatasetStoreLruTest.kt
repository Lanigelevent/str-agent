package com.stragent.data

import com.stragent.core.dataset.Dataset
import com.stragent.core.ports.DatasetStore
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-076 · 数据集持久化总量 LRU（D21 P-3）。
 *
 * 三个真问题：
 *  ①条数超限：最旧的集被淘汰、**新导入永远成功**（persisted=true）——负控是 D21 指定的验收；
 *  ②LRU 语义：重导 = 最近使用，重导过的集不让位，让位的是真正最旧的；
 *  ③字节上限：总量口径（keep 条目计入）；单集超总上限时如实保留新集（最新优先）。
 */
class AbilityDatasetStoreLruTest {

    private fun datasetOf(name: String): Dataset = tableDataset(name, "k,v\n1,2\n")

    /** 单调计数器时钟（T-159）：注入后 savedAt 严格递增——LRU 序确定、免 sleep、免疫墙钟并列 */
    private fun monotonic(): () -> Long {
        var tick = 0L
        return { ++tick }
    }

    private fun store(
        entries: Int = 64,
        capacity: Long = 512L * 1024 * 1024,
        clock: () -> Long = System::currentTimeMillis,
    ): AbilityDatasetStore =
        AbilityDatasetStore(
            FileDatasetStore(createTempDirectory("lru").toFile()),
            maxEntries = entries,
            capacityBytes = capacity,
            clock = clock,
        )

    @Test
    fun `负控-条数超限最旧集被淘汰且新导入成功`() {
        val s = store(entries = 3, clock = monotonic())
        assertTrue(s.save("ds_a", datasetOf("A")))
        assertTrue(s.save("ds_b", datasetOf("B")))
        assertTrue(s.save("ds_c", datasetOf("C")))
        assertNotNull(s.load("ds_a"), "前置：A 尚在（未超限不淘汰）")

        val (ok, evicted) = s.saveWithEvictions("ds_d", datasetOf("D"))
        assertTrue(ok, "新导入必须成功（最新使用优先留存）")
        assertEquals(listOf("ds_a"), evicted, "最旧的 A 让位")
        assertNull(s.load("ds_a"), "A 已被淘汰")
        assertNotNull(s.load("ds_d"))
        assertNotNull(s.load("ds_b"), "B/C 不受牵连")
        assertNotNull(s.load("ds_c"))
    }

    @Test
    fun `重导即最近使用-淘汰的是真正最旧的`() {
        val s = store(entries = 2, clock = monotonic())
        assertTrue(s.save("ds_a", datasetOf("A")))
        assertTrue(s.save("ds_b", datasetOf("B")))
        // 重导 A：A 变成最近使用
        assertTrue(s.save("ds_a", datasetOf("A2")))
        val (ok, evicted) = s.saveWithEvictions("ds_c", datasetOf("C"))
        assertTrue(ok)
        assertEquals(listOf("ds_b"), evicted, "让位的应是没被重导过的 B")
        assertNotNull(s.load("ds_a"), "重导过的 A 留存")
    }

    @Test
    fun `字节上限-总量口径且新集超上限时如实保留`() {
        val s = store(entries = 8, capacity = 1L) // 1 字节总量：任何非空集都超
        val (ok, evicted) = s.saveWithEvictions("ds_only", datasetOf("唯一"))
        assertTrue(ok, "最新使用优先：单集超总上限也如实保留（它自己是唯一候选）")
        assertTrue(evicted.isEmpty(), "没有别的条目可淘汰")
        assertNotNull(s.load("ds_only"))
    }

    @Test
    fun `字节上限-旧集给新集让位`() {
        val s = store(entries = 8, capacity = 8L * 1024, clock = monotonic())
        assertTrue(s.save("ds_big", tableDataset("大", "k,v\n" + "1,2\n".repeat(3000)))) // ~12KB，落盘即超 8KB 总上限
        val (ok, evicted) = s.saveWithEvictions("ds_small", tableDataset("小", "k,v\n1,2\n"))
        assertTrue(ok)
        assertEquals(listOf("ds_big"), evicted, "总量超限：旧的大集让位")
        assertNull(s.load("ds_big"))
        assertNotNull(s.load("ds_small"))
    }

    @Test
    fun `回执层-淘汰名单随导入如实交代`() {
        val rawStore = FileDatasetStore(createTempDirectory("lru-receipt").toFile())
        val datasets = AbilityDatasets(AbilityDatasetStore(rawStore, maxEntries = 2))
        fun import(csv: String): JsonObject = abilityTools(datasets)
            .first { it.name == "stragent_import_table" }
            .invoke(buildJsonObject { put("csv", csv); put("name", "LRU") })
        val first = import("a,a\n1,1\n")
        Thread.sleep(25)
        import("b,b\n2,2\n")
        Thread.sleep(25)
        val third = import("c,c\n3,3\n")

        assertEquals("true", third.str("persisted"), "新导入永远成功")
        val evicted = third["evicted"] as? List<*> ?: error("回执缺 evicted：$third")
        assertEquals(1, evicted.size, "最旧的一个让位：$third")
        assertEquals(first.str("datasetId"), (evicted[0] as JsonPrimitive).content, "淘汰的是第一份")
        assertTrue(third.str("evictedNote").isNotEmpty(), "淘汰要有说明")
        // 淘汰的是**落盘**（P-3 管的是 kv 无限增长）；会话内存仍在，byId 照常可用
        assertNotNull(datasets.byId(first.str("datasetId")), "本会话内存仍可用")
        assertNull(AbilityDatasetStore(rawStore).load(first.str("datasetId")), "落盘已淘汰")
    }

    private fun tableDataset(name: String, csv: String): Dataset {
        val table = com.stragent.core.csv.Csv.parseDelimitedFull(csv)
        return Dataset(
            name = name,
            importedAt = 0L,
            columns = table.columns,
            rows = table.rows,
            columnTypes = table.columnTypes,
        )
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content ?: error("缺少字段 $key：$this")
}
