package com.stragent.host.eval

/** Cohen's κ（无序三分类）—— 评测口径，不处理加权与多评审。 */
object CohenKappa {

    fun compute(a: List<String>, b: List<String>): Double {
        require(a.size == b.size) { "两组标签长度必须一致：${a.size} vs ${b.size}" }
        require(a.isNotEmpty()) { "标签不能为空" }
        val n = a.size
        val observed = a.indices.count { a[it] == b[it] }.toDouble() / n
        val categories = (a + b).distinct()
        val expected = categories.sumOf { category ->
            val pa = a.count { it == category }.toDouble() / n
            val pb = b.count { it == category }.toDouble() / n
            pa * pb
        }
        return if (expected >= 1.0) {
            if (observed >= 1.0) 1.0 else 0.0
        } else {
            (observed - expected) / (1.0 - expected)
        }
    }
}
