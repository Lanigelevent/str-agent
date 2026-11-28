package com.stragent.data

import com.stragent.core.ports.AgentTool
import com.stragent.core.ports.ProgressBus
import com.stragent.core.i18n.Messages
import com.stragent.core.observability.StructuredLogger
import com.stragent.core.ports.PrefsStore
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * /ui 驾驶舱的数据提供者（T-070）——宿主实现，桥负责鉴权与路由。
 *
 * 三个端点都挂在 `GET /ui/data/<name>` 下，与 `/events` 同一套鉴权
 * （Bearer 或 `?token=`）——**数据端点必须鉴权**：数据集名与报告正文是敏感面。
 * 纪律：提供者只读快照，绝不阻塞工具调用（内部各自保证线程安全）。
 */
interface UiDataProvider {
    /** 服务概览：版本 / 运行时长 / 报告统计（次数、token 合计、P50/P95） */
    fun overview(): JsonObject

    /** 数据集清单：id / 名称 / 行列数 / 截断 / 落盘状态 */
    fun datasets(): JsonObject

    /** 最近报告（环形缓冲，最多 10 条）：正文摘录 + 引擎 + token + traceId */
    fun reports(): JsonObject
}

/**
 * MCP 标准桥 —— 在 App 内起一个 **仅绑 localhost** 的 HTTP 服务，说 MCP Streamable
 * HTTP 协议（JSON-RPC 2.0：initialize / notifications/initialized / tools/list /
 * tools/call）。任何说 MCP 的 AI 客户端（STEPX Agent、桌面 IDE、Claude 等经 adb
 * reverse 或同进程调用）都能即插即用Str Agent的能力面。
 *
 * 安全边界（三条，缺一不可）：
 *   1. **只绑 127.0.0.1**——同设备进程可达，局域网/外网不可达；
 *   2. **Bearer 鉴权**——token 由 `SecureRandom` 生成 128-bit（32 hex）、常量时间比较、
 *      日志只留指纹（T-046）；`--token` 可显式取原文，MCP 客户端配到 header 里；
 *   3. **只读工具面**——目录里的工具全是查询/统计（import_table 也只写本机内存），
 *      无删除/无网络写入。
 *
 * 生命周期：AppContainer 持有，设置页开关控制 start/stop；端口固定 7787
 * （与 stepx mock 的 7788 错开，同机联调时互不干扰）。
 *
 * 进度：客户端在 `tools/call` 里带 `params._meta.progressToken` **且** Accept 里写了
 * `text/event-stream` 时，走 SSE 渐进响应（同一条流上进度帧… + 结果帧，见
 * [serveStreamed]）；没协商 SSE 的调用仍把进度落 stderr，响应保持单帧 JSON。
 * 两条传输的帧形制由 [McpFrames] 单一定义（与 stdio 逐字一致）。
 *
 * 反向通道（T-056）：`GET /mcp`（Accept: text/event-stream）是**服务端消息流**，
 * elicitation 的 `elicitation/create` 从这条流下发；客户端把 JSON-RPC 响应 POST 回
 * `/mcp`，由 [ElicitationBroker] 收口（见 [serveServerMessages]）。
 *
 * 并发（T-030 / T-045）：弹性线程池（核心 4、上限 64、daemon）——长任务期间
 * 短请求不再排队。会话态由 [SessionRegistry] 按 `Mcp-Session-Id` 头分桶：
 * 写互斥、读快照；具名会话严格隔离，缺省 default 会话保持「最近导入」的
 * 既有语义（向后兼容）。dispatch 经 [McpSessionContext]（ThreadLocal）传会话，
 * 与 ProgressBus 同形制，业务层签名零改动。
 *
 * 传输层安全（T-045 / AD-029）：
 *  - `/events` 与 `/mcp` 一样强制 Bearer（浏览器 EventSource 不能设请求头，故
 *    `/events` 额外接受 `?token=`，见 [handleEvents]）；
 *  - 事件按会话过滤（`?sessionId=` 或 `Mcp-Session-Id`，缺省 default）；
 *  - SSE 连接有独立额度（[MAX_SSE_CONNECTIONS]），超出回 429 而不是拖垮 `/mcp`；
 *    每连接队列有界，满时丢帧计数（不阻塞生成主链）；
 *  - 限流在**鉴权之后**（未鉴权请求不消耗令牌桶），远程绑定默认开启。
 */
class McpBridge(
    private val datasets: AbilityDatasets,
    private val prefs: PrefsStore,
    private val extraTools: List<AgentTool> = emptyList(),
    /**
     * 动态工具提供器（可选）：工具面随会话状态变化时用它（如策展分析工具依赖「会话当前数据集」）。
     * 每次 tools/list / tools/call 都会重新取一次，保证目录与当下数据一致。
     */
    private val extraToolsProvider: (() -> List<AgentTool>)? = null,
    /**
     * T-155：tools/list 目录裁剪谓词——返回「当前不应下发的工具名集合」。
     * **调度不受影响**（tools/call 对被裁工具仍走 invoke 侧授权拦截——运行时授权语义不变：
     * 宿主运行中 prefs.set 开启沙箱后，下一次 tools/list 即恢复下发）。缺省不裁。
     */
    private val catalogHiddenNames: () -> Set<String> = { emptySet() },
    /** 溯源留痕器（可选）：把每次工具调用记进分析链，供「重放复核」用 */
    private val provenance: ProvenanceRecorder? = null,
    /** 会话注册表（可选）：给了就按会话分桶「当前数据集指针」；不给保持全局语义 */
    private val registry: SessionRegistry? = null,
    /** Web UI HTML 内容（D11 v3 S4）：非 null 时 start() 注册 /ui 和 /events 端点 */
    private val webUiHtml: String? = null,
    /**
     * HTTP 监听地址：缺省仅回环（同机使用，安全边界默认不放宽）。
     * 容器/端口映射场景必须显式给 `0.0.0.0`，否则端口映射到的是容器内的回环，
     * 宿主机连不上（Bearer token 仍是必过的一道闸）。
     */
    private val bindAddress: String = "127.0.0.1",
    /**
     * 限流器（D15 T4 / AD-029）：缺省**按监听地址决定**——回环不限流（单机自用），
     * 远程绑定默认开启（10 req/s、burst 20）。检查在鉴权之后，未鉴权请求不耗桶。
     * 显式传 `null` 可关闭（本地自用的合法选择）。
     */
    private val rateLimiter: RateLimiter? = defaultRateLimiterFor(bindAddress),
    /**
     * 任务表（T-056 / D17 G-15）：`_meta.task` 异步调用的句柄与轮询状态。
     * 缺省进程内单例；测试注入假时钟/小容量以验证过期与淘汰，不改生产语义。
     */
    private val taskStore: McpTaskStore = McpTaskStore(),
    /**
     * OAuth 2.1 资源服务器（T-056 / D17 G-15）：非 null 时 `/mcp` 与 `/events`
     * 只认 JWT（静态 Bearer 一律不认，否则绕开过期与作用域）。是否启用由部署形态
     * 决定（[OAuthResourceServer.fromEnvironment]：仅远程绑定）。
     */
    private val oauth: OAuthResourceServer? = null,
    /**
     * elicitation 双向通道（T-056）：stdio / HTTP GET 流注册发送器，工具侧发起提问。
     * 公开给传输层与宿主工具共用**同一个**实例（否则提问发不出去）。
     */
    val elicitation: ElicitationBroker = ElicitationBroker(),
    /**
     * OTel 追踪（T-056 / D17 G-16）：非 null 时每次 `tools/call` 产出一个 SERVER span，
     * 并沿 [McpTraceContext] 把链路交给工具（报告根 span 挂到同一条 trace 下）。
     * 未配置（null）时**零成本**：不建 span、不导出，上下文仍传递上游 traceparent。
     */
    private val otel: com.stragent.core.observability.OtelTraces? = null,
    /**
     * OTel metrics（T-065）：非 null 时每次 `tools/call` 计数与记时延直方图
     * （`str_agent.tool.calls` / `str_agent.tool.duration`），与追踪相互独立——
     * 只配 metrics 不配 traces 也照常记。未配置时零成本。
     */
    private val metrics: com.stragent.core.observability.OtelMetrics? = null,
    /**
     * 头采样器（T-065）：只对**新 trace 根**做比率判定；有上游 traceparent 时
     * 一律遵从上游的 sampled 标志（parent-based）。null = 未配置 = 全采（现行为）。
     */
    private val sampler: com.stragent.core.observability.OtelSampler? = null,
    /**
     * /ui 驾驶舱数据源（T-070）：null = 不注册驾驶舱数据端点（旧宿主零改动）。
     * 端点路径：`/ui/data/overview`、`/ui/data/datasets`、`/ui/data/reports`。
     */
    val uiDataProvider: UiDataProvider? = null,
    /**
     * 预计总耗时提供者（T-082 / D21 W-4）：非 null 时，本次 `tools/call` 的**首个进度帧**
     * 附 `estimatedTotalMs`（返回值 ms；null = 无历史可估，该帧不带此字段）。
     * 语义：近期同类型 trace 的 P50——由宿主接线（str-agent 宿主用 RecentReports 的
     * 报告时长 P50），桥不关心估计从哪来。
     */
    private val estimateTotalMs: (() -> Long?)? = null,
) {
    private var server: HttpServer? = null
    private var executor: ExecutorService? = null
    private val json = Json
    private val log = StructuredLogger.getLogger("McpBridge")

    /** SSE 连接额度：满则 429（不是排队，避免拖垮主链） */
    private val sseSlots = Semaphore(MAX_SSE_CONNECTIONS)
    private val sseActive = AtomicInteger(0)
    private val sseRejected = AtomicLong(0)
    private val sseDroppedFrames = AtomicLong(0)

    /** 服务生命周期：stop() 后 SSE 循环在下一个轮询点退出 */
    @Volatile
    private var running = false

    /** 方法路由 + 异步任务 + 工具执行核心（T-094 拆分第 1 步，行为零变化） */
    /** T-156 P2-13：OAuth 模式按主体分桶限流（静态模式走全局 [rateLimiter]，单凭据等价） */
    private val oauthBuckets = RateLimitBuckets(
        capacity = 20,
        refillRatePerSecond = 10.0,
    )

    private val taskDispatch = McpTaskDispatch(
        datasets, extraTools, extraToolsProvider, provenance, registry, taskStore, otel, metrics, sampler, log,
        catalogHiddenNames = catalogHiddenNames,
    )

    /** 鉴权单元（T-094 拆分第 3 步）：OAuth/静态 Bearer 双模闸 + RFC 9728 元数据端点 */
    private val authGate = McpAuthGate(oauth) {
        server?.address?.let { "127.0.0.1:${it.port}" } ?: "127.0.0.1:$PORT"
    }

    /** UI 端点单元（T-094 拆分第 4 步）：/ui、/events、/ui/data 系列端点的处理器与注册 */
    private val uiEndpoints = McpUiEndpoints(
        webUiHtml, uiDataProvider, authGate, { running },
        uiCallDispatcher = { method, params -> dispatch(method, params) },
        sseSlots, sseActive, sseRejected, sseDroppedFrames, log,
    )

    /** 当前 SSE 连接数（诊断/测试用） */
    fun activeSseConnections(): Int = sseActive.get()

    /** 因超过 [MAX_SSE_CONNECTIONS] 被拒的累计次数（诊断/测试用） */
    fun rejectedSseConnections(): Long = sseRejected.get()

    /** 当前任务表条数（T-174 水位线：异步任务占用） */
    fun activeTasks(): Int = taskStore.size()

    /**
     * `/events` 慢消费者累计丢帧数（T-048 / D17 G-06：丢弃不许静默）。
     * 每条连接结束时把本连接的丢弃数汇总到这里，同时打警告日志。
     */
    fun droppedSseFrames(): Long = sseDroppedFrames.get()

    companion object {
        const val PORT = 7787

        /** 单请求体上限（D17 §8.3）：超过即 413，按上限 +1 有界读取，不整段入内存。 */
        const val MAX_REQUEST_BODY_BYTES = 8 * 1024 * 1024

        private const val TOKEN_KEY = "mcp.token"

        /** 迁移标记（T-046）：旧 16 hex token 已过一次性轮换，之后不再动用户 token */
        private const val TOKEN_VERSION_KEY = "mcp.token.version"
        private const val TOKEN_VERSION = "2"

        /**
         * 协议版本（T-056 / D17 G-15）：默认 2026-07-28（无状态核心）。
         * 旧版（2025-06-18 / 2025-03-26）保留到 [McpProtocol.COMPAT_UNTIL]，
         * 协商与弃用提示统一见 [McpProtocol]。
         */

        /** 短请求核心线程数（与 T-030 口径一致：长任务期间短请求不排队） */
        private const val HTTP_CORE_POOL = 4

        /** 弹性上限：SSE 长连接最多占 [MAX_SSE_CONNECTIONS] 条，其余留给 /mcp */
        private const val HTTP_MAX_POOL = 64

        /** SSE 并发连接上限（D17 G-02：4 条 SSE 曾可占满固定 4 线程池） */
        const val MAX_SSE_CONNECTIONS = 8

        const val SESSION_HEADER = McpProtocol.SESSION_HEADER

        /** 绑定地址是否回环（决定限流默认值；也用于「远程绑定必须默认安全」的判定） */
        fun isLoopbackBind(address: String): Boolean = when (address.trim().lowercase()) {
            "127.0.0.1", "localhost", "::1", "[::1]" -> true
            else -> false
        }

        /** 远程绑定默认开启限流：10 req/s、burst 20（AD-029）；回环默认关闭 */
        fun defaultRateLimiterFor(bindAddress: String): RateLimiter? =
            if (isLoopbackBind(bindAddress)) null else RateLimiter(capacity = 20, refillRatePerSecond = 10.0)

        fun currentToken(prefs: PrefsStore): String {
            val saved = prefs.get(TOKEN_KEY, "")
            if (saved.isNotEmpty()) {
                // 一次性迁移（T-046）：T-045 前的 token 是 16 hex / 64 bit 且可能出自
                // 非密码学随机源——升级即作废，换成 128-bit。标记落盘后不再动用户 token
                // （用户手动设置的 32 hex 或口令式 token 原样保留）。
                if (isLegacyToken(saved) && prefs.get(TOKEN_VERSION_KEY, "") != TOKEN_VERSION) {
                    val upgraded = generateToken()
                    prefs.set(TOKEN_KEY, upgraded)
                    prefs.set(TOKEN_VERSION_KEY, TOKEN_VERSION)
                    return upgraded
                }
                return saved
            }
            val token = generateToken()
            prefs.set(TOKEN_KEY, token)
            prefs.set(TOKEN_VERSION_KEY, TOKEN_VERSION)
            return token
        }

        /** T-045 及更早的 token 形制：16 个小写 hex（疑似 64-bit 弱随机源） */
        private fun isLegacyToken(token: String): Boolean =
            token.length == 16 && token.all { it in "0123456789abcdef" }

        /** 熵源（线程安全；一个进程一个实例即可） */
        private val secureRandom = SecureRandom()

        /**
         * 生成 128-bit Bearer token：16 个 `SecureRandom` 字节 → 32 个 hex 字符
         * （T-046 / D17 G-05：旧的 `kotlin.random.Random` 16 hex 只有 64 bit，可预测）。
         */
        fun generateToken(): String {
            val bytes = ByteArray(16)
            secureRandom.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** token 指纹：SHA-256 前 8 个 hex —— 日志只留指纹，不可反推原文（T-046） */
        fun tokenFingerprint(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }.take(8)

        /**
         * 常量时间比较（T-046）：两端先做 SHA-256 定长摘要，再 `MessageDigest.isEqual`，
         * 避免「长度不同/前缀相同」这类逐字符短路泄露给攻击者时间侧信道。
         */
        fun tokenMatches(expected: String, supplied: String?): Boolean {
            if (supplied == null) return false
            val digest = MessageDigest.getInstance("SHA-256")
            val a = digest.digest(expected.toByteArray(Charsets.UTF_8))
            val b = digest.digest(supplied.toByteArray(Charsets.UTF_8))
            return MessageDigest.isEqual(a, b)
        }

        fun refreshToken(prefs: PrefsStore): String {
            prefs.remove(TOKEN_KEY)
            return currentToken(prefs)
        }
    }

    val isRunning: Boolean get() = server != null

    /** 启动 HTTP 服务并返回**实际**监听端口（`port = 0` 时由系统分配，供测试隔离用） */
    fun start(port: Int = PORT): Int {
        server?.let { return it.address.port }
        val token = currentToken(prefs)
        // T-085：listen backlog 0 → 128。0 在 Windows 回环上实际可用容量极小，
        // 并发 connect 突发（客户端建连风暴）会直接 ConnectException（run 1884 的
        // c64 抖动实证：服务端 backlog 与客户端建连突发不匹配）。128 = 常规服务器
        // 默认量级，真实 MCP 客户端并发建连同样受益——健壮性修复，非判据放宽。
        val http = HttpServer.create(InetSocketAddress(InetAddress.getByName(bindAddress), port), 128)
        http.createContext("/mcp") { exchange -> handle(exchange, token) }
        // T-156：健康探针（无需鉴权，只回存活；compose healthcheck 可从 /dev/tcp 升级到它）
        http.createContext("/health") { exchange ->
            runCatching { respond(exchange, 200, buildJsonObject { put("ok", true) }) }
        }
        if (oauth != null) {
            // RFC 9728：受保护资源元数据（公开、无需鉴权）。带资源路径后缀的变体
            // 是客户端按 resource URL 拼出来的标准位置，两个都注册。
            http.createContext("/.well-known/oauth-protected-resource/mcp") { exchange ->
                authGate.handleProtectedResourceMetadata(exchange)
            }
            http.createContext("/.well-known/oauth-protected-resource") { exchange ->
                authGate.handleProtectedResourceMetadata(exchange)
            }
        }
        uiEndpoints.registerInto(http, token)
        // com.sun.net.httpserver 只支持服务器级执行器（无法按 context 分池），
        // 故用「核心 4 + 弹性上限 64」：SSE 最多占 MAX_SSE_CONNECTIONS 条，
        // 其余线程随时服务 /mcp —— 长连接在结构上无法饿死短请求。
        val pool = ThreadPoolExecutor(
            HTTP_CORE_POOL, HTTP_MAX_POOL, 60L, TimeUnit.SECONDS, SynchronousQueue(),
        ) { runnable -> Thread(runnable, "mcp-http").apply { isDaemon = true } }
        http.executor = pool
        http.start()
        running = true
        executor = pool
        server = http
        return http.address.port
    }

    fun stop() {
        running = false
        server?.stop(0)
        server = null
        // 中断阻塞在 SSE 轮询上的线程（HttpServer.stop 不会打断 handler）
        executor?.shutdownNow()
        executor = null
    }

    private fun handle(exchange: HttpExchange, token: String) {
        // 是否已经把响应头发出去过（SSE 一旦开流就不能再改状态码/Content-Type）
        var streamed = false
        // 取消/异常分支要用请求 id 组错误帧，先留个引用
        var requestIdElement: JsonElement? = null
        try {
            // ① 鉴权先行（AD-029）：临时令牌必须（省得同设备上别的进程扫端口白嫖）。
            //    OAuth 开启时只认 JWT；静态 token 一律不认（否则绕开过期与作用域）。
            val principal = authGate.ensureAuthorized(exchange, token) ?: return
            // ② 鉴权通过后才限流（T-156 P2-13 按主体分桶）：未鉴权请求不消耗令牌桶。
            //    OAuth 模式按 JWT sub 分桶（多用户互不挤占）；静态模式单凭据=全局等价。
            val blocked = when {
                rateLimiter == null -> false
                oauth != null -> !oauthBuckets.tryAcquire(principal)
                else -> !rateLimiter.tryAcquire()
            }
            if (blocked) {
                exchange.responseHeaders.set("Retry-After", "1")
                respond(exchange, 429, buildJsonObject {
                    put("error", Messages.get("error.rate_limit.exceeded"))
                    put("retryAfter", "1")
                })
                return
            }
            if (exchange.requestMethod == "GET") {
                // Streamable HTTP 的**服务端消息流**（T-056）：elicitation 等反向请求靠它下发
                serveServerMessages(exchange)
                return
            }
            if (exchange.requestMethod != "POST") {
                respond(exchange, 405, buildJsonObject { put("error", Messages.get("error.method_not_allowed")) })
                return
            }
            // 请求体上限（D17 §8.3 / T-059）：先看声明长度快速拒绝，再按 8MB+1 有界读取，
            // 防止无 Content-Length 的分块请求把整段读进内存。
            // T-163：解析提前到版本闸之前——版本闸/路由头校验的 400 错误帧要带请求 id。
            val declaredLength = exchange.requestHeaders.getFirst("Content-Length")?.trim()?.toLongOrNull()
            if (declaredLength != null && declaredLength > MAX_REQUEST_BODY_BYTES) {
                rejectTooLarge(exchange)
                return
            }
            val rawBody = exchange.requestBody.readNBytes(MAX_REQUEST_BODY_BYTES + 1)
            if (rawBody.size > MAX_REQUEST_BODY_BYTES) {
                rejectTooLarge(exchange)
                return
            }
            val body = String(rawBody, Charsets.UTF_8)
            // JSON-RPC -32700：请求体不是合法 JSON 是协议错误，不是服务端内部错误——
            // 回 400 + ParseError（id 未知，错误帧不带 id），不让它落进 -32603 兜底。
            val rpc = try {
                json.parseToJsonElement(body).jsonObject
            } catch (parse: kotlinx.serialization.SerializationException) {
                respondSafe(exchange, 400, McpFrames.error(null, -32700, Messages.get("error.bad_json")))
                return
            }
            val id = rpc["id"]
            requestIdElement = id
            val method = rpc["method"]?.let { (it as? JsonPrimitive)?.content } ?: ""
            val params = rpc["params"] as? JsonObject ?: JsonObject(emptyMap())
            // 协议版本（T-056 / D17 G-15）：无状态核心下版本由请求头声明，不再依赖 initialize。
            // 未知版本回 400 + JSON-RPC -32022（UnsupportedProtocolVersionError，带 supported/requested）。
            if (!negotiateProtocol(exchange, id, params)) return
            // T-163：路由头↔体一致性校验（-32020，仅对声明 2026-07-28 的「现代请求」强制；
            // 遗留客户端（头缺失/旧版）不受约束；无 method 的帧是客户端对服务端请求的
            // 回包，不是请求，不适用路由头）。
            if (rpc.containsKey("method")) {
                McpProtocol.validateRoutingHeaders({ exchange.requestHeaders.getFirst(it) }, method, params)
            }
            // 会话 id：MCP Streamable HTTP 标准头；缺省 default（向后兼容）
            val sessionId = exchange.requestHeaders.getFirst(SESSION_HEADER)
                ?.trim()?.takeIf { it.isNotEmpty() } ?: SessionRegistry.DEFAULT_SESSION
            // 客户端对服务端请求（elicitation/create）的响应：无 method、有 id + result/error。
            // MCP 语义是 202 Accepted（无响应体）——这是「回包」，不该再生成 JSON-RPC 回执。
            if (!rpc.containsKey("method")) {
                if (!elicitation.consume(sessionId, rpc)) {
                    // 未命中多半是已超时/别的会话的迟到响应：丢弃但留痕，不报错
                    log.info("丢弃未匹配的服务端请求响应", "session" to sessionId, "id" to (id?.toString() ?: "无"))
                }
                // 202 Accepted 无响应体：这是「回包」，不该再生成 JSON-RPC 回执
                exchange.sendResponseHeaders(202, -1)
                return
            }
            // 通知（无 id）按 MCP 语义不回响应体
            if (id == null) {
                // 取消通知：置标志，由在途的长任务在下一个可停点自行退出（协作式）
                if (method == "notifications/cancelled") {
                    params["requestId"]?.let { McpRequestCancellation.cancel(sessionId, it.toString()) }
                }
                respond(exchange, 204, JsonObject(emptyMap()))
                return
            }
            val progressToken = (params["_meta"] as? JsonObject)?.get("progressToken")
            val requestKey = id.toString()
            if (progressToken != null && method == "tools/call" && acceptsEventStream(exchange)) {
                // 客户端带了 progressToken 且明确接受 SSE → 走渐进响应（同一条流上：进度帧… + 结果帧）
                streamed = true
                serveStreamed(exchange, progressToken, id, method, params, sessionId, requestKey)
                return
            }
            val result = McpSessionContext.with(sessionId) {
                com.stragent.core.ports.UiEventBroadcaster.withSession(sessionId) {
                    ProgressBus.with({ message ->
                        // 没协商 SSE 的调用保持原行为：进度落 stderr（宿主日志可见），响应仍是单帧 JSON
                        log.info(message, "transport" to "stdio-fallback")
                    }) {
                        dispatch(method, params, requestKey)
                    }
                }
            }
            respond(exchange, 200, McpFrames.result(id, result))
        } catch (cancelled: McpRequestCancelledException) {
            if (streamed) {
                // serveStreamed 内部已按取消语义发过错误帧，这里只记一笔
                log.info("请求已取消", "error" to (cancelled.message ?: "cancelled"))
            } else {
                respondSafe(
                    exchange, 200,
                    McpFrames.error(requestIdElement, -32800, cancelled.message ?: Messages.get("error.cancelled")),
                )
            }
        } catch (notFound: McpMethodNotFoundException) {
            // -32601：方法名拼错不是服务端故障，客户端不该重试。HTTP 上按 2026-07-28
            // 回 404 + JSON-RPC 错误体（与旧版 HTTP+SSE 的裸 404 区分开——客户端看体）。
            if (!streamed) respondSafe(exchange, 404, McpFrames.error(requestIdElement, -32601, notFound.message ?: "未知方法"))
            else log.warn("SSE 流内未知方法", "error" to (notFound.message ?: "unknown method"))
        } catch (header: McpHeaderMismatchException) {
            // T-163：路由头↔体不一致（-32020）——传输层路由信任问题，HTTP 状态固定 400
            if (!streamed) respondSafe(exchange, 400, McpFrames.error(requestIdElement, McpProtocol.ERROR_HEADER_MISMATCH, header.message ?: "Header mismatch"))
            else log.warn("SSE 流内路由头校验失败", "error" to (header.message ?: "header mismatch"))  // lint:allow zh-first（日志文案，与既有 SSE 日志同源）
        } catch (invalid: McpInvalidParamsException) {
            if (!streamed) respondSafe(exchange, 200, McpFrames.error(requestIdElement, -32602, invalid.message ?: "参数非法"))
            else log.warn("SSE 流内参数非法", "error" to (invalid.message ?: "invalid params"))
        } catch (error: Exception) {
            if (streamed) {
                // 已经在 SSE 流上：错误只能以帧的形式存在（serveStreamed 内部已发过则此处静默）
                log.error("SSE 传输异常", "error" to (error.message ?: error.javaClass.simpleName))
            } else {
                respondSafe(exchange, 200, McpFrames.error(null, -32603, error.message ?: Messages.get("error.internal")))
            }
        } finally {
            exchange.close()
        }
    }

    /**
     * SSE 渐进响应：过程进度以 `notifications/progress` 帧实时下发，最后发该请求的结果帧。
     *
     * 帧形制与 stdio 传输**完全一致**（同用 [McpFrames]，并有跨传输对拍测试钉住）；
     * 客户端断开时只丢后续帧，工具照常执行完、溯源照常记录。
     */
    private fun serveStreamed(
        exchange: HttpExchange,
        progressToken: JsonElement,
        id: JsonElement,
        method: String,
        params: JsonObject,
        sessionId: String,
        requestId: String,
    ) {
        val writer = SseProgressWriter(exchange)
        var counter = 0L
        try {
            val result = McpSessionContext.with(sessionId) {
                com.stragent.core.ports.UiEventBroadcaster.withSession(sessionId) {
                    ProgressBus.with({ message ->
                        counter += 1
                        // T-082：首个进度帧带预计总耗时（近期同类型 trace 的 P50，
                        // 由宿主接线提供；无历史 = null = 不发，不编造估计）
                        val estimate = if (counter == 1L) estimateTotalMs?.invoke() else null
                        writer.frame(McpFrames.progress(progressToken, counter, message, estimate))
                    }) {
                        dispatch(method, params, requestId)
                    }
                }
            }
            writer.frame(McpFrames.result(id, result))
        } catch (cancelled: McpRequestCancelledException) {
            writer.frame(McpFrames.error(id, -32800, cancelled.message ?: Messages.get("error.cancelled")))
        } catch (notFound: McpMethodNotFoundException) {
            writer.frame(McpFrames.error(id, -32601, notFound.message ?: "未知方法"))
        } catch (invalid: McpInvalidParamsException) {
            writer.frame(McpFrames.error(id, -32602, invalid.message ?: "参数非法"))
        } catch (error: Exception) {
            writer.frame(McpFrames.error(id, -32603, error.message ?: Messages.get("error.internal")))
        } finally {
            writer.finish()
        }
    }

    /**
     * 协议版本协商（T-056 / D17 G-15）：未知版本回 400 + JSON-RPC -32022
     * （2026-07-28 UnsupportedProtocolVersionError，`error.data` 带 supported/requested，
     * 客户端据此探测服务端支持集或按 Backward Compatibility 检查体判时代）——
     * 不假装支持，也不把连接直接掐掉。通过则把协商结果回显到响应头（[applyProtocolHeaders]）。
     */
    private fun negotiateProtocol(exchange: HttpExchange, id: JsonElement?, params: JsonObject): Boolean {
        val requestedVersion = exchange.requestHeaders.getFirst(McpProtocol.PROTOCOL_HEADER)?.trim()
        if (!McpProtocol.isSupported(requestedVersion)) {
            respond(exchange, 400, McpFrames.error(
                id,
                McpProtocol.ERROR_UNSUPPORTED_PROTOCOL_VERSION,
                "不支持的协议版本：${requestedVersion ?: "(缺失)"}",  // lint:allow zh-first（协议错误文案，与既有错误帧同源）
                data = buildJsonObject {
                    put("supported", kotlinx.serialization.json.JsonArray(McpProtocol.SUPPORTED.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                    requestedVersion?.let { put("requested", it) }
                },
            ))
            return false
        }
        applyProtocolHeaders(exchange, requestedVersion)
        return true
    }

    /**
     * GET /mcp —— **服务端消息流**（MCP Streamable HTTP 的反向通道，T-056）。
     *
     * 为什么需要它：HTTP 传输里请求都是客户端发起的，服务端没有反向通道；
     * elicitation（`elicitation/create`）必须借一条长期 SSE 把问题送给客户端。
     * 客户端收到后把 JSON-RPC 响应 POST 回 `/mcp`，由 [handle] 交给
     * [ElicitationBroker]——两条线共用同一个会话桶（`Mcp-Session-Id`）。
     *
     * 资源纪律与 `/events` 相同：连接额度 [MAX_SSE_CONNECTIONS]、有界队列、
     * 慢消费者丢帧计数（不拖主链）、15s 心跳、`stop()` 后 ~0.5s 内退出。
     *
     * 没有这条流时 elicitation 照样能用：工具侧**立刻**返回 `cancel` + 原因
     * （如实降级），不阻塞等待。
     */
    private fun serveServerMessages(exchange: HttpExchange) {
        if (!negotiateProtocol(exchange, null, JsonObject(emptyMap()))) return
        if (!acceptsEventStream(exchange)) {
            // MCP 规定 GET /mcp 必须声明接受 text/event-stream（浏览器直接访问会走到这里）
            respond(exchange, 406, buildJsonObject {
                put("error", "not_acceptable")
                put("detail", "GET /mcp 需要 Accept: text/event-stream（服务端消息流，用于 elicitation）")
            })
            return
        }
        if (!sseSlots.tryAcquire()) {
            sseRejected.incrementAndGet()
            exchange.responseHeaders.set("Retry-After", "1")
            respond(exchange, 429, buildJsonObject {
                put("error", Messages.get("error.rate_limit.exceeded"))
                put("retryAfter", "1")
                put("limit", MAX_SSE_CONNECTIONS)
            })
            return
        }
        sseActive.incrementAndGet()
        try {
            val sessionId = eventSessionOf(exchange)
            val queue = SseEventQueue(SSE_QUEUE_CAPACITY)
            // 稳定引用（register/unregister 按同一实例匹配，避免顶掉后来的新连接）
            val sender: (String) -> Boolean = { payload -> queue.offer(payload) }
            // 先注册再发响应头：客户端认为「流已建立」时通道必须已经可用
            elicitation.register(sessionId, sender)
            try {
                exchange.responseHeaders.set("Content-Type", "text/event-stream")
                exchange.responseHeaders.set("Cache-Control", "no-cache")
                exchange.responseHeaders.set("Connection", "keep-alive")
                exchange.sendResponseHeaders(200, 0L)
                val out = exchange.responseBody
                var lastHeartbeat = System.nanoTime()
                while (running) {
                    val payload = queue.poll(SSE_POLL_MS)
                    if (payload == null) {
                        val now = System.nanoTime()
                        if (now - lastHeartbeat >= SSE_HEARTBEAT_MS * 1_000_000) {
                            out.write(HEARTBEAT_FRAME.toByteArray(Charsets.UTF_8))
                            out.flush()
                            lastHeartbeat = now
                        }
                    } else {
                        // 帧形制：event: message + data: <JSON-RPC>（Streamable HTTP 的服务端消息）
                        out.write(("event: message\ndata: $payload\n\n").toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                }
            } catch (_: java.io.IOException) {
                // 客户端断开——SSE 的正常结局
            } catch (_: InterruptedException) {
                // 服务停止（stop() 中断轮询）——正常退出
                Thread.currentThread().interrupt()
            } finally {
                elicitation.unregister(sessionId, sender)
                if (queue.droppedCount() > 0) {
                    log.warn(
                        "服务端消息流丢帧",
                        "session" to sessionId,
                        "dropped" to queue.droppedCount().toString(),
                    )
                }
                runCatching { exchange.close() }
            }
        } finally {
            sseActive.decrementAndGet()
            sseSlots.release()
        }
    }

    /**
     * 协议响应头（T-056）：版本回显 + 旧版弃用提示。
     *
     * `MCP-Protocol-Version` 每响应都回，客户端据此确认协商结果；请求里带了旧版
     * 会话头时附一条 `Warning: 299`（RFC 7234 的「持续性警告」），明说它已弃用、
     * 何时移除——不静默阉割旧客户端，也不假装它仍是协议前提。
     */
    private fun applyProtocolHeaders(exchange: HttpExchange, requestedVersion: String?) {
        exchange.responseHeaders.set(McpProtocol.PROTOCOL_HEADER, McpProtocol.negotiate(requestedVersion))
        val usingSession = !exchange.requestHeaders.getFirst(McpProtocol.SESSION_HEADER).isNullOrBlank()
        if (usingSession) {
            exchange.responseHeaders.add(
                "Warning",
                "299 - \"${McpProtocol.SESSION_HEADER} is deprecated since ${McpProtocol.LATEST}; " +
                    "removed after ${McpProtocol.COMPAT_UNTIL}\"",
            )
        }
    }

    /** 客户端是否明确接受 SSE（只有 Accept 里写了 text/event-stream 才开流，不猜） */
    private fun acceptsEventStream(exchange: HttpExchange): Boolean =
        (exchange.requestHeaders["Accept"]?.firstOrNull() ?: "").contains("text/event-stream")

    /**
     * JSON-RPC 方法分发 —— **传输无关**。委托给 [McpTaskDispatch]（T-094 拆分第 1 步）：
     * stdio 与 HTTP 两条传输共用同一份实现，能力面与语义逐字一致。
     */
    fun dispatch(method: String, params: JsonObject, requestId: String? = null): JsonObject =
        taskDispatch.dispatch(method, params, requestId)

    /** 413：请求体超过上限（D17 §8.3）。错误体只给上限与原因，不回显请求内容。 */
    private fun rejectTooLarge(exchange: HttpExchange) {
        respond(exchange, 413, buildJsonObject {
            put("error", Messages.get("error.request_too_large"))
            put("limitBytes", MAX_REQUEST_BODY_BYTES)
        })
    }

    /** 错误兜底用：响应头可能已发出（如客户端断开），失败就不再尝试第二次写 */
    private fun respondSafe(exchange: HttpExchange, status: Int, body: JsonObject) {
        runCatching { respond(exchange, status, body) }
    }
}
