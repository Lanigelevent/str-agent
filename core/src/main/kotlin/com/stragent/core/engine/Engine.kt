package com.stragent.core.engine

import com.stragent.core.dataset.AggregateItem
import com.stragent.core.dataset.ColumnStat

/**
 * 双轨数据层的引擎抽象（D10 阶段 1）。
 *
 * 两档的分工（与 D10 §阶段1「双轨数据层」同款，不混用）：
 *  - **tier-1 · IN_MEMORY**：现有 Kotlin 实现，数据全量驻内存。**报告路径恒走此档**，
 *    每个数字都能回溯到内存中的原始行，是「数字确定性可溯源」壁垒的载体。
 *  - **tier-2 · COLUMNAR**：列式引擎（DuckDB），承接超出内存阈值的数据规模，
 *    负责探索与中间聚合。任何进入最终报告的数值，仍需经 tier-1 复核，
 *    或以「DuckDB 查询语句」作为溯源证据附在报告里。
 *
 * 纪律：
 *  - 接口只描述**确定性可复核**的能力（聚合 / 画像），不放 LLM、检索、存储——那是 ports/ 的职责；
 *  - 同一档内不同实现的返回值口径必须一致（标签、计数、share 语义），
 *    否则双轨会把「同一个数」变成两个数，直接击穿可溯源壁垒 —— 一致性由 bench 的
 *    `engine-parity` 套件逐样本对拍保证。
 */
enum class EngineTier(val label: String) {
    /** tier-1：内存 Kotlin 实现，报告路径专用 */
    IN_MEMORY("tier-1 内存"),

    /** tier-2：列式引擎（DuckDB），规模与探索路径 */
    COLUMNAR("tier-2 列式"),
}

/**
 * 分析引擎的最小公共面。
 *
 * 只暴露「可被独立复核」的分析动作：两个实现（内存 / 列式）对同一份数据必须给出同样口径的结果。
 * 参数均为列索引，与 `Dataset.columns` 对齐，避免每个实现各自发明列寻址方式。
 */
interface AnalysisEngine {
    /** 引擎标识（用于日志、溯源证据、报告脚注） */
    val id: String

    /** 所属档位 */
    val tier: EngineTier

    /** 本引擎可见的行数（tier-2 可见全量；tier-1 可见内存中的行） */
    val rowCount: Int

    /**
     * 按维度列分组、按数值列聚合。
     *
     * @param categoryIndex 维度列索引
     * @param valueIndex    数值列索引
     * @param mode          "sum" 或 "avg"（与 core.dataset.aggregateBy 同口径）
     * @param topN          明细保留条数，其余合并为「其他 N 项」
     */
    fun aggregateBy(categoryIndex: Int, valueIndex: Int, mode: String, topN: Int = 8): List<AggregateItem>

    /**
     * 单列画像（count / missing / min / max / sum / mean / median / p25 / p75 / distinct / topValues）。
     *
     * 口径与 `core.dataset.summarizeColumn` 一致：count 是「实际参与统计的样本数」，
     * missing = 空值 + 脏值；分位数为线性插值（Excel PERCENTILE.INC 同口径）。
     */
    fun profileColumn(columnIndex: Int): ColumnStat?
}
