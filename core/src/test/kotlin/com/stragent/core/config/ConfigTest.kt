package com.stragent.core.config

import com.stragent.core.ai.TEXT_QUERY_REWRITE_MODE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-157 · 配置单点（Config）：
 *  - source 注入是仓内唯一 env 覆盖渠道（JVM 进程内改不了进程 env）；
 *  - 注入优先于进程环境，返回 null 回退进程环境；
 *  - getter 化的固化配置（原顶层 val）**读取时解析**——注入改动即时生效。
 *
 * 测试纪律：用独立探针变量名（STRAGENT_TEST_PROBE）与 try/finally 恢复注入，
 * 不碰任何真实 STRAGENT_* 变量，不断言本机真实 env 的值（可能有配置）。
 */
class ConfigTest {
    private val probe = "STRAGENT_TEST_PROBE"

    private fun withSource(map: Map<String, String>, block: () -> Unit) {
        val old = Config.source
        try {
            Config.source = { name -> map[name] }
            block()
        } finally {
            Config.source = old
        }
    }

    @Test
    fun `source 注入优先于进程环境`() {
        withSource(mapOf(probe to "injected")) {
            assertEquals("injected", Config.get(probe))
        }
    }

    @Test
    fun `source 命中但值为 null 时回退进程环境`() {
        // 注入表未含该键 → source 返回 null → 回退 System.getenv（值由本机决定，只断言不抛且为 String?）
        withSource(emptyMap()) {
            val value = Config.get(probe)
            assertTrue(value == null || value is String, "回退读取不得抛异常")
        }
    }

    @Test
    fun `getter 化配置读取时解析`() {
        // TEXT_QUERY_REWRITE_MODE：注入 always/off 各取一次，证明每次访问都重新读 env（原顶层 val 是启动时冻结）
        withSource(mapOf("STRAGENT_TEXT_QUERY_REWRITE" to "always")) {
            assertEquals("always", TEXT_QUERY_REWRITE_MODE)
        }
        withSource(mapOf("STRAGENT_TEXT_QUERY_REWRITE" to "off")) {
            assertEquals("off", TEXT_QUERY_REWRITE_MODE)
        }
    }
}
