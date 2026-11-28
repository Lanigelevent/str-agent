package com.stragent.agent

import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.ChatResult
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 编辑部多层级分工测试：主编选题 → 记者条线采访（共享日志）→ 主笔成稿 →
 * 确定性核查退稿 → 主编终审。用按「角色标记」路由的 fake LLM（编辑部 prompt
 * 由本测试定义，含独特标记词），验证职责隔离、共享日志、退稿与终审语义。
 */
class EditorialDeptTest {
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

    /** 团队定义（标记词 CHIEF_PLAN / BEAT / WRITER 供 fake 路由） */
    private fun team(writerPromptSuffix: String = "") = PluginManifest(
        id = "editorial-test",
        name = "测试编辑部",
        kind = "editorial",
        systemPrompt = "CHIEF_PLAN：召开选题会。",
        tools = emptyList(),
        temperature = 0.3,
        maxSteps = 4,
        writerPrompt = "WRITER：汇编成稿。$writerPromptSuffix",
        beats = listOf(
            EditorialBeat(name = "条线一", prompt = "BEAT_ONE：查分组。", tools = listOf("get_overview", "group_aggregate")),
            EditorialBeat(name = "条线二", prompt = "BEAT_TWO：查时序。", tools = listOf("get_overview", "get_series")),
        ),
    )

    /** 按最近一条 system/user 的角色标记路由输出 */
    private class RoutingLlm(private val route: (List<Pair<String, String>>, Int) -> String) : LlmClient {
        // 并行记者线并发调用 chat——记录必须同步（linux 协程调度下无同步列表会丢条目，
        // windows 调度恰好掩盖；本会话公开 CI 实录）
        val calls = java.util.Collections.synchronizedList(mutableListOf<List<Pair<String, String>>>())
        private var n = 0
        override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int): ChatResult {
            calls.add(messages)
            return ChatResult(route(messages, ++n), 20)
        }
    }

    private class MapPrefs : com.stragent.core.ports.PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    @Test
    fun `编辑部——五阶段全链：计划、两条线采访、成稿、终审 publish`() {
        val llm = RoutingLlm { messages, _ ->
            val system = messages.firstOrNull { it.first == "system" }?.second ?: ""
            val last = messages.last().second
            when {
                system.contains("CHIEF_PLAN") && !last.contains("计划已登记") ->
                    """{"thought":"拆三条线","tool":"submit_plan","args":{"steps":["条线一查分组","条线二查时序","汇编"]}}"""
                system.contains("CHIEF_PLAN") ->
                    """{"tool":"finish","args":{}}"""
                system.contains("BEAT_ONE") && !last.contains("record_finding") ->
                    """{"tool":"record_finding","args":{"title":"West 最高","detail":"West 725457.94 居首","evidence":"Region 列"}}"""
                system.contains("BEAT_ONE") ->
                    """{"tool":"finish","args":{"report":"条线一小结"}}"""
                system.contains("BEAT_TWO") && !last.contains("record_finding") ->
                    """{"tool":"record_finding","args":{"title":"2015-09 突变","detail":"环比 +193%","evidence":"Order Date 列"}}"""
                system.contains("BEAT_TWO") ->
                    """{"tool":"finish","args":{"report":"条线二小结"}}"""
                system.contains("WRITER") && !last.contains("audit") && !last.contains("audit_report") ->
                    """{"tool":"finish","args":{"report":"## 总体概览\nWest 725,457.94 居首；2015-09 环比 +193%。"}}"""
                else -> """{"tool":"finish","args":{"decision":"publish","notes":""}}"""
            }
        }
        val ds = dataset()
        val brief = com.stragent.core.brief.buildDatasetBrief(ds)
        val target = com.stragent.core.report.TargetLength(8000, "测试")
        val outcome = EditorialDept(llm).run(
            ds, brief, target, "standard", MapPrefs(), team(), caliberNotes = emptyList(), model = "m",
            onPhase = { },
        )!!

        // 采编日志 = 两名记者的发现合并（共享日志）
        assertEquals(2, outcome.findings.size)
        assertEquals("West 最高", outcome.findings[0].title)
        assertEquals("2015-09 突变", outcome.findings[1].title)
        // 计划来自主编
        assertEquals(listOf("条线一查分组", "条线二查时序", "汇编"), outcome.plan)
        // 终稿来自主笔
        assertTrue(outcome.output["report"].toString().contains("725,457.94"))
        // 各阶段都发生了：主编/两条记者/主笔/终审
        val systems = llm.calls.mapNotNull { m -> m.firstOrNull { it.first == "system" }?.second }
        assertTrue(systems.any { it.contains("CHIEF_PLAN") })
        assertTrue(systems.any { it.contains("BEAT_ONE") })
        assertTrue(systems.any { it.contains("BEAT_TWO") })
        assertTrue(systems.any { it.contains("WRITER") })
        assertTrue(systems.count { it.contains("CHIEF_PLAN") } >= 2, "主编选题会与终审都发生")
    }

    @Test
    fun `六记者并行——进度经协程上下文全部送达`() {
        // 每条线的第一步都记录一条发现（onStep 在 Dispatchers.IO 的工作线程上触发），
        // 进度处理器必须跟着协程上下文走，否则这 6 条进度全部静默丢失（T-048 / G-06）。
        val llm = RoutingLlm { messages, _ ->
            val system = messages.firstOrNull { it.first == "system" }?.second ?: ""
            val last = messages.last().second
            when {
                system.contains("CHIEF_PLAN") && !last.contains("计划已登记") ->
                    """{"tool":"submit_plan","args":{"steps":["六条线并行查证","汇编"]}}"""
                system.contains("CHIEF_PLAN") ->
                    """{"tool":"finish","args":{}}"""
                system.contains("BEAT_") && !last.contains("record_finding") ->
                    """{"tool":"record_finding","args":{"title":"条线发现","detail":"按条线查证","evidence":"列"}}"""
                system.contains("BEAT_") ->
                    """{"tool":"finish","args":{"report":"条线小结"}}"""
                system.contains("WRITER") && !last.contains("audit") && !last.contains("audit_report") ->
                    """{"tool":"finish","args":{"report":"## 总体概览\nWest 725,457.94 居首；2015-09 环比 +193%。"}}"""
                else -> """{"tool":"finish","args":{"decision":"publish","notes":""}}"""
            }
        }
        val sixBeats = team().copy(beats = (1..6).map {
            EditorialBeat(name = "条线$it", prompt = "BEAT_$it：查证。", tools = emptyList())
        })
        val delivered = java.util.Collections.synchronizedList(mutableListOf<String>())
        val reporterThreads = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val caller = Thread.currentThread().name
        val outcome = ProgressBus.with({ message -> delivered += message }) {
            EditorialDept(llm).run(
                dataset(), com.stragent.core.brief.buildDatasetBrief(dataset()),
                com.stragent.core.report.TargetLength(8000, "测试"), "standard", MapPrefs(), sixBeats,
                caliberNotes = emptyList(), model = "m",
                onStep = { step ->
                    if (step.tool == "record_finding") reporterThreads += Thread.currentThread().name
                    ProgressBus.emit("step:${step.tool}")
                },
            )
        }
        assertTrue(outcome != null, "六条线全部成功时应产出报告")
        assertEquals(
            6, delivered.count { it == "step:record_finding" },
            "6 名记者的记录步骤应全部送达，实际：$delivered",
        )
        assertTrue(reporterThreads.isNotEmpty())
        assertTrue(reporterThreads.all { it != caller }, "记者进度应来自并行工作线程，实际线程：$reporterThreads")
    }

    @Test
    fun `审校退稿——未溯源数字打回主笔修，修后放行`() {
        var writerRound = 0
        val llm = RoutingLlm { messages, _ ->
            val system = messages.firstOrNull { it.first == "system" }?.second ?: ""
            val last = messages.last().second
            when {
                system.contains("CHIEF_PLAN") && !last.contains("计划已登记") ->
                    """{"tool":"submit_plan","args":{"steps":["查","写"]}}"""
                system.contains("CHIEF_PLAN") ->
                    """{"tool":"finish","args":{}}"""
                system.contains("BEAT_ONE") && !last.contains("record_finding") ->
                    """{"tool":"record_finding","args":{"title":"有数","detail":"Sales 合计 2297200","evidence":"Sales"}}"""
                system.contains("BEAT_ONE") ->
                    """{"tool":"finish","args":{"report":"小结"}}"""
                system.contains("BEAT_TWO") ->
                    """{"tool":"finish","args":{"report":"小结"}}"""
                system.contains("WRITER") -> {
                    writerRound += 1
                    if (writerRound == 1) {
                        // 第一稿带一个 brief 无法溯源的数字 → 资料核查退稿
                        """{"tool":"finish","args":{"report":"## 概览\n神秘数字 987654.32 元。"}}"""
                    } else {
                        // 退稿后主笔改用可溯源数字
                        """{"tool":"finish","args":{"report":"## 概览\nWest 合计 725,457.94 元居首。"}}"""
                    }
                }
                else -> """{"tool":"finish","args":{"decision":"publish","notes":""}}"""
            }
        }
        val ds = dataset()
        val brief = com.stragent.core.brief.buildDatasetBrief(ds)
        val outcome = EditorialDept(llm).run(
            ds, brief, com.stragent.core.report.TargetLength(4000, "测试"), "standard", MapPrefs(), team(), emptyList(), "m", onPhase = { },
        )!!
        assertEquals(2, writerRound, "退稿后主笔重写一次")
        assertTrue(outcome.output["report"].toString().contains("725,457.94"))
        // T-044：内部退稿轮数透传——外层门禁据此用 0 预算，不双重计费
        assertEquals(1, outcome.gateRevisions, "内部退稿 1 轮必须如实回传")
        // 退稿意见进入过 writer 的任务（第二次 writer 调用里带核查意见）
        assertTrue(llm.calls.any { calls -> calls.any { it.second.contains("退稿意见") || it.second.contains("无法溯源") } })
    }

    @Test
    fun `主编终审——revise 一次后必发（不无限循环）`() {
        var chiefReviews = 0
        val llm = RoutingLlm { messages, _ ->
            val system = messages.firstOrNull { it.first == "system" }?.second ?: ""
            val last = messages.last().second
            when {
                // 终审分支必须先判（system 同时含 CHIEF_PLAN 与 终审）
                system.contains("终审") -> {
                    chiefReviews += 1
                    if (chiefReviews == 1) {
                        """{"tool":"finish","args":{"decision":"revise","notes":"口径要统一"}}"""
                    } else {
                        """{"tool":"finish","args":{"decision":"publish","notes":""}}"""
                    }
                }
                system.contains("CHIEF_PLAN") && !last.contains("计划已登记") ->
                    """{"tool":"submit_plan","args":{"steps":["查"]}}"""
                system.contains("CHIEF_PLAN") ->
                    """{"tool":"finish","args":{}}"""
                system.contains("BEAT_ONE") && !last.contains("record_finding") ->
                    """{"tool":"record_finding","args":{"title":"发现","detail":"d","evidence":"e"}}"""
                system.contains("BEAT_ONE") ->
                    """{"tool":"finish","args":{"report":"小结"}}"""
                system.contains("BEAT_TWO") ->
                    """{"tool":"finish","args":{"report":"小结"}}"""
                else -> """{"tool":"finish","args":{"report":"## 报告\nWest 合计 725,457.94 元。"}}"""
            }
        }
        val ds = dataset()
        val brief = com.stragent.core.brief.buildDatasetBrief(ds)
        val outcome = EditorialDept(llm).run(
            ds, brief, com.stragent.core.report.TargetLength(4000, "测试"), "standard", MapPrefs(), team(), emptyList(), "m", onPhase = { },
        )!!
        assertEquals(1, chiefReviews, "主编终审一轮：退一次（改后必发，不再复审）")
        assertTrue(outcome.output["report"].toString().contains("报告"))
        // 退稿后主笔重跑过：writer 阶段的调用数应包含主编退稿修订轮
        val writerCalls = llm.calls.count { calls -> calls.any { it.second.contains("WRITER") } }
        assertEquals(2, writerCalls, "主编退稿后主笔重写一次")
    }

    @Test
    fun `取消——任意阶段取消立即抛出`() {
        val llm = RoutingLlm { _, _ -> """{"tool":"finish","args":{}}""" }
        assertFailsWithAgentCancelled {
            EditorialDept(llm).run(
                dataset(), com.stragent.core.brief.buildDatasetBrief(dataset()),
                com.stragent.core.report.TargetLength(4000, "测试"), "standard", MapPrefs(), team(), emptyList(), "m",
                cancelSignal = { true },
            )
        }
    }

    private inline fun assertFailsWithAgentCancelled(block: () -> Any?) {
        try {
            block()
        } catch (expected: AgentCancelled) {
            return
        }
        throw AssertionError("未抛出 AgentCancelled")
    }

    @Test
    fun `清单——editorial 校验 beats 与 writerPrompt`() {
        val parsed = PluginManifest.parse(
            """{"id":"t1","kind":"editorial","systemPrompt":"c","writerPrompt":"w",
               "beats":[{"name":"b1","prompt":"p1"}]}""",
        )
        assertEquals(1, parsed.beats.size)
        assertEquals("w", parsed.writerPrompt)
        assertTrue(
            runCatching { PluginManifest.parse("""{"id":"t2","kind":"editorial","systemPrompt":"c"}""") }
                .exceptionOrNull()!!.message!!.contains("beats"),
        )
    }

    @Test
    fun `主笔提示词注入完整写作纪律栈（T-032）——行业框架、文风、去AI味、方法论全部在场`() {
        val prefs = MapPrefs()
        val llm = RoutingLlm { messages, _ ->
            val system = messages.firstOrNull { it.first == "system" }?.second ?: ""
            val last = messages.last().second
            when {
                system.contains("CHIEF_PLAN") && !last.contains("计划已登记") ->
                    """{"tool":"submit_plan","args":{"steps":["查"]}}"""
                system.contains("CHIEF_PLAN") ->
                    """{"tool":"finish","args":{}}"""
                system.contains("BEAT_ONE") && !last.contains("record_finding") ->
                    """{"tool":"record_finding","args":{"title":"有数","detail":"Sales 合计 2297200","evidence":"Sales"}}"""
                system.contains("BEAT_ONE") ->
                    """{"tool":"finish","args":{"report":"小结"}}"""
                system.contains("BEAT_TWO") ->
                    """{"tool":"finish","args":{"report":"小结"}}"""
                system.contains("WRITER") ->
                    """{"tool":"finish","args":{"report":"## 概览\nWest 合计 725,457.94 元居首。"}}"""
                else -> """{"tool":"finish","args":{"decision":"publish","notes":""}}"""
            }
        }
        val outcome = EditorialDept(llm).run(
            dataset(), com.stragent.core.brief.buildDatasetBrief(dataset()),
            com.stragent.core.report.TargetLength(1500, "测试"), "standard", prefs, team(), emptyList(), "m", onPhase = { },
        )!!
        val writerSystem = llm.calls
            .mapNotNull { m -> m.firstOrNull { it.first == "system" }?.second }
            .first { it.contains("WRITER") }
        // 与经典单发路径同一份纪律栈（此前主笔只有六行浓缩版，风格与行业库失效）
        assertTrue(writerSystem.contains("<industry_framework"), "应有行业框架块：${writerSystem.take(200)}")
        assertTrue(writerSystem.contains("<writing_style"), "应有文风块")
        assertTrue(writerSystem.contains("<de_ai_style>"), "应有去 AI 味块")
        assertTrue(writerSystem.contains("<methodology>"), "应有方法论块")
        assertTrue(writerSystem.contains("<quality_bar>"), "应有质量门槛块")
        assertTrue(writerSystem.contains("<self_check>"), "应有输出自检块")
        // 记者保持窄提示词：记者的 system 不含纪律栈
        val reporterSystem = llm.calls
            .mapNotNull { m -> m.firstOrNull { it.first == "system" }?.second }
            .first { it.contains("BEAT_ONE") }
        assertFalseForKotlin(reporterSystem.contains("<de_ai_style>"), "记者不该带写作纪律栈（窄提示词设计）")
        assertTrue(outcome.qualityNotes.isNotEmpty(), "写作审计注记应可见（短稿缺结构块属软提示）")
    }

    private fun assertFalseForKotlin(cond: Boolean, message: String) {
        if (cond) throw AssertionError(message)
    }

    @Test
    fun `分章成稿——chaptered 插件长报告逐章拼接、全文核查`() {
        val llm = RoutingLlm { messages, _ ->
            val system = messages.firstOrNull { it.first == "system" }?.second ?: ""
            val last = messages.last().second
            when {
                system.contains("CHIEF_PLAN") && !last.contains("计划已登记") ->
                    """{"tool":"submit_plan","args":{"steps":["查"]}}"""
                system.contains("CHIEF_PLAN") ->
                    """{"tool":"finish","args":{}}"""
                system.contains("BEAT_ONE") && !last.contains("record_finding") ->
                    """{"tool":"record_finding","args":{"title":"有数","detail":"Sales 合计 2297200","evidence":"Sales"}}"""
                system.contains("BEAT_ONE") ->
                    """{"tool":"finish","args":{"report":"小结"}}"""
                system.contains("BEAT_TWO") ->
                    """{"tool":"finish","args":{"report":"小结"}}"""
                system.contains("<chapter_position>") -> {
                    // 每章一次调用：从定位块里抠出章号，回本章专属内容
                    val no = Regex("第 (\\d+) 章").find(system)!!.groupValues[1].toInt()
                    """{"tool":"finish","args":{"report":"## 第${no}章正文\nWest 合计 725,457.94 元（第${no}章）。"}}"""
                }
                else -> """{"tool":"finish","args":{"decision":"publish","notes":""}}"""
            }
        }
        val chapteredTeam = PluginManifest(
            id = "editorial-ch",
            name = "分章编辑部",
            kind = "editorial",
            systemPrompt = "CHIEF_PLAN：召开选题会。",
            tools = emptyList(),
            maxSteps = 4,
            writerPrompt = "WRITER：汇编成稿。",
            chaptered = true,
            beats = listOf(
                EditorialBeat(name = "条线一", prompt = "BEAT_ONE：查分组。", tools = listOf("get_overview")),
                EditorialBeat(name = "条线二", prompt = "BEAT_TWO：查时序。", tools = listOf("get_overview")),
            ),
        )
        val outcome = EditorialDept(llm).run(
            dataset(), com.stragent.core.brief.buildDatasetBrief(dataset()),
            com.stragent.core.report.TargetLength(3000, "测试"), "standard", MapPrefs(), chapteredTeam, emptyList(), "m",
            onPhase = { },
        )!!
        val report = (outcome.output["report"] as kotlinx.serialization.json.JsonPrimitive).content
        // 章数 > 1（3000 字目标在 superstore 摘要上必然拆出多章），且每章正文都在终稿里按序拼接
        val chapterCount = Regex("第(\\d+)章正文").findAll(report).map { it.groupValues[1].toInt() }.toList()
        assertTrue(chapterCount.size >= 2, "应有 ≥2 章，实际：$report")
        assertEquals((1..chapterCount.size).toList(), chapterCount, "章节应按序完整拼接")
        // 全文核查发生在拼接后：单章内容可溯源 → 无退稿轮（writer-ch 调用只发生一轮）
        val chapterCalls = llm.calls.count { calls -> calls.any { it.first == "system" && it.second.contains("<chapter_position>") } }
        assertEquals(chapterCount.size, chapterCalls, "每章恰好一次调用")
    }
}
