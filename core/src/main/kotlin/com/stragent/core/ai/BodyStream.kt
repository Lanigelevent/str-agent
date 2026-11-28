package com.stragent.core.ai

/**
 * 正文增量总线（T-172 · 增量可用产物）——[BodyDelta] 的**线程局部**投递口。
 *
 * 为什么需要它：正文增量此前只有一条出口——宿主上下文里的 `bodyListener`
 * （`StrAgentHostContext`），它把增量转成进度帧发给**当次请求的客户端**。
 * 但**异步任务**（`_meta.task`）的客户端不在一条连接上等着：它先拿句柄、再轮询
 * `tasks/get`。于是任务执行期间产出的正文**没有落点**——只能等全部章节跑完，
 * 任务终态才有完整结果。这正是「实时生成」缺的那一块。
 *
 * 本总线给正文增量补上第二个出口：
 *  - 宿主装配时 `bodyListener` 同时投递到本总线（**广播语义**，不是搬家）；
 *  - 异步任务的工作线程订阅本总线，把增量按 (章, 次) 归档进任务表；
 *  - `tasks/get` 就能给出**当前草稿**（已写出的章节，按章序拼接）。
 *
 * 口径（与 [BodyDelta] 的客户端拼装协议**完全一致**，不发明第二套）：
 *  - 片段按 (chapter, attempt, 到达序) 拼接 = 该章正文；
 *  - 同一章的 attempt 一变 → **丢掉该章旧片段**（重写时旧片段是垃圾）。
 *
 * 纪律：增量是**增强**不是依赖——没有订阅者时 `publish` 空转；正文增量是**草稿**，
 * 尚未过数字门禁（门禁在全文拼接后统一执行），对外必须标注「未过门禁」。
 */
object BodyStream {

    private val sink = ThreadLocal<((BodyDelta) -> Unit)?>()

    /** 当前线程生效的正文增量处理器（无订阅者为 null） */
    fun current(): ((BodyDelta) -> Unit)? = sink.get()

    /** 在 [block] 执行期间把正文增量交给 [handler]（结束后恢复原值，支持嵌套） */
    fun <T> with(handler: ((BodyDelta) -> Unit)?, block: () -> T): T {
        val previous = sink.get()
        sink.set(handler)
        try {
            return block()
        } finally {
            sink.set(previous)
        }
    }

    /** 投递一段正文增量（无订阅者时为空操作） */
    fun publish(delta: BodyDelta) {
        sink.get()?.invoke(delta)
    }

    /** 当前是否有订阅者 */
    fun isActive(): Boolean = sink.get() != null
}
