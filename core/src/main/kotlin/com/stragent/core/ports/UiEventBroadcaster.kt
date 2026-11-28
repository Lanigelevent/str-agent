package com.stragent.core.ports

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * UI 事件广播器 —— D11 v3 S4 Web UI 前端。
 *
 * 让 ProgressBus 的事件推送到多个 SSE 订阅者（浏览器 /events 端点）。
 * 与 ProgressBus（ThreadLocal，同步调用链内）互补：
 *   - ProgressBus 处理单次 MCP 请求内的进度（已有机制，不改）
 *   - UiEventBroadcaster 处理跨请求的 UI 事件推送（新增）
 *
 * 设计：发布-订阅模式，SSE writer 注册为订阅者，
 * ProgressBus.emit() 时调用 broadcast() 推送到所有订阅者。
 * 无订阅者时空转（与 ProgressBus 纪律一致）。
 *
 * 会话隔离（T-045 / AD-029）：事件带「所属会话」——由传输层在 dispatch 期间经
 * [withSession] 设置（线程局部，与 [ProgressBus] 同形制）。订阅者分两类：
 *  - **会话订阅**（sessionId 非 null）：只收到同会话事件；HTTP `/events` 永远用这类，
 *    A 会话的进度正文不会出现在 B 的流里；
 *  - **全局订阅**（sessionId = null）：进程内测试 / 诊断用，收无会话事件；
 *    HTTP 端点不提供这条路（否则鉴权形同虚设）。
 * 无会话事件（非传输上下文，例如宿主进程内直接生成）只投给全局订阅者——
 * 宁可少一路，也不把不可归属的事件混进具名会话流。
 *
 * 诚实边界：
 *  - 不是 Kotlin 协程 Channel（同步架构下不需要），是简单的回调列表
 *  - 无背压处理（高频 token 流可能丢帧，与 SSE 实际使用场景匹配——UI 只需看到阶段进展；
 *    每个 SSE 连接另有有界队列与丢帧计数，见 McpBridge.handleEvents）
 *  - 订阅者在连接断开时需自行移除（SSE endpoint 的 finally 块负责）
 */
object UiEventBroadcaster {

    /** 订阅者：sessionId = null 表示进程内全局订阅（只收无会话事件） */
    private data class Subscriber(val sessionId: String?, val handler: (String) -> Unit)

    private val subscribers = ConcurrentLinkedQueue<Subscriber>()
    private val eventCounter = AtomicLong(0)

    /** 当前线程的事件会话（传输层在 dispatch 期间设置；null = 非传输上下文） */
    private val scope = ThreadLocal<String?>()

    /** 在 [block] 执行期间把事件归入 [sessionId]（结束后恢复原值，支持嵌套） */
    fun <T> withSession(sessionId: String?, block: () -> T): T {
        val previous = scope.get()
        scope.set(sessionId)
        try {
            return block()
        } finally {
            scope.set(previous)
        }
    }

    /** 当前线程的事件会话（无则 null） */
    fun currentSession(): String? = scope.get()

    /**
     * 注册一个 SSE 订阅者，返回取消订阅的函数。
     * [sessionId] 非 null = 只收该会话的事件；null = 全局订阅（只收无会话事件）。
     */
    fun subscribe(sessionId: String? = null, handler: (String) -> Unit): () -> Unit {
        val subscriber = Subscriber(sessionId, handler)
        subscribers.add(subscriber)
        // 发送连接确认
        handler("{\"type\":\"connected\",\"event\":\"ui_ready\",\"message\":\"SSE 连接已建立\"}")
        return { subscribers.remove(subscriber) }
    }

    /** 向与当前线程会话匹配的订阅者广播一条事件 */
    fun broadcast(message: String) = broadcast(message, scope.get())

    /** 向指定会话的订阅者广播（sessionId = null → 仅全局订阅者） */
    fun broadcast(message: String, sessionId: String?) {
        if (subscribers.isEmpty()) return
        val eventId = eventCounter.incrementAndGet()
        val payload =
            "{\"id\":$eventId,\"session\":${jsonString(sessionId)},\"message\":\"${escape(message)}\"}"
        for (sub in subscribers) {
            if (sub.sessionId != null && sub.sessionId != sessionId) continue
            try {
                sub.handler(payload)
            } catch (_: Exception) {
                subscribers.remove(sub)
            }
        }
    }

    /** 当前是否有订阅者 */
    fun hasSubscribers(): Boolean = subscribers.isNotEmpty()

    /** 转义 JSON 字符串 */
    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

    /** JSON 字符串字面量（null 写成 JSON null，不是 "null"） */
    private fun jsonString(value: String?): String = if (value == null) "null" else "\"${escape(value)}\""
}

/**
 * Notebook 序列化器 —— D11 v3 S4 Web UI 前端。
 *
 * 将分析步骤（工具调用序列+参数+结果）序列化为 Notebook JSON，
 * 支持一键重放——反序列化后按序执行工具调用。
 *
 * 格式兼容 Jupyter Notebook 的 cell 结构（简化版）：
 * {
 *   "nbformat": 4, "nbformat_minor": 5,
 *   "metadata": { "name": "...", "created": "..." },
 *   "cells": [
 *     { "cell_type": "tool_call", "tool": "stragent_import_table",
 *       "args": {...}, "result": {...}, "duration_ms": 50 }
 *   ]
 * }
 *
 * 纯 Kotlin、零依赖、确定性。
 */
object NotebookSerializer {

    data class NotebookCell(
        val tool: String,
        val args: JsonObject,
        val result: JsonObject?,
        val durationMs: Long,
    )

    data class Notebook(
        val name: String,
        val cells: List<NotebookCell>,
        val createdAt: Long = System.currentTimeMillis(),
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("nbformat", 4)
            put("nbformat_minor", 5)
            put("metadata", buildJsonObject {
                put("name", name)
                put("created", createdAt)
                put("cell_count", cells.size)
            })
            put("cells", kotlinx.serialization.json.JsonArray(cells.map { cell ->
                buildJsonObject {
                    put("cell_type", "tool_call")
                    put("tool", cell.tool)
                    put("args", cell.args)
                    cell.result?.let { put("result", it) }
                    put("duration_ms", cell.durationMs)
                }
            }))
        }
    }

    /** 从工具调用记录列表创建 Notebook */
    fun fromTrace(name: String, trace: List<NotebookCell>): Notebook = Notebook(name, trace)

    /** 从 ProvenanceManifest 的 steps 创建 Notebook */
    fun fromProvenanceSteps(
        name: String,
        steps: List<com.stragent.core.trace.ProvenanceStep>,
    ): Notebook = Notebook(
        name = name,
        cells = steps.map { step ->
            NotebookCell(
                tool = step.tool,
                args = step.args,
                result = null,  // manifest 只存 hash，不存原始 result
                durationMs = step.elapsedMs,
            )
        },
    )
}
