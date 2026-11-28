package com.stragent.bench.suites

import com.stragent.bench.model.SuiteResult
import com.stragent.core.ai.HedgeBudget
import com.stragent.core.ai.HedgedCall

/**
 * T-137 · LLM 调用对冲（hedging）的**尾延迟与成本**基线（D23 §5 第 3 条）。
 *
 * **测什么**（能确定性测的）：
 * ① **尾延迟改善**：桩端按「每 N 个请求里第 1 个慢」制造**偶发**慢（真实尾延迟的形状）
 *    → 对冲下慢调用的等待时间应被副本救回；
 * ② **成本上界**：对冲多花的请求数必须**恒不超过**预算比例承诺的值（这是对冲的**核心纪律**——
 *    尾延迟收益不假绿、成本不失控，两者并列）。
 *
 * **不测什么（如实登记）**：**真实端点的 P99 before/after 读数**。本地桩的慢调用分布是
 * **人造**的（固定周期 + 固定延迟），它能证明「机制按设计工作」，**不能**代表真实模型的
 * 尾延迟分布——真实 P99 收益须在 T-135（真实 Key 语义级验证轮）里用真实端点测。
 * 本套件因此只给 notes，**不产出**可用于跨提交比较的绝对 P99 数字。
 *
 * **桩的形状很关键**：慢必须是**偶发**的（个别请求撞上慢节点），而不是「前 N 个都慢」。
 * 后者会把副本也拖慢 → 对冲完全帮不上忙 → 测出来的「无收益」是桩的假象，不是机制的结论。
 *
 * 断言哲学（与 [runRetrievalLatency] 同款）：宽门限 + 语义等价 + 数字进 notes。
 */
fun runHedgeLatency(): SuiteResult {
    var passed = 0
    val total = 6
    val failures = mutableListOf<String>()
    val notes = mutableListOf<String>()

    fun check(name: String, ok: Boolean, detail: () -> String = { "" }) {
        if (ok) {
            passed++
        } else {
            failures += name + (detail().takeIf { it.isNotBlank() }?.let { "：$it" } ?: "")
        }
    }

    /**
     * 桩：每 [slowEveryN] 个请求里第 1 个慢 [slowMs]，其余快。
     *
     * 慢的**位置**按请求计数走，与「谁先发」无关 → 对冲时副本恰好落在快的那一侧
     * （主副本 #1 慢、副本 #2 快），正是「尾延迟被副本救回」的真实形状。
     */
    class TailLatencyStub(private val slowEveryN: Int, private val slowMs: Long) {
        val requests = java.util.concurrent.atomic.AtomicInteger()
        var slowCalls = 0

        fun call(): String {
            val n = requests.incrementAndGet()
            if (n % slowEveryN == 1) {
                slowCalls++
                Thread.sleep(slowMs)
            }
            return "答复"
        }
    }

    /** 跑一次「N 次调用」，返回 (总时延 ms, 实际请求数, 慢调用数) */
    fun measure(
        stub: TailLatencyStub,
        budget: HedgeBudget,
        hedgeAfterMs: Long,
        calls: Int,
    ): Triple<Long, Int, Int> {
        val hedged = HedgedCall(budget, hedgeAfterMs = hedgeAfterMs)
        val t0 = System.nanoTime()
        repeat(calls) { hedged.run({ stub.call() }) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        return Triple(ms, stub.requests.get(), stub.slowCalls)
    }

    try {
        // 场景：10 次调用，每 2 个请求里第 1 个慢 400ms（慢是**偶发**的）
        val calls = 10
        val slowEveryN = 2
        val slowMs = 400L

        // ① 不对冲（基线）：慢调用真等 → 总时延含 5 × 400ms
        val baseStub = TailLatencyStub(slowEveryN, slowMs)
        val baseBudget = HedgeBudget(0.0)
        val (baseMs, baseRequests, baseSlow) = measure(baseStub, baseBudget, 0L, calls)
        check("基线无对冲——请求数等于调用数", baseRequests == calls, { "requests=$baseRequests calls=$calls" })
        check("基线确有偶发慢调用", baseSlow >= 4, { "slow=$baseSlow（每 2 个请求 1 个慢，10 次调用应约 5 次）" })

        // ② 对冲 + 预算：慢调用应被副本救回
        val hedgeStub = TailLatencyStub(slowEveryN, slowMs)
        val hedgeBudget = HedgeBudget(1.0)     // 比例 1.0 → 上界宽（测试用）；成本另由③核
        val (hedgeMs, hedgeRequests, hedgeSlow) = measure(hedgeStub, hedgeBudget, 50L, calls)
        // 尾延迟改善：慢调用的 400ms 等待被 ~50ms 的副本救回
        // 判据用「基线减去对冲掉的时延」而非「对冲 < 基线/2」：后者卡在边界上，
        // 调度抖动就能假红。这里判「至少省下 3 次慢调用的等待」——实质且留足余量。
        check(
            "对冲后尾延迟实质改善（至少省下 3 次慢调用的等待）",
            baseMs - hedgeMs >= 3 * slowMs,
            { "base=${baseMs}ms hedge=${hedgeMs}ms 差 ${baseMs - hedgeMs}ms（一次慢调用 ${slowMs}ms）" },
        )
        check(
            "对冲后慢调用仍真实发生（不是被跳过——只是换了谁先回）",
            hedgeSlow >= 4,
            { "slow=$hedgeSlow" },
        )

        // ③ 成本上界：额外请求数不得超过「完成数 × 比例」
        val ratio = 0.5
        val boundStub = TailLatencyStub(slowEveryN, slowMs)
        val boundBudget = HedgeBudget(ratio)
        val (_, boundRequests, _) = measure(boundStub, boundBudget, 20L, calls)
        val extra = boundRequests - calls
        val allowed = (calls.toDouble() * ratio).toInt()
        check(
            "成本上界——额外请求数不超过预算比例承诺",
            extra <= allowed,
            { "extra=$extra allowed=$allowed requests=$boundRequests calls=$calls" },
        )

        // ④ 关闭态零差异：比例 0 时不多发一次请求
        val offStub = TailLatencyStub(slowEveryN, slowMs)
        val offBudget = HedgeBudget(HedgeBudget.DEFAULT_RATIO)
        val (_, offRequests, _) = measure(offStub, offBudget, 50L, calls)
        check(
            "关闭态零差异——不多发一次请求",
            offRequests == calls,
            { "requests=$offRequests calls=$calls" },
        )

        notes.add(
            "T-137 对冲基线：10 次调用 / 每 2 个请求 1 个慢 400ms —— 不对冲 ${baseMs}ms（${baseRequests} 次请求）" +
                " vs 对冲 ${hedgeMs}ms（${hedgeRequests} 次请求，省 ${baseMs - hedgeMs}ms）；" +
                "成本上界探测（比例 0.5）：${boundRequests} 次请求 / 额外 $extra ≤ 允许 $allowed；" +
                "关闭态 ${offRequests} 次请求（不多发）" +
                "（**本地桩的人造偶发慢分布，只证明机制按设计工作；真实端点 P99 收益待 T-135 真实 Key 轮测**）",
        )
    } finally {
        // 无需清理（桩为进程内对象）
    }
    return SuiteResult(
        name = "hedge-latency",
        passed = passed,
        total = total,
        failures = failures,
        notes = notes.joinToString("；"),
    )
}
