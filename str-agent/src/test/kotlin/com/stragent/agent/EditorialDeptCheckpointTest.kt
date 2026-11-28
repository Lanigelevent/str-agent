package com.stragent.agent

import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset
import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.LlmClient
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-050：编辑部的阶段断点与部分成功合并。
 *
 *  ① 单条记者线失败（协议坏 JSON 修不回来）→ 只丢该条线：其余发现照常合并，
 *    主笔任务里带 `<editorial_gaps>`、宿主拿到 `[coverage]` 注记；
 *  ② 崩溃/失败发生在主笔阶段 → 主编选题会与两条已完成采访在下一轮**直接回放**，
 *    不再发起模型调用；主笔从干净状态重新成稿。
 *
 * 全部条线都失败才回落经典流水线（编辑部无米下锅）——这一条由既有
 * EditorialDeptTest 的整链用例覆盖边界，这里专测「部分」语义。
 */
class EditorialDeptCheckpointTest {

    private fun dataset(): Dataset {
        val text = File("../fixtures/sample/superstore.csv").readText()
        val parsed = Csv.parseDelimited(text)
        val limited = Csv.limitRows(parsed)
        val types = Csv.inferTypes(limited.rows, limited.columns.size)
        return Dataset(
            name = "superstore.csv", importedAt = 1,
            columns = limited.columns, rows = limited.rows, columnTypes = types,
            delimiter = limited.delimiter, truncated = limited.truncated, sourceRows = limited.totalRows,
        )
    }

    private fun team() = PluginManifest(
        id = "editorial-checkpoint",
        name = "断点编辑部",
        kind = "editorial",
        systemPrompt = "CHIEF_PLAN：召开选题会。",
        tools = emptyList(),
        temperature = 0.3,
        maxSteps = 4,
        writerPrompt = "WRITER：汇编成稿。",
        beats = listOf(
            EditorialBeat(name = "条线一", prompt = "BEAT_ONE：查分组。", tools = listOf("get_overview")),
            EditorialBeat(name = "条线二", prompt = "BEAT_TWO：查时序。", tools = listOf("get_overview")),
        ),
    )

    private class RoutingLlm(private val route: (List<Pair<String, String>>) -> String) : LlmClient {
        val calls: MutableList<List<Pair<String, String>>> =
            java.util.Collections.synchronizedList(mutableListOf())
        override fun chat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
        ): ChatResult {
            calls += messages
            return ChatResult(route(messages), 10)
        }
    }

    private class MapPrefs : com.stragent.core.ports.PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private fun systemOf(messages: List<Pair<String, String>>): String =
        messages.firstOrNull { it.first == "system" }?.second ?: ""

    private fun lastOf(messages: List<Pair<String, String>>): String = messages.last().second

    @Test
    fun `部分成功——单条记者线失败只丢该条线，缺口写进主笔任务与质量注记`() {
        val llm = RoutingLlm { messages ->
            val system = systemOf(messages)
            val last = lastOf(messages)
            when {
                system.contains("CHIEF_PLAN") && !system.contains("终审") && !last.contains("计划已登记") ->
                    """{"tool":"submit_plan","args":{"steps":["查分组","查时序","汇编"]}}"""
                system.contains("CHIEF_PLAN") && !system.contains("终审") ->
                    """{"tool":"finish","args":{}}"""
                system.contains("BEAT_ONE") && !last.contains("record_finding") ->
                    """{"tool":"record_finding","args":{"title":"West 最高","detail":"West 725457.94 居首","evidence":"Region 列"}}"""
                system.contains("BEAT_ONE") ->
                    """{"tool":"finish","args":{"report":"条线一小结"}}"""
                // 条线二：修不回来的坏 JSON → 该条线整体失败
                system.contains("BEAT_TWO") -> "这不是合法的 JSON"
                system.contains("WRITER") ->
                    """{"tool":"finish","args":{"report":"## 总体概览\nWest 合计 725,457.94 元居首；时序条线未覆盖。"}}"""
                else -> """{"tool":"finish","args":{"decision":"publish","notes":""}}"""
            }
        }
        val ds = dataset()
        val outcome = EditorialDept(llm).run(
            ds, com.stragent.core.brief.buildDatasetBrief(ds),
            com.stragent.core.report.TargetLength(4000, "测试"), "standard", MapPrefs(), team(),
            caliberNotes = emptyList(), model = "m", onPhase = { },
        )!!
        // 部分成功：报告照常产出，只合并完成条线的发现
        assertTrue(outcome.output["report"].toString().contains("725,457.94"))
        assertEquals(1, outcome.findings.size)
        assertEquals("West 最高", outcome.findings[0].title)
        // 缺口必须让主笔看见、让宿主看见
        assertTrue(
            llm.calls.any { messages -> systemOf(messages).contains("WRITER") && lastOf(messages).contains("<editorial_gaps>") },
            "主笔任务应包含掉队条线块",
        )
        assertTrue(
            llm.calls.any { messages -> lastOf(messages).contains("条线二") },
            "掉队条线名应出现在任务里",
        )
        assertTrue(
            outcome.qualityNotes.any { it.contains("[coverage]") && it.contains("条线二") },
            "宿主应拿到覆盖缺口注记：${outcome.qualityNotes}",
        )
    }

    @Test
    fun `阶段断点——主笔失败后重跑，已完成的主编计划与采访直接回放`() {
        val root = Files.createTempDirectory("stragent-editorial-ckpt-").toFile()
        val scope = AgentCheckpointStore(root).forRun("run-1")
        val ds = dataset()
        val brief = com.stragent.core.brief.buildDatasetBrief(ds)
        val target = com.stragent.core.report.TargetLength(4000, "测试")

        // 第一轮：记者正常完成，主笔始终输出坏 JSON → 整次任务失败（writer-r0 作废）
        val failing = RoutingLlm { messages ->
            val system = systemOf(messages)
            val last = lastOf(messages)
            when {
                system.contains("CHIEF_PLAN") && !system.contains("终审") && !last.contains("计划已登记") ->
                    """{"tool":"submit_plan","args":{"steps":["查分组","查时序","汇编"]}}"""
                system.contains("CHIEF_PLAN") && !system.contains("终审") ->
                    """{"tool":"finish","args":{}}"""
                system.contains("BEAT_ONE") && !last.contains("record_finding") ->
                    """{"tool":"record_finding","args":{"title":"West 最高","detail":"West 725457.94 居首","evidence":"Region 列"}}"""
                system.contains("BEAT_ONE") ->
                    """{"tool":"finish","args":{"report":"条线一小结"}}"""
                system.contains("BEAT_TWO") && !last.contains("record_finding") ->
                    """{"tool":"record_finding","args":{"title":"2015-09 突变","detail":"环比 +193%","evidence":"Order Date 列"}}"""
                system.contains("BEAT_TWO") ->
                    """{"tool":"finish","args":{"report":"条线二小结"}}"""
                else -> "坏 JSON"
            }
        }
        val first = EditorialDept(failing).run(
            ds, brief, target, "standard", MapPrefs(), team(), emptyList(), "m", checkpoints = scope,
        )
        assertNull(first, "主笔失败：本轮编辑部返回 null（调用方回落经典）")

        // 第二轮（模拟进程重启：换新 store 实例）：同一任务重跑。
        // 主编/记者阶段应回放，不再调用模型；主笔重新成稿
        val reopened = AgentCheckpointStore(root).forRun("run-1")
        val counts = mutableMapOf<String, Int>()
        val llm = RoutingLlm { messages ->
            val system = systemOf(messages)
            val last = lastOf(messages)
            val key = when {
                system.contains("终审") -> "review"
                system.contains("CHIEF_PLAN") -> "chief"
                system.contains("BEAT_ONE") -> "beat1"
                system.contains("BEAT_TWO") -> "beat2"
                system.contains("WRITER") -> "writer"
                else -> "other"
            }
            counts[key] = (counts[key] ?: 0) + 1
            when (key) {
                "writer" ->
                    """{"tool":"finish","args":{"report":"## 总体概览\nWest 725,457.94 居首；2015-09 环比 +193%。"}}"""
                "review" -> """{"tool":"finish","args":{"decision":"publish","notes":""}}"""
                else -> """{"tool":"finish","args":{}}"""
            }
        }
        val outcome = EditorialDept(llm).run(
            ds, brief, target, "standard", MapPrefs(), team(), emptyList(), "m", checkpoints = reopened,
        )!!
        assertTrue(outcome.output["report"].toString().contains("725,457.94"))
        assertEquals(2, outcome.findings.size, "回放的采访发现应完整合并")
        assertEquals(null, counts["chief"], "主编选题会应回放，不再调用模型")
        assertEquals(null, counts["beat1"], "条线一应回放，不再调用模型")
        assertEquals(null, counts["beat2"], "条线二应回放，不再调用模型")
        assertEquals(1, counts["writer"], "主笔重新成稿一次")
        assertEquals(1, counts["review"], "主编终审正常发生")
        // 整次任务成功交付后由编排器清理；这里手动清，验证清理后阶段不可再回放
        reopened.clearAll()
        assertNull(reopened.stage("beat-0").load())
    }
}
