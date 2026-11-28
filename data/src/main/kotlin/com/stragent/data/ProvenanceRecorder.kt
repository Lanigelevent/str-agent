package com.stragent.data

import com.stragent.core.trace.ProvenanceManifest
import com.stragent.core.trace.ProvenanceStep
import java.util.concurrent.ConcurrentHashMap

/**
 * 分析链留痕器 —— 把「用了哪些工具、传了什么参数、结果是什么」记成**溯源清单**。
 *
 * 它回答的三个问题（D10 §阶段2 的纪律内核）：
 *  ①这份结论是用**哪份数据**得出的（datasetHash）；
 *  ②中间**调了哪些工具、传了什么参数**（steps）；
 *  ③**重放一遍**结果是否一致（resultHash 比对，见 host 侧 `stragent_provenance`）。
 *
 * 记录点在 MCP 桥的 `tools/call` —— 一处覆盖全部工具（数据契约 / 策展分析 / 插件能力），
 * 不需要在每个工具里各写一遍。
 */
class ProvenanceRecorder(private val datasets: AbilityDatasets) {
    private val steps = ConcurrentHashMap<String, MutableList<ProvenanceStep>>()

    fun record(datasetId: String, step: ProvenanceStep) {
        if (datasetId.isBlank()) return
        // 数据集哈希在**调用时**捕获（不是清单生成时）—— 否则事后换数据也检测不出来；
        // T-205：数据时点同点捕获（importedAt，best-effort 标注）
        val ds = datasets.byId(datasetId)
        val hash = ds?.let { ProvenanceManifest.hashOfDataset(it) } ?: step.datasetHash
        val list = steps.computeIfAbsent(datasetId) { mutableListOf() }
        // 同一数据集可被多个会话并发调用（T-030），MutableList 本身非线程安全：
        // 写入互斥、读出快照，与 ConcurrentHashMap 的桶级语义对齐
        synchronized(list) { list.add(step.copy(datasetHash = hash, datasetAsOf = ds?.importedAt ?: step.datasetAsOf)) }
    }

    fun stepsOf(datasetId: String): List<ProvenanceStep> {
        val list = steps[datasetId] ?: return emptyList()
        return synchronized(list) { list.toList() }
    }

    /**
     * 生成清单；数据集已不存在（进程重启等）或还没有任何调用 → 返回 null。
     */
    fun manifest(datasetId: String, engine: String = "str-agent"): ProvenanceManifest? {
        val list = stepsOf(datasetId)
        if (list.isEmpty()) return null
        return ProvenanceManifest(
            datasetId = datasetId,
            // 取**第一步**捕获的哈希 —— 那才是分析当时的数据
            datasetHash = list.first().datasetHash,
            steps = list,
            engine = engine,
        )
    }
}
