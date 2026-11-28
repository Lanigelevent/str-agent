package com.stragent.data

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-156（P2-12）· 磁盘 kv 总量淘汰。
 *
 * 检索缓存键（embed/chunkctx/blocksum/textpoints 等，可按需重算）此前在磁盘上
 * 无限累积（D24 审查确认的泄漏面）。超限后按 mtime 淘汰最旧的可重算键；
 * `ability-dataset.` 前缀（用户数据集）受保护不被淘汰。
 */
class FileDatasetStoreEvictTest {

    private fun tempStore(capBytes: Long = 512L * 1024 * 1024): Pair<FileDatasetStore, File> {
        val dir = Files.createTempDirectory("t156-kv-evict").toFile()
        return FileDatasetStore(dir, diskBytesCapOverride = capBytes) to dir
    }

    private fun fakeKvFile(dir: File, key: String, content: String, ageMs: Long): File {
        val f = File(dir, java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(key.toByteArray(Charsets.UTF_8)) + ".kv")
        f.writeText(content)
        f.setLastModified(System.currentTimeMillis() - ageMs)
        return f
    }

    @Test
    fun `超限淘汰最旧可重算键——数据集键受保护`() {
        val (store, dir) = tempStore(capBytes = 128L * 1024)
        // 三份假缓存文件（各 ~60KB），总量超过测试上限（128KB）
        val oldest = fakeKvFile(dir, "embed:old", "x".repeat(60_000), ageMs = 60_000)
        val newer = fakeKvFile(dir, "embed:new", "x".repeat(40_000), ageMs = 10_000)
        val dataset = fakeKvFile(dir, "ability-dataset.ds_1", "x".repeat(20_000), ageMs = 50_000)
        // 走一次 put（触发 evictDiskIfOver；put 的内容很小，总量仍超限 → 淘汰启动）
        store.put("embed:trigger", "y".repeat(40_000))
        assertTrue(!oldest.exists(), "最旧的可重算键应被淘汰")
        assertTrue(newer.exists(), "较新的可重算键应保留（总量已回落到上限内）")
        assertTrue(dataset.exists(), "数据集键受保护不被淘汰")
        // 保留键仍可读
        assertEquals("x".repeat(40_000), store.get("embed:new"))
        dir.deleteRecursively()
    }

    @Test
    fun `淘汰键按 miss 处理——新实例读到 null`() {
        val (store, dir) = tempStore(capBytes = 128L * 1024)
        fakeKvFile(dir, "blocksum:k", "z".repeat(100_000), ageMs = 30_000)
        // 触发淘汰后再用新实例读（新实例无内存缓存）
        store.put("embed:trigger", "y".repeat(40_000))
        val fresh = FileDatasetStore(dir, diskBytesCapOverride = 128L * 1024)
        assertEquals(null, fresh.get("blocksum:k"), "被淘汰的键按 miss 处理（重算责任在调用方）")
        dir.deleteRecursively()
    }
}
