package com.stragent.host

import java.net.InetAddress
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-149 · SsrfGuard 单元测试（解析器注入，无需真实 DNS / 网络）。
 *
 * 覆盖：scheme 白名单、每类保留地址（回环 / RFC1918 / 链路本地 / ULA / 0.0.0.0 / IPv6 内嵌 IPv4）、
 * 解析失败、重定向链错误信息、公网放行不误伤。
 */
class SsrfGuardTest {

    /** 平台无关的字节级地址构造：不碰解析器与 IPv6 栈（ubuntu CI 实录：getByName("fc00::1") 在
     *  无 IPv6 栈的 runner 上抛 UnknownHostException）。 */
    private fun byAddress(literal: String): java.net.InetAddress {
        if (!literal.contains(":")) {
            val p = literal.split(".")
            return java.net.InetAddress.getByAddress(ByteArray(4) { p[it].toInt().toByte() })
        }
        val b = ByteArray(16)
        val head = literal.substringBefore("::").split(":").filter { it.isNotEmpty() }
        val tailRaw = literal.substringAfter("::").split(":").filter { it.isNotEmpty() }
        // 尾段最后一个组可能是 IPv4 点分形式（::ffff:10.0.0.5）——先归一成两个 hex 组
        val tail = tailRaw.flatMap { g ->
            if (g.contains(".")) {
                val o = g.split(".")
                listOf("%02x".format(o[0].toInt()) + o[1].toInt().toString(16).padStart(2, '0'),
                       "%02x".format(o[2].toInt()) + o[3].toInt().toString(16).padStart(2, '0'))
            } else listOf(g)
        }
        head.forEachIndexed { idx, g ->
            val v = g.toInt(16)
            b[idx * 2] = (v shr 8).toByte(); b[idx * 2 + 1] = v.toByte()
        }
        val base = head.size * 2
        tail.forEachIndexed { idx, g ->
            val v = g.toInt(16)
            b[16 - tail.size * 2 + idx * 2] = (v shr 8).toByte()
            b[16 - tail.size * 2 + idx * 2 + 1] = v.toByte()
        }
        return java.net.InetAddress.getByAddress(b)
    }

    private fun guard(mapping: Map<String, String> = emptyMap(), allowLoopback: Boolean = false) =
        SsrfGuard(
            resolve = { host ->
                mapping[host]?.let { listOf(byAddress(it)) }
                    ?: throw java.net.UnknownHostException(host)
            },
            allowLoopback = allowLoopback,
        )

    @Test
    fun `scheme 白名单——非 http 或 https 一律拒绝`() {
        val g = guard(mapOf("public.example" to "203.0.113.10"))
        val e = assertFailsWith<IllegalArgumentException> { g.check(URI("ftp://public.example/x")) }
        assertTrue(e.message!!.contains("只支持 http/https"), e.message)
    }
    @Test
    fun `回环与 0点0点0点0 拒绝`() {
        val g = guard(mapOf("stub.example" to "127.0.0.1", "stub2.example" to "0.0.0.0"))
        for (u in listOf("http://stub.example/x.csv", "http://stub2.example/x.csv")) {
            val e = assertFailsWith<IllegalArgumentException> { g.check(URI(u)) }
            assertTrue(e.message!!.contains("禁止访问内网"), e.message)
        }
    }

    @Test
    fun `RFC1918 与 ULA 拒绝——allowLoopback 不豁免私网`() {
        val g = guard(
            mapOf("s1" to "10.1.2.3", "s2" to "172.16.0.9", "s3" to "192.168.1.7",
                "s4" to "fc00::1", "s5" to "169.254.169.254"),
            allowLoopback = true,
        )
        for (h in listOf("s1", "s2", "s3", "s4", "s5")) {
            val e = assertFailsWith<IllegalArgumentException> { g.check(URI("http://$h/x")) }
            assertTrue(e.message!!.contains("禁止访问内网"), e.message)
        }
    }

    @Test
    fun `IPv6 内嵌 IPv4 拒绝`() {
        val g = guard(mapOf("s6" to "::ffff:10.0.0.5"))
        val e = assertFailsWith<IllegalArgumentException> { g.check(URI("http://s6/x")) }
        assertTrue(e.message!!.contains("禁止访问内网"), e.message)
    }

    @Test
    fun `公网地址放行不误伤`() {
        val g = guard(mapOf("public.example" to "203.0.113.10", "public2.example" to "8.8.8.8"))
        // 放行 = check 不抛异常
        g.check(URI("http://public.example/x.csv"))
        g.check(URI("https://public2.example/x.csv"))
        g.check(URI("http://public.example/x.csv"), redirectFrom = URI("http://a.example/1"))
    }

    @Test
    fun `解析失败——可解释错误`() {
        val g = guard(emptyMap())
        val e = assertFailsWith<IllegalArgumentException> { g.check(URI("http://no-such-host.invalid/x")) }
        assertTrue(e.message!!.contains("无法解析主机"), e.message)
    }

    @Test
    fun `重定向链——错误信息写明跳转来源`() {
        val g = guard(mapOf("hop.example" to "203.0.113.10", "inner.example" to "10.9.9.9"))
        val e = assertFailsWith<IllegalArgumentException> {
            g.check(URI("http://inner.example/x"), redirectFrom = URI("http://hop.example/y"))
        }
        assertTrue(e.message!!.contains("重定向自"), e.message)
        assertTrue(e.message!!.contains("hop.example"), e.message)
    }

    @Test
    fun `地址判定函数——逐类直断言`() {
        fun addr(s: String) = byAddress(s)
        assertTrue(SsrfGuard.isForbiddenAddress(addr("127.0.0.1")))
        assertTrue(SsrfGuard.isForbiddenAddress(addr("::1")))
        assertTrue(SsrfGuard.isForbiddenAddress(addr("10.0.0.1")))
        assertTrue(SsrfGuard.isForbiddenAddress(addr("172.20.1.2")))
        assertTrue(SsrfGuard.isForbiddenAddress(addr("192.168.50.50")))
        assertTrue(SsrfGuard.isForbiddenAddress(addr("169.254.169.254")))
        assertTrue(SsrfGuard.isForbiddenAddress(addr("fc00::1")))
        assertTrue(SsrfGuard.isForbiddenAddress(addr("::ffff:192.168.0.1")))
        assertFalse(SsrfGuard.isForbiddenAddress(addr("203.0.113.10")))
        assertFalse(SsrfGuard.isForbiddenAddress(addr("8.8.8.8")))
    }
}