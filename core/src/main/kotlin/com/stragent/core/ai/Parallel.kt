package com.stragent.core.ai

import com.stragent.core.config.Config
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * 有界并行扇出（T-129，D23 W-F）——独立子任务的 map 助手。
 *
 * 业界借鉴：LangGraph Send 模式（map-reduce 扇出+归并）——「最大时延改进来自有界并发的
 * 独立 Map 调用」；仓内先例 EditorialDept（6 记者并行）。要点：**有界**（并发上限防限流
 * 风暴——Tail at Scale 预算纪律的轻量版）、**保序**（结果按输入序归并，与串行逐字节一致）、
 * **协作式取消**（每任务执行前自查 cancelSignal；HTTP 调用进行中不可中断是既有边界，
 * ChatStep.kt 同款）。
 *
 * 失败语义双模式：
 * - [mapLimited]（failFast）：首个异常取消剩余并原样上抛——ctx/摘要树/OCR 的整调用失败语义；
 * - [mapLimitedCollecting]：任一失败不影响其他，逐项 [Result]——map 要点的
 *   「逐块失败→failed 列表」语义。
 * 两者结果都保证与串行一致（同输入同输出，除时序）。per-item 超时参数位留给对冲后续
 * （D23 §5），本轮不启用。
 */
object Parallel {

    /** 默认并发（EditorialDept 先例 + TextSummarizer 计划值「并发 3」）；env 覆盖，1=串行 */
    val DEFAULT_CONCURRENCY: Int =
        Config.get("STRAGENT_TEXT_CONCURRENCY")?.toIntOrNull()?.coerceIn(1, 16) ?: 3

    /**
     * failFast 扇出：任一任务异常 → **取消剩余** → 原样上抛首个异常。
     *
     * ## 为什么独立实现（不再复用 [mapLimitedCollecting]）
     *
     * 原实现是 `mapLimitedCollecting(...).map { it.getOrThrow() }`——而收集式的契约是
     * 「**每个任务都被尝试**」，它必须等齐全部任务才返回。于是 failFast 名不副实：
     *  ① 首个任务失败时，**剩余任务照样全部跑完**（没有取消）；
     *  ② 异常要等**所有任务跑完**才抛出（没有提前返回）。
     *
     * 实测（T-144 修复前）：5 个任务里第 1 个立刻失败，其余 4 个各睡 200ms——
     * `executed` 计数是 **5**（全部执行），且整体耗时被最慢的任务拖住。
     * 这与本 KDoc 承诺的「首个异常取消剩余」**不符**。
     *
     * 本实现用 [ExecutorCompletionService]：按**完成顺序**取结果，
     *  ① 首个失败 → 对所有 future `cancel(true)`（**中断**正在跑的线程，
     *     并阻止排队中的任务启动）→ 原样上抛首个异常；
     *  ② 结果仍按**输入序**归并（保序契约不变，与串行逐字节一致）。
     */
    fun <T : Any, R> mapLimited(
        items: List<T>,
        concurrency: Int = DEFAULT_CONCURRENCY,
        cancelSignal: () -> Boolean = { false },
        block: (T) -> R,
    ): List<R> {
        if (items.isEmpty()) return emptyList()
        if (concurrency <= 1 || items.size == 1) {
            // 串行退化：零线程开销，语义与并行一致
            return items.map { item ->
                if (cancelSignal()) throw GenerationCancelled()
                block(item)
            }
        }
        val pool: ExecutorService = Executors.newFixedThreadPool(minOf(concurrency, items.size)) { r ->
            Thread(r, "str-agent-fanout").apply { isDaemon = true }
        }
        val completion = ExecutorCompletionService<IndexedValue<R>>(pool)
        val futures = mutableListOf<Future<IndexedValue<R>>>()
        try {
            items.forEachIndexed { index, item ->
                // 提交侧自查：置位后不再有新提交（T-129 取消契约）
                if (cancelSignal()) throw GenerationCancelled()
                futures += completion.submit(Callable {
                    if (cancelSignal()) throw GenerationCancelled()
                    IndexedValue(index, block(item))
                })
            }
            val out = arrayOfNulls<Any?>(items.size)
            var done = 0
            while (done < futures.size) {
                val completed = completion.take()
                val value = try {
                    completed.get()
                } catch (e: java.util.concurrent.ExecutionException) {
                    // 首个失败：**取消剩余**（中断运行中的、阻止排队中的）→ 原样上抛
                    futures.forEach { it.cancel(true) }
                    val cause = e.cause ?: e
                    // 取消是协作式全局信号，优先于「首个业务异常」的语义
                    if (cause is GenerationCancelled) throw cause
                    throw cause
                }
                out[value.index] = value.value
                done += 1
            }
            @Suppress("UNCHECKED_CAST")
            return out.map { it as R }
        } finally {
            pool.shutdownNow()
        }
    }

    /** 收集式扇出：逐项 Result，失败不互相影响（调用方按需归并 failed 列表） */
    fun <T : Any, R> mapLimitedCollecting(
        items: List<T>,
        concurrency: Int = DEFAULT_CONCURRENCY,
        cancelSignal: () -> Boolean = { false },
        block: (T) -> R,
    ): List<Result<R>> {
        if (items.isEmpty()) return emptyList()
        if (concurrency <= 1 || items.size == 1) {
            // 串行退化：零线程开销，语义与并行一致
            return items.map { item ->
                if (cancelSignal()) throw GenerationCancelled()
                runCatching { block(item) }
            }
        }
        val pool: ExecutorService = Executors.newFixedThreadPool(minOf(concurrency, items.size)) { r ->
            Thread(r, "str-agent-fanout").apply { isDaemon = true }
        }
        try {
            val futures = items.map { item ->
                // 提交侧自查：置位后不再有新提交（T-129 取消契约）
                if (cancelSignal()) throw GenerationCancelled()
                pool.submit(Callable {
                    if (cancelSignal()) throw GenerationCancelled()
                    block(item)
                })
            }
            // 收集式契约：每个任务都被尝试（任一失败不影响其他）；不抛业务异常。
            // GenerationCancelled 例外——取消是协作式全局信号，传播优先于逐项收集。
            return futures.map { f: Future<R> ->
                try {
                    Result.success(f.get())
                } catch (e: java.util.concurrent.ExecutionException) {
                    val cause = e.cause ?: e
                    if (cause is GenerationCancelled) throw cause
                    Result.failure(cause)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw GenerationCancelled()
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
