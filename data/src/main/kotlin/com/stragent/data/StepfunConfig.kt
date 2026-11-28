package com.stragent.data

import com.stragent.core.ports.PrefsStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * stepfun.ts 的配置层移植：服务商清单 + 本机偏好读写。
 * （HTTP 调用在 HttpLlmClient；这里的职责是「当前用哪家、哪个模型、Key 在不在」。）
 */

/**
 * 面向用户的错误分类（不把 Key / 原始报文暴露到界面）——stepfun.ts StepError 的 kind。
 *
 * T-062 起唯一来源上移到 `core/ai/ChatStep.kt`（[StepError] 住在 core，core 里也要能按分类分流）；
 * 这里保留**同值别名**，既有调用方与测试零改动。
 */
const val STEP_ERR_AUTH = com.stragent.core.ai.STEP_ERR_AUTH
const val STEP_ERR_QUOTA = com.stragent.core.ai.STEP_ERR_QUOTA
const val STEP_ERR_BAD_REQUEST = com.stragent.core.ai.STEP_ERR_BAD_REQUEST
const val STEP_ERR_NETWORK = com.stragent.core.ai.STEP_ERR_NETWORK
const val STEP_ERR_NO_KEY = com.stragent.core.ai.STEP_ERR_NO_KEY
const val STEP_ERR_UNSUPPORTED = com.stragent.core.ai.STEP_ERR_UNSUPPORTED

/**
 * T-049 新增分类：5xx 与超时从 network 里拆出来。
 * 拆分不是为了好看——重试决策依赖它（5xx/连接超时值得退避，读超时/鉴权不值得），
 * UI 也能对「服务端故障」与「网络不通」给不同提示。
 */
const val STEP_ERR_SERVER = com.stragent.core.ai.STEP_ERR_SERVER
const val STEP_ERR_TIMEOUT = com.stragent.core.ai.STEP_ERR_TIMEOUT

data class StepModelOption(val id: String, val label: String, val hint: String)

data class VendorOption(
    val id: String,
    val label: String,
    /** OpenAI 兼容的 base URL（不含 /chat/completions） */
    val baseUrl: String,
    /** 该服务商的主力模型；厂商上新后用户可用「自定义模型」兜底 */
    val models: List<StepModelOption>,
    /** Key 输入框的标签（如「DeepSeek API Key」） */
    val keyLabel: String,
    /** 去哪里拿 Key（设置页 Key 区下方的一行提示） */
    val keyHint: String,
    /** 联网检索（StepSearch）只认阶跃星辰的 Key，别家没有这条路 */
    val supportsSearch: Boolean,
)

/**
 * 服务商清单。模型 ID 与 stepfun.ts 逐字一致（2026-10-20 核对口径）：
 * 已确认下线的旧 ID 不留在清单里；聚合平台（硅基流动）模型 ID 变动频繁，不列死，
 * 靠「自定义模型」让用户填——给一个过期 ID 比不给更糟。
 */
val VENDORS: List<VendorOption> = listOf(
    VendorOption(
        id = "stepfun",
        label = "阶跃星辰 Step",
        baseUrl = "https://api.stepfun.com/step_plan/v1",
        models = listOf(
            StepModelOption("step-5-preview", "Step 5 Preview", "新一代旗舰基模，1M 上下文，图文视频输入"),
            StepModelOption("step-3.7-flash", "Step 3.7 Flash", "多模态推理旗舰，256K 上下文，Agent 与 Coding"),
            StepModelOption("step-router-v1", "Router 自动路由", "按任务复杂度自动选择模型"),
            StepModelOption("step-1o-turbo-vision", "Step 1o Turbo Vision", "视觉理解，图片问答"),
        ),
        keyLabel = "Step API Key",
        keyHint = "阶跃星辰开放平台 → 控制台创建 API Key；联网检索也走这一把",
        supportsSearch = true,
    ),
    VendorOption(
        id = "deepseek",
        label = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1",
        models = listOf(
            StepModelOption("deepseek-flash", "DeepSeek Flash", "V4.1 主力，1M 上下文，支持图像理解"),
            StepModelOption("deepseek-v4-pro", "DeepSeek V4 Pro", "高阶推理，1M 上下文，纯文本"),
        ),
        keyLabel = "DeepSeek API Key",
        keyHint = "platform.deepseek.com → API Keys 创建",
        supportsSearch = false,
    ),
    VendorOption(
        id = "qwen",
        label = "阿里通义千问",
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        models = listOf(
            StepModelOption("qwen3.8-max", "Qwen3.8 Max", "最强推理，1M 上下文，成本较高"),
            StepModelOption("qwen3.7-plus", "Qwen3.7 Plus", "官方默认推荐，能力与成本均衡"),
            StepModelOption("qwen3.8-flash", "Qwen3.8 Flash", "轻量低成本，效果接近旗舰"),
        ),
        keyLabel = "阿里云百炼 API Key",
        keyHint = "阿里云百炼控制台 → API-KEY 管理创建",
        supportsSearch = false,
    ),
    VendorOption(
        id = "zhipu",
        label = "智谱 AI",
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        models = listOf(
            StepModelOption("glm-5.3", "GLM-5.3", "当前旗舰，1M 上下文，编程与 Agent"),
            StepModelOption("glm-5.3-flash", "GLM-5.3 Flash", "原生多模态，价格约为旗舰 1/10"),
            StepModelOption("glm-4.6", "GLM-4.6", "高级编码与工具调用"),
            StepModelOption("glm-4.7-flash", "GLM-4.7 Flash", "免费档，响应快"),
        ),
        keyLabel = "智谱 API Key",
        keyHint = "open.bigmodel.cn → API Keys 创建",
        supportsSearch = false,
    ),
    VendorOption(
        id = "moonshot",
        label = "月之暗面 Kimi",
        baseUrl = "https://api.moonshot.cn/v1",
        models = listOf(
            StepModelOption("kimi-k3", "Kimi K3", "旗舰，1M 上下文，原生多模态"),
            StepModelOption("kimi-k2.6", "Kimi K2.6", "通用多模态，256K 上下文，思考可开关"),
            StepModelOption("kimi-k2.7-code", "Kimi K2.7 Code", "编程专用，思考常开"),
        ),
        keyLabel = "Moonshot API Key",
        keyHint = "platform.moonshot.cn → API Key 管理创建",
        supportsSearch = false,
    ),
    VendorOption(
        id = "minimax",
        label = "MiniMax",
        baseUrl = "https://api.minimaxi.com/v1",
        models = listOf(
            StepModelOption("MiniMax-M3.1-Flash", "MiniMax M3.1 Flash", "最新，1M 上下文，多模态编程"),
            StepModelOption("MiniMax-M3", "MiniMax M3", "1M 上下文，多模态编程"),
            StepModelOption("MiniMax-M2.7", "MiniMax M2.7", "204.8K 上下文，文本主力"),
        ),
        keyLabel = "MiniMax API Key",
        keyHint = "MiniMax 开放平台 → 账户管理 → API Key 创建",
        supportsSearch = false,
    ),
    VendorOption(
        id = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        models = listOf(
            StepModelOption("gpt-6-astra", "GPT-6 Astra", "当前旗舰，最强推理与编码"),
            StepModelOption("gpt-6.1-sol", "GPT-6.1 Sol", "接近旗舰的能力，价格更低"),
            StepModelOption("gpt-6-luna", "GPT-6 Luna", "低成本高并发"),
            StepModelOption("gpt-4o", "GPT-4o", "上一代主力多模态，仍在线"),
        ),
        keyLabel = "OpenAI API Key",
        keyHint = "platform.openai.com → API Keys 创建（国内需可访问 OpenAI 的网络）",
        supportsSearch = false,
    ),
    VendorOption(
        id = "siliconflow",
        label = "硅基流动",
        baseUrl = "https://api.siliconflow.cn/v1",
        models = emptyList(),
        keyLabel = "硅基流动 API Key",
        keyHint = "cloud.siliconflow.cn → API Key 创建；模型 ID 请在模型选择里用「自定义模型」填写",
        supportsSearch = false,
    ),
)

@Serializable
private data class LiveModels(val ids: List<String> = emptyList(), val fetchedAt: Long = 0)

/** 接入方式可用性（UI 用它决定显示什么）——stepfun.ts AccessStatus */
data class AccessStatus(
    val providerId: String,
    val vendorId: String,
    val ready: Boolean,
    val needsKey: Boolean,
    val canSearch: Boolean,
    val reason: String? = null,
)

/**
 * stepfun 配置读写：所有键名与 TS 侧一致（stepfun.provider / vendor / model /
 * customModel / apiKey / liveModels / extraModels）。
 */
class StepfunConfig(
    private val prefs: PrefsStore,
    /** API Key 加密/解密（Android 侧注入 KeystoreEncryptor；JVM 测试传恒等） */
    private val encrypt: (String) -> String = { it },
    private val decrypt: (String) -> String = { it },
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun getProviderId(): String {
        val saved = prefs.get(PROVIDER_STORAGE, "")
        return if (saved == "amoo-system") "amoo-system" else "step-plan"
    }

    fun setProviderId(id: String) = prefs.set(PROVIDER_STORAGE, id)

    /** 接入方式列表（顺序即展示顺序）：amoo-system 是预留位，永远不可用 */
    val providers: List<Pair<String, String>> = listOf(
        "step-plan" to "开放平台 API",
        "amoo-system" to "系统模型 · AMOO",
    )

    fun getVendorId(): String {
        val saved = prefs.get(VENDOR_STORAGE, "")
        return if (VENDORS.any { it.id == saved }) saved else "stepfun"
    }

    fun setVendorId(id: String) = prefs.set(VENDOR_STORAGE, id)

    fun getVendor(vendorId: String = getVendorId()): VendorOption =
        VENDORS.find { it.id == vendorId } ?: VENDORS.first()

    fun getApiKey(): String = decrypt(prefs.get(KEY_STORAGE, ""))

    fun setApiKey(key: String) = prefs.set(KEY_STORAGE, encrypt(key.trim()))

    fun removeApiKey() = prefs.remove(KEY_STORAGE)

    fun getCustomModel(): String = prefs.get(CUSTOM_MODEL_STORAGE, "")

    fun setCustomModel(model: String) = prefs.set(CUSTOM_MODEL_STORAGE, model.trim())

    /** 某家服务商上次实时拉到的模型（没有记录返回 null） */
    fun getLiveModels(vendorId: String): Pair<List<String>, Long>? {
        val raw = prefs.get(LIVE_MODELS_STORAGE, "")
        if (raw.isEmpty()) return null
        return runCatching {
            val store = json.decodeFromString<Map<String, LiveModels>>(raw)
            store[vendorId]?.let { it.ids to it.fetchedAt }
        }.getOrNull()
    }

    fun saveLiveModels(vendorId: String, ids: List<String>) {
        val raw = prefs.get(LIVE_MODELS_STORAGE, "")
        val store: MutableMap<String, LiveModels> = runCatching {
            json.decodeFromString<Map<String, LiveModels>>(raw.ifEmpty { "{}" })
        }.getOrDefault(emptyMap()).toMutableMap()
        store[vendorId] = LiveModels(ids, System.currentTimeMillis())
        prefs.set(LIVE_MODELS_STORAGE, json.encodeToString(store))
    }

    /** 用户确认加入的扩展模型 ID */
    fun getExtraModels(vendorId: String): List<String> {
        val raw = prefs.get(EXTRA_MODELS_STORAGE, "")
        if (raw.isEmpty()) return emptyList()
        return runCatching {
            json.decodeFromString<Map<String, List<String>>>(raw)[vendorId] ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** 把实时发现的新模型写入本地扩展（模型选择里立即可选） */
    fun addExtraModels(vendorId: String, ids: List<String>) {
        val raw = prefs.get(EXTRA_MODELS_STORAGE, "")
        val store: MutableMap<String, List<String>> = runCatching {
            json.decodeFromString<Map<String, List<String>>>(raw.ifEmpty { "{}" })
        }.getOrDefault(emptyMap()).toMutableMap()
        val current = store[vendorId] ?: emptyList()
        store[vendorId] = (current + ids).distinct()
        prefs.set(EXTRA_MODELS_STORAGE, json.encodeToString(store))
    }

    /** 当前生效的模型清单 = 内置 + 用户确认加入的扩展 */
    fun getModels(vendorId: String = getVendorId()): List<StepModelOption> {
        val vendor = getVendor(vendorId)
        val extras = getExtraModels(vendor.id)
        if (extras.isEmpty()) return vendor.models
        val known = vendor.models.map { it.id }.toSet()
        return vendor.models + extras.filter { it !in known }
            .map { StepModelOption(it, it, "检查模型更新时发现的新模型") }
    }

    /** 当前生效的模型 ID（自定义模型填了就以它为准） */
    fun getModel(): String {
        val custom = getCustomModel()
        if (custom.isNotEmpty()) return custom
        val saved = prefs.get(MODEL_STORAGE, "")
        val models = getModels()
        return if (models.any { it.id == saved }) saved else (models.firstOrNull()?.id ?: "")
    }

    fun setModel(model: String) = prefs.set(MODEL_STORAGE, model)

    /** 当前模型的展示名（自定义模型直接显示 ID） */
    fun getModelLabel(): String {
        val model = getModel()
        return getModels().find { it.id == model }?.label ?: model
    }

    /** 接入方式能不能立刻调用（amoo-system 是预留位，永远 unsupported） */
    fun accessStatus(): AccessStatus {
        val providerId = getProviderId()
        if (providerId == "amoo-system") {
            return AccessStatus(
                providerId = providerId, vendorId = getVendorId(), ready = false,
                needsKey = false, canSearch = false,
                reason = "Step AOS 生态专用，独立 APK 版暂不支持。赠送的 Token Plan 目前只能通过 Step Studio " +
                    "生态版使用（见工程根目录 STEP_STUDIO_BRIEF.md）；本工程是通用安卓包，拿不到系统模型调用契约。",
            )
        }
        val vendor = getVendor()
        if (getApiKey().isEmpty()) {
            return AccessStatus(
                providerId = providerId, vendorId = vendor.id, ready = false,
                needsKey = true, canSearch = false, reason = "还没有配置 ${vendor.keyLabel}",
            )
        }
        // 聚合平台的模型 ID 不列死，没填自定义模型时不给调（空 model 只会换来一个 400）
        if (vendor.models.isEmpty() && getCustomModel().isEmpty()) {
            return AccessStatus(
                providerId = providerId, vendorId = vendor.id, ready = false,
                needsKey = false, canSearch = false,
                reason = "请先在「分析模型」里用「自定义模型」填写要用的模型 ID",
            )
        }
        return AccessStatus(
            providerId = providerId, vendorId = vendor.id, ready = true,
            needsKey = false, canSearch = vendor.supportsSearch,
        )
    }

    companion object {
        const val PROVIDER_STORAGE = "stepfun.provider"
        const val VENDOR_STORAGE = "stepfun.vendor"
        const val MODEL_STORAGE = "stepfun.model"
        const val CUSTOM_MODEL_STORAGE = "stepfun.customModel"
        const val KEY_STORAGE = "stepfun.apiKey"
        const val LIVE_MODELS_STORAGE = "stepfun.liveModels"
        const val EXTRA_MODELS_STORAGE = "stepfun.extraModels"
    }
}
