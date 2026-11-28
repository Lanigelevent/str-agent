package com.stragent.data

import com.stragent.core.ports.DatasetStore
import com.stragent.core.semantic.TermCandidate
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 语义层存储的契约测试。
 *
 * 重点是「跨数据集」：语义层全局唯一键 `semantic.terms`，
 * 不按数据集内容指纹分桶——换数据不丢语义（S-W7 核心收口点）。
 *
 * 与 SessionMemoryStoreTest 同构：换一个 store 实例（同一目录）还能召回。
 */
class SemanticStoreTest {

    private class MapStore : DatasetStore {
        val map = mutableMapOf<String, String>()
        override fun put(key: String, value: String) { map[key] = value }
        override fun get(key: String): String? = map[key]
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): List<String> = map.keys.toList()
    }

    private fun tempDir() = Files.createTempDirectory("str-agent-sem").toFile()

    // ---- 基本 CRUD ----

    @Test
    fun `upsert 新术语直接入库`() {
        val store = SemanticStore(MapStore())
        val candidate = TermCandidate("销售额", "不含退货的净销售金额", "definition", "在下定义")
        assertTrue(store.upsert(candidate, "followup"))
        val terms = store.listTerms()
        assertEquals(1, terms.size)
        assertEquals("销售额", terms[0].name)
        assertEquals("不含退货的净销售金额", terms[0].definition)
        assertEquals(1, terms[0].version)
        assertEquals("followup", terms[0].source)
    }

    @Test
    fun `upsert 约束追加到已有术语`() {
        val store = SemanticStore(MapStore())
        // 先加定义
        store.upsert(TermCandidate("销售额", "不含退货的净销售", "definition", "在下定义"), "followup")
        // 再加约束
        assertTrue(store.upsert(TermCandidate("销售额", "还要扣掉折扣", "constraint", "调整计算"), "followup"))
        val term = store.findTerm("销售额")
        assertNotNull(term)
        assertEquals(2, term!!.version)
        assertEquals(1, term.constraints.size)
        assertTrue(term.constraints.contains("还要扣掉折扣"))
    }

    @Test
    fun `upsert 重复约束不更新`() {
        val store = SemanticStore(MapStore())
        store.upsert(TermCandidate("销售额", "不含退货", "constraint", "调整计算"), "followup")
        assertFalse(store.upsert(TermCandidate("销售额", "不含退货", "constraint", "调整计算"), "followup"))
        val term = store.findTerm("销售额")
        assertEquals(1, term!!.version) // 版本没涨
    }

    @Test
    fun `upsert 更短定义不算更具体`() {
        val store = SemanticStore(MapStore())
        store.upsert(TermCandidate("销售额", "这是一个很长的定义", "definition", "在下定义"), "manual")
        assertFalse(store.upsert(TermCandidate("销售额", "短", "definition", "在下定义"), "manual"))
        val term = store.findTerm("销售额")
        assertEquals(1, term!!.version) // 版本没涨
        assertEquals("这是一个很长的定义", term.definition) // 定义没被覆盖
    }

    // ---- 跨会话（跨实例）复现 ----

    @Test
    fun `跨会话复现——换一个实例落在同一目录仍能召回`() {
        val dir = tempDir()
        val first = SemanticStore(FileDatasetStore(dir))
        assertTrue(first.upsert(TermCandidate("销售额", "不含退货的净销售", "definition", "在下定义"), "followup"))
        // 模拟进程重启：全新 store 实例
        val second = SemanticStore(FileDatasetStore(dir))
        val term = second.findTerm("销售额")
        assertNotNull(term)
        assertEquals("不含退货的净销售", term!!.definition)
    }

    // ---- 手动添加 ----

    @Test
    fun `addTerm 手动添加完整术语`() {
        val store = SemanticStore(MapStore())
        assertTrue(store.addTerm("客单价", aliases = listOf("ARPU"), definition = "总销售额÷订单数", unit = "元", constraints = listOf("不含退款")))
        val term = store.findTerm("客单价")
        assertNotNull(term)
        assertEquals(1, term!!.aliases.size)
        assertEquals("元", term.unit)
        assertEquals(1, term.constraints.size)
    }

    @Test
    fun `addTerm 同名术语不覆盖`() {
        val store = SemanticStore(MapStore())
        store.addTerm("销售额", definition = "第一个定义")
        assertFalse(store.addTerm("销售额", definition = "第二个定义"))
        val term = store.findTerm("销售额")
        assertEquals("第一个定义", term!!.definition)
    }

    // ---- 别名管理 ----

    @Test
    fun `addAlias 给已有术语加别名`() {
        val store = SemanticStore(MapStore())
        store.addTerm("销售额", aliases = listOf("revenue"))
        assertTrue(store.addAlias("销售额", "sales"))
        val term = store.findTerm("销售额")
        assertEquals(2, term!!.aliases.size)
        assertEquals(2, term.version) // 版本涨了
    }

    @Test
    fun `addAlias 重复别名不更新`() {
        val store = SemanticStore(MapStore())
        store.addTerm("销售额", aliases = listOf("sales"))
        assertFalse(store.addAlias("销售额", "sales"))
    }

    // ---- 删除 ----

    @Test
    fun `removeTerm 删除存在的术语`() {
        val store = SemanticStore(MapStore())
        store.addTerm("销售额")
        val id = store.findTerm("销售额")!!.id
        assertTrue(store.removeTerm(id))
        assertNull(store.findTerm("销售额"))
    }

    @Test
    fun `removeTerm 不存在的 id 返回 false`() {
        val store = SemanticStore(MapStore())
        assertFalse(store.removeTerm("nonexistent"))
    }

    // ---- 清空 ----

    @Test
    fun `clear 清空全部`() {
        val store = SemanticStore(MapStore())
        store.addTerm("销售额")
        store.addTerm("利润")
        assertTrue(store.clear())
        assertTrue(store.listTerms().isEmpty())
    }

    // ---- 拒收 ----

    @Test
    fun `空名字拒收`() {
        val store = SemanticStore(MapStore())
        assertFalse(store.upsert(TermCandidate("", "定义", "definition", "在下定义")))
        assertFalse(store.addTerm(""))
    }

    @Test
    fun `超长定义拒收`() {
        val store = SemanticStore(MapStore())
        val longDef = "x".repeat(com.stragent.core.semantic.SEMANTIC_MAX_DEF_CHARS + 1)
        assertFalse(store.upsert(TermCandidate("销售额", longDef, "definition", "在下定义")))
    }

    // ---- JSON 损坏安全 ----

    @Test
    fun `JSON 损坏返回空列表而不是抛`() {
        val mapStore = MapStore()
        mapStore.map[SemanticStore.KEY] = "not valid json {{{"
        val store = SemanticStore(mapStore)
        assertTrue(store.listTerms().isEmpty())
    }

    // ---- 上限 ----

    @Test
    fun `超过上限拒收新术语`() {
        val mapStore = MapStore()
        val store = SemanticStore(mapStore)
        for (i in 1..com.stragent.core.semantic.SEMANTIC_MAX_TERMS) {
            store.addTerm("术语$i")
        }
        assertFalse(store.addTerm("多出来的"))
    }
}
