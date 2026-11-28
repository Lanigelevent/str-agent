package com.stragent.host

import com.stragent.core.ports.AgentTool
import com.stragent.data.AbilityDatasets
import com.stragent.data.McpSessionContext
import com.stragent.data.SessionRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 单次读取的段数与字符上限（页大小设计：段号即天然游标，可连续翻页，不是总闸） */
private const val READ_SEGMENTS_MAX_COUNT = 20
private const val READ_SEGMENTS_MAX_CHARS = 8_000

/**
 * `stragent_read_segments`——按段号区间读取文本/混合数据集的正文原文（T-098）。
 *
 * 两个用途（工具描述对调用方模型明示）：
 * 1. **原文下钻**：stragent_summarize_text / stragent_generate_report 回执中的「段 N」锚
 *    由此解引用——锚点不再是悬空引用；
 * 2. **引用核对（验证循环的取证端）**：核对回执中的 quote 是否被原文支撑——行业
 *    verification-loop 纪律（生成带引声明 → 取回被引证据 → 校验），服务端 gate 之外
 *    把验证能力延伸给调用方模型。
 *
 * 分页：段号本身就是游标（比 opaque cursor 更利于模型推理「段 12 之后还有 13-50」）——
 * 返回 nextFrom 提示续读位置；单页上限 20 段 / 8000 字符（先到者为准），超出如实标注。
 */
fun readSegmentsTool(
    datasets: AbilityDatasets,
    registry: SessionRegistry? = null,
): AgentTool = object : AgentTool {
    override val name = "stragent_read_segments"
    override val description =
        "按段号区间读取文本/混合数据集的正文原文（stragent_import_text 导入的材料）。" + // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            "两个用途：①下钻 summarize_text/generate_report 回执中的「段 N」锚点查看原文；" + // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            "②核对回执中的引用（quote）是否被原文支撑。段号即分页游标，返回 nextFrom 供续读。" + // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            "纯表格数据集报错（请用表格工具）。" // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put("datasetId", buildJsonObject {
                    put("type", "string")
                    put("description", "显式数据集 id（缺省为会话当前数据集）") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                })
                put("from", buildJsonObject {
                    put("type", "integer")
                    put("description", "起始段号（1 起始，含）；缺省为 1") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                })
                put("to", buildJsonObject {
                    put("type", "integer")
                    put("description", "结束段号（含）；缺省 = from + 页大小 - 1") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                })
            },
        )
        put("required", JsonArray(emptyList()))
    }

    override val cacheable = false
    override val readOnly = true

    override fun invoke(args: JsonObject): JsonObject {
        val dataset = resolveDataset(args.string("datasetId"), datasets, registry)
        val content = dataset.text
            ?: throw IllegalArgumentException(
                "数据集「${dataset.name}」没有文本部分（纯表格）：本工具只服务文本/混合数据集的正文段落。", // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            )
        val total = content.segments.size
        val from = (args.int("from") ?: 1).coerceIn(1, total)
        val to = (args.int("to") ?: (from + READ_SEGMENTS_MAX_COUNT - 1)).coerceIn(from, total)
        val selected = content.segments.filter { it.index in from..to }

        // 页预算：段数与字符双上限，先到者为准；截断如实标注并给续读位置
        val rows = mutableListOf<JsonObject>()
        var usedChars = 0
        var lastDelivered = from - 1
        var charsTruncated = false
        for (seg in selected) {
            val entryChars = seg.text.length
            if (rows.isNotEmpty() && (rows.size >= READ_SEGMENTS_MAX_COUNT || usedChars + entryChars > READ_SEGMENTS_MAX_CHARS)) break
            rows += buildJsonObject {
                put("segment", seg.index)
                put("kind", seg.kind.name.lowercase())
                put("text", seg.text)
            }
            usedChars += entryChars
            lastDelivered = seg.index
        }
        val delivered = lastDelivered - from + 1
        // hasMore = 材料里还有没送达的段（to 只是本窗终点；模型未必知道 total，游标要如实指下去）
        val hasMore = lastDelivered < total
        // 因字符预算（而非段数/区间）中断 = 提示「本页在字符预算内截断」
        charsTruncated = hasMore && rows.size < READ_SEGMENTS_MAX_COUNT

        return buildJsonObject {
            put("datasetId", args.string("datasetId") ?: currentPointerId(registry, datasets) ?: "")
            put("name", dataset.name)
            put("kind", if (dataset.rows.isEmpty()) "text" else "mixed")
            put("totalSegments", total)
            put("from", from)
            put("to", to)
            put("delivered", delivered)
            put(
                "segments",
                JsonArray(rows),
            )
            if (hasMore) {
                put("nextFrom", lastDelivered + 1)
                put(
                    "notice",
                    "本页送达段 $from-$lastDelivered" + (if (charsTruncated) "（字符预算内截断）" else "") + // lint:allow zh-first（用户面中文输出，T-095/T-098 语言定位）
                        "；后续还有内容，续读请传 from=${lastDelivered + 1}。", // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                )
            }
        }
    }
}
