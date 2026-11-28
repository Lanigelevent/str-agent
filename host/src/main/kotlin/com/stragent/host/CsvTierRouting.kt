package com.stragent.host

import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset
import com.stragent.core.dataset.datasetFromParsed
import com.stragent.core.engine.EngineRouter
import com.stragent.core.engine.EngineTier
import com.stragent.core.engine.InMemoryEngine
import com.stragent.engine.duckdb.DuckDbEngine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * 双轨路由的**宿主接线**（T-013 余量 / D10 阶段 1：「策展工具增加 mode 参数，自动路由；
 * 工具语义不变，仅后端切换」）。
 *
 * 接线前的事实：`EngineRouter` 只有单测在跑，host 侧两个按路径分析的 CSV 工具**写死
 * tier-2**——小文件也走 DuckDB，且宿主没有 DuckDB 原生库时一律抛错（连 200 行的文件都
 * 答不了）。这就是「路由只是纸面能力」的缺口。
 *
 * 接线后的口径（三条都不许静默）：
 *  1. **按规模自动切档**：数据行数 ≤ 阈值 → tier-1（[InMemoryEngine]，与报告路径同一实现，
 *     数字逐个可回溯到内存原始行）；> 阈值 → tier-2（[DuckDbEngine]，数据不进 JVM 堆）；
 *  2. **tier-2 不可用时回落 tier-1**，并按 [EngineRouter.needsTruncationNotice] 显式回报
 *     「只统计了前 N 行」——降级可以，把降级藏起来不行；
 *  3. **显式指名档位**优先于自动路由（`engine=memory` 即报告路径口径 / `engine=columnar`
 *     即规模口径），便于双轨对拍与用户主动要规模。
 *
 * 两档结果的**口径一致性**不由这里保证，由 bench `engine-parity` 套件逐样本对拍保证
 * （两档共用 `core.dataset.finalizeAggregate`）。
 */
internal object CsvTierRouting {

    /** `engine` 参数的三个合法取值 */
    const val REQUEST_AUTO = "auto"
    const val REQUEST_MEMORY = "memory"
    const val REQUEST_COLUMNAR = "columnar"

    /**
     * 引擎标识与各自实现的 `id` 同源（`InMemoryEngine.id` / `DuckDbEngine.id`）。
     * 测试里逐条钉住，避免这里漂移成第二套名字（C-12 单一权威源）。
     */
    const val ENGINE_TIER1 = "in-memory"
    const val ENGINE_TIER2 = "duckdb"

    /**
     * tier-1 要把文件整读进 JVM 解析，超过此体积一律**显式拒绝**：
     * 与其冒 OOM（进程被杀、一句提示都没有），不如说清「这条通道只接小文件」。
     */
    const val MAX_TIER1_BYTES = 64L * 1024 * 1024

    /**
     * 路由决策 + 决策依据。
     *
     * 依据字段全部要进工具输出：「这个数字来自哪一档、按什么判定」必须可回答。
     */
    data class Decision(
        val tier: EngineTier,
        /** 引擎标识：tier-1 = [ENGINE_TIER1]，tier-2 = [ENGINE_TIER2] */
        val engine: String,
        /** 用户请求的档位（auto / memory / columnar） */
        val requested: String,
        /** 路由时数到的数据行数（未早停时即全量行数） */
        val dataRows: Int,
        /** 行数在阈值处早停：真实行数「不止于此」，措辞据此而不假装精确 */
        val rowsCountCapped: Boolean,
        val threshold: Int,
        val tier2Available: Boolean,
        /** 非 null = 判定层已认定「tier-1 没看到全量数据」，必须显式回报 */
        val needsTruncationNotice: Boolean,
    )

    /** 归一 `engine` 参数（兼收文档/TS 里的旧叫法；未知取值中文报错，不猜） */
    fun normalizeEngine(raw: String?): String = when (raw?.trim()?.lowercase()) {
        null, "", REQUEST_AUTO -> REQUEST_AUTO
        REQUEST_MEMORY, "tier1", "tier-1", "in-memory", "内存" -> REQUEST_MEMORY
        REQUEST_COLUMNAR, "tier2", "tier-2", "duckdb", "列式" -> REQUEST_COLUMNAR
        else -> throw IllegalArgumentException(
            "engine 只支持 auto / memory / columnar，收到：$raw",
        )
    }

    /**
     * 数据行数（不含表头）的**路由级**计数。
     *
     * 只用于路由判定，不是统计口径——对外回报的行数由所选引擎给出。
     * 口径对齐 [Csv]：引号内的换行不切断逻辑行、全空逻辑行丢弃；数到 [cap] 即早停，
     * 免得为百万行文件把全程数完（那正是 tier-2 要解决的问题）。
     *
     * @return 行数；以及是否在 cap 处早停（true 时结果只保证「≥ cap」）
     */
    fun countDataRows(file: File, cap: Int): Pair<Int, Boolean> {
        var lines = 0
        var headerSeen = false
        var hasContent = false
        var inQuotes = false
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            var raw = reader.read()
            while (raw != -1) {
                val ch = raw.toChar()
                when {
                    // Csv 同款宽松配对：遇到 " 就翻面（够用于「换行是否在引号内」的判定）
                    ch == '"' -> {
                        inQuotes = !inQuotes
                        hasContent = true
                    }
                    ch == '\n' && !inQuotes -> {
                        if (hasContent) {
                            // 第一条逻辑行是表头，不计入数据行
                            if (headerSeen) {
                                lines += 1
                                if (lines >= cap) return lines to true
                            } else {
                                headerSeen = true
                            }
                        }
                        hasContent = false
                    }
                    !ch.isWhitespace() -> hasContent = true
                }
                raw = reader.read()
            }
        }
        if (hasContent && headerSeen) lines += 1  // 末行无换行符
        return lines to false
    }

    /** 决策一次：走哪档、依据是什么、要不要提示截断 */
    fun decide(file: File, engine: String?): Decision {
        val requested = normalizeEngine(engine)
        val threshold = EngineRouter.DEFAULT_TIER_THRESHOLD
        val tier2Available = DuckDbEngine.isAvailable()
        if (requested == REQUEST_COLUMNAR && !tier2Available) {
            throw IllegalStateException(
                "当前宿主没有 DuckDB 原生库，engine=columnar 无法满足；" +
                    "去掉 engine 参数可自动走 tier-1（≤$threshold 行的文件全量统计）",
            )
        }
        val (dataRows, capped) = countDataRows(file, threshold + 1)
        val tier = EngineRouter.selectTier(
            rowCount = dataRows,
            threshold = threshold,
            bigMode = requested == REQUEST_COLUMNAR,
            tier2Available = tier2Available,
            reportPath = requested == REQUEST_MEMORY,
        )
        return Decision(
            tier = tier,
            engine = if (tier == EngineTier.IN_MEMORY) ENGINE_TIER1 else ENGINE_TIER2,
            requested = requested,
            dataRows = dataRows,
            rowsCountCapped = capped,
            threshold = threshold,
            tier2Available = tier2Available,
            needsTruncationNotice = EngineRouter.needsTruncationNotice(dataRows, tier, threshold),
        )
    }

    /**
     * 建立 tier-1 数据集（[Csv.parseDelimited] → [Dataset]）。
     *
     * 用 `parseDelimited` 而非 `parseDelimitedFull`：后者不截断，是给 bench 对拍用的；
     * 产品路径保留 10K 软约束 + 显式截断提示。
     */
    fun tier1Dataset(file: File): Dataset {
        val size = file.length()
        if (size > MAX_TIER1_BYTES) {
            throw IllegalStateException(
                "文件 ${(size / 1024 / 1024).coerceAtLeast(1)}MB 超过 tier-1 内存解析上限 " +
                    "${MAX_TIER1_BYTES / 1024 / 1024}MB，且本机 tier-2（DuckDB）不可用。" +
                    "请安装 DuckDB 原生库后重试，或先截取样本文件。",
            )
        }
        return datasetFromParsed(
            name = file.name,
            importedAt = System.currentTimeMillis(),
            table = Csv.parseDelimited(file.readText(Charsets.UTF_8)),
        )
    }

    /**
     * 最终的截断提示文案。
     *
     * 判定层（[EngineRouter.needsTruncationNotice]）决定「要不要说」，解析层（tier-1 实际
     * 吃进多少行）决定「怎么说」；两者任一命中就必须说，不许静默降级。
     * tier-2 看到全量数据，恒为 null。
     */
    fun truncationNotice(decision: Decision, dataset: Dataset?): String? {
        val truncated = dataset?.truncated == true
        if (!truncated && !decision.needsTruncationNotice) return null
        val seen = dataset?.rows?.size ?: decision.threshold
        val total = when {
            dataset != null && dataset.sourceRows > seen -> "${dataset.sourceRows} 行"
            decision.rowsCountCapped -> "不止 ${decision.threshold} 行"
            dataset != null -> "${dataset.sourceRows} 行"
            else -> "${decision.dataRows} 行"
        }
        val why = if (!decision.tier2Available) "（本机没有 DuckDB 原生库，tier-2 不可用，故回落 tier-1）" else ""
        return "口径提示：本次走 tier-1（内存引擎），只统计了前 $seen 行，文件实际 $total$why——" +
            "其余行未参与统计。需要全量口径请用带 DuckDB 的宿主或 engine=columnar。"
    }

    /**
     * tier-1 列索引寻址：按表头原文找列，找不到就把该文件的列清单报回去。
     *
     * 文案与 tier-2（DuckDbEngine）逐字对齐：`role` 传「列」/「维度列」/「指标列」，
     * 两档的「列不存在」报错同构，调用方不必按档位分支处理。
     */
    fun columnIndex(dataset: Dataset, name: String, role: String): Int {
        val index = dataset.columns.indexOfFirst { it == name }
        if (index < 0) {
            throw IllegalArgumentException(
                "$role「$name」不存在（该文件的列：${dataset.columns.joinToString("、")}）",
            )
        }
        return index
    }
}

/**
 * 决策依据 → JSON（两个按路径分析的工具共用）。
 *
 * 「这个数字来自哪一档、按什么判定」必须可回答：把阈值、行数列数、tier-2 可用性
 * 一并回报，外部 agent 才能判断结果值不值得信。
 */
internal fun routingJson(decision: CsvTierRouting.Decision): JsonObject = buildJsonObject {
    put("requested", decision.requested)
    put("dataRows", decision.dataRows)
    put("rowsCountCapped", decision.rowsCountCapped)
    put("threshold", decision.threshold)
    put("tier2Available", decision.tier2Available)
    put("tier", decision.tier.name)
}
