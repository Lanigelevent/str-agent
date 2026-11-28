package com.stragent.core.ai

/**
 * 生成被调用方取消 —— **协作式**：只在可停点（章与章之间、单次调用发起前）抛出，
 * 不会中断正在进行的 HTTP 模型调用。
 *
 * 为什么单独一个类型而不是 `InterruptedException`：取消是**正常控制流**，
 * 调用方（MCP 层）需要把它与网络/解析失败区分开，前者回 `-32800`，后者回内部错误。
 */
class GenerationCancelled(message: String = "生成已取消") : RuntimeException(message)
