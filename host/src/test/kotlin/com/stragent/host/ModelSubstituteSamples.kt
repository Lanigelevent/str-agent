package com.stragent.host

import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset
import kotlin.math.roundToInt

/**
 * T-037 · 真实模型质量抽样 —— 模型替代样本（8 份，覆盖 8 种报告类型）。
 *
 * 为什么要有这个文件：真实第三方模型质量抽样需要可用 Key，环境里没有；
 * 由 agent 充当「模型替代」，按**真实流水线**产出 8 份报告，用确定性闸门
 * （verifyReport 数字溯源 + WritingAudit 写作质量）复核。样本数据是有意构造的，
 * 但统计口径、提示词、校验器全部是生产代码，不绕过任何一条。
 *
 * 样本设计原则：
 *  - 一型一业：8 种报告类型各一份，行业互不重复；
 *  - 每份数据都埋一个「可被发现的真问题」（异常/拐点/质量缺陷），避免报告无事可说；
 *  - 数值全部整数或两位小数，便于逐字引用摘要、避免精度判据误伤。
 */
internal data class ModelSubstituteSample(
    val id: String,
    val title: String,
    val typeId: String,
    val styleId: String,
    val industryId: String,
    val csv: String,
) {
    fun dataset(): Dataset {
        val parsed = Csv.parseDelimitedFull(csv)
        return Dataset(
            name = "t037-$id.csv",
            importedAt = 0L,
            columns = parsed.columns,
            rows = parsed.rows,
            columnTypes = parsed.columnTypes,
            delimiter = parsed.delimiter,
            truncated = parsed.truncated,
            sourceRows = parsed.totalRows,
        )
    }
}

internal object ModelSubstituteSamples {

    val all: List<ModelSubstituteSample> = listOf(
        ModelSubstituteSample(
            id = "s1-ecommerce-standard",
            title = "电商促销与退款（标准分析报告）",
            typeId = "standard",
            styleId = "professional",
            industryId = "ecommerce",
            csv = ecommerceCsv(),
        ),
        ModelSubstituteSample(
            id = "s2-saas-executive",
            title = "SaaS 月度经营（一页纸执行摘要）",
            typeId = "executive",
            styleId = "executive",
            industryId = "saas",
            csv = saasCsv(),
        ),
        ModelSubstituteSample(
            id = "s3-hospitality-bulletin",
            title = "连锁门店日报（数据快讯）",
            typeId = "bulletin",
            styleId = "concise",
            industryId = "hospitality",
            csv = hospitalityCsv(),
        ),
        ModelSubstituteSample(
            id = "s4-manufacturing-deepdive",
            title = "产线良率与能耗（专题深挖）",
            typeId = "deepdive",
            styleId = "professional",
            industryId = "manufacturing",
            csv = manufacturingCsv(),
        ),
        ModelSubstituteSample(
            id = "s5-education-compare",
            title = "校区经营横评（对比评估）",
            typeId = "compare",
            styleId = "professional",
            industryId = "education",
            csv = educationCsv(),
        ),
        ModelSubstituteSample(
            id = "s6-logistics-anomaly",
            title = "干线时效异常（异常排查报告）",
            typeId = "anomaly",
            styleId = "detective",
            industryId = "logistics",
            csv = logisticsCsv(),
        ),
        ModelSubstituteSample(
            id = "s7-finance-trend",
            title = "收入结构（趋势研判）",
            typeId = "trend",
            styleId = "professional",
            industryId = "finance",
            csv = financeCsv(),
        ),
        ModelSubstituteSample(
            id = "s8-healthcare-quality",
            title = "门诊记录质量（数据质量报告）",
            typeId = "quality",
            styleId = "professional",
            industryId = "healthcare",
            csv = healthcareCsv(),
        ),
    )

    private data class Channel(val name: String, val index: Int, val baseHome: Int, val baseDigital: Int)
    private data class Category(val name: String, val price: Double)

    /** 12 个月 × 3 渠道 × 2 品类：11 月大促折扣抬升，退款同步走高 */
    private fun ecommerceCsv(): String {
        val sb = StringBuilder("月份,渠道,品类,订单数,销售额,退款额,折扣率\n")
        val channels = listOf(
            Channel("天猫", 0, 120, 70),
            Channel("京东", 1, 90, 55),
            Channel("抖音", 2, 70, 40),
        )
        val categories = listOf(Category("家居", 180.0), Category("数码", 520.0))
        for (m in 1..12) {
            val month = "2025-%02d".format(m)
            for (channel in channels) {
                for (gi in categories.indices) {
                    val season = when (m) {
                        2 -> 0.82
                        6 -> 1.35
                        11 -> 1.75
                        12 -> 1.20
                        else -> 1.0
                    }
                    val baseOrders = if (gi == 0) channel.baseHome else channel.baseDigital
                    val growth = 1.0 + channel.index * 0.02 * (m - 1)
                    val orders = (baseOrders * season * growth).roundToInt()
                    val rawDiscount = when (m) {
                        11 -> 0.32
                        6 -> 0.22
                        else -> 0.12 + ((m + channel.index + gi) % 4) * 0.015
                    }
                    val discount = (rawDiscount * 100).roundToInt() / 100.0
                    val price = categories[gi].price * if (m >= 9) 1.02 else 1.0
                    val sales = (price * orders * (1 - discount)).roundToInt()
                    val refundRate = 0.04 + discount * 0.12 + if (gi == 1) 0.01 else 0.0
                    val refund = (sales * refundRate).roundToInt()
                    sb.append("$month,${channel.name},${categories[gi].name},$orders,$sales,$refund,$discount\n")
                }
            }
        }
        return sb.toString()
    }

    /** 12 个月：MRR 稳步上行，流失用户自 10 月起抬升（增长质量的分歧点） */
    private fun saasCsv(): String {
        val sb = StringBuilder("月份,新增用户,活跃用户,付费用户,流失用户,MRR\n")
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

    private data class Store(val name: String, val avgPrice: Int, val baseRevenue: Int)

    /** 14 天 × 3 门店：周末抬升，城西店 10-06 投诉集中 */
    private fun hospitalityCsv(): String {
        val sb = StringBuilder("日期,门店,营收,订单数,客单价,投诉数\n")
        val stores = listOf(
            Store("江畔店", 68, 38000),
            Store("城西店", 74, 30000),
            Store("高新店", 82, 24000),
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
                    store.name == "城西店" && day == 6 -> 5
                    store.name == "城西店" && day in listOf(5, 12) -> 3
                    (day + index) % 6 == 0 -> 1
                    else -> 0
                }
                sb.append("$date,${store.name},$revenue,$orders,${store.avgPrice},$complaints\n")
            }
        }
        return sb.toString()
    }

    private data class ProductionLine(val name: String, val base: Int, val baseDowntime: Int)

    /** 6 个月 × 3 条产线：C 线 5–6 月停机抬升、单耗走高（深挖对象） */
    private fun manufacturingCsv(): String {
        val sb = StringBuilder("产线,月份,产量,良品数,停机时长,单位能耗\n")
        val lines = listOf(
            ProductionLine("A线", 12000, 8),
            ProductionLine("B线", 10500, 11),
            ProductionLine("C线", 9800, 14),
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

    private data class CampusRow(
        val campus: String,
        val type: String,
        val signup: Int,
        val finished: Int,
        val refund: Int,
        val score: Double,
    )

    /** 4 校区 × 3 班型：规模与平均分反向（横评的冲突点） */
    private fun educationCsv(): String {
        val rows = listOf(
            CampusRow("城东", "大班", 300, 255, 12000, 76.8),
            CampusRow("城东", "小班", 210, 196, 6400, 80.2),
            CampusRow("城东", "一对一", 110, 104, 3000, 82.6),
            CampusRow("城西", "大班", 260, 230, 9800, 79.5),
            CampusRow("城西", "小班", 180, 170, 5200, 83.4),
            CampusRow("城西", "一对一", 100, 96, 2600, 85.1),
            CampusRow("滨江", "大班", 200, 180, 7600, 78.9),
            CampusRow("滨江", "小班", 140, 133, 4100, 82.2),
            CampusRow("滨江", "一对一", 70, 67, 1800, 84.0),
            CampusRow("高新", "大班", 160, 148, 5200, 81.7),
            CampusRow("高新", "小班", 120, 115, 3300, 85.8),
            CampusRow("高新", "一对一", 50, 49, 1200, 88.2),
        )
        val sb = StringBuilder("校区,班型,报名人数,完课人数,退费金额,平均分\n")
        for (row in rows) {
            sb.append("${row.campus},${row.type},${row.signup},${row.finished},${row.refund},${row.score}\n")
        }
        return sb.toString()
    }

    private data class Route(val name: String, val base: Int, val index: Int)

    /** 20 天 × 3 条干线：西南线 09-17 延误骤增（异常样本） */
    private fun logisticsCsv(): String {
        val sb = StringBuilder("线路,日期,准时单量,延误单量,车次,平均时效,单均成本\n")
        val routes = listOf(
            Route("华东线", 220, 0),
            Route("华南线", 180, 1),
            Route("西南线", 150, 2),
        )
        for (day in 1..20) {
            val date = "2025-09-%02d".format(day)
            for (route in routes) {
                val wobble = 1.0 + ((day + route.index) % 4 - 1.5) * 0.03
                val onTime = (route.base * wobble).roundToInt()
                val delayed = 4 + (day + route.index) % 7 + if (day % 6 == 0) 3 else 0
                val trips = 14 + (day + route.index) % 5
                val spike = route.name == "西南线" && day == 17
                val hours = if (spike) 9.8 else 5.2 + ((day + route.index) % 5) * 0.3
                val cost = if (spike) 58.6 else 42.0 + ((day + route.index) % 4) * 1.5
                sb.append("${route.name},$date,$onTime,${if (spike) 48 else delayed},$trips,$hours,$cost\n")
            }
        }
        return sb.toString()
    }

    /** 12 个月 × 3 个科目：4 月回落、11 月冲高，预算全年不变 */
    private fun financeCsv(): String {
        val sb = StringBuilder("科目,月份,金额,预算金额\n")
        val subjects = listOf("销售收入" to 1.0, "服务收入" to 0.55, "其他收入" to 0.18)
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

    private data class Department(val name: String, val baseVisits: Int, val baseWait: Double, val bedUse: Double, val readmit: Double)

    /** 15 天 × 3 科室：备注大量空值 + 3 行完全重复（质量样本） */
    private fun healthcareCsv(): String {
        val sb = StringBuilder("科室,日期,备注,门诊人次,平均候诊分钟,床位使用率,再入院率\n")
        val departments = listOf(
            Department("内科", 520, 22.0, 0.92, 0.031),
            Department("外科", 380, 28.0, 0.88, 0.042),
            Department("儿科", 300, 16.0, 0.76, 0.018),
        )
        val rows = mutableListOf<String>()
        for (day in 1..15) {
            val date = "2025-09-%02d".format(day)
            val weekend = day in listOf(6, 7, 13, 14)
            for ((index, dept) in departments.withIndex()) {
                val visits = (dept.baseVisits * (if (weekend) 0.82 else 1.0) * (1 + ((day + index) % 4 - 1.5) * 0.02)).roundToInt()
                val wait = ((dept.baseWait + ((day + index) % 5 - 2) * 1.5) * 10).roundToInt() / 10.0
                val bedUse = dept.bedUse + ((day + index) % 3 - 1) * 0.02
                val bedRounded = (bedUse * 100).roundToInt() / 100.0
                val note = when {
                    day == 4 && dept.name == "内科" -> "设备检修"
                    day == 9 && dept.name == "外科" -> "系统切换"
                    day == 12 && dept.name == "儿科" -> "义诊"
                    else -> ""
                }
                rows.add("${dept.name},$date,$note,$visits,$wait,$bedRounded,${dept.readmit}")
            }
        }
        // 3 行完全重复：唯一性扣分，且会让计数类指标虚高
        rows.add(rows[2])
        rows.add(rows[10])
        rows.add(rows[20])
        rows.forEach { sb.append(it).append('\n') }
        return sb.toString()
    }
}
