package com.stragent.data

import com.stragent.core.dataset.Dataset
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话注册表（T-030）：MCP 会话态按会话分桶。
 *
 * 会话态 = 「当前数据集指针」（溯源清单按数据集落账，内容寻址天然不串）。
 * 数据集本体仍是全局共享存储（[AbilityDatasets]，ds_ id 是内容哈希，跨会话可见
 * 且语义正确）；**指针**才是每会话私有：会话 A 导入/切换不影响会话 B 的当前。
 *
 * 并发纪律：写路径互斥（ConcurrentHashMap.put 对同 key 原子），读路径快照
 * （get 即不可变快照；指针是单个 String，无复合状态，无需更大锁粒度）。
 *
 * 会话来源：HTTP 头 `Mcp-Session-Id`（MCP Streamable HTTP 标准头）；
 * 缺省 = [DEFAULT_SESSION]，回退到全局「最近导入」——桌面 UI 导入 → MCP 默认
 * 会话可见的既有行为不变（stdio 传输也走默认会话，单客户端语义不变）。
 */
class SessionRegistry(private val datasets: AbilityDatasets) {
    /** sessionId → 当前数据集 id */
    private val currents = ConcurrentHashMap<String, String>()

    /** 写路径互斥：同 key 原子替换 */
    fun setCurrent(sessionId: String, datasetId: String) {
        currents[sessionId] = datasetId
    }

    /**
     * 会话当前数据集 id。具名会话严格分桶（不回退全局——别的会话导入的不该漏进来）；
     * 默认会话回退全局「最近导入」（向后兼容）。
     */
    fun currentId(sessionId: String): String? =
        if (sessionId == DEFAULT_SESSION) {
            currents[sessionId] ?: datasets.currentId()
        } else {
            currents[sessionId]
        }

    /** 会话当前数据集（不存在返回 null） */
    fun current(sessionId: String): Dataset? = currentId(sessionId)?.let { datasets.byId(it) }

    companion object {
        const val DEFAULT_SESSION = "default"
    }
}

/**
 * 当前请求的会话上下文（ThreadLocal，与 [com.stragent.core.ports.ProgressBus] 同形制：
 * 传输层（McpBridge/McpStdio）设置，业务层读取，签名零改动穿透 dispatch）。
 */
object McpSessionContext {
    private val holder = ThreadLocal<String>()

    fun current(): String = holder.get() ?: SessionRegistry.DEFAULT_SESSION

    fun <T> with(sessionId: String, block: () -> T): T {
        val prev = holder.get()
        holder.set(sessionId)
        return try {
            block()
        } finally {
            if (prev == null) holder.remove() else holder.set(prev)
        }
    }
}
