package com.stragent.agent

import com.stragent.core.i18n.Messages
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * T-079（D21 U-2/W-3）· 错误 → 动作模板表的单测。
 *
 * 负控纪律：未知消息/null/空一律通用兜底（兜底不误吞已知族）；族间区分（不同族映射到
 * 不同动作）；i18n 键缺失时回退键名（MessageBundle 既有语义，保证 nextAction 永非空）。
 */
class ErrorActionsTest {

    /**
     * 期望值与**生产同源**取文案（跟随宿主 locale）——i18n 表中英齐全，
     * 生产侧 `Messages.get(key)` 本就按系统 locale 选表；断言若绑死中文，
     * 在非中文宿主上必红（Linux 容器实测 17 例假失败，T-139）。
     * 鉴别力不减：族判错 → key 错 → 文案不同 → 仍红（42 个 key 文案互不相同）。
     */
    private fun zh(key: String): String = Messages.get(key)

    @Test
    fun `数据集族——句柄无效与未导入数据都指向导入与盘点工具`() {
        for (message in listOf(
            "数据集不存在：ds_x（请先导入或核对句柄）",
            "尚无数据集：请先调用 stragent_import_table",
            "datasetId 不能为空（先用 stragent_import_table 导入）",
        )) {
            assertEquals(zh("error.action.dataset"), ErrorActions.nextAction(message), message)
        }
    }

    @Test
    fun `列名族——列不存在与角色不符都指向列清单核对`() {
        for (message in listOf(
            "找不到列。「sales」：数据集中的列是 a、b",
            "列不存在: n（该数据集的列: a、b）",
            "列 \"n\" 被识别为文本：category 请选 dimension",
        )) {
            assertEquals(zh("error.action.column"), ErrorActions.nextAction(message), message)
        }
    }

    @Test
    fun `参数缺失族——点名缺失项的动作是补全后重试`() {
        for (message in listOf("connectionId 不能为空", "code 不能为空")) {
            assertEquals(zh("error.action.param_missing"), ErrorActions.nextAction(message), message)
        }
    }

    @Test
    fun `配额、文件、格式、配置族——各命中真实消息样本`() {
        // 配额族（上限/过大）——顺序敏感：配额正则在文件/格式之前，别让「过大」漏进格式族的「非法」
        assertEquals(zh("error.action.quota"), ErrorActions.nextAction("用户指标已达上限 64"))
        assertEquals(zh("error.action.quota"), ErrorActions.nextAction("图片过大（上限 4MB）"))
        // 文件族
        assertEquals(zh("error.action.file"), ErrorActions.nextAction("文件不存在：a/b.csv"))
        assertEquals(zh("error.action.file"), ErrorActions.nextAction("图片不存在或不是普通文件：x.png"))
        // 格式族
        assertEquals(zh("error.action.format"), ErrorActions.nextAction("无法解析的地址：http://x"))
        assertEquals(zh("error.action.format"), ErrorActions.nextAction("这份 xlsx 里没有工作表"))
        // 配置族
        assertEquals(zh("error.action.config"), ErrorActions.nextAction("还没有配置 Step API Key"))
    }

    @Test
    fun `族间区分——不同族映射到不同动作，不互相吞并`() {
        val dataset = ErrorActions.nextAction("datasetId 不能为空")
        val column = ErrorActions.nextAction("列不存在: n")
        val missing = ErrorActions.nextAction("connectionId 不能为空")
        assertNotEquals(dataset, column)
        assertNotEquals(dataset, missing)
        assertNotEquals(column, missing)
    }

    @Test
    fun `未知消息与空值——一律通用兜底且非空`() {
        val fallback = zh("error.action.fallback")
        assertEquals(fallback, ErrorActions.nextAction("完全未知的一段错误文本"))
        assertEquals(fallback, ErrorActions.nextAction(null))
        assertEquals(fallback, ErrorActions.nextAction(""))
        assertEquals(fallback, ErrorActions.nextAction("   "))
        assertTrue(fallback.isNotBlank())
    }
}
