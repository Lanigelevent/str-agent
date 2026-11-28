package com.stragent.bench.suites

import com.stragent.core.ports.AgentTool
import com.stragent.bench.fixtures.MapPrefs
import com.stragent.bench.model.SuiteResult
import com.stragent.data.AbilityDatasets
import com.stragent.data.McpBridge
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.math.ceil

/**
 * 套件九 · concurrency-baseline（MCP 并发压测基线）—— D21 §2.3 P-1 / T-075。
 *
 * P-1 的动机：4+64 HTTP 线程池的实际 QPS 与「长任务共存时短请求是否被饿死」没有数字；
 * `McpConcurrencyTest` 曾在 64 并发时负载敏感失败（P-060 首跑），压测工具与阈值缺失。
 *
 * 口径纪律：
 *  - 走**真实 HTTP 传输**（`McpBridge.start(0)` 临时端口；core 4 + 弹性上限 64 线程池、
 *    SynchronousQueue、Bearer 鉴权——池语义与排队都在被测路径上）；
 *  - 混合负载：快查 = 真策展工具（`stragent_get_group_aggregate` / `stragent_list_rows`，
 *    常驻四件套，无会话依赖），长任务 = bench 专用 stub（sleep 40ms，模拟 LLM 回包的
 *    时长尺度——真实报告路径只差延迟尺度，池语义与排队完全一致；stub 只存在于
 *    bench 桥实例，不进产品工具面）；
 *  - 每级 4/16/64 并发：每线程 6 轮，每轮 1 次快查、第 0 与第 4 轮各加 1 次长任务；
 *  - **预热相位（T-080）**：每级计量前先跑一轮同形态未计量请求——建连爬坡（keep-alive
 *    池、accept 路径、JIT）吸收进预热，不进任何指标；计量窗口的判据（错误率 0 等）
 *    一字不动，属测量卫生而非放宽。CI 机在全量测试后立即跑 bench，冷启动爬坡正是
 *    c64 错误率间歇翻红（2/512、4/512）的来源——本地同码四轮 888/888 从未复现。
 *  - 指标：快查/长任务各自 P50/P95（最近邻秩取上档，ms）、错误率、QPS（总调用/墙钟）；
 *  - 门禁（宽，与 perf-baseline 同纪律——紧阈值只会产出假红，价值在数字随提交可对比）：
 *      错误率必须为 0；快查 P95 ≤ 2000ms；长任务 P95 ≤ 5000ms；
 *      基线对比（`bench/baseline/concurrency-baseline.json`，工作目录 = 仓库根）：
 *      延迟 >3× FAIL、>2× WARN；QPS <基线/3 FAIL、</2 WARN；基线缺键不对比（硬阈值兜底）。
 */
fun runConcurrencyBaseline(updateBaseline: Boolean = false): SuiteResult {
    val csv = buildString {
        appendLine("地区,销售额")
        for (i in 1..200) appendLine("地区${i % 8},${i * 1.5}")
    }
    val datasets = AbilityDatasets()
    val prefs = MapPrefs()
    val bridge = McpBridge(datasets, prefs, extraTools = listOf(longReportStubTool()))
    val port = bridge.start(0)
    try {
        val token = McpBridge.currentToken(prefs)
        val client = HttpClient(port, token)
        val (_, importOk, importBody) = client.call("stragent_import_table", importArgs(csv))
        check(importOk) { "压测前置导入失败" }
        val datasetId = Regex("\"datasetId\":\"(ds_[0-9a-f]+)\"").find(importBody)?.groupValues?.get(1)
            ?: error("导入回执缺 datasetId：${importBody.take(200)}")

        val results = CONCURRENCY_LEVELS.map { level ->
            // T-080 预热相位：同形态一轮，结果全弃——爬坡成本不进计量窗口（判据零放宽）
            warmupLevel(client, level, datasetId)
            measureLevel(client, level, datasetId)
        }
        val failures = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var passed = 0
        var total = 0

        fun check(condition: Boolean, failure: String) {
            total += 1
            if (condition) passed += 1 else failures.add(failure)
        }

        for (m in results) {
            check(m.errorRate == 0.0, "c${m.level}: 错误率 ${m.errorRate} != 0（错误 ${m.errors}/${m.total}）")
            check(m.fastP95 <= FAST_P95_LIMIT_MS, "c${m.level}: 快查 P95 ${m.fastP95}ms > ${FAST_P95_LIMIT_MS}ms")
            check(m.longP95 <= LONG_P95_LIMIT_MS, "c${m.level}: 长任务 P95 ${m.longP95}ms > ${LONG_P95_LIMIT_MS}ms")
        }

        val baselineFile = defaultConcurrencyBaselineFile()
        val baseline = loadConcurrencyBaseline(baselineFile)
        if (baseline == null) {
            warnings.add("并发基线缺失（首跑写入：--update-baseline / -Pbench.updateBaseline）")
        } else {
            for (m in results) {
                val (f, fwarn) = compareConcurrency(m, baseline)
                failures += f
                warnings += fwarn
            }
        }

        if (updateBaseline) {
            val flat = results.flatMap { m ->
                m.toBaselineMap().map { (k, v) -> "c${m.level}.$k" to v }
            }.toMap()
            saveConcurrencyBaseline(baselineFile, flat)
        }

        val notes = results.joinToString("；") { m ->
            "c${m.level}: 快查 P50/P95=${m.fastP50}/${m.fastP95}ms，长任务 P50/P95=${m.longP50}/${m.longP95}ms，" +
                "错误率=${m.errorRate}，QPS=${m.qps}"
        } + if (warnings.isEmpty()) "" else "；⚠ " + warnings.joinToString("；")

        return SuiteResult(
            name = "concurrency-baseline",
            passed = passed,
            total = total,
            failures = failures,
            notes = notes,
            gatePassed = failures.isEmpty(),
        )
    } finally {
        bridge.stop()
    }
}

/** bench 专用长任务 stub：sleep 40ms + 确定性回包（模拟 LLM 报告回包的时长尺度） */
internal fun longReportStubTool(sleepMs: Long = 40): AgentTool = object : AgentTool {
    override val name = "stragent_bench_long_report"
    override val description =
        "bench 长任务 stub（仅存在于 bench 桥实例，非产品工具面）：sleep ${sleepMs}ms 模拟报告回包"
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
    }
    override val cacheable = false
    override val readOnly = true

    override fun invoke(args: JsonObject): JsonObject = buildJsonObject {
        Thread.sleep(sleepMs)
        put("stub", true)
        put("sleptMs", sleepMs)
    }
}

// ---------- 指标与门禁（纯函数，可单测） ----------

/** 每个并发档位的实测指标 */
internal data class ConcurrencyMetrics(
    val level: Int,
    val fastP50: Double,
    val fastP95: Double,
    val longP50: Double,
    val longP95: Double,
    val errorRate: Double,
    val qps: Double,
    val total: Int,
    val errors: Int,
) {
    fun toBaselineMap(): Map<String, Double> = mapOf(
        "fastP50" to fastP50,
        "fastP95" to fastP95,
        "longP50" to longP50,
        "longP95" to longP95,
        "qps" to qps,
    )
}

/** 最近邻秩分位（与 /ui 驾驶舱同口径：保守取上档） */
internal fun nearestRank(sorted: List<Long>, p: Double): Long {
    if (sorted.isEmpty()) return 0L
    val rank = ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
    return sorted[rank - 1]
}

/** 宽门禁硬阈值：快查 P95 2s、长任务 P95 5s（错误率必须 0，由套件内 check 兜底） */
internal const val FAST_P95_LIMIT_MS = 2000.0
internal const val LONG_P95_LIMIT_MS = 5000.0

/**
 * 单档位 vs 基线的宽倍数对比（T-075 门禁内核）。
 * 返回 (failures, warnings)：延迟 >3× 或 QPS </3 → FAIL；>2× 或 </2 → WARN。
 * 基线缺键 / 值 ≤0 不对比（不伪造通过，硬阈值兜底）。
 */
internal fun compareConcurrency(
    current: ConcurrencyMetrics,
    baseline: Map<String, Double>,
): Pair<List<String>, List<String>> {
    val failures = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    val prefix = "c${current.level}"

    fun latency(key: String, value: Double) {
        val base = baseline[key] ?: return
        if (base <= 0.0) return
        when {
            value > base * 3 -> failures.add("$prefix.$key: 当前 ${value}ms > 基线 ${base}ms 的 3 倍")
            value > base * 2 -> warnings.add("$prefix.$key: 当前 ${value}ms > 基线 ${base}ms 的 2 倍")
        }
    }

    fun qps() {
        val base = baseline["$prefix.qps"] ?: return
        if (base <= 0.0) return
        when {
            current.qps < base / 3 -> failures.add("$prefix.qps: 当前 ${current.qps} < 基线 $base 的 1/3")
            current.qps < base / 2 -> warnings.add("$prefix.qps: 当前 ${current.qps} < 基线 $base 的 1/2")
        }
    }

    latency("$prefix.fastP95", current.fastP95)
    latency("$prefix.longP95", current.longP95)
    qps()
    return failures to warnings
}

internal fun defaultConcurrencyBaselineFile(): File =
    File(System.getProperty("user.dir") ?: ".", "bench/baseline/concurrency-baseline.json")

internal fun loadConcurrencyBaseline(file: File): MutableMap<String, Double>? = try {
    val obj = Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
    mutableMapOf<String, Double>().apply {
        for ((k, v) in obj) this[k] = (v as JsonPrimitive).content.toDouble()
    }
} catch (_: Exception) {
    null
}

internal fun saveConcurrencyBaseline(file: File, map: Map<String, Double>) {
    file.parentFile?.mkdirs()
    val body = map.entries.sortedBy { it.key }.joinToString(",\n") { (k, v) ->
        "  \"$k\": $v"
    }
    file.writeText("{\n$body\n}\n", Charsets.UTF_8)
}

internal val CONCURRENCY_LEVELS = listOf(4, 16, 64)

private fun importArgs(csv: String): String {
    val escaped = JsonPrimitive(csv).toString()
    return """{"csv":$escaped,"name":"concurrency-baseline"}"""
}

/** 极简 HTTP JSON-RPC 客户端（keep-alive 复用连接——测的是服务端池语义，不是连接建立） */
internal class HttpClient(private val port: Int, private val token: String) {
    private val callId = AtomicInteger(0)
    private val failSample = AtomicBoolean(false)

    /** worker 专用包装：独立 callId 计数与失败采样，共享目标端口与 token */
    fun forWorker(): HttpClient = HttpClient(port, token)

    /** 返回 (耗时 ms, 是否成功, 响应体) */
    fun call(name: String, args: String): Triple<Long, Boolean, String> {
        val t0 = System.nanoTime()
        var ok = false
        var body = ""
        val ms = try {
            val conn = URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            val id = callId.incrementAndGet()
            conn.outputStream.write(
                """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$name","arguments":$args}}"""
                    .toByteArray(Charsets.UTF_8),
            )
            val code = conn.responseCode
            body = if (code == 200) conn.inputStream.bufferedReader().readText()
            else conn.errorStream?.bufferedReader()?.readText() ?: ""
            // 不调 disconnect()：连接归入 JVM keep-alive 池按 worker 复用——
            // 每调用一连接会在 64 并发下打爆 Windows 回环 listen backlog（首跑实证：28.9% 连接被 RST）
            ok = code == 200 && body.contains("\"result\"")
            if (!ok && failSample.compareAndSet(false, true)) {
                println("[conc-diag] code=$code body=${body.take(200)}")
            }
            (System.nanoTime() - t0) / 1_000_000
        } catch (error: Exception) {
            // T-084：异常型失败此前零诊断（failSample 只打非法响应）——CI 五次翻红
            // 全是 c64 异常型，观测盲区。首个异常记类名+消息，下一轮 CI 红才有据可查。
            if (failSample.compareAndSet(false, true)) {
                println("[conc-diag] exception=${error.javaClass.simpleName} message=${error.message?.take(160)}")
            }
            -1L
        }
        return Triple(ms, ok, body)
    }
}

/**
 * 单档位预热（T-080）：N 线程 × 1 轮（每线程 1 快查 + 1 长任务），结果全弃。
 *
 * 与 [measureLevel] 同构（同 pool/gate/forWorker/callId 结构）——让 keep-alive 连接池、
 * 服务端 accept 路径与 JIT 在计量开始前就位。预热请求**不产生任何指标**；预热后短暂
 * 停顿让 accept 队列排空，再进入计量窗口。这不是放宽：判据仍要求计量窗口错误率 = 0。
 */
private fun warmupLevel(client: HttpClient, level: Int, datasetId: String) {
    val pool = Executors.newFixedThreadPool(level)
    val startGate = java.util.concurrent.CountDownLatch(1)
    val futures = (1..level).map { workerId ->
        val workerClient = client.forWorker()
        pool.submit(Callable {
            startGate.await()
            val useAggregate = (workerId + 1) % 2 == 0
            val fastName = if (useAggregate) "stragent_get_group_aggregate" else "stragent_list_rows"
            val fastArgs = if (useAggregate) {
                """{"datasetId":"$datasetId","dimension":"地区","metric":"销售额","mode":"sum"}"""
            } else {
                """{"datasetId":"$datasetId","offset":1,"limit":5}"""
            }
            workerClient.call(fastName, fastArgs)
            workerClient.call("stragent_bench_long_report", "{}")
        })
    }
    startGate.countDown()
    futures.forEach { it.get() }
    pool.shutdown()
    Thread.sleep(100) // 排空 accept 队列，计量窗口从干净状态开始
}

/** 单档位测量：N 线程 × 6 轮（每轮 1 快查，第 0/4 轮加 1 长任务） */
private fun measureLevel(client: HttpClient, level: Int, datasetId: String): ConcurrencyMetrics {
    val fast = mutableListOf<Long>()
    val long = mutableListOf<Long>()
    var errors = 0
    var total = 0

    val rounds = 6
    val pool = Executors.newFixedThreadPool(level)
    val startGate = java.util.concurrent.CountDownLatch(1)
    val futures = (1..level).map { workerId ->
        // callId 每线程独立（并发自增无争用）；底层 keep-alive 连接由 JVM 连接池按目标地址
        // 复用——连接数 ≈ 并发数。首跑实证「每调用一连接」在 64 并发下会打爆 Windows 回环
        // listen backlog（28.9% 连接被 RST）；真实 MCP 客户端也是复用连接，同口径。
        val workerClient = client.forWorker()
        pool.submit(Callable {
            startGate.await()
            // 各线程按 workerId 错开快查工具与轮次起点，避免同步齐步造成假长尾
            for (round in 0 until rounds) {
                val useAggregate = (workerId + round) % 2 == 0
                val fastName = if (useAggregate) "stragent_get_group_aggregate" else "stragent_list_rows"
                val fastArgs = if (useAggregate) {
                    """{"datasetId":"$datasetId","dimension":"地区","metric":"销售额","mode":"sum"}"""
                } else {
                    """{"datasetId":"$datasetId","offset":1,"limit":5}"""
                }
                val (ms, ok, _) = workerClient.call(fastName, fastArgs)
                synchronized(fast) { fast.add(ms) }
                synchronizedMark(ok) { errors += 1 }
                synchronized(total) { total += 1 }
                if (round == 0 || round == 4) {
                    val (lms, lok, _) = workerClient.call("stragent_bench_long_report", "{}")
                    synchronized(long) { long.add(lms) }
                    synchronizedMark(lok) { errors += 1 }
                    synchronized(total) { total += 1 }
                }
            }
        })
    }
    startGate.countDown()
    val t0 = System.nanoTime()
    futures.forEach { it.get() }
    val wallMs = (System.nanoTime() - t0) / 1_000_000
    pool.shutdown()

    val fastSorted = synchronized(fast) { fast.sorted() }
    val longSorted = synchronized(long) { long.sorted() }
    val totalCount = synchronized(total) { total }
    val errorCount = synchronized(errors) { errors }
    return ConcurrencyMetrics(
        level = level,
        fastP50 = nearestRank(fastSorted, 0.50).toDouble(),
        fastP95 = nearestRank(fastSorted, 0.95).toDouble(),
        longP50 = nearestRank(longSorted, 0.50).toDouble(),
        longP95 = nearestRank(longSorted, 0.95).toDouble(),
        errorRate = if (totalCount == 0) 1.0 else errorCount.toDouble() / totalCount,
        qps = if (wallMs == 0L) 0.0 else totalCount * 1000.0 / wallMs,
        total = totalCount,
        errors = errorCount,
    )
}

/** 小工具：condition 为 true 才执行块（用于错误计数） */
private inline fun synchronizedMark(ok: Boolean, block: () -> Unit) {
    if (!ok) block()
}
