package com.stragent.core.stats

import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset
import com.stragent.core.dataset.looksLikeIdColumn

/**
 * 热路径正则——**预编译单例**（T-142）。
 *
 * `monthKey` 是**逐格**调用的（找日期列 / 分组时逐值求月份键），
 * 写在函数体内会每次调用都重新编译 `Pattern`。`Pattern` 不可变且线程安全，
 * 提为单例安全。仓内先例：`core/verify/VerifyCore.kt:17-38`。
 */
private val MONTH_ISO_PREFIX = Regex("^((?:19|20)\\d{2})[-/.年](\\d{1,2})")
private val MONTH_US = Regex("^(\\d{1,2})[-/](\\d{1,2})[-/]((?:19|20)\\d{2})")
private val MONTH_ISO_EXACT = Regex("^((?:19|20)\\d{2})-(\\d{2})$")

/**
 * 月份键工具 —— src/shared/lib/group-yoy.ts 的 monthKey / shiftMonth / findMonthIndex 移植。
 *
 * 认 `2017-11`、`2017/11/8` 与美式 `11/8/2017`（superstore 的 Order Date）。
 * shiftMonth 只比字符串与算术，不用 Date（月末天数/时区坑）。
 */
fun monthKey(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val iso = MONTH_ISO_PREFIX.find(text)
    if (iso != null) {
        val mon = iso.groupValues[2].toInt()
        if (mon in 1..12) return "${iso.groupValues[1]}-${mon.toString().padStart(2, '0')}"
    }
    val us = MONTH_US.find(text)
    if (us != null) {
        val mon = us.groupValues[1].toInt()
        if (mon in 1..12) return "${us.groupValues[3]}-${mon.toString().padStart(2, '0')}"
    }
    return null
}

/** 把 `YYYY-MM` 回推 n 个月。月份进位（`2024-01` 减一月落 `2023-12`）用算术不用 Date */
fun shiftMonth(month: String, delta: Int): String {
    val match = MONTH_ISO_EXACT.find(month) ?: return month
    val total = match.groupValues[1].toInt() * 12 + (match.groupValues[2].toInt() - 1) + delta
    val year = Math.floorDiv(total, 12)
    val mon = Math.floorMod(total, 12) + 1
    return "$year-${mon.toString().padStart(2, '0')}"
}

/** 找日期列：第一个文本列且存在能解析出月份的行（与 yoy.ts yearOverYear 一致） */
fun findMonthIndex(dataset: Dataset): Int =
    dataset.columns.indices.firstOrNull { index ->
        dataset.columnTypes[index] == com.stragent.core.csv.ColumnType.TEXT &&
            dataset.rows.any { monthKey(it.getOrNull(index) ?: "") != null }
    } ?: -1

/** round2：Math.round(value * 100) / 100（yoy 与 group-yoy 共用的精度） */
fun round2(value: Double): Double = Math.round(value * 100) / 100.0

/** parseNumber 便捷引用（re-export 语义） */
internal fun toNumber(raw: String): Double? = Csv.parseNumber(raw)

/** looksLikeIdColumn 便捷引用 */
internal fun isIdLike(name: String): Boolean = looksLikeIdColumn(name)
