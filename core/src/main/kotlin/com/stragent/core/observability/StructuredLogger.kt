package com.stragent.core.observability

import java.time.Instant

/**
 * 结构化日志器（D15 T8）——零依赖纯 Kotlin 实现。
 *
 * 输出 JSON 格式单行日志，包含：
 *   timestamp (ISO-8601), level, logger, message, fields（键值对）
 *
 * 级别阈值全局生效（[setGlobalThreshold]），默认 INFO。
 * TRACE < DEBUG < INFO < WARN < ERROR
 */
class StructuredLogger private constructor(
    private val name: String,
    private val context: Map<String, String> = emptyMap(),
) {
    fun withContext(key: String, value: String): StructuredLogger =
        StructuredLogger(name, context + (key to value))

    fun isTraceEnabled() = Level.TRACE.ordinal >= globalThreshold.ordinal
    fun isDebugEnabled() = Level.DEBUG.ordinal >= globalThreshold.ordinal
    fun isInfoEnabled() = Level.INFO.ordinal >= globalThreshold.ordinal
    fun isWarnEnabled() = Level.WARN.ordinal >= globalThreshold.ordinal
    fun isErrorEnabled() = Level.ERROR.ordinal >= globalThreshold.ordinal

    fun trace(msg: String, vararg pairs: Pair<String, String?>) = log(Level.TRACE, msg, *pairs)
    fun debug(msg: String, vararg pairs: Pair<String, String?>) = log(Level.DEBUG, msg, *pairs)
    fun info(msg: String, vararg pairs: Pair<String, String?>) = log(Level.INFO, msg, *pairs)
    fun warn(msg: String, vararg pairs: Pair<String, String?>) = log(Level.WARN, msg, *pairs)
    fun error(msg: String, vararg pairs: Pair<String, String?>) = log(Level.ERROR, msg, *pairs)

    private fun log(level: Level, msg: String, vararg pairs: Pair<String, String?>) {
        if (level.ordinal < globalThreshold.ordinal) return
        val fields = LinkedHashMap(context)
        for ((k, v) in pairs) {
            if (v != null) fields[k] = v
        }
        val entry = LinkedHashMap<String, Any?>()
        entry["timestamp"] = Instant.now().toString()
        entry["level"] = level.name
        entry["logger"] = name
        entry["message"] = msg
        // T-156（P2-15）：自动注入当前 traceId——日志行与 OTel 链路可关联
        //（Jaeger 里看到慢 span 后能 grep 对应日志）。scope 为空（非链路内）时不注入。
        TraceScope.current()?.let { entry["traceId"] = it.traceId }
        if (fields.isNotEmpty()) entry["fields"] = fields
        sink(renderJson(entry))
    }

    enum class Level { TRACE, DEBUG, INFO, WARN, ERROR }

    companion object {
        @Volatile
        private var globalThreshold: Level = Level.INFO

        @Volatile
        private var sink: (String) -> Unit = { line -> System.err.println(line) } // lint:allow 日志器唯一出口

        fun getLogger(name: String): StructuredLogger = StructuredLogger(name)

        fun setGlobalThreshold(level: Level) {
            globalThreshold = level
        }

        fun setGlobalOut(out: (String) -> Unit) {
            sink = out
        }

        /** 当前全局 sink（T-066：OtelLogBridge 多路复用时读旧值，保证原通道不变） */
        fun globalOut(): (String) -> Unit = sink

        private fun renderJson(value: Any?): String = when (value) {
            null -> "null"
            is String -> quote(value)
            is Number, is Boolean -> value.toString()
            is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
                quote(k.toString()) + ":" + renderJson(v)
            }
            else -> quote(value.toString())
        }

        private fun quote(s: String): String = buildString {
            append('"')
            for (c in s) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(c)
                }
            }
            append('"')
        }
    }
}
