package com.stragent.data

import com.stragent.core.memory.MEMORY_KINDS
import com.stragent.core.memory.MEMORY_MAX_FACTS
import com.stragent.core.memory.MemoryFact
import com.stragent.core.memory.memoryDedupeKey
import com.stragent.core.memory.normalizeMemoryText
import com.stragent.core.ports.DatasetStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 跨会话记忆的存储读写 —— 规则在 `core/memory`，这里只管 CRUD 与「键怎么选」。
 *
 * 键 = `memory.<datasetId>`，而 datasetId 是 csv 内容 sha256 的前 12 位
 * （见 `datasetIdOf`），**换一份数据就换一把键** —— 失效不是「记得清不干净」的问题，
 * 而是从键的选择上就不给串数据的口子（纪律 c）。这也意味着调用方不需要传时间戳、
 * 不需要传指纹，传 datasetId 就够了。
 *
 * 为什么走 kv（DatasetStore）而不是 prefs： prefs 是配置口径的窄存储（FilePrefs 一个
 * 文件全量读写），记忆是**会随会话增长的多条记录** —— 每数据集一整串 JSON 落在
 * FileDatasetStore 的「每键一文件」上，写放大只与本数据集的记忆条数有关，
 * 不会把全仓配置拖进去重写。
 */
@Serializable
private data class StoredFact(
    /** 冗余存一份：读回时要和键对得上（防止手工改盘或键冲突把别的数据集的记忆带进来） */
    val datasetId: String,
    val id: String,
    val kind: String,
    val text: String,
    val source: String,
    val savedAt: Long,
)

class SessionMemoryStore(private val kv: DatasetStore) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun keyFor(datasetId: String) = PREFIX + datasetId

    /**
     * 读一个数据集的记忆（新的在前）。
     *
     * 三种情况一律返回空表而不是抛：键不存在、JSON 解析失败（盘被手改过 / 版本不兼容）、
     * **载荷里的 datasetId 与键不一致**（内容对不上，采信就是把别的记忆当成本数据集的）。
     * 记忆缺失只是少一段背景，采信用错就是拿别人的结论答题。
     */
    fun recall(datasetId: String): List<MemoryFact> {
        if (datasetId.isBlank()) return emptyList()
        val raw = kv.get(keyFor(datasetId)) ?: return emptyList()
        val stored = runCatching { json.decodeFromString<List<StoredFact>>(raw) }.getOrNull()
            ?: return emptyList()
        if (stored.any { it.datasetId != datasetId }) return emptyList()
        return stored
            .sortedWith(compareByDescending<StoredFact> { it.savedAt }.thenByDescending { it.id })
            .map { MemoryFact(it.id, it.kind, it.text, it.source, it.savedAt) }
    }

    /**
     * 记一条会话产出。四条拒收（与 core 规则一致，规则只在一处定义）：
     * ① datasetId 空；② kind 不在白名单；③ 正文归一后为空；④ 与已有某条**同文**
     * （同一句话存两遍，注入时模型就看到两遍强调，等于自己给自己加权重）。
     * 归一后的文本才是入库的那一份（截断与折叠空白在 core 定死）。
     */
    fun remember(
        datasetId: String,
        kind: String,
        text: String,
        source: String = "mcp",
        savedAt: Long = System.currentTimeMillis(),
    ): Boolean {
        if (datasetId.isBlank()) return false
        if (kind !in MEMORY_KINDS) return false
        val normalized = normalizeMemoryText(text) ?: return false
        val notes = loadRaw(datasetId)
        val key = memoryDedupeKey(normalized)
        if (notes.any { memoryDedupeKey(it.text) == key }) return false
        val next = (notes + StoredFact(datasetId, newId(), kind, normalized, source, savedAt))
            .sortedWith(compareByDescending<StoredFact> { it.savedAt }.thenByDescending { it.id })
            .take(MEMORY_MAX_FACTS)
        return save(datasetId, next)
    }

    /** 删一条。id 不存在返回 false（不能让调用方拿着「已删除」的假成功去提示用户） */
    fun forget(datasetId: String, id: String): Boolean {
        val notes = loadRaw(datasetId)
        val next = notes.filter { it.id != id }
        if (next.size == notes.size) return false
        return save(datasetId, next)
    }

    /** 清空一个数据集的记忆（用户说「这份数据的记忆都忘掉」；无记录时也算成功） */
    fun clear(datasetId: String): Boolean {
        if (datasetId.isBlank()) return false
        kv.remove(keyFor(datasetId))
        return true
    }

    /** 只在键确实存在且能解析时返回载荷；任何异常形态都当「没有记忆」 */
    private fun loadRaw(datasetId: String): List<StoredFact> {
        val raw = kv.get(keyFor(datasetId)) ?: return emptyList()
        val stored = runCatching { json.decodeFromString<List<StoredFact>>(raw) }.getOrNull()
            ?: return emptyList()
        return stored.filter { it.datasetId == datasetId }
    }

    private fun save(datasetId: String, facts: List<StoredFact>): Boolean = runCatching {
        kv.put(keyFor(datasetId), json.encodeToString(facts))
    }.isSuccess

    companion object {
        /** 与 project.reports.&lt;id&gt; / caliber.&lt;id&gt; 同一分层口径：按数据集单独存 */
        const val PREFIX = "memory."
    }
}
