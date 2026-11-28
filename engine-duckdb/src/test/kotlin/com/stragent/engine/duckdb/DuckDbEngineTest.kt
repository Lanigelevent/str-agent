package com.stragent.engine.duckdb

import com.stragent.core.csv.Csv
import com.stragent.core.dataset.datasetFromParsed
import com.stragent.core.engine.InMemoryEngine
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * tier-2（DuckDB）与 tier-1（内存）的口径一致性单测。
 *
 * 这里的每个用例都是「同一个数不能有两个答案」的守卫：
 * 数值解析规则（千分位 / % / 空格 / 脏值）、空标签、分组计数、列画像统计量，
 * 只要有一处对不齐，报告里的数字就会在两档之间漂移。
 */
class DuckDbEngineTest {

    private val csv = """
        地区,销售额,备注
        华东,100.5,a
        华北,200,b
        华东,50.5,c
        ,300,d
        华南,abc,e
        西南,"1,234",f
        华中,12%,g
        华中, 56 ,h
        华东,,i
    """.trimIndent()

    private fun engines(): Pair<InMemoryEngine, DuckDbEngine> {
        val table = Csv.parseDelimited(csv)
        val dataset = datasetFromParsed("t", 1, table)
        return InMemoryEngine(dataset) to DuckDbEngine.fromRows(table.columns, table.rows)
    }

    @Test
    fun `聚合结果与 tier-1 完全一致`() {
        val (tier1, tier2) = engines()
        tier2.use {
            val a = tier1.aggregateBy(0, 1, "sum")
            val b = tier2.aggregateBy(0, 1, "sum")
            assertEquals(a.size, b.size)
            a.zip(b).forEach { (x, y) ->
                assertEquals(x.label, y.label, "分组标签不一致")
                assertEquals(x.count, y.count, "分组计数不一致：${x.label}")
                assertTrue(abs(x.value - y.value) <= 1e-9 * maxOf(1.0, abs(x.value)), "分组数值不一致：${x.label}")
                if (x.share.isNaN() || y.share.isNaN()) {
                    assertTrue(x.share.isNaN() && y.share.isNaN(), "share 有效性不一致：${x.label}")
                } else {
                    assertTrue(abs(x.share - y.share) <= 1e-9 * maxOf(1.0, abs(x.share)), "占比不一致：${x.label}")
                }
            }
        }
    }

    @Test
    fun `均值模式与 tier-1 完全一致`() {
        val (tier1, tier2) = engines()
        tier2.use {
            val a = tier1.aggregateBy(0, 1, "avg")
            val b = tier2.aggregateBy(0, 1, "avg")
            assertEquals(a.size, b.size)
            a.zip(b).forEach { (x, y) ->
                assertEquals(x.label, y.label)
                assertEquals(x.count, y.count)
                assertTrue(abs(x.value - y.value) <= 1e-9 * maxOf(1.0, abs(x.value)), "均值不一致：${x.label}")
            }
        }
    }

    @Test
    fun `数值列画像与 tier-1 完全一致`() {
        val (tier1, tier2) = engines()
        tier2.use {
            val x = tier1.profileColumn(1)!!
            val y = tier2.profileColumn(1)!!
            assertEquals(x.type, y.type)
            assertEquals(x.count, y.count, "参与统计的样本数不一致")
            assertEquals(x.missing, y.missing, "缺失数不一致")
            assertTrue(abs((x.min ?: 0.0) - (y.min ?: 0.0)) < 1e-9, "min 不一致")
            assertTrue(abs((x.max ?: 0.0) - (y.max ?: 0.0)) < 1e-9, "max 不一致")
            assertTrue(abs((x.sum ?: 0.0) - (y.sum ?: 0.0)) <= 1e-9 * maxOf(1.0, abs(x.sum ?: 0.0)), "sum 不一致")
            assertTrue(abs((x.mean ?: 0.0) - (y.mean ?: 0.0)) <= 1e-9, "mean 不一致")
            assertTrue(abs((x.median ?: 0.0) - (y.median ?: 0.0)) <= 1e-9, "median 不一致")
        }
    }

    @Test
    fun `文本列画像与 tier-1 完全一致`() {
        val (tier1, tier2) = engines()
        tier2.use {
            val x = tier1.profileColumn(0)!!
            val y = tier2.profileColumn(0)!!
            assertEquals(x.type, y.type)
            assertEquals(x.count, y.count)
            assertEquals(x.missing, y.missing)
            assertEquals(x.distinct, y.distinct, "去重数不一致")
            val xt = x.topValues.orEmpty()
            val yt = y.topValues.orEmpty()
            assertEquals(xt.size, yt.size)
            xt.zip(yt).forEach { (p, q) ->
                assertEquals(p.value, q.value)
                assertEquals(p.count, q.count)
            }
        }
    }

    @Test
    fun `干净 CSV 的文件路径与内存路径结果一致`() {
        val dir = kotlin.io.path.createTempDirectory("str-agent-duckdb-test").toFile()
        val file = dir.resolve("clean.csv")
        file.writeText("g,v\na,1\nb,2\na,3\n", Charsets.UTF_8)
        DuckDbEngine.fromCsvFile(file).use { engine ->
            assertEquals(3, engine.rowCount)
            val items = engine.aggregateBy(0, 1, "sum")
            assertEquals(2, items.size)
            assertEquals("a", items[0].label)
            assertEquals(4.0, items[0].value, 1e-9)
            assertEquals(2, items[0].count)
            assertEquals("b", items[1].label)
            assertEquals(2.0, items[1].value, 1e-9)
        }
        file.delete()
        dir.delete()
    }
}
