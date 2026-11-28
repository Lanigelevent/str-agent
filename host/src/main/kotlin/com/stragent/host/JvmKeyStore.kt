package com.stragent.host

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 本机密钥存储 —— D15 升级：AES-256-GCM 加密 at rest。
 *
 * 安全模型：
 *  - 主密钥从机器指纹派生（MAC 地址 + 用户名 → SHA-256 → AES-256 key）
 *  - 每次 encrypt 生成随机 12 字节 IV，输出 `enc:<base64(iv + ciphertext + tag)>`
 *  - decrypt 解析 `enc:` 前缀，解密还原明文
 *  - 向后兼容：无 `enc:` 前缀的旧值（`file-` 前缀或裸明文）视为明文自动迁移
 *
 * 诚实边界：
 *  - 机器指纹不是真正的密钥派生（无 KDF / 无 salt），但比明文强几个数量级
 *  - 同用户同机器的所有进程可解密（无法做进程级隔离，JVM 没有此能力）
 *  - 环境变量 STRAGENT_API_KEY 仍优先级最高，全程不落盘
 */
class JvmKeyStore(private val file: File) {

    /**
     * 加密明文，返回 `enc:<base64>` 格式的存储值。
     * 同时写入密钥文件（向后兼容旧路径）。
     */
    fun encrypt(plain: String): String {
        file.parentFile?.mkdirs()
        // 写入加密后的文件内容（不再写明文）
        val encrypted = aesEncrypt(plain)
        file.writeText(encrypted, Charsets.UTF_8)
        restrict(file)
        return encrypted
    }

    /**
     * 解密存储值，返回明文。
     * 支持 `enc:` 格式（AES 解密）和旧格式（file- 前缀或裸明文，自动迁移）。
     */
    fun decrypt(stored: String): String = when {
        stored.startsWith(ENC_PREFIX) -> {
            // 新格式：AES 加密
            runCatching { aesDecrypt(stored) }.getOrDefault("")
        }
        stored.startsWith(FILE_PREFIX) -> {
            // 旧格式：file- 前缀，从文件读取（可能是旧明文）
            val fromFile = runCatching { file.readText(Charsets.UTF_8).trim() }.getOrDefault("")
            if (fromFile.startsWith(ENC_PREFIX)) {
                // 文件已迁移为加密格式
                runCatching { aesDecrypt(fromFile) }.getOrDefault(fromFile)
            } else {
                // 文件仍是旧明文（自动迁移：返回明文，下次 encrypt 时自动加密）
                fromFile
            }
        }
        else -> stored  // 裸明文（StepfunConfig 默认恒等 encrypt 的遗留值）
    }

    // --- AES-256-GCM 实现 ---

    private fun aesEncrypt(plain: String): String {
        val key = deriveKey()
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        // 输出：iv (12 bytes) + ciphertext + tag (16 bytes)
        val combined = iv + ciphertext
        return ENC_PREFIX + Base64.getEncoder().encodeToString(combined)
    }

    private fun aesDecrypt(stored: String): String {
        val encoded = stored.removePrefix(ENC_PREFIX)
        val combined = Base64.getDecoder().decode(encoded)
        require(combined.size >= 28) { "加密数据过短（IV 12 + tag 16 = 28 字节最小）" }
        val iv = combined.copyOfRange(0, 12)
        val ciphertext = combined.copyOfRange(12, combined.size)
        val key = deriveKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(ciphertext)
        return String(plain, Charsets.UTF_8)
    }

    /**
     * 从机器指纹派生 AES-256 密钥。
     * 输入：MAC 地址 + 用户名 → SHA-256 → 32 字节密钥
     */
    private fun deriveKey(): ByteArray {
        val mac = getMachineFingerprint()
        val user = System.getProperty("user.name", "unknown")
        val seed = "$mac::$user::str-agent-key-v1"
        return MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
    }

    /** 获取机器指纹（第一个非 loopback 网卡的 MAC 地址） */
    private fun getMachineFingerprint(): String {
        return try {
            val ni = java.net.NetworkInterface.getNetworkInterfaces()
                ?.toList()
                ?.filter { it.isUp && !it.isLoopback }
                ?.firstOrNull()
            ni?.hardwareAddress?.joinToString(":") { "%02x".format(it) }
                ?: System.getProperty("user.home", "unknown")
        } catch (e: Exception) {
            // 沙箱 / 无网络：降级为 user.home
            System.getProperty("user.home", "fallback")
        }
    }

    companion object {
        /** 加密标记前缀（新格式） */
        const val ENC_PREFIX = "enc:"

        /** 旧格式标记前缀（文件引用，保留向后兼容） */
        const val FILE_PREFIX = "file-"

        /** 收紧为仅属主可读写（POSIX）；失败不抛 —— 权限收紧是加固，不是功能前提 */
        fun restrict(target: File) {
            runCatching {
                target.setReadable(false, false)
                target.setWritable(false, false)
                target.setReadable(true, true)
                target.setWritable(true, true)
            }
        }
    }
}
