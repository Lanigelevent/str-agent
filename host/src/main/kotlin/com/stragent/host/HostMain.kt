package com.stragent.host

import com.stragent.data.McpBridge
import com.stragent.data.McpStdioServer
import java.io.File

/**
 * 无头宿主入口 —— 以**独立进程**形态对外提供 MCP 能力面。
 *
 * 这是「纯 agent 插件」形态下插件的运行方式：
 *   - 进程内：StrAgentHost 装载 StrAgentPlugin，能力全在插件里；
 *   - 进程外：MCP 服务器对任意 MCP 客户端开放，两条接线：
 *       · **stdio**（`--transport stdio`）：宿主一条命令拉起，进程随宿主生死 —— dev-agent 同款形态；
 *       · **http**（`--transport http`，默认）：独立长驻服务，127.0.0.1:7787 + Bearer 鉴权。
 *
 * 用法：
 * ```
 * gradlew :host:run --args="--transport stdio"   # 被宿主拉起的 stdio MCP 服务器
 * gradlew :host:run                              # 起 HTTP MCP 桥并阻塞
 * gradlew :host:run --args="--manifest"           # 打印插件清单（供外部 harness 发现）
 * gradlew :host:run --args="--token"              # 只打印 MCP token（启动横幅只显示指纹）
 * ```
 *
 * 安全边界：HTTP 传输只绑 localhost + Bearer 鉴权 + 只读/受控工具面；
 * stdio 传输不做 Bearer（进程边界即信任边界，进程由宿主拉起、生命周期与宿主一致）。
 */
/** CLI 解析结果 */
data class HostCli(val mode: String, val transport: String, val target: String? = null)

/**
 * 解析命令行参数（**可单测**）。
 *
 * 支持两种写法：
 *   `--manifest` / `manifest`      → 模式
 *   `--transport stdio` / `--transport=stdio` → 传输方式
 *
 * 注意：`--transport` 的**取值**不能被当成模式（这里曾把 "stdio" 误判成模式，
 * 导致 `--manifest` 被忽略、进程直接进了 serve 模式挂着 —— 故抽成函数并加单测）。
 */
fun parseCli(argv: List<String>): HostCli {
    val cleaned = argv.map { it.trim() }.filter { it.isNotEmpty() }
    val transportIdx = cleaned.indexOf("--transport")
    val transport = when {
        transportIdx >= 0 -> cleaned.getOrNull(transportIdx + 1) ?: "http"
        else -> cleaned.firstOrNull { it.startsWith("--transport=") }?.substringAfter('=') ?: "http"
    }
    val positional = cleaned.filterIndexed { index, arg ->
        arg != "--transport" && !(transportIdx >= 0 && index == transportIdx + 1) && !arg.startsWith("--transport=")
    }
    val mode = positional.firstOrNull()?.removePrefix("--") ?: "serve"
    // 第二个位置参数：kv-export / kv-import 的目标文件路径（T-076）
    val target = positional.getOrNull(1)
    return HostCli(mode = mode, transport = transport, target = target)
}

fun main(args: Array<String>) {
    val cli = parseCli(args.toList())
    // D25 审查收口：transport 取值拼错此前静默回落 http（stdio 客户端挂上会拿到 HTTP 服务）——显式拒绝
    if (cli.transport !in setOf("stdio", "http")) {
        System.err.println(  // lint:allow 错误诊断走 stderr（stdout 留给协议帧/帮助输出）
            com.stragent.core.i18n.Messages.get("cli.transport.invalid").replace("{value}", cli.transport),
        )
        kotlin.system.exitProcess(2)
    }
    if (cli.mode == "help" || cli.mode == "--help" || cli.mode == "-h") {
        // 帮助走 stdout（显式请求的输出；诊断/错误才走 stderr——本仓协议纪律）。文案走 i18n（D25）。
        println(com.stragent.core.i18n.Messages.get("cli.help").replace("\\n", "\n"))
        return
    }
    val home = StrAgentHostBootstrap.defaultHome()
    val bootstrap = StrAgentHostBootstrap.create(home)
    // T-054：受控计算开启即等于允许执行任意代码（RCE）——启动时明示，不藏在文档里
    if (computeSandboxAuthorized(bootstrap.prefs)) {
        System.err.println( // lint:allow 安全告警必须走 stderr（stdout 留给协议帧/帮助输出）
            "警告：受控计算已开启（compute.sandbox.enabled=true 或 STRAGENT_SANDBOX=1）。" +
                "stragent_compute_python 可执行任意 Python 代码（RCE）——沙箱只有纵深防御，" +
                "不是系统级隔离；请仅在你信任调用方时开启。",
        )
    }
    val mode = cli.mode
    val transport = cli.transport

    when (mode) {
        "manifest" -> {
            printUtf8(bootstrap.plugin.manifest.toJson().toString())
            return
        }

        "dsh-manifest" -> {
            printUtf8(bootstrap.plugin.manifest.toDshManifestJson().toString())
            return
        }

        "token" -> {
            // D25 审查收口：文档承诺「启用 OAuth 后 token 子命令不再被接受」——兑现它。
            // OAuth 启用判据与 bootstrap 的 oauth 装配同源（远程绑定 + issuer 已配，T-146）；
            // 此时静态 Bearer 已不被 /mcp 接受，照常打印 = 误导性成功。
            val oauthEnabled = bootstrap.httpBindAddress != "127.0.0.1" &&
                !com.stragent.core.config.Config.get("STRAGENT_OAUTH_ISSUER").isNullOrBlank()
            if (oauthEnabled) {
                System.err.println(  // lint:allow 错误诊断必须走 stderr（stdout 留给协议帧/帮助输出）
                    com.stragent.core.i18n.Messages.get("cli.oauth.reject"),
                )
                kotlin.system.exitProcess(2)
            }
            println(McpBridge.currentToken(bootstrap.prefs))
            return
        }

        "capabilities" -> {
            bootstrap.plugin.capabilities().forEach { cap ->
                println("${cap.id}\t${cap.kind}\t${cap.description}")
            }
            return
        }

        "interactive-batch" -> {
            // 交互式替代批测（2026-11-14 用户裁决）：目录作为检查点——逐轮落盘 prompt/应答
            val dir = java.io.File(cli.target ?: "build/model-eval/interactive")
            interactiveBatchMain(dir, dir.name)
            return
        }

        "list-tools" -> {
            // 工具面以**运行时注册表**为准：走与 MCP 客户端完全相同的 tools/list 分发路径，
            // 避免静态快照与真实能力面漂移（曾有 mcp-tools.json 列出未注册的 web_search）。
            printUtf8(bootstrap.mcpBridge.dispatch("tools/list", kotlinx.serialization.json.JsonObject(emptyMap())).toString())
            return
        }

        "kv-export" -> {
            // T-076 kv 备份导出（宿主自用，不进对外工具面）：全量快照到一个 JSON 文件
            val path = cli.target ?: run {
                println("kv-export requires a target path: --args=\"kv-export /path/backup.json\"")
                return
            }
            val count = KvBackup.export(bootstrap.kv, java.io.File(path))
            println("Exported $count entries -> $path")
            return
        }

        "kv-import" -> {
            // T-076 kv 备份导入：默认只补缺失键（不覆盖现有状态）；导入后回灌内存数据集
            val path = cli.target ?: run {
                println("kv-import requires a snapshot path: --args=\"kv-import /path/backup.json\"")
                return
            }
            val written = KvBackup.import(bootstrap.kv, java.io.File(path))
            val restored = bootstrap.abilityDatasets.restore()
            println("Imported $written entries -> $path (datasets restored: $restored)")
            return
        }

        "serve" -> Unit

        else -> {
            // D25 审查收口：未知子命令此前 println 后 exit 0——编排层把拼错的子命令当成
            // 成功；诊断也走 stdout 会污染 stdio 协议流（本仓纪律：stdout 留协议，stderr 留日志）。
            System.err.println(  // lint:allow 错误诊断必须走 stderr（stdout 留给协议帧/帮助输出）
                com.stragent.core.i18n.Messages.get("cli.unknown.mode").replace("{mode}", mode),
            )
            kotlin.system.exitProcess(2)
        }
    }

    if (transport == "stdio") {
        // 被宿主拉起的 stdio MCP 服务器：stdout 只写 JSON-RPC 帧，日志一律走 stderr
        System.err.println("Str Agent · stdio MCP 服务器已启动（数据目录：${home.absolutePath}）") // lint:allow stdio 的 stdout 只能写 JSON-RPC 帧
        McpStdioServer(bootstrap.mcpBridge, estimateTotalMs = { bootstrap.recentReports.durationP50() }).serve()
        return
    }

    val port = bootstrap.mcpBridge.start()
    val token = McpBridge.currentToken(bootstrap.prefs)
    val bind = StrAgentHostBootstrap.httpBindAddress()
    println("Str Agent · 纯 agent 插件宿主已启动")
    println("  数据目录：${home.absolutePath}")
    println("  MCP 桥  ：http://$bind:$port/mcp （Bearer 鉴权）")
    println("  Web UI  ：http://$bind:$port/ui?token=<MCP Token（用 --token 取）>")
    // T-046：启动横幅只出指纹——完整 token 不落日志（--token 显式获取）
    println("  MCP Token：鉴权已开启（指纹 ${McpBridge.tokenFingerprint(token)}；完整值用 --token 查看）")
    println("  能力面  ：${bootstrap.plugin.capabilities().joinToString(", ") { it.id }}")
    // T-047：DB 工具只读靠 SQL 校验 + 5s/5 万行上限；驱动级只读不可用，硬保证是只读账号
    println("  数据库  ：只读靠 SQL 校验与 5s/50000 行上限；硬保证请使用只读数据库账号")
    println("  Ctrl+C 退出")

    Runtime.getRuntime().addShutdownHook(
        Thread {
            bootstrap.mcpBridge.stop()
            println("MCP 桥已停止")
        },
    )
    // 阻塞直到进程被终止
    try {
        Thread.currentThread().join()
    } catch (_: InterruptedException) {
        bootstrap.mcpBridge.stop()
    }
}

/**
 * 打印机器可读输出（JSON / manifest）时强制 UTF-8。
 *
 * `println` 走 JVM 默认字符集——中文 Windows 上是 GBK，管道/文件里的 JSON 会带
 * 非法 UTF-8 字节（实测 `--list-tools > x.json` 解析失败）。控制台人读输出不受影响，
 * 但凡是「给程序读」的都以字节写出。
 */
private fun printUtf8(text: String) {
    val out = System.out
    out.write(text.toByteArray(Charsets.UTF_8))
    out.write('\n'.code)
    out.flush()
}
