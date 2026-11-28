package com.stragent.core.config

/**
 * 环境变量单点入口（T-157 / D24 P2-16）：全仓 STRAGENT_* 变量的唯一读取渠道。
 *
 * 设计边界（诚实）：
 *  - **命名不变、读取点归一**——变量名与解析口径留在各读取点（本对象只归一渠道，
 *    不新增第二事实源）；`--list-tools`、README env 表、D6 env 矩阵的对外口径不变。
 *  - **source 是仓内唯一的 env 注入渠道**——JVM 进程内无法改写进程环境，测试要
 *    覆盖 env 行为经 `Config.source = { name -> ... }` 注入，不再各自 mock System.getenv。
 *    已声明的**第二覆盖路径**（D25 审查登记）：host/ObjectStorage 的 `str-agent.s3.*`
 *    System.getProperty 通道（先查属性再回退本对象）——覆盖率统计里 property 通道是盲区；
 *    bench 的 `bench.*` 属性 = bench 专属调参命名空间，**不走** Config（T-157 边界条款）。
 *  - **core 顶层 val 固化改读取时解析**——原顶层 `val X = System.getenv(...)`（进程
 *    启动时冻结）改为 Config 驱动的读取时解析（见 Followup/TextSummarizer 的 getter 化），
 *    env 改动在同一进程内即时生效（测试/长驻 daemon 场景）。
 */
object Config {
    /** 测试注入渠道：非 null 时优先于 System.getenv；仓内唯一 env 覆盖路径 */
    @Volatile
    var source: ((String) -> String?)? = null

    /** 唯一读取渠道：所有生产代码的 STRAGENT_* 读取必须走这里 */
    fun get(name: String): String? {
        val injected = source?.invoke(name)
        if (injected != null) return injected
        return System.getenv(name)
    }
}
