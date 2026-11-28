package com.stragent.data

import com.stragent.core.csv.Csv
import com.stragent.core.csv.ParsedTable
import com.stragent.core.dataset.Dataset
import com.stragent.core.ports.DatasetStore
import com.stragent.core.ports.PrefsStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/* =============================================================================
 * 多数据集与分类（「数据」Tab = 文档库）—— dataset.ts 存储层移植。
 *
 * 存储分两层，避免一个大数据集把整个库拖垮：
 *   datasets.index     每条数据集的元信息（不含数据本体）
 *   dataset.<id>       数据本体，按 id 单独存、单独删
 *
 * 与 TS 的差异（存储介质不同，语义对齐）：TS 本体在 IndexedDB 存完整 Dataset
 * 对象；这里存「原始 CSV 文本 + 元信息」，读取时重新 parseDelimited——
 * 解析是确定性的，重解析得到的行列与首存完全一致，而 CSV 文本正是用户
 * 导入的那份文件，不会引入第二次转换损失。
 */

@Serializable
data class DatasetMeta(
    val id: String,
    val name: String,
    /** 所属分类 id；'' = 未分类 */
    val categoryId: String,
    val importedAt: Long,
    val rows: Int,
    val columns: Int,
    val truncated: Boolean = false,
)

/** 数据分类（文档库里的文件夹） */
@Serializable
data class DataCategory(val id: String, val name: String, val createdAt: Long)

/** 落盘的数据集本体 = 原始 CSV + 展示名 + 分类归属 */
@Serializable
private data class StoredBody(
    val name: String,
    val categoryId: String,
    val importedAt: Long,
    val csv: String,
)

data class SaveDatasetResult(val id: String, /** false = 落盘失败（磁盘问题）：本次可用、刷新后需重新导入 */ val persisted: Boolean)

/** 存好的数据集读出结果：元信息 + 可分析 Dataset */
data class LoadedDataset(val meta: DatasetMeta, val dataset: Dataset)

fun newId(): String =
    "${java.lang.Long.toString(System.currentTimeMillis(), 36)}-" +
        (1..6).map { "abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)] }.joinToString("")

/** 导入时间展示：精确到分（秒对用户没有意义）——dataset.ts formatImportTime */
fun formatImportTime(timestamp: Long): String {
    val zoned = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
    return DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").format(zoned)
}

/** 重名时追加序号：导入同一个文件两次，列表里出现两条一样名字用户无法分辨 */
fun uniqueDatasetName(base: String, taken: Set<String>): String {
    val trimmed = base.trim().ifEmpty { "未命名数据" }
    if (trimmed !in taken) return trimmed
    for (i in 2 until 1000) {
        val candidate = "$trimmed ($i)"
        if (candidate !in taken) return candidate
    }
    return "$trimmed (${System.currentTimeMillis()})"
}

class DatasetLibrary(private val prefs: PrefsStore, private val blobs: DatasetStore) {
    private val json = Json { ignoreUnknownKeys = true }

    fun listDatasetMetas(): List<DatasetMeta> =
        decodeJson<List<DatasetMeta>>(INDEX_KEY) ?: emptyList()

    fun listCategories(): List<DataCategory> =
        decodeJson<List<DataCategory>>(CATEGORIES_KEY) ?: emptyList()

    /** 分类名（'' = 未分类；分类被删后也回落未分类） */
    fun categoryName(categoryId: String): String =
        if (categoryId.isEmpty()) "未分类" else listCategories().find { it.id == categoryId }?.name ?: "未分类"

    /**
     * 新导入一个数据集（默认进「未分类」）。
     * 先写数据体：写不进去就不要写索引——否则列表里会出现一条点开就
     * "数据已不在本机"的幽灵记录。索引写失败时回滚数据体，不留孤儿键。
     */
    fun saveDatasetFile(dataset: Dataset, csv: String, categoryId: String = ""): SaveDatasetResult {
        val id = newId()
        val existing = listDatasetMetas()
        val name = uniqueDatasetName(dataset.name, existing.map { it.name }.toSet())
        val body = StoredBody(name = name, categoryId = categoryId, importedAt = dataset.importedAt, csv = csv)
        val bodyOk = runCatching { blobs.put(bodyKey(id), json.encodeToString(body)) }.isSuccess
        if (!bodyOk) return SaveDatasetResult(id, persisted = false)

        val metas = listOf(
            DatasetMeta(
                id = id, name = name, categoryId = categoryId,
                importedAt = dataset.importedAt,
                rows = dataset.rows.size, columns = dataset.columns.size,
                truncated = dataset.truncated,
            ),
        ) + existing
        val indexOk = runCatching { prefs.set(INDEX_KEY, json.encodeToString(metas)) }.isSuccess
        if (!indexOk) {
            blobs.remove(bodyKey(id))
            return SaveDatasetResult(id, persisted = false)
        }
        return SaveDatasetResult(id, persisted = true)
    }

    /** 读一个数据集的本体（重新解析 CSV——确定性重建）。读不到返回 null。 */
    fun loadDataset(id: String): LoadedDataset? {
        val meta = listDatasetMetas().find { it.id == id } ?: return null
        val raw = blobs.get(bodyKey(id)) ?: return null
        val body = runCatching { json.decodeFromString<StoredBody>(raw) }.getOrNull() ?: return null
        val dataset = datasetFromCsv(body.csv, body.name, body.importedAt) ?: return null
        return LoadedDataset(meta, dataset)
    }

    /** 列表里真正可用的数据集（数据本体还在的） */
    fun listAvailableDatasets(): List<LoadedDataset> =
        listDatasetMetas().mapNotNull { loadDataset(it.id) }

    /**
     * 重命名数据集。索引与本体两处都要写：只改一处时，列表显示新名字、
     * 项目页头图与预览弹窗仍是旧名字。
     */
    fun renameDataset(id: String, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val metas = listDatasetMetas().toMutableList()
        val index = metas.indexOfFirst { it.id == id }
        if (index == -1) return false
        val previous = metas[index].name
        metas[index] = metas[index].copy(name = trimmed)
        if (!prefs.setSafe(INDEX_KEY, json.encodeToString(metas))) return false
        patchBody(id) { it.copy(name = trimmed) }
        return true
    }

    /** 移动到分类。索引与本体都要改（只改索引时重新读取分类会对不上） */
    fun moveDataset(id: String, categoryId: String) {
        val metas = listDatasetMetas().toMutableList()
        val index = metas.indexOfFirst { it.id == id }
        if (index == -1) return
        metas[index] = metas[index].copy(categoryId = categoryId)
        prefs.setSafe(INDEX_KEY, json.encodeToString(metas))
        patchBody(id) { it.copy(categoryId = categoryId) }
    }

    /** 删除数据集（数据本体 + 索引条目 + 它中断的生成草稿） */
    fun deleteDataset(id: String) {
        blobs.remove(bodyKey(id))
        prefs.set(
            INDEX_KEY,
            json.encodeToString(listDatasetMetas().filter { it.id != id }),
        )
        // 数据没了，为它续传的草稿也没有意义（签名第一段是 datasetId，按前缀扫得掉）
        if (blobs is FileDatasetStore) blobs.removeByPrefix("chapter-draft.$id@")
    }

    fun createCategory(name: String): DataCategory? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val category = DataCategory(newId(), trimmed, System.currentTimeMillis())
        prefs.set(CATEGORIES_KEY, json.encodeToString(listCategories() + category))
        return category
    }

    fun renameCategory(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        prefs.set(
            CATEGORIES_KEY,
            json.encodeToString(listCategories().map { if (it.id == id) it.copy(name = trimmed) else it }),
        )
    }

    /** 删除分类：里面的数据集移到 targetCategoryId（默认「未分类」） */
    fun deleteCategory(id: String, targetCategoryId: String = "") {
        prefs.set(CATEGORIES_KEY, json.encodeToString(listCategories().filter { it.id != id }))
        val metas = listDatasetMetas().map { if (it.categoryId == id) it.copy(categoryId = targetCategoryId) else it }
        prefs.set(INDEX_KEY, json.encodeToString(metas))
    }

    /**
     * 清除本机全部数据：数据集本体、索引、分类。
     * 不清：API Key（stepfun.apiKey）与分析偏好——「清除数据」不该顺手把
     * 用户的接入配置也抹掉。
     */
    fun clearAll() {
        for (meta in listDatasetMetas()) blobs.remove(bodyKey(meta.id))
        prefs.remove(INDEX_KEY)
        prefs.remove(CATEGORIES_KEY)
    }

    private fun bodyKey(id: String) = DATA_PREFIX + id

    private fun patchBody(id: String, patch: (StoredBody) -> StoredBody) {
        val raw = blobs.get(bodyKey(id)) ?: return
        val body = runCatching { json.decodeFromString<StoredBody>(raw) }.getOrNull() ?: return
        runCatching { blobs.put(bodyKey(id), json.encodeToString(patch(body))) }
    }

    private fun PrefsStore.setSafe(key: String, value: String): Boolean =
        runCatching { set(key, value) }.isSuccess

    private inline fun <reified T> decodeJson(key: String): T? =
        prefs.get(key, "").takeIf { it.isNotEmpty() }?.let { raw ->
            runCatching { json.decodeFromString<T>(raw) }.getOrNull()
        }

    companion object {
        const val INDEX_KEY = "datasets.index"
        const val CATEGORIES_KEY = "datasets.categories"
        const val DATA_PREFIX = "dataset."
    }
}

/** 从 CSV 文本确定性重建 Dataset（与导入链路同一套解析口径） */
fun datasetFromCsv(csv: String, name: String, importedAt: Long): Dataset? {
    val parsed: ParsedTable = runCatching { Csv.parseDelimited(csv) }.getOrNull() ?: return null
    if (parsed.columns.isEmpty() || parsed.rows.isEmpty()) return null
    return datasetFromTable(name, importedAt, parsed)
}

/** 从已解析的表格（xlsx / JSON / HTML / Markdown 来源）构建 Dataset */
fun datasetFromTable(name: String, importedAt: Long, table: ParsedTable): Dataset? {
    if (table.columns.isEmpty() || table.rows.isEmpty()) return null
    return Dataset(
        name = name,
        importedAt = importedAt,
        columns = table.columns,
        rows = table.rows,
        columnTypes = table.columnTypes,
        delimiter = table.delimiter,
        truncated = table.truncated,
        sourceRows = table.totalRows,
    )
}

/** 表格 → CSV 文本（落盘统一格式；含引号/逗号/换行的字段按 RFC 4180 转义） */
fun tableToCsv(table: ParsedTable): String {
    val escape = { field: String ->
        if (field.contains(',') || field.contains('"') || field.contains('\n') || field.contains('\r')) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }
    }
    val lines = mutableListOf(table.columns.joinToString(",") { escape(it) })
    table.rows.forEach { row -> lines.add(row.joinToString(",") { escape(it) }) }
    return lines.joinToString("\n")
}
