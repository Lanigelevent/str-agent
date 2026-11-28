// core：纯 Kotlin JVM 模块——Str Agent分析逻辑的移植目标。
// 模块纪律：**禁止 import android.* 与任何 Android 依赖**（构建器层面强制，
// 比 TS 时代「约定俗成不碰浏览器 API」强一档）。分层：
//   csv/      表格解析（csv.ts）
//   dataset/  数据集与统计（dataset.ts）
//   brief/    摘要构建（dataset-brief.ts）
//   report/   报告校验与章节（report-verify.ts、report-chapters.ts）
//   ai/       编排纯逻辑（ai-insight.ts 的可测部分）
// 对外依赖通过 ports/ 接口注入（存储、LLM、检索），测试用 fake。
plugins {
    id("org.jetbrains.kotlin.jvm")
}
kotlin {
    jvmToolchain(17)
}
dependencies {
    // kotlinx-serialization：纯 JVM 库——语义审查结果的 JSON 解析（semanticReview）
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // coroutines：编辑部记者条线并行采访（EditorialDept）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
