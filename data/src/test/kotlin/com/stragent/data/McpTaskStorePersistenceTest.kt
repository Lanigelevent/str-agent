package com.stragent.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-204 任务句柄落盘恢复测试（A6 opencode/langgraph checkpoint 机制借鉴）。
 * 「重启」= 同一 JSONL 上开新实例；断言终态恢复/非终态如实转 failed/TTL 不变/容错降级。
 */
class McpTaskStorePersistenceTest {

    private fun tempStore(): Path {
        val dir = Files.createTempDirectory("stragent-task-store-test")
        return dir.resolve("tasks.jsonl")
    }

    private fun newStore(path: Path, now: Long = 1_000_000L): McpTaskStore =
        McpTaskStore(defaultTtlMs = 60_000L, clock = { now }, storePath = path)

    @Test
    fun `终态恢复——completed 句柄与结果跨重启可取`() {
        val path = tempStore()
        val first = newStore(path)
        val t = first.create("stragent_generate_report", "sess-1", null)
        first.complete(t.id, buildJsonObject { put("value", 42) })
        // 「重启」：同路径新实例（时钟同一时点，未过期）
        val second = newStore(path)
        val restored = second.get(t.id)
        assertNotNull(restored)
        assertEquals(McpTaskStore.Status.COMPLETED, restored!!.status)
        assertEquals("stragent_generate_report", restored.tool)
        val result = assertNotNull(restored.result)
        assertEquals("42", (result["value"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertTrue(second.persistHealthy)
    }

    @Test
    fun `非终态恢复——working 任务如实转 failed`() {
        val path = tempStore()
        val first = newStore(path)
        val t = first.create("stragent_generate_report", "sess-1", null)
        // 不收尾（模拟执行线程死亡）→ 重启
        val second = newStore(path)
        val restored = second.get(t.id)
        assertNotNull(restored)
        assertEquals(McpTaskStore.Status.FAILED, restored!!.status)
        assertTrue(restored.statusMessage!!.contains("宿主重启"))
        assertNull(restored.result)
        assertTrue(second.persistHealthy)
    }

    @Test
    fun `TTL 语义不变——过期终态重启后不可取`() {
        val path = tempStore()
        val first = newStore(path, now = 1_000_000L)
        val t = first.create("tool", "s", null)
        first.cancel(t.id)
        // 重启时已过期（defaultTtl 60s，now+61s）
        val second = newStore(path, now = 1_000_061_000L)
        assertNull(second.get(t.id))
    }

    @Test
    fun `容错与降级——坏行跳过、坏路径降级不炸`() {
        val path = tempStore()
        val first = newStore(path)
        val t = first.create("tool", "s", null)
        first.complete(t.id, buildJsonObject { put("ok", true) })
        Files.writeString(path, "{broken json", java.nio.file.StandardOpenOption.APPEND)
        // 坏行后的新实例：好行恢复，坏行跳过
        val second = newStore(path)
        assertNotNull(second.get(t.id), "坏行前的完好行必须恢复")
        assertTrue(second.persistHealthy)
        // 坏路径（目录不存在→写失败）→ 降级不炸
        val bad = Path.of("Z:/definitely/not/exist/tasks.jsonl")
        val third = McpTaskStore(storePath = bad)
        val bt = third.create("tool", "s", null)
        third.complete(bt.id, buildJsonObject { put("ok", true) })
        assertTrue(!third.persistHealthy, "写失败必须降级为 persistHealthy=false")
        assertEquals(McpTaskStore.Status.COMPLETED, third.get(bt.id)!!.status, "降级态内存操作不受影响")
    }
}
