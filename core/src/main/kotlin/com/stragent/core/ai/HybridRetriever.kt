package com.stragent.core.ai

import kotlin.math.sqrt

/**
 * 混合检索器（T-100）——BM25 词法 + 嵌入语义的双路召回与 RRF 融合。
 *
 * 行业依据：hybrid（BM25+embedding+RRF）是 2025+ 生产 RAG 的默认形态——BM25 抓精确词
 * （数字/术语/代号），嵌入抓同义改写；单路都有盲区，融合后检索失败率显著下降
 * （Anthropic Contextual Retrieval 数据：hybrid 一项 top-20 失败 5.7%→2.9%）。
 *
 * RRF（Reciprocal Rank Fusion）：score(d) = Σ 1/(K + rank_i(d))——只吃**排名**不吃分值，
 * 天然免调参（BM25 分与余弦分不可比），K=60 为文献标准值。
 *
 * 语义路可选：[vectors] 为 null = 未配置嵌入（退化为 BM25 单路，既有行为零变化）；
 * 嵌入调用失败由调用方回落（诚实降级 + 结构化警告，不在本类静默吞）。
 */
object HybridRetriever {

    /** RRF 常数（文献标准值：小 K 让头部排名主导融合） */
    const val RRF_K = 60

    /** 单路文档：id 透传（用于融合后回填段号），indexText = 已拼上下文前缀的检索文本 */
    data class Doc(val id: String, val segmentIndex: Int, val indexText: String)

    data class Scored(val id: String, val segmentIndex: Int, val score: Double, val sources: List<String>)

    /**
     * 融合检索：BM25 必选；[queryVector]/[docVectors] 任一为 null = 语义路缺席。
     * 返回 RRF 融合分降序（同分按 BM25 名次稳定）；score 为 RRF 分（无绝对语义）。
     */
    fun retrieve(
        docs: List<Doc>,
        query: String,
        queryVector: DoubleArray?,
        docVectors: List<DoubleArray>?,
        k: Int,
    ): List<Scored> = retrieve(docs, query, queryVector, docVectors, k, Bm25Index(docs.map { Bm25Index.Doc(it.id, it.segmentIndex, it.indexText) }))

    /** 同上，但复用调用方预建的 BM25 索引（T-124 索引缓存：同批 docs 跨查询复用，免重分词） */
    fun retrieve(
        docs: List<Doc>,
        query: String,
        queryVector: DoubleArray?,
        docVectors: List<DoubleArray>?,
        k: Int,
        prebuilt: Bm25Index,
    ): List<Scored> {
        val bm25 = prebuilt
        val bm25Rank = bm25.score(query).withIndex().associate { (i, s) -> s.id to i + 1 }
        val vecRank = if (queryVector != null && docVectors != null && docVectors.size == docs.size) {
            val ranked = docs.withIndex()
                .map { (i, d) -> Triple(d.id, cosine(queryVector, docVectors[i]), i) }
                .filter { it.second > 0.0 }
                .sortedByDescending { it.second }
                .mapIndexed { i, t -> t.first to i + 1 }
            ranked.toMap()
        } else {
            null
        }
        val ids = docs.map { it.id }.toSet()
        val scores = HashMap<String, Triple<Double, Int, MutableList<String>>>()
        for (id in ids) {
            var score = 0.0
            val sources = mutableListOf<String>()
            bm25Rank[id]?.let { rank ->
                score += 1.0 / (RRF_K + rank)
                sources += "bm25"
            }
            vecRank?.get(id)?.let { rank ->
                score += 1.0 / (RRF_K + rank)
                sources += "vector"
            }
            if (score > 0) scores[id] = Triple(score, bm25Rank[id] ?: Int.MAX_VALUE, sources)
        }
        val srcById = docs.associate { it.id to it.segmentIndex }
        return scores.entries
            .sortedWith(compareByDescending<Map.Entry<String, Triple<Double, Int, MutableList<String>>>> { it.value.first }.thenBy { it.value.second })
            .take(k.coerceAtLeast(0))
            .map { (id, v) -> Scored(id, srcById[id] ?: 0, v.first, v.third) }
    }

    /** 余弦相似度：零向量/长度不齐返回 0（不抛——单条坏向量不该拖垮整路） */
    fun cosine(a: DoubleArray, b: DoubleArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0.0
        return dot / (sqrt(na) * sqrt(nb))
    }

    /**
     * 多列表 RRF 融合（T-113 查询分解）：对任意个排名列表按 Σ 1/(K+rank) 融合。
     * 与 [retrieve] 的双路融合同常数同纪律：只吃**排名**不吃分值——原始查询的精排分与
     * 子查询的融合分混在一起也安全。sources 取各列表并集；同分按首列表出现名次稳定。
     */
    fun rrfFuse(lists: List<List<Scored>>, k: Int = RRF_K): List<Scored> {
        class Acc(val id: String, var score: Double, var firstRank: Int, val sources: MutableSet<String>, val segmentIndex: Int)
        val acc = LinkedHashMap<String, Acc>()
        for ((li, list) in lists.withIndex()) {
            for ((rank, s) in list.withIndex()) {
                val a = acc.getOrPut(s.id) { Acc(s.id, 0.0, li * 100_000 + rank, LinkedHashSet(s.sources), s.segmentIndex) }
                a.score += 1.0 / (k + rank + 1)
                a.sources += s.sources
            }
        }
        return acc.values
            .sortedWith(compareByDescending<Acc> { it.score }.thenBy { it.firstRank })
            .map { Scored(it.id, it.segmentIndex, it.score, it.sources.toList()) }
    }
}
