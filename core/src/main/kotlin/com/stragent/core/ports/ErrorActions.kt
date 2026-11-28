package com.stragent.core.ports

import com.stragent.core.i18n.Messages

/**
 * T-079（D21 U-2/W-3）：错误 → 下一步动作的系统化模板表。
 *
 * 背景：工具错误回执长期「说了是什么、没说下一步做什么」（D21 §2.6 U-2，用户痛点三甲）。
 * 本表把「错误族 → 一句中文动作」收敛成单一事实源，供两条出口共用：
 *   1. MCP 层（McpBridge）：工具执行异常 → `isError` 结果回执，`structuredContent.nextAction`；
 *   2. 内循环（AgentLoop）：工具异常 → `{"ok":false,"error","nextAction"}`，帮模型自纠。
 *
 * 判定按**错误消息文本族**（首条命中生效）——工具抛错时消息已含「是什么」，
 * 动作模板只补「下一步」，不重复错误内容。未命中任何族 → 通用兜底，保证
 * 任何 isError 路径都有非空 nextAction（契约测试断言）。
 */
object ErrorActions {

    /**
     * 错误族：消息正则 → i18n 动作键。顺序即优先级（首条命中生效）。
     *
     * 逐行 `lint:allow`（T-072 预算制的既定出路）：族的判定面就是**中文错误消息本体**
     * ——工具抛错文案是 i18n 的 zh 值，此处正则匹配同一文本，不是可外移的硬编码 UI 文案。
     */
    private val families: List<Pair<Regex, String>> = listOf(
        // 数据集句柄族：句柄无效/还没导入数据——动作是指路导入与盘点工具
        Regex("数据集不存在|尚无数据集|datasetId 不能为空") to "error.action.dataset", // lint:allow 族判定面=中文错误消息本体
        // 列名族：列不存在/角色类型不符——动作是对列清单核对
        Regex("找不到列|列不存在|被识别为") to "error.action.column", // lint:allow 族判定面=中文错误消息本体
        // 参数缺失族：必填参数为空——消息已点名缺失项，动作是补全后重试
        Regex("不能为空") to "error.action.param_missing", // lint:allow 族判定面=中文错误消息本体
        // 配额族：条目数/图片大小等上限——动作是清理或缩减
        Regex("已达上限|上限 \\$|过大") to "error.action.quota", // lint:allow 族判定面=中文错误消息本体
        // 文件族：路径无效——动作是核对路径
        Regex("文件不存在|图片不存在|不是普通文件|zip 不存在") to "error.action.file", // lint:allow 族判定面=中文错误消息本体
        // 格式族：文件结构/响应格式不符——动作是核对格式
        Regex("无法解析|没有工作表|没有数据行|缺少 document\\.xml|缺少 content\\.xml") to "error.action.format", // lint:allow 族判定面=中文错误消息本体
        // 配置族：缺服务配置（如 Step API Key）——动作是补配置
        Regex("API Key|未配置") to "error.action.config", // lint:allow 族判定面=中文错误消息本体
    )

    /**
     * 由错误消息推导下一步动作（中文一句话，i18n 双语）。
     *
     * @param message 工具抛出的错误消息；null/空 → 通用兜底
     * @return 非空动作文案（i18n 缺键时回退键名，保证非空）
     */
    fun nextAction(message: String?): String {
        val text = message ?: return Messages.get(FALLBACK_KEY)
        if (text.isBlank()) return Messages.get(FALLBACK_KEY)
        val key = families.firstOrNull { (pattern, _) -> pattern.containsMatchIn(text) }?.second
            ?: return Messages.get(FALLBACK_KEY)
        return Messages.get(key)
    }

    /**
     * 通用兜底键：未命中任何错误族时的动作。
     */
    private const val FALLBACK_KEY = "error.action.fallback"
}
