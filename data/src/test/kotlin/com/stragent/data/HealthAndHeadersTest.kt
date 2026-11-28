package com.stragent.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-156 · /health 探针 + 安全响应头（D24 审查 P2-14）。
 *
 * /health 无需鉴权（compose healthcheck 可从 /dev/tcp 升级为真实 HTTP 探活）；
 * 全 JSON 响应带 nosniff + 禁 iframe；/ui 带 CSP（单文件仪表盘，内联例外）。
 */
class HealthAndHeadersTest {

    private val client: HttpClient = HttpClient.newBuilder().build()

    private fun start(): McpBridge {
        val prefs = FilePrefs(java.nio.file.Files.createTempDirectory("t156-hdr").toFile())
        val b = McpBridge(AbilityDatasets(), prefs, webUiHtml = "<html><body>probe</body></html>")
        b.start(0)
        return b
    }

    private fun get(url: String, token: String? = null): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create(url)).apply {
                token?.let { header("Authorization", "Bearer $it") }
            }.GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `health 探针——无需鉴权回 200`() {
        val b = start()
        try {
            val port = b.start(0)
            val r = get("http://127.0.0.1:$port/health")
            assertEquals(200, r.statusCode())
            assertTrue(Json.parseToJsonElement(r.body()).jsonObject.containsKey("ok"))
        } finally {
            b.stop()
        }
    }

    @Test
    fun `安全响应头——JSON 与 401 响应都带 nosniff 与 DENY`() {
        val b = start()
        try {
            val port = b.start(0)
            // 未鉴权 401 也要带头（错误路径不豁免）
            val r1 = get("http://127.0.0.1:$port/mcp")
            assertEquals(401, r1.statusCode())
            assertEquals("nosniff", r1.headers().firstValue("X-Content-Type-Options").orElse(""))
            assertEquals("DENY", r1.headers().firstValue("X-Frame-Options").orElse(""))
            // /health 同样带头
            val r2 = get("http://127.0.0.1:$port/health")
            assertEquals("nosniff", r2.headers().firstValue("X-Content-Type-Options").orElse(""))
        } finally {
            b.stop()
        }
    }

    @Test
    fun `ui 带 CSP——内联例外不放开外链`() {
        val b = start()
        try {
            val port = b.start(0)
            val r = get("http://127.0.0.1:$port/ui")
            assertEquals(200, r.statusCode())
            val csp = r.headers().firstValue("Content-Security-Policy").orElse("")
            assertTrue(csp.contains("default-src 'none'"), "CSP 默认拒绝：$csp")
        } finally {
            b.stop()
        }
    }
}
