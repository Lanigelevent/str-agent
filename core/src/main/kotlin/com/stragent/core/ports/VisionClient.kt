package com.stragent.core.ports

import com.stragent.core.trace.VisionManifest

/**
 * 视觉端口（VisionClient）—— D10 §阶段3「vision 作为可插拔模型能力」。
 *
 * 为什么单列一个端口而不是塞进 LlmClient：
 *   视觉模型与文本模型的**可用范围、计价、限流、失败模式**都不同，且并非所有服务商都有视觉模型。
 *   分开成端口后，宿主可以给两个端口配不同的服务商/模型/开关，互不影响。
 *
 * **接口形态对齐主流实现**：请求是 OpenAI 兼容的 vision 形态（`image_url` 内容块），
 * 所以 GLM-4V / Qwen-VL / Kimi-VL / MiniMax / Step-Vision 等主流多模态模型**同一份适配器**都能用。
 *
 * 纪律（与本仓其他能力一致）：
 *  - 视觉**抽取出的数字**同样要可溯源 → 每次调用产出 [VisionManifest]（图像哈希 + 提示词哈希 + 模型）；
 *  - 图像只读本地文件或内联 base64，**不上传到第三方之外的任何地方**（本地优先）。
 */
interface VisionClient {
    /** 引擎标识（用于溯源与日志） */
    val id: String

    /** 当前环境是否可用（缺 Key / 无视觉模型 / 未启用） */
    fun isAvailable(): Boolean

    /** 看一张图、回答一个问题；失败抛异常（中文错误） */
    fun describe(request: VisionRequest): VisionResult
}

/** 图像来源 */
sealed interface VisionImage {
    /** 本地文件路径（由客户端读取并转 base64） */
    data class LocalFile(val path: String) : VisionImage

    /** 内联 base64（data URL 载荷） */
    data class Inline(val base64: String, val mimeType: String = "image/png") : VisionImage
}

/** 一次视觉调用请求 */
data class VisionRequest(
    val image: VisionImage,
    /** 提问（中文即可） */
    val prompt: String,
    val maxTokens: Int = 1024,
    val temperature: Double = 0.2,
)

/** 一次视觉调用的结果 */
data class VisionResult(
    /** 模型返回的文本（可能是 JSON 序列，视 prompt 而定） */
    val text: String,
    /** 使用的模型 */
    val model: String,
    val totalTokens: Int,
    /** 溯源清单：图像哈希 / 提示词哈希 / 输出哈希 / 模型 */
    val manifest: VisionManifest,
)
