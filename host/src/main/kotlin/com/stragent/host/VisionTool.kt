package com.stragent.host

import com.stragent.core.ports.AgentTool
import com.stragent.core.ports.VisionClient
import com.stragent.core.ports.VisionImage
import com.stragent.core.ports.VisionRequest
import com.stragent.data.StepfunConfig
import com.stragent.data.VisionPrompts
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 视觉分析工具 —— D10 §阶段3「图表反向抽取」的对外入口。
 *
 * 两种模式：
 *  - **describe**（默认）：自由提问，看图回答；
 *  - **extract_series**：把图表**反解成数据序列**（严格 JSON）→ 同时渲染成 CSV，
 *    交给既有分析链（`stragent_import_table` → 分析工具）继续算 ——
 *    于是「从图里读到的数」与「从表里读到的数」走**同一条可溯源流水线**。
 *
 * 纪律：
 *  - **只读本地图片**（扩展名白名单 + 体积上限），不上传其它本地文件；
 *  - 每次调用产出**视觉清单**（图像哈希 + 提示词哈希 + 输出哈希 + 模型）→ 抽出的数可回溯到「哪张图」。
 */
fun visionTool(vision: VisionClient): AgentTool = object : AgentTool {
    override val name = "stragent_analyze_image"
    override val description =
        "看**本地图片**并回答问题（默认），或把图表**反解成数据序列**（mode=extract_series）。" +
        "抽取模式会同时给出 CSV，可直接用 stragent_import_table 导入后继续做分组聚合等分析，" +
        "这样图里的数与表里的数走同一条可溯源流水线。"
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "imagePath",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "本地图片路径（.png / .jpg / .jpeg / .webp / .gif / .bmp）")
                    },
                )
                put(
                    "question",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "要问的问题（describe 模式；抽取模式忽略）")
                    },
                )
                put(
                    "mode",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "describe（默认，看图回答）或 extract_series（图表反解成数据）")
                    },
                )
            },
        )
        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("imagePath"))))
    }

    // 视觉调用花钱且不确定，不进缓存
    override val cacheable = false
    // 调外部视觉模型（开放世界）
    override val openWorld = true

    override fun invoke(args: JsonObject): JsonObject {
        val imagePath = args.string("imagePath") ?: throw IllegalArgumentException("imagePath 不能为空")
        val question = args.string("question") ?: ""
        val mode = args.string("mode") ?: "describe"
        if (!vision.isAvailable()) {
            throw IllegalStateException("视觉能力不可用：请先配置支持视觉的模型与 Key")
        }
        val file = File(imagePath)
        if (!file.isFile) throw IllegalArgumentException("图片不存在或不是普通文件：$imagePath")

        val prompt = if (mode == "extract_series") VisionPrompts.extractSeries() else VisionPrompts.describe(question)
        val result = vision.describe(VisionRequest(image = VisionImage.LocalFile(imagePath), prompt = prompt))

        return buildJsonObject {
            put("mode", mode)
            put("text", result.text)
            put("model", result.model)
            put("totalTokens", result.totalTokens)
            put("manifest", result.manifest.toStableJson())
            if (mode == "extract_series") {
                val series = VisionPrompts.parseSeries(result.text)
                put("series", series)
                put("csv", VisionPrompts.seriesToCsv(series))
            }
        }
    }
}

/**
 * 选一个视觉模型：
 *  ① prefs `vision.model`（用户显式指定）；
 *  ② 当前服务商里第一个「视觉/多模态/图像」模型；
 *  ③ 都没有就用服务商的首选模型（若它不支持视觉，API 会报错，错误文案会透出）。
 */
fun resolveVisionModel(config: StepfunConfig, prefs: com.stragent.core.ports.PrefsStore): String {
    val explicit = prefs.get("vision.model", "").trim()
    if (explicit.isNotBlank()) return explicit
    val vendor = config.getVendor()
    val visionLike = vendor.models.firstOrNull { model ->
        val text = "${model.label} ${model.hint} ${model.id}".lowercase()
        text.contains("视觉") || text.contains("vision") || text.contains("图像") || text.contains("多模态")
    }
    return visionLike?.id ?: vendor.models.firstOrNull()?.id.orEmpty()
}
