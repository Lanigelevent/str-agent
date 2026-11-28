package com.stragent.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull
import java.io.File

/**
 * JvmKeyStore AES 加密测试（D15 T3）。
 *
 * 验证维度：
 *  1. 加密-解密往返：encrypt → decrypt 还原明文
 *  2. 加密结果含 enc: 前缀
 *  3. 旧明文值自动兼容（decrypt 不崩溃）
 *  4. 重复加密产生不同密文（IV 随机）
 *  5. 密文不含明文片段
 */
class JvmKeyStoreTest {

    private fun tempKeyStore(): JvmKeyStore {
        val tmp = File(System.getProperty("java.io.tmpdir"), "stragent-test-key-${System.nanoTime()}")
        return JvmKeyStore(tmp)
    }

    @Test
    fun `加密-解密往返`() {
        val ks = tempKeyStore()
        val plain = "sk-test-api-key-123456789"
        val encrypted = ks.encrypt(plain)
        val decrypted = ks.decrypt(encrypted)
        assertEquals(plain, decrypted)
    }

    @Test
    fun `加密结果含 enc 前缀`() {
        val ks = tempKeyStore()
        val encrypted = ks.encrypt("secret")
        assertTrue(encrypted.startsWith(JvmKeyStore.ENC_PREFIX), "加密值应以 enc: 开头")
    }

    @Test
    fun `重复加密产生不同密文`() {
        val ks = tempKeyStore()
        val plain = "same-plaintext"
        val enc1 = ks.encrypt(plain)
        val enc2 = ks.encrypt(plain)
        assertNotEquals(enc1, enc2, "相同明文应产生不同密文（IV 随机）")
    }

    @Test
    fun `密文不含明文片段`() {
        val ks = tempKeyStore()
        val plain = "very-secret-api-key-xyz"
        val encrypted = ks.encrypt(plain)
        assertTrue(!encrypted.contains(plain), "密文不应包含明文")
    }

    @Test
    fun `旧明文值自动兼容`() {
        val ks = tempKeyStore()
        // 模拟旧值：裸明文（StepfunConfig 默认恒等 encrypt 的遗留值）
        val oldValue = "old-plaintext-key"
        val decrypted = ks.decrypt(oldValue)
        assertEquals(oldValue, decrypted, "裸明文应原样返回")
    }

    @Test
    fun `file 前缀旧格式从文件读取`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "stragent-test-file-${System.nanoTime()}")
        tmp.writeText("legacy-plain-key", Charsets.UTF_8)
        val ks = JvmKeyStore(tmp)
        val decrypted = ks.decrypt(JvmKeyStore.FILE_PREFIX + tmp.name)
        assertEquals("legacy-plain-key", decrypted)
    }

    @Test
    fun `空字符串加密解密`() {
        val ks = tempKeyStore()
        val encrypted = ks.encrypt("")
        val decrypted = ks.decrypt(encrypted)
        assertEquals("", decrypted)
    }

    @Test
    fun `长密钥加密解密`() {
        val ks = tempKeyStore()
        val longKey = "sk-".repeat(100)
        val encrypted = ks.encrypt(longKey)
        val decrypted = ks.decrypt(encrypted)
        assertEquals(longKey, decrypted)
    }
}
