package com.stragent.data

import com.stragent.core.config.Config
import com.stragent.core.csv.ColumnType
import com.stragent.core.csv.Csv
import com.stragent.core.csv.ParsedTable
import com.stragent.core.dataset.Dataset
import com.stragent.core.dataset.SamplingInfo
import com.stragent.core.dataset.TextContent
import com.stragent.core.dataset.TextSegment
import com.stragent.core.ports.DatasetStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 会话数据集的持久化（T-054 / D17 G-13）—— 让「服务重启后内存数据会清空」成为过去时。
 *
 * 为什么不是 SQLite/DuckDB：数据集本体本来就是「一份表格文本」，宿主的 kv 端口
 * （[DatasetStore]，文件落盘）已经能把确定性重建所需的全部信息存下来；再引入一个
 * 嵌入式数据库只为存几份 CSV，收益是零、供应链和迁移成本却是实打实的。DuckDB
 * 留在 tier-2 分析路径上做它该做的事。
 *
 * 口径：
 *  - 键 `ability-dataset.<datasetId>`：datasetId 本身是内容派生的（`ds_<sha256 前 12>`），
 *    同内容重导自然覆盖同一键，不会积累重复条目；
 *  - 存的是「CSV 本体 + 列类型 + 抽样口径」：重解析是确定性的，读回来的行列与首存一致；
 *  - 单份上限 [maxBytes]（默认 32MB）：超限返回 false，**不静默**——导入工具据此回报
 *    `persisted=false`，用户知道重启后要重新导入；
 *  - 大文件数据集持久化的是**抽样样本**（会话里实际分析的那份），不是源文件——
 *    源文件可能几百 MB，指纹与行号映射已存，重导同一文件即可得到同一份样本；
 *  - 总量 LRU（T-076，D21 P-3）：条数上限 [maxEntries]（默认 64）与字节上限 [capacityBytes]
 *    （默认 512MB），超限按 savedAt 淘汰最旧条目（env 可调：
 *    `STRAGENT_DATASET_MAX_ENTRIES` / `STRAGENT_DATASET_MAX_BYTES`）；淘汰名单随保存
 *    返回、由导入回执 `evicted` 如实交代——新导入永远成功（最新使用优先留存）。
 */
class AbilityDatasetStore(
    private val blobs: DatasetStore,
    private val maxBytes: Int = 32 * 1024 * 1024,
    /**
     * 条数上限（默认 64，env STRAGENT_DATASET_MAX_ENTRIES 可调）
     */
    private val maxEntries: Int = Config.get("STRAGENT_DATASET_MAX_ENTRIES")?.toIntOrNull()?.coerceAtLeast(1) ?: 64,
    /**
     * 总字节上限（默认 512MB，env STRAGENT_DATASET_MAX_BYTES 可调）
     */
    private val capacityBytes: Long =
        Config.get("STRAGENT_DATASET_MAX_BYTES")?.toLongOrNull()?.coerceAtLeast(1L) ?: 512L * 1024 * 1024,
    /**
     * 时钟注入点（T-159）：savedAt 的来源，缺省墙钟。LRU 淘汰按 savedAt 排序——墙钟在
     * 同毫秒内并列时 victim 选择不确定（D25 Q2），测试注入单调计数器后顺序完全确定，
     * 免 sleep 且免疫 NTP 步进/时钟回跳。
     */
    internal val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * 条目字节数与保存时间记账（save/load 时维护；LRU 淘汰依据，避免淘汰时全量重读）
     */
    private val sizes = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val savedAts = java.util.concurrent.ConcurrentHashMap<String, Long>()

    @Serializable
    private data class StoredSampling(
        val method: String,
        val targetRows: Int,
        val step: Int,
        val totalRows: Int,
        val fingerprint: String,
        val rowNumbers: List<Int>,
    )

    /** 文本部分（T-096）：段落与表格投影的持久化形态（与 core 的 TextContent/TextSegment 手工互转） */
    @Serializable
    private data class StoredSegment(
        val index: Int,
        val text: String,
        val kind: String,
    )

    @Serializable
    private data class StoredTextContent(
        val segments: List<StoredSegment>,
    )

    @Serializable
    private data class StoredBody(
        val name: String,
        val importedAt: Long,
        val delimiter: String,
        val truncated: Boolean,
        val sourceRows: Int,
        val columnTypes: List<String>,
        val csv: String,
        val sampling: StoredSampling? = null,
        val text: StoredTextContent? = null,
        val savedAt: Long,
    )

    /** 保存一份数据集；返回是否真的落盘（超限/IO 失败都返回 false，不抛） */
    fun save(id: String, dataset: Dataset): Boolean = saveWithEvictions(id, dataset).first

    /**
     * 保存并执行 LRU 淘汰（T-076）；返回 (是否落盘, 被淘汰的 datasetId 列表)。
     * 淘汰只发生在**成功落盘之后**：新导入永远成功，最旧的条目让位（最新使用优先留存）。
     */
    fun saveWithEvictions(id: String, dataset: Dataset): Pair<Boolean, List<String>> {
        // T-096：纯文本数据集（表格部分为空表 + text 承载段落）是合法形态
        if (dataset.rows.isEmpty() && dataset.text == null) return false to emptyList()
        val csv = tableToCsv(
            ParsedTable(
                columns = dataset.columns,
                rows = dataset.rows,
                columnTypes = dataset.columnTypes,
                delimiter = dataset.delimiter,
                truncated = dataset.truncated,
                totalRows = dataset.sourceRows,
            ),
        )
        if (csv.toByteArray(Charsets.UTF_8).size > maxBytes) return false to emptyList()
        val body = StoredBody(
            name = dataset.name,
            importedAt = dataset.importedAt,
            delimiter = dataset.delimiter.toString(),
            truncated = dataset.truncated,
            sourceRows = dataset.sourceRows,
            columnTypes = dataset.columnTypes.map { it.name },
            csv = csv,
            sampling = dataset.sampling?.let {
                StoredSampling(it.method, it.targetRows, it.step, it.totalRows, it.fingerprint, it.rowNumbers)
            },
            text = dataset.text?.let {
                StoredTextContent(
                    segments = it.segments.map { seg ->
                        StoredSegment(seg.index, seg.text, seg.kind.name)
                    },
                )
            },
            savedAt = clock(),
        )
        val raw = json.encodeToString(body)
        val ok = runCatching { blobs.put(keyOf(id), raw) }.isSuccess
        if (!ok) return false to emptyList()
        sizes[id] = raw.toByteArray(Charsets.UTF_8).size.toLong()
        savedAts[id] = body.savedAt
        return true to evictExcluding(id)
    }

    /**
     * LRU 淘汰：条数超 [maxEntries] 或总字节超 [capacityBytes] 时，按 savedAt 从最旧开始淘汰，
     * 直到回到限额内；刚保存的 [keepId] 永不淘汰（新导入不让位）。
     * 记账缺失的条目（本进程没读过的）惰性补记；条目已不存在/损坏的直接从记账清掉。
     */
    private fun evictExcluding(keepId: String): List<String> {
        val evicted = mutableListOf<String>()
        while (true) {
            val ids = blobs.keys()
                .filter { it.startsWith(KEY_PREFIX) }
                .map { it.removePrefix(KEY_PREFIX) }
                .filter { it != keepId }
            // 记账惰性补齐：本进程没读过的条目读一次并缓存
            for (id in ids) {
                if (!sizes.containsKey(id)) {
                    val raw = blobs.get(keyOf(id)) ?: continue
                    sizes[id] = raw.toByteArray(Charsets.UTF_8).size.toLong()
                    savedAts[id] = runCatching { json.decodeFromString<StoredBody>(raw).savedAt }.getOrDefault(0L)
                }
            }
            val known = ids.filter { sizes.containsKey(it) }
            val keepBytes = sizes[keepId] ?: 0L
            val totalBytes = known.sumOf { sizes[it] ?: 0L } + keepBytes
            val over = known.size + 1 > maxEntries || totalBytes > capacityBytes
            if (!over) break
            val victim = known.minByOrNull { savedAts[it] ?: 0L } ?: break
            runCatching { blobs.remove(keyOf(victim)) }
            sizes.remove(victim)
            savedAts.remove(victim)
            evicted.add(victim)
        }
        return evicted
    }

    /** 读回一份数据集；不存在或损坏返回 null（损坏不抛——启动恢复不该被一份坏数据拖死） */
    fun load(id: String): Dataset? {
        val raw = blobs.get(keyOf(id)) ?: return null
        sizes[id] = raw.toByteArray(Charsets.UTF_8).size.toLong()
        val body = runCatching { json.decodeFromString<StoredBody>(raw) }.getOrNull()
        if (body == null) {
            sizes.remove(id)
            return null
        }
        savedAts[id] = body.savedAt
        return datasetFromBody(body)
    }

    /** 全部已持久化的数据集（按保存时间顺序，最新的在最后） */
    fun loadAll(): List<Pair<String, Dataset>> =
        blobs.keys()
            .filter { it.startsWith(KEY_PREFIX) }
            .mapNotNull { key ->
                val raw = blobs.get(key) ?: return@mapNotNull null
                val id = key.removePrefix(KEY_PREFIX)
                sizes[id] = raw.toByteArray(Charsets.UTF_8).size.toLong()
                val body = runCatching { json.decodeFromString<StoredBody>(raw) }.getOrNull()
                    ?: return@mapNotNull corruptedOnce(key)
                savedAts[id] = body.savedAt
                Triple(id, body.savedAt, body)
            }
            .sortedBy { it.second }
            .mapNotNull { (id, _, body) -> datasetFromBody(body)?.let { id to it } }

    /**
     * 损坏条目的诚实告警（T-074）：每个实例只告警一次（防 ids()/loadAll 反复刷屏），
     * 键名照实给出——运维在 stderr 结构化日志里能直接看到是哪一条坏了。
     */
    private val corruptionLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun corruptedOnce(key: String): Nothing? {
        if (corruptionLogged.compareAndSet(false, true)) {
            com.stragent.core.observability.StructuredLogger.getLogger("DatasetStore").warn(
                // 诊断文案走 i18n（预算制：新代码零新增硬编码中文）
                com.stragent.core.i18n.Messages.get("datasetstore.corrupt_entry_skipped"),
                "key" to key,
            )
        }
        return null
    }

    private fun datasetFromBody(body: StoredBody): Dataset? {
        val parsed = runCatching { Csv.parseDelimitedFull(body.csv) }.getOrNull() ?: return null
        // T-096：纯文本数据集（表格部分空表 + text 承载段落）是合法形态；两者皆空才是损坏
        if ((parsed.columns.isEmpty() || parsed.rows.isEmpty()) && body.text == null) return null
        val types = body.columnTypes.mapNotNull { name ->
            ColumnType.entries.find { it.name == name }
        }
        return Dataset(
            name = body.name,
            importedAt = body.importedAt,
            columns = parsed.columns,
            rows = parsed.rows,
            columnTypes = if (types.size == parsed.columns.size) types else parsed.columnTypes,
            delimiter = body.delimiter.firstOrNull() ?: parsed.delimiter,
            truncated = body.truncated,
            sourceRows = body.sourceRows,
            sampling = body.sampling?.let {
                SamplingInfo(
                    method = it.method,
                    targetRows = it.targetRows,
                    step = it.step,
                    totalRows = it.totalRows,
                    fingerprint = it.fingerprint,
                    rowNumbers = it.rowNumbers,
                )
            },
            text = body.text?.let {
                TextContent(
                    segments = it.segments.map { seg ->
                        TextSegment(
                            index = seg.index,
                            text = seg.text,
                            kind = runCatching { TextSegment.Kind.valueOf(seg.kind) }
                                .getOrDefault(TextSegment.Kind.PARAGRAPH),
                        )
                    },
                )
            },
        )
    }

    /** 已持久化的数据集 id（不含损坏条目） */
    fun ids(): List<String> = loadAll().map { it.first }

    fun remove(id: String) {
        runCatching { blobs.remove(keyOf(id)) }
        sizes.remove(id)
        savedAts.remove(id)
    }

    private fun keyOf(id: String): String = KEY_PREFIX + id

    companion object {
        const val KEY_PREFIX = "ability-dataset."
    }
}
