package com.stragent.data

/**
 * MCP 协议版本与生命周期口径（D17 G-15 / T-056）。
 *
 * 本项目声明的目标版本是 **2026-07-28**：无状态核心（请求不再依赖 `initialize`
 * 与 `Mcp-Session-Id`）、`outputSchema`/`structuredContent`、tool annotations、
 * Tasks 扩展、OAuth 2.1 资源服务器、elicitation。
 *
 * 三条纪律：
 *  1. **版本协商只回自己支持的版本**。客户端请求不认识的字面量时，回最新版并
 *     在结果里列 `supported`——不假装支持，也不把连接直接掐掉。
 *  2. **旧版保留一整个弃用窗口**（12 个月，至 [COMPAT_UNTIL]）。旧客户端仍可
 *     用 `initialize` + `Mcp-Session-Id`，但响应会带弃用提示（`Warning` 头与
 *     `initialize._meta.deprecated`），且 `notifications/initialized` 不再必需。
 *  3. **无状态是默认语义**：不带任何版本头/会话头也能直接 `tools/list`、
 *     `tools/call`、`tasks/…`。会话头只作为「同一会话的数据集指针」的路由提示，
 *     不再是协议前提。
 *
 * 边界（诚实）：本文件实现的是**本项目声明的 2026-07-28 档案**（无状态核心 +
 * Tasks + OAuth RS + elicitation + 结构化输出），本地一致性测试覆盖的是这些
 * MUST 项，不是官方测试套件（环境无官方套件可跑，见 D7 T-056 边界）。
 */
object McpProtocol {

    /** 本服务声明的最新协议版本 */
    const val LATEST = "2026-07-28"

    /** 仍可协商的版本（新→旧）。旧版在 [COMPAT_UNTIL] 前继续可用 */
    val SUPPORTED: List<String> = listOf(LATEST, "2025-06-18", "2025-03-26")

    /** 12 个月弃用窗口的终点：此后旧版协商可以直接失败（当前只是提示） */
    const val COMPAT_UNTIL = "2027-07-28"

    /** HTTP 请求/响应头：每请求携带的协议版本（无状态核心下取代 initialize 协商） */
    const val PROTOCOL_HEADER = "MCP-Protocol-Version"

    /** 旧版会话头（弃用中，仅用于「会话数据集指针」路由，不再是协议前提） */
    const val SESSION_HEADER = "Mcp-Session-Id"

    /**
     * Streamable HTTP 路由头（2026-07-28 起强制）：请求体字段镜像进 HTTP 头，
     * 让网关/负载均衡不解析请求体就能路由（spec: Standard Request Headers）。
     * 服务端 MUST 校验头↔体一致（[validateRoutingHeaders]），不一致回 -32020。
     */
    const val METHOD_HEADER = "Mcp-Method"
    const val NAME_HEADER = "Mcp-Name"

    /** 路由头的 Base64 哨兵编码：非 ASCII/含哨兵形态的值必须按此格式编码传输 */
    const val HEADER_SENTINEL_PREFIX = "=?base64?"
    const val HEADER_SENTINEL_SUFFIX = "?="

    /** 头镜像不匹配（-32020）与不支持的协议版本（-32022）：2026-07-28 新增的协议错误码 */
    const val ERROR_HEADER_MISMATCH = -32020
    const val ERROR_UNSUPPORTED_PROTOCOL_VERSION = -32022

    /**
     * 路由头值解码：`=?base64?…?=` 哨兵形态按 Base64(UTF-8) 解码，原样返回。
     * 哨兵冲突规则（spec Value Encoding）：与哨兵形态同形的普通 ASCII 值会被客户端
     * 编码传输，所以头里出现哨兵形态一律解码——正文里不会再有裸的哨兵形态值。
     * 非法 Base64 抛 [IllegalArgumentException]（调用方包成 -32020）。
     */
    fun decodeHeaderValue(raw: String): String {
        val value = raw.trim()
        if (!value.startsWith(HEADER_SENTINEL_PREFIX) || !value.endsWith(HEADER_SENTINEL_SUFFIX)) return value
        val payload = value.removePrefix(HEADER_SENTINEL_PREFIX).removeSuffix(HEADER_SENTINEL_SUFFIX)
        return java.util.Base64.getDecoder().decode(payload).toString(Charsets.UTF_8)
    }

    /**
     * Streamable HTTP 路由头↔请求体一致性校验（2026-07-28 Server Validation）。
     *
     * 只对「现代请求」强制：`MCP-Protocol-Version` 头声明 [LATEST] 且带 method 的帧。
     * 遗留客户端（头缺失或旧版本）不受约束——它们的流程里不存在这些头（spec 的 MAY
     * 条款：缺头可按 2025-03-26 处理），拒了等于直接断供既有客户端。
     *
     * 校验项（失败一律 [McpHeaderMismatchException]，HTTP 侧包 400 + -32020）：
     *  - `MCP-Protocol-Version` 头 ↔ `params._meta` 的 protocolVersion 一致（声明了才比）；
     *  - `Mcp-Method` 必须存在且等于请求体 method；
     *  - `tools/call` 的 `Mcp-Name` 必须存在且等于 `params.name`（哨兵编码先解码再比）。
     *    resources/read 与 prompts/get 的同名约束随这两个方法实现时一并加上（本服务未实现）。
     */
    fun validateRoutingHeaders(
        headerOf: (String) -> String?,
        method: String,
        params: kotlinx.serialization.json.JsonObject,
    ) {
        val declared = headerOf(PROTOCOL_HEADER)?.trim().orEmpty()
        if (declared != LATEST) return
        val meta = params["_meta"] as? kotlinx.serialization.json.JsonObject
        val metaVersion = (meta?.get("io.modelcontextprotocol/protocolVersion") as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it.isString }?.content
        if (metaVersion != null && metaVersion != LATEST) {
            throw McpHeaderMismatchException(
                "MCP-Protocol-Version 头（$LATEST）与 _meta protocolVersion（$metaVersion）不一致",  // lint:allow zh-first（协议错误文案，用户面中文输出）
            )
        }
        val methodHeader = headerOf(METHOD_HEADER)?.trim()
            ?: throw McpHeaderMismatchException("缺少 Mcp-Method 头（2026-07-28 起对所有请求强制）")  // lint:allow zh-first（协议错误文案，用户面中文输出）
        if (methodHeader != method) {
            throw McpHeaderMismatchException("Mcp-Method 头（$methodHeader）与请求体 method（$method）不一致")  // lint:allow zh-first（协议错误文案，用户面中文输出）
        }
        if (method == "tools/call") {
            val bodyName = (params["name"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it.isString }?.content.orEmpty()
            val nameHeader = headerOf(NAME_HEADER)
                ?: throw McpHeaderMismatchException("缺少 Mcp-Name 头（tools/call 必带）")  // lint:allow zh-first（协议错误文案，用户面中文输出）
            val decoded = try {
                decodeHeaderValue(nameHeader)
            } catch (e: IllegalArgumentException) {
                throw McpHeaderMismatchException("Mcp-Name 头不是合法的 Base64 哨兵编码：${e.message}")  // lint:allow zh-first（协议错误文案，用户面中文输出）
            }
            if (decoded != bodyName) {
                throw McpHeaderMismatchException(
                    "Mcp-Name 头（$decoded）与请求体 name（$bodyName）不一致",  // lint:allow zh-first（协议错误文案，用户面中文输出）
                )
            }
        }
    }

    /**
     * 协商：请求版本受支持就原样回；缺失/未知一律回 [LATEST]。
     * 返回的永远是 [SUPPORTED] 里的值——不会把不认识的字面量回显成「已支持」。
     */
    fun negotiate(requested: String?): String {
        val value = requested?.trim().orEmpty()
        return if (value in SUPPORTED) value else LATEST
    }

    /** 请求头里的版本是否可接受（缺失 = 可接受，按最新版处理） */
    fun isSupported(requested: String?): Boolean {
        val value = requested?.trim().orEmpty()
        return value.isEmpty() || value in SUPPORTED
    }

    /** 旧版协商结果的弃用提示；最新版返回 null（HTTP 用 `Warning: 299` 头） */
    fun deprecationNotice(version: String): String? =
        if (version == LATEST) null
        else "protocol $version is deprecated; unsupported after $COMPAT_UNTIL"

    /** `initialize` 是否已被标记弃用（无状态核心下它只是兼容壳） */
    const val INITIALIZE_DEPRECATED = true
}

/**
 * 未知 JSON-RPC 方法（-32601）。
 *
 * 单独类型是为了让两条传输映射出同一个错误码——把「方法名拼错」当内部错误
 * （-32603）回，会让客户端误以为是服务端故障而重试。
 */
class McpMethodNotFoundException(val method: String) :
    RuntimeException("未知方法：$method")

/**
 * 参数非法（-32602）：工具名不存在、必填参数缺失、结构不对。
 *
 * 与「工具执行失败」区分：客户端改参数就能好，不是服务端故障。
 * 继承 [IllegalArgumentException]：沿用「可读参数错误」的既有约定（工具层同源）。
 */
class McpInvalidParamsException(message: String) : IllegalArgumentException(message)

/**
 * HTTP 路由头与请求体不一致 / 必需路由头缺失（-32020 HeaderMismatch，2026-07-28）。
 *
 * spec 定位：这是**传输层路由信任问题**（网关按头路由、服务端按体执行——两个
 * 「真值源」不一致可被用来绕过网关策略），所以是独立于 -32601/-32602 的协议错误。
 * HTTP 侧 MUST 回 400 状态码 + JSON-RPC 错误帧；stdio 无头概念，此异常只由 HTTP 处理。
 */
class McpHeaderMismatchException(message: String) : RuntimeException(message)

/**
 * 请求声明了本服务不支持的协议版本（-32022 UnsupportedProtocolVersionError）。
 *
 * 目前版本闸在 HTTP 侧直接判（[McpProtocol.isSupported]），这个异常类型暂无抛出点；
 * 保留独立类型是为了与 -32020/-32021 同构，给 MRTR 迁移轮的 elicitation 能力闸
 * （-32021 MissingRequiredClientCapability）留同款接法。
 */
class McpUnsupportedProtocolVersionException(
    val requested: String?,
    val supported: List<String>,
) : RuntimeException("unsupported protocol version: ${requested ?: "(缺失)"}")  // lint:allow zh-first（兜底文案，用户面中文输出）
