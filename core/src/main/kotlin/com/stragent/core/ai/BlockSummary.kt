package com.stragent.core.ai

import com.stragent.core.ports.DatasetStore
import com.stragent.core.ports.LlmClient
import java.security.MessageDigest

/**
 * 块摘要（T-117）——RAPTOR 摘要节点层（T-116 粗层的语义升级）。
 *
 * T-116 的粗层索引文本是**原文拼装**（零成本确定性）；本件补上 LLM 对每块的一段要点
 * 摘要，**替换**原块文本进检索索引——宽泛主题问题在摘要节点上的词法/语义匹配显著优于
 * 原文块（RAPTOR：摘要节点让检索同时覆盖细节层与主题层）。命中的回填单位仍是块成员
 * **原文段**（摘要只负责把查询引到对的块）。
 *
 * 纪律：
 * - **索引专用**：摘要只进检索索引文本（indexText），绝不进材料回填与门禁 brief——
 *   生成物的数字不是数据（零编造纪律不因此松动）；
 * - **逐块单调用**：每块一次轻调用（块 ~4000 字入提示词，maxTokens 500）——不做批内
 *   行对齐（对齐错位会静默串块，宁多花调用数换确定性）；
 * - **逐槽诚实**：某块摘要产出失败/为空 → null → 该块回落原块索引文本（T-116 行为）；
 * - **缓存化**：`CachedBlockSummarizer` 内容寻址缓存（blocksum.<sha12(块文本)>），
 *   材料不变时第二次追问的摘要调用 = 0；失败不缓存。
 */
object LlmBlockSummarizer : com.stragent.core.ports.BlockSummarizer {

    /** 单条摘要上限（字符）：检索摘要不是成文，超长截断（clamp 到索引文本预算内） */
    const val MAX_SUMMARY_CHARS = 400

    /** 递归分组大小（T-120）：上层节点 = 下层 8 个节点的索引文本再摘要（确定性顺序分组） */
    const val LEVEL2_GROUP_SIZE = 8

    private const val SYSTEM_PROMPT =
        "你是检索摘要器。把这份材料块压缩成一段 100-200 字的要点摘要：" + // lint:allow zh-first（用户面中文输出/提示词，T-095/T-117 语言定位）
        "保留关键实体、数字与结论，用材料中会出现的原词，不要评论、不要分点、不要解释。" // lint:allow zh-first（用户面中文输出/提示词，T-095/T-117 语言定位）

    /** 「摘要：」前缀（T-142 热路径纪律：预编译单例） */
    private val SUMMARY_PREFIX = Regex("^摘要[：:]\\s*") // lint:allow zh-first（用户面中文输出/提示词，T-095/T-117 语言定位）

    /** LLM 输出 → 单块摘要：去首尾空白与包裹引号，超长截断，空则 null（该块回落原块索引） */
    fun cleanSummary(raw: String): String? {
        var x = raw.trim().trim('"', '「', '」')
        if (x.isEmpty()) return null
        x = x.replace(SUMMARY_PREFIX, "").trim()
        x = x.take(MAX_SUMMARY_CHARS)
        return x.takeIf { it.length >= 2 }
    }

    override fun summariesFor(
        llm: LlmClient,
        model: String,
        blocks: List<String>,
        cancelSignal: () -> Boolean,
    ): List<String?> {
        if (blocks.isEmpty()) return emptyList()
        val out = arrayOfNulls<String>(blocks.size)
        // T-129 有界扇出：逐块独立，保序归并（取消/失败语义与串行一致）
        Parallel.mapLimited(blocks.indices.toList(), cancelSignal = cancelSignal) { i ->
            val r = chatStep(
                llm, model,
                listOf(
                    "system" to SYSTEM_PROMPT,
                    "user" to blocks[i],
                ),
                ChatOptions(temperature = 0.2, maxTokens = 500),
                cancelSignal = cancelSignal,
            )
            out[i] = cleanSummary(r.content)
            i
        }
        return out.toList()
    }
}

/**
 * 摘要缓存装饰器（T-117）——同一块文本的摘要只算一次（与 [CachedChunkContextClient] 同纪律）。
 * 缓存键 `blocksum.<sha12(块文本)>` 内容寻址：材料变一个字键就变，无失效问题；
 * 只缓存非空摘要（null=产出失败不缓存，下次重算——不把一次失败钉死成永久缺失）；
 * delegate 失败原样上抛（回落决策在调用方）。
 */
class CachedBlockSummarizer(
    private val delegate: com.stragent.core.ports.BlockSummarizer,
    private val kv: DatasetStore,
) : com.stragent.core.ports.BlockSummarizer {

    override fun summariesFor(
        llm: LlmClient,
        model: String,
        blocks: List<String>,
        cancelSignal: () -> Boolean,
    ): List<String?> {
        if (blocks.isEmpty()) return emptyList()
        val keys = blocks.map { "blocksum." + sha12(it) }
        val out = arrayOfNulls<String>(blocks.size)
        val missingIdx = mutableListOf<Int>()
        for ((i, key) in keys.withIndex()) {
            val cached = kv.get(key)
            if (cached == null) {
                missingIdx += i
            } else {
                out[i] = cached
            }
        }
        if (missingIdx.isNotEmpty()) {
            val fresh = delegate.summariesFor(llm, model, missingIdx.map { blocks[it] }, cancelSignal)
            require(fresh.size == missingIdx.size) { "摘要返回数量不齐（${fresh.size} != ${missingIdx.size}）" } // lint:allow zh-first（用户面中文输出，T-095/T-117 语言定位）
            for ((j, i) in missingIdx.withIndex()) {
                val s = fresh[j]
                if (s != null) {
                    out[i] = s
                    kv.put(keys[i], s)
                }
                // null 不缓存也不填——该槽位保持 null（该块回落原块索引文本）
            }
        }
        return out.toList()
    }

    private fun sha12(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(12)
}
