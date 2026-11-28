package com.stragent.data

import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 流式读的每读活跃看门狗（T-133）：java.net.http.HttpClient 的超时止于响应头，流内读取
 * 无超时——每次 read() 刷新最后活跃时间，共享 daemon 巡检线程关闭超时流（阻塞读随即抛出），
 * 保持 T-062 的「每读 180s」语义（旧 HttpURLConnection readTimeout 的等价物）。
 *
 * **两个判据，正交**：
 * ① **不活跃窗口**（[inactivityMs]）：距上次 read 超过它就关流——这是「连接卡死」的判据，
 *    覆盖整个流（含正文）；
 * ② **尾帧上限**（[capTotalWait]）：自设置时刻起超过它就关流——这是「**正文已宣告完成**、
 *    但网关不再说话」的判据（见 [HttpLlmClient] 的 finish_reason 尾帧）。
 * ② 独立于 ①：它只约束**正文完成之后**那一段等待。正文在 finish_reason 到达时已经读完，
 * 故 ② 不可能截断正文；而把两者混为一谈（收窄 ① 当 ② 用）会让「usage 帧迟到」被一起砍掉
 * → token 账记 0 = 假账。
 *
 * 注册在 init 自动完成；单实例巡检线程服务全部客户端。
 */
internal class WatchedInputStream(
    private val delegate: InputStream,
    /** 不活跃窗口（毫秒）：最后一次 read 起算，超过即被巡检强制关流 */
    inactivityMs: Long = DEFAULT_INACTIVITY_MS,
    /** 巡检器（测试可注入更快的巡检节奏；生产用共享单例） */
    private val watchdog: StreamWatchdog = STREAM_WATCHDOG,
) : FilterInputStream(delegate) {

    /** 不活跃窗口（毫秒）：可变——最后一次 read 刷新 */
    @Volatile
    private var inactivityWindowMs: Long = inactivityMs

    /** 不活跃判定时刻（epoch 毫秒）：读线程写、巡检线程读，故用 volatile */
    @Volatile
    private var deadline: Long = System.currentTimeMillis() + inactivityMs

    /** 尾帧上限（epoch 毫秒，0 = 无上限）：绝对时刻，不随 read 刷新 */
    @Volatile
    private var hardDeadline: Long = 0L

    /**
     * 设置尾帧上限（毫秒）：自此刻起，超过这个时长仍未读完就强制关流。
     *
     * 用途：供应商宣告正文完成（finish_reason）之后，剩下的只是可选的尾帧（usage / [DONE]）。
     * 有些网关不发 [DONE] 且把连接挂着——此时若无上限，读端要静默到整段超时（180 秒）才收尾。
     *
     * 两个动作缺一不可：①设**绝对上限**（hardDeadline）——它约束「正文完成之后」总共等多久
     * （正文此刻已读完，砍不到它）；②把不活跃窗口**放宽**到不小于该上限——否则「不活跃窗口」
     * 会在 usage 帧迟到时先把流砍掉：usage 按协议就排在 finish_reason 之后，迟到是常态，
     * 砍掉它 → token 账记 0 = 假账。
     */
    fun capTotalWait(ms: Long) {
        hardDeadline = System.currentTimeMillis() + ms
        if (inactivityWindowMs < ms) inactivityWindowMs = ms
    }

    init {
        watchdog.watch(this)
    }

    override fun read(): Int { touch(); return super.read() }

    override fun read(b: ByteArray): Int { touch(); return super.read(b) }

    override fun read(b: ByteArray, off: Int, len: Int): Int { touch(); return super.read(b, off, len) }

    private fun touch() { deadline = System.currentTimeMillis() + inactivityWindowMs }

    /** 超过任一判据即视为该关流了 */
    fun expired(): Boolean {
        val now = System.currentTimeMillis()
        if (now > deadline) return true
        return hardDeadline != 0L && now > hardDeadline
    }

    fun forceClose() { runCatching { delegate.close() } }

    /**
     * 正常收尾：从巡检表摘除。
     *
     * 不摘的话，已读完的流要占着名额直到过期才被清掉——长进程里这是一笔 steadily 增长的
     * 滞留（每次流式调用留一条）。摘除只影响登记，不影响已发生的读。
     */
    override fun close() {
        watchdog.unwatch(this)
        super.close()
    }

    internal companion object {
        /** 每读不活跃上限的默认值（毫秒）：T-062 的读超时口径 */
        const val DEFAULT_INACTIVITY_MS: Long = 180_000L

        internal val STREAM_WATCHDOG = StreamWatchdog()
    }
}

/**
 * 共享巡检线程：2s 巡检关闭超时流（单 daemon 线程服务全部客户端实例）。
 *
 * 巡检体的两条纪律（都来自 P-172 的实证教训）：
 * - 用 [CopyOnWriteArrayList.removeIf] 移除：它在 COW 上走 bulkRemove（一次扫描、一次数组
 *   拷贝）。Kotlin 的 `MutableIterable.removeAll { }` 对 RandomAccess 列表走的是
 *   「逐元素 set + remove(index)」的**搬移**分支——在 COW 上每次都是整份数组拷贝
 *   （n 条流 = n 次全量拷贝）。注：COW 实现了 RandomAccess，所以那条分支**不抛**
 *   UnsupportedOperationException（实测如此）——真正的风险不在这里。
 * - 整体包在 runCatching 里：**周期任务一旦抛出异常，ScheduledExecutorService 会静默取消
 *   后续执行**（实测：抛一次后 runs 停在 1）——巡检线程还在，看门狗却再也不会关流，
 *   比直接崩更隐蔽（症状是「流卡住不报错」）。所以巡检体必须异常-proof。
 */
internal class StreamWatchdog(
    /** 巡检间隔（毫秒） */
    private val patrolIntervalMs: Long = 2_000L,
) {
    private val watched = CopyOnWriteArrayList<WatchedInputStream>()
    private val scheduler = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "str-agent-stream-watchdog").apply { isDaemon = true }
    }

    init {
        scheduler.scheduleWithFixedDelay({
            runCatching {
                watched.removeIf { w ->
                    if (w.expired()) {
                        w.forceClose()
                        true
                    } else {
                        false
                    }
                }
            }
        }, patrolIntervalMs, patrolIntervalMs, TimeUnit.MILLISECONDS)
    }

    fun watch(w: WatchedInputStream) { watched += w }

    /** 摘除已正常收尾的流（[WatchedInputStream.close] 调用） */
    fun unwatch(w: WatchedInputStream) { watched -= w }

    /** 当前在册的流条数（测试用：断言正常收尾后不滞留） */
    fun trackedCount(): Int = watched.size
}
