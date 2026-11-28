package com.stragent.core.ai

import com.stragent.core.config.Config
import com.stragent.core.dataset.TextContent
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/**
 * 数据集级文本索引缓存（T-124，D23 W-A）——查询侧 compute-once。
 *
 * 业界借鉴：ingestion-time 预计算（昂贵工作移到一次性成本，查询时只留轻评分）；
 * LangChain Indexing API Record Manager 的内容哈希去重同构。热点实证：每次追问
 * sentenceGroups/aggregateChunks 重跑 + Bm25Index 各池全量重分词（~5-7 次 O(全文)）。
 *
 * 两层键：
 * - **原结构**（句组/聚合块）键 = materialHash（内容寻址，材料变一字键即变）；
 * - **富化条目**（docs/索引，含 ctx/摘要）键 = materialHash + flags——富化值（LLM 产出）
 *   已由 kv 层缓存（chunkctx/blocksum）在跨进程间稳定，同进程内同键条目一致；kv 淘汰后
 *   重新生成的富化值可能不同，内存条目继续服务旧值（语义等价，诚实登记）。
 *
 * 构建期间不持全局锁：FutureTask 模式——同键并发构建合并为一次（其余等待同一结果），
 * 异键互不阻塞；构建抛异常不留缓存（下次重算）。Bm25Index 构造后不可变（score 只读），
 * 跨线程共享安全。LRU 份数上限（默认 8，env STRAGENT_TEXT_INDEX_CACHE，0=关）。
 */
object TextIndexCache {

    private val cap = Config.get("STRAGENT_TEXT_INDEX_CACHE")?.toIntOrNull()?.coerceAtLeast(0) ?: 8
    private val lock = Any()
    private val rawCache = LinkedHashMap<String, Any>(16, 0.75f, true)
    private val richCache = LinkedHashMap<String, FutureTask<Any>>(16, 0.75f, true)

    /** materialHash：与 TextSummarizer.materialHash 同构（段号+类型+文本 join 后 sha12） */
    fun hashOf(content: TextContent): String =
        sha12(content.segments.joinToString("\u0000") { "${it.index}:${it.kind}:${it.text}" })

    /** 原结构（句组/聚合块等）按内容寻址取用；builder 只在未命中时跑一次 */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> raw(materialHash: String, kind: String, builder: () -> T): T =
        if (cap <= 0) builder() else synchronized(lock) {
            (rawCache["$kind:$materialHash"] as? T)?.let { return it }
            val built = builder()
            rawCache["$kind:$materialHash"] = built
            while (rawCache.size > cap) {
                val eldest = rawCache.keys.firstOrNull() ?: break
                rawCache.remove(eldest)
            }
            built
        }

/** 富化条目降级标记（T-124）：ctx/摘要瞬时失败的条目不留缓存——不把一次失败钉死成进程级降级 */
interface CacheDegradeAware {
    val cacheDegraded: Boolean
}

/**
 * 富化条目（docs/Bm25Index/池）按 (materialHash, flags) 取用：同键并发构建合并为一次
 * （其余等待同一结果），异键互不阻塞；构建抛异常不留缓存（下次重算）；
 * **降级条目（[CacheDegradeAware.cacheDegraded]）同样不留缓存**——下次重算。
 */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> enriched(materialHash: String, flags: String, builder: () -> T): T {
        if (cap <= 0) return builder()
        val key = "$materialHash|$flags"
        while (true) {
            val task: FutureTask<Any> = synchronized(lock) {
                richCache[key] ?: FutureTask(Callable { builder() as Any }).also { richCache[key] = it }
            }
            if (!task.isDone) task.run()
            try {
                val v = task.get() as T
                if (v is CacheDegradeAware && v.cacheDegraded) {
                    synchronized(lock) { if (richCache[key] === task) richCache.remove(key) }
                }
                return v
            } catch (e: ExecutionException) {
                // 失败不留缓存：仅当仍是本任务时移除，让下次重算
                synchronized(lock) { if (richCache[key] === task) richCache.remove(key) }
                throw (e.cause ?: e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("索引缓存等待被中断", e) // lint:allow zh-first（用户面中文输出，T-095 语言定位）
            } finally {
                synchronized(lock) {
                    while (richCache.size > cap) {
                        val eldest = richCache.entries.firstOrNull { it.value.isDone } ?: break
                        richCache.remove(eldest.key)
                    }
                }
            }
        }
    }

    /** 清空（测试用） */
    fun clear() = synchronized(lock) {
        rawCache.clear()
        richCache.clear()
    }

    private fun sha12(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(12)
}
