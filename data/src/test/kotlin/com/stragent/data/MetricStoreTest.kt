package com.stragent.data

import com.stragent.core.ports.DatasetStore
import com.stragent.core.semantic.MetricDef
import com.stragent.core.semantic.MetricTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 指标定义存储契约测试（T-055）。
 *
 * 重点：
 *  1. 往返保真（表达式/测试/字段别名全都活下来）；
 *  2. 版本门控（改内容升版、没改不升）；
 *  3. 写入即挡冲突（同名不同口径 / 同口径重复 / 内置不可覆盖）；
 *  4. 换 store 实例（同一目录）仍能读回——口径跨会话。
 */
class MetricStoreTest {

    private class MapStore : DatasetStore {
        val map = mutableMapOf<String, String>()
        override fun put(key: String, value: String) { map[key] = value }
        override fun get(key: String): String? = map[key]
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): List<String> = map.keys.toList()
    }

    private fun custom(
        id: String = "metric_net_sales",
        name: String = "净销售额",
        expression: String = "sum(销售额) - sum(退货额)",
        aliases: List<String> = listOf("net_sales"),
    ) = MetricDef(
        id = id,
        name = name,
        aliases = aliases,
        definition = "销售额扣除退货额",
        expression = expression,
        unit = "元",
        dimensions = listOf("地区"),
        tests = listOf(MetricTest("nonNegative")),
        owner = "tester",
        fields = mapOf(
            "销售额" to listOf("销售额"),
            "退货额" to listOf("退货额"),
            "地区" to listOf("地区", "region"),
        ),
    )

    @Test
    fun `新建往返保真且换实例仍可读`() {
        val kv = MapStore()
        val store = MetricStore(kv)
        val saved = store.upsert(custom())
        assertEquals(1, saved.version)
        assertEquals("tester", saved.owner)
        assertFalse(saved.builtin)

        val reopened = MetricStore(kv)
        val loaded = reopened.list().single()
        assertEquals("metric_net_sales", loaded.id)
        assertEquals("sum(销售额) - sum(退货额)", loaded.expression)
        assertEquals(listOf("地区"), loaded.dimensions)
        assertEquals(listOf(MetricTest("nonNegative")), loaded.tests)
        assertEquals(setOf("销售额", "退货额", "地区"), loaded.fields.keys)
        assertNotNull(reopened.find("net_sales"), "别名查找应命中")
        assertNull(reopened.find("毛利"), "内置指标不在用户存储里")
    }

    @Test
    fun `同 id 改内容升版、没改不升版`() {
        val store = MetricStore(MapStore())
        val saved = store.upsert(custom())
        val unchanged = store.upsert(custom())
        assertEquals(saved.version, unchanged.version, "内容没变不应升版")

        val changed = store.upsert(custom(expression = "sum(销售额) - sum(退货额) - sum(成本)"))
        assertEquals(2, changed.version, "口径变化应升版")
        assertEquals("sum(销售额) - sum(退货额) - sum(成本)", store.list().single().expression)
    }

    @Test
    fun `与内置同名不同口径被拒——写入即挡冲突`() {
        val store = MetricStore(MapStore())
        val divergent = custom(id = "metric_sales_net", name = "销售额", aliases = emptyList(), expression = "sum(销售额 - 退货额)")
        val error = assertFailsWith<IllegalArgumentException> { store.upsert(divergent) }
        assertTrue(error.message!!.contains("口径冲突"), error.message)
        assertTrue(store.list().isEmpty(), "冲突定义不能落盘")
    }

    @Test
    fun `与内置同口径的重复定义也被拒——不允许出现两份同名口径`() {
        val store = MetricStore(MapStore())
        val duplicate = custom(id = "metric_sales_copy", name = "销售额", aliases = emptyList(), expression = "sum(销售额)")
        val error = assertFailsWith<IllegalArgumentException> { store.upsert(duplicate) }
        assertTrue(error.message!!.contains("已存在同口径"), error.message)
    }

    @Test
    fun `两个用户指标别名相交且口径不同——第二个被拒`() {
        val store = MetricStore(MapStore())
        store.upsert(custom())
        val clash = custom(id = "metric_other", name = "净收入", aliases = listOf("net_sales"), expression = "sum(销售额)")
        val error = assertFailsWith<IllegalArgumentException> { store.upsert(clash) }
        assertTrue(error.message!!.contains("口径冲突"), error.message)
    }

    @Test
    fun `非法定义被拒——聚合、粒度、测试类型都兜住`() {
        val store = MetricStore(MapStore())
        assertTrue(
            assertFailsWith<IllegalArgumentException> { store.upsert(custom().copy(agg = "stddev")) }
                .message!!.contains("不合法"),
        )
        assertTrue(
            assertFailsWith<IllegalArgumentException> { store.upsert(custom().copy(grain = "week")) }
                .message!!.contains("粒度"),
        )
        assertTrue(
            assertFailsWith<IllegalArgumentException> { store.upsert(custom().copy(tests = listOf(MetricTest("range", value = 0.0)))) }
                .message!!.contains("range"),
        )
    }

    @Test
    fun `内置指标不能通过用户入口写入`() {
        val store = MetricStore(MapStore())
        val error = assertFailsWith<IllegalArgumentException> { store.upsert(custom().copy(builtin = true)) }
        assertTrue(error.message!!.contains("内置"), error.message)
    }

    @Test
    fun `删除与清空——内置指标不受影响`() {
        val store = MetricStore(MapStore())
        val saved = store.upsert(custom())
        assertTrue(store.remove(saved.id))
        assertFalse(store.remove(saved.id), "重复删除应为 false")
        assertTrue(store.list().isEmpty())

        store.upsert(custom())
        store.clear()
        assertTrue(store.list().isEmpty())
        assertEquals(10, com.stragent.core.semantic.MetricCatalog.BUILTINS.size)
    }

    @Test
    fun `用户指标数量有上限`() {
        val store = MetricStore(MapStore())
        repeat(MetricStore.MAX_USER_METRICS) { index ->
            store.upsert(
                custom(
                    id = "m_$index",
                    name = "指标$index",
                    aliases = emptyList(),
                    expression = "sum(销售额) + $index",
                ),
            )
        }
        assertEquals(MetricStore.MAX_USER_METRICS, store.list().size)
        val error = assertFailsWith<IllegalArgumentException> {
            store.upsert(custom(id = "overflow", name = "溢出指标", aliases = emptyList(), expression = "sum(销售额) + 999"))
        }
        assertTrue(error.message!!.contains("上限"), error.message)
    }
}
