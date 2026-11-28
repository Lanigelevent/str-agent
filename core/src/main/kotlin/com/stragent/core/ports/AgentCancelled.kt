package com.stragent.core.ports

/**
 * 协作式取消的契约异常（T-148 自 AgentLoop.kt 下移）。
 *
 * 工具与循环体在取消信号在场时抛出；传输层（data/McpTaskDispatch）据此把任务
 * 记为如实中断——抛出方与捕获方分属不同模块，异常类型必须住在**双方都能见的契约层**
 * （core/ports），而不是任何一侧的私有类型。取消是协作式的：抛出方决定何时抛，
 * 捕获方决定如何呈现；谁都不假装取消成功。
 */
class AgentCancelled : Exception("agent 已取消")
