package com.stragent.data

import com.stragent.core.config.Config
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * OAuth 2.1 资源服务器配置（T-056 / D17 G-15；RFC 9728 / 8414 / 8707）。
 *
 * @param issuer 授权服务器 issuer（JWT `iss` 必须逐字匹配）
 * @param audience 本资源的标识（JWT `aud` 必须包含它；RFC 8707 资源指示器）
 * @param jwksUri AS 的 JWKS 地址；null 时按 issuer 的 RFC 8414 / OIDC 元数据发现
 * @param requiredScopes 访问令牌必须覆盖的作用域（缺一个即 403 insufficient_scope）
 * @param clockSkewSeconds exp/nbf 允许的时钟偏移
 */
data class OAuthConfig(
    val issuer: String,
    val audience: String,
    val jwksUri: String? = null,
    val requiredScopes: List<String> = listOf("mcp"),
    val clockSkewSeconds: Long = 60,
)

/** 令牌校验结论（资源服务器的三种终态：可用 / 不可用 / 作用域不足） */
sealed interface OAuthVerdict {
    data class Authorized(val subject: String?, val scopes: Set<String>) : OAuthVerdict

    /** 401 invalid_token：格式/签名/签发方/受众/时效任一不满足 */
    data class Unauthorized(val reason: String) : OAuthVerdict

    /** 403 insufficient_scope：令牌有效但作用域不够（RFC 6750 §3.1） */
    data class InsufficientScope(val missing: List<String>) : OAuthVerdict
}

/**
 * MCP OAuth 2.1 **资源服务器**（T-056 / D17 G-15）。
 *
 * 定位：**只给远程绑定用**——`STRAGENT_HTTP_BIND=0.0.0.0` 暴露出去时，静态 Bearer
 * token 无过期、无作用域、无法吊销；OAuth 资源服务器把授权交给外部 AS，本服务只
 * 校验访问令牌。回环部署保持 [McpBridge] 的静态 token 简单模式
 * （D17：「不把 OAuth 强加给本地单用户」）。
 *
 * 校验口径（只接受 **RS256 JWT**）：
 *  - 签名：JWKS 取 RSA 公钥（`kid` 匹配；未知 kid 触发一次限频刷新）；
 *  - 算法：`none` / `HS256` / 其它一律拒绝（算法混淆攻击的经典入口）；
 *  - 声明：`iss` 精确、`aud` 包含本资源、`exp`/`nbf` 带时钟偏移；
 *  - 作用域：`scope` / `scp` 覆盖 [OAuthConfig.requiredScopes]。
 *
 * 发现顺序：`jwksUri` → `issuer/.well-known/oauth-authorization-server` →
 * `issuer/.well-known/openid-configuration`。JWKS 缓存 [JWKS_TTL_MS]；
 * 未知 kid 可提前刷新，但两次刷新间隔不小于 [MIN_REFRESH_INTERVAL_MS]
 * （避免被伪造 kid 打爆 AS）。
 *
 * 边界（诚实）：只做资源服务器——不签发令牌、不做授权服务器、不做动态客户端注册；
 * 不做 RFC 7662 introspection / 吊销查询，离线校验 JWT，吊销依赖 AS 侧短 TTL。
 */
class OAuthResourceServer(
    val config: OAuthConfig,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val fetcher: (String) -> String = ::httpGet,
) {

    private class CachedKeys(val keys: List<Pair<String?, PublicKey>>, val fetchedAt: Long)

    private val cache = AtomicReference<CachedKeys?>(null)
    private val lastRefresh = AtomicLong(Long.MIN_VALUE / 2)
    private val discoveredJwksUri = AtomicReference<String?>(null)

    /** RFC 9728 受保护资源元数据（公开文档，鉴权前可读） */
    fun protectedResourceMetadata(): JsonObject = buildJsonObject {
        put("resource", config.audience)
        put("authorization_servers", JsonArray(listOf(JsonPrimitive(config.issuer))))
        put("scopes_supported", JsonArray(config.requiredScopes.map { JsonPrimitive(it) }))
        // EventSource 不能设请求头，/events 额外接受 ?access_token=（见 McpBridge）
        put("bearer_methods_supported", JsonArray(listOf(JsonPrimitive("header"), JsonPrimitive("query"))))
    }

    /** 校验访问令牌（离线；JWKS 按缓存取） */
    fun verify(accessToken: String): OAuthVerdict {
        val jwt = parseJwt(accessToken) ?: return OAuthVerdict.Unauthorized("令牌格式非法（不是三段式 JWT）")
        val algorithm = (jwt.header["alg"] as? JsonPrimitive)?.contentOrNull
        if (algorithm != "RS256") {
            return OAuthVerdict.Unauthorized("不支持的签名算法：${algorithm ?: "缺失"}")
        }
        val kid = (jwt.header["kid"] as? JsonPrimitive)?.contentOrNull
        val key = resolveKey(kid)
            ?: return OAuthVerdict.Unauthorized("找不到匹配的签名公钥（kid=${kid ?: "未提供"}）")
        if (!signatureMatches(key, jwt)) return OAuthVerdict.Unauthorized("签名校验失败")

        val claims = jwt.claims
        // JWT 的 exp/nbf 是 **秒**（RFC 7519），本机时钟是毫秒——统一换算到秒再比较
        val nowSeconds = clock() / 1000
        val skewSeconds = config.clockSkewSeconds
        val expiresAt = claims.longOrNull("exp")
            ?: return OAuthVerdict.Unauthorized("缺少 exp 声明")
        if (nowSeconds - skewSeconds >= expiresAt) return OAuthVerdict.Unauthorized("令牌已过期")
        claims.longOrNull("nbf")?.let { notBefore ->
            if (nowSeconds + skewSeconds < notBefore) return OAuthVerdict.Unauthorized("令牌尚未生效（nbf）")
        }
        val issuer = (claims["iss"] as? JsonPrimitive)?.contentOrNull
        if (issuer != config.issuer) {
            return OAuthVerdict.Unauthorized("issuer 不匹配：${issuer ?: "缺失"}")
        }
        val audience = audienceOf(claims)
        if (config.audience !in audience) {
            return OAuthVerdict.Unauthorized("audience 不匹配：${audience.joinToString(",").ifEmpty { "缺失" }}")
        }
        val scopes = scopesOf(claims)
        val missing = config.requiredScopes.filter { it !in scopes }
        if (missing.isNotEmpty()) return OAuthVerdict.InsufficientScope(missing)
        return OAuthVerdict.Authorized(
            subject = (claims["sub"] as? JsonPrimitive)?.contentOrNull,
            scopes = scopes,
        )
    }

    // ---- JWT ----

    private class Jwt(
        val header: JsonObject,
        val claims: JsonObject,
        val signingInput: ByteArray,
        val signature: ByteArray,
    )

    private fun parseJwt(token: String): Jwt? {
        val parts = token.split('.')
        if (parts.size != 3) return null
        val header = decodeJsonObject(parts[0]) ?: return null
        val claims = decodeJsonObject(parts[1]) ?: return null
        val signature = runCatching { base64UrlDecode(parts[2]) }.getOrNull() ?: return null
        return Jwt(
            header = header,
            claims = claims,
            signingInput = "${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII),
            signature = signature,
        )
    }

    private fun decodeJsonObject(segment: String): JsonObject? = runCatching {
        Json.parseToJsonElement(base64UrlDecode(segment).decodeToString()).jsonObject
    }.getOrNull()

    private fun signatureMatches(key: PublicKey, jwt: Jwt): Boolean = runCatching {
        Signature.getInstance("SHA256withRSA").run {
            initVerify(key)
            update(jwt.signingInput)
            verify(jwt.signature)
        }
    }.getOrDefault(false)

    private fun JsonObject.longOrNull(name: String): Long? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private fun audienceOf(claims: JsonObject): List<String> = when (val aud = claims["aud"]) {
        is JsonPrimitive -> listOfNotNull(aud.contentOrNull)
        is JsonArray -> aud.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        else -> emptyList()
    }

    private fun scopesOf(claims: JsonObject): Set<String> {
        val tokens = mutableListOf<String>()
        (claims["scope"] as? JsonPrimitive)?.contentOrNull?.let { tokens += it.split(' ', ',') }
        when (val scp = claims["scp"]) {
            is JsonPrimitive -> scp.contentOrNull?.let { tokens += it.split(' ', ',') }
            is JsonArray -> scp.forEach { element ->
                (element as? JsonPrimitive)?.contentOrNull?.let { tokens += it.split(' ', ',') }
            }
            else -> Unit
        }
        return tokens.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    // ---- JWKS ----

    private fun resolveKey(kid: String?): PublicKey? =
        matchKey(currentKeys(forceRefresh = false), kid)
            ?: matchKey(currentKeys(forceRefresh = true), kid)

    private fun matchKey(keys: List<Pair<String?, PublicKey>>, kid: String?): PublicKey? =
        if (kid != null) keys.firstOrNull { it.first == kid }?.second
        else keys.singleOrNull()?.second

    private fun currentKeys(forceRefresh: Boolean): List<Pair<String?, PublicKey>> {
        val now = clock()
        val cached = cache.get()
        if (!forceRefresh && cached != null && now - cached.fetchedAt < JWKS_TTL_MS) return cached.keys
        // 限频：无论是 TTL 到期还是未知 kid，两次真实拉取至少隔 MIN_REFRESH_INTERVAL_MS
        if (now - lastRefresh.get() < MIN_REFRESH_INTERVAL_MS) return cached?.keys ?: emptyList()
        lastRefresh.set(now)
        val uri = jwksUriOrNull() ?: return cached?.keys ?: emptyList()
        val body = runCatching { fetcher(uri) }.getOrNull() ?: return cached?.keys ?: emptyList()
        val keys = parseKeys(body) ?: return cached?.keys ?: emptyList()
        cache.set(CachedKeys(keys, clock()))
        return keys
    }

    /** T-151：JWKS 地址可信校验（实例入口，委托 companion 单点，issuer 取自配置） */
    private fun ensureTrustedJwksUri(uri: String, source: String) =
        validateJwksUri(uri, config.issuer, source)


    /** `jwksUri` 配置优先；否则按 RFC 8414 / OIDC 发现（结果缓存） */
    private fun jwksUriOrNull(): String? {
        config.jwksUri?.takeIf { it.isNotBlank() }?.let { return it }
        discoveredJwksUri.get()?.let { return it }
        val base = config.issuer.trimEnd('/')
        for (url in listOf(
            "$base/.well-known/oauth-authorization-server",
            "$base/.well-known/openid-configuration",
        )) {
            val body = runCatching { fetcher(url) }.getOrNull() ?: continue
            val jwks = runCatching {
                (Json.parseToJsonElement(body).jsonObject["jwks_uri"] as? JsonPrimitive)?.contentOrNull
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
            // T-151：发现出来的 jwks_uri 必须过同一套可信校验——AS 元数据被配错/被攻击时，
            // 不能让它把 JWKS 拉取引到非 https 或跨源地址；校验不过 = 视为发现失败
            // （fail-closed：密钥为空 → 令牌全部拒绝），服务不因坏元数据崩溃。
            if (runCatching { ensureTrustedJwksUri(jwks, source = "发现链") }.isFailure) continue // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            discoveredJwksUri.set(jwks)
            return jwks
        }
        return null
    }

    private fun parseKeys(body: String): List<Pair<String?, PublicKey>>? = runCatching {
        val keys = Json.parseToJsonElement(body).jsonObject["keys"]?.jsonArray ?: return@runCatching null
        keys.mapNotNull { element ->
            val jwk = element.jsonObject
            if ((jwk["kty"] as? JsonPrimitive)?.contentOrNull != "RSA") return@mapNotNull null
            val use = (jwk["use"] as? JsonPrimitive)?.contentOrNull
            if (use != null && use != "sig") return@mapNotNull null
            val modulus = (jwk["n"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val exponent = (jwk["e"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val spec = RSAPublicKeySpec(
                BigInteger(1, base64UrlDecode(modulus)),
                BigInteger(1, base64UrlDecode(exponent)),
            )
            val key = KeyFactory.getInstance("RSA").generatePublic(spec)
            ((jwk["kid"] as? JsonPrimitive)?.contentOrNull) to key
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    companion object {
        const val ENV_ISSUER = "STRAGENT_OAUTH_ISSUER"
        const val ENV_AUDIENCE = "STRAGENT_OAUTH_AUDIENCE"
        const val ENV_JWKS_URI = "STRAGENT_OAUTH_JWKS_URI"
        const val ENV_SCOPES = "STRAGENT_OAUTH_SCOPES"

        /** JWKS 缓存时长 */
        const val JWKS_TTL_MS = 5 * 60 * 1000L

        /** 两次真实拉取的最小间隔（防伪造 kid 打爆 AS） */
        const val MIN_REFRESH_INTERVAL_MS = 15_000L

        /**
         * 按部署形态决定是否启用 OAuth（D17：只在远程绑定启用）：
         *  - 回环绑定 → null（静态 token 简单模式，不把 OAuth 强加给本地单用户）；
         *  - 远程绑定但没配 `STRAGENT_OAUTH_ISSUER` → **抛异常拒绝启动**（T-146）；
         *  - 远程绑定且配了 issuer → 资源服务器。
         *
         * ## 为什么远程绑定要 fail-closed（T-146）
         *
         * 此前是 **fail-open**：远程绑定未配 issuer 时静默回落静态 Bearer token，只在
         * 启动日志里打一条 warn。OWASP **CWE-636「Not Failing Securely」** 说的正是
         * 这种形态——安全配置缺失时**系统继续运行**。而静态 token **无过期、无 audience、
         * 无 scope**，一旦监听地址暴露到网络，等于把「一个永不过期的万能钥匙」挂在
         * 公网上：任何拿到它的人都能调用全部 53 条工具（含写文件、查数据库、跑沙箱）。
         * 那条 warn 日志救不了——没人盯启动日志。
         *
         * 现在的口径：**远程暴露必须显式声明身份源**。要么配 OAuth（推荐），要么
         * 明确改回回环绑定。**这是本轮唯一的破坏性变更**（BREAKING）——
         * 未配 issuer 的远程部署将**启动失败**，升级前请先看 D6 的「升级前检查」。
         */
        fun fromEnvironment(
            bindAddress: String,
            port: Int,
            env: (String) -> String? = { Config.get(it) },
        ): OAuthResourceServer? {
            if (McpBridge.isLoopbackBind(bindAddress)) return null
            val issuer = env(ENV_ISSUER)?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException(
                    "远程绑定（$bindAddress:$port）必须配置 ${ENV_ISSUER}：静态 Bearer token " + // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                        "无过期、无 audience、无 scope，不能用于远程暴露。请设置 ${ENV_ISSUER}" + // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                        "（可选 ${ENV_AUDIENCE} / ${ENV_SCOPES} / ${ENV_JWKS_URI}），" + // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                        "或改回回环绑定（STRAGENT_HTTP_BIND=127.0.0.1）。", // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                )
            // T-151：身份源可信校验——issuer 必须 https（回环豁免：本地 IdP 桩合法）；
            // 显式 jwksUri 必须 https + 与 issuer 同源（跨源 = 密钥可被第三方替换）。
            runCatching { URI(issuer) }.getOrElse {
                throw IllegalStateException("${ENV_ISSUER} 无法解析：$issuer") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            }.let { issuerUri ->
                val host = issuerUri.host ?: ""
                if (issuerUri.scheme?.lowercase() != "https" && !isLoopbackHost(host)) {
                    throw IllegalStateException(
                        "${ENV_ISSUER} 必须为 https（收到「${issuerUri.scheme}」：$issuer）。" + // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                            "回环地址豁免（本地 IdP 桩/本地 IdP 允许 http）；远程部署的令牌经明文传输等于不设防。", // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                    )
                }
            }
            val audience = env(ENV_AUDIENCE)?.trim()?.takeIf { it.isNotEmpty() }
                ?: "http://$bindAddress:$port/mcp"
            val scopes = env(ENV_SCOPES)?.split(',')
                ?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() } ?: listOf("mcp")
            val jwksUri = env(ENV_JWKS_URI)?.trim()?.takeIf { it.isNotEmpty() }
            jwksUri?.let { jw -> validateJwksUri(jw, issuer, source = "配置") } // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            return OAuthResourceServer(
                OAuthConfig(
                    issuer = issuer,
                    audience = audience,
                    jwksUri = jwksUri,
                    requiredScopes = scopes,
                ),
            )
        }

        /** 回环主机判定（issuer/JWKS 校验的豁免口径）：127.x、localhost、::1 */
        private fun isLoopbackHost(host: String): Boolean =
            host == "localhost" || host.startsWith("127.") || host == "::1" || host == "[::1]"

        /**
         * T-151：JWKS 地址可信校验单点——必须 https（回环豁免），且与 issuer 同源（主机名一致）。
         * 覆盖两条来源：`STRAGENT_OAUTH_JWKS_URI` 显式配置（fromEnvironment 启动期）与
         * AS 元数据发现链（拉取前逐次校验，D24 审查确认的关键洞）。
         */
        private fun validateJwksUri(uri: String, issuer: String, source: String) {
            val parsed = runCatching { URI(uri) }.getOrElse {
                throw IllegalArgumentException("JWKS 地址无法解析（$source）：$uri") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            }
            val host = parsed.host ?: ""
            if (parsed.scheme?.lowercase() != "https" && !isLoopbackHost(host)) {
                throw IllegalArgumentException(
                    "JWKS 地址必须是 https（$source 收到「${parsed.scheme}」：$uri）。" + // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                        "回环地址豁免（本地 IdP 桩/本地 IdP 允许 http）。", // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                )
            }
            val issuerHost = runCatching { URI(issuer).host }.getOrNull()?.lowercase() ?: ""
            if (host.lowercase() != issuerHost) {
                throw IllegalArgumentException(
                    "JWKS 地址必须与 issuer 同源（issuer=$issuerHost，JWKS=$host，$source）。" + // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                        "跨源 JWKS 意味着密钥可被第三方替换——拒绝。", // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                )
            }
        }

        private fun base64UrlDecode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)

        private fun httpGet(url: String): String {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() == 200) { "HTTP ${response.statusCode()}：$url" }
            return response.body()
        }
    }
}
