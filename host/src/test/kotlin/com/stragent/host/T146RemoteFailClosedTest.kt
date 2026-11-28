package com.stragent.host

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * T-146 · 远程绑定的 fail-closed 契约（**BREAKING**）。
 *
 * ## 契约
 *
 * **远程绑定（非回环）必须显式声明身份源**：配 `STRAGENT_OAUTH_ISSUER` → 启用 OAuth
 * 资源服务器；**没配 → 拒绝启动**。
 *
 * ## 为什么是 fail-closed 而不是「打条警告」
 *
 * 此前远程绑定未配 issuer 时**静默回落静态 Bearer token**，只在启动日志里 warn 一次。
 * OWASP **CWE-636「Not Failing Securely」** 说的正是这种形态：安全配置缺失时系统继续运行。
 * 而静态 token **无过期、无 audience、无 scope**——挂在公网上等于一把永不过期的万能钥匙，
 * 拿到它就能调用全部工具面（含写文件、查数据库、跑沙箱）。没人盯启动日志。
 *
 * ## 为什么这条契约需要「宿主层」的测试
 *
 * `OAuthResourceServer.fromEnvironment` 的单测只证明**工厂函数**会抛；
 * 真正要保证的是「**宿主启动不了**」。而监听地址此前**只**从环境变量读、
 * 进程内无法覆盖——这条安全契约在测试里**根本无法验证**。故本轮给
 * `StrAgentHostBootstrap.create` 加了可注入的 `httpBind` 参数，让契约可被执行。
 */
class T146RemoteFailClosedTest {

    private fun tempHome(): File =
        File(System.getProperty("java.io.tmpdir"), "t146-${System.nanoTime()}").also { it.mkdirs() }

    @Test
    fun `远程绑定未配 issuer——宿主拒绝启动`() {
        val refused = assertFailsWith<IllegalStateException> {
            StrAgentHostBootstrap.create(home = tempHome(), httpBind = "0.0.0.0")
        }
        assertTrue(
            refused.message!!.contains("STRAGENT_OAUTH_ISSUER"),
            "错误信息要写明该配哪个环境变量：${refused.message}",
        )
        assertTrue(
            refused.message!!.contains("127.0.0.1"),
            "错误信息要给出第二条出路（改回回环绑定）：${refused.message}",
        )
    }

    @Test
    fun `回环绑定不受影响——照常启动`() {
        // 回环是本地单用户的既有形态，不该被 OAuth 强加（否则会破坏所有本地用法）
        val bootstrap = StrAgentHostBootstrap.create(home = tempHome(), httpBind = "127.0.0.1")
        try {
            assertTrue(bootstrap.oauth == null, "回环绑定不该启用 OAuth")
        } finally {
            bootstrap.close()
        }
    }
}
