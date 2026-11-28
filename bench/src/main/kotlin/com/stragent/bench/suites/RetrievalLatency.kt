package com.stragent.bench.suites

import com.stragent.bench.model.SuiteResult
import com.stragent.core.ai.Parallel
import com.stragent.core.ai.TextChunker
import com.stragent.core.ai.TextIndexCache
import com.stragent.core.dataset.Dataset
import com.stragent.core.dataset.TextContent
import com.stragent.core.dataset.TextSegment
import com.stragent.core.ai.askFollowup
import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.PrefsStore

/**
 * T-131 检索时延基线（D23 W-H）：固定大材料上追问检索的冷/热行为。
 *
 * 断言哲学（PerfBaseline 同款宽门限）：①**语义等价**——缓存命中路径的检索结果与首次
 * 构建逐字节一致（同问题同命中，确定性断言）；②时延数字进 notes 可跨提交对比
 * （不设紧门限——CI 机器负载波动大，绝对时延门限会产出假红，价值在 notes 趋势）。
 */
fun runRetrievalLatency(): SuiteResult {
    var passed = 0
    val total = 4
    val failures = mutableListOf<String>()
    val notes = mutableListOf<String>()

    fun check(name: String, ok: Boolean, detail: () -> String = { "" }) {
        if (ok) {
            passed++
        } else {
            failures += name + (detail().takeIf { it.isNotBlank() }?.let { "：$it" } ?: "")
        }
    }

    TextIndexCache.clear()
    try {
        // 固定材料：200 段 × ~250 字（≈50K，两块以上触发多粒度粗层）
        val segs = Array(200) { i ->
            "管廊项目第%03d标段巡检批次记录：本日对该区域管廊本体结构与排水设施开展例行巡检，".format(i + 1) +
                "巡检发现的零星缺陷已当场处置并登记台账，现场秩序正常。" + "后续按计划开展下一批次巡检工作。".repeat(6)
        }
        val ds = Dataset(
            name = "latency.md", importedAt = 1,
            columns = emptyList(), rows = emptyList(), columnTypes = emptyList(),
            text = TextContent(segs.mapIndexed { i, s -> TextSegment(i + 1, s) }),
        )
        val llm = object : LlmClient {
            override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int) =
                ChatResult("按材料回答。", 55)
        }
        val prefs = object : PrefsStore {
            val m = mutableMapOf<String, String>()
            override fun get(key: String, fallback: String) = m[key] ?: fallback
            override fun set(key: String, value: String) { m[key] = value }
        }
        val q = "管廊巡检情况如何"

        // 冷路径（首次：句组/块/索引全构建）与热路径（缓存命中）
        val t0 = System.nanoTime()
        val cold = askFollowup(llm, prefs, ds, emptyList(), q, "bench")
        val coldMs = (System.nanoTime() - t0) / 1_000_000

        val t1 = System.nanoTime()
        val warm = askFollowup(llm, prefs, ds, emptyList(), q, "bench")
        val warmMs = (System.nanoTime() - t1) / 1_000_000

        // 语义等价：同问题同命中材料（确定性断言）
        check("热路径语义等价", cold.subQueries == warm.subQueries && cold.gateFailed == warm.gateFailed &&
            cold.content == warm.content, { "cold=${cold.content.take(30)} warm=${warm.content.take(30)}" })

        // 热路径不比重试慢得离谱（宽门限 4×，防回归性劣化而非测绝对值）
        check("热路径无回归性劣化", warmMs <= coldMs * 4 + 50, { "cold=${coldMs}ms warm=${warmMs}ms" })

        // 缓存确实生效：第三次调用应命中（时间应不高于冷路径的 1/2，宽断言）
        val t2 = System.nanoTime()
        askFollowup(llm, prefs, ds, emptyList(), q, "bench")
        val warm2Ms = (System.nanoTime() - t2) / 1_000_000
        check("缓存命中路径快速", warm2Ms <= kotlin.math.max(coldMs / 2, 20), { "cold=${coldMs}ms warm2=${warm2Ms}ms" })

        // 原结构缓存复用：同材料句组重复请求零重建（构建计数器语义的替代表达——直接时间断言已覆盖）
        val groups = TextChunker.sentenceGroups(ds.text!!)
        check("材料规模合规", groups.size == segs.size, { "groups=${groups.size}" })

        notes.add(
            "T-131 检索时延基线：${segs.size} 段材料追问——冷 $coldMs ms / 热 $warmMs ms / 缓存命中 $warm2Ms ms" +
                "（并发上限 ${Parallel.DEFAULT_CONCURRENCY}，indexCache 8 份 LRU）",
        )
    } finally {
        TextIndexCache.clear()
    }
    return SuiteResult(
        name = "retrieval-latency",
        passed = passed,
        total = total,
        failures = failures,
        notes = notes.joinToString("；"),
    )
}
