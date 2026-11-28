package com.stragent.host

import com.stragent.data.McpStdioServer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 双轨路由的**宿主接线**验证（T-013 余量：`host` 侧按 `EngineRouter` 真实路由）。
 *
 * 接线前的状态是「`EngineRouter` 只有单测在跑，两个按路径分析的 CSV 工具写死 tier-2」。
 * 本套件的断言都在证明路由**真的被咨询了**，而不是又一层纸面能力：
 *  ① 同一支工具，小文件走 tier-1、大文件走 tier-2（反向对照：写死任一档都过不了这条）；
 *  ② 同一份 CSV 指名两档，数字逐项相等（双轨口径不漂移，工具语义不变、只换后端）；
 *  ③ 超阈值却只能走 tier-1 时，`truncationNotice` 必须显式出现（不许静默降级）；
 *  ④ 决策依据（阈值 / 行数 / tier-2 可用性）随结果一起回报，「数字从哪来」可回答。
 */
class CsvTierRoutingTest {

    private fun csv(fileName: String, rows: Int, groups: Int = 5): File {
        val file = File(createTempDirectory("str-agent-routing").toFile(), fileName)
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write("地区,销售额\n")
            for (i in 0 until rows) writer.write("G${i % groups},$i\n")
        }
        return file
    }

    private fun call(bootstrap: StrAgentHostBootstrap, tool: String, args: String): JsonObject {
        val input = ByteArrayInputStream(
            ("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$args}}""" + "\n")
                .toByteArray(Charsets.UTF_8),
        )
        val output = ByteArrayOutputStream()
        McpStdioServer(bootstrap.mcpBridge, input, output).serve()
        val frame = Json.parseToJsonElement(output.toString(Charsets.UTF_8).trim()).jsonObject
        assertTrue(frame.containsKey("result"), "$tool 调用应成功：$frame")
        val content = frame["result"]!!.jsonObject
        return Json.parseToJsonElement(
            content["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        ).jsonObject
    }

    private fun errorOf(bootstrap: StrAgentHostBootstrap, tool: String, args: String): String {
        val input = ByteArrayInputStream(
            ("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$args}}""" + "\n")
                .toByteArray(Charsets.UTF_8),
        )
        val output = ByteArrayOutputStream()
        McpStdioServer(bootstrap.mcpBridge, input, output).serve()
        val frame = Json.parseToJsonElement(output.toString(Charsets.UTF_8).trim()).jsonObject
        // T-079：执行错误在 isError 结果里（响应形状 success），协议错误仍在 frame["error"]
        frame["error"]?.let { return it.jsonObject["message"]!!.jsonPrimitive.content }
        val result = frame["result"]?.jsonObject
        assertTrue(
            result?.get("isError")?.jsonPrimitive?.content == "true",
            "该帧既非协议错误也非 isError 结果：$frame",
        )
        return result!!["structuredContent"]!!.jsonObject["error"]!!.jsonPrimitive.content
    }

    private fun num(result: JsonObject, key: String): Double =
        result[key]!!.jsonPrimitive.content.toDouble()

    private fun itemsOf(result: JsonObject): List<Triple<String, Double, Int>> =
        result["items"]!!.jsonArray.map {
            val o = it.jsonObject
            Triple(
                o["label"]!!.jsonPrimitive.content,
                o["value"]!!.jsonPrimitive.content.toDouble(),
                o["count"]!!.jsonPrimitive.content.toInt(),
            )
        }

    @Test
    fun `① 同一支工具：小文件走 tier-1，大文件走 tier-2`() {
        val small = csv("small.csv", rows = 200)
        val big = csv("big.csv", rows = 12_000)
        val bootstrap = StrAgentHostBootstrap.create(createTempDirectory("routing-home").toFile())
        try {
            val smallArgs = """{"csvPath":${jsonString(small.absolutePath)},"dimension":"地区","metric":"销售额"}"""
            val smallResult = call(bootstrap, "stragent_aggregate_csv", smallArgs)
            assertEquals("in-memory", smallResult["engine"]!!.jsonPrimitive.content, "200 行该走 tier-1")
            assertEquals("in_memory", smallResult["tier"]!!.jsonPrimitive.content)
            assertEquals(200, smallResult["rowCount"]!!.jsonPrimitive.content.toInt())

            val bigArgs = """{"csvPath":${jsonString(big.absolutePath)},"dimension":"地区","metric":"销售额"}"""
            val bigResult = call(bootstrap, "stragent_aggregate_csv", bigArgs)
            assertEquals("duckdb", bigResult["engine"]!!.jsonPrimitive.content, "1.2 万行超过阈值该走 tier-2")
            assertEquals("columnar", bigResult["tier"]!!.jsonPrimitive.content)
            assertEquals(12_000, bigResult["rowCount"]!!.jsonPrimitive.content.toInt())

            // 决策依据随结果回报（不是只报一个档位名）
            val routing = bigResult["routing"]!!.jsonObject
            assertEquals("auto", routing["requested"]!!.jsonPrimitive.content)
            assertEquals(10_000, routing["threshold"]!!.jsonPrimitive.content.toInt())
            // 路由级计数在阈值处早停：不为选档把 120 万行数完全程。
            // 「不止于此」由 rowsCountCapped 明说，工具口径的行数看 rowCount。
            assertEquals(10_001, routing["dataRows"]!!.jsonPrimitive.content.toInt())
            assertTrue(
                routing["rowsCountCapped"]!!.jsonPrimitive.content.toBoolean(),
                "早停必须如实回报，不能假装数到了 10001 就是全量",
            )
            assertEquals(12_000, bigResult["rowCount"]!!.jsonPrimitive.content.toInt(), "tier-2 看到全量")
            assertTrue(routing["tier2Available"]!!.jsonPrimitive.content.toBoolean())

            // tier-1 的数字与解析解一致（0..199 分 5 组，每组 40 个）
            assertEquals(
                (0 until 200).sumOf { it.toDouble() },
                num(smallResult, "grandTotal"),
                1e-6,
                "tier-1 合计应等于解析解",
            )
            assertEquals(5, itemsOf(smallResult).size, "5 个分组，不该出现「其他」")
            assertNull(smallResult["truncationNotice"], "tier-1 看到全量数据时不该有截断提示")
        } finally {
            bootstrap.close()
            small.delete()
            big.delete()
        }
    }

    @Test
    fun `② 同一份 CSV 指名两档：数字逐项相等（口径不随档位漂移）`() {
        val file = csv("parity.csv", rows = 300, groups = 7)
        val bootstrap = StrAgentHostBootstrap.create(createTempDirectory("routing-home2").toFile())
        try {
            val base = """"csvPath":${jsonString(file.absolutePath)},"dimension":"地区","metric":"销售额","mode":"sum","topN":4"""
            val tier1 = call(bootstrap, "stragent_aggregate_csv", """{$base,"engine":"memory"}""")
            val tier2 = call(bootstrap, "stragent_aggregate_csv", """{$base,"engine":"columnar"}""")
            assertEquals("in-memory", tier1["engine"]!!.jsonPrimitive.content)
            assertEquals("duckdb", tier2["engine"]!!.jsonPrimitive.content)

            val a = itemsOf(tier1)
            val b = itemsOf(tier2)
            assertEquals(b.size, a.size, "两档分组数应一致（含「其他 N 项」）")
            assertEquals(
                a.map { it.first },
                b.map { it.first },
                "并列时的先后序也应一致（tier-1 首见序 ↔ tier-2 首行序）",
            )
            a.zip(b).forEach { (x, y) ->
                assertEquals(x.second, y.second, 1e-6, "组 ${x.first} 的值两档应相等")
                assertEquals(x.third, y.third, "组 ${x.first} 的样本量两档应相等")
            }
            assertEquals(num(tier1, "grandTotal"), num(tier2, "grandTotal"), 1e-6, "合计两档应相等")
            assertEquals(
                tier1["truncatedToTopN"]!!.jsonPrimitive.content,
                tier2["truncatedToTopN"]!!.jsonPrimitive.content,
                "topN 截断标记两档应一致",
            )
            assertTrue(a.last().first.startsWith("其他 "), "topN=4 < 7 组，应汇总出「其他 N 项」")
        } finally {
            bootstrap.close()
            file.delete()
        }
    }

    @Test
    fun `③ 只能走 tier-1 而数据超阈值时，必须显式回报截断提示`() {
        val file = csv("truncate.csv", rows = 12_000)
        val bootstrap = StrAgentHostBootstrap.create(createTempDirectory("routing-home3").toFile())
        try {
            // engine=memory = 报告路径口径（恒 tier-1）：超阈值时只统计前 1 万行，但必须说
            val args = """{"csvPath":${jsonString(file.absolutePath)},"dimension":"地区","metric":"销售额","engine":"memory"}"""
            val result = call(bootstrap, "stragent_aggregate_csv", args)
            assertEquals("in-memory", result["engine"]!!.jsonPrimitive.content)
            assertEquals(10_000, result["rowCount"]!!.jsonPrimitive.content.toInt(), "tier-1 实际只吃进阈值行")
            assertEquals(12_000, result["sourceRows"]!!.jsonPrimitive.content.toInt(), "文件真实行数一并回报")
            val notice = result["truncationNotice"]?.jsonPrimitive?.content
            assertNotNull(notice, "降级可以，把降级藏起来不行：必须显式回报截断")
            assertTrue(notice.contains("只统计了前 10000 行"), "提示要给出实际吃进的行数，实际：$notice")
            assertTrue(notice.contains("12000 行"), "提示要给出文件全量行数，实际：$notice")

            val profileArgs = """{"csvPath":${jsonString(file.absolutePath)},"column":"销售额","engine":"memory"}"""
            val profile = call(bootstrap, "stragent_profile_csv", profileArgs)
            assertEquals("in-memory", profile["engine"]!!.jsonPrimitive.content)
            assertNotNull(profile["truncationNotice"], "画像工具同样不许静默降级")
        } finally {
            bootstrap.close()
            file.delete()
        }
    }

    @Test
    fun `④ 画像工具小文件走 tier-1，字段与 tier-2 同构`() {
        val file = csv("profile.csv", rows = 120, groups = 4)
        val bootstrap = StrAgentHostBootstrap.create(createTempDirectory("routing-home4").toFile())
        try {
            val auto = call(bootstrap, "stragent_profile_csv", """{"csvPath":${jsonString(file.absolutePath)}}""")
            assertEquals("in-memory", auto["engine"]!!.jsonPrimitive.content, "120 行该走 tier-1")
            val forced = call(
                bootstrap,
                "stragent_profile_csv",
                """{"csvPath":${jsonString(file.absolutePath)},"engine":"columnar"}""",
            )
            assertEquals("duckdb", forced["engine"]!!.jsonPrimitive.content)

            fun firstColumn(result: JsonObject) = result["columns"]!!.jsonArray[0].jsonObject
            val a = firstColumn(auto)
            val b = firstColumn(forced)
            // 同构：字段集合一致（画像的两档输出必须可以互换消费）
            assertEquals(b.keys, a.keys, "两档画像字段应同构")
            assertEquals("地区", a["name"]!!.jsonPrimitive.content)
            assertEquals(120, a["count"]!!.jsonPrimitive.content.toInt(), "count 是参与统计的样本数")
            assertEquals(0, a["missing"]!!.jsonPrimitive.content.toInt())
            assertEquals(4, a["distinct"]!!.jsonPrimitive.content.toInt())
            assertEquals(
                b["topValues"]!!.jsonArray.map { it.jsonObject["count"]!!.jsonPrimitive.content },
                a["topValues"]!!.jsonArray.map { it.jsonObject["count"]!!.jsonPrimitive.content },
                "高频值计数两档应相等",
            )
        } finally {
            bootstrap.close()
            file.delete()
        }
    }

    @Test
    fun `⑤ engine 取值：旧叫法可用，未知值中文报错`() {
        val file = csv("args.csv", rows = 50)
        val bootstrap = StrAgentHostBootstrap.create(createTempDirectory("routing-home5").toFile())
        try {
            val base = """"csvPath":${jsonString(file.absolutePath)},"dimension":"地区","metric":"销售额""""
            assertEquals(
                "in-memory",
                call(bootstrap, "stragent_aggregate_csv", """{$base,"engine":"tier1"}""")
                    ["engine"]!!.jsonPrimitive.content,
                "tier1 是 memory 的旧叫法，仍要认",
            )
            val auto = call(bootstrap, "stragent_aggregate_csv", """{$base}""")
            assertEquals("in-memory", auto["engine"]!!.jsonPrimitive.content, "不写 engine 即 auto")
            val bad = errorOf(bootstrap, "stragent_aggregate_csv", """{$base,"engine":"pandas"}""")
            assertTrue(bad.contains("engine 只支持"), "未知档位要报清支持项，实际：$bad")
        } finally {
            bootstrap.close()
            file.delete()
        }
    }

    @Test
    fun `⑥ 路由级行数计数：引号内换行算一行，空行不计数`() {
        val file = File(createTempDirectory("routing-count").toFile(), "quoted.csv")
        file.writeText(
            "地区,备注\nG1,\"多\n行\n备注\"\n\nG2,普通\n",
            Charsets.UTF_8,
        )
        try {
            // 逻辑行：表头 + G1（引号内两个换行不切断）+ G2 = 3 行文本，数据行 2
            val (rows, capped) = CsvTierRouting.countDataRows(file, cap = 100)
            assertEquals(2, rows, "引号内换行不该被当成新逻辑行；空行不计数")
            assertFalse(capped, "未达 cap 不该报早停")
            val (cappedRows, hitCap) = CsvTierRouting.countDataRows(file, cap = 2)
            assertTrue(hitCap, "数到 cap 就该早停（大文件不为路由数完全程）")
            assertEquals(2, cappedRows)
        } finally {
            file.delete()
        }
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> append(ch)
            }
        }
        append('"')
    }
}
