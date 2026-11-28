package com.stragent.data

import com.stragent.core.csv.ColumnType
import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset
import com.stragent.core.dataset.SamplingInfo
import java.nio.file.Files
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 数据集持久化（T-054 / D17 G-13）——「重启后数据集还在」的行为测试。
 *
 * 钉住的三件事：
 *  ① 存进去再读出来，行列/类型/截断位**逐项一致**（确定性重建，不做第二次转换损失）；
 *  ② 抽样口径（指纹、步长、样本→源行号映射）随数据集一起恢复；
 *  ③ 存储损坏 / 超限都不抛、不静默：损坏跳过，超限 save=false。
 */
class AbilityDatasetStoreTest {

    private fun tempStore(maxBytes: Int = 32 * 1024 * 1024): Pair<AbilityDatasetStore, FileDatasetStore> {
        val dir = Files.createTempDirectory("str-agent-ability-store").toFile()
        val blobs = FileDatasetStore(dir)
        return AbilityDatasetStore(blobs, maxBytes) to blobs
    }

    private fun datasetOf(name: String = "门店"): Dataset {
        val table = Csv.parseDelimited("地区,销售额\n华东,1000\n华北,300\n")
        return Dataset(
            name = name,
            importedAt = 42L,
            columns = table.columns,
            rows = table.rows,
            columnTypes = table.columnTypes,
        )
    }

    @Test
    fun `保存后读回——行列类型与来源行数逐项一致`() {
        val (store, _) = tempStore()
        val original = datasetOf()
        assertTrue(store.save("ds_abc", original))
        val loaded = store.load("ds_abc")
        assertNotNull(loaded)
        assertEquals(original.columns, loaded.columns)
        assertEquals(original.rows, loaded.rows)
        assertEquals(original.columnTypes, loaded.columnTypes)
        assertEquals(original.truncated, loaded.truncated)
        assertEquals(original.sourceRows, loaded.sourceRows)
        assertEquals(42L, loaded.importedAt)
        assertNull(loaded.sampling, "未抽样数据集恢复后不应凭空多出抽样口径")
    }

    @Test
    fun `抽样口径随数据集恢复——指纹与行号映射可用`() {
        val (store, _) = tempStore()
        val sampling = SamplingInfo(
            targetRows = 2,
            step = 50,
            totalRows = 100,
            fingerprint = "sha256:" + "cd".repeat(32),
            rowNumbers = listOf(1, 51),
        )
        val dataset = datasetOf().copy(
            truncated = true,
            sourceRows = 100,
            sampling = sampling,
        )
        assertTrue(store.save("ds_sample", dataset))
        val loaded = store.load("ds_sample")!!
        assertEquals(sampling, loaded.sampling)
        assertEquals(51, loaded.sampling!!.sourceRowOf(2))
        // 契约层回链：样本第 2 行 → 源文件第 51 行
        val rows = StrAgentAbilities.listRows(
            AbilityDatasets().apply { put("ds_sample", loaded) },
            "ds_sample",
            0.0,
            2.0,
        )
        val second = rows["items"]!!.jsonArray[1].jsonObject
        assertEquals(2, second["row"]!!.jsonPrimitive.content.toInt())
        assertEquals(51, second["sourceRow"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `listRows 在抽样数据集上给出 sourceRow 与 sampling 块`() {
        val table = Csv.parseDelimited("地区,销售额\n华东,1000\n华北,300\n")
        val sampling = SamplingInfo(
            targetRows = 2,
            step = 50,
            totalRows = 100,
            fingerprint = "sha256:" + "ef".repeat(32),
            rowNumbers = listOf(1, 51),
        )
        val datasets = AbilityDatasets()
        datasets.put("ds_x", Dataset("x", 0, table.columns, table.rows, table.columnTypes, sampling = sampling))
        val result = StrAgentAbilities.listRows(datasets, "ds_x", 1.0, 1.0)
        val item = result["items"]!!.toString()
        assertTrue(item.contains("\"sourceRow\":51"), "第二行应回链源文件第 51 行：$item")
        assertTrue(result.toString().contains("\"sampling\""), "应带 sampling 块")
    }

    @Test
    fun `重启恢复——新注册表从存储回灌同一 datasetId`() {
        val (store, _) = tempStore()
        val first = AbilityDatasets(store)
        first.put("ds_persist", datasetOf("持久化门店"))
        assertEquals(true, first.persistedState("ds_persist"))

        // 模拟进程重启：新注册表 + 同一个 store
        val second = AbilityDatasets(store)
        assertEquals(0, second.size(), "未 restore 前内存为空（与重启后首帧一致）")
        assertEquals(1, second.restore())
        assertEquals("持久化门店", second.mustDataset("ds_persist").name)
        assertEquals("ds_persist", second.currentId(), "恢复后当前数据集 = 最近保存的那份")
    }

    @Test
    fun `未命中回读——不依赖启动 restore 也能取到已保存的数据集`() {
        val (store, _) = tempStore()
        AbilityDatasets(store).put("ds_lazy", datasetOf("懒加载"))
        val fresh = AbilityDatasets(store)
        assertEquals("懒加载", fresh.byId("ds_lazy")!!.name)
    }

    @Test
    fun `超过单份上限不落盘——save 返回 false 且 persistedState 如实记录`() {
        val (store, _) = tempStore(maxBytes = 16)
        val datasets = AbilityDatasets(store)
        datasets.put("ds_big", datasetOf())
        assertEquals(false, datasets.persistedState("ds_big"), "超限要如实回报未落盘")
        assertNull(store.load("ds_big"))
        // 内存里仍然可用（本次会话不受影响）
        assertEquals("门店", datasets.mustDataset("ds_big").name)
    }

    @Test
    fun `损坏条目——loadAll 跳过而不是拖死启动`() {
        val (store, blobs) = tempStore()
        assertTrue(store.save("ds_ok", datasetOf("好的")))
        blobs.put(AbilityDatasetStore.KEY_PREFIX + "ds_broken", "{这不是 JSON")
        val restored = AbilityDatasets(store).let { datasets -> datasets.restore() to datasets }
        assertEquals(1, restored.first, "损坏条目应被跳过，只恢复 1 条")
        assertNotNull(restored.second.byId("ds_ok"))
        assertNull(restored.second.byId("ds_broken"))
    }

    @Test
    fun `同 id 重存覆盖——内容派生 id 不会积累重复条目`() {
        val (store, blobs) = tempStore()
        assertTrue(store.save("ds_same", datasetOf("旧名")))
        assertTrue(store.save("ds_same", datasetOf("新名")))
        assertEquals("新名", store.load("ds_same")!!.name)
        assertEquals(
            1,
            blobs.keys().count { it == AbilityDatasetStore.KEY_PREFIX + "ds_same" },
        )
    }

    @Test
    fun `类型列清单为空时回落到重解析推断`() {
        val (store, _) = tempStore()
        val table = Csv.parseDelimited("a,b\n1,2\n")
        assertTrue(store.save("ds_types", Dataset("types", 0, table.columns, table.rows, emptyList())))
        // 空类型清单存进去、读回来按重解析推断补齐（列数一致 → 逐列 NUMBER）
        assertEquals(listOf(ColumnType.NUMBER, ColumnType.NUMBER), store.load("ds_types")!!.columnTypes)
    }

    @Test
    fun `空数据集不落盘`() {
        val (store, _) = tempStore()
        val empty = Dataset("空", 0, listOf("a"), emptyList(), listOf(ColumnType.TEXT))
        assertFalse(store.save("ds_empty", empty))
    }
}
