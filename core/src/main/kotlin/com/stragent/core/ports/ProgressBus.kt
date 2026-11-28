package com.stragent.core.ports

import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 进度总线——把「长任务进行到哪一步」透传给宿主与外部客户端。
 *
 * 为什么需要它：
 *   纯 agent 插件形态下，**外部 agent 是唯一的使用者**。生成一份深度报告要几十秒，
 *   如果客户端只能干等，它既不知道进度、也不能中途判断「要不要继续」——
 *   交互体验比 App 形态差一大截。MCP 协议对此有标准解法：客户端在请求里带
 *   `params._meta.progressToken`，服务端在过程中回 `notifications/progress`。
 *
 * 为什么是线程局部 + 协程上下文双通道（T-048）：
 *   进度是从**引擎深处**（阶段跃迁：analyzing / chapter / tool …）冒出来的，
 *   中间隔着 core / str-agent / data / host 四层，逐层加参数会污染所有签名；
 *   生成链路本身是**同步**的，线程局部天然与「一次调用」对齐。
 *   但编辑部会把 6 条记者条线丢到 `Dispatchers.IO` 并行跑——那些线程上没有
 *   调用线程的 ThreadLocal，进度会在并行段静默丢失。解法是给并行的协程挂一个
 *   [Element]（`ThreadContextElement`）：协程每切换到一个工作线程，就把处理器
 *   写进该线程的 ThreadLocal，退出时恢复。异步边界走协程上下文，同步链路照旧，
 *   业务签名零改动。
 *
 * 纪律：进度是**增强**，不是依赖 —— 没有订阅者时 `emit` 直接空转，绝不影响主流程。
 */
object ProgressBus {
    private val sink = ThreadLocal<((String) -> Unit)?>()

    /** 当前线程生效的进度处理器（无订阅者为 null） */
    fun current(): ((String) -> Unit)? = sink.get()

    /** 在 [block] 执行期间把进度回调交给 [handler]（结束后恢复原值，支持嵌套） */
    fun <T> with(handler: ((String) -> Unit)?, block: () -> T): T {
        val previous = sink.get()
        sink.set(handler)
        try {
            return block()
        } finally {
            sink.set(previous)
        }
    }

    /**
     * 协程上下文元素（T-048）：把 [handler] 带到协程实际执行的每个线程上。
     *
     * 用法：`async(Dispatchers.IO + ProgressBus.asContextElement()) { … }`。
     * 元素只同步「本协程」的线程上下文；不同协程各挂各的元素，互不串台。
     */
    class Element(private val handler: ((String) -> Unit)?) :
        ThreadContextElement<((String) -> Unit)?>, CoroutineContext.Element {

        companion object Key : CoroutineContext.Key<Element>

        override val key: CoroutineContext.Key<Element> get() = Key

        override fun updateThreadContext(context: CoroutineContext): ((String) -> Unit)? {
            val previous = sink.get()
            sink.set(handler)
            return previous
        }

        override fun restoreThreadContext(context: CoroutineContext, oldState: ((String) -> Unit)?) {
            sink.set(oldState)
        }
    }

    /** 用当前线程的处理器构造协程上下文元素（调用点即订阅点） */
    fun asContextElement(): Element = Element(sink.get())

    /** 报告一条进度（无订阅者时为空操作） */
    fun emit(message: String) {
        sink.get()?.invoke(message)
        // D11 v3 S4：同时广播给 UI 事件订阅者（浏览器 SSE）
        com.stragent.core.ports.UiEventBroadcaster.broadcast(message)
    }

    /** 当前是否有订阅者 */
    fun isActive(): Boolean = sink.get() != null
}
