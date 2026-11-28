package com.stragent.host

import com.stragent.data.AbilityDatasets
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** T-132 host 触发测试：导入成功后预热回调被调用且携带文本数据集（daemon 线程，latch 等待） */
class ImportWarmupTriggerTest {

    @Test
    fun `导入触发预热回调——携带文本数据集且不影响导入回执`() {
        val datasets = AbilityDatasets()
        val latch = CountDownLatch(1)
        val warmed = AtomicReference<com.stragent.core.dataset.Dataset>()
        val tool = importTextTool(datasets, null, warmup = { ds ->
            warmed.set(ds)
            latch.countDown()
        })
        val f = File.createTempFile("str-agent-warmup-", ".md")
        f.writeText("本工程由华东建设集团承建。\n\n第二段 filler 内容。")
        try {
            val args = buildJsonObject {
                put("filePath", f.absolutePath)
                put("name", "预热材料")
            }
            val r = tool.invoke(args)
            val dsId = r.getValue("datasetId").jsonPrimitive.content
            assertTrue(latch.await(5, TimeUnit.SECONDS), "预热回调在 5s 内被触发")
            val ds = warmed.get()
            assertNotNull(ds)
            assertEquals("预热材料", ds.name, "预热收到的是导入的数据集本体（名称匹配）")
            assertNotNull(ds.text, "文本数据集才触发预热")
        } finally {
            f.delete()
        }
    }
}
