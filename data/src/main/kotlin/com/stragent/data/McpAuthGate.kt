package com.stragent.data

import com.stragent.core.i18n.Messages
import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MCP 桥的鉴权单元（T-094 拆分单元，自 [McpBridge] 迁出）：OAuth 2.1 校验 + 静态 Bearer +
 * RFC 9728 受保护资源元数据端点。/mcp、/ui、/events、/ui/data 四条路共用这一道闸。
 *
 * 元数据地址的兜底地址由宿主单元（桥）注入——服务未启动时用固定回环默认值。
 *
 * **行为零变化**：成员自 McpBridge 逐字迁出（T-094 第 3 步），单元边界记录见 docs/D7-tasks.md T-094。
 */
internal class McpAuthGate(
    private val oauth: OAuthResourceServer?,
    private val fallbackAddress: () -> String,
) {
    /**
     * 统一鉴权入口（T-056）：OAuth 开启时校验 JWT（401 challenge / 403 作用域），
     * 否则走静态 Bearer。返回 true = 放行；false = 已把拒绝响应写出去。
     *
     * 开启 OAuth 后**静态 token 不再被接受**——两种凭据并存会让攻击者用不过期的
     * 静态 token 绕开作用域与吊销。
     */
    /**
     * T-156（P2-13）：鉴权并返回**限流主体**——null = 拒绝；非 null = 按此键分桶。
     * 静态 token 模式只有一个凭据，主体恒为 "static-token"（分桶与全局等价）；
     * OAuth 模式取 JWT `sub`（多用户部署互不挤占），缺 sub 并入共享桶。
     */
    internal fun ensureAuthorized(exchange: HttpExchange, token: String, allowQueryToken: Boolean = false): String? {
        val oauth = oauth ?: return if (isAuthorized(exchange, token, allowQueryToken)) {
            "static-token"
        } else {
            respond(exchange, 401, buildJsonObject { put("error", Messages.get("error.unauthorized")) })
            null
        }
        // EventSource 不能设请求头：/events 额外接受 ?access_token=（RFC 6750 §2.3 的查询参数形态）
        val supplied = bearerOf(exchange)
            ?: if (allowQueryToken) queryParam(exchange, "access_token") else null
        val verdict = if (supplied == null) OAuthVerdict.Unauthorized("缺少访问令牌") else oauth.verify(supplied)  // lint:allow zh-first（用户面中文输出，迁自 McpBridge 原基线）
        return when (verdict) {
            is OAuthVerdict.Authorized -> verdict.subject ?: "oauth-no-sub"
            is OAuthVerdict.InsufficientScope -> {
                challenge(
                    exchange, 403, "insufficient_scope",
                    "作用域不足：需要 ${verdict.missing.joinToString(" ")}", verdict.missing,  // lint:allow zh-first（用户面中文输出，迁自 McpBridge 原基线）
                )
                null
            }
            is OAuthVerdict.Unauthorized -> {
                challenge(exchange, 401, "invalid_token", verdict.reason, null)
                null
            }
        }
    }

    /**
     * 401/403 的标准挑战：`WWW-Authenticate: Bearer resource_metadata="…"`（RFC 9728 §5.1
     * + RFC 6750），客户端据此发现 AS 并重新取令牌。
     *
     * 头里只放 ASCII 的 `error` / `scope`：HTTP 头是 ISO-8859-1（RFC 7230），中文原因
     * 放 JSON body 的 `error_description`——否则头值会被编码层截断/替换，连 `scope`
     * 都可能跟着丢（本地实测踩过）。
     */
    private fun challenge(
        exchange: HttpExchange,
        status: Int,
        error: String,
        description: String,
        scopes: List<String>?,
    ) {
        val header = buildString {
            append("Bearer resource_metadata=\"").append(protectedResourceMetadataUrl(exchange)).append('"')
            append(", error=\"").append(error).append('"')
            scopes?.let { append(", scope=\"").append(it.joinToString(" ")).append('"') }
        }
        exchange.responseHeaders.set("WWW-Authenticate", header)
        respond(
            exchange, status,
            buildJsonObject {
                put("error", error)
                put("error_description", description)
                scopes?.let { put("scope", it.joinToString(" ")) }
            },
        )
    }

    /** 元数据地址：按 RFC 9728 在资源路径前插入 well-known 段（/mcp → …/oauth-protected-resource/mcp） */
    private fun protectedResourceMetadataUrl(exchange: HttpExchange): String {
        val fallback = fallbackAddress()
        val host = exchange.requestHeaders.getFirst("Host")?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
        return "http://$host/.well-known/oauth-protected-resource/mcp"
    }

    /** RFC 9728 受保护资源元数据：公开文档（鉴权前可读），客户端据此发现 AS */
    internal fun handleProtectedResourceMetadata(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "GET") {
                respond(exchange, 405, buildJsonObject { put("error", Messages.get("error.get_only")) })
                return
            }
            exchange.responseHeaders.set("Cache-Control", "public, max-age=300")
            respond(exchange, 200, oauth?.protectedResourceMetadata() ?: JsonObject(emptyMap()))
        } finally {
            exchange.close()
        }
    }

    private fun bearerOf(exchange: HttpExchange): String? =
        exchange.requestHeaders.getFirst("Authorization")
            ?.takeIf { it.startsWith("Bearer ") }?.substring("Bearer ".length)

    /**
     * 静态 token 鉴权：`Authorization: Bearer <token>` 必须匹配；`/events` 允许 `?token=` 兜底
     * （EventSource 不能设请求头）。token 比较走常量时间（T-046）。
     */
    private fun isAuthorized(exchange: HttpExchange, token: String, allowQueryToken: Boolean = false): Boolean {
        // 常量时间比较（T-046）：错误 token 不因长度/前缀在耗时上可区分
        if (McpBridge.tokenMatches(token, bearerOf(exchange))) return true
        if (!allowQueryToken) return false
        return McpBridge.tokenMatches(token, queryParam(exchange, "token"))
    }
}
