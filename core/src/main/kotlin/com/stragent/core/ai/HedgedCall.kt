package com.stragent.core.ai

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * T-137 · LLM 调用对冲的执行器：**慢了就发一个并发副本，先到先用**。
 *
 * **只对「不吐片段」的调用有意义**：流式通道的硬契约是「增量拼接 = 最终正文」，
 * 两个副本同时吐片段会让客户端收到重复内容（内容重复 = 假账，且比慢更糟）。
 * 因此对冲只在 [com.stragent.core.ports.LlmClient.chat] 这条**整包返回**的通道上启用；
 * 流式通道的对冲需要另一套「片段去重」设计——本轮不做（见 D7 T-137 边界）。
 *
 * **先到先用**的语义：两个副本返回的内容**可能不同**（推理模型的采样差异）。
 * 谁先到用谁——Tail at Scale 的原始语义（后到的副本被丢弃）。
 * 被丢弃的副本**仍可能已在供应商侧计费**（请求已发出）——这是对冲的固有成本，
 * 由 [HedgeBudget] 的配额封顶。
 *
 * ## 败者怎么停（实测结论，勿凭直觉改）
 *
 * 败者必须**真中断**，否则它会继续占着供应商侧的算力与本地的连接：
 * - `CompletableFuture.cancel(true)` **不中断**运行中的任务（JDK 文档：`mayInterruptIfRunning`
 *   对 CompletableFuture 无效）——实测任务照常跑完整个 sleep。**用它 = 假取消**。
 * - `Future.cancel(true)`（[ExecutorService.submit] 的返回值）**会**中断工作线程；
 *   而中断能真正打断在途的 `HttpClient.send`（实测：服务端要 5 秒，客户端 408ms 就返回
 *   `InterruptedException`）——这才是「停掉败者」的真手段。
 *
 * 所以本类用 [ExecutorService.submit] + [Future.cancel]，并**额外**记录工作线程
 * 直接 `interrupt()`——因为「取消」的信号送达是异步的（中断标志置位后，
 * 被中断的线程要在下一次检查点才抛出 [InterruptedException]），
 * 直接打断能确定地停掉在途的 HTTP 读。
 */
class HedgedCall(
    /** 预算闸门：唯一决定「能不能再发一个副本」的地方 */
    private val budget: HedgeBudget,
    /** 对冲延迟阈值（毫秒）：超过它还没回，就发副本 */
    private val hedgeAfterMs: Long,
    /**
     * 副本用的线程池（daemon）。
     *
     * **至少 2 个线程**：主副本正占着线程等慢响应时，副本若排在它后面就成了串行的，
     * 对冲完全失效（实测：端到端只发出 1 次请求）。
     */
    private val executor: ExecutorService = defaultExecutor(),
) {

    /** 本客户端已发出的对冲副本总数（观测用：应与预算一致，不超上界） */
    private val hedgedCalls = java.util.concurrent.atomic.AtomicLong()

    /** 已发出的副本数（观测用） */
    val hedgedCount: Long get() = hedgedCalls.get()

    /**
     * 执行一次带对冲的调用。
     *
     * @param call 正常路径的调用（**每次对冲会被调用两次**：主副本 + 至多一个副本）
     * @param onHedge 发出副本时的回调（测试与观测用）
     */
    fun <T> run(call: () -> T, onHedge: (() -> Unit)? = null): T {
        // 额度发放：**只算可对冲的通道**（流式通道不发放——见 [HedgeBudget] 的说明）
        budget.onCallStarted()
        // 对冲关闭（比例 0）或阈值非法 → 单副本，与既有行为逐字节一致
        if (!budget.enabled || hedgeAfterMs <= 0) {
            return call()
        }
        val main = launch(call)
        return try {
            main.future.get(hedgeAfterMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            // 主副本超过阈值还没回 → 看预算是否允许发副本
            if (!budget.tryConsumeHedge()) {
                // 配额用尽 → 就等主副本（不追加成本）；主副本的异常照原样抛
                return try {
                    main.future.get()
                } catch (ex: ExecutionException) {
                    throw ex.cause ?: ex
                }
            }
            hedgedCalls.incrementAndGet()
            onHedge?.invoke()
            val copy = launch(call)
            try {
                // 等**先到的那个**（谁先完成用谁）。
                // anyOf 返回最先完成的**那个** future 的值（类型擦除成 Any?）——
                // 这里用 whenComplete 把它收敛回 T，避免到处强转。
                val winner = CompletableFuture<T>()
                main.completion.whenComplete { v, e ->
                    if (e == null) winner.complete(v) else winner.completeExceptionally(e)
                }
                copy.completion.whenComplete { v, e ->
                    if (e == null) winner.complete(v) else winner.completeExceptionally(e)
                }
                try {
                    winner.get()
                } catch (ex: ExecutionException) {
                    // 先到的那份**失败**了：仍取主副本的结果（语义与单副本一致：报主因，
                    // 不把副本的偶发错误当成本次失败）。主副本若也失败，其异常照原样抛出。
                    main.future.get()
                }
            } finally {
                // 败者**真中断**（打断工作线程 → 停掉在途 HTTP；Future.cancel 兜底取消任务）。
                // 不这么做，败者会继续烧供应商侧的算力（本仓最忌讳的「花的钱没账」）。
                main.stop()
                copy.stop()
            }
        } catch (e: ExecutionException) {
            // 主副本失败（非超时）：语义与单副本一致——原样抛出，不发副本
            main.stop()
            throw e.cause ?: e
        }
    }

    /**
     * 提交一次调用。
     *
     * 返回两份句柄：
     * - [Launched.completion] —— 完成态视图（供「先到先用」竞速）；
     * - [Launched.future] —— 任务句柄（供 [Future.cancel]）；
     * - 工作线程引用 —— 供**直接 interrupt**（取消信号的送达是异步的，直接打断更确定）。
     */
    private fun <T> launch(call: () -> T): Launched<T> {
        val completion = CompletableFuture<T>()
        val runner = AtomicReference<Thread?>()
        // 显式包成 Callable：Kotlin 的 `submit { }` 会选到 Runnable 重载，丢掉返回类型 T。
        // 这里让 Callable 真正返回结果（不是 completion 的完成态），
        // 这样 `future.get()` 拿到的就是调用结果，异常也由 Future 原样包成 ExecutionException。
        val future = executor.submit(java.util.concurrent.Callable<T> {
            runner.set(Thread.currentThread())
            try {
                val value = call()
                completion.complete(value)
                value
            } catch (t: Throwable) {
                // 必须显式完成为异常态：否则「先到先用」的竞速会永远等下去（挂死）
                completion.completeExceptionally(t)
                throw t
            }
        })
        return Launched(completion, future, runner)
    }

    /** 一次提交的三份句柄 */
    private class Launched<T>(
        /** 完成态视图（值或异常） */
        val completion: CompletableFuture<T>,
        /** 任务句柄：取消任务本身（带返回类型，取结果时不用强转） */
        val future: Future<T>,
        /** 工作线程引用：直接打断（真中断） */
        private val runner: AtomicReference<Thread?>,
    ) {
        /**
         * 停掉这次调用：直接打断工作线程（打断在途 HTTP 读）+ 取消任务句柄。
         *
         * 两者都做：打断负责「立刻停」，取消负责「还没开始的任务别跑」。
         */
        fun stop() {
            runCatching { runner.get()?.interrupt() }
            runCatching { future.cancel(true) }
        }
    }

    private companion object {
        /**
         * 副本池：**2 个线程**（主副本 + 副本各占一个，才能真正并行）。
         *
         * 单线程是错的：主副本正占着线程等慢响应，副本只能**排在它后面**——副本成了串行的，
         * 对冲完全失效（实测：端到端只发出 1 次请求）。
         */
        fun defaultExecutor(): ExecutorService =
            Executors.newFixedThreadPool(2) { r ->
                Thread(r, "str-agent-hedge").apply { isDaemon = true }
            }
    }
}
