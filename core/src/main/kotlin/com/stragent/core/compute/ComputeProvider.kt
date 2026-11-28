package com.stragent.core.compute

import com.stragent.core.dataset.Dataset
import com.stragent.core.trace.ProvenanceManifest
import java.security.MessageDigest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 计算清单（Compute Manifest）—— D10 §阶段2 规定的形状。
 *
 * 与工具调用清单（`ProvenanceManifest`）**并存**：前者记「模型写的代码」，后者记「调了哪些工具」。
 * 两者共用「重放校验」这一纪律：**任何要进报告的数字，都必须能重放出同样的结果**。
 */
data class ComputeManifest(
    /** 执行的代码（受控表达式） */
    val code: String,
    /** 依赖（受控表达式求值器无外部依赖 → 空列表；沙箱接入后填 Pyodide 版本等） */
    val deps: List<String> = emptyList(),
    /** 输入数据哈希（数据集内容） */
    val inputHash: String,
    /** 输出哈希（重放比对用） */
    val outputHash: String,
    /** 随机种子（确定性求值器 → 恒 0；接入采样类计算时才有意义） */
    val seed: Int = 0,
    /** 执行环境标识 */
    val env: String = ExpressionComputeProvider.ENV_ID,
    /** 输出口径（供宿主判断结果形状） */
    val outputSchema: JsonObject? = null,
    /** 执行引擎标识 */
    val engineId: String = ExpressionComputeProvider.ID,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("code", code)
        put("deps", kotlinx.serialization.json.JsonArray(deps.map { JsonPrimitive(it) }))
        put("inputHash", inputHash)
        put("outputHash", outputHash)
        put("seed", seed)
        put("env", env)
        put("engineId", engineId)
        put("createdAt", createdAt)
        outputSchema?.let { put("outputSchema", it) }
    }

    /**
     * **确定性形态**（不含时间戳）—— 用于「结果比对/哈希」。
     *
     * 为什么要专门一种形态：`createdAt` 每次都不同，若把它算进结果哈希，
     * 同样的输入重放一次就会得到不同的哈希 → 重放校验**永远失败**，
     * 等于把纪律自己废掉。
     */
    fun toStableJson(): JsonObject = buildJsonObject {
        put("code", code)
        put("deps", kotlinx.serialization.json.JsonArray(deps.map { JsonPrimitive(it) }))
        put("inputHash", inputHash)
        put("outputHash", outputHash)
        put("seed", seed)
        put("env", env)
        put("engineId", engineId)
        outputSchema?.let { put("outputSchema", it) }
    }

    companion object {
        fun fromJson(obj: JsonObject): ComputeManifest = ComputeManifest(
            code = obj["code"]?.jsonPrimitive?.content ?: "",
            deps = (obj["deps"] as? kotlinx.serialization.json.JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
            inputHash = obj["inputHash"]?.jsonPrimitive?.content ?: "",
            outputHash = obj["outputHash"]?.jsonPrimitive?.content ?: "",
            seed = obj["seed"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            env = obj["env"]?.jsonPrimitive?.content ?: ExpressionComputeProvider.ENV_ID,
            outputSchema = obj["outputSchema"]?.jsonObject,
            engineId = obj["engineId"]?.jsonPrimitive?.content ?: ExpressionComputeProvider.ID,
            createdAt = obj["createdAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
        )
    }
}

/**
 * 计算提供方（ComputeProvider）—— D10 §阶段2 的插件式契约。
 *
 * 两档：
 *  - **CURATED**：现有策展工具（默认）。天然可溯源，无需本文件；
 *  - **SANDBOX**：受控执行（本仓落地为表达式求值器）。**默认关闭**，需显式授权。
 */
interface ComputeProvider {
    val id: String
    val kind: ComputeKind

    /** 当前环境是否可用（缺原生库 / 未授权等） */
    fun isAvailable(): Boolean

    /** 沙箱级别（D11 v3 S2）—— 控制安全隔离程度 */
    val sandboxLevel: SandboxLevel get() = SandboxLevel.STANDARD

    /** 执行一段计算；失败抛 [ComputeException]（中文错误） */
    fun execute(code: String, dataset: Dataset): ComputeOutcome
}

enum class ComputeKind(val id: String, val label: String) {
    CURATED("curated", "策展工具（默认，天然可溯源）"),
    SANDBOX("sandbox", "受控沙箱（默认关闭，需授权）"),
}

/** 沙箱级别（D11 v3 S2）—— 控制代码执行的安全隔离程度 */
enum class SandboxLevel(val id: String, val label: String) {
    STANDARD("standard", "标准库子集（默认，无第三方包）"),
    WHITELIST("whitelist", "白名单命名空间（预装 venv + pandas/numpy/matplotlib）"),
    RESTRICTED_PYTHON("restricted_python", "AST 前置校验（RestrictedPython）"),
    DOCKER("docker", "Docker 容器隔离（多租户）"),
}

/** 一次计算的结果：值 + 清单 */
data class ComputeOutcome(
    val value: JsonElement,
    val manifest: ComputeManifest,
)

/**
 * 受控表达式执行器（本仓的 sandbox 档实现）。
 *
 * 特性：零依赖、确定性、可重放（见 [ExpressionEvaluator]）。
 */
object ExpressionComputeProvider : ComputeProvider {
    override val id: String = ID
    override val kind: ComputeKind = ComputeKind.SANDBOX

    const val ID = "expr-sandbox"
    const val ENV_ID = "str-agent-expr/1.0"

    override fun isAvailable(): Boolean = true

    override fun execute(code: String, dataset: Dataset): ComputeOutcome {
        val value = ExpressionEvaluator.evaluate(code, dataset)
        val manifest = ComputeManifest(
            code = code.trim(),
            inputHash = ProvenanceManifest.hashOfDataset(dataset),
            outputHash = hashOfValue(value),
            outputSchema = buildJsonObject {
                put("type", if (value is JsonPrimitive && value.isString) "string" else "number")
            },
        )
        return ComputeOutcome(value = value, manifest = manifest)
    }

    /** 输出哈希（重放比对用） */
    fun hashOfValue(value: JsonElement): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }
}
