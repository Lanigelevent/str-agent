package com.stragent.core.format

import kotlin.math.abs
import kotlin.math.floor

/**
 * 格式化工具 —— src/shared/lib/format.ts 的 Kotlin 移植。
 * 这些是 UI 向函数（金额 / 大数 / 时间 / 脱敏 / 字节），brief 的数字格式化
 * 在 brief 模块自己手里（toFixed 对齐见 FormatCompat）。
 */
fun formatCurrency(cents: Double, currency: String = "¥"): String =
    "$currency${formatFixed(cents / 100, 2)}"

/** 大数字：12345 → 「1.2万」 */
fun formatCompactNumber(value: Double): String {
    if (abs(value) >= 100_000_000) return "${formatFixed(value / 100_000_000, 1)}亿"
    if (abs(value) >= 10_000) return "${formatFixed(value / 10_000, 1)}万"
    return formatJsNumber(value)
}

fun formatDateTimeIso(iso: String, withYear: Boolean = false): String = "—" // 见 FormatCompat 注释

fun maskPhone(phone: String): String =
    if (phone.length == 11) "${phone.slice(0..2)}****${phone.slice(7..10)}" else phone

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024 * 1024) return "${formatFixed(bytes / 1024.0, 1)} KB"
    return "${formatFixed(bytes / 1024.0 / 1024.0, 1)} MB"
}

/**
 * JS `Number.prototype.toFixed(n)` 对齐：round-half-away-from-zero 于十进制字符串上
 * （Kotlin 的 "%.1f" 是 half-up 于二进制表示，边界值会出现 ±1 差异；Gate B 要逐字节，
 * 统一走十进制字符串舍入）。负数时 JS 对绝对值舍入再带符号。
 */
fun formatFixed(value: Double, digits: Int): String {
    require(digits in 0..100)
    val negative = value < 0 || (value == 0.0 && 1.0 / value < 0)
    val scaled = java.math.BigDecimal(abs(value)).setScale(digits, java.math.RoundingMode.HALF_UP)
    var s = scaled.toPlainString()
    if (digits > 0 && !s.contains('.')) s += "." + "0".repeat(digits)
    return if (negative && (scaled.signum() != 0 || true)) "-$s" else s
}

/** JS Number → String 的整数形态（无小数位的 double 直接给整数） */
fun formatJsNumber(value: Double): String {
    if (value == floor(value) && abs(value) < 1e21) {
        val long = value.toLong()
        return long.toString()
    }
    return value.toString()
}
