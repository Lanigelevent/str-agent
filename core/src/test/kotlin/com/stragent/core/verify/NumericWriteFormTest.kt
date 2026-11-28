package com.stragent.core.verify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-185 · 数字门禁的**写体旁路**回归（对抗探针抓出的四类隐身口子）。
 *
 * 探针实测（修前）：
 *  - `１２３` 全角数字 → 0 个 token（编造数可完全绕过门禁）；
 *  - `1，000` 全角逗号 → 拆成 `1` 与 `000`，白名单对不上；
 *  - `1.2万亿` / `3千亿` → 截成 12000 / 3000（值差 10^8 倍）；
 *  - `－1000` / `−1000` → 符号丢失，claim 被当成正数。
 *
 * 本测试钉住：①这些写体都被抽成**正确自然值**；②用这些写体**造假仍被抓**（门禁牙齿不松）。
 */
class NumericWriteFormTest {

    @Test
    fun `三类写体都抽出正确自然值`() {
        fun natural(s: String) = extractNaturalNumbers(s).map { it.value }
        assertEquals(listOf(1.2e12), natural("1.2万亿"), "万亿复合量级")
        assertEquals(listOf(3.0e11), natural("3千亿"), "千亿复合量级")
        assertEquals(listOf(123.0), natural("１２３"), "全角数字")
        assertEquals(listOf(-1000.0), natural("－1000"), "全角减号")
        assertEquals(listOf(-1000.0), natural("−1000"), "Unicode 减号")
    }

    @Test
    fun `写体变换不改变白名单判定`() {
        val brief = "规模：2000 行\n合计 241000\n" +
            "分组：East 合计 120500，West 合计 72300，North 合计 48200\n"
        // 白名单里的数换成全角/全角逗号写法 → 仍判可信（修前会被误判成编造）
        val ok = verifyReport("East 合计 １２０５００，West 合计 ７２３００。", brief)
        assertTrue(ok.unsourced.isEmpty(), "全角写法应对上同一份白名单：$ok")

        // 编造数换成全角/复合单位写法 → 仍被抓（修前可绕过）
        val bad = verifyReport("East 合计 ９９９９９９，规模 １.２万亿。", brief)
        assertTrue(bad.unsourced.isNotEmpty(), "写体变换不得让编造数隐身：$bad")
    }

    @Test
    fun `T-184 的万单位修复没被复合量级改动破坏`() {
        assertEquals(8888.0 * 1e4, extractNaturalNumbers("8888 万").single().value)
        assertEquals(8888.0 * 1e4, extractNaturalNumbers("８８８８万").single().value)
    }
}
