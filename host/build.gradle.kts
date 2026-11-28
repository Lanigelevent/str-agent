import org.gradle.api.tasks.testing.Test

// host，纯 JVM 宿主（替代原 Android 侧的 AppContainer 装配层）。
//
// 定位（产品形态 = 纯 agent 插件）：
//   - 本模块**不是**产品本体，而是「参考宿主」：把 core 端口的真实实现装配成
//     StrAgentHostContext，装载 StrAgentPlugin，并以无头进程对外提供 MCP 能力面；
//   - 任何第三方宿主也可以跳过本模块，直接用 core 的 ports/ 接口自建装配；
//   - 纯 JVM，不引任何 Android / 桌面 GUI 依赖，也不产出任何安装包。
//
// 两条对外接线（能力只打包一次，宿主各自接线 —— dev-agent 同款形态）：
//   - stdio：`--transport stdio`，宿主一条命令拉起，进程随宿主生死（插件市场接线方式）
//   - http ：`--transport http`，独立长驻服务 127.0.0.1:7787 + Bearer 鉴权
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}
kotlin {
    jvmToolchain(17)
}
application {
    mainClass.set("com.stragent.host.HostMainKt")
    applicationName = "str-agent-mcp"
}
dependencies {
    implementation(project(":core"))
    implementation(project(":str-agent"))
    implementation(project(":data"))
    // tier-2 列式引擎（大文件聚合工具）：只由宿主侧引用，插件本体不依赖它
    implementation(project(":engine-duckdb"))
    // PDF 文本层抽取（Apache-2.0）：零依赖纪律的有记录例外（D4 P-139）——
    // 自研对象级解析器在 CID 字体 + ToUnicode CMap 的 Word 导出 PDF 上无法正确解码
    //（真实材料 268 页实测），PDFTextStripper 是该问题的行业标准解法。
    implementation("org.apache.pdfbox:pdfbox:3.0.5")
    // T-138 补强轮（用户拍板「全部格式引 batik」）：SVG → PNG 栅格化，
    // 让 docx/pdf/pptx 三种交付格式都能**内嵌图表**（此前只能注明「图表见 HTML 仪表盘」）。
    // 这是零三方依赖纪律（AD-004）的**第二次**破例，需登记进 D3/CHANGELOG。
    implementation("org.apache.xmlgraphics:batik-transcoder:1.17")
    // batik-codec：PNG 写出适配器（transcoder 的可选依赖，不显式加就没有 PNG 编码器）
    implementation("org.apache.xmlgraphics:batik-codec:1.17")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

// 运行入口：gradlew :host:run（application 插件自带 run 任务）
tasks.named<JavaExec>("run") {
    group = "application"
    description = "以无头进程启动 Str Agent MCP 服务器（默认 http，可用 --args=\"--transport stdio\" 切 stdio）"
    standardInput = System.`in`
}

// 分发目录：gradlew :host:installDist → host/build/install/str-agent-mcp/{bin,lib}
// 插件清单（.zcode-plugin/plugin.json）指向该目录的 lib/* 作为 classpath。

// T-122 单文件分发：java -jar str-agent-mcp-all.jar（宿主+全部运行时依赖，零外部安装件）
tasks.register<Jar>("fatJar") {
    group = "application"
    description = "单文件可运行分发（依赖打入；签名文件与 module-info 排除）"
    archiveClassifier.set("all")
    manifest { attributes["Main-Class"] = "com.stragent.host.HostMainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
    }
}

// 真实模型抽样是 opt-in：默认 test 不跑（CI 无 Key/无外网），避免把 skipped 混进默认门禁。
// 运行：STRAGENT_REAL_LLM_KEY=... ./gradlew :host:realLlmTest
tasks.register<Test>("realLlmTest") {
    group = "verification"
    description = "真实 OpenAI 兼容后端抽样（需 STRAGENT_REAL_LLM_KEY；默认不跑）"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnit()
    filter { includeTestsMatching("com.stragent.host.RealBackendEvalTest") }
    testLogging { showStandardStreams = true }
}

// TA 校准（T-109）：真实后端测长度依从曲线并推荐 STRAGENT_LENGTH_TA 因子。
// opt-in 同 realLlmTest：无 Key 时打印指引以 0 退出（手动任务，SKIP 不假绿）。
tasks.register<JavaExec>("calibrateTA") {
    group = "verification"
    description = "TA 校准——真实后端实测字数依从并推荐 STRAGENT_LENGTH_TA（需 STRAGENT_REAL_LLM_KEY；默认不跑）"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.stragent.host.CalibrateTaMainKt")
}
tasks.named<Test>("test") {
    exclude("**/RealBackendEvalTest.class")
    // T-135 两段式证据轮的模式转发：Gradle 的 -D 不会自动进测试 fork（data 模块 transcript 同款先例）。
    systemProperty("stragent.t135.live.mode", System.getProperty("stragent.t135.live.mode") ?: "")
}
