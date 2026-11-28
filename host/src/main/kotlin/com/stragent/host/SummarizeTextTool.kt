package com.stragent.host

import com.stragent.core.ports.AgentTool
import com.stragent.core.ai.summarizeText
import com.stragent.core.dataset.Dataset
import com.stragent.core.ports.DatasetStore
import com.stragent.core.ports.LlmClient
import com.stragent.data.AbilityDatasets
import com.stragent.data.McpSessionContext
import com.stragent.data.SessionRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `stragent_summarize_text`——对文本/混合数据集的正文材料生成总结（T-096）。
 *
 * 材料 = `stragent_import_text` 导入的「文本部分」（段落 + 表格投影）；纯表格数据集
 * 报中文错误（请用表格分析/综述报告链路）。混合数据集**只总结 text 部分**——
 * 表格数字请用表格工具，本工具的数字白名单只来自正文要点。
 *
 * 路由：≤30K 字符 stuff 直通；否则 map-reduce（逐块要点→归并）；块数 > 40 时层级归并
 * （先节后总）。quote-first：要点必带 ≤50 字原文短引用与段锚；归并只允许使用要点里的
 * 关键数字（<allowed_numbers>），清单外数字不许出现；全部块失败抛中文错误，不编造。
 *
 * 数据集解析：显式 `datasetId`（存在才用）→ 否则**会话指针**（具名会话严格隔离，
 * 不回退别的会话；缺省 default 会话回退全局最近导入）——与计算工具同一口径（T-031）。
 *
 * 进度帧经 ProgressBus 发出（map 逐块/层级/CoD 阶段）；带 progressToken 的 MCP 调用
 * 直接收到 notifications/progress；取消经 McpRequestCancellation.signal（步与步之间检查）。
 * kv 续传：同一材料哈希 + 同一块哈希命中块级要点缓存——中断后重试不重复付费。
 */
fun summarizeTextTool(
    llm: LlmClient,
    datasets: AbilityDatasets,
    registry: SessionRegistry? = null,
    kv: DatasetStore? = null,
    model: String = "step-3.5-flash",
    usageLedger: com.stragent.core.observability.UsageLedger? = null,
): AgentTool = object : AgentTool {
    override val name = "stragent_summarize_text"
    override val description =
        "总结文本/混合数据集的正文材料（stragent_import_text 导入的 txt/md/docx/pdf）。" + // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            "三层路由自动（stuff/map-reduce/层级）：stuff ≤30K 字直通，否则逐块要点再归并，块数 > 40 先节后总。" + // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            "quote-first：要点必带 ≤50 字原文短引用与段锚（段 N）；数字白名单只来自要点，归并不允许清单外数字。" + // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            "返回 summary 正文 + points（要点/短引用/段锚）+ numbersWithAnchors + failedChunks + route/chunkCount/durationMs/totalTokens。" + // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            "混合数据集只总结正文；纯表格数据集报中文错误。" // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put("datasetId", buildJsonObject {
                    put("type", "string")
                    put("description", "显式数据集 id（缺省为会话当前数据集：具名会话严格隔离，default 会话回退全局最近导入）") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                })
                put("focus", buildJsonObject {
                    put("type", "string")
                    put("description", "分析视角（可选，如「关注成本结构与风险」）；非空时按 BM25 相关度重排要点（lost-in-the-middle 缓解：最相关置首、次相关置尾）") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                })
                put("maxWords", buildJsonObject {
                    put("type", "integer")
                    put("description", "目标篇幅字数上限（默认 800，100–2000）") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                })
                put("maxPoints", buildJsonObject {
                    put("type", "integer")
                    put("description", "回执携带的要点条数上限（verbosity，默认 40；小上下文模型保持默认即可，长上下文模型可传 9999 取全量要点）") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                })
                put("audience", buildJsonObject {
                    put("type", "string")
                    put("description", "受众画像（T-105，可选，≤300 字）：写给谁（如「施工方项目经理，非监测专业」）；非空时注入 persona 块并参与要点相关度重排") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                })
                put("requirements", buildJsonObject {
                    put("type", "string")
                    put("description", "附加输出要求（T-105，可选，≤2000 字）：总结口径等自由要求（如「只保留风险相关要点」）；与 focus 正交（focus=看什么，本参=怎么写）") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                })
                put("depth", buildJsonObject {
                    put("type", "string")
                    put("description", "详细程度（T-106，可选）：brief（缺省 400 字）/standard（800）/deep（1500）；显式 maxWords 优先于 depth 缺省") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                })
            },
        )
        put("required", JsonArray(emptyList()))
    }

    override val cacheable = false

    // 调模型 + 块级要点缓存（textpoints.* 写入 kv）：不是只读，与 generate_report 同口径
    override val readOnly = false
    override val openWorld = true // 涉及模型调用（LLM 端点） // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）

    override fun invoke(args: JsonObject): JsonObject {
        val dataset = resolveDataset(args.string("datasetId"), datasets, registry)
        val content = dataset.text
            ?: throw IllegalArgumentException(
                "数据集「${dataset.name}」没有文本部分——它是纯表格数据集：表格分析工具与综述报告链路可用；" + // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                    "stragent_import_text 导入的 txt/md/docx/pdf 才有正文材料。", // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            )
        val focus = args.string("focus")
        // T-106 depth 缺省映射（GPT Researcher summary/detailed 先例）：显式 maxWords 优先
        val depth = args.string("depth")?.takeIf { it.isNotBlank() }?.also {
            require(it in com.stragent.core.report.ReportLib.depths) { "depth 无效：$it（合法值：${com.stragent.core.report.ReportLib.depths.joinToString("/")}）" } // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        }
        val maxWords = (args.int("maxWords") ?: when (depth) {
            "brief" -> 400
            "deep" -> 1500
            else -> 800
        }).coerceIn(100, 2000)
        // T-098（Anthropic verbosity 模式）：要点回传量由调用方按自家上下文决定——
        // 默认 40 条服务小上下文模型；长上下文模型显式传大值取全量。截断永远带取全路径。
        val maxPoints = (args.int("maxPoints") ?: 40).coerceIn(1, 9_999)
        // T-105 按调用输出适配：受众画像与附加要求（长度上限校验；空白 = 不注入，零差异）
        val audience = args.string("audience")?.takeIf { it.isNotBlank() }?.also {
            require(it.length <= 300) { "audience 过长：${it.length} 字（上限 300）" } // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        }
        val requirements = args.string("requirements")?.takeIf { it.isNotBlank() }?.also {
            require(it.length <= 2000) { "requirements 过长：${it.length} 字（上限 2000）" } // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        }

        val result = summarizeText(
            llm = llm,
            model = model,
            dataset = dataset,
            focus = focus,
            maxWords = maxWords,
            kv = kv,
            cancelSignal = com.stragent.data.McpRequestCancellation.signal(),
            onStage = { message -> com.stragent.agent.ProgressBus.emit(message) },
            audience = audience,
            requirements = requirements,
        )

        // T-098：要点分级回传——顺序即相关度（focus 非空时 summarizeText 已按 BM25 重排）
        val pointsTotal = result.points.size
        val selected = result.points.take(maxPoints)
        val pointsTruncated = pointsTotal > selected.size

        return buildJsonObject {
            // 字段序即注意力序（lost-in-the-middle 同理适用回执内）：正文最相关信息在前
            put("datasetId", args.string("datasetId") ?: currentPointerId(registry, datasets) ?: "")
            put("name", dataset.name)
            put("kind", if (dataset.rows.isEmpty()) "text" else "mixed")
            put("summary", result.summary)
            // 恒输出：list 字段空也输出（「没这路信息」≠「字段缺失」）
            put(
                "points",
                JsonArray(
                    selected.map { p ->
                        buildJsonObject {
                            put("text", p.text)
                            put("quote", p.quote)
                            put("segmentIndex", p.segmentIndex)
                        }
                    },
                ),
            )
            put("pointsTotal", pointsTotal)
            put("pointsTruncated", pointsTruncated)
            put(
                "numbersWithAnchors",
                JsonArray(
                    result.numbersWithAnchors.map { n ->
                        buildJsonObject {
                            put("number", n.number)
                            put("anchor", n.anchor)
                        }
                    },
                ),
            )
            put("failedChunks", JsonArray(result.failedChunks.map { JsonPrimitive(it) }))
            put("route", result.route)
            put("chunkCount", result.chunkCount)
            put("durationMs", result.durationMs)
            put("totalTokens", result.totalTokens)
            // T-171：用量与成本（估算，basis 明写；未配单价时只给 token 数，不编造金额）
            usageLedger?.let { ledger ->
                ledger.record("stragent_summarize_text", result.totalTokens)
                put("usage", ledger.usageBlock("stragent_summarize_text", result.totalTokens))
            }
            put("segments", content.segments.size)
            put("paragraphs", content.paragraphCount)
            focus?.let { put("focus", it) }
            audience?.let { put("audience", it) }
            requirements?.let { put("requirements", it) }
            depth?.let { put("depth", it) } // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）——T-108 生效配置回显
            put("maxWords", maxWords)
            if (result.failedChunks.isNotEmpty()) {
                put(
                    "notice",
                    "有 ${result.failedChunks.size} 块要点提取失败，总结覆盖的材料不完整（详见 failedChunks）；重试同一调用可命中块级缓存，失败块会重新尝试。", // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                )
            }
            // T-098：nextStep 恒输出——截断给取全路径，混合给表格链指路，纯文本给核对路径
            put(
                "nextStep",
                buildString {
                    if (pointsTruncated) {
                        append("要点已截断（共 $pointsTotal 条，仅含前 ${selected.size} 条）：需要全量要点请带 maxPoints=9999 重调（块级缓存命中，成本极低）；") // lint:allow zh-first（用户面中文输出，T-095/T-098 语言定位）
                    }
                    if (dataset.rows.isNotEmpty()) {
                        append("这是混合数据集：表格数字请用 stragent_session_overview / stragent_session_group_aggregate 等表格工具；") // lint:allow zh-first（用户面中文输出，T-095/T-098 语言定位）
                    }
                    append("核对要点/锚点对应的原文请用 stragent_read_segments（段号区间读取）。") // lint:allow zh-first（用户面中文输出，T-095/T-098 语言定位）
                },
            )
        }
    }
}

/** 数据集解析：显式 datasetId 优先，否则会话指针（具名严格隔离，default 回退全局最近导入） */
internal fun resolveDataset(
    datasetId: String?,
    datasets: AbilityDatasets,
    registry: SessionRegistry?,
): Dataset {
    if (!datasetId.isNullOrBlank()) {
        return datasets.byId(datasetId)
            ?: throw IllegalArgumentException("数据集不存在：$datasetId（先用 stragent_import_text 导入，或检查 id）") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
    }
    if (registry != null) {
        return registry.current(McpSessionContext.current())
            ?: throw IllegalArgumentException("尚未导入数据：请先用 stragent_import_text 导入文本材料，或显式传 datasetId") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
    }
    return datasets.current()
        ?: throw IllegalArgumentException("尚未导入数据：请先用 stragent_import_text 导入文本材料，或显式传 datasetId") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
}

internal fun currentPointerId(registry: SessionRegistry?, datasets: AbilityDatasets): String? =
    registry?.currentId(McpSessionContext.current()) ?: datasets.currentId()
