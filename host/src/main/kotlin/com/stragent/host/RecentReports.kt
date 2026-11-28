package com.stragent.host

import com.stragent.data.StoredChart
import java.util.ArrayDeque

/** 一条最近报告（/ui 驾驶舱「报告」区与总览统计的数据源） */
data class RecentReport(
    val atMs: Long,
    val datasetId: String,
    val datasetName: String,
    val engineId: String,
    val totalTokens: Int,
    val gateFailed: Boolean,
    val traceId: String?,
    /** 正文全文（渲染区用；清单区只取摘录） */
    val reportText: String,
    val durationMs: Long,
    /**
     * T-086：本报告的图表数据（/ui 报告视图按需渲染成离线 SVG）。
     * 存数据本体而非渲染产物——渲染路径演进时历史报告仍能重渲。
     */
    val charts: List<StoredChart> = emptyList(),
)

/**
 * 最近报告的环形缓冲（T-070）：最多 [capacity] 条（默认 10），新的挤掉最旧的。
 *
 * 线程安全：报告生成在桥的多线程池里并发，读写都走同一把锁。
 * 只增不删（进程生命周期内的滚动窗口）——持久化留给 kv 层，不在此处做。
 */
class RecentReports(private val capacity: Int = 10) {
    private val deque = ArrayDeque<RecentReport>()

    fun record(entry: RecentReport) {
        synchronized(this) {
            deque.addLast(entry)
            while (deque.size > capacity) {
                deque.removeFirst()
            }
        }
    }

    /** 快照（新的在前） */
    fun all(): List<RecentReport> = synchronized(this) { deque.toList().asReversed() }

    fun size(): Int = synchronized(this) { deque.size }

    /**
     * 近期报告时长的 P50（ms）——预计耗时（T-082 / D21 W-4）的估计源。
     *
     * 最近邻秩（ceil 取上档）：与 /ui 驾驶舱分位同口径（宁取保守值）；
     * 环空 = null（调用方如实不发估计，不编造）。
     */
    fun durationP50(): Long? = synchronized(this) {
        if (deque.isEmpty()) {
            null
        } else {
            val durations = deque.map { it.durationMs }.sorted()
            val index = Math.ceil(0.5 * durations.size).toInt().coerceIn(1, durations.size) - 1
            durations[index]
        }
    }
}
