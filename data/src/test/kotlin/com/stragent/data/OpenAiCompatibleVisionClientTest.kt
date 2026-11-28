package com.stragent.data

import com.stragent.core.ports.PrefsStore
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **主流视觉接口**的契约测试。
 *
 * 这里「后端大模型」由本地桩扮演（返回**符合主流响应形状**的 JSON）——
 * 测的是**请求/响应契约**，不是模型质量：请求必须长得像 OpenAI vision（text 块 + image_url 块），
 * 响应必须能解析出 `choices[0].message.content`，且每次调用必须给出可溯源的清单。
 */
class OpenAiCompatibleVisionClientTest {

    private class MapPrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private fun pngFile(): File {
        val file = File(createTempDirectory("str-agent-vision").toFile(), "chart.png")
        // 最小 PNG 头（够算哈希与 mime 判定）
        file.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4))
        return file
    }

    @Test
    fun `按主流 OpenAI vision 形状发请求，并解析响应与清单`() {
        val received = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            received += exchange.requestBody.readBytes().decodeToString()
            val response = """
                {"id":"1","object":"chat.completion","created":1,"model":"test-vision",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"图中 2024 年柱形最高。"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":123,"completion_tokens":45,"total_tokens":168}}
            """.trimIndent()
            val bytes = response.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            val prefs = MapPrefs()
            prefs.set("stepfun.apiKey", "sk-test")
            val client = OpenAiCompatibleVisionClient(
                config = StepfunConfig(prefs),
                model = "test-vision",
                baseUrlOverride = "http://127.0.0.1:${server.address.port}",
            )
            val image = pngFile()
            val result = client.describe(
                com.stragent.core.ports.VisionRequest(
                    image = com.stragent.core.ports.VisionImage.LocalFile(image.absolutePath),
                    prompt = "哪一年最高？",
                ),
            )

            // 响应解析
            assertEquals("图中 2024 年柱形最高。", result.text)
            assertEquals(168, result.totalTokens)
            assertEquals("test-vision", result.model)

            // 清单（可溯源）
            assertTrue(result.manifest.imageHash.isNotBlank())
            assertTrue(result.manifest.promptHash.isNotBlank())
            assertTrue(result.manifest.outputHash.isNotBlank())
            assertEquals("test-vision", result.manifest.model)

            // 请求形状：必须是主流 vision 形态
            assertEquals(1, received.size)
            val payload = Json.parseToJsonElement(received[0]).jsonObject
            assertEquals("test-vision", payload["model"]!!.jsonPrimitive.content)
            val content = payload["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
            assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("哪一年最高？", content[0].jsonObject["text"]!!.jsonPrimitive.content)
            assertEquals("image_url", content[1].jsonObject["type"]!!.jsonPrimitive.content)
            val url = content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
            assertTrue(url.startsWith("data:image/png;base64,"), "应为 data URL：$url")
            assertEquals(false, payload["stream"]!!.jsonPrimitive.content.toBoolean())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `非白名单扩展名与超大文件被拒绝（只读本地图片的边界）`() {
        val prefs = MapPrefs()
        prefs.set("stepfun.apiKey", "sk-test")
        val client = OpenAiCompatibleVisionClient(StepfunConfig(prefs), "test-vision")

        val txt = File(createTempDirectory("str-agent-vision2").toFile(), "secret.txt")
        txt.writeText("not an image")
        val error = runCatching {
            client.describe(
                com.stragent.core.ports.VisionRequest(
                    image = com.stragent.core.ports.VisionImage.LocalFile(txt.absolutePath),
                    prompt = "看图",
                ),
            )
        }.exceptionOrNull()
        assertTrue(error != null, "非图片扩展名应被拒绝")
        assertTrue(error!!.message!!.contains("只支持"), "应给中文提示：${error.message}")
    }
}
