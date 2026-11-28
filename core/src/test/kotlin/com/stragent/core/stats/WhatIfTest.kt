package com.stragent.core.stats

import com.stragent.core.csv.Csv
import com.stragent.core.csv.ColumnType
import com.stragent.core.dataset.Dataset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class WhatIfTest {

    private fun makeDataset(columns: List<String>, rows: List<List<String>>): Dataset {
        val types = columns.mapIndexed { i, _ ->
            val numericCount = rows.count { Csv.parseNumber(it[i]) != null }
            if (numericCount > 0 && numericCount.toDouble() / rows.size >= 0.85) ColumnType.NUMBER
            else ColumnType.TEXT
        }
        return Dataset(
            name = "test",
            importedAt = 1,
            columns = columns,
            rows = rows,
            columnTypes = types,
        )
    }

    @Test
    fun multiply_10pct_increase_correct_delta() {
        val ds = makeDataset(
            listOf("地区", "销售额"),
            listOf(
                listOf("华东", "100"),
                listOf("华北", "200"),
                listOf("华东", "50"),
            ),
        )
        val result = whatIf(ds, 1, WhatIfOperation.MULTIPLY, 1.1, -1)
        assertEquals("销售额", result.column)
        assertEquals("multiply", result.operation)
        assertEquals(1.1, result.factor)
        assertEquals(1, result.before.size)
        assertEquals(1, result.after.size)
        // before sum = 350, after sum = 385
        assertEquals(350.0, result.before[0].sum)
        assertEquals(385.0, result.after[0].sum)
        // delta = 35, pct = 10%
        assertEquals(35.0, result.delta[0].sumDelta)
        assertTrue(kotlin.math.abs(result.delta[0].sumDeltaPct - 10.0) < 0.01)
    }

    @Test
    fun add_fixed_amount_correct_delta() {
        val ds = makeDataset(
            listOf("val"),
            listOf(listOf("10"), listOf("20"), listOf("30")),
        )
        val result = whatIf(ds, 0, WhatIfOperation.ADD, 5.0, -1)
        // before sum = 60, after sum = 75
        assertEquals(60.0, result.before[0].sum)
        assertEquals(75.0, result.after[0].sum)
        assertEquals(15.0, result.delta[0].sumDelta)
    }

    @Test
    fun set_all_values_to_constant() {
        val ds = makeDataset(
            listOf("val"),
            listOf(listOf("10"), listOf("20"), listOf("30")),
        )
        val result = whatIf(ds, 0, WhatIfOperation.SET, 100.0, -1)
        // all set to 100, sum = 300
        assertEquals(300.0, result.after[0].sum)
        assertEquals(100.0, result.after[0].avg)
        assertEquals(100.0, result.after[0].min)
        assertEquals(100.0, result.after[0].max)
    }

    @Test
    fun groupBy_produces_per_group_comparison() {
        val ds = makeDataset(
            listOf("地区", "销售额"),
            listOf(
                listOf("华东", "100"),
                listOf("华北", "200"),
                listOf("华东", "50"),
            ),
        )
        val result = whatIf(ds, 1, WhatIfOperation.MULTIPLY, 2.0, 0)
        // two groups: 华东 and 华北
        assertEquals(2, result.before.size)
        assertEquals(2, result.after.size)
        assertEquals(2, result.delta.size)
        // 华东: before sum = 150, after = 300
        val east = result.before.first { it.group == "华东" }
        assertEquals(150.0, east.sum)
        val eastAfter = result.after.first { it.group == "华东" }
        assertEquals(300.0, eastAfter.sum)
        // 华北: before sum = 200, after = 400
        val north = result.before.first { it.group == "华北" }
        assertEquals(200.0, north.sum)
    }

    @Test
    fun deterministic_same_input_same_output() {
        val ds = makeDataset(
            listOf("val"),
            listOf(listOf("10"), listOf("20"), listOf("30")),
        )
        val r1 = whatIf(ds, 0, WhatIfOperation.MULTIPLY, 1.5, -1)
        val r2 = whatIf(ds, 0, WhatIfOperation.MULTIPLY, 1.5, -1)
        assertEquals(r1.before[0].sum, r2.before[0].sum)
        assertEquals(r1.after[0].sum, r2.after[0].sum)
        assertEquals(r1.delta[0].sumDelta, r2.delta[0].sumDelta)
    }

    @Test
    fun findWhatIfColumn_specified_column_found() {
        val ds = makeDataset(listOf("name", "sales"), listOf(listOf("A", "100")))
        val idx = findWhatIfColumn(ds, "sales")
        assertEquals(1, idx)
    }

    @Test
    fun findWhatIfColumn_text_column_returns_minus2() {
        val ds = makeDataset(listOf("name", "sales"), listOf(listOf("A", "100")))
        val idx = findWhatIfColumn(ds, "name")
        assertEquals(-2, idx)
    }

    @Test
    fun findWhatIfColumn_missing_returns_minus3() {
        val ds = makeDataset(listOf("name", "sales"), listOf(listOf("A", "100")))
        val idx = findWhatIfColumn(ds, "nonexistent")
        assertEquals(-3, idx)
    }

    @Test
    fun findWhatIfColumn_auto_picks_first_numeric() {
        val ds = makeDataset(listOf("name", "sales", "cost"), listOf(listOf("A", "100", "50")))
        val idx = findWhatIfColumn(ds, null)
        assertEquals(1, idx)
    }

    @Test
    fun skips_non_numeric_values() {
        val ds = makeDataset(
            listOf("val"),
            listOf(listOf("10"), listOf("abc"), listOf(""), listOf("30")),
        )
        val result = whatIf(ds, 0, WhatIfOperation.MULTIPLY, 2.0, -1)
        // only 2 valid values: 10 and 30
        assertEquals(2, result.before[0].count)
        assertEquals(40.0, result.before[0].sum)
    }
}
