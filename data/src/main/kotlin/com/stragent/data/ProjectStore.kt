package com.stragent.data

import com.stragent.core.ports.DatasetStore
import com.stragent.core.ports.PrefsStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/* =============================================================================
 * 分析项目 —— project.ts 移植。一个数据集 = 一个可展开的分析工作区。
 * 项目只存「轻量状态」——数据集本体在 DatasetLibrary 里按 id 存，
 * 两边用 datasetId 关联：数据集删了，项目同步删。
 *
 * 对话持久化的取舍：报告 + 全部问答可能几十 KB——只持久化
 * 「首轮报告 + 最近 N 轮问答」，更早的轮次不落盘。
 */

/** 追问轮次（user = 用户提问，assistant = AI 回答） */
@Serializable
data class ProjectTurn(val role: String, val content: String)

/** 报告引用的网络来源 */
@Serializable
data class StoredSource(val title: String, val url: String, val snippet: String = "")

/** Agent 发现卡（scratchpad 日志的持久化形状） */
@Serializable
data class StoredFinding(val title: String, val detail: String, val evidence: String = "")

/** Agent 建议图表（suggest_chart 的持久化形状） */
@Serializable
data class StoredChart(
    val type: String,
    val title: String,
    val labels: List<String>,
    val values: List<Double>,
    val description: String = "",
)

/** 语义审查发现的问题（quote 与报告原文逐字一致，程序靠它定位打码） */
@Serializable
data class StoredSemantic(val kind: String, val quote: String, val why: String)

/** 一份历史报告：只留能区分版本与还原正文的最小信息 */
@Serializable
data class SavedReport(
    /** 生成它时的风格与类型——同一份数据换风格试出差异，靠这两个字段标注 */
    val styleId: String,
    val typeId: String,
    /** 正文字数（列表里显示用，不必为它再算一遍） */
    val chars: Int,
    val savedAt: Long,
    val text: String,
)

@Serializable
data class AnalysisProject(
    val id: String,
    val datasetId: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** 首轮 AI 报告（生成过才有） */
    val insight: String? = null,
    val sources: List<StoredSource> = emptyList(),
    val semantic: List<StoredSemantic> = emptyList(),
    /** 首轮报告之后的问答轮次 */
    val turns: List<ProjectTurn> = emptyList(),
    /** 章节结构是否由模型自主规划（刷新后要能照实显示） */
    val autonomous: Boolean = false,
    /** **生成 insight 时用的偏好**：收旧版进 savedReports 时标注用，不能用当前偏好标 */
    /** 生成引擎（classic = 经典流水线；其余为插件 id）——报告卡与版本条显示用 */
    val engineId: String = "classic",
    /** Agent 引擎的分析计划（Plan-and-Execute 计划层；经典路径为空） */
    val agentPlan: List<String> = emptyList(),
    /** Agent 引擎的发现卡（scratchpad 日志：title/detail/evidence） */
    val agentFindings: List<StoredFinding> = emptyList(),
    /** Agent 引擎建议的图表（suggest_chart 的持久化形状） */
    val agentCharts: List<StoredChart> = emptyList(),
    val insightStyleId: String? = null,
    val insightTypeId: String? = null,
    val webFailed: Boolean = false,
)

/**
 * 历史版本的存储键前缀：按项目 id 单独存（projects 整个数组一个键——
 * 一个项目攒到 1.5MB 历史会连累别的项目写不进去）。
 */
class ProjectStore(private val prefs: PrefsStore, private val blobs: DatasetStore) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 项目列表：按最近活动（updatedAt）降序——刚追问过的项目排最前。
     * **不带 savedReports**（列表页只用到 id、名字与时间；历史版本按
     * loadReports(id) 单独读）。
     */
    fun listProjects(): List<AnalysisProject> =
        decodeJson<List<AnalysisProject>>(STORAGE_KEY)
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()

    fun loadProject(id: String): AnalysisProject? =
        listProjects().find { it.id == id }

    /** 按数据集找项目（一个数据集默认对应一个项目；也允许建多个） */
    fun findProjectByDataset(datasetId: String): AnalysisProject? =
        listProjects().find { it.datasetId == datasetId }

    /** 新建项目。返回 null 表示没写进本机 */
    fun createProject(datasetId: String, name: String): AnalysisProject? {
        val now = System.currentTimeMillis()
        val project = AnalysisProject(id = newId(), datasetId = datasetId, name = name, createdAt = now, updatedAt = now)
        val ok = runCatching {
            prefs.set(STORAGE_KEY, json.encodeToString(listOf(project) + listProjects()))
        }.isSuccess
        return if (ok) project else null
    }

    /**
     * 保存项目。截断规则：**永远保留 turns[0]（首轮报告），尾部按偶数条截取**，
     * 保证问答成对、不产生孤儿答案——直接 slice(-6) 会把报告挤出窗口。
     * 历史版本：undefined 语义 = 没带这个字段就不动它（savedReportsInternal 为 null）；
     * 显式空列表才表示"清空"。
     */
    fun saveProject(project: AnalysisProject, savedReports: List<SavedReport>? = null): Boolean {
        val projects = listProjects()
        val index = projects.indexOfFirst { it.id == project.id }
        val trimmed = project.copy(turns = trimTurns(project.turns))
        val writeBody = fun(): Boolean = runCatching {
            prefs.set(
                STORAGE_KEY,
                json.encodeToString(
                    if (index == -1) listOf(trimmed) + projects
                    else projects.mapIndexed { i, item -> if (i == index) trimmed else item },
                ),
            )
        }.isSuccess
        val stored = writeBody()
        if (!stored) return false

        if (savedReports == null) return true
        if (savedReports.isEmpty()) {
            blobs.remove(REPORTS_PREFIX + project.id)
            return true
        }
        // 写不下就收敛再试：历史版本可丢，但没有理由为它打扰用户
        var candidate = trimSavedReports(savedReports) ?: savedReports
        while (candidate.isNotEmpty()) {
            if (runCatching { blobs.put(REPORTS_PREFIX + project.id, json.encodeToString(candidate)) }.isSuccess) return true
            candidate = candidate.take(candidate.size / 2)
        }
        blobs.remove(REPORTS_PREFIX + project.id)
        return true
    }

    /** 读某个项目的历史版本 */
    fun loadReports(id: String): List<SavedReport>? =
        blobs.get(REPORTS_PREFIX + id)?.let { raw ->
            runCatching { json.decodeFromString<List<SavedReport>>(raw) }.getOrNull()
        }

    fun renameProject(id: String, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val project = loadProject(id) ?: return false
        return saveProject(project.copy(name = trimmed, updatedAt = System.currentTimeMillis()))
    }

    /** 数据集被删时，连同它的项目一起清掉 */
    fun deleteProjectsByDataset(datasetId: String) {
        val removed = listProjects().filter { it.datasetId == datasetId }
        prefs.set(STORAGE_KEY, json.encodeToString(listProjects().filter { it.datasetId != datasetId }))
        for (project in removed) blobs.remove(REPORTS_PREFIX + project.id)
    }

    fun deleteProject(id: String) {
        prefs.set(STORAGE_KEY, json.encodeToString(listProjects().filter { it.id != id }))
        blobs.remove(REPORTS_PREFIX + id)
    }

    /** 清空全部项目（设置页「清除本机数据」用） */
    fun clearAll() {
        for (project in listProjects()) blobs.remove(REPORTS_PREFIX + project.id)
        prefs.remove(STORAGE_KEY)
    }

    private inline fun <reified T> decodeJson(key: String): T? =
        prefs.get(key, "").takeIf { it.isNotEmpty() }?.let { raw ->
            runCatching { json.decodeFromString<T>(raw) }.getOrNull()
        }

    /**
     * 历史版本的体积收敛：从最新往最旧累加，超过总量上限就丢更旧的。
     * 上限 1.5MB 的来处：本机存储要同时放数据集本身与全部项目。
     */
    private fun trimSavedReports(saved: List<SavedReport>): List<SavedReport> {
        if (saved.isEmpty()) return saved
        var used = 0
        val kept = mutableListOf<SavedReport>()
        // 从最新往最旧：savedReports 约定按 savedAt 降序（新的在前）
        for (item in saved.sortedByDescending { it.savedAt }) {
            val size = item.text.length + 200 // 200 ≈ 其余字段的固定开销
            if (used + size > SAVED_REPORTS_BUDGET && kept.isNotEmpty()) break
            used += size
            kept.add(item)
        }
        return kept
    }

    /**
     * 报告（1 条）+ 最近若干组完整问答；总条数不超过 MAX_PERSISTED_TURNS。
     * 直接 slice(-6) 会把报告挤出窗口——正确做法：**保留 turns[0]，
     * 尾部按偶数条截取**，并修掉以 assistant 开头的孤儿答案与末尾悬空的问题。
     */
    private fun trimTurns(turns: List<ProjectTurn>): List<ProjectTurn> {
        if (turns.size <= MAX_PERSISTED_TURNS) return turns
        val head = turns.first()
        val rest = turns.drop(1)
        val budget = MAX_PERSISTED_TURNS - 1
        val evenBudget = budget - (budget % 2)
        var tail = if (rest.size > evenBudget) rest.takeLast(evenBudget) else rest
        // 截取窗口可能从某条回答中间切开：以 user 开头才算干净
        val firstQuestion = tail.indexOfFirst { it.role == "user" }
        if (firstQuestion > 0) tail = tail.drop(firstQuestion)
        // 末尾悬空的问题（发出去了、回答还没回来就刷新）不落盘
        if (tail.isNotEmpty() && tail.last().role == "user") tail = tail.dropLast(1)
        return listOf(head) + tail
    }

    companion object {
        const val STORAGE_KEY = "projects"
        const val REPORTS_PREFIX = "project.reports."

        /** 落盘的对话轮数上限：再往前的只留在本次会话里 */
        const val MAX_PERSISTED_TURNS = 6

        /** 历史报告版本占用的本机存储总量上限（字节） */
        const val SAVED_REPORTS_BUDGET = 1_500_000
    }
}
