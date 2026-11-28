package com.stragent.core.stats

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import com.stragent.core.stats.CrossCellWithYield
import com.stragent.core.stats.CrossTabReport
import com.stragent.core.stats.ExtremeLocation
import com.stragent.core.stats.ExtremePair
import com.stragent.core.stats.GroupTrendReport
import com.stragent.core.stats.GroupYoYReport
import com.stragent.core.stats.ResolvedExtremes
import com.stragent.core.stats.YoYPoint
import com.stragent.core.stats.YoYReport

/**
 * 分析结果的「契约序列化 + 结构化比对」。
 *
 * 这是门 A 与本仓库 `bench/` 评测模块**共用**的单一事实源：
 * 既被 `AnalysisBaselineGate` 用于回归，也被 bench 的 numeric-accuracy 套件用于量化评分。
 * 抽取到此文件，杜绝「测试一份、bench 一份」的漂移（吸取 D6 教训）。
 *
 * 比对口径（跨语言 JSON 比对）：字符串精确、整数精确、浮点相对容差 1e-9。
 * undefined 在 JSON.stringify 里被省略，Kotlin 的 null → JsonNull → 比对为"缺失"。
 */

fun analysisToJson(result: AnalysisResult): JsonObject = buildJsonObject {
    put("columns", buildJsonObject {
        put("category", JsonPrimitive(result.columns.category))
        put("value", JsonPrimitive(result.columns.value))
        put("x", JsonPrimitive(result.columns.x))
        put("month", JsonPrimitive(result.columns.month))
        put("second", JsonPrimitive(result.columns.second))
    })
    put("yoy", result.yoy?.let { yoyJson(it) } ?: JsonNull)
    put("groupYoY", result.groupYoY?.let { gyJson(it) } ?: JsonNull)
    put("extremes", result.extremes?.let { extJson(it) } ?: JsonNull)
    put("crossTab", result.crossTab?.let { ctJson(it) } ?: JsonNull)
    put("groupTrend", result.groupTrend?.let { gtJson(it) } ?: JsonNull)
}

private fun yoyJson(r: YoYReport): JsonObject = buildJsonObject {
    put("points", buildJsonArray { r.points.forEach { add(pointJson(it)) } })
    put("metric", JsonPrimitive(r.metric))
    put("dateColumn", JsonPrimitive(r.dateColumn))
    put("comparable", JsonPrimitive(r.comparable))
    put("months", JsonPrimitive(r.months))
    put("seasonalMonths", buildJsonArray { r.seasonalMonths.forEach { add(JsonPrimitive(it)) } })
}

private fun pointJson(p: YoYPoint): JsonObject = buildJsonObject {
    put("month", JsonPrimitive(p.month))
    p.lastYear?.let { put("lastYear", JsonPrimitive(it)) }
    put("current", JsonPrimitive(p.current))
    p.previous?.let { put("previous", JsonPrimitive(it)) }
    p.change?.let { put("change", JsonPrimitive(it)) }
    p.mom?.let { put("mom", JsonPrimitive(it)) }
    p.previousMonth?.let { put("previousMonth", JsonPrimitive(it)) }
    put("count", JsonPrimitive(p.count))
    p.lastYearCount?.let { put("lastYearCount", JsonPrimitive(it)) }
}

private fun gyJson(r: GroupYoYReport): JsonObject = buildJsonObject {
    put("metric", JsonPrimitive(r.metric))
    put("dimension", JsonPrimitive(r.dimension))
    put("period", JsonPrimitive(r.period))
    put("previousPeriod", JsonPrimitive(r.previousPeriod))
    r.overall?.let { put("overall", JsonPrimitive(it)) }
    put("points", buildJsonArray { r.points.forEach { p ->
        add(buildJsonObject {
            put("label", JsonPrimitive(p.label))
            put("current", JsonPrimitive(p.current))
            put("previous", JsonPrimitive(p.previous))
            p.change?.let { put("change", JsonPrimitive(it)) }
            put("share", JsonPrimitive(p.share))
            put("rows", JsonPrimitive(p.rows))
        })
    } })
    put("laggards", buildJsonArray { r.laggards.forEach { add(JsonPrimitive(it)) } })
}

private fun extJson(r: ResolvedExtremes): JsonObject = buildJsonObject {
    put("pairs", buildJsonArray { r.pairs.forEach { add(pairJson(it)) } })
    put("source", JsonPrimitive(r.source))
    put("scopeRows", JsonPrimitive(r.scopeRows))
    put("sourceRows", JsonPrimitive(r.sourceRows))
}

private fun pairJson(p: ExtremePair): JsonObject = buildJsonObject {
    put("column", JsonPrimitive(p.column))
    p.max?.let { put("max", locJson(it)) }
    p.min?.let { put("min", locJson(it)) }
    p.top?.let { put("top", buildJsonArray { it.forEach { add(locJson(it)) } }) }
}

private fun locJson(l: ExtremeLocation): JsonObject = buildJsonObject {
    put("value", JsonPrimitive(l.value))
    put("row", JsonPrimitive(l.row))
    l.group?.let { put("group", JsonPrimitive(it)) }
    l.date?.let { put("date", JsonPrimitive(it)) }
    l.column?.let { put("column", JsonPrimitive(it)) }
}

private fun ctJson(r: CrossTabReport): JsonObject = buildJsonObject {
    put("aColumn", JsonPrimitive(r.aColumn))
    put("bColumn", JsonPrimitive(r.bColumn))
    put("metric", JsonPrimitive(r.metric))
    put("cells", buildJsonArray { r.cells.forEach { c ->
        add(buildJsonObject {
            put("a", JsonPrimitive(c.a)); put("b", JsonPrimitive(c.b))
            put("value", JsonPrimitive(c.value)); put("count", JsonPrimitive(c.count))
            put("yield", JsonPrimitive(c.yield))
        })
    } })
    put("baseline", JsonPrimitive(r.baseline))
    r.busiest?.let { put("busiest", cellJson(it)) }
    r.bestYield?.let { put("bestYield", cellJson(it)) }
    put("rows", JsonPrimitive(r.rows))
}

private fun cellJson(c: CrossCellWithYield): JsonObject = buildJsonObject {
    put("a", JsonPrimitive(c.a)); put("b", JsonPrimitive(c.b))
    put("value", JsonPrimitive(c.value)); put("count", JsonPrimitive(c.count))
    put("yield", JsonPrimitive(c.yield))
}

private fun gtJson(r: GroupTrendReport): JsonObject = buildJsonObject {
    put("metric", JsonPrimitive(r.metric))
    put("dimension", JsonPrimitive(r.dimension))
    put("window", JsonPrimitive(r.window))
    put("threshold", JsonPrimitive(r.threshold))
    put("points", buildJsonArray { r.points.forEach { p ->
        add(buildJsonObject {
            put("label", JsonPrimitive(p.label))
            put("periods", JsonPrimitive(p.periods))
            p.lastMonth?.let { put("lastMonth", JsonPrimitive(it)) }
            p.lastValue?.let { put("lastValue", JsonPrimitive(it)) }
            p.deviation?.let { put("deviation", JsonPrimitive(it)) }
            p.run?.let { run ->
                put("run", buildJsonObject {
                    put("start", JsonPrimitive(run.start)); put("end", JsonPrimitive(run.end))
                    put("months", JsonPrimitive(run.months)); put("direction", JsonPrimitive(run.direction))
                    put("deviation", JsonPrimitive(run.deviation))
                })
            }
        })
    } })
}

/**
 * 结构化比对：返回差异描述列表（空 = 完全一致）。
 * 比对口径同 [analysisToJson] 注释。
 */
fun contractCompare(expected: JsonElement?, actual: JsonElement?): List<String> {
    val diffs = mutableListOf<String>()
    fun rec(path: String, e: JsonElement?, a: JsonElement?) {
        if (e == null || e is JsonNull) {
            if (a != null && a !is JsonNull) diffs.add("$path: 基线缺失但实际有值")
            return
        }
        if (a == null || a is JsonNull) {
            diffs.add("$path: 基线有值但实际缺失")
            return
        }
        if (e is JsonObject && a is JsonObject) {
            val allKeys = (e.keys + a.keys).toSet()
            for (key in allKeys) rec("$path.$key", e[key], a[key])
            return
        }
        if (e is JsonArray && a is JsonArray) {
            if (e.size != a.size) { diffs.add("$path: 数组长度 ${e.size} vs ${a.size}"); return }
            e.forEachIndexed { i, el -> rec("$path[$i]", el, a[i]) }
            return
        }
        if (e is JsonPrimitive && a is JsonPrimitive) {
            val ev = e.jsonPrimitive
            val av = a.jsonPrimitive
            if (ev.isString || av.isString) {
                if (ev.content != av.content) diffs.add("$path: '${ev.content}' vs '${av.content}'")
                return
            }
            val en = ev.doubleOrNull
            val an = av.doubleOrNull
            if (en != null && an != null) {
                val tolerance = maxOf(kotlin.math.abs(en), kotlin.math.abs(an)) * 1e-9
                if (kotlin.math.abs(en - an) > tolerance) diffs.add("$path: $en vs $an")
                return
            }
            if (e.toString() != a.toString()) diffs.add("$path: $e vs $a")
            return
        }
        diffs.add("$path: 类型不一致 ${e::class.simpleName} vs ${a::class.simpleName}")
    }
    rec("", expected, actual)
    return diffs
}
