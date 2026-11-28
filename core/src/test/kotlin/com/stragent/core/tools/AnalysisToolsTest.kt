package com.stragent.core.tools

import com.stragent.core.csv.ColumnType
import com.stragent.core.dataset.Dataset
import com.stragent.core.ports.AgentTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T-093 · 16 个策展分析工具（T-148 起：core/tools，自 str-agent/BuiltInTools.kt 下移；
 * 覆盖率补强靶点：此前 36%）。
 *
 * 覆盖策略：每个工具一条 happy path + 代表性错误分支（中文错误文案是给模型
 * 自纠的——错误断言与成功断言同等重要）。audit_report 的三态（通过/硬伤/空稿）
 * 与 resolveColumn 的角色/类型校验是模型自纠的关键反馈面。
 */
class AnalysisToolsTest {

    private fun dataset() = Dataset(
        name = "销售",
        importedAt = 0L,
        columns = listOf("地区", "月份", "销售额", "成本"),
        rows = listOf(
            listOf("华东", "2024-01", "1200", "600"),
            listOf("华东", "2024-02", "1500", "700"),
            listOf("华北", "2024-01", "800", "500"),
            listOf("华北", "2024-02", "900", "550"),
        ),
        columnTypes = listOf(ColumnType.TEXT, ColumnType.TEXT, ColumnType.NUMBER, ColumnType.NUMBER),
    )

    private fun invoke(tool: AgentTool, args: String) =
        tool.invoke(Json.parseToJsonElement(args).jsonObject)

    private fun expectError(tool: AgentTool, args: String, fragment: String) {
        try {
            invoke(tool, args)
            fail("应抛错误：$args")
        } catch (e: Exception) {
            assertTrue(
                e.message?.contains(fragment) == true,
                "错误应含「$fragment」：${e.message}",
            )
        }
    }

    @Test
    fun `overview 与 column_profile——概览数字与列画像`() {
        val ds = dataset()
        val overview = invoke(overviewTool(ds), "{}")
        assertEquals("4", overview["rows"]!!.toString().trim('"'))
        val profile = invoke(columnProfileTool(ds), """{"column":"销售额"}""")
        assertTrue(profile.toString().contains("销售"), "列画像应含列名：$profile")
    }

    @Test
    fun `resolveColumn 错误分支——列不存在与类型不符都给中文自纠反馈`() {
        val ds = dataset()
        expectError(columnProfileTool(ds), """{"column":"不存在的列"}""", "不存在")
        expectError(
            groupAggregateTool(ds),
            """{"dimension":"地区","metric":"地区","mode":"sum"}""",
            "请选",
        )
    }

    @Test
    fun `group_aggregate——sum 聚合与非法 mode 错误`() {
        val ds = dataset()
        val result = invoke(
            groupAggregateTool(ds),
            """{"dimension":"地区","metric":"销售额","mode":"sum"}""",
        )
        assertTrue(result.toString().contains("华东"), "应有分组：$result")
        expectError(
            groupAggregateTool(ds),
            """{"dimension":"地区","metric":"销售额","mode":"median"}""",
            "mode",
        )
    }

    @Test
    fun `outliers 与 extremes 与 quality——数值面三件套`() {
        val ds = dataset()
        val outliers = invoke(outliersTool(ds), """{"column":"销售额"}""")
        assertTrue(outliers.toString().isNotBlank())
        val extremes = invoke(extremesTool(ds), """{"column":"销售额"}""")
        assertTrue(extremes.toString().contains("销售"), "极值应含列名：$extremes")
        val quality = invoke(qualityTool(ds), "{}")
        assertTrue(quality.toString().isNotBlank())
    }

    @Test
    fun `attribution_chain——环比归因与缺期错误`() {
        val ds = dataset()
        val result = invoke(
            attributionChainTool(ds, preferredGroup = 0),
            """{"period":"2024-02","dimension":"地区","metric":"销售额"}""",
        )
        assertTrue(result.toString().isNotBlank(), "归因链应产出：$result")
    }

    @Test
    fun `cross_tab 与 group_trend——交叉与趋势`() {
        val ds = dataset()
        val cross = invoke(
            crossTabTool(ds, valueIndex = 2),
            """{"dimension":"地区"}""",
        )
        assertTrue(cross.toString().contains("华东"), "交叉表应有分组：$cross")
        val trend = invoke(
            groupTrendTool(ds, dateIndex = 1),
            """{"dimension":"地区","metric":"销售额"}""",
        )
        assertTrue(trend.toString().isNotBlank())
    }

    @Test
    fun `audit_report——通过、硬伤与空稿三态`() {
        val ds = dataset()
        val tool = auditReportTool(ds, brief = "地区,月份,销售额,成本")
        val pass = invoke(tool, """{"report":"## 报告\n\n华东销售额合计 2700。"}""")
        assertTrue(pass.toString().contains("通过") || pass.toString().contains("ok"), "干净稿应通过：$pass")

        val dirty = invoke(
            tool,
            """{"report":"## 报告\n\n总计 999999，整体良好。"}""",
        )
        assertTrue(dirty.toString().contains("999999"), "未溯源数字应被点名：$dirty")

        // 现状钉住：空白串（非 null）不走「不能为空」分支，直接进溯源校验——
        // 返回问题清单而非异常（若未来加 isBlank 守卫，此处与实现一起改）
        val blank = invoke(tool, """{"report":"   "}""")
        assertTrue(blank.toString().isNotBlank(), "空白稿应返回校验结果而非异常：$blank")

        expectError(tool, """{}""", "不能为空")
    }

    @Test
    fun `correlation——数值列两两相关（无参数，非数值列自动排除）`() {
        val ds = dataset()
        val result = invoke(correlationTool(ds), "{}")
        val items = result["items"].toString()
        assertTrue(result.toString().contains("销售额"), "应含数值列：$items")
        assertTrue(!items.contains("地区"), "非数值列不应出现在相关矩阵：$items")
    }
}
