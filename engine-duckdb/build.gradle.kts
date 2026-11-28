// engine-duckdb，tier-2 列式引擎（D10 阶段 1）。
//
// 落点说明（D10 §阶段1 原本写的是 core/data/engine）：
//   - 引擎**接口**放 core（纯 Kotlin，零依赖）—— core 是 Android 与桌面共用的底座，不能沾原生库；
//   - DuckDB **实现**独立成模块，app 不依赖它 —— duckdb_jdbc 自带桌面版原生库，
//     打进 APK 会既增大体积又加载失败（Android ABI 不匹配）。
//     D10 §8 风险表的缓解措施「按需动态下发 .so」落地前，tier-2 只在桌面/bench 侧可用，
//     Android 端保持 tier-1（报告路径本就恒走 tier-1，数字可溯源不受影响）。
plugins {
    id("org.jetbrains.kotlin.jvm")
}
kotlin {
    jvmToolchain(17)
}
dependencies {
    api(project(":core"))
    // LTS 线：1.4.5.0（D10 阶段 1 指定版本）
    api("org.duckdb:duckdb_jdbc:1.4.5.0")
    testImplementation(kotlin("test"))
}
