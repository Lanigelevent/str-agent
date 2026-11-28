package com.stragent.host

import com.stragent.core.ports.DatasetStore
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * kv 备份导出/导入（T-076，D21 R-2②）——**宿主自用，不进对外工具面**。
 *
 * 为什么要有它：基础设施单点（单 runner、单机、kv 无备份）是 D21 R-2 点名的风险——
 * 这对命令把「备份」变成两条可演练的路径：导出全量快照（一个 JSON，键值对全量），
 * 导入把快照里目标 store 缺失的条目写回去（默认不覆盖现有键——防止旧备份盖掉新状态；
 * replace=true 才全量覆盖）。数据集持久化、跨会话记忆、口径记忆共用同一 kv 端口，
 * 一份快照即覆盖全部。
 *
 * 用法（宿主 CLI，见 HostMain）：
 *   gradlew :host:run --args="kv-export /path/backup.json"
 *   gradlew :host:run --args="kv-import /path/backup.json"
 */
object KvBackup {

    /**
     * 全量导出为 JSON 快照；返回导出条数。键按字典序排列（同输入同字节，可 diff）。
     */
    fun export(store: DatasetStore, file: File): Int {
        val entries = store.keys().sorted().mapNotNull { key -> store.get(key)?.let { key to it } }
        val body = buildJsonObject {
            put("version", 1)
            put("count", entries.size)
            put("entries", JsonObject(entries.associate { (k, v) -> k to JsonPrimitive(v) }))
        }
        file.parentFile?.mkdirs()
        file.writeText(body.toString(), Charsets.UTF_8)
        return entries.size
    }

    /**
     * 导入快照：默认只写目标 store **缺失**的键（现有状态不让位）；replace=true 全量覆盖。
     * 返回实际写入条数。结构非法时抛 IllegalArgumentException（CLI 层如实报错）。
     */
    fun import(store: DatasetStore, file: File, replace: Boolean = false): Int {
        val obj = runCatching { Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject }
            .getOrElse {
                // 诊断文案走 i18n（预算制）；{0} 带原始解析错误
                throw IllegalArgumentException(
                    com.stragent.core.i18n.Messages.get("kvbackup.bad_json")
                        .replace("{0}", it.message ?: "unknown"),
                )
            }
        val entries = obj["entries"] as? JsonObject
            ?: throw IllegalArgumentException(
                com.stragent.core.i18n.Messages.get("kvbackup.missing_entries"),
            )
        var written = 0
        for ((key, value) in entries) {
            val v = (value as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: continue
            if (!replace && store.get(key) != null) continue
            store.put(key, v)
            written += 1
        }
        return written
    }
}
