package com.stragent.core.trace

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 视觉调用的溯源清单（Vision Manifest）—— D10 §阶段3 的纪律内核。
 *
 * 为什么视觉也要清单：图表里抽出来的数字，**和表格里读出来的数字一样**，必须能回溯到"哪张图的哪次抽取"。
 * 没有清单的视觉抽取，等于给报告开了一个可以编造数字的口子。
 *
 * 与 [ProvenanceManifest] 同样的两个纪律：
 *  ① 哈希在**调用时**捕获（不是事后算）；
 *  ② 提供**确定性形态**（不含时间戳）专供结果比对 —— 否则同样输入重放的哈希必不同，重放校验永远失败。
 */
data class VisionManifest(
    /** 图像内容哈希（同一张图 → 同一个哈希） */
    val imageHash: String,
    /** 提示词哈希（换了问法 → 不同的哈希） */
    val promptHash: String,
    /** 输出哈希（重放比对用） */
    val outputHash: String,
    /** 使用的模型 */
    val model: String,
    /** 执行环境标识（客户端 + 接口形态） */
    val env: String,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("imageHash", imageHash)
        put("promptHash", promptHash)
        put("outputHash", outputHash)
        put("model", model)
        put("env", env)
        put("createdAt", createdAt)
    }

    /** 确定性形态（不含时间戳）—— 用于结果比对/哈希 */
    fun toStableJson(): JsonObject = buildJsonObject {
        put("imageHash", imageHash)
        put("promptHash", promptHash)
        put("outputHash", outputHash)
        put("model", model)
        put("env", env)
    }

    companion object {
        /** 内容哈希：sha256 前 16 位 */
        fun hashOf(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(16)
        }

        /** 字节哈希（图像用） */
        fun hashOfBytes(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }.take(16)
        }
    }
}
