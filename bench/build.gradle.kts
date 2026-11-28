// bench：str-agent 评测基座（D10 阶段 0）。
// 纯 JVM 模块，复用 :core（分析/溯源/摘要）与 :str-agent（策展工具集）的既有能力，
// 对 fixtures/baseline 金标做量化评分。无 LLM 依赖，可离线运行。
plugins {
    id("org.jetbrains.kotlin.jvm")
}
kotlin {
    jvmToolchain(17)
}
dependencies {
    api(project(":core"))
    api(project(":str-agent"))
    // tier-2 列式引擎：engine-parity（双轨口径对拍）与 large-data（规模门禁）两个套件要用
    api(project(":engine-duckdb"))
    // T-075 并发压测基线：McpBridge（真实 HTTP 传输 + 4+64 线程池语义）在 data 模块
    api(project(":data"))
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // T-057 外部基准：Spider 2.0-Lite 的 SQLite 题要用 JDBC 执行 SQL 并读回结果。
    // 3.46.1.3 内置 SQLite 3.46.1，含 math 函数（radians/sin/asin...），
    // Airlines 的 haversine 题依赖它；版本与 AI 侧 3.46.1.3 对齐。
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    // sqlite-jdbc 用 slf4j 1.7 API 打日志；无绑定时 JDBC 会在 stderr 提示。
    // 门禁日志要干净，装 NOP 绑定（不引入日志实现）。
    runtimeOnly("org.slf4j:slf4j-nop:1.7.36")
    testImplementation(kotlin("test"))
}

// 外部 SQL 基准单独入口（T-057）：数据缺省时显式 SKIP 并退出 0。
// 数据集目录由 STRAGENT_BENCH_EXTERNAL_DIR 指定；CI 准备好数据后可直接跑本任务。
tasks.register("externalBench", JavaExec::class) {
    group = "verification"
    description = "运行外部 SQL 基准（Spider 2.0-Lite SQLite 子集；无数据时 SKIP）"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.stragent.bench.external.ExternalBenchMainKt")
    workingDir = rootProject.projectDir
    if (project.hasProperty("bench.updateBaseline")) args("--update-baseline")
}

// 评测任务：运行主入口，产物写入 build/reports/
// 用法：gradle --offline :bench:benchmark
//       gradle --offline :bench:benchmark -Pbench.updateBaseline   （通过后覆盖性能基线）
tasks.register("benchmark", JavaExec::class) {
    group = "verification"
    description = "运行 str-agent 评测基座，输出量化评分卡（8 套件）"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.stragent.bench.BenchMainKt")
    val out = layout.buildDirectory.dir("reports").get().asFile
    args = buildList {
        add(out.absolutePath)
        if (project.hasProperty("bench.updateBaseline")) add("--update-baseline")
    }
    // T-085：并发基线 c64 需要 64 条并发 keep-alive 连接；JDK 客户端缓存默认
    // http.maxConnections=5（每目标地址只留 5 条空闲连接）——预热建立的 64 条连接
    // 在计量开始前被客户端自己关掉，c64 开闸时 64 个并发 connect 打向小 backlog 的
    // listening socket，Windows 回环溢出表现为 ConnectException（run 1884 实证诊断）。
    // 提高客户端缓存上限 = 测量工具与被测并发匹配，属测量卫生而非放宽判据。
    jvmArgs("-Dhttp.maxConnections=128")
    // 基线路径 bench/baseline/... 是相对仓库根解析的（默认工作目录是 bench/，会指错）
    workingDir = rootProject.projectDir
    doFirst { out.mkdirs() }
}
