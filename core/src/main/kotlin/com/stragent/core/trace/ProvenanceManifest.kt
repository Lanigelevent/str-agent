package com.stragent.core.trace

import com.stragent.core.dataset.Dataset
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 溯源清单（Provenance Manifest）—— D10 §阶段2 的核心协议雏形。
 *
 * 为什么现在做这个（而不是等沙箱）：
 *   D10 §5.1 说得很直接 ——「凡是要进报告的数字，必须有一条能**重放复核**的来路」。
 *   这条纪律**与能不能跑任意代码无关**：策展工具的分析链（import → profile → aggregate → report）
 *   本来就是确定性的，把它记成清单，就能回答三个真问题：
 *   ①这份结论是用**哪份数据**得出的（datasetHash）；
 *   ②中间**调了哪些工具、传了什么参数**（steps）；
 *   ③**重放一遍**结果是否一致（resultHash 比对）。
 *
 * 沙箱（阶段 2 后半段）接入后，同一套 manifest 协议直接覆盖「模型写的代码」——
 * 届时 `env` / `deps` / `seed` 字段才有意义；在那之前它们留空即可，**协议不变**。
 *
 * 关键纪律：清单里存的是**参数与哈希**，不是「结论」——
 * 哈希用来判断「有没有被改过」，重放才用来判断「还对不对」。
 */
data class ProvenanceStep(
    /** 工具名（如 `group_aggregate`） */
    val tool: String,
    /** 实际入参（重放时回灌；T-046 起凭据/SQL 字面量已脱敏，原文哈希见 [sqlHash]） */
    val args: JsonObject,
    /** 入参哈希（快速判断是否被改动） */
    val argsHash: String,
    /** 结果哈希（重放时逐字节比对） */
    val resultHash: String,
    /**
     * **调用发生那一刻**的数据集哈希。
     *
     * 为什么必须记在每一步上，而不是清单生成时算一次：
     *   清单是**事后**生成的 —— 若那时才算哈希，数据早就被换掉的话也照样「一致」，
     *   「拿旧数据说新话」就检测不出来。所以哈希必须在**调用时**捕获。
     */
    val datasetHash: String,
    /**
     * **调用发生那一刻**的数据时点（T-205，epoch millis；数据集导入时间 best-effort 标注）。
     * null = 旧清单或调用时数据集不可达。时效**标注**非有效性判断：结论基于该时点导入的数据，
     * 数据在真实世界是否仍然有效不由本协议判断（document.ai 时效机制的最小诚实版）。
     */
    val datasetAsOf: Long? = null,
    /** 耗时（毫秒） */
    val elapsedMs: Long,
    /**
     * 事件所属 MCP 会话（T-046）：并发会话的分析链不会互相串账，
     * 也方便按会话核查「谁在什么时候查了什么结构」。
     */
    val sessionId: String? = null,
    /**
     * 原始 SQL 的哈希（T-046）：审计只存 [sqlMasked] 的结构，原文只在哈希里可比对；
     * 同一语句重放/复核用哈希判定一致性，不把业务数据抄进清单。
     */
    val sqlHash: String? = null,
    /** 脱敏后的 SQL（字面量与注释已替换，结构可读） */
    val sqlMasked: String? = null,
    /**
     * **数值面**（T-073）：该步骤的入参与结果里出现过的去重归一数字 token（上限 64，插入序）。
     *
     * 为什么清单里要有它：追问回执的 `provenanceRef` 要把「回答引用的数字」归因到
     * 「这个数字在哪个已留痕步骤里出现过」。没有结果值就没法归因——全量存结果又违反
     * 「清单存参数与哈希」的纪律。数值面是两者的折中：**只取数字 token**（不含文本/结构），
     * 有界（64 个）、可审计（stragent_provenance 的 steps 里原样可见）。
     * null = 旧清单（T-073 之前记录的步骤）——归因时如实说「该步骤无数值面，无法匹配」。
     */
    val numTokens: List<String>? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("tool", tool)
        put("args", args)
        put("argsHash", argsHash)
        put("resultHash", resultHash)
        put("datasetHash", datasetHash)
        // 可选字段缺省不写：保持老清单的字节形制（T-046 前生成的记录无需迁移）
        datasetAsOf?.let { put("datasetAsOf", it) }
        put("elapsedMs", elapsedMs)
        sessionId?.let { put("sessionId", it) }
        sqlHash?.let { put("sqlHash", it) }
        sqlMasked?.let { put("sqlMasked", it) }
        numTokens?.let { put("numTokens", JsonArray(it.map { t -> JsonPrimitive(t) })) }
    }

    companion object {
        fun fromJson(obj: JsonObject): ProvenanceStep = ProvenanceStep(
            tool = obj["tool"]?.jsonPrimitive?.content ?: "",
            args = obj["args"]?.jsonObject ?: JsonObject(emptyMap()),
            argsHash = obj["argsHash"]?.jsonPrimitive?.content ?: "",
            resultHash = obj["resultHash"]?.jsonPrimitive?.content ?: "",
            datasetHash = obj["datasetHash"]?.jsonPrimitive?.content ?: "",
            datasetAsOf = obj["datasetAsOf"]?.jsonPrimitive?.content?.toLongOrNull(),
            elapsedMs = obj["elapsedMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            sessionId = obj["sessionId"].textOrNull(),
            sqlHash = obj["sqlHash"].textOrNull(),
            sqlMasked = obj["sqlMasked"].textOrNull(),
            numTokens = (obj["numTokens"] as? JsonArray)?.mapNotNull { el ->
                (el as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            },
        )

        /** JSON null 与缺省同义（老清单）；只取真正的字符串值 */
        private fun JsonElement?.textOrNull(): String? =
            (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    }
}

/**
 * 一份分析的溯源清单。
 *
 * @param datasetId   数据集句柄（会话内的 datasetId）
 * @param datasetHash 数据集内容的哈希 —— 换了数据，哈希就变，旧结论自动失效
 * @param steps       分析链上的工具调用（按发生顺序）
 * @param engine      产出引擎标识（str-agent / 插件 id …）
 * @param createdAt   生成时间
 */
data class ProvenanceManifest(
    val datasetId: String,
    /** 数据集哈希（取自**第一步调用时**捕获的值，不是清单生成时的当前值） */
    val datasetHash: String,
    val steps: List<ProvenanceStep>,
    val engine: String = "str-agent",
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** 分析过程中数据是否一直一致（各步捕获的哈希是否相同） */
    val datasetStable: Boolean get() = steps.map { it.datasetHash }.distinct().size <= 1

    fun toJson(): JsonObject = buildJsonObject {
        put("datasetId", datasetId)
        put("datasetHash", datasetHash)
        put("datasetStable", datasetStable)
        put("engine", engine)
        put("createdAt", createdAt)
        put("stepCount", steps.size)
        // T-205：清单级数据时点（派生自第一步调用时捕获值；无任何步骤带时点则缺省不写）
        steps.firstNotNullOfOrNull { it.datasetAsOf }?.let { put("datasetAsOf", it) }
        put(
            "steps",
            JsonArray(steps.map { it.toJson() }),
        )
    }

    companion object {
        fun fromJson(obj: JsonObject): ProvenanceManifest = ProvenanceManifest(
            datasetId = obj["datasetId"]?.jsonPrimitive?.content ?: "",
            datasetHash = obj["datasetHash"]?.jsonPrimitive?.content ?: "",
            steps = (obj["steps"] as? JsonArray)?.map { ProvenanceStep.fromJson(it.jsonObject) } ?: emptyList(),
            engine = obj["engine"]?.jsonPrimitive?.content ?: "str-agent",
            createdAt = obj["createdAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
        )

        /** 内容哈希：sha256 前 16 位（够用、可读、够防「顺手改一下」） */
        fun hashOf(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(16)
        }

        /** JSON 哈希（对稳定的键序序列化，避免同内容不同序导致哈希漂移） */
        fun hashOf(element: JsonElement): String = hashOf(canonical(element))

        /**
         * 数据集哈希：列名 + 全部行（确定性序列化）。
         *
         * 只覆盖**参与分析的数据**（列 + 值），不覆盖会话元信息 ——
         * 目的是「同一份数据 = 同一个哈希」，这样清单才能在跨进程/跨时间比对。
         */
        fun hashOfDataset(dataset: Dataset): String {
            val builder = StringBuilder()
            builder.append(dataset.columns.joinToString(""))
            for (row in dataset.rows) {
                builder.append('').append(row.joinToString(""))
            }
            return hashOf(builder.toString())
        }

        /** 稳定序列化：对象按键名排序后输出 */
        private fun canonical(element: JsonElement): String = when (element) {
            is JsonObject -> element.entries.sortedBy { it.key }.joinToString(
                prefix = "{",
                postfix = "}",
                separator = ",",
            ) { (key, value) -> canonical(value) }

            is JsonArray -> element.joinToString(prefix = "[", postfix = "]", separator = ",") { canonical(it) }
            is JsonPrimitive -> if (element.isString) "\"${element.content}\"" else element.content
            else -> element.toString()
        }
    }
}

// ---------- 数值面与追问归因（T-073） ----------

/**
 * 数值面单步上限：超过即截断（插入序，先到先得）——清单不复制全量结果
 */
private const val NUM_TOKENS_CAP = 64

/**
 * 单 token 上限（字符）：防畸形长数字挤占预算
 */
private const val NUM_TOKEN_MAX_CHARS = 24

/**
 * 步骤的**数值面**：一段 JSON（入参或结果）里出现过的去重归一数字 token。
 *
 * 归一规则（与追问归因同一套，写在明处）：
 *   - 只取 ASCII 数字 token（`[+-]?\d[\d,]*(\.\d+)?`），中文数字不进数值面（简报门禁已覆盖其语义）；
 *   - 千分位逗号剥掉；小数末尾零归一（`240.0` → `240`）；
 *   - 百分号后缀剥掉（`35.2%` 与结果里的 `35.2` 同 token——形式不同、数值同源）；
 *   - token 超 [NUM_TOKEN_MAX_CHARS] 字符丢弃（畸形长串当不了归因锚点）。
 *
 * 诚实边界：这是**字面 token 对拍**，不做语义等价——四舍五入差（37000 vs 3.7万）、
 * 百分比小数形式（0.352）不匹配；未命中时回执如实说明，不装作归因成功。
 */
fun numericSurface(vararg elements: JsonElement?): List<String> {
    val token = Regex("[+-]?\\d[\\d,]*(?:\\.\\d+)?")
    val out = LinkedHashSet<String>()
    fun walk(element: JsonElement?) {
        when (element) {
            null -> {}
            is JsonPrimitive -> {
                val text = element.content
                for (m in token.findAll(text)) {
                    var t = m.value.replace(",", "").trimEnd('%')
                    // 小数末尾零归一：240.0 → 240；保留正负号
                    if (t.contains('.')) t = t.trimEnd('0').trimEnd('.')
                    if (t == "" || t == "+" || t == "-") continue
                    if (t.length > NUM_TOKEN_MAX_CHARS) continue
                    out.add(t)
                }
            }
            is JsonObject -> element.values.forEach { walk(it) }
            is JsonArray -> element.forEach { walk(it) }
            else -> {}
        }
    }
    elements.forEach { walk(it) }
    return out.take(NUM_TOKENS_CAP).toList()
}

/**
 * 追问归因（T-073，D21 F-2）：回答引用的数字 ↔ 已留痕步骤的数值面对拍。
 *
 * 返回：`(步骤下标, 命中的数字 token)` 列表——下标就是 `stragent_provenance` 返回的
 * steps 数组位置（0 起，进程内 append-only，稳定）。没有任何步骤命中 → 空表，
 * 调用方（宿主回执）必须如实说明「数字来自简报的确定性计算，未命中留痕步骤」。
 *
 * 对 `numTokens = null` 的旧步骤：视为「无数值面可对拍」，不命中也不报错。
 */
fun List<ProvenanceStep>.stepIndicesMentioning(numbers: Collection<String>): List<Pair<Int, List<String>>> {
    if (numbers.isEmpty()) return emptyList()
    val wanted = numbers.toSet()
    val hits = mutableListOf<Pair<Int, List<String>>>()
    forEachIndexed { index, step ->
        val surface = step.numTokens ?: return@forEachIndexed
        val matched = surface.filter { it in wanted }
        if (matched.isNotEmpty()) hits.add(index to matched)
    }
    return hits
}
