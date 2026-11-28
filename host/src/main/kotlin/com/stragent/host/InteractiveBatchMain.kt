package com.stragent.host

import com.stragent.core.ai.InsightOrchestrator
import com.stragent.core.brief.buildDatasetBrief
import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset
import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.PrefsStore
import com.stragent.core.report.ReportLib
import com.stragent.data.StepfunConfig
import java.io.File
import kotlin.math.roundToInt
import kotlin.system.exitProcess

/**
 * 交互式替代批测（2026-11-14 用户裁决；T-135 判据③的深化）：
 * **会话模型（GLM）作为多轮现场后端**，跑通 classic 报告流水线的完整多轮生成——
 * 每轮 prompt 落盘 → 会话模型生成应答 → 存回应答文件 → 重跑续放（确定性回放）。
 *
 * 与两段式回放（T135LiveModelChainTest）的区别：批测的章节 prompt **依赖前序应答**，
 * 两段式无法诚实驱动；本驱动用「checkpoint-exit」模式逐轮推进——
 * 每次运行回放已就位的应答，走到下一个缺应答的调用点，落盘 prompt 后退出（exit 10）。
 *
 * 诚实边界：
 *  - 应答由**本会话模型**现场生成（对真实流水线 prompt 的真实多轮生成）——
 *    质量结论是「GLM-5.3-Flash 作为后端」的表现，不是厂商结论；
 *  - usage 记账是合成的（content.length/2）——真实 usage 需要厂商 serving；
 *  - 目标短体型（executive，一页纸）以控制轮数；样本 = saasCsv（12 月 SaaS 经营数据，
 *    与 ModelSubstituteSamples.s2 同源），类型/风格/行业按该样本配置。
 *
 * 运行：`./gradlew :host:run --args="interactive-batch <seq 目录>" --no-daemon`
 * 退出码 10 = 本轮落盘了一个新 prompt（待会话模型应答后重跑）；0 = 报告完成。
 */
fun interactiveBatchMain(seqDir: File, sampleId: String = "s2") {
    val promptsDir = File(seqDir, "prompts").apply { mkdirs() }
    val responsesDir = File(seqDir, "responses").apply { mkdirs() }

    val prefs = object : PrefsStore {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String) = values[key] ?: fallback
        override fun set(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
    }
    // 双样本（D25 交互式批测）：s1=电商 standard（多章长跑）/ s2=SaaS executive（短体型）
    val isS1 = sampleId.startsWith("s1")
    val isS3 = sampleId.startsWith("s3")
    val isS4 = sampleId.startsWith("s4")
    val isS6 = sampleId.startsWith("s6")
    val isS7 = sampleId.startsWith("s7")
    val isS8 = sampleId.startsWith("s8")
    ReportLib.setReportType(prefs, if (isS1) "standard" else if (isS3) "bulletin" else if (isS4) "deepdive" else if (isS6) "anomaly" else if (isS7) "trend" else if (isS8) "quality" else "executive")
    ReportLib.setReportStyle(prefs, if (isS1) "professional" else if (isS3) "concise" else if (isS4) "professional" else if (isS6) "detective" else if (isS7) "professional" else if (isS8) "professional" else "executive")
    ReportLib.setIndustry(prefs, if (isS1) "ecommerce" else if (isS3) "hospitality" else if (isS4) "manufacturing" else if (isS6) "logistics" else if (isS7) "finance" else if (isS8) "healthcare" else "saas")

    val llm = InteractiveLlmClient(promptsDir, responsesDir)
    val csvText = if (isS1) interactiveEcommerceCsv() else if (isS3) interactiveHospitalityCsv() else if (isS4) interactiveManufacturingCsv() else if (isS6) interactiveLogisticsCsv() else if (isS7) interactiveFinanceCsv() else if (isS8) interactiveHealthcareCsv() else interactiveSaasCsv()
    val csvName = if (isS1) "ecommerce-promo.csv" else if (isS3) "hospitality-daily.csv" else if (isS4) "production-lines.csv" else if (isS6) "logistics-routes.csv" else if (isS7) "finance-monthly.csv" else if (isS8) "outpatient-notes.csv" else "saas-monthly.csv"
    val parsed = Csv.parseDelimitedFull(csvText)
    val dataset = Dataset(
        name = csvName,
        importedAt = 0L,
        columns = parsed.columns,
        rows = parsed.rows,
        columnTypes = parsed.columnTypes,
        delimiter = parsed.delimiter,
        truncated = parsed.truncated,
        sourceRows = parsed.totalRows,
    )
    val brief = buildDatasetBrief(dataset, prefs)

    val result = InsightOrchestrator(llm, prefs, model = "assistant-live").generateInsight(dataset)

    // 完成：报告落盘 + 回执事实
    val out = File(seqDir, "report.md")
    out.writeText(result.report, Charsets.UTF_8)
    println("[interactive] 报告完成：${out.absolutePath}（${result.report.length} 字）")  // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
    println("[interactive] gateFailed=${result.gateFailed} gateIssues=${result.gateIssues.size} " +  // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        "gateRevisions=${result.gateRevisions} 调用数=${responsesDir.listFiles()?.size ?: 0}")  // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
    for (note in result.qualityNotes) {
        println("[interactive] qualityNote: $note")
    }
    // 语义审查的 issues 在 semantic 字段（T-062：只有「审查失败」才进 qualityNotes）
    for (issue in result.semantic) {
        println("[interactive] semantic: ${issue.kind} | ${issue.quote} | ${issue.why}")
    }
    println("[interactive] brief=${brief.take(80)}…")
}

/** 确定性回放客户端：有应答就服务，没有就落盘 prompt 并退出（checkpoint-exit） */
internal class InteractiveLlmClient(
    private val promptsDir: File,
    private val responsesDir: File,
) : LlmClient {
    private var served = 0

    override fun chat(
        model: String,
        messages: List<Pair<String, String>>,
        temperature: Double,
        maxTokens: Int,
    ): ChatResult {
        // seq = 本次运行内的调用序数（确定性回放：第 n 次逻辑调用 ↔ n.txt）；
        // 此前误用「文件计数」——重跑时第一笔调用就被误判成第 1 次，重复落盘同一 prompt。
        val seq = served
        served += 1
        val respFile = File(responsesDir, "%03d.txt".format(seq))
        if (!respFile.exists()) {
            val dump = buildString {
                appendLine("== CALL #$seq · model=$model · temperature=$temperature · maxTokens=$maxTokens ==")
                for ((role, content) in messages) {
                    appendLine("----- $role -----")
                    appendLine(content)
                }
            }
            File(promptsDir, "%03d.prompt.txt".format(seq)).writeText(dump, Charsets.UTF_8)
            println("[interactive] 第 $seq 次调用的 prompt 已落盘（exit 10）：${promptsDir.absolutePath}")  // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            exitProcess(10)
        }
        val content = respFile.readText()
        // usage 记账合成（诚实边界：真实 usage 需厂商 serving）
        return ChatResult(content, totalTokens = content.length / 2)
    }

    override fun streamChat(
        model: String,
        messages: List<Pair<String, String>>,
        temperature: Double,
        maxTokens: Int,
        onToken: ((String) -> Unit)?,
    ): ChatResult {
        val r = chat(model, messages, temperature, maxTokens)
        onToken?.invoke(r.text)
        return r
    }
}

/** 样本数据（与 ModelSubstituteSamples.s1-ecommerce-standard 同源）：电商促销与退款 */
internal fun interactiveEcommerceCsv(): String {
    val sb = StringBuilder("订单量,销售额,退款额,折扣额,客单价,区域,渠道\n")  // lint:allow zh-first（样本数据列名，非 UI 文案）
    val regions = listOf("华东", "华南", "华北", "西南", "东北", "西北", "华中", "华东")  // lint:allow zh-first（样本数据区域值，非 UI 文案）
    val channels = listOf("直营", "平台", "分销", "直营", "平台", "分销", "平台", "直营")  // lint:allow zh-first（样本数据渠道值，非 UI 文案）
    var order = 1200
    for (i in 0 until 40) {
        order += 60 - (i % 5) * 12 + if (i == 6) 180 else 0
        val sales = order * (38 + i % 7)
        val refund = if (i % 8 == 6) (sales * 12 / 100) else (sales * 4 / 100)
        val discount = if (i % 6 == 3) (sales * 18 / 100) else (sales * 6 / 100)
        val perOrder = sales / order
        sb.append("$order,$sales,$refund,$discount,$perOrder,${regions[i % regions.size]},${channels[i % channels.size]}\n")
    }
    return sb.toString()
}

private data class InteractiveStore(val name: String, val avgPrice: Int, val baseRevenue: Int)

/** 样本数据（与 ModelSubstituteSamples.s3-hospitality-bulletin 同源）：连锁门店 14 天日报 */
internal fun interactiveHospitalityCsv(): String {
    val sb = StringBuilder("日期,门店,营收,订单数,客单价,投诉数\n")  // lint:allow zh-first（样本数据字面量，非 UI 文案）
    val stores = listOf(
        InteractiveStore("江畔店", 68, 38000),  // lint:allow zh-first（样本数据字面量，非 UI 文案）
        InteractiveStore("城西店", 74, 30000),  // lint:allow zh-first（样本数据字面量，非 UI 文案）
        InteractiveStore("高新店", 82, 24000),  // lint:allow zh-first（样本数据字面量，非 UI 文案）
    )
    for (day in 1..14) {
        val date = "2025-10-%02d".format(day)
        val weekend = day in listOf(4, 5, 11, 12)
        for ((index, store) in stores.withIndex()) {
            val uplift = if (weekend) 1.25 else 1.0
            val wobble = 1.0 + ((day + index) % 5 - 2) * 0.02
            val revenue = (store.baseRevenue * uplift * wobble).roundToInt()
            val orders = (revenue / store.avgPrice.toDouble()).roundToInt()
            val complaints = when {
                store.name == "城西店" && day == 6 -> 5  // lint:allow zh-first（样本数据字面量，非 UI 文案）
                store.name == "城西店" && day in listOf(5, 12) -> 3  // lint:allow zh-first（样本数据字面量，非 UI 文案）
                (day + index) % 6 == 0 -> 1
                else -> 0
            }
            sb.append("$date,${store.name},$revenue,$orders,${store.avgPrice},$complaints\n")
        }
    }
    return sb.toString()
}

private data class InteractiveLine(val name: String, val base: Int, val baseDowntime: Int)

/** 样本数据（与 ModelSubstituteSamples.s4-manufacturing-deepdive 同源）：三产线六个月 */
internal fun interactiveManufacturingCsv(): String {
    val sb = StringBuilder("产线,月份,产量,良品数,停机时长,单位能耗\n")  // lint:allow zh-first（样本数据字面量，非 UI 文案）
    val lines = listOf(
        InteractiveLine("A线", 12000, 8),
        InteractiveLine("B线", 10500, 11),
        InteractiveLine("C线", 9800, 14),
    )
    for (m in 1..6) {
        val month = "2025-%02d".format(m)
        for ((index, line) in lines.withIndex()) {
            val seasonal = 1.0 + (m - 3.5) * 0.01
            val output = (line.base * seasonal).roundToInt()
            val extraDown = when {
                line.name == "C线" && m == 5 -> 22
                line.name == "C线" && m == 6 -> 30
                line.name == "B线" && m == 6 -> 10
                else -> 0
            }
            val downtime = line.baseDowntime + ((m + index) % 3) + extraDown
            val defectRate = when {
                line.name == "C线" && m == 6 -> 0.075
                line.name == "C线" && m == 5 -> 0.055
                line.name == "B线" && m == 6 -> 0.042
                else -> 0.02 + ((m + index) % 3) * 0.003
            }
            val good = (output * (1 - defectRate)).roundToInt()
            val energy = 0.42 + downtime * 0.0022 + index * 0.008 + defectRate * 0.4
            val energyRounded = (energy * 100).roundToInt() / 100.0
            sb.append("${line.name},$month,$output,$good,$downtime,$energyRounded\n")
        }
    }
    return sb.toString()
}

private data class InteractiveRoute(val name: String, val base: Int, val index: Int)

/** 样本数据（与 ModelSubstituteSamples.s6-logistics-anomaly 同源）：三线路 20 天 */
internal fun interactiveLogisticsCsv(): String {
    val sb = StringBuilder("线路,日期,准时单量,延误单量,车次,平均时效,单均成本\n")  // lint:allow zh-first（样本数据字面量，非 UI 文案）
    val routes = listOf(
        InteractiveRoute("华东线", 220, 0),  // lint:allow zh-first（样本数据字面量，非 UI 文案）
        InteractiveRoute("华南线", 180, 1),  // lint:allow zh-first（样本数据字面量，非 UI 文案）
        InteractiveRoute("西南线", 150, 2),  // lint:allow zh-first（样本数据字面量，非 UI 文案）
    )
    for (day in 1..20) {
        val date = "2025-09-%02d".format(day)
        for (route in routes) {
            val wobble = 1.0 + ((day + route.index) % 4 - 1.5) * 0.03
            val onTime = (route.base * wobble).roundToInt()
            val delayed = 4 + (day + route.index) % 7 + if (day % 6 == 0) 3 else 0
            val trips = 14 + (day + route.index) % 5
            val spike = route.name == "西南线" && day == 17  // lint:allow zh-first（样本数据字面量，非 UI 文案）
            val hours = if (spike) 9.8 else 5.2 + ((day + route.index) % 5) * 0.3
            val cost = if (spike) 58.6 else 42.0 + ((day + route.index) % 4) * 1.5
            sb.append("${route.name},$date,$onTime,${if (spike) 48 else delayed},$trips,$hours,$cost\n")
        }
    }
    return sb.toString()
}

/** 样本数据（与 ModelSubstituteSamples.s7-finance-trend 同源）：三科目 12 个月 */
internal fun interactiveFinanceCsv(): String {
    val sb = StringBuilder("科目,月份,金额,预算金额\n")  // lint:allow zh-first（样本数据字面量，非 UI 文案）
    val subjects = listOf("销售收入" to 1.0, "服务收入" to 0.55, "其他收入" to 0.18)  // lint:allow zh-first（样本数据字面量，非 UI 文案）
    for (m in 1..12) {
        val month = "2025-%02d".format(m)
        val season = when (m) {
            4 -> 0.88
            11 -> 1.22
            12 -> 1.12
            else -> 1.0 + (m - 6.5) * 0.006
        }
        for ((subject, factor) in subjects) {
            val amount = (320 * factor * season).roundToInt()
            val budget = (300 * factor).roundToInt()
            sb.append("$subject,$month,$amount,$budget\n")
        }
    }
    return sb.toString()
}

private data class InteractiveDept(val name: String, val baseVisits: Int, val baseWait: Double, val bedBase: Double, val readmit: Double)

/** 样本数据（与 ModelSubstituteSamples.s8-healthcare-quality 同源）：三科室 15 天门诊 */
internal fun interactiveHealthcareCsv(): String {
    val sb = StringBuilder("科室,日期,备注,门诊人次,平均候诊分钟,床位使用率,再入院率\n")  // lint:allow zh-first（样本数据字面量，非 UI 文案）
    val departments = listOf(
        InteractiveDept("内科", 520, 22.0, 0.92, 0.031),  // lint:allow zh-first（样本数据字面量，非 UI 文案）
        InteractiveDept("外科", 380, 28.0, 0.88, 0.042),  // lint:allow zh-first（样本数据字面量，非 UI 文案）
        InteractiveDept("儿科", 300, 16.0, 0.76, 0.018),  // lint:allow zh-first（样本数据字面量，非 UI 文案）
    )
    for (day in 1..15) {
        val date = "2025-09-%02d".format(day)
        val weekend = day in listOf(6, 7, 13, 14)
        for ((index, dept) in departments.withIndex()) {
            val visits = (dept.baseVisits * (if (weekend) 0.82 else 1.0) * (1 + ((day + index) % 4 - 1.5) * 0.02)).roundToInt()
            val wait = ((dept.baseWait + ((day + index) % 5 - 2) * 1.5) * 10).roundToInt() / 10.0
            val bedUse = dept.bedBase + ((day + index) % 3 - 1) * 0.02
            val bedRounded = (bedUse * 100).roundToInt() / 100.0
            val note = when {
                day == 4 && dept.name == "内科" -> "设备检修"  // lint:allow zh-first（样本数据字面量，非 UI 文案）
                day == 9 && dept.name == "外科" -> "系统切换"  // lint:allow zh-first（样本数据字面量，非 UI 文案）
                day == 12 && dept.name == "儿科" -> "义诊"  // lint:allow zh-first（样本数据字面量，非 UI 文案）
                else -> ""
            }
            sb.append("${dept.name},$date,$note,$visits,$wait,$bedRounded,${dept.readmit}\n")
        }
    }
    return sb.toString()
}

/** 样本数据（与 ModelSubstituteSamples.s2-saas-executive 同源）：12 月 SaaS 经营 */
internal fun interactiveSaasCsv(): String {
    val sb = StringBuilder("月份,新增用户,活跃用户,付费用户,流失用户,MRR\n")  // lint:allow zh-first（样本数据列名，非 UI 文案）
    for (m in 1..12) {
        val month = "2025-%02d".format(m)
        val newUsers = 380 + m * 8 - if (m == 2) 60 else 0
        val active = 3000 + m * 95 + if (m == 7 || m == 8) 80 else 0
        val paid = 640 + m * 15
        val churn = 80 + m * 10 + if (m >= 10) (m - 9) * 8 else 0
        val mrr = 800 + m * 32 + if (m == 11) 40 else 0
        sb.append("$month,$newUsers,$active,$paid,$churn,$mrr\n")
    }
    return sb.toString()
}
