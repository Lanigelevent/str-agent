package com.stragent.data

import com.stragent.core.ports.DatasetStore
import com.stragent.core.semantic.SEMANTIC_MAX_DEF_CHARS
import com.stragent.core.semantic.SEMANTIC_MAX_TERMS
import com.stragent.core.semantic.SemanticTerm
import com.stragent.core.semantic.TermCandidate
import com.stragent.core.semantic.isMoreSpecific
import com.stragent.core.semantic.normalizeTermName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 语义层的存储读写 —— 规则在 `core/semantic`，这里只管 CRUD 与「键怎么选」。
 *
 * 与 [CaliberStore]（按 projectId 存）和 [SessionMemoryStore]（按 datasetId 存）不同，
 * 语义层是**跨数据集全局**的——键只有一个：`semantic.terms`。
 * 这正是「换 CSV 不丢语义」的实现基础（S-W7 的核心收口点）。
 *
 * 版本门控（简化版 EvoOntology 模式）：
 *  - 新术语直接入库（version=1）；
 *  - 已有术语收到新定义时，[isMoreSpecific] 判定通过才更新（version+1）；
 *  - 已有术语收到约束时，约束不重复才追加（version+1，定义不变）。
 * 旧版本不保留（简化：只存最新版），但 version 字段让调用方知道这条被改过几轮。
 */
@Serializable
private data class StoredTerm(
    val id: String,
    val name: String,
    val aliases: List<String>,
    val definition: String,
    val unit: String?,
    val constraints: List<String>,
    val source: String,
    val version: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

class SemanticStore(private val kv: DatasetStore) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 读全部术语（按 updatedAt 降序，新的在前） */
    fun listTerms(): List<SemanticTerm> =
        loadRaw().sortedByDescending { it.updatedAt }
            .map { it.toTerm() }

    /** 按术语名归一查找 */
    fun findTerm(name: String): SemanticTerm? {
        val key = normalizeTermName(name)
        return loadRaw().find { normalizeTermName(it.name) == key }?.toTerm()
    }

    /**
     * 添加或更新一条术语。
     *
     * 行为：
     *  - 术语不存在：新建（version=1）；
     *  - 术语存在 + 定义类候选 + [isMoreSpecific]：更新定义（version+1）；
     *  - 术语存在 + 约束类候选 + 约束不重复：追加约束（version+1）；
     *  - 术语存在 + 候选不够具体 / 约束重复：不更新（返回 false）。
     *
     * 拒收：name 空、text 空、超长、术语总数超上限（新增时）。
     */
    fun upsert(candidate: TermCandidate, source: String = "auto"): Boolean {
        val normalName = candidate.name.trim()
        if (normalName.isEmpty()) return false
        if (candidate.text.length > SEMANTIC_MAX_DEF_CHARS) return false

        val terms = loadRaw()
        val existingIdx = terms.indexOfFirst { normalizeTermName(it.name) == normalizeTermName(normalName) }

        if (existingIdx == -1) {
            // 新术语
            if (terms.size >= SEMANTIC_MAX_TERMS) return false
            val now = System.currentTimeMillis()
            val newTerm = StoredTerm(
                id = newId(),
                name = normalName,
                aliases = emptyList(),
                definition = if (candidate.kind == "definition") candidate.text else "",
                unit = null,
                constraints = if (candidate.kind == "constraint") listOf(candidate.text) else emptyList(),
                source = source,
                version = 1,
                createdAt = now,
                updatedAt = now,
            )
            return save(terms + newTerm)
        }

        val existing = terms[existingIdx]
        if (!isMoreSpecific(candidate, existing.toTerm())) return false

        val updated = if (candidate.kind == "constraint") {
            // 追加约束（去重）
            val normalConstraint = candidate.text.trim().lowercase()
            if (existing.constraints.any { it.trim().lowercase() == normalConstraint }) return false
            existing.copy(
                constraints = existing.constraints + candidate.text,
                version = existing.version + 1,
                updatedAt = System.currentTimeMillis(),
            )
        } else {
            // 更新定义
            existing.copy(
                definition = candidate.text,
                version = existing.version + 1,
                updatedAt = System.currentTimeMillis(),
            )
        }
        val next = terms.toMutableList()
        next[existingIdx] = updated
        return save(next)
    }

    /**
     * 手动添加一条完整术语（不走候选抽取，直接存结构化数据）。
     * 用于宿主或外部 agent 显式定义术语。
     */
    fun addTerm(
        name: String,
        aliases: List<String> = emptyList(),
        definition: String = "",
        unit: String? = null,
        constraints: List<String> = emptyList(),
        source: String = "manual",
    ): Boolean {
        val normalName = name.trim()
        if (normalName.isEmpty()) return false
        if (definition.isNotEmpty() && definition.length > SEMANTIC_MAX_DEF_CHARS) return false

        val terms = loadRaw()
        // 已存在同名术语：不覆盖（手动添加与自动抽取不冲突，先到先得）
        if (terms.any { normalizeTermName(it.name) == normalizeTermName(normalName) }) return false
        if (terms.size >= SEMANTIC_MAX_TERMS) return false

        val now = System.currentTimeMillis()
        val newTerm = StoredTerm(
            id = newId(),
            name = normalName,
            aliases = aliases,
            definition = definition,
            unit = unit,
            constraints = constraints,
            source = source,
            version = 1,
            createdAt = now,
            updatedAt = now,
        )
        return save(terms + newTerm)
    }

    /** 给已有术语加别名 */
    fun addAlias(name: String, alias: String): Boolean {
        val terms = loadRaw()
        val idx = terms.indexOfFirst { normalizeTermName(it.name) == normalizeTermName(name) }
        if (idx == -1) return false
        val existing = terms[idx]
        val normalAlias = alias.trim()
        if (normalAlias.isEmpty()) return false
        if (existing.aliases.any { normalizeTermName(it) == normalizeTermName(normalAlias) }) return false
        val updated = existing.copy(
            aliases = existing.aliases + normalAlias,
            version = existing.version + 1,
            updatedAt = System.currentTimeMillis(),
        )
        val next = terms.toMutableList()
        next[idx] = updated
        return save(next)
    }

    /** 删一条术语。id 不存在返回 false */
    fun removeTerm(id: String): Boolean {
        val terms = loadRaw()
        val next = terms.filter { it.id != id }
        if (next.size == terms.size) return false
        return save(next)
    }

    /** 清空全部语义层（用户说「所有术语都忘掉」） */
    fun clear(): Boolean {
        kv.remove(KEY)
        return true
    }

    private fun loadRaw(): List<StoredTerm> {
        val raw = kv.get(KEY) ?: return emptyList()
        return runCatching { json.decodeFromString<List<StoredTerm>>(raw) }.getOrNull() ?: emptyList()
    }

    private fun save(terms: List<StoredTerm>): Boolean = runCatching {
        kv.put(KEY, json.encodeToString(terms))
    }.isSuccess

    private fun StoredTerm.toTerm() = SemanticTerm(
        id, name, aliases, definition, unit, constraints, source, version, createdAt, updatedAt,
    )

    companion object {
        /** 全局唯一键：语义层不分数据集 */
        const val KEY = "semantic.terms"
    }
}
