package com.stragent.core.ai

import com.stragent.core.ports.DatasetStore
import com.stragent.core.ports.EmbeddingClient
import java.security.MessageDigest

/**
 * 嵌入缓存装饰器（T-101）——同一文本的向量只算一次。
 *
 * 动机：追问的检索按「材料段落」嵌入，材料不变时段落嵌入不变——每次追问重复计费
 * 是纯浪费（行业 memoization 惯例，与 T-099 块要点缓存同纪律）。缓存键 = `embed.<sha12(文本)>`，
 * 内容寻址：材料变一个字键就变，无失效问题。
 *
 * 批量合并：先整批查缓存，未命中的子集一次调 delegate（不打散成单条），写回后按
 * 原序拼装——第二次同材料追问的嵌入调用次数 = 0。delegate 失败原样上抛（不缓存失败，
 * 不静默降级——回落决策在调用方）。
 */
class CachedEmbeddingClient(
    private val delegate: EmbeddingClient,
    private val kv: DatasetStore,
) : EmbeddingClient {

    override fun embed(texts: List<String>): List<DoubleArray> {
        if (texts.isEmpty()) return emptyList()
        val keys = texts.map { "embed." + sha12(it) }
        val results = arrayOfNulls<DoubleArray>(texts.size)
        val missingIdx = mutableListOf<Int>()
        for ((i, key) in keys.withIndex()) {
            val cached = kv.get(key)
            if (cached != null) {
                parse(cached)?.let { results[i] = it }
            }
            if (results[i] == null) missingIdx += i
        }
        if (missingIdx.isNotEmpty()) {
            val fresh = delegate.embed(missingIdx.map { texts[it] })
            require(fresh.size == missingIdx.size) { "嵌入返回数量不齐（${fresh.size} != ${missingIdx.size}）" } // lint:allow zh-first（用户面中文输出，T-095/T-101 语言定位）
            for ((j, i) in missingIdx.withIndex()) {
                results[i] = fresh[j]
                kv.put(keys[i], encode(fresh[j]))
            }
        }
        return results.map { it!! }
    }

    private fun encode(v: DoubleArray): String =
        v.joinToString(",") { it.toString() }

    private fun parse(s: String): DoubleArray? = runCatching {
        s.split(",").map { it.toDouble() }.toDoubleArray()
    }.getOrNull()

    private fun sha12(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(12)
}
