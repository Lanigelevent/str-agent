package com.stragent.data

import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-048：`/events` 有界队列的丢帧语义（容量、计数、顺序、超时）。
 *
 * 这条测试保护的是「慢消费者不许拖垮生成主链」：队列满时 `offer` 必须立刻返回
 * false 并计数，而不是阻塞或无限增长。
 */
class SseEventQueueTest {

    @Test
    fun `先进先出——按入队顺序取出`() {
        val queue = SseEventQueue(4)
        listOf("a", "b", "c").forEach { assertTrue(queue.offer(it)) }
        assertEquals("a", queue.poll(10))
        assertEquals("b", queue.poll(10))
        assertEquals("c", queue.poll(10))
        assertEquals(0, queue.droppedCount())
    }

    @Test
    fun `有界——满后非阻塞丢弃并计数`() {
        val queue = SseEventQueue(2)
        assertTrue(queue.offer("1"))
        assertTrue(queue.offer("2"))
        assertFalse(queue.offer("3"), "第 3 条应被丢弃而不是阻塞")
        assertFalse(queue.offer("4"))
        assertEquals(2, queue.droppedCount())
        assertEquals(2, queue.size())
        // 丢弃不影响已入队事件
        assertEquals("1", queue.poll(10))
        assertEquals("2", queue.poll(10))
    }

    @Test
    fun `空队列——超时返回 null（心跳轮询依赖它）`() {
        val queue = SseEventQueue(1)
        val started = System.nanoTime()
        assertNull(queue.poll(50))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs >= 40, "应至少等待约 50ms，实际 ${elapsedMs}ms")
    }

    @Test
    fun `容量非法——构造即拒绝`() {
        assertFailsWith<IllegalArgumentException> { SseEventQueue(0) }
    }

    // ---------- P2-18 · SSE 多写者并发（D24 §5）----------
    //
    // 设计纪律（TS-053 教训）：并发测试只断言 join/drain 之后的**确定性不变量**
    // （账目守恒 / 集合恰好一次 / 有界墙钟），不做任何紧时序断言，避免满载抖动假红。

    /** latch 闸门起跑 N 个生产者（P2-18 的 latch 闸门模式） */
    private fun runProducers(threads: Int, perThread: Int, gate: CountDownLatch, block: (Int) -> Unit): List<Thread> =
        (0 until threads).map { id ->
            Thread {
                gate.await()
                repeat(perThread) { j -> block(id * perThread + j) }
            }.apply { start() }
        }

    @Test
    fun `多写者并发——账目守恒（accepted+dropped 恒等于投放总数）`() {
        val queue = SseEventQueue(64)
        val total = 8 * 250
        val accepted = java.util.Collections.synchronizedSet(HashSet<String>())
        val gate = CountDownLatch(1)
        val producers = runProducers(8, 250, gate) { msg ->
            if (queue.offer("m$msg")) accepted.add("m$msg")
        }
        gate.countDown()
        producers.forEach { it.join(10_000) }
        assertTrue(producers.all { !it.isAlive }, "生产者全部退出（10s 上界）")

        // drain 侧取回（join 后无新投放，drain 到空是确定的）
        val drained = HashSet<String>()
        while (true) {
            val v = queue.poll(20) ?: break
            check(drained.add(v)) { "同一消息被投递两次：$v" }
        }
        // 不变量 1：账目守恒——每条消息要么在队列里被取回，要么被丢弃计数，不多不少
        assertEquals(total.toLong(), drained.size + queue.droppedCount(), "accepted(取回) + dropped 必须 == 投放总数")
        // 不变量 2：offer 侧接受集合与 drain 侧逐条一致（恰好一次，无重复无丢失）
        assertEquals(accepted, drained, "offer 返回 true 的集合必须与 drain 取回集合逐条一致")
    }

    @Test
    fun `多写者并发——满载 offer 永不阻塞（墙钟上界）`() {
        val queue = SseEventQueue(1)
        queue.offer("占位") // 满载：后续所有 offer 都应立即丢弃返回
        val gate = CountDownLatch(1)
        val producers = runProducers(8, 250, gate) { queue.offer("x") }
        gate.countDown()
        val started = System.nanoTime()
        producers.forEach { it.join(10_000) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(producers.all { !it.isAlive }, "全部生产者应已退出")
        // 2000 次注定被拒的 offer 全部返回：远低于任何阻塞实现的上界（宽松断言防满载抖动）
        assertTrue(elapsedMs < 5_000, "满载下 2000 次 offer 不应阻塞，实际 ${elapsedMs}ms")
        assertEquals(2_000, queue.droppedCount(), "全部被丢弃并逐条计数")
    }

    @Test
    fun `多写者并发——生产消费同时进行，消息恰好一次`() {
        val queue = SseEventQueue(16)
        val total = 4 * 200
        val gate = CountDownLatch(1)
        val consumed = java.util.Collections.synchronizedSet(HashSet<String>())
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val producers = runProducers(4, 200, gate) { msg ->
            queue.offer("c$msg")
        }
        val consumers = (0 until 2).map {
            Thread {
                while (!done.get() || queue.size() > 0) {
                    val v = queue.poll(5) ?: continue
                    check(consumed.add(v)) { "消费侧收到重复消息：$v" }
                }
            }.apply { start() }
        }
        gate.countDown()
        producers.forEach { it.join(10_000) }
        done.set(true)
        consumers.forEach { it.join(10_000) }
        assertTrue(consumers.all { !it.isAlive }, "消费者全部退出")
        // 确定性不变量：取回 + 丢弃 + 在途(此刻应为 0) == 投放总数；消费集合无重复由 check 钉住
        assertEquals(total.toLong(), consumed.size + queue.droppedCount() + queue.size(), "取回 + 丢弃 + 积压 == 总数")
    }
}
