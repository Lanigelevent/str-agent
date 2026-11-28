package com.stragent.agent

import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-048 验收：进度处理器要能**跨线程**跟着协程走。
 *
 * 为什么需要这条测试：编辑部把 6 条记者条线丢到 `Dispatchers.IO` 并行跑，
 * 而 `ProgressBus` 的处理器原本只存在调用线程的 ThreadLocal 上。没有协程上下文
 * 元素时，并行段里的 `onStep`/阶段进度会在工作线程上静默空转——UI 广播还在，
 * 但 MCP 客户端收不到 `notifications/progress`，这正是 D17 G-06 的「丢进度」。
 */
class ProgressBusContextTest {

    @Test
    fun `协程上下文元素——切到 IO 线程后进度仍然送达`() {
        val messages = Collections.synchronizedList(mutableListOf<String>())
        val threads = Collections.synchronizedSet(mutableSetOf<String>())
        val caller = Thread.currentThread().name
        ProgressBus.with({ message ->
            messages += message
            threads += Thread.currentThread().name
        }) {
            runBlocking {
                withContext(Dispatchers.Default + ProgressBus.asContextElement()) {
                    assertTrue(ProgressBus.isActive(), "协程内应看得到订阅者")
                    repeat(100) { ProgressBus.emit("p$it") }
                }
            }
        }
        assertEquals(100, messages.size, "100 条进度应全部送达（丢失说明 ThreadLocal 没跟协程走）")
        assertEquals((0 until 100).map { "p$it" }, messages.toList())
        assertFalse(threads.contains(caller), "进度应来自协程工作线程，而不是调用线程")
    }

    @Test
    fun `负控——不挂上下文元素时跨线程进度为空转`() {
        val messages = Collections.synchronizedList(mutableListOf<String>())
        ProgressBus.with({ messages += it }) {
            runBlocking {
                withContext(Dispatchers.Default) {
                    assertFalse(ProgressBus.isActive(), "没有元素时工作线程上不应有订阅者")
                    ProgressBus.emit("lost")
                }
            }
        }
        assertEquals(0, messages.size, "工作线程上的 emit 应为空转（这是挂元素前的真实行为）")
    }

    @Test
    fun `嵌套 with——内层处理器生效，退出后恢复外层`() {
        val outer = mutableListOf<String>()
        val inner = mutableListOf<String>()
        ProgressBus.with({ outer += it }) {
            ProgressBus.emit("a")
            ProgressBus.with({ inner += it }) {
                ProgressBus.emit("b")
            }
            ProgressBus.emit("c")
        }
        assertEquals(listOf("a", "c"), outer)
        assertEquals(listOf("b"), inner)
    }

    @Test
    fun `并行协程各挂各的元素——互不串台`() {
        val a = Collections.synchronizedList(mutableListOf<String>())
        val b = Collections.synchronizedList(mutableListOf<String>())
        runBlocking {
            listOf(
                async(Dispatchers.Default + com.stragent.core.ports.ProgressBus.Element { a += it }) {
                    repeat(50) { ProgressBus.emit("a$it") }
                },
                async(Dispatchers.Default + com.stragent.core.ports.ProgressBus.Element { b += it }) {
                    repeat(50) { ProgressBus.emit("b$it") }
                },
            ).awaitAll()
        }
        assertEquals((0 until 50).map { "a$it" }, a.toList())
        assertEquals((0 until 50).map { "b$it" }, b.toList())
    }
}
