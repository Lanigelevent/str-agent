package com.stragent.core.ai

import com.stragent.core.ports.DatasetStore
import com.stragent.core.ports.LlmClient
import java.security.MessageDigest

/**
 * 块级上下文前缀（T-114）——Anthropic Contextual Retrieval 的完整形态。
 *
 * T-096 已落地的「段N 」段号前缀是**零成本结构化变体**；本件补上 LLM 生成的一句
 * 「把该块放回文档全局语境」的补语，拼在索引文本前——BM25 词法与嵌入语义两路同时受益。
 * 行业依据（Anthropic Contextual Retrieval）：单此项即有 35%-49% 的 top-20 检索失败率降幅，
 * 与 hybrid（T-100）、rerank（T-101）、查询分解（T-113）叠加构成检索纵深主干。
 *
 * 纪律：
 * - **索引专用**：上下文只进检索索引文本（indexText），绝不进材料回填与门禁 brief——
 *   生成物的数字不是数据（零编造纪律不因此松动）；
 * - **批量轻调用**：每 5 块一次调用（每块 ≤200 字入提示词），省调用数；
 * - **逐槽诚实**：应答行数不齐时按位对齐、缺失槽位 null（该块索引文本零变化），不硬凑；
 * - **缓存化**：`CachedChunkContextClient` 内容寻址缓存（chunkctx.<sha12(块文本)>），
 *   材料不变时第二次追问的上下文调用 = 0；失败不缓存。
 */
object LlmChunkContextualizer : com.stragent.core.ports.ChunkContextClient {

    /** 每批块数：批越大单次调用越重，5 是省调用数与单批失败爆炸半径的折中 */
    const val BATCH_SIZE = 5

    /** 单条补语上限（字符）：补语是定位语不是摘要，超长说明模型在写作 */
    const val MAX_CTX_CHARS = 80

    /** 入提示词的单块截断（字符）：取块头足以定位全局语境 */
    const val PROMPT_CHUNK_CHARS = 200

    private const val SYSTEM_PROMPT =
        "你是检索上下文补全器。对给定的每个材料块，输出一句把该块放回文档全局语境的补语：" + // lint:allow zh-first（用户面中文输出/提示词，T-095/T-114 语言定位）
        "包含文档主题与该块的角色定位，20-40 字，可用文档中会出现的同义词与规范用语。" + // lint:allow zh-first（用户面中文输出/提示词，T-095/T-114 语言定位）
        "每块一行，行首标块号（如「块1：」），不要解释。" // lint:allow zh-first（用户面中文输出/提示词，T-095/T-114 语言定位）

    /** LLM 输出 → 每块补语：剥块号/项目符，超长截断到上限，行数不齐按位对齐、缺失槽位 null */
    fun parseContextLines(raw: String, expected: Int): List<String?> {
        val cleaned = raw.lines().mapNotNull { s ->
            var x = s.trim()
            if (x.isEmpty()) return@mapNotNull null
            x = x.removePrefix("-").removePrefix("•").trim()
            x = x.replace(Regex("^块[0-9０-９]+[：:]\\s*"), "").trim()
            x = x.replace(Regex("^[0-9０-９]+[.、)）：:]\\s*"), "").trim()
            x.take(MAX_CTX_CHARS).takeIf { it.length >= 2 }
        }
        // 按位对齐：第 i 条有效行 → 第 i 块；行数不足的尾部槽位 null（诚实，不硬凑）
        return List(expected) { i -> cleaned.getOrNull(i) }
    }

    override fun contextsFor(
        llm: LlmClient,
        model: String,
        chunks: List<String>,
        cancelSignal: () -> Boolean,
    ): List<String?> {
        if (chunks.isEmpty()) return emptyList()
        val out = arrayOfNulls<String>(chunks.size)
        // T-129 有界扇出：批间独立，保序归并（取消/失败语义与串行一致）
        val starts = chunks.indices.step(BATCH_SIZE).toList()
        Parallel.mapLimited(starts, cancelSignal = cancelSignal) { start ->
            val end = minOf(start + BATCH_SIZE, chunks.size)
            val group = chunks.subList(start, end)
            val r = chatStep(
                llm, model,
                listOf(
                    "system" to SYSTEM_PROMPT,
                    "user" to group.mapIndexed { i, c ->
                        "块${i + 1}：${c.take(PROMPT_CHUNK_CHARS)}" // lint:allow zh-first（用户面中文输出/提示词，T-095/T-114 语言定位）
                    }.joinToString("\n"),
                ),
                ChatOptions(temperature = 0.2, maxTokens = 60 * group.size + 50),
                cancelSignal = cancelSignal,
            )
            val lines = parseContextLines(r.content, group.size)
            for ((i, line) in lines.withIndex()) out[start + i] = line
            start
        }
        return out.toList()
    }
}

/**
 * 上下文缓存装饰器（T-114）——同一块文本的补语只算一次（与 [CachedEmbeddingClient] 同纪律）。
 * 缓存键 `chunkctx.<sha12(块文本)>` 内容寻址：材料变一个字键就变，无失效问题；
 * 只缓存非空补语（null=解析缺失不缓存，下次重算——不把一次解析失败钉死成永久缺失）；
 * delegate 失败原样上抛（回落决策在调用方）。
 */
class CachedChunkContextClient(
    private val delegate: com.stragent.core.ports.ChunkContextClient,
    private val kv: DatasetStore,
) : com.stragent.core.ports.ChunkContextClient {

    override fun contextsFor(
        llm: LlmClient,
        model: String,
        chunks: List<String>,
        cancelSignal: () -> Boolean,
    ): List<String?> {
        if (chunks.isEmpty()) return emptyList()
        val keys = chunks.map { "chunkctx." + sha12(it) }
        val out = arrayOfNulls<String>(chunks.size)
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
            val fresh = delegate.contextsFor(llm, model, missingIdx.map { chunks[it] }, cancelSignal)
            require(fresh.size == missingIdx.size) { "上下文返回数量不齐（${fresh.size} != ${missingIdx.size}）" } // lint:allow zh-first（用户面中文输出，T-095/T-114 语言定位）
            for ((j, i) in missingIdx.withIndex()) {
                val ctx = fresh[j]
                if (ctx != null) {
                    out[i] = ctx
                    kv.put(keys[i], ctx)
                }
                // null 不缓存也不填——该槽位保持 null（该块索引文本零变化）
            }
        }
        return out.toList()
    }

    private fun sha12(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(12)
}
