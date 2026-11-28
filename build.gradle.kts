import org.gradle.testing.jacoco.tasks.JacocoReport

// 根构建脚本
// 版本对齐表：Kotlin 2.0.21 / JDK 17
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    // T-071：二进制兼容门禁（D21 Q-4）——core/str-agent 的公开 API dump 入库，
    // `apiCheck` 进 CI：改插件契约（AgentTool/StrAgentPlugin 等）签名而不显式更新
    // dump 会让 CI 变红。破坏性变更 = 有意识地更新 dump + CHANGELOG 注记。
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.17.0"
}

version = "1.1.0"
group = "com.stragent"

// T-071：只校验宿主集成契约层（core 与 str-agent——AgentTool/StrAgentPlugin/
// ReportEngine 等第三方宿主面）；data/host 是参考宿主实现、bench 是评测基座，不在契约内。
apiValidation {
    ignoredProjects += listOf("data", "host", "engine-duckdb", "bench")
}

// 版本号继承到子项目：:data 的 BuildInfo 从 project.version 生成，MCP initialize
// 回执的 serverInfo.version 也读它。子项目不继承会得到 "unspecified"。
subprojects {
    version = rootProject.version
    group = rootProject.group

    // T-102 CI 资源治理：test fork JVM 显式限并发/限堆。此前 bench:test 的 fork 在 CI
    // 容器里因页面文件耗尽（DOS 1455）反复启动失败（run 66 attempt1 实证：4 个 fork
    // 各自 reserve 512MB G1 空间时崩）。单模块单测内存需求小，768m 充裕；串行 fork
    // 消除「多 fork 同时 reserve」的峰值。判据不变，纯运维稳定。
    tasks.withType<Test>().configureEach {
        maxParallelForks = 1
        maxHeapSize = "768m"
    }
}

// D15 T6: JaCoCo 覆盖率（Gradle 内置插件）
// 默认不启用：jacoco agent（org.jacoco.agent）是外部依赖，--offline 环境没有缓存时
// 会让所有 test 任务直接失败。覆盖率是「有网的 CI / 发版前」动作，不是本地门禁的前提。
// 用法：./gradlew test jacocoTestReport -Pcoverage（需可解析 org.jacoco.agent）
subprojects {
    if (providers.gradleProperty("coverage").isPresent) {
        pluginManager.apply("jacoco")
        tasks.withType<JacocoReport>().configureEach {
            classDirectories.setFrom(
                fileTree(layout.buildDirectory.dir("classes/kotlin/main")) {
                    exclude("**/model/**", "**/trace/**", "**/dto/**")
                }
            )
            sourceDirectories.setFrom(files("$projectDir/src/main/kotlin"))
            executionData.setFrom(fileTree(layout.buildDirectory) { include("jacoco/test.exec") })
            reports {
                xml.required.set(true)
                html.required.set(true)
            }
        }
    }
}
