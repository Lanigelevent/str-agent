package com.stragent.core.report

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T-109 TA 校准数学：中位比 / 因子推荐 / env 解析 */
class TaCalibrationTest {

    @Test
    fun `中位比奇偶与空集与非法样本`() {
        assertEquals(0.8, medianRatio(listOf(TaSample(1000, 800))))
        assertEquals(0.75, medianRatio(listOf(TaSample(1000, 700), TaSample(1000, 800))))
        assertEquals(1.0, medianRatio(emptyList()), "空集=无证据不上调")
        assertEquals(1.0, medianRatio(listOf(TaSample(0, 500))), "非法样本剔除后空=1.0")
        assertEquals(
            0.8,
            medianRatio(listOf(TaSample(1000, 800), TaSample(0, 999), TaSample(500, 400), TaSample(2000, 1600))),
            "四样本中位=(0.8+0.8)/2，非法项剔除",
        )
    }

    @Test
    fun `因子推荐判据与夹取`() {
        assertEquals(1.0, recommendFactor(0.85), "依从足够=关")
        assertEquals(1.0, recommendFactor(0.8), "阈值边界=关")
        assertEquals(1.0, recommendFactor(1.1), "超产也不下调（TA 只治欠产）")
        assertEquals(1.5, recommendFactor(0.5), "深度欠产夹上限")
        assertEquals(1.5, recommendFactor(0.3), "极端欠产夹上限")
        assertTrue(
            kotlin.math.abs(recommendFactor(0.79) - 1.266) < 0.001,
            "0.79 → 1/0.79≈1.266（夹内）",
        )
        assertEquals(1.5, recommendFactor(listOf(TaSample(1000, 400))), "样本直算：0.4→夹上限")
        assertEquals(1.0, recommendFactor(listOf(TaSample(1000, 850))), "样本直算：依从足够")
    }

    @Test
    fun `env 解析三态`() {
        assertNull(parseTaFactor(null), "缺省=关")
        assertNull(parseTaFactor(""), "空=关")
        assertNull(parseTaFactor("off"), "误配按关（防呆）")
        assertEquals(1.25, parseTaFactor("1"), "旧档兼容")
        assertEquals(1.3, parseTaFactor("1.3"), "显式因子")
        assertNull(parseTaFactor("0.8"), "低于 1 无意义=关")
        assertNull(parseTaFactor("2.0"), "超上限视为误配=关")
    }
    @Test
    fun `分档 profile 解析与插值`() {
        val prof = parseTaProfile("300:1.18,500:1.43,800:1.5")
        assertEquals(listOf(300 to 1.18, 500 to 1.43, 800 to 1.5), prof)
        assertNull(parseTaProfile(null))
        assertNull(parseTaProfile("bad"))
        assertNull(parseTaProfile("300:2.0"), "超上限项剔除后空=关")
        assertEquals(1.18, factorFor(200, prof!!), "低于最小档取首点")
        assertEquals(1.5, factorFor(3500, prof), "高于最大档取末点")
        assertEquals(1.18 + (1.43 - 1.18) * 0.5, factorFor(400, prof), 0.0001, "档间中点线性插值")
    }
}
