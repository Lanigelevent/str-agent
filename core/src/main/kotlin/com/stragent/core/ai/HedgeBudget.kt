package com.stragent.core.ai

/**
 * T-137 · LLM 调用对冲（hedging）的**预算闸门**。
 *
 * **为什么需要它**：对冲（慢请求加一个并发副本，先到先用）用**额外成本**换**尾延迟**——
 * 按 Tail at Scale 的实测，代价是 5-10% 的额外调用量换 P99 -75~96%。但那是有**上界**的换法；
 * 没有上界的对冲就是**成本失控**：慢请求越多、副本越多、成本越高——正反馈。
 *
 * 所以本闸门只管一件事：**把「多花的调用量」钉在一个可核的比例上**。
 *
 * ## 口径：令牌桶（比例发放 + 突发封顶）
 *
 * - **发放**：每发起一次**可对冲的**调用，额度 +[budgetRatio]（连续量，不取整）；
 * - **消费**：每发出一个对冲副本，额度 −1；
 * - **封顶**：额度余额不超过 [maxCredits]（默认 1）——**突发上限**：
 *   两次对冲之间至少要间隔 `ceil(1 / 比例)` 次调用，**不会连发对冲把负载瞬间翻倍**。
 *
 * ### 可核的上界
 *
 * 每次调用发放的额度 **≤ 比例**（封顶只会让额度变少，不会变多），故：
 *
 * ```
 * 已发副本数 ≤ floor(比例 × 已发起调用数)      —— [quota]，全程单调成立
 * ```
 *
 * 这正是 D23 §6 B3 的验收判据「对冲请求数 ≤ 预算比例」的精确形式，
 * 由 [tryConsumeHedge]（判定与扣减原子完成）保证。
 *
 * ### 为什么是「连续量 + 封顶」而不是「先攒够 N 次再发 1 个」
 *
 * 后者（小计数向上取整）会在计数很小时就多发一个（比例 0.2 时发 1 个 = 500% 放大），
 * 且低频场景下永远攒不够 → 对冲形同虚设。连续量 + 向下取整的口径给出**恒成立**的上界，
 * 同时起步阶段即可用（5 次调用攒够 1 个副本）。
 *
 * ### 只计「可对冲的」调用
 *
 * 发放只发生在**会被对冲的通道**上（整包调用）。流式通道不发放——
 * 否则流式调用会替整包通道「白攒」额度（它自己永远不消费），对冲会变得比配置的更激进。
 */
class HedgeBudget(
    /**
     * 预算比例：每 1 次调用，累积多少「对冲额度」。
     *
     * 0.2 = 每 5 次调用攒够 1 个副本（20% 上界，与 Tail at Scale 的 5-10% 量级同族）。
     * 0 = 关闭对冲（默认，见 [DEFAULT_RATIO]）。
     *
     * **上限 1.0**：本实现每个调用至多发 1 个副本，超过 1 的比例等价于 1（多出的额度用不上）。
     */
    budgetRatio: Double,
    /**
     * 额度余额上限（单位＝副本个数）：**突发上限**。
     *
     * 默认 1 —— 两次对冲之间至少间隔 `ceil(1 / 比例)` 次调用。
     * 调大它允许「攒够一批再连发」，代价是瞬时负载放大（成本上界不变，但上界达成得更集中）。
     */
    private val maxCredits: Double = DEFAULT_MAX_CREDITS,
) {
    /** 生效比例（钳到 [0, 1]——见构造参数说明） */
    private val ratio: Double = budgetRatio.coerceIn(0.0, 1.0)

    private val lock = Any()

    /** 额度余额（连续量，单位＝可发的副本个数） */
    private var credits: Double = 0.0

    /** 已发起的可对冲调用数（配额的分母） */
    private var normalCalls: Long = 0

    /** 已发出的对冲副本数（已花掉的量） */
    private var hedges: Long = 0

    /**
     * 对冲是否启用（比例 > 0）。
     *
     * 比例 = 0 时**恒不发副本**——这是默认态（成本换尾延迟不替用户默认做主）。
     */
    val enabled: Boolean get() = ratio > 0.0

    /**
     * 记一次**可对冲的**调用发起 → 额度 +[budgetRatio]（封顶）。
     *
     * 在**发起时**记（不是完成时）：否则首次调用时额度为 0 → 永不对冲，
     * 而慢调用恰恰常发生在**开头**（冷启动、连接池未热）——等于在最该对冲的时候不对冲。
     * 调用「发起」即已产生成本（请求已发出），计入分母是如实的。
     */
    fun onCallStarted() {
        synchronized(lock) {
            normalCalls++
            credits = minOf(maxCredits, credits + ratio)
        }
    }

    /**
     * 能否再发一个对冲副本 —— **判定与扣减原子完成**（同一把锁）。
     *
     * 判据：**额度余额 ≥ 1**；够则扣 1 并返回 true（发副本），不够返回 false（不追加成本）。
     *
     * **为什么合成一个方法**：分成「判定」与「扣减」两步时，两个并发调用可能都判定为
     * 「够」→ 各自发一个副本 → 超发（不变量 `已发副本数 ≤ 配额` 被打破，成本失控）。
     * 这是唯一允许发副本的入口（[HedgedCall] 只走这里）。
     */
    fun tryConsumeHedge(): Boolean = synchronized(lock) {
        if (credits < 1.0) return false
        credits -= 1.0
        hedges++
        true
    }

    /** 已发起的可对冲调用数（观测用） */
    val callCount: Long get() = synchronized(lock) { normalCalls }

    /** 已发出的副本数（观测用） */
    val hedgeCount: Long get() = synchronized(lock) { hedges }

    /** 额度余额（观测用） */
    val creditBalance: Double get() = synchronized(lock) { credits }

    /**
     * **成本上界** = floor(比例 × 已发起调用数)。
     *
     * 全程单调成立的不变量：`已发副本数 ≤ quota`。
     * 每次调用发放的额度 ≤ 比例（封顶只会更少），故累计发放量 ≤ 比例 × 调用数，
     * 而已发副本数 ≤ 累计发放量。
     */
    val quota: Long get() = synchronized(lock) { Math.floor(normalCalls.toDouble() * ratio).toLong() }

    companion object {
        /**
         * 默认比例 = **0（关闭）**。
         *
         * 对冲是**成本换尾延迟**，默认开启等于替用户默认多付钱——不。
         * 需要时显式打开（env `STRAGENT_LLM_HEDGE_RATIO`），且预算上界由该比例唯一决定。
         */
        const val DEFAULT_RATIO: Double = 0.0

        /** 默认额度余额上限（突发上限）：两次对冲之间至少间隔 `ceil(1 / 比例)` 次调用 */
        const val DEFAULT_MAX_CREDITS: Double = 1.0
    }
}
