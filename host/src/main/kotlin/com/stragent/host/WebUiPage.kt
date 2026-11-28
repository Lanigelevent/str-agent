package com.stragent.host

/**
 * Web UI 单页应用 —— D11 v3 S4 Web UI 前端（**事件查看器**）。
 *
 * 从 classpath 资源加载 HTML（避免 Kotlin 字符串模板与 JS/Alpine.js 的 $ 冲突）。
 * 资源路径：resources/web/index.html
 *
 * 诚实边界（T-123 后复核）：
 *  - 报告 tab 渲染正文+离线 SVG 图表（T-086）；操作 tab 经 `/ui/call`（T-123）直接驱动
 *    导入→报告→追问（固定 ui 会话，进度在事件页 sessionId=ui 可见）——报告链浏览器闭环。
 *  - `/events` 强制 Bearer（T-045 / AD-029）：用 `/ui?token=<MCP token>` 打开；
 *    EventSource 不能设请求头，故 token 走查询参数。事件按会话隔离，缺省看
 *    `default` 会话，可加 `&sessionId=<id>` 观看指定会话。
 *  - 前端框架 Alpine.js 从 CDN 加载：**离线环境页面不可用**（本仓其余部分均可离线）。
 *  - 数据不出本机（SSE 连同一 HTTP 服务的回环地址；监听地址默认 127.0.0.1，
 *    容器场景可用 `STRAGENT_HTTP_BIND=0.0.0.0` 覆盖，且必须配 `STRAGENT_OAUTH_ISSUER`
 *    （T-146 起远程绑定未配 issuer 拒绝启动），Bearer/OAuth 仍强制）。
 */
object WebUiPage {

    val html: String by lazy {
        val stream = Thread.currentThread().contextClassLoader
            ?.getResourceAsStream("web/index.html")
            ?: WebUiPage::class.java.classLoader?.getResourceAsStream("web/index.html")
            ?: WebUiPage::class.java.getResourceAsStream("/web/index.html")
        stream?.bufferedReader()?.readText()
            ?: "<html><body><h1>Str Agent Web UI</h1><p>资源文件 web/index.html 未找到</p></body></html>"
    }
}
