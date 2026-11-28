package com.stragent.data

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * `/events` 每连接的**有界事件队列**（T-048 / D17 G-06）。
 *
 * 为什么独立成类：丢帧策略是本模块里唯一「允许丢数据」的地方，必须能被单测
 * 直接钉住——容量、丢弃计数、先进先出、空队列超时。以前它内联在 `handleEvents`
 * 里，只能靠真 socket 慢读来触发，测不稳也说不清。
 *
 * 纪律：`offer` 永不阻塞（慢消费者只丢自己的帧，不拖生成主链）；
 * 丢弃不是静默的——计数会被连接结束时汇总到 [McpBridge.droppedSseFrames] 与日志。
 */
internal class SseEventQueue(private val capacity: Int) {

    init {
        require(capacity > 0) { "队列容量必须为正数：$capacity" }
    }

    private val queue = ArrayBlockingQueue<String>(capacity)
    private val dropped = AtomicLong(0)

    /** 非阻塞入队；返回 false 表示已满并丢弃（计数 +1） */
    fun offer(payload: String): Boolean {
        val accepted = queue.offer(payload)
        if (!accepted) dropped.incrementAndGet()
        return accepted
    }

    /** 取一条事件；[timeoutMs] 内无事件返回 null（供心跳与 stop() 轮询） */
    fun poll(timeoutMs: Long): String? = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)

    /** 当前积压条数（诊断用） */
    fun size(): Int = queue.size

    /** 累计丢弃条数（诊断/日志用） */
    fun droppedCount(): Long = dropped.get()
}
