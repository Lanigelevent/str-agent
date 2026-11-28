package com.stragent.data

import java.util.concurrent.Callable

import com.stragent.core.observability.OtelSpanContext
import com.stragent.core.observability.TraceScope

/**
 * 当前 MCP 工具调用的**链路上下文**（ThreadLocal，与 [McpSessionContext] 同形制）。
 *
 * 为什么需要它：传输层（[McpBridge]）知道上游的 `_meta.traceparent`，工具实现
 * （例如报告生成）并不知道自己正被谁调用。把「本调用的 span」放进线程上下文后，
 * 工具内部产生的 span（报告根 + 阶段）可以直接挂到这条链上，形成
 *
 * ```
 * 上游客户端 trace → tools/call → report.generate → report.stage:…
 * ```
 *
 * 纪律：追踪是**增强**，不是依赖 —— 未配置导出器时上下文照样传递（id 依旧一致），
 * 只是没有 span 被上报；业务代码不得依赖它是否存在。
 */
object McpTraceContext {
    /** T-156 P2-15：holder 下移 core 层（[TraceScope]）——日志/埋点同源读取 */
    fun current(): OtelSpanContext? = TraceScope.current()

    fun <T> with(context: OtelSpanContext?, block: () -> T): T =
        TraceScope.with(context, Callable(block))
}
