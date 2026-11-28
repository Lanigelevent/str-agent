package com.stragent.data

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MCP 请求的**协作式取消**注册表（MCP `notifications/cancelled`）。
 *
 * 为什么是协作式而不是「掐线程」：
 *   引擎是同步调用（LLM HTTP 调用期间无法安全中断），唯一可靠的取消点是**步与步之间**。
 *   因此取消只置一个标志，长任务（报告生成）在每个可停点自查；当前正在进行的模型调用
 *   会跑完，随后在下一个边界退出。这与 MCP 规范对 cancellation 的定位一致，也避免把
 *   线程强杀带进 JVM（`Thread.stop` 早已废弃，且会破坏调用方状态）。
 *
 * 作用域：一个 `tools/call` 请求 = 一个令牌，键 = 会话 id + JSON-RPC id。
 *   - HTTP：取消通知由另一条连接送达，所以令牌不能放 ThreadLocal 当唯一事实源；
 *     注册表是跨线程的，`withRequest` 负责登记/清理。
 *   - stdio：单线程顺序处理，取消通知只能在当前调用结束后被读到（当前的取消会静默无效）；
 *     这是 stdio 传输的已知边界，不假装它可中断。
 *
 * 线程语义：`signal()` 返回**捕获了令牌对象**的 lambda，可以在任意线程调用
 * （编辑部会用协程/`runBlocking`，ThreadLocal 在那边读不到）。
 */
object McpRequestCancellation {

    private class Token {
        val cancelled = AtomicBoolean(false)
    }

    /** 键 = 会话 + JSON-RPC id；id 用 JSON 字面量表示（数字 1 与字符串 "1" 是两条请求） */
    private fun key(session: String, requestId: String): String = "$session\u0000$requestId"

    private val active = ConcurrentHashMap<String, Token>()
    private val current = ThreadLocal<Token?>()

    /**
     * 登记一次请求并在当前线程暴露令牌；块结束（成功/异常）一定清理。
     *
     * 同一键并发调用时后者覆盖前者（客户端不应复用 id；这里不做额外裁决，
     * 只保证清理只删自己的令牌，不会误删后一次登记的）。
     */
    fun <T> withRequest(session: String, requestId: String, block: () -> T): T {
        val k = key(session, requestId)
        val token = Token()
        active[k] = token
        current.set(token)
        try {
            return block()
        } finally {
            current.remove()
            active.remove(k, token)
        }
    }

    /** 客户端发来 `notifications/cancelled`：返回是否命中在途请求 */
    fun cancel(session: String, requestId: String): Boolean =
        active[key(session, requestId)]?.let { it.cancelled.set(true); true } ?: false

    /** 捕获当前请求的取消信号；不在请求内时返回恒 false（裸调用零行为差异） */
    fun signal(): () -> Boolean {
        val token = current.get() ?: return { false }
        return { token.cancelled.get() }
    }

    /** 当前线程是否已被取消（短循环用；长任务请用 [signal] 捕获后跨线程传递） */
    fun isCancelled(): Boolean = current.get()?.cancelled?.get() == true
}

/**
 * 工具已确认被取消（`-32800`，MCP/JSON-RPC 的 Request cancelled）。
 *
 * 只有**确信是取消**时才抛它：否则会把普通失败伪装成取消，客户端不会重试。
 */
class McpRequestCancelledException(message: String = "请求已被客户端取消") : RuntimeException(message)
