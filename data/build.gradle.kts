// data：适配层。core/ 的 ports 在这里落到真实实现：
//   文件系统数据集仓库（对应 idb.ts 的本体存储）、文件偏好、JDK 自带 HTTP 的 LLM 客户端。
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}
kotlin {
    jvmToolchain(17)
}
dependencies {
    implementation(project(":core"))
    // T-148：data→str-agent 主依赖归零——工具契约（AgentTool/schema 函数/ProgressBus 等）
    // 真身在 core/ports，data 只依赖 core；agent 循环专属物不再被传输层反向依赖。
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

// 版本号单一来源：从 project.version（gradle.properties / 根 build）生成 BuildInfo，
// 避免源码里再硬编码一份导致与 CHANGELOG / MCP initialize 回执漂移。
val generateBuildInfo by tasks.registering {
    val versionValue = project.version.toString()
    val outDir = layout.buildDirectory.dir("generated/buildinfo/kotlin")
    inputs.property("version", versionValue)
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().file("com/stragent/data/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package com.stragent.data
            |
            |// 由 Gradle 生成，勿手改（来源：project.version）
            |object BuildInfo {
            |    const val version: String = "$versionValue"
            |}
            |
            """.trimMargin(),
        )
    }
}
kotlin.sourceSets["main"].kotlin.srcDir(generateBuildInfo)
tasks.named("compileKotlin") { dependsOn(generateBuildInfo) }

// T-153：transcript 快照的 write 模式转发——`./gradlew :data:test -Dstragent.transcript.write=true`
// 重生成金标夹具（刻意变更需 review）。Gradle 的 -D 不会自动进测试 fork，需显式转发。
tasks.withType<Test>().configureEach {
    systemProperty("stragent.transcript.write", System.getProperty("stragent.transcript.write") ?: "false")
}
