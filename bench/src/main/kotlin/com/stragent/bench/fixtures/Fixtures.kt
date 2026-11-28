package com.stragent.bench.fixtures

import com.stragent.core.ports.PrefsStore
import java.io.File

/**
 * 定位 fixtures 目录：从当前工作目录向上最多 6 层寻找名为 `fixtures` 的目录。
 * bench 模块的默认工作目录是 <root>/bench，因此 ../fixtures 即 <root>/fixtures；
 * 该兜底逻辑让本基座在测试/CI/手动运行任意路径下都能找到金标。
 */
fun findFixtures(): File {
    var dir = File(System.getProperty("user.dir"))
    repeat(6) {
        val cand = File(dir, "fixtures")
        if (cand.isDirectory) return cand
        dir = dir.parentFile ?: return@repeat
    }
    return File("../fixtures")
}

/** 极简内存偏好存储——复用 PrefsStore 契约，供 brief 生成套件按组偏好注入。 */
class MapPrefs : PrefsStore {
    private val m = mutableMapOf<String, String>()
    override fun get(key: String, fallback: String): String = m[key] ?: fallback
    override fun set(key: String, value: String) { m[key] = value }
}
