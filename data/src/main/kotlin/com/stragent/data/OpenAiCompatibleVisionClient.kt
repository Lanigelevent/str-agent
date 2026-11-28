package com.stragent.data

import com.stragent.core.ai.StepError
import com.stragent.core.ports.VisionClient
import com.stragent.core.ports.VisionImage
import com.stragent.core.ports.VisionRequest
import com.stragent.core.ports.VisionResult
import com.stragent.core.trace.VisionManifest
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * **主流视觉接口**适配器 —— OpenAI 兼容的 vision 形态。
 *
 * 请求形态（业界事实标准，GLM-4V / Qwen-VL / Kimi-VL / MiniMax / Step-Vision 等通用）：
 * ```
 * POST {baseUrl}/chat/completions
 * {
 *   "model": "...",
 *   "messages": [{ "role": "user", "content": [
 *       { "type": "text", "text": "<提问>" },
 *       { "type": "image_url", "image_url": { "url": "data:image/png;base64,..." } }
 *   ]}],
 *   "max_tokens": 1024, "temperature": 0.2, "stream": false
 * }
 * ```
 * 响应取 `choices[0].message.content`。
 *
 * 为什么只做这一个适配器：**接口主流 = 覆盖面最广**。同一份代码 + 不同的 baseUrl/model/Key
 * 就能覆盖绝大多数多模态服务商；要接非主流接口时，按端口新增实现即可（宿主可替换）。
 *
 * 纪律：
 *  - **只读本地图片**：扩展名白名单 + 体积上限，绝不外传本地其它文件；
 *  - **每次调用出清单**：图像哈希 + 提示词哈希 + 输出哈希 + 模型 → 抽出的数字可回溯到"哪张图"。
 */
class OpenAiCompatibleVisionClient(
    private val config: StepfunConfig,
    private val model: String,
    private val timeoutMs: Int = 60_000,
    /** 覆盖 base URL（默认取当前服务商；自建/网关/测试时用） */
    private val baseUrlOverride: String? = null,
) : VisionClient {

    override val id: String = ID

    override fun isAvailable(): Boolean =
        config.getApiKey().isNotEmpty() && model.isNotBlank()

    override fun describe(request: VisionRequest): VisionResult {
        val apiKey = config.getApiKey()
        if (apiKey.isEmpty()) {
            throw StepError(STEP_ERR_NO_KEY, "还没有配置 ${config.getVendor().keyLabel}（视觉模型也需要 Key）")
        }
        val image = loadImage(request.image)
        val payload = buildJsonObject {
            put("model", model)
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "content",
                                buildJsonArray {
                                    add(buildJsonObject { put("type", "text"); put("text", request.prompt) })
                                    add(
                                        buildJsonObject {
                                            put("type", "image_url")
                                            put(
                                                "image_url",
                                                buildJsonObject {
                                                    put("url", "data:${image.mimeType};base64,${image.base64}")
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
            put("max_tokens", request.maxTokens)
            put("temperature", request.temperature)
            put("stream", false)
        }

        val url = "${baseUrlOverride ?: config.getVendor().baseUrl}/chat/completions"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        val body = conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val status = conn.responseCode
        val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
            ?.use { it.readBytes().decodeToString() }
            .orEmpty()
        conn.disconnect()
        if (status !in 200..299) {
            throw StepError(ERR_VISION, "视觉模型调用失败（HTTP $status）：${extractErrorMessage(text)}")
        }
        val content = extractContent(text)
        return VisionResult(
            text = content,
            model = model,
            totalTokens = extractTotalTokens(text),
            manifest = VisionManifest(
                imageHash = VisionManifest.hashOfBytes(image.bytes),
                promptHash = VisionManifest.hashOf(request.prompt),
                outputHash = VisionManifest.hashOf(content),
                model = model,
                env = ENV_ID,
            ),
        )
    }

    /** 载入图片：本地文件（白名单 + 体积上限）或内联 base64 */
    private fun loadImage(image: VisionImage): LoadedImage = when (image) {
        is VisionImage.LocalFile -> {
            val file = File(image.path)
            if (!file.isFile) throw IllegalArgumentException("图片不存在或不是普通文件：${image.path}")
            val extension = file.extension.lowercase()
            if (extension !in ALLOWED_IMAGE_EXTENSIONS) {
                throw IllegalArgumentException(
                    "只支持 ${ALLOWED_IMAGE_EXTENSIONS.joinToString(" / ")} 图片，收到：.$extension",
                )
            }
            if (file.length() > MAX_IMAGE_BYTES) {
                throw IllegalArgumentException("图片过大（上限 ${MAX_IMAGE_BYTES / 1024 / 1024}MB）")
            }
            val bytes = file.readBytes()
            LoadedImage(
                base64 = Base64.getEncoder().encodeToString(bytes),
                mimeType = MIME_BY_EXTENSION[extension] ?: "image/png",
                bytes = bytes,
            )
        }

        is VisionImage.Inline -> {
            val bytes = runCatching { Base64.getDecoder().decode(image.base64) }
                .getOrElse { throw IllegalArgumentException("base64 图片解码失败") }
            if (bytes.size > MAX_IMAGE_BYTES) {
                throw IllegalArgumentException("图片过大（上限 ${MAX_IMAGE_BYTES / 1024 / 1024}MB）")
            }
            LoadedImage(image.base64, image.mimeType, bytes)
        }
    }

    private fun extractContent(body: String): String {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrElse {
            throw StepError(ERR_VISION, "视觉模型返回的不是 JSON：${body.take(120)}")
        }
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw StepError(ERR_VISION, "视觉模型返回里没有 choices：${body.take(120)}")
        val message = choice["message"]?.jsonObject
            ?: throw StepError(ERR_VISION, "视觉模型返回里没有 message")
        val content = message["content"]
        return when (content) {
            is JsonPrimitive -> content.content
            else -> content?.jsonArray?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content ?: "" }
                ?: throw StepError(ERR_VISION, "视觉模型返回里没有 content")
        }
    }

    private fun extractTotalTokens(body: String): Int =
        runCatching {
            Json.parseToJsonElement(body).jsonObject["usage"]?.jsonObject
                ?.get("total_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        }.getOrDefault(0)

    private fun extractErrorMessage(body: String): String {
        val message = runCatching {
            Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
                ?.get("message")?.jsonPrimitive?.content
        }.getOrNull()
        return message ?: body.take(200).ifBlank { "（无响应体）" }
    }

    private data class LoadedImage(val base64: String, val mimeType: String, val bytes: ByteArray)

    companion object {
        const val ID = "openai-compatible-vision"
        const val ENV_ID = "openai-compatible-vision/1.0"
        const val ERR_VISION = "vision-failed"

        /** 体积上限 10MB（够用且防止把内存当仓库用） */
        const val MAX_IMAGE_BYTES = 10L * 1024 * 1024

        val ALLOWED_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

        val MIME_BY_EXTENSION = mapOf(
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "webp" to "image/webp",
            "gif" to "image/gif",
            "bmp" to "image/bmp",
        )
    }
}
