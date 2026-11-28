package com.stragent.data

import com.stragent.core.caliber.CALIBER_MAX_NOTE_CHARS
import com.stragent.core.caliber.CALIBER_MAX_NOTES
import com.stragent.core.caliber.CaliberNote
import com.stragent.core.ports.PrefsStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 口径备注的存储读写 —— caliber-memory.ts 存储层移植。
 * （抽取规则与口径块生成在 core/caliber——这里只管 CRUD 与三条硬规则。）
 *
 * 存储键前缀：按项目 id 单独存，与 project.reports.<id> 同一分层——
 * 备注虽小，但"给一个项目加备注把另一个项目的列表顶爆"这种事故出过一次。
 */
@Serializable
private data class StoredNote(val id: String, val text: String, val source: String, val savedAt: Long)

class CaliberStore(private val prefs: PrefsStore) {

    /** 时钟注入点（T-159）：缺省墙钟；同毫秒并列时 savedAt 排序不确定——测试注入单调计数器（D25 Q2） */
    internal var clock: () -> Long = System::currentTimeMillis
    private val json = Json { ignoreUnknownKeys = true }

    private fun load(projectId: String): List<StoredNote> =
        prefs.get(PREFIX + projectId, "").takeIf { it.isNotEmpty() }?.let { raw ->
            runCatching { json.decodeFromString<List<StoredNote>>(raw) }.getOrNull()
        } ?: emptyList()

    private fun save(projectId: String, notes: List<StoredNote>): Boolean =
        runCatching { prefs.set(PREFIX + projectId, json.encodeToString(notes)) }.isSuccess

    /** 读一个项目的全部口径备注（按入库时间降序，新的在前） */
    fun listCaliberNotes(projectId: String): List<CaliberNote> =
        load(projectId).sortedByDescending { it.savedAt }
            .map { CaliberNote(it.id, it.text, it.source, it.savedAt) }

    /**
     * 加一条口径备注。三条硬规则：空文本不加；超长不加；
     * **完全重复的不加**（同一条话说两遍会让模型强调两次）。
     */
    fun addCaliberNote(projectId: String, text: String, source: String = "manual"): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.length > CALIBER_MAX_NOTE_CHARS) return false
        val notes = load(projectId)
        if (notes.any { it.text.trim() == trimmed }) return false
        val next = (
            notes + StoredNote(newId(), trimmed, source, clock())
            ).sortedByDescending { it.savedAt }.take(CALIBER_MAX_NOTES)
        return save(projectId, next)
    }

    /** 删一条。返回是否真的删掉了（id 不存在时返回 false，UI 不能再提示"已删除"） */
    fun removeCaliberNote(projectId: String, id: String): Boolean {
        val notes = load(projectId)
        val next = notes.filter { it.id != id }
        if (next.size == notes.size) return false
        return save(projectId, next)
    }

    /** 清空一个项目的全部口径（数据集删了 / 用户手动清） */
    fun clearCaliberNotes(projectId: String) {
        prefs.remove(PREFIX + projectId)
    }

    companion object {
        const val PREFIX = "project.caliber."
    }
}
