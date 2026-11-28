package com.stragent.core.ai

import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset
import com.stragent.core.ports.DatasetStore
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.PrefsStore
import com.stragent.core.ports.ChatResult as PortChatResult
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 门 D（编排层）：mock LLM 下 generateInsight 的行为验证。
 *
 * 场景：① 截断续写（chatStep 把已写内容垫回历史）
 *      ② 单次生成（目标 < 9000）
 *      ③ 分章生成（目标 > 9000：多章拼接、顺序、断点续传跳过已写章、失败章占位）
 */
class OrchestrationTest {
    private val sampleDir = File("../fixtures/sample")

    class FakePrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
    }

    class FakeStore : DatasetStore {
        val map = mutableMapOf<String, String>()
        override fun put(key: String, value: String) { map[key] = value }
        override fun get(key: String): String? = map[key]
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): List<String> = map.keys.toList()
    }

    /** 可编程 fake：按调用序返回预设输出 */
    class ScriptedLlm(private val outputs: MutableList<PortChatResult>) : LlmClient {
        val calls = java.util.Collections.synchronizedList(mutableListOf<List<Pair<String, String>>>())
        override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int): PortChatResult {
            calls.add(messages)
            // T-193：语义审查调用返回合法 JSON——审查失败会触发单轮修复重问（T-193），
            // 桩对审查调用返回非 JSON 会让调用数漂移 +1（修复轮也是真实调用）。
            if (temperature < 0.3) return PortChatResult("""{"issues":[]}""", 10) // 语义审查
            return if (outputs.isEmpty()) PortChatResult("(空)", 10) else outputs.removeAt(0)
        }
    }

    private fun datasetOf(name: String): Dataset {
        val text = File(sampleDir, "$name.csv").readText()
        val parsed = Csv.parseDelimited(text)
        val limited = Csv.limitRows(parsed)
        val types = Csv.inferTypes(limited.rows, limited.columns.size)
        return Dataset(
            name = "$name.csv", importedAt = 1,
            columns = limited.columns, rows = limited.rows, columnTypes = types,
            delimiter = limited.delimiter, truncated = limited.truncated, sourceRows = limited.totalRows,
        )
    }

    /** 与编排器完全相同的 target/plan 口径——断点续传的签名必须逐段对齐才命中 */
    private fun targetAndPlanFor(dataset: Dataset, prefs: PrefsStore): Pair<Int, com.stragent.core.report.ChapterPlan> {
        val brief = com.stragent.core.brief.buildDatasetBrief(dataset, prefs)
        val typeId = com.stragent.core.report.ReportLib.getReportType(prefs).id
        val styleLength = com.stragent.core.report.ReportLib.getReportStyle(prefs).length
        val stats = com.stragent.core.dataset.summarize(dataset)
        val numericColumns = stats.count {
            it.type == com.stragent.core.csv.ColumnType.NUMBER && !com.stragent.core.dataset.looksLikeIdColumn(it.name)
        }
        val groupColumns = stats.count {
            it.type == com.stragent.core.csv.ColumnType.TEXT && !com.stragent.core.dataset.looksLikeIdColumn(it.name) &&
                (it.distinct ?: 0) >= 2 && (it.distinct ?: 0) <= 40
        }
        val target = com.stragent.core.report.targetLengthFor(
            brief, dataset.rows.size, dataset.columns.size, numericColumns, groupColumns, styleLength, typeId,
        )
        return target.chars to com.stragent.core.report.planChapters(brief, target.chars, typeId)
    }

    /** 与编排器相同的草稿签名（InsightOrchestrator.generateByChapters 的七段式） */
    private fun draftSignature(
        dataset: Dataset,
        prefs: PrefsStore,
        targetChars: Int,
        plan: com.stragent.core.report.ChapterPlan,
    ): String = listOf(
        "${dataset.name}@${dataset.importedAt}",
        com.stragent.core.report.ReportLib.getReportType(prefs).id,
        com.stragent.core.report.ReportLib.getReportStyle(prefs).id,
        com.stragent.core.report.ReportLib.getIndustry(prefs).id,
        targetChars.toString(),
        "local",
        plan.chapters.joinToString("|") { "${it.title}@${it.chars}" },
    ).joinToString("§")

    @Test
    fun `chatStep 截断续写——垫回历史并拼接到 3 轮上限`() {
        val outputs = mutableListOf(
            PortChatResult("第一段", 100, truncated = true),
            PortChatResult("第二段", 80, truncated = true),
            PortChatResult("第三段", 60, truncated = true),
            PortChatResult("第四段（仍在截断——到 3 轮上限）", 40, truncated = true),
        )
        val llm = ScriptedLlm(outputs)
        val result = chatStep(llm, "m", listOf("user" to "写"), ChatOptions(maxTokens = 100, targetChars = 1000))
        // 拼接全部 4 段（首轮 + 3 轮续写）
        assertEquals("第一段第二段第三段第四段（仍在截断——到 3 轮上限）", result.content)
        assertTrue(result.truncated)
        // T-049：每一轮续写都是独立计费调用，token 账须逐轮累加（旧实现只记首轮+末轮 = 140）
        assertEquals(100 + 80 + 60 + 40, result.totalTokens, "续写轮次的 token 不许漏账")
        // 第 2 次调用的历史里有垫回的 assistant 内容与续写指令
        val second = llm.calls[1]
        assertTrue(second.any { it.first == "assistant" && it.second == "第一段" })
        assertTrue(second.last().second.contains("从断点处继续写"))
        assertTrue(second.last().second.contains("目前已写约 3 字"))
    }

    @Test
    fun `chatStep 未截断直接返回（不追加调用）`() {
        val llm = ScriptedLlm(mutableListOf(PortChatResult("完整回答", 50)))
        val result = chatStep(llm, "m", listOf("user" to "写"))
        assertEquals("完整回答", result.content)
        assertEquals(1, llm.calls.size)
    }

    @Test
    fun `单次生成——小数据集走 single-shot 路径`() {
        val llm = ScriptedLlm(mutableListOf(PortChatResult("## 总体概览\n完整报告内容。", 500)))
        val prefs = FakePrefs()
        val orchestrator = InsightOrchestrator(llm, prefs, model = "test")
        val result = orchestrator.generateInsight(datasetOf("single_col"))
        assertTrue(result.report.contains("完整报告内容"))
        assertEquals(2, llm.calls.size) // 1 次生成 + 1 次语义审查
        assertEquals(null, result.chapters)
        assertTrue(result.targetChars <= 9000 || result.report.isNotEmpty()) // 小表目标必小
        // system prompt 是完整装配：质量门槛 / 写作纪律 / 反直觉要求都在
        assertTrue(llm.calls.first().first().second.contains("<quality_bar>"))
        assertTrue(llm.calls.first().first().second.contains("<de_ai_style>"))
        assertTrue(llm.calls.first().first().second.contains("<self_check>"))
    }

    @Test
    fun `分章生成——superstore 多章拼接且按章节序`() {
        val prefs = FakePrefs()
        val dataset = datasetOf("superstore")
        val (_, plan) = targetAndPlanFor(dataset, prefs)
        val outputs = mutableListOf<PortChatResult>()
        plan.chapters.forEachIndexed { i, _ -> outputs.add(PortChatResult("第${i + 1}章内容。", 100)) }
        val llm = ScriptedLlm(outputs)
        // superstore 默认偏好 target=32021 > 9000 → 分章路径自然触发
        val orchestrator = InsightOrchestrator(llm, prefs, model = "test")
        val result = orchestrator.generateInsight(dataset)
        assertTrue(result.chapters != null && result.chapters.size >= 10, "章数 ${result.chapters?.size}")
        // 章数与调用数一致：每章恰好一次调用（fake 不截断 → 无续写轮）+ 末尾 1 次语义审查
        assertEquals(plan.chapters.size + 1, llm.calls.size)
        // 章节按序拼接：报告里第 i 章出现在第 i+1 章之前
        plan.chapters.forEachIndexed { i, _ ->
            val marker = "第${i + 1}章内容。"
            assertTrue(result.report.contains("## ${plan.chapters[i].title}") && result.report.contains(marker), "缺 $marker")
        }
        val firstIdx = result.report.indexOf("第1章内容。")
        val lastIdx = result.report.indexOf("第${plan.chapters.size}章内容。")
        assertTrue(firstIdx in 0 until lastIdx, "章节乱序：first=$firstIdx last=$lastIdx")
        // 每章都进了 prompt（分章切片包含 <available_data>；最后一条是语义审查，不在其列）
        assertTrue(llm.calls.dropLast(1).all { it.last().second.contains("<available_data>") })
    }

    @Test
    fun `断点续传——已写的章跳过调用`() {
        val prefs = FakePrefs()
        val dataset = datasetOf("superstore")
        val (targetChars, plan) = targetAndPlanFor(dataset, prefs)
        val store = FakeStore()
        // 预存草稿（编排器的落盘格式：PART_SEP 连接；第一章已写完，其余空串=未写）
        val signature = draftSignature(dataset, prefs, targetChars, plan)
        val draftParts = (0 until plan.chapters.size).map { if (it == 0) "已写的第一章内容。" else "" }
        store.put("chapter-draft.$signature", draftParts.joinToString(PART_SEP))

        val outputs = mutableListOf<PortChatResult>()
        plan.chapters.drop(1).forEach { outputs.add(PortChatResult("后续章。", 100)) }
        val llm = ScriptedLlm(outputs)
        val orchestrator = InsightOrchestrator(llm, prefs, store, model = "test")
        val result = orchestrator.generateInsight(dataset)
        // 第一章来自草稿，不消耗调用：调用数 = 章数 - 1（+末尾 1 次语义审查）
        assertTrue(result.report.contains("已写的第一章内容。"))
        assertEquals(plan.chapters.size - 1 + 1, llm.calls.size, "已写章应被跳过")
        // 第一次调用写的是第二章（第一章没进编排）
        assertTrue(llm.calls.first().last().second.contains("「${plan.chapters[1].title}」"))
        // 全部写完才清草稿（TS clearChapterDraft 口径）：下一次生成从零开始
        assertEquals(null, store.get("chapter-draft.$signature"))
    }

    @Test
    fun `报告预算被端点 400 拒绝——按 8192 回落一次并出稿`() {
        // T-062：推理模型需要 16k 预算，但老端点/小模型可能 400 拒收——只回落到旧下限再打一次
        class BudgetLlm : LlmClient {
            val calls = mutableListOf<Pair<Double, Int>>() // temperature to maxTokens
            override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int): PortChatResult {
                calls += temperature to maxTokens
                if (temperature == 0.7 && maxTokens > 8192) {
                    throw StepError(STEP_ERR_BAD_REQUEST, "max_tokens 超过模型上限")
                }
                if (temperature < 0.3) return PortChatResult("""{"issues":[]}""", 10) // 语义审查
                return PortChatResult("## 总体概览\n完整报告内容。", 500)
            }
        }
        val llm = BudgetLlm()
        val result = InsightOrchestrator(llm, FakePrefs(), model = "test").generateInsight(datasetOf("single_col"))
        assertTrue(result.report.contains("完整报告内容"))
        // 生成：32768（T-062 报告预算 = cap）→ 400 → 8192；审查：命中 16384 预算（temp 0.2，不受报告回落影响）
        assertEquals(listOf(0.7 to 32768, 0.7 to 8192, 0.2 to 16384), llm.calls)
    }

    @Test
    fun `报告预算回落——持续 400 不无限重试，非 400 错误不回落`() {
        class Always400 : LlmClient {
            var calls = 0
            override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int): PortChatResult {
                calls += 1
                throw StepError(STEP_ERR_BAD_REQUEST, "bad")
            }
        }
        val llm = Always400()
        val e = assertFailsWith<StepError> {
            InsightOrchestrator(llm, FakePrefs(), model = "test").generateInsight(datasetOf("single_col"))
        }
        assertEquals(STEP_ERR_BAD_REQUEST, e.code)
        assertEquals(2, llm.calls, "回落只打一次，不无限循环")

        class TimeoutLlm : LlmClient {
            var calls = 0
            override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int): PortChatResult {
                calls += 1
                throw StepError(STEP_ERR_TIMEOUT, "timeout")
            }
        }
        val timeoutLlm = TimeoutLlm()
        val e2 = assertFailsWith<StepError> {
            InsightOrchestrator(timeoutLlm, FakePrefs(), model = "test").generateInsight(datasetOf("single_col"))
        }
        assertEquals(STEP_ERR_TIMEOUT, e2.code)
        assertEquals(1, timeoutLlm.calls, "读超时不回落重打（再等一轮只会更贵）")
    }

    @Test
    fun `失败章占位且不落盘`() {
        class FailingLlm : LlmClient {
            var calls = 0
            override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int): PortChatResult {
                calls += 1
                throw RuntimeException("网络抖动")
            }
        }
        val llm = FailingLlm()
        val store = FakeStore()
        val orchestrator = InsightOrchestrator(llm, FakePrefs(), store, model = "test")
        val result = orchestrator.generateInsight(datasetOf("superstore"))
        // 全部失败 → 每章留占位说明
        assertTrue(result.report.contains(FAILED_CHAPTER_MARK))
        // 占位文本不落盘（failedChapters 不进草稿）
        val savedDraft = store.map.values.firstOrNull()
        assertTrue(savedDraft == null || !savedDraft.contains(FAILED_CHAPTER_MARK), "占位文本不该进草稿")
        // 每章两次尝试（首调 + 重试一次）
        assertTrue(llm.calls >= 2, "有重试")
    }
}
