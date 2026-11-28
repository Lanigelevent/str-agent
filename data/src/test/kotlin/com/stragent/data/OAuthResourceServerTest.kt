package com.stragent.data

import com.stragent.core.ports.AgentTool
import com.stragent.core.ports.PrefsStore
import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * T-056 / D17 G-15 · OAuth 2.1 资源服务器（RFC 9728/8414/8707/6750）。
 *
 * 证据结构：
 *  1. **真密码学**：测试内起一个桩 AS（JWKS + RFC 8414 元数据），用 RSA-2048
 *     私钥签真 JWT，服务端走真签名校验——不是把校验打成桩；
 *  2. **拒绝矩阵**：过期 / nbf / iss / aud / alg=none / alg=HS256 / 篡改签名 /
 *     整段垃圾，全部 401 而不是放行；
 *  3. **作用域**：有效令牌缺 scope → 403 insufficient_scope（RFC 6750 §3.1）；
 *  4. **部署策略**：回环不启用、远程未配 issuer 回退静态 token、远程配置后启用；
 *  5. **HTTP 语义**：401 带 `WWW-Authenticate: Bearer resource_metadata=…`，
 *     RFC 9728 元数据端点公开可读，开启 OAuth 后静态 token 不再被接受。
 *
 * 边界（诚实）：这是本地桩 AS 的端到端测试，不是对某个真实 IdP（Keycloak/Okta/
 * Auth0）的互操作测试；真实 AS 的元数据细节差异未覆盖。
 */
class OAuthResourceServerTest {

    private companion object {
        const val RESOURCE = "http://127.0.0.1:7787/mcp"
    }

    private class MapPrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private class ProbeTool : AgentTool {
        override val name = "oauth_probe"
        override val description = "OAuth 测试用探针"
        override val parametersSchema = buildJsonObject { put("type", "object") }
        override fun invoke(args: JsonObject): JsonObject = buildJsonObject { put("ok", true) }
    }

    /** 桩授权服务器：RFC 8414 元数据 + JWKS（真 RSA 公钥） */
    private class StubAuthorizationServer {
        // 测试桩签名用密钥对：强位长，每次测试类生成一次（约 1s，可接受）
        val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(4096) }.generateKeyPair()
        val jwksHits = AtomicInteger(0)
        val discoveryHits = AtomicInteger(0)
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        val issuer: String get() = "http://127.0.0.1:${server.address.port}"

        init {
            server.createContext("/.well-known/oauth-authorization-server") { exchange ->
                discoveryHits.incrementAndGet()
                respond(exchange, """{"issuer":"$issuer","jwks_uri":"$issuer/jwks"}""")
            }
            server.createContext("/jwks") { exchange ->
                jwksHits.incrementAndGet()
                respond(exchange, jwksJson())
            }
            server.start()
        }

        fun stop() = server.stop(0)

        private fun jwksJson(): String {
            val publicKey = keyPair.public as RSAPublicKey
            val modulus = b64(unsigned(publicKey.modulus.toByteArray()))
            val exponent = b64(unsigned(publicKey.publicExponent.toByteArray()))
            return """{"keys":[{"kty":"RSA","kid":"k1","use":"sig","alg":"RS256","n":"$modulus","e":"$exponent"}]}"""
        }

        private fun respond(exchange: com.sun.net.httpserver.HttpExchange, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
    }

    private fun resourceServer(stub: StubAuthorizationServer, clock: () -> Long = { System.currentTimeMillis() }) =
        OAuthResourceServer(
            OAuthConfig(issuer = stub.issuer, audience = RESOURCE, requiredScopes = listOf("mcp")),
            clock = clock,
            fetcher = { url -> URL(url).readText() },
        )

    private fun claims(
        issuer: String,
        audience: String? = RESOURCE,
        scope: String? = "mcp",
        expOffsetSeconds: Long = 300,
        nbfOffsetSeconds: Long = -10,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): JsonObject = buildJsonObject {
        put("iss", issuer)
        audience?.let { put("aud", it) }
        put("sub", "user-1")
        put("iat", nowSeconds - 10)
        put("exp", nowSeconds + expOffsetSeconds)
        put("nbf", nowSeconds + nbfOffsetSeconds)
        scope?.let { put("scope", it) }
    }

    private fun mint(
        privateKey: PrivateKey,
        claims: JsonObject,
        kid: String? = "k1",
        alg: String = "RS256",
    ): String {
        val header = buildJsonObject {
            put("alg", alg)
            put("typ", "JWT")
            kid?.let { put("kid", it) }
        }
        val signingInput = b64(header.toString().toByteArray()) + "." + b64(claims.toString().toByteArray())
        if (alg == "none") return "$signingInput."
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(signingInput.toByteArray(Charsets.US_ASCII))
            sign()
        }
        return "$signingInput.${b64(signature)}"
    }

    // ---- HTTP helpers ----

    private class HttpResult(val status: Int, val text: String, val headers: Map<String, List<String>>)

    private fun request(
        port: Int,
        method: String,
        path: String,
        token: String? = null,
        body: String? = null,
    ): HttpResult {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        token?.let { conn.setRequestProperty("Authorization", it) }
        if (body != null) {
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val status = conn.responseCode
        val stream = if (status >= 400) conn.errorStream else conn.inputStream
        val text = stream?.readBytes()?.decodeToString() ?: ""
        val headers = conn.headerFields.filterKeys { it != null }
        conn.disconnect()
        return HttpResult(status, text, headers)
    }

    @Test
    fun `元数据——RFC 9728 必需字段齐全`() {
        val stub = StubAuthorizationServer()
        try {
            val metadata = resourceServer(stub).protectedResourceMetadata()
            assertEquals(RESOURCE, metadata["resource"]!!.jsonPrimitive.content)
            assertTrue(
                metadata["authorization_servers"].toString().contains(stub.issuer),
                "必须指向授权服务器：$metadata",
            )
            assertTrue(metadata["scopes_supported"].toString().contains("mcp"), "必须公布作用域：$metadata")
            assertTrue(
                metadata["bearer_methods_supported"].toString().contains("header"),
                "必须声明 Bearer 用法：$metadata",
            )
        } finally {
            stub.stop()
        }
    }

    @Test
    fun `合法令牌通过——带回 subject 与作用域`() {
        val stub = StubAuthorizationServer()
        try {
            val server = resourceServer(stub)
            val token = mint(stub.keyPair.private, claims(stub.issuer))
            val verdict = server.verify(token)
            assertTrue(verdict is OAuthVerdict.Authorized, "合法令牌应通过：$verdict")
            assertEquals("user-1", (verdict as OAuthVerdict.Authorized).subject)
            assertTrue("mcp" in verdict.scopes)
        } finally {
            stub.stop()
        }
    }

    @Test
    fun `拒绝矩阵——过期 nbf iss aud 算法 篡改 垃圾格式全部 401`() {
        val stub = StubAuthorizationServer()
        try {
            val server = resourceServer(stub)
            val good = mint(stub.keyPair.private, claims(stub.issuer))

            val cases = linkedMapOf(
                // 120 秒前过期，时钟偏移 60 秒仍算过期
                "过期" to mint(stub.keyPair.private, claims(stub.issuer, expOffsetSeconds = -120)),
                "尚未生效" to mint(stub.keyPair.private, claims(stub.issuer, nbfOffsetSeconds = 120)),
                "issuer 不匹配" to mint(stub.keyPair.private, claims("http://evil.example")),
                "audience 不匹配" to mint(stub.keyPair.private, claims(stub.issuer, audience = "http://other/mcp")),
                "缺少 aud" to mint(stub.keyPair.private, claims(stub.issuer, audience = null)),
                "缺少 exp" to mint(
                    stub.keyPair.private,
                    buildJsonObject { put("iss", stub.issuer); put("aud", RESOURCE); put("scope", "mcp") },
                ),
                "alg=none" to mint(stub.keyPair.private, claims(stub.issuer), alg = "none"),
                "alg=HS256" to mint(stub.keyPair.private, claims(stub.issuer), alg = "HS256"),
                "未知 kid" to mint(stub.keyPair.private, claims(stub.issuer), kid = "k9"),
                "篡改签名" to good.dropLast(2) + if (good.last() == 'A') "BB" else "AA",
                "垃圾字符串" to "not-a-jwt",
            )
            for ((label, token) in cases) {
                val verdict = server.verify(token)
                assertTrue(verdict is OAuthVerdict.Unauthorized, "$label 必须拒绝：$verdict")
            }
        } finally {
            stub.stop()
        }
    }

    @Test
    fun `作用域——缺 mcp 报 insufficient_scope，scp 数组也认`() {
        val stub = StubAuthorizationServer()
        try {
            val server = resourceServer(stub)
            val missing = server.verify(mint(stub.keyPair.private, claims(stub.issuer, scope = "read")))
            assertTrue(missing is OAuthVerdict.InsufficientScope, "缺作用域应 403：$missing")
            assertEquals(listOf("mcp"), (missing as OAuthVerdict.InsufficientScope).missing)

            val extra = server.verify(mint(stub.keyPair.private, claims(stub.issuer, scope = "read mcp")))
            assertTrue(extra is OAuthVerdict.Authorized, "作用域覆盖即通过：$extra")

            val scpArray = buildJsonObject {
                put("iss", stub.issuer)
                put("aud", RESOURCE)
                put("exp", System.currentTimeMillis() / 1000 + 300)
                put(
                    "scp",
                    kotlinx.serialization.json.JsonArray(
                        listOf(kotlinx.serialization.json.JsonPrimitive("mcp")),
                    ),
                )
            }
            assertTrue(
                server.verify(mint(stub.keyPair.private, scpArray)) is OAuthVerdict.Authorized,
                "scp 数组形式应被接受",
            )
        } finally {
            stub.stop()
        }
    }

    @Test
    fun `JWKS 缓存——重复校验不重复拉取；未知 kid 在限频窗口后可触发一次刷新`() {
        val stub = StubAuthorizationServer()
        try {
            val now = AtomicLong(1_700_000_000_000)
            val server = resourceServer(stub) { now.get() }
            val valid = mint(stub.keyPair.private, claims(stub.issuer, nowSeconds = now.get() / 1000))

            assertTrue(server.verify(valid) is OAuthVerdict.Authorized)
            assertEquals(1, stub.jwksHits.get(), "首次校验应拉一次 JWKS")
            assertTrue(server.verify(valid) is OAuthVerdict.Authorized)
            assertEquals(1, stub.jwksHits.get(), "缓存期内不应重复拉取")
            assertEquals(1, stub.discoveryHits.get(), "AS 元数据发现也应缓存")

            val unknownKid = mint(
                stub.keyPair.private,
                claims(stub.issuer, nowSeconds = now.get() / 1000),
                kid = "k9",
            )
            assertTrue(server.verify(unknownKid) is OAuthVerdict.Unauthorized)
            assertEquals(1, stub.jwksHits.get(), "限频窗口内未知 kid 不得打爆 AS")

            now.addAndGet(OAuthResourceServer.MIN_REFRESH_INTERVAL_MS + 1_000)
            assertTrue(server.verify(unknownKid) is OAuthVerdict.Unauthorized)
            assertEquals(2, stub.jwksHits.get(), "过了限频窗口应允许一次刷新")
        } finally {
            stub.stop()
        }
    }

    @Test
    fun `部署策略——回环不启用、远程未配 issuer 拒绝启动、远程配置后启用`() {
        fun env(vararg pairs: Pair<String, String>): (String) -> String? {
            val map = pairs.toMap()
            return { map[it] }
        }

        assertNull(
            OAuthResourceServer.fromEnvironment(
                "127.0.0.1", 7787,
                env(OAuthResourceServer.ENV_ISSUER to "https://stub.example"),
            ),
            "回环部署保持静态 token 简单模式",
        )
        assertNull(
            OAuthResourceServer.fromEnvironment("127.0.0.1", 7787, env()),
            "回环部署未配 issuer 也不该启用 OAuth",
        )
        // T-146（BREAKING）：远程但未配 issuer → **拒绝启动**（fail-closed）。
        // 此前是静默回落静态 Bearer token（OWASP CWE-636「Not Failing Securely」）——
        // 静态 token 无过期/无 audience/无 scope，挂在公网上等于永不过期的万能钥匙。
        val refused = assertFailsWith<IllegalStateException> {
            OAuthResourceServer.fromEnvironment("0.0.0.0", 7787, env())
        }
        assertTrue(
            refused.message!!.contains(OAuthResourceServer.ENV_ISSUER),
            "错误信息要写明该配哪个环境变量：${refused.message}",
        )
        assertTrue(
            refused.message!!.contains("127.0.0.1"),
            "错误信息要给出第二条出路（改回回环绑定）：${refused.message}",
        )
        val enabled = OAuthResourceServer.fromEnvironment(
            "0.0.0.0", 7787,
            env(
                OAuthResourceServer.ENV_ISSUER to "https://stub.example",
                OAuthResourceServer.ENV_AUDIENCE to "https://agent.example/mcp",
                OAuthResourceServer.ENV_SCOPES to "mcp, read",
            ),
        )
        assertNotNull(enabled, "远程 + issuer 应启用")
        assertEquals("https://stub.example", enabled.config.issuer)
        assertEquals("https://agent.example/mcp", enabled.config.audience)
        assertEquals(listOf("mcp", "read"), enabled.config.requiredScopes)
    }

    @Test
    fun `HTTP——401 challenge 指向 RFC 9728 元数据，开启 OAuth 后静态 token 失效`() {
        val stub = StubAuthorizationServer()
        val prefs = MapPrefs()
        val bridge = McpBridge(
            AbilityDatasets(), prefs,
            extraTools = listOf(ProbeTool()),
            oauth = resourceServer(stub),
            // 注册 /events 需要 Web UI 开关（既有装配约束）
            webUiHtml = "<html><body>oauth test</body></html>",
        )
        val port = bridge.start(0)
        val staticToken = McpBridge.currentToken(prefs)
        try {
            // ① 元数据端点公开可读
            val metadata = request(port, "GET", "/.well-known/oauth-protected-resource/mcp")
            assertEquals(200, metadata.status, metadata.text)
            assertEquals(RESOURCE, Json.parseToJsonElement(metadata.text).jsonObject["resource"]!!.jsonPrimitive.content)

            // ② 无令牌 → 401，challenge 带 resource_metadata
            val anonymous = request(port, "POST", "/mcp", body = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
            assertEquals(401, anonymous.status)
            val challenge = anonymous.headers.entries
                .firstOrNull { it.key.equals("WWW-Authenticate", ignoreCase = true) }
                ?.value?.joinToString(" ")
            assertNotNull(challenge, "401 必须带 WWW-Authenticate：${anonymous.headers}")
            assertTrue(challenge.contains("resource_metadata=\""), "challenge 要指向元数据：$challenge")
            assertTrue(challenge.contains("invalid_token"), "challenge 要说明 invalid_token：$challenge")

            // ③ 静态 token 不再被接受（否则绕开过期与作用域）
            val staticAttempt = request(port, "POST", "/mcp", "Bearer $staticToken", """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
            assertEquals(401, staticAttempt.status, "开启 OAuth 后静态 token 必须失效")

            // ④ 有效 JWT 通过
            val jwt = mint(stub.keyPair.private, claims(stub.issuer))
            val authorized = request(port, "POST", "/mcp", "Bearer $jwt", """{"jsonrpc":"2.0","id":3,"method":"tools/list"}""")
            assertEquals(200, authorized.status, authorized.text)
            assertTrue(authorized.text.contains("oauth_probe"), "应能列工具：${authorized.text}")

            // ⑤ 有效但缺作用域 → 403 insufficient_scope
            val readOnly = mint(stub.keyPair.private, claims(stub.issuer, scope = "read"))
            val forbidden = request(port, "POST", "/mcp", "Bearer $readOnly", """{"jsonrpc":"2.0","id":4,"method":"tools/list"}""")
            assertEquals(403, forbidden.status, forbidden.text)
            val forbidChallenge = forbidden.headers.entries
                .firstOrNull { it.key.equals("WWW-Authenticate", ignoreCase = true) }
                ?.value?.joinToString(" ")
            assertTrue(forbidChallenge!!.contains("insufficient_scope"), "$forbidChallenge")
            assertTrue(forbidChallenge.contains("scope=\"mcp\""), "$forbidChallenge")

            // ⑥ /events 无令牌同样 401（不因 SSE 而放宽）
            val events = request(port, "GET", "/events")
            assertEquals(401, events.status)
        } finally {
            bridge.stop()
            stub.stop()
        }
    }

    @Test
    fun `负控——非回环 http issuer 启动即拒（T-151）`() {
        val e = assertFailsWith<IllegalStateException> {
            OAuthResourceServer.fromEnvironment("0.0.0.0", 7787) { name ->
                if (name == OAuthResourceServer.ENV_ISSUER) "http://idp.example.com" else null
            }
        }
        assertTrue(
            e.message!!.contains("https"),
            "错误信息要写明 https 要求：${e.message}",
        )
    }

    @Test
    fun `负控——回环 issuer 的 http 豁免不误伤（T-151）`() {
        // 本地 IdP 桩（127.0.0.1 + http）合法——既有回环桩用例依赖此豁免
        val server = OAuthResourceServer.fromEnvironment("0.0.0.0", 7787) { name ->
            if (name == OAuthResourceServer.ENV_ISSUER) "http://127.0.0.1:8080" else null
        }
        assertTrue(server != null, "回环 http issuer 应放行")
    }

    @Test
    fun `负控——跨源与非 https 的 JWKS 配置被拒（T-151）`() {
        // 非回环 http jwksUri：https 校验拒绝
        val e1 = assertFailsWith<IllegalArgumentException> {
            OAuthResourceServer.fromEnvironment("0.0.0.0", 7787) { name ->
                when (name) {
                    OAuthResourceServer.ENV_ISSUER -> "https://idp.example.com"
                    OAuthResourceServer.ENV_JWKS_URI -> "http://keys.example.com/jwks"
                    else -> null
                }
            }
        }
        assertTrue(e1.message!!.contains("https"), e1.message)
        // 跨源 https jwksUri：同源校验拒绝
        val e2 = assertFailsWith<IllegalArgumentException> {
            OAuthResourceServer.fromEnvironment("0.0.0.0", 7787) { name ->
                when (name) {
                    OAuthResourceServer.ENV_ISSUER -> "https://idp.example.com"
      OAuthResourceServer.ENV_JWKS_URI -> "https://keys.example.net/jwks"
                    else -> null
                }
            }
        }
        assertTrue(e2.message!!.contains("同源"), e2.message)
    }
}

private fun unsigned(bytes: ByteArray): ByteArray =
    bytes.dropWhile { it == 0.toByte() }.toByteArray().takeIf { it.isNotEmpty() } ?: byteArrayOf(0)

private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
