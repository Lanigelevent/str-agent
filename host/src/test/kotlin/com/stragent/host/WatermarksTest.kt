package com.stragent.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * T-174 · 水位线快照测试。
 *
 * 覆盖：每个维度带 current/limit/口径；没有硬上限的维度 `limit` 缺省（不写 0）；
 * 驾驶舱总览带水位线视图。
 */
class WatermarksTest {

    private fun lines(snapshot: kotlinx.serialization.json.JsonObject): List<kotlinx.serialization.json.JsonObject> =
        (snapshot["lines"] as JsonArray).map { it.jsonObject }

    @Test
    fun `水位线——每行带 current、limit 与口径说明`() {
        val snapshot = Watermarks.snapshot(
            datasets = null,
            bridge = null,
            metrics = null,
            semantic = null,
            toolCount = 42,
            tier2Available = true,
        )
        val rows = lines(snapshot)
        assertTrue(rows.any { it["metric"]!!.jsonPrimitive.content == "tools.exposed" }, "工具条数：$rows")
        assertTrue(rows.any { it["metric"]!!.jsonPrimitive.content == "engine.tier2" }, "tier-2 可用性：$rows")
        assertTrue(rows.any { it["metric"]!!.jsonPrimitive.content == "jvm.heapUsed" }, "堆水位：$rows")
        rows.forEach { row ->
            assertTrue(row.containsKey("current"), "每行都要有当前值：$row")
            assertTrue(row.containsKey("unit"), "每行都要有单位：$row")
            assertTrue(row["note"]!!.jsonPrimitive.content.isNotBlank(), "每行都要有口径说明：$row")
        }
        val tools = rows.first { it["metric"]!!.jsonPrimitive.content == "tools.exposed" }
        assertEquals(42, tools["current"]!!.jsonPrimitive.content.toInt())
        assertTrue(!tools.containsKey("limit"), "没有硬上限的维度不写 limit（不写 0）：$tools")
    }

    @Test
    fun `水位线——驾驶舱总览带该视图`() {
        val provider = UiDashboardData(
            com.stragent.data.AbilityDatasets(), RecentReports(), "1.1.0",
            watermarks = { Watermarks.snapshot(null, null, null, null, 50, false) },
        )
        val overview = provider.overview()
        val watermarks = overview["watermarks"]!!.jsonObject
        assertTrue(watermarks.containsKey("lines"), "总览应带水位线：$overview")
    }
}
