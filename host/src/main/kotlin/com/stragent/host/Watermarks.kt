package com.stragent.host

import com.stragent.core.semantic.SEMANTIC_MAX_TERMS
import com.stragent.data.AbilityDatasets
import com.stragent.data.McpBridge
import com.stragent.data.MetricStore
import com.stragent.data.SemanticStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 水位线快照（T-174 · D22 §6 P2-10）——「现在到什么水位」一眼可见。
 *
 * 现状与缺口：数据面到处都有上限与阈值（落盘数据集 64 条 / 512MB、异步任务 128 条、
 * SSE 长连接 8 条、用户指标 50 条、语义术语 50 条、请求体 8MB、HTTP 弹性池 64），
 * 但**没有一处能一眼看到当前值**——运维只能靠猜或翻日志。
 *
 * 三条纪律：
 *  1. **只读既有计数器，不另建采样**：所有数字都来自现成的计数器/上限常量，
 *     读一次就是读一次，**不新增常驻开销**（没有定时任务、没有额外埋点）；
 *  2. **每个数字带上限与口径**：`limit` 为 null 表示「这一维没有硬上限」，
 *     `note` 说明口径（上限可由哪个 env 调整、这是采样下限还是精确值）；
 *  3. **测不到就不报**：宁可少一行，也不给一个编出来的数（例如落盘数据集的字节占用
 *     需要全量加载才能算——成本高且会扰动，所以只报条数与配置上限，不报字节水位）。
 */
object Watermarks {

    /**
     * 快照。
     *
     * @param datasets 会话内数据集（内存侧）
     * @param bridge MCP 桥（拿 SSE / 任务表计数）；null = 不报这两维
     * @param metrics 用户指标存储（null = 不报）
     * @param semantic 语义术语存储（null = 不报）
     * @param toolCount 当前下发的工具条数
     * @param tier2Available tier-2（DuckDB）是否可用
     */
    fun snapshot(
        datasets: AbilityDatasets?,
        bridge: McpBridge?,
        metrics: MetricStore?,
        semantic: SemanticStore?,
        toolCount: Int?,
        tier2Available: Boolean?,
    ): JsonObject {
        val lines = mutableListOf<JsonObject>()
        val runtime = Runtime.getRuntime()

        datasets?.let {
            lines.add(
                line(
                    "datasets.inMemory",
                    it.size().toLong(),
                    null,
                    "条",
                    "会话内内存数据集；落盘侧上限由 STRAGENT_DATASET_MAX_ENTRIES / _BYTES 决定（默认 64 条 / 512MB）",  // lint:allow zh-first（用户面中文文案/结构标记，与语言档位同源）
                ),
            )
        }
        bridge?.let {
            lines.add(
                line(
                    "tasks.active",
                    it.activeTasks().toLong(),
                    128L,
                    "条",
                    "在途 + 未过期终态的异步任务；上限 128 条（满了先淘汰最老终态，全在途时拒绝新建）",  // lint:allow zh-first（用户面中文文案/结构标记，与语言档位同源）
                ),
            )
            lines.add(
                line(
                    "sse.connections",
                    it.activeSseConnections().toLong(),
                    8L,
                    "条",
                    "服务端消息流 / 事件流长连接；累计被拒 ${it.rejectedSseConnections()} 次、累计丢帧 ${it.droppedSseFrames()} 帧",  // lint:allow zh-first（用户面中文文案/结构标记，与语言档位同源）
                ),
            )
        }
        metrics?.let {
            lines.add(
                line(
                    "metrics.userDefined",
                    it.list().size.toLong(),
                    MetricStore.MAX_USER_METRICS.toLong(),
                    "条",
                    "用户自定义指标定义（口径，不是笔记）",  // lint:allow zh-first（用户面中文文案/结构标记，与语言档位同源）
                ),
            )
        }
        semantic?.let {
            lines.add(
                line(
                    "semantic.terms",
                    it.listTerms().size.toLong(),
                    SEMANTIC_MAX_TERMS.toLong(),
                    "条",
                    "跨数据集口径术语定义",  // lint:allow zh-first（用户面中文文案/结构标记，与语言档位同源）
                ),
            )
        }
        toolCount?.let {
            lines.add(line("tools.exposed", it.toLong(), null, "条", "当前形态下发的对外工具条数（按部署形态裁剪）"))  // lint:allow zh-first（用户面中文文案/结构标记，与语言档位同源）
        }
        tier2Available?.let {
            lines.add(
                line(
                    "engine.tier2",
                    if (it) 1L else 0L,
                    null,
                    "可用",  // lint:allow zh-first（用户面中文文案/结构标记，与语言档位同源）
                    "tier-2 列式引擎（DuckDB）是否在类路径上：可用=1 / 不可用=0（不可用时大文件走 tier-1 并显式提示）",  // lint:allow zh-first（用户面中文文案/结构标记，与语言档位同源）
                ),
            )
        }
        val maxHeapMb = runtime.maxMemory() / (1024 * 1024)
        lines.add(
            line(
                "jvm.heapUsed",
                (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024),
                maxHeapMb,
                "MB",
                "JVM 堆：当前占用 / 本进程可用上限（不是业务配额，是进程自己的天花板）",  // lint:allow zh-first（用户面中文文案/结构标记，与语言档位同源）
            ),
        )
        return buildJsonObject {
            put("lines", JsonArray(lines))
            put("note", "水位线只读既有计数器，不另建采样；limit 为 null 表示该维度没有硬上限。")  // lint:allow zh-first（用户面中文文案/结构标记，与语言档位同源）
        }
    }

    private fun line(
        metric: String,
        current: Long,
        limit: Long?,
        unit: String,
        note: String,
    ): JsonObject = buildJsonObject {
        put("metric", metric)
        put("current", current)
        limit?.let { put("limit", it) }
        put("unit", unit)
        put("note", note)
    }
}
