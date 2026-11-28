package com.stragent.data

import com.stragent.core.dataset.Dataset
import com.stragent.core.ports.PrefsStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryTest {
    private fun tempDir() = Files.createTempDirectory("str-agent-lib").toFile()

    private class MapPrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private fun sampleCsv() = "月份,销售额,订单数\n2023-01,100.5,10\n2023-02,220.0,25\n2023-03,,13\n"

    private fun datasetOf(name: String) = datasetFromCsv(sampleCsv(), name, 12345L)!!

    @Test
    fun `DatasetLibrary 导入-重读-重命名-移动-删除 全链`() {
        val prefs = MapPrefs()
        val library = DatasetLibrary(prefs, FileDatasetStore(tempDir()))
        // 导入
        val result = library.saveDatasetFile(datasetOf("门店 A"), sampleCsv())
        assertTrue(result.persisted)
        // 重名追加序号
        val dup = library.saveDatasetFile(datasetOf("门店 A"), sampleCsv())
        val metas = library.listDatasetMetas()
        assertEquals(2, metas.size)
        // TS unshift 口径：新导入的排最前
        assertEquals("门店 A (2)", metas[0].name)
        assertEquals("门店 A", metas[1].name)
        // 重读：行列与类型确定性重建
        val loaded = library.loadDataset(result.id)!!
        assertEquals(3, loaded.dataset.rows.size)
        assertEquals(listOf("月份", "销售额", "订单数"), loaded.dataset.columns)
        assertEquals("门店 A", loaded.meta.name)
        assertEquals(12345L, loaded.dataset.importedAt)
        // 重命名
        assertTrue(library.renameDataset(result.id, " 门店 B "))
        assertEquals("门店 B", library.listDatasetMetas().first { it.id == result.id }.name)
        // 分类
        val cat = library.createCategory(" 门店 ")!!
        library.moveDataset(result.id, cat.id)
        assertEquals(cat.id, library.listDatasetMetas().first { it.id == result.id }.categoryId)
        assertEquals("门店", library.categoryName(cat.id))
        assertEquals("未分类", library.categoryName(""))
        // 删分类 → 数据移到未分类
        library.deleteCategory(cat.id)
        assertEquals("", library.listDatasetMetas().first { it.id == result.id }.categoryId)
        // 删除数据集：本体与索引都没了
        library.deleteDataset(result.id)
        assertNull(library.loadDataset(result.id))
        assertEquals(1, library.listDatasetMetas().size)
    }

    @Test
    fun `ProjectStore CRUD 与 turns 截断保报告`() {
        val prefs = MapPrefs()
        val store = ProjectStore(prefs, FileDatasetStore(tempDir()))
        val project = store.createProject("ds-1", "门店 A 分析")!!
        // 默认排序：最近活动在前
        val p2 = store.createProject("ds-2", "另一份")!!
        assertEquals(listOf(p2.id, project.id), store.listProjects().map { it.id })
        assertTrue(store.findProjectByDataset("ds-1")!!.id == project.id)
        // turns 截断：报告永远保留 + 尾部偶数条（TS trimTurns：孤儿答案与悬空问题剔除）
        val turns = buildList {
            add(ProjectTurn("assistant", "报告"))
            for (i in 1..9) add(ProjectTurn(if (i % 2 == 1) "user" else "assistant", "t$i"))
        }
        store.saveProject(project.copy(turns = turns))
        val saved = store.loadProject(project.id)!!
        // tail=takeLast(4)=[a,u,a,u] → 首条 assistant 是孤儿答案被剔 → [u,a,u] → 末尾悬空 user 被剔 → [u,a]
        assertEquals(3, saved.turns.size)
        assertEquals("报告", saved.turns.first().content)
        assertEquals("assistant", saved.turns.last().role, "末尾悬空的问题不落盘")
        assertEquals("user", saved.turns[1].role, "不能以孤儿答案开头")
        // 删数据集连带删项目
        store.deleteProjectsByDataset("ds-1")
        assertNull(store.loadProject(project.id))
        // 清空
        store.clearAll()
        assertEquals(0, store.listProjects().size)
    }

    @Test
    fun `ProjectStore 历史版本拆键存储与体积收敛`() {
        val prefs = MapPrefs()
        val store = ProjectStore(prefs, FileDatasetStore(tempDir()))
        val project = store.createProject("ds", "p")!!
        // 存 3 份历史：每份 600K 字符 × 3 > 1.5MB 预算 → 丢最旧（3×(900K) 只装得下 1 份，2×600K 可装 2 份）
        val reports = (1..3).map { i ->
            SavedReport("standard", "standard", 0, savedAt = i.toLong(), text = "报告$i".repeat(200_000))
        }
        store.saveProject(project, savedReports = reports)
        val loaded = store.loadReports(project.id)!!
        assertEquals(2, loaded.size, "超预算应收敛")
        assertTrue(loaded.first().text.startsWith("报告3"), "新的在前")
        // 显式空数组 = 清空
        store.saveProject(store.loadProject(project.id)!!, savedReports = emptyList())
        assertNull(store.loadReports(project.id))
    }

    @Test
    fun `CaliberStore 三条硬规则与排序`() {
        val prefs = MapPrefs()
        val store = CaliberStore(prefs).apply {
            // T-159：单调计数器时钟——savedAt 严格递增，降序断言免 sleep、免疫墙钟并列
            var tick = 0L
            clock = { ++tick }
        }
        // 空文本不加
        assertFalse(store.addCaliberNote("p1", "   "))
        // 超长不加
        assertFalse(store.addCaliberNote("p1", "x".repeat(201)))
        // 正常加
        assertTrue(store.addCaliberNote("p1", "华东不含上海", source = "followup"))
        // 完全重复不加
        assertFalse(store.addCaliberNote("p1", " 华东不含上海 "))
        // 降序排列（新的在前）
        assertTrue(store.addCaliberNote("p1", "销售额要扣退货"))
        val notes = store.listCaliberNotes("p1")
        assertEquals(2, notes.size)
        assertEquals("销售额要扣退货", notes.first().text)
        assertEquals("manual", notes.first().source)
        // 删除不存在的 id 返回 false
        assertFalse(store.removeCaliberNote("p1", "nope"))
        assertTrue(store.removeCaliberNote("p1", notes.first().id))
        assertEquals(1, store.listCaliberNotes("p1").size)
    }

    @Test
    fun `formatImportTime 精确到分`() {
        // 2026-10-24 09:05 本地时区
        val millis = java.util.Calendar.Builder().setDate(2026, 9, 24).setTimeOfDay(9, 5, 30).build().timeInMillis
        val text = formatImportTime(millis)
        assertTrue(text.matches(Regex("\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}")), text)
        assertTrue(text.endsWith("09:05"), text)
    }
}
