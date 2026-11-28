package com.stragent.core.ai

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-137 · 对冲**预算闸门**的纯逻辑契约测试（不涉传输层）。
 *
 * **本件的靶心是「成本上界」**，不是「时延收益」——收益要靠真实端点的慢调用分布才能测
 * （见 D7 T-137 边界与 bench `hedge-latency` 套件），而**上界是可以在本地钉死的**：
 * 对冲多花的调用量必须**恒不超过**预算比例承诺的值。
 *
 * 端到端（真实 HTTP 桩 + 真实客户端）在 `data` 模块的 `HedgeBudgetEndToEndTest`——
 * 因为它要验 `HttpLlmClient` 的接线，跨模块不可见故分置。
 *
 * 四组：① 默认关闭；② 上界可核（任意调用序列下恒 ≤ 配额）；③ 突发封顶（不连发）；
 * ④ 额度发放只在可对冲通道发生（流式不发放）。
 */
class HedgeBudgetTest {

    @Test
    fun `① 默认关闭——比例 0 时恒不发副本`() {
        val budget = HedgeBudget(HedgeBudget.DEFAULT_RATIO)
        assertFalse(budget.enabled, "默认比例 0 = 对冲关闭")
        repeat(50) {
            budget.onCallStarted()
            assertFalse(budget.tryConsumeHedge(), "关闭态下恒不可对冲")
        }
        assertEquals(0, budget.hedgeCount)
        assertEquals(50, budget.callCount)
    }

    @Test
    fun `② 上界可核——任意调用序列下副本数恒不超过配额`() {
        val ratio = 0.2
        val budget = HedgeBudget(ratio)
        var sent = 0L
        // 交替：正常完成 → 尝试对冲（额度够就发），模拟最激进的调用方
        repeat(200) {
            budget.onCallStarted()
            if (budget.tryConsumeHedge()) sent++
            // 不变量：已发副本数 ≤ 配额（= floor(调用数 × 比例)）
            assertTrue(
                budget.hedgeCount <= budget.quota,
                "已发 ${budget.hedgeCount} > 配额 ${budget.quota}（比例 $ratio）",
            )
        }
        assertEquals(sent, budget.hedgeCount)
        // 上界收敛到比例附近：完成 200 次、比例 0.2 → 配额 40
        assertTrue(budget.quota <= 40L, "配额不应超过 floor(200 × 0.2) = 40，实际 ${budget.quota}")
    }

    @Test
    fun `③ 突发封顶——不连发，代价上限不瞬时放大`() {
        // 比例 1.0 + 额度上限 1：攒够 1 个只能发 1 个，发完额度归零 → 下次要再攒
        val budget = HedgeBudget(1.0)
        budget.onCallStarted()
        assertTrue(budget.tryConsumeHedge(), "额度 1.0 → 可发 1 个")
        assertFalse(budget.tryConsumeHedge(), "额度已归零 → 不得连发（突发上限生效）")
        assertEquals(1L, budget.hedgeCount)
        // 再攒一次调用 → 又能发 1 个
        budget.onCallStarted()
        assertTrue(budget.tryConsumeHedge(), "补一次调用后额度回到 1.0")
        assertEquals(2L, budget.hedgeCount)
        assertEquals(2L, budget.quota, "配额 = floor(2 × 1.0) = 2")
    }

    @Test
    fun `④ 向下取整——小计数时不因取整多发一个`() {        // 比例 0.2、完成 1 次 → 额度 0.2 < 1 → 发不出副本
        val budget = HedgeBudget(0.2)
        budget.onCallStarted()
        assertEquals(0.2, budget.creditBalance, "完成 1 次、比例 0.2 → 额度 0.2（连续量，不取整）")
        assertFalse(budget.tryConsumeHedge(), "额度不足 1 → 不得发副本")
        assertEquals(0L, budget.quota, "配额 = floor(1 × 0.2) = 0")
    }

    @Test
    fun `⑤ 额度累积——5 次调用攒够 1 个副本`() {
        val budget = HedgeBudget(0.2)
        repeat(4) { budget.onCallStarted() }
        assertFalse(budget.tryConsumeHedge(), "4 次调用 → 额度 0.8，不够")
        budget.onCallStarted()
        assertTrue(budget.tryConsumeHedge(), "5 次调用 → 额度 1.0，够发 1 个")
        assertEquals(1L, budget.hedgeCount)
    }

    // ==================== 执行器（先到先用 / 败者真中断） ====================

    @Test
    fun `⑥ 慢调用触发对冲——先到先用取快的那份`() {
        val budget = HedgeBudget(1.0)
        budget.onCallStarted()
        val hedged = HedgedCall(budget, hedgeAfterMs = 100)
        val calls = AtomicInteger()
        val result = hedged.run({
            calls.incrementAndGet()
            // 第一个副本慢（模拟尾延迟），第二个快
            if (calls.get() == 1) Thread.sleep(3_000) else "快的副本"
        })
        assertEquals("快的副本", result, "应取先到的那份")
        assertEquals(2, calls.get(), "应发了 1 个副本（主 + 副本）")
        assertEquals(1L, budget.hedgeCount, "副本数应记账")
    }

    @Test
    fun `⑦ 快调用不发副本——省成本`() {
        val budget = HedgeBudget(1.0)
        budget.onCallStarted()
        val hedged = HedgedCall(budget, hedgeAfterMs = 2_000)
        val calls = AtomicInteger()
        val result = hedged.run({
            calls.incrementAndGet()
            "主副本"
        })
        assertEquals("主副本", result)
        assertEquals(1, calls.get(), "够快就不该发副本")
        assertEquals(0L, budget.hedgeCount)
    }

    /**
     * ⑧ **败者真被中断**——本轮最关键的实测结论钉在这里。
     *
     * `CompletableFuture.cancel(true)` **不中断**运行中的任务（JDK 文档：
     * `mayInterruptIfRunning` 对 CompletableFuture 无效）——用它等于假取消，败者会继续烧钱。
     * 本仓用 `Future.cancel(true)`（会中断工作线程），故败者必须**真的**被打断。
     *
     * 判据：败者的阻塞点在被打断后**立刻**解除（不是等它自然跑完 3 秒）。
     */
    @Test
    fun `⑧ 败者真被中断——不继续烧供应商侧的算力`() {
        val budget = HedgeBudget(1.0)
        budget.onCallStarted()
        val hedged = HedgedCall(budget, hedgeAfterMs = 100)
        val loserInterrupted = java.util.concurrent.CountDownLatch(1)
        val calls = AtomicInteger()
        val t0 = System.currentTimeMillis()
        val result = hedged.run({
            if (calls.incrementAndGet() == 1) {
                // 主副本：睡 3 秒（模拟慢响应）。若被真中断，这里会抛 InterruptedException
                try {
                    Thread.sleep(3_000)
                } catch (e: InterruptedException) {
                    loserInterrupted.countDown()
                    throw e
                }
                "慢副本"
            } else {
                Thread.sleep(50)
                "快副本"
            }
        })
        val elapsed = System.currentTimeMillis() - t0
        assertEquals("快副本", result)
        // 中断是**异步投递**的（中断标志置位后，被中断的线程要到下一个检查点才抛异常），
        // 故这里等败者真正察觉（带上限，失败即判定「没被打断」）。
        val noticed = loserInterrupted.await(2, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(
            noticed,
            "败者必须被真中断（不是只标记 future）——否则它会继续烧供应商侧的算力",
        )
        assertTrue(
            elapsed < 2_000,
            "总耗时 $elapsed ms 应远小于败者自然跑完的 3 秒（说明败者被提前打断）",
        )
    }

    @Test
    fun `⑨ 关闭态零差异——不多发一次调用`() {
        val budget = HedgeBudget(0.0)
        val hedged = HedgedCall(budget, hedgeAfterMs = 50)
        val calls = AtomicInteger()
        repeat(5) {
            hedged.run({ calls.incrementAndGet(); "答复" })
        }
        assertEquals(5, calls.get(), "关闭态不得多发一次请求")
        assertEquals(0L, budget.hedgeCount)
    }
}
