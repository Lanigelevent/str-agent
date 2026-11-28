package com.stragent.agent

import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.SearchClient
import com.stragent.core.ports.SearchHit
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResearcherAgentTest {

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
        id = "editorial-search",
        name = "检索编辑部",
        kind = "editorial",
        systemPrompt = "CHIEF_PLAN：召开选题会。",
        tools = emptyList(),
        maxSteps = 4,
        writerPrompt = "WRITER：汇编成稿。",
        beats = listOf(
            EditorialBeat(name = "条线一", prompt = "BEAT_ONE：查分组。", tools = listOf("get_overview", "web_search")),
            EditorialBeat(name = "条线二", prompt = "BEAT_TWO：查时序。", tools = listOf("get_overview")),
        ),
    )

    private class FakeSearchClient : SearchClient {
        var queryReceived: String? = null
        override fun search(query: String): List<SearchHit> {
            queryReceived = query
            return listOf(SearchHit("行业参考", "https://example.com/market", "市场增长15%"))
        }
    }

    private class RoutingLlm(private val route: (List<Pair<String, String>>, Int) -> String) : LlmClient {
        val calls = mutableListOf<List<Pair<String, String>>>()
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
    fun `开启检索后记者可用web_search工具`() {
        val fakeSearch = FakeSearchClient()
        val llm = RoutingLlm { messages, _ ->
            val system = messages.firstOrNull { it.first == "system" }?.second ?: ""
            val last = messages.last().second
            when {
                system.contains("CHIEF_PLAN") && !last.contains("计划已登记") ->
                    """{"tool":"submit_plan","args":{"steps":["查"]}}"""
                system.contains("CHIEF_PLAN") ->
                    """{"tool":"finish","args":{}}"""
                system.contains("BEAT_ONE") && !last.contains("record_finding") && !last.contains("市场增长") ->
                    """{"tool":"web_search","args":{"query":"行业增长率"}}"""
                system.contains("BEAT_ONE") && last.contains("市场增长") ->
                    """{"tool":"record_finding","args":{"title":"行业增长15%","detail":"市场增长15%","evidence":"web_search"}}"""
                system.contains("BEAT_ONE") ->
                    """{"tool":"finish","args":{"report":"条线一小结"}}"""
                system.contains("BEAT_TWO") ->
                    """{"tool":"finish","args":{"report":"条线二小结"}}"""
                system.contains("WRITER") ->
                    """{"tool":"finish","args":{"report":"## 概览\n行业增长15%。"}}"""
                else -> """{"tool":"finish","args":{"decision":"publish","notes":""}}"""
            }
        }
        val ds = dataset()
        val brief = com.stragent.core.brief.buildDatasetBrief(ds)
        val outcome = EditorialDept(llm).run(
            ds, brief, com.stragent.core.report.TargetLength(4000, "测试"), "standard", MapPrefs(), team(), emptyList(), "m", onPhase = { },
            search = fakeSearch,
        )
        assertTrue(outcome != null, "编辑部应成功完成")
        assertEquals("行业增长率", fakeSearch.queryReceived, "FakeSearchClient 应被调用")
    }

    @Test
    fun `未传入search时工具列表不含web_search`() {
        val ds = dataset()
        val brief = com.stragent.core.brief.buildDatasetBrief(ds)
        val tools = buildAgentTools(ds, brief, search = null)
        val names = tools.map { it.name }
        assertTrue(!names.contains("web_search"), "未传入 search 时不应有 web_search 工具")
    }
}
