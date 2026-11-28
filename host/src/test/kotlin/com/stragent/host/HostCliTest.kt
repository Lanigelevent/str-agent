package com.stragent.host

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CLI 参数解析单测。
 *
 * 背景：这个解析出过一次真 bug —— `--transport` 的**取值**被当成模式，
 * 于是 `--manifest` 被忽略、进程直接进了 serve 模式挂住（还白占一个 7787 端口）。
 * 凡是「值可能长得像另一个选项」的解析，都必须有单测钉住。
 */
class HostCliTest {

    @Test
    fun `默认是 serve + http`() {
        assertEquals(HostCli("serve", "http"), parseCli(emptyList()))
    }

    @Test
    fun `模式参数带或不带横杠都能识别`() {
        assertEquals("manifest", parseCli(listOf("--manifest")).mode)
        assertEquals("manifest", parseCli(listOf("manifest")).mode)
        assertEquals("dsh-manifest", parseCli(listOf("--dsh-manifest")).mode)
        assertEquals("capabilities", parseCli(listOf("--capabilities")).mode)
        assertEquals("token", parseCli(listOf("--token")).mode)
    }

    @Test
    fun `transport 的取值不会被当成模式`() {
        val cli = parseCli(listOf("--transport", "stdio"))
        assertEquals("serve", cli.mode)
        assertEquals("stdio", cli.transport)
    }

    @Test
    fun `等号写法也支持`() {
        val cli = parseCli(listOf("--transport=stdio"))
        assertEquals("serve", cli.mode)
        assertEquals("stdio", cli.transport)
    }

    @Test
    fun `模式与 transport 可以并存`() {
        val cli = parseCli(listOf("--manifest", "--transport", "stdio"))
        assertEquals("manifest", cli.mode)
        assertEquals("stdio", cli.transport)
    }
}
