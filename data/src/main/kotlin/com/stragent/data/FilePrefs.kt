package com.stragent.data

import com.stragent.core.ports.PrefsStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties

/**
 * PrefsStore 的文件实现：一个 .properties 文件。
 *
 * 选 Properties 而不是 DataStore/SharedPreferences 的原因：data/ 是纯 JVM 模块
 * （模块边界纪律——适配层不碰 android.*），Properties 是 JVM 自带的键值格式，
 * 中文值按 \uXXXX 转义、键里的 = : 等特殊字符自动处理，零依赖且可移植。
 * 内存缓存 + 写穿：get 走内存（UI 高频读），set 落盘（原子写：临时文件 + 改名）。
 */
class FilePrefs(dir: File) : PrefsStore {
    private val file = File(dir, "prefs.properties").apply { parentFile?.mkdirs() }
    private val lock = Any()
    private val cache: Properties = loadFromDisk()

    init {
        // 老文件可能从宽权限时代过来（T-046）：启动时顺手收紧一次
        if (file.exists()) restrictPermissions(file)
    }

    private fun loadFromDisk(): Properties = Properties().apply {
        if (file.exists()) {
            try {
                FileInputStream(file).use { load(it) }
            } catch (_: Exception) {
                // 半截文件（写一半被杀）：当空配置启动，不崩
                clear()
            }
        }
    }

    override fun get(key: String, fallback: String): String =
        synchronized(lock) { cache.getProperty(key, fallback) }

    override fun set(key: String, value: String) {
        synchronized(lock) {
            cache.setProperty(key, value)
            persist()
        }
    }

    override fun remove(key: String) {
        synchronized(lock) {
            cache.remove(key)
            persist()
        }
    }

    private fun persist() {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(tmp).use { cache.store(it, null) }
        // 临时文件在改名之前就收紧，避免「刚写盘、还没改名」的窗口里可被他人读取
        restrictPermissions(tmp)
        if (!tmp.renameTo(file)) {
            // Windows 上目标存在时 renameTo 可能失败：删了再试
            file.delete()
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
        restrictPermissions(file)
    }

    /**
     * 把文件权限收紧为 600（仅属主读写，T-046 / D17 G-05）。
     *
     * POSIX 文件系统上生效；Windows 没有 POSIX 权限视图（Java 也不提供 ACL API），
     * 此时返回 false —— ACL 由**宿主负责**，已在文档标注（不假装跨平台都收紧了）。
     */
    private fun restrictPermissions(target: File): Boolean = try {
        val view = Files.getFileAttributeView(target.toPath(), PosixFileAttributeView::class.java)
        if (view == null) {
            false
        } else {
            view.setPermissions(PosixFilePermissions.fromString("rw-------"))
            true
        }
    } catch (_: Exception) {
        false
    }
}
