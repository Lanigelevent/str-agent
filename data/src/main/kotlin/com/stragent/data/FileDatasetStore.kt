package com.stragent.data

import com.stragent.core.config.Config
import com.stragent.core.ports.DatasetStore
import java.io.File
import java.util.Base64

/**
 * DatasetStore 的文件实现：一个键一个文件，文件名 = 键的 Base64url（无填充）。
 *
 * 键里带 @ | § 等字符（草稿签名是七段拼接），不能直接做文件名；Base64url 编码后
 * 在所有文件系统上都安全，且编码可逆——keys() 解码即得原始键，不需要单独的索引文件。
 * 每键一文件（而不是一个大 JSON）：分章生成每批落盘一次，大 JSON 每次全量重写，
 * 数据集多了以后是 O(数据集大小 × 章批数) 的写放大。
 */
class FileDatasetStore(private val dir: File, diskBytesCapOverride: Long? = null) : DatasetStore {
    private val lock = Any()

    /**
     * 写穿内存层（T-125，FilePrefs 先例）：get 命中免盘、put 写穿、remove 失效。
     * 按字节总量 LRU（env STRAGENT_KV_CACHE_BYTES，默认 64MB，0=关）——kv 同时存大数据集体
     * （≤32MB/键）与检索缓存小值（embed/chunkctx/blocksum/textpoints），字节上限让大键被自然
     * 挤出、小值常驻。accessOrder LinkedHashMap 非线程安全，全部走 [lock]。
     */
    private val cacheBytes = Config.get("STRAGENT_KV_CACHE_BYTES")?.toLongOrNull()?.coerceAtLeast(0) ?: (64L * 1024 * 1024)

    /**
     * T-156（P2-12）：**磁盘层**总量上限（env `STRAGENT_KV_DISK_BYTES`，默认 512MB，0=关）。
     * 超限后按文件 mtime 从旧到新淘汰**可重算键**（非 `ability-dataset.` 前缀——
     * 数据集是用户数据有自己的 LRU，检索缓存可按需重算）。内存 LRU 只管读缓存，
     * 磁盘键文件此前无总量上限——检索缓存（embed/chunkctx/blocksum/textpoints）
     * 会随使用无限累积（D24 审查确认的泄漏面）。
     */
    private val diskBytesCap = diskBytesCapOverride
        ?: Config.get("STRAGENT_KV_DISK_BYTES")?.toLongOrNull()?.coerceAtLeast(0)
        ?: (512L * 1024 * 1024)
    private val cache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
            if (cacheBytes <= 0) return false
            var total = 0L
            for (v in values) total += v.length * 2L
            return total > cacheBytes
        }
    }

    init {
        dir.mkdirs()
    }

    private fun fileFor(key: String): File {
        val name = Base64.getUrlEncoder().withoutPadding().encodeToString(key.toByteArray(Charsets.UTF_8))
        return File(dir, "$name.kv")
    }

    override fun put(key: String, value: String) {
        synchronized(lock) {
            if (cacheBytes > 0) cache[key] = value
            val f = fileFor(key)
            val tmp = File(dir, "${f.name}.tmp")
            tmp.writeText(value, Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) {
                    tmp.copyTo(f, overwrite = true)
                    tmp.delete()
                }
            }
            // T-156（P2-12）：写盘成功后检查磁盘总量——超限淘汰最旧的可重算键
            evictDiskIfOver()
        }
    }

    /**
     * 磁盘总量超限 → 按文件 mtime 从旧到新淘汰**可重算键**（非 `ability-dataset.` 前缀）。
     * 在锁内调用（put 尾部）；淘汰的键下次读取按 miss 处理、按需重算。
     */
    private fun evictDiskIfOver() {
        if (diskBytesCap <= 0) return
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= diskBytesCap) return
        for (f in files.filter { it.isFile && it.name.endsWith(".kv") && !isProtectedKey(it.name) }.sortedBy { it.lastModified() }) {
            if (total <= diskBytesCap) break
            val size = f.length()
            if (f.delete()) total -= size
        }
    }

    /** 数据集键保护：文件名 = key 的 Base64URL，解码后按前缀判定（解码失败按可淘汰处理） */
    private fun isProtectedKey(kvFileName: String): Boolean = runCatching {
        val key = Base64.getUrlDecoder().decode(kvFileName.removeSuffix(".kv")).decodeToString()
        key.startsWith("ability-dataset.")
    }.getOrDefault(false)

    override fun get(key: String): String? = synchronized(lock) {
        cache[key]?.let { return it }
        fileFor(key).takeIf { it.exists() }?.readText(Charsets.UTF_8)?.also { if (cacheBytes > 0) cache[key] = it }
    }

    override fun remove(key: String) {
        synchronized(lock) {
            cache.remove(key)
            fileFor(key).delete()
        }
    }

    override fun keys(): List<String> = synchronized(lock) {
        dir.listFiles { f -> f.isFile && f.name.endsWith(".kv") }
            ?.mapNotNull { f ->
                runCatching {
                    String(Base64.getUrlDecoder().decode(f.name.removeSuffix(".kv")), Charsets.UTF_8)
                }.getOrNull()
            }
            ?.sorted()
            ?: emptyList()
    }

    /** 按前缀清一批（TS storage.removeByPrefix 的语义：删数据集时扫掉它的草稿） */
    fun removeByPrefix(prefix: String) {
        synchronized(lock) {
            keys().filter { it.startsWith(prefix) }.forEach {
                cache.remove(it)
                fileFor(it).delete()
            }
        }
    }
}
