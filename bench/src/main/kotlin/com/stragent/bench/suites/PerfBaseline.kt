package com.stragent.bench.suites

import com.stragent.bench.model.SuiteResult
import com.stragent.core.csv.Csv
import com.stragent.core.csv.ColumnType
import com.stragent.engine.duckdb.DuckDbEngine
import java.io.File

/**
 * 套件七 · perf-baseline（性能基准）—— D7 T-006 的落地。
 *
 * 验收口径（T-006）：**100K 行 CSV 的解析耗时与内存峰值**（此前只验证到 10K，
 * 也就是只验证了「软约束以内」，从没量过「软约束外面有多大」）。
 *
 * 为什么要这道门：
 *  - tier-1 的 10K 帽不是能力上限，是**经验阈值**——帽子的另一侧到底是 200ms 还是 20s，
 *    决定了要不要劝用户走 tier-2，也决定了「切档线 10K」这条默认值该不该动；
 *  - 内存是 JVM 侧唯一会**把进程打死**的维度：耗时超标只是慢，堆超标是崩溃；
 *  - 没有基线数字，任何「优化了性能」的说法都不可证伪。这道门给出可复算的数。
 *
 * 三条口径纪律：
 *  1. 数据完全确定性（同 LargeData：行号决定一切），所以耗时差异只可能来自代码变化；
 *  2. 门限**宽松**（解析 < 4000ms、驻留 < 768MB）——这台机器上还跑着别的会话，
 *     紧门限只会产出假红；真正的价值是 notes 里的数字随提交可对比；
 *  3. 内存有两个并列口径，互不冒称：
 *     - **驻留量** = 「GC 后基线 → 解析后驻留」的差值（老口径，看「解析完留下了多少」）；
 *     - **采样峰值下限** = 10ms 守护线程在解析期读已用堆取 max（P-038 插桩）——
 *       间隔内一瞬分配可能漏记，漏记方向只会偏低，所以真峰值 ≥ max(采样峰值, 驻留)；
 *       它不是分配剖析，只回答「有没有接近把进程打死过」。
 *
 * 行数可覆盖：`-Dbench.perf.rows=N`（默认 100_000）。
 */
/** 测量重复次数（min-of-5：噪声只会让每次更慢，最小值才是回归检测该用的统计量） */
private const val PERF_RUNS = 5

fun runPerfBaseline(): SuiteResult {
    val rows = System.getProperty("bench.perf.rows")?.toIntOrNull() ?: 100_000
    val groups = 8
    var passed = 0
    var total = 0
    val failures = mutableListOf<String>()

    fun check(condition: Boolean, failure: String) {
        total += 1
        if (condition) passed += 1 else failures.add(failure)
    }

    // ---- 1. 确定性数据 + 解析期就算出真值 ----
    val truthSum = DoubleArray(groups)
    val truthCount = IntArray(groups)
    val text = buildString(rows * 24) {
        appendLine("id,分类,金额,备注")
        for (i in 0 until rows) {
            val g = i % groups
            val value = ((i * 41) % 997) * 1.25 + 0.5
            truthSum[g] += value
            truthCount[g] += 1
            // 备注带引号包裹且内含逗号：让解析走「引号段不切断」这条真实路径，而不是最快路径
            appendLine("$i,分类$g,$value,\"备注,第${i}条\"")
        }
    }
    val textBytes = text.toByteArray(Charsets.UTF_8).size

    // ---- 2. 完整解析（不截断）：耗时 + 口径 ----
    // P-231：测量取 **5 次的最小值**（与 brief-latency 同一纪律：min-of-5）。
    // 为什么必须 min-of-5：CI runner 是**共享机器**，单次测量噪声实测 ±25%
    // （run 2804/2861 的同一份代码量到 345–433ms），而回归检测要回答的是
    // 「代码变慢了吗」——噪声只会让每次更慢，**最小值**才是该用的统计量
    // （真退化会让 5 次全部变慢，最小值照样抬头）。
    // 内存同理：采样峰值取 5 次里的最小值（污染最少的那一次）。
    var parseMs = Long.MAX_VALUE
    var sampledPeakMb = Long.MAX_VALUE
    var heapBefore = 0L
    var heapAfterFull = 0L
    var full: com.stragent.core.csv.ParsedTable? = null
    repeat(PERF_RUNS) {
        gc()
        val before = usedHeapMb()
        val sampler = PeakSampler(intervalMs = 10)
        sampler.start()
        val start = System.nanoTime()
        val parsed = Csv.parseDelimitedFull(text)
        val ms = (System.nanoTime() - start) / 1_000_000
        val peak = sampler.stop()
        // 驻留口径是「解析完留下了多少」：先 GC 再量，否则量到的是解析期尚未回收的垃圾
        // （full 仍强引用，不会被回收）。两轮实测 222MB/277MB 的抖动正是垃圾口径的证据。
        gc()
        val after = usedHeapMb()
        if (full == null) full = parsed
        if (ms < parseMs) parseMs = ms
        if (peak < sampledPeakMb) {
            sampledPeakMb = peak
            heapBefore = before
            heapAfterFull = after
        }
    }
    val parsed = full ?: error("解析未产出结果")

    check(parsed.rows.size == rows, "完整解析行数 ${parsed.rows.size} != $rows")
    check(parsed.columns.size == 4, "列数 ${parsed.columns.size} != 4")
    check(!parsed.truncated, "parseDelimitedFull 不该标 truncated")
    check(
        parsed.columnTypes == listOf(ColumnType.NUMBER, ColumnType.TEXT, ColumnType.NUMBER, ColumnType.TEXT),
        "列类型推断不对：${parsed.columnTypes}",
    )
    // 引号内含逗号必须落在同一个字段里（否则规模一大就悄悄多列）
    check(parsed.rows.first()[3] == "备注,第0条", "引号字段被逗号切断：${parsed.rows.first()[3]}")

    val parseLimitMs = 4_000L
    check(parseMs < parseLimitMs, "完整解析耗时 ${parseMs}ms ≥ ${parseLimitMs}ms")

    val residentMb = (heapAfterFull - heapBefore).coerceAtLeast(0)
    val residentLimitMb = 768
    check(residentMb < residentLimitMb, "解析后堆驻留 ${residentMb}MB ≥ ${residentLimitMb}MB")

    // 采样峰值（下限口径）：真峰值 ≥ max(采样峰值, 驻留)；门限刻意再放宽一档，
    // 它的职责是拦「接近把进程打死」这类灾难，不是做细粒度回归门
    check(sampledPeakMb > 0, "堆采样器没有采到任何样本（守护线程根本没跑）")
    val peakDuringParseMb = (maxOf(sampledPeakMb, heapAfterFull) - heapBefore).coerceAtLeast(0)
    val peakLimitMb = 2_048
    check(peakDuringParseMb < peakLimitMb, "解析期采样峰值下限 ${peakDuringParseMb}MB ≥ ${peakLimitMb}MB")

    // ---- 3. 10K 软约束路径仍然生效（帽子内外都要有数）----
    val cappedStart = System.nanoTime()
    val capped = Csv.parseDelimited(text)
    val cappedMs = (System.nanoTime() - cappedStart) / 1_000_000
    check(capped.rows.size == Csv.MAX_ROWS, "截断路径行数 ${capped.rows.size} != ${Csv.MAX_ROWS}")
    check(capped.truncated && capped.totalRows == rows, "截断标记不对：truncated=${capped.truncated} totalRows=${capped.totalRows}")

    // ---- 4. 聚合口径在 100K 上与解析解一致（不只是「解析出来了」）----
    val aggStart = System.nanoTime()
    val sums = DoubleArray(groups)
    val counts = IntArray(groups)
    for (row in parsed.rows) {
        val g = row[1].removePrefix("分类").toInt()
        sums[g] += row[2].toDouble()
        counts[g] += 1
    }
    val aggregateMs = (System.nanoTime() - aggStart) / 1_000_000
    var mismatch = -1
    for (g in 0 until groups) {
        if (counts[g] != truthCount[g] || kotlin.math.abs(sums[g] - truthSum[g]) > 1e-6 * maxOf(1.0, truthSum[g])) {
            mismatch = g
            break
        }
    }
    check(mismatch < 0, if (mismatch < 0) "" else "分组真值对不上（分组 $mismatch）")

    // ---- 5. tier-2 同规模对照（可用时才算，不可用就明说不评）----
    var columnarMs = -1L
    var columnarNote = "tier-2 不可用，未评（DuckDB 原生库缺失）"
    if (DuckDbEngine.isAvailable()) {
        val file = File.createTempFile("str-agent-perf-", ".csv")
        file.deleteOnExit()
        file.writeText(text, Charsets.UTF_8)
        val start = System.nanoTime()
        DuckDbEngine.fromCsvFile(file).use { engine ->
            columnarMs = (System.nanoTime() - start) / 1_000_000
            val items = engine.aggregateBy(1, 2, "sum", topN = groups)
            val top = items.maxByOrNull { it.value }
            check(engine.rowCount == rows, "tier-2 行数 ${engine.rowCount} != $rows")
            check(top != null && top.count == truthCount[groups - 1], "tier-2 top 组计数不对：${top?.label}/${top?.count}")
        }
        file.delete()
        columnarNote = "tier-2 读入+建表 ${columnarMs}ms"
        check(columnarMs < parseLimitMs, "tier-2 读入耗时 ${columnarMs}ms ≥ ${parseLimitMs}ms")
    }

    val perRowMicros = if (rows > 0) parseMs * 1000.0 / rows else 0.0
    return SuiteResult(
        name = "perf-baseline",
        passed = passed,
        total = total,
        failures = failures.filter { it.isNotBlank() }.take(8),
        notes = "$rows 行 / ${(textBytes / 1024 / 1024.0).let { "%.2fMB".format(it) }} 文本：" +
            "tier-1 完整解析 ${parseMs}ms（≈%.1fµs/行，门限 ${parseLimitMs}ms），"
                .format(perRowMicros) +
            "解析后堆驻留 ${residentMb}MB（门限 ${residentLimitMb}MB，GC 后基线差值口径），" +
            "解析期采样峰值下限约 ${peakDuringParseMb}MB（10ms 采样口径，真峰值只高不低，门限 ${peakLimitMb}MB），" +
            "10K 截断路径 ${cappedMs}ms，内存侧分组聚合 ${aggregateMs}ms；$columnarNote。" +
            "行数覆盖：-Dbench.perf.rows=N",
    )
}

/** 显式 GC 并留一点时间让软/API 内存归还，减少驻留测量的噪声 */
internal fun gc() {
    repeat(3) {
        System.gc()
        Thread.sleep(30)
    }
}

internal fun usedHeapMb(): Long {
    val rt = Runtime.getRuntime()
    return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
}

/**
 * 解析期堆采样器：守护线程按固定间隔读「已用堆」并记最大值。
 *
 * 口径诚实：这是**采样峰值的下限**——两次采样之间的一瞬分配可能漏记，
 * 漏记方向只会偏低（所以它能回答「有没有接近打死进程」，不能回答「峰值精确是多少」）。
 * 守护线程 + stop() 带 join 兜底：套件异常退出也不会把采样线程挂在 JVM 里。
 */
internal class PeakSampler(private val intervalMs: Long) {
    @Volatile private var running = false
    @Volatile private var peakMb = 0L
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread {
            val rt = Runtime.getRuntime()
            while (running) {
                val used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
                if (used > peakMb) peakMb = used
                try {
                    Thread.sleep(intervalMs)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }
        thread!!.isDaemon = true
        thread!!.start()
    }

    /** 停止并返回采样到的峰值（MB）；一秒内 join 不上也不阻塞套件 */
    fun stop(): Long {
        running = false
        thread?.join(1_000)
        return peakMb
    }
}
