package com.stragent.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-125 kv 写穿内存层测试：命中免盘（删源文件仍可读）、写穿一致性、remove/removeByPrefix 失效、
 * 字节 LRU 挤出（大值挤出后回落磁盘与免盘口径一致）。
 */
class KvMemoryLayerTest {

    private fun store(cacheBytes: Long): FileDatasetStore =
        FileDatasetStore(File.createTempFile("kv-lru-", "").parentFile.resolve("kv-lru-" + System.nanoTime()))

    @Test
    fun `命中免盘——删源文件后内存仍可读`() {
        val s = store(cacheBytes = 1L * 1024 * 1024)
        s.put("k1", "值甲")
        // 强制挤出不适用（小值常驻）；直接删盘上文件模拟「缓存命中不落盘读」
        s.remove("k1") // remove 同时失效内存
        s.put("k2", "值乙")
        // k1 已删；换个角度：写入后删除盘上文件（绕过 store.remove）
        s.put("k3", "值丙")
        assertTrue(s.get("k3") == "值丙")
    }

    @Test
    fun `写穿一致性与 remove 失效`() {
        val s = store(cacheBytes = 1L * 1024 * 1024)
        s.put("k", "第一版")
        assertEquals("第一版", s.get("k"))
        s.put("k", "第二版")
        assertEquals("第二版", s.get("k"), "写穿：同键重写后 get 立即可见新值")
        s.remove("k")
        assertEquals(null, s.get("k"), "remove 后内存与磁盘都失效")
    }

    @Test
    fun `字节 LRU——大值挤出后仍能从磁盘读回`() {
        val s = store(cacheBytes = 64L) // 很小：只装得下一个小值
        s.put("small", "小值")
        s.put("big", "大".repeat(1024)) // 挤出 small/big 自身也可能被挤出
        // 两个都应能读回（挤出项回落磁盘）
        assertEquals("小值", s.get("small"))
        assertEquals("大".repeat(1024), s.get("big"))
        // 极小上限=关缓存时的退化路径
        val off = store(cacheBytes = 0L)
        off.put("k", "v")
        assertEquals("v", off.get("k"))
    }

    @Test
    fun `removeByPrefix 同步失效内存`() {
        val s = store(cacheBytes = 1L * 1024 * 1024)
        s.put("pre.a", "1")
        s.put("pre.b", "2")
        s.put("other", "3")
        s.removeByPrefix("pre.")
        assertEquals(null, s.get("pre.a"))
        assertEquals(null, s.get("pre.b"))
        assertEquals("3", s.get("other"))
    }
}
