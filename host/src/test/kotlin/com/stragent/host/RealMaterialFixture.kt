package com.stragent.host

import java.io.File
import org.junit.Assume.assumeTrue

/**
 * T-139 · 真实材料夹具的**统一守卫**。
 *
 * ## 为什么需要它（实测发现）
 *
 * 真实 PDF / docx 材料在**仓库外**（开发机桌面路径），所以在 CI runner、Linux/macOS
 * 容器、还没装材料的同事机器上**都不存在**。而在此之前，用到真实材料的 9 个用例里
 * **只有 2 个**做了「缺失 → 显式跳过」（T-136 修的 `RealDocABTest` / `PdfSumSamplingTest`），
 * 其余 7 个是**硬失败**（实测：把 `AB_REAL_PDF` 指向不存在的路径 → 9 例中 7 例红）。
 *
 * 后果很具体：**任何没有该材料的机器跑全量门禁都会红**——包括本轮实测的 Linux 容器
 * （Ubuntu 22.04）。这不是「代码坏了」，是「环境没配」；硬失败会让门禁对所有没装夹具的
 * 机器变红，**信号失真**（与「假绿」同族问题，反向而已）。
 *
 * ## 口径
 *
 * - 缺失 → **显式跳过**（JUnit assumption → `skipped`，不是 failure）：不假红也不假绿；
 * - 跳过理由里写明**怎么补**（设哪个 env），便于接手的人知道这不是「测过了」；
 * - 默认路径集中在本文件一处：开发机（Windows 桌面）能真跑，其他机器自动跳过。
 */
internal object RealMaterialFixture {

    /** 开发机默认路径：基坑监测总结报告（Word 导出 PDF，268 页） */
    const val DEFAULT_PDF: String = "C:/Users/<user>/Desktop/总结报告/一区二段/基坑安全监测总结报告_字处理版.pdf"

    /** 开发机默认路径：管廊连接围护监测方案（docx） */
    const val DEFAULT_DOCX: String = "C:/Users/<user>/Desktop/管廊连接围护监测方案/管廊连接围护监测方案.docx"

    /**
     * 取真实 PDF 夹具路径；缺失 → 显式跳过。
     *
     * @param envName 覆盖用的环境变量名（优先于默认路径）
     * @param defaultPath 默认路径（null = 只认 env）
     */
    fun requirePdf(envName: String = "AB_REAL_PDF", defaultPath: String? = DEFAULT_PDF): String =
        require(envName, defaultPath, "真实 PDF")

    /** 取真实 docx 夹具路径；缺失 → 显式跳过 */
    fun requireDocx(envName: String = "AB_REAL_DOCX", defaultPath: String? = DEFAULT_DOCX): String =
        require(envName, defaultPath, "真实 docx")

    private fun require(envName: String, defaultPath: String?, label: String): String {
        val path = System.getenv(envName)?.trim()?.takeIf { it.isNotEmpty() } ?: defaultPath
        assumeTrue(
            "缺少$label 夹具（$envName=${path ?: "<未设置>"}）——该材料在**仓库外**（开发机桌面），" +
                "本机之外的机器（CI runner / Linux / macOS）没有属正常；设置 $envName 指向本机材料即可启用。" +
                "缺失不是代码坏了，是环境没配 → 显式跳过（不假红也不假绿）",
            path != null && File(path).isFile,
        )
        return path!!
    }
}
