package com.stragent.host

import com.stragent.data.AbilityDatasetStore
import com.stragent.data.FileDatasetStore
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-076 · kv 备份导出/导入（D21 R-2②）——宿主自用 CLI 的往返演练。
 *
 * 验收口径（R-2 演练精神）：导出 → **换一个空库** → 导入 → 数据回来（数据集可 byId）。
 * 兼顾两个语义边界：默认导入不覆盖现有键（防旧备份盖新状态）；replace=true 全量覆盖。
 */
class KvBackupTest {

    private fun newStore(): FileDatasetStore = FileDatasetStore(createTempDirectory("kv-backup").toFile())

    private fun seed(store: FileDatasetStore) {
        val datasets = AbilityDatasetStore(store)
        datasets.save("ds_a", tableDataset("A", "a,a\n1,1\n"))
        datasets.save("ds_b", tableDataset("B", "b,b\n2,2\n"))
        // 直接放一个非数据集条目（如跨会话记忆），验证快照覆盖全部 kv
        store.put("memory.ds_a", """[{"id":"f1","datasetId":"ds_a","kind":"followup","text":"问：1 答：1","source":"mcp","savedAt":1}]""")
    }

    private fun tableDataset(name: String, csv: String) = run {
        val table = com.stragent.core.csv.Csv.parseDelimitedFull(csv)
        com.stragent.core.dataset.Dataset(
            name = name,
            importedAt = 0L,
            columns = table.columns,
            rows = table.rows,
            columnTypes = table.columnTypes,
        )
    }

    @Test
    fun `导出导入往返-空库恢复后数据可用`() {
        val source = newStore()
        seed(source)
        val backup = File(createTempDirectory("kv-bak").toFile(), "backup.json")
        assertEquals(3, KvBackup.export(source, backup), "两个数据集 + 一条记忆")

        // 演练：全新空库（模拟「换机/换目录」）→ 导入 → 数据回来
        val target = newStore()
        assertEquals(3, KvBackup.import(target, backup), "空库全部条目都要写入")
        val restored = AbilityDatasetStore(target)
        assertTrue(restored.load("ds_a") != null, "数据集回来")
        assertTrue(restored.load("ds_b") != null)
        assertTrue(target.get("memory.ds_a")!!.contains("问：1"), "非数据集条目也回来")

        // 幂等：再导一次 → 0（不覆盖现有键）；replace=true → 全量覆盖
        assertEquals(0, KvBackup.import(target, backup), "默认不覆盖现有键")
        assertEquals(3, KvBackup.import(target, backup, replace = true), "replace 全量覆盖")
    }

    @Test
    fun `CLI解析-kv模式取第二个位置参数`() {
        assertEquals("kv-export", parseCli(listOf("kv-export", "/tmp/b.json")).mode)
        assertEquals("/tmp/b.json", parseCli(listOf("kv-export", "/tmp/b.json")).target)
        assertEquals(null, parseCli(listOf("kv-export")).target, "缺路径时 target 为 null（CLI 层提示）")
        assertEquals("serve", parseCli(listOf()).mode, "无参默认 serve 不受影响")
    }

    @Test
    fun `非法快照-如实报错不半写`() {
        val target = newStore()
        val bad = File(createTempDirectory("kv-bad").toFile(), "bad.json")
        bad.writeText("""{"version":1,"count":0}""", Charsets.UTF_8)
        val thrown = runCatching { KvBackup.import(target, bad) }
        assertTrue(thrown.isFailure, "缺 entries 必须报错")
        assertTrue(thrown.exceptionOrNull()!!.message!!.contains("entries"), "报错要说清原因")
        assertEquals(0, target.keys().size, "报错前不得写入任何条目")
        val (ok, _) = AbilityDatasetStore(target).saveWithEvictions(
            "ds_x", tableDataset("X", "x,x\n9,9\n"),
        )
        assertTrue(ok, "报错不影响后续正常写入")
    }
}
