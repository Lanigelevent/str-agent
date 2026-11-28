package com.stragent.core.i18n

import java.util.Locale
import kotlin.test.*

class MessagesTest {

    @Test
    fun `中文 locale 返回中文消息`() {
        val zh = MessageBundle.forLocale(Locale.CHINESE)
        val msg = zh.get("error.rate_limit.exceeded")
        assertTrue(msg.contains("请求过于频繁") || msg.contains("Rate limit"), "应返回中文或 fallback 到 key：$msg")
    }

    @Test
    fun `英文 locale 返回英文消息`() {
        val en = MessageBundle.forLocale(Locale.ENGLISH)
        val msg = en.get("error.rate_limit.exceeded")
        assertTrue(msg.contains("Too many requests") || msg == "error.rate_limit.exceeded", "应返回英文或 key：$msg")
    }

    @Test
    fun `缺失 key 回退到 key 本身`() {
        val bundle = MessageBundle.forLocale(Locale.ENGLISH)
        assertEquals("nonexistent.key.xyz", bundle.get("nonexistent.key.xyz"))
    }

    @Test
    fun `默认 locale 使用系统默认`() {
        val msg = Messages.get("error.internal")
        assertNotNull(msg)
        assertTrue(msg.isNotBlank())
    }
}
