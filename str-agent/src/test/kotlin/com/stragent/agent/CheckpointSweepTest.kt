package com.stragent.agent

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-156 · checkpoint 孤儿回收（D24 审查 P2-11）。
 *
 * 清理此前只挂在「成功过门禁 / 回落经典」路径——取消/崩溃残留的 run 目录
 * **无限累积**。sweepOrphans 按「目录内最新文件 mtime」判定：过期整删、活跃保留。
 * 负控：把目录内文件 mtime 改旧（setLastModified）模拟残留。
 */
class CheckpointSweepTest {

    private fun tempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "t156-sweep-" + System.nanoTime()).also { it.mkdirs() }

    private fun seedRun(root: File, name: String, ageMs: Long): File {
        val dir = File(root, name).also { it.mkdirs() }
        val f = File(dir, "stage.json").also { it.writeText("{}") }
        f.setLastModified(System.currentTimeMillis() - ageMs)
        return dir
    }

    @Test
    fun `过期 run 目录被清——活跃目录不受影响`() {
        val root = tempRoot()
        val stale = seedRun(root, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 8L * 24 * 60 * 60 * 1000)
        val fresh = seedRun(root, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", 60_000)
        val store = AgentCheckpointStore(root)
        val removed = store.sweepOrphans(now = System.currentTimeMillis())
        assertEquals(1, removed, "应清 1 个过期目录")
        assertTrue(!stale.exists(), "过期目录应被删除")
        assertTrue(fresh.exists(), "活跃目录应保留")
        root.deleteRecursively()
    }

    @Test
    fun `自定义窗口与禁用态`() {
        val root = tempRoot()
        seedRun(root, "cccccccccccccccccccccccccccccccc", 2L * 60 * 60 * 1000)
        val store = AgentCheckpointStore(root)
        assertEquals(0, store.sweepOrphans(maxAgeMs = 7L * 24 * 60 * 60 * 1000), "7 天窗口下 2 小时残留不清")
        assertEquals(1, store.sweepOrphans(maxAgeMs = 1_000), "1 秒窗口下清掉")
        assertEquals(0, AgentCheckpointStore(null).sweepOrphans(), "禁用态（root=null）返回 0")
        root.deleteRecursively()
    }
}
