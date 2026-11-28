package com.stragent.core.ai

/**
 * 标准 Okapi BM25 评分器（T-096 追问检索的确定性基线）。
 *
 * 行业依据：BM25 在领域语料（金融文档）上可击败 dense 检索（arXiv 2604.01733）——
 * 零模型依赖、可解释、可重放；语义检索（嵌入+hybrid RRF）是后续演进路径，不是本版范围。
 *
 * 分词：中文按 **CJK bigram**（连续汉字两两成组，Lucene CJKAnalyzer 同款零依赖做法），
 * ASCII 连续字母/数字段为整词——中英混排、表格投影里的数字均覆盖；完全确定性。
 *
 * 上下文前缀（Anthropic Contextual Retrieval 的结构化零成本变体）：调用方把
 * 「文档名·章节路径·块号」拼进 indexText 再传入——前缀词参与检索，块的可检索性提升。
 */
class Bm25Index(
    docs: List<Doc>,
    private val k1: Double = 1.5,
    private val b: Double = 0.75,
) {
    /** 一条可检索文档（子块）：id 透传给调用方（用于父段回填），indexText=已拼好上下文前缀的全文 */
    data class Doc(val id: String, val segmentIndex: Int, val indexText: String)

    data class Scored(val id: String, val segmentIndex: Int, val score: Double)

    private data class Posting(var df: Int, val tfByDoc: MutableMap<Int, Int>)

    private val postings = HashMap<String, Posting>()
    private val docLens = DoubleArray(docs.size)
    private val ids = Array(docs.size) { "" }
    private val segIndexes = IntArray(docs.size)
    private var avgLen = 0.0

    init {
        for ((i, d) in docs.withIndex()) {
            ids[i] = d.id
            segIndexes[i] = d.segmentIndex
            val terms = tokenize(d.indexText)
            docLens[i] = terms.size.toDouble()
            for ((term, tf) in terms.groupingBy { it }.eachCount()) {
                postings.getOrPut(term) { Posting(0, HashMap()) }.let { p ->
                    p.df += 1
                    p.tfByDoc[i] = tf
                }
            }
        }
        avgLen = if (docs.isEmpty()) 0.0 else docLens.average()
    }

    /** 查询评分：返回 score>0 的文档，按分数降序（同分按文档序稳定）。n = 语料文档数 */
    fun score(query: String): List<Scored> {
        val n = ids.size
        if (n == 0) return emptyList()
        val queryTerms = tokenize(query).distinct()
        val scores = DoubleArray(n)
        for (term in queryTerms) {
            val p = postings[term] ?: continue
            val idf = kotlin.math.ln((n - p.df + 0.5).coerceAtLeast(0.5) / (p.df + 0.5) + 1.0)
            for ((docIdx, tf) in p.tfByDoc) {
                val len = docLens[docIdx]
                val norm = if (avgLen > 0) (1 - b + b * len / avgLen) else 1.0
                scores[docIdx] += idf * (tf * (k1 + 1)) / (tf + k1 * norm)
            }
        }
        return scores.withIndex()
            .filter { it.value > 0 }
            .sortedWith(compareByDescending<IndexedValue<Double>> { it.value }.thenBy { it.index })
            .map { Scored(ids[it.index], segIndexes[it.index], it.value) }
    }

    /** top-K 便捷方法 */
    fun topK(query: String, k: Int): List<Scored> = score(query).take(k.coerceAtLeast(0))

    companion object {
        /**
         * 分词：ASCII 连续字母/数字段为整词（小写化）；连续 CJK 按字符 bigram 成组；
         * 其余字符（标点/空白）为分隔符。确定性、无状态。
         */
        fun tokenize(text: String): List<String> {
            val out = mutableListOf<String>()
            val word = StringBuilder()
            val chars = text.toCharArray()
            var i = 0
            fun flushWord() {
                if (word.isNotEmpty()) {
                    out += word.toString().lowercase()
                    word.setLength(0)
                }
            }
            while (i < chars.size) {
                val c = chars[i]
                if (c.code < 128 && c.isLetterOrDigit()) {
                    word.append(c)
                    i++
                    continue
                }
                flushWord()
                if (isCjk(c)) {
                    // 滑动 bigram：相邻两字成组（Lucene CJKAnalyzer 同款），保证查询词跨组命中
                    if (i + 1 < chars.size && isCjk(chars[i + 1])) {
                        out += "${c}${chars[i + 1]}"
                    }
                    i++
                    continue
                }
                i++
            }
            flushWord()
            return out
        }

        private fun isCjk(c: Char): Boolean = c.code >= 0x4E00 && c.code <= 0x9FFF
    }
}
