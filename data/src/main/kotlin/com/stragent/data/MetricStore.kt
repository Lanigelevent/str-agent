package com.stragent.data

import com.stragent.core.ports.DatasetStore
import com.stragent.core.semantic.MetricCatalog
import com.stragent.core.semantic.MetricDef
import com.stragent.core.semantic.MetricEngine
import com.stragent.core.semantic.MetricTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 指标定义的存储读写（T-055）—— 规则在 `core/semantic`，这里只管 CRUD 与键。
 *
 * 与 [SemanticStore]（`semantic.terms`，文本备忘）并列，键是 `semantic.metrics`：
 * 同样**跨数据集全局**——口径定义不随数据更换丢失；数据本身变了只是「解析不到列」，
 * 定义仍在，缺的是数据而不是口径。
 *
 * 三条写入纪律（冲突在写入时就挡住，而不是等到出报告才发现）：
 *  1. **内置指标不可覆盖**：与 `MetricCatalog.BUILTINS` 同名/别名相交且口径不同 →
 *     拒收并说明冲突；同口径重复定义也不收（避免出现两份同名定义）。
 *  2. **同 id 视为修改**：口径/字段/别名/测试有变化时 version + 1；完全没变则不升版。
 *  3. **有界**：[MAX_USER_METRICS] 上限，指标是口径不是笔记。
 *
 * 未通过 [MetricEngine.validate] 的定义一律拒收（聚合/粒度/测试类型都在这里兜住）。
 */
@Serializable
private data class StoredMetric(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val definition: String = "",
    val expression: String,
    val agg: String = "sum",
    val unit: String = "",
    val dimensions: List<String> = emptyList(),
    val grain: String = "total",
    val tests: List<StoredTest> = emptyList(),
    val version: Int = 1,
    val owner: String = "user",
    val fields: Map<String, List<String>> = emptyMap(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable
private data class StoredTest(
    val kind: String,
    val value: Double? = null,
    val upper: Double? = null,
    val note: String = "",
)

class MetricStore(private val kv: DatasetStore) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 用户定义的全部指标（按创建时间升序；内置指标不在这里，见 MetricCatalog） */
    fun list(): List<MetricDef> =
        loadRaw().sortedBy { it.createdAt }.map { it.toDef() }

    /** 按指标名/别名查找（只在用户定义里找） */
    fun find(name: String): MetricDef? = MetricCatalog.find(name, list())

    /**
     * 新建或修改一条用户指标。
     *
     * @throws IllegalArgumentException 定义非法、与内置/其它用户定义口径冲突、超上限
     * @return 落盘后的定义（同 id 修改时 version 已递增）
     */
    fun upsert(def: MetricDef, now: Long = System.currentTimeMillis()): MetricDef {
        if (def.builtin) throw IllegalArgumentException("内置指标不能通过本入口修改：${def.name}")
        val problems = MetricEngine.validate(def)
        if (problems.isNotEmpty()) throw IllegalArgumentException("指标定义不合法：" + problems.joinToString("；"))

        val stored = loadRaw()
        val existingIndex = stored.indexOfFirst { it.id == def.id }
        val existing = stored.getOrNull(existingIndex)

        // 与「其它定义」比对：内置 + 除自己以外的用户定义
        val others = MetricCatalog.BUILTINS + stored.filter { it.id != def.id }.map { it.toDef() }
        val clash = others.firstOrNull { MetricEngine.sharesName(it, def) }
        if (clash != null) {
            if (MetricEngine.signature(clash) == MetricEngine.signature(def)) {
                throw IllegalArgumentException(
                    "已存在同口径的指标「${clash.name}」（id=${clash.id}）；" +
                        "要改口径请用同一个 id 更新，不要新建同名定义",
                )
            }
            throw IllegalArgumentException(
                "口径冲突：指标「${def.name}」与已有定义「${clash.name}」同名/别名相交但口径不同" +
                    "（${clash.expression} vs ${def.expression}）；" +
                    "请改用不同名称，或用同一个 id 更新原定义",
            )
        }

        if (existing == null) {
            if (stored.size >= MAX_USER_METRICS) {
                throw IllegalArgumentException("用户指标已达上限 $MAX_USER_METRICS 条；请先删除不再使用的定义")
            }
            val created = def.copy(builtin = false).toStored(version = 1, createdAt = now, updatedAt = now)
            save(stored + created)
            return created.toDef()
        }

        val unchanged = existing.toDef().contentForVersion() == def.copy(builtin = false).contentForVersion()
        val updated = def.copy(builtin = false).toStored(
            version = if (unchanged) existing.version else existing.version + 1,
            createdAt = existing.createdAt,
            updatedAt = if (unchanged) existing.updatedAt else now,
        )
        val next = stored.toMutableList()
        next[existingIndex] = updated
        save(next)
        return updated.toDef()
    }

    /** 删一条用户指标；id 不存在返回 false */
    fun remove(id: String): Boolean {
        val stored = loadRaw()
        val next = stored.filter { it.id != id }
        if (next.size == stored.size) return false
        save(next)
        return true
    }

    /** 清空用户指标（内置指标不受影响） */
    fun clear() {
        kv.remove(KEY)
    }

    private fun loadRaw(): List<StoredMetric> {
        val raw = kv.get(KEY) ?: return emptyList()
        return runCatching { json.decodeFromString<List<StoredMetric>>(raw) }.getOrNull() ?: emptyList()
    }

    private fun save(metrics: List<StoredMetric>) {
        kv.put(KEY, json.encodeToString(metrics))
    }

    private fun MetricDef.toStored(version: Int, createdAt: Long, updatedAt: Long) = StoredMetric(
        id = id,
        name = name,
        aliases = aliases,
        definition = definition,
        expression = expression,
        agg = agg,
        unit = unit,
        dimensions = dimensions,
        grain = grain,
        tests = tests.map { StoredTest(it.kind, it.value, it.upper, it.note) },
        version = version,
        owner = owner.ifBlank { "user" },
        fields = fields,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun StoredMetric.toDef() = MetricDef(
        id = id,
        name = name,
        aliases = aliases,
        definition = definition,
        expression = expression,
        agg = agg,
        unit = unit,
        dimensions = dimensions,
        grain = grain,
        tests = tests.map { MetricTest(it.kind, it.value, it.upper, it.note) },
        version = version,
        owner = owner,
        fields = fields,
        builtin = false,
    )

    /** 版本判定口径：影响结果的字段全算，展示类字段（definition/owner）不算 */
    private fun MetricDef.contentForVersion(): String = listOf(
        MetricEngine.signature(this),
        aliases.joinToString(",") { it.trim().lowercase() },
        dimensions.joinToString(","),
        tests.joinToString(";") { "${it.kind}:${it.value}:${it.upper}:${it.note}" },
        fields.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value.joinToString(",")}" },
    ).joinToString("|")

    companion object {
        /** 全局唯一键：指标定义与数据集无关 */
        const val KEY = "semantic.metrics"

        /** 用户指标上限（口径是定义不是笔记，太多了必然互相矛盾） */
        const val MAX_USER_METRICS = 50
    }
}
