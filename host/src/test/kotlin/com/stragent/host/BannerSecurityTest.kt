package com.stragent.host

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-046 验收守卫：启动横幅只打印 token 指纹，不打印全文。
 *
 * 为什么是**源码级**断言：`main` 起服务后会阻塞在 serve，没法在单测里跑到横幅那几行；
 * 而「日志泄漏 token」恰恰是静态可判的——只要 println 不插 `$token` 就成立。
 * token 原文仍可经 `--token` 显式获取（那是用户主动取，不是被动日志）。
 */
class BannerSecurityTest {

    @Test
    fun `启动横幅只含指纹——不得打印 token 全文`() {
        val source = File("src/main/kotlin/com/stragent/host/HostMain.kt")
        assertTrue(source.exists(), "测试工作目录应为 host 模块：${source.absolutePath}")
        val text = source.readText()
        assertTrue(text.contains("McpBridge.tokenFingerprint(token)"), "横幅应使用 token 指纹")
        assertFalse(text.contains("MCP Token：\$token"), "横幅不得打印 token 全文")
        assertFalse(
            Regex("""println\([^\n]*\$\{?token""").containsMatchIn(text),
            "println 不得直接插值 token 变量",
        )
    }

    /**
     * T-047 判据 ④：启动日志必须提示「硬保证是只读数据库账号」。
     * 应用层只能做 SQL 校验 + 超时/行数上限，连接级 `setReadOnly` 在 DuckDB 上不可用；
     * 这条提示是给部署者的最后一道人工防线。
     */
    @Test
    fun `启动横幅提示数据库只读账号`() {
        val source = File("src/main/kotlin/com/stragent/host/HostMain.kt")
        val text = source.readText()
        assertTrue(text.contains("只读数据库账号"), "启动横幅应提示使用只读数据库账号")
        assertTrue(text.contains("50000"), "启动横幅应说明行数上限")
    }
}
