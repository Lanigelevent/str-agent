package com.stragent.core.security

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertTrue

/**
 * 注入语料库加载器（T-140）。
 *
 * 语料源：`core/src/test/resources/injection-corpus.json`。
 *
 * ## 为什么把语料外置成资源文件
 *
 * 1. **一处定义、多处复用**：规则单测、净化单测（Wave 3）、未来的模糊测试共用同一份期望——
 *    避免「规则侧认得中文、净化侧拦不住中文」这种**两边各有一份语料**的裂缝
 *    （T-140 审查实测：8/14 中文载荷命中规则却被预筛放行）；
 * 2. **不可见字符必须可见**：语料里的零宽字符（U+200B 等）本身就是攻击手段。
 *    在 JSON 里它们存成**字面文本** `\u200b`（而不是 JSON 的 `\u200b` 转义），
 *    这样 review 时肉眼能看见、diff 时不会静默消失；由本加载器还原成真字符。
 * 3. **可被外部工具消费**：JSON 语料库让「语料完整性」能被 check-docs 之类的门禁核对。
 *
 * ## 语料库的两个区
 *
 * - [adversarial]：按规则 id 分组的对抗集——每条**必须**命中该规则；
 * - [clean]：真实业务文本——命中即误报（误报会污染用户对信号字的信任）。
 */
object InjectionCorpus {

    private val json = Json { ignoreUnknownKeys = true }

    /** 对抗集：规则 id → 该规则的载荷列表 */
    val adversarial: Map<String, List<String>> by lazy {
        val root = load()
        val adv = root["adversarial"]?.jsonObject ?: error("语料库缺 adversarial 区")
        adv.mapValues { (_, arr) -> arr.jsonArray.map { decodeInvisible(it.jsonPrimitive.content) } }
    }

    /** 干净集：真实业务文本，任何一条命中即误报 */
    val clean: List<String> by lazy {
        val root = load()
        root["clean"]?.jsonArray?.map { decodeInvisible(it.jsonPrimitive.content) } ?: emptyList()
    }

    /** 全部对抗载荷（打平），用于「逐条过净化」这类全量断言 */
    val allAdversarial: List<Pair<String, String>>
        get() = adversarial.flatMap { (rule, texts) -> texts.map { rule to it } }

    private fun load(): JsonObject =
        json.parseToJsonElement(readResource()).jsonObject

    private fun readResource(): String {
        // 加载方式沿用仓内已验证的惯用法（data/AbilityGoldenTest、host/AssistantBackendStub）：
        // **classLoader.getResource*（不带前导斜杠）**。
        val text = InjectionCorpus::class.java.classLoader
            .getResourceAsStream(RESOURCE)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
        return text ?: error("找不到语料库资源：$RESOURCE")
    }

    /**
     * 把字面文本 `\uXXXX` 还原成真字符（语料库里不可见字符的存储形态）。
     *
     * 只处理 `\uXXXX`——语料里没有需要保留的字面反斜杠，
     * 所以不引入 `\\` 的二次转义语义（少一套规则，少一处出错的地方）。
     */
    private fun decodeInvisible(text: String): String =
        UNICODE_ESCAPE.replace(text) { m ->
            m.groupValues[1].toInt(16).toChar().toString()
        }

    private val UNICODE_ESCAPE = Regex("\\\\u([0-9a-fA-F]{4})")

    private const val RESOURCE = "injection-corpus.json"
}

/** 语料库完整性断言的公共部分：结构、条数、规则覆盖 */
internal fun assertCorpusShape(adversarial: Map<String, List<String>>, clean: List<String>) {
    val expectedRules = (1..8).map { "R$it" }.toSet()
    assertTrue(
        adversarial.keys == expectedRules,
        "语料库必须覆盖 8 条规则，实得 ${adversarial.keys.sorted()}",
    )
    val total = adversarial.values.sumOf { it.size }
    assertTrue(total >= 30, "对抗语料至少 30 例，实为 $total")
    adversarial.forEach { (rule, texts) ->
        assertTrue(texts.size >= 3, "$rule 至少 3 例，实为 ${texts.size}")
    }
    assertTrue(clean.size >= 30, "干净语料至少 30 例，实为 ${clean.size}")
    assertTrue(clean.none { it.isBlank() }, "干净语料不许有空串")
}
