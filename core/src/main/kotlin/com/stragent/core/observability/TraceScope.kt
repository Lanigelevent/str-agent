package com.stragent.core.observability

import java.util.concurrent.Callable

/**
 * 当前线程的**span 上下文作用域**（core 层单点，T-156 P2-15）。
 *
 * 背景（D24 审查）：StructuredLogger 无自动 traceId 注入——日志行与 trace 链
 * 无法关联，Jaeger 里看到慢 span 后没法 grep 对应日志。把 holder 放在 core 层：
 * 传输层（data）写入，core 的日志/埋点读取；`McpTraceContext`（data 的既有 API）
 * 委托到这里，既有调用方零改动。
 *
 * 纪律：追踪是增强不是依赖——scope 为空时一切照旧（日志无 traceId 字段）。
 */
object TraceScope {
    private val holder = ThreadLocal<OtelSpanContext?>()

    /** 当前线程的 span 上下文；不在任何链路内为 null */
    fun current(): OtelSpanContext? = holder.get()

    fun set(context: OtelSpanContext?) {
        holder.set(context)
    }

    /** 在 scope 内执行（finally 恢复外层值，支持嵌套） */
    fun <T> with(context: OtelSpanContext?, block: Callable<T>): T {
        val previous = holder.get()
        holder.set(context)
        return try {
            block.call()
        } finally {
            holder.set(previous)
        }
    }
}
