package com.stragent.core.security

import javax.xml.XMLConstants
import javax.xml.parsers.SAXParser
import javax.xml.parsers.SAXParserFactory

/**
 * 安全的 XML 解析入口（**全仓唯一的 XML 解析点**）。
 *
 * ## 为什么需要它
 *
 * `data` 模块用 SAX 解析 docx / xlsx / odf 包内的 XML。SAX 在 JDK 里的默认行为是
 * **会**解析外部实体——实测（JDK 17）：`load-external-dtd` / `external-general-entities` /
 * `external-parameter-entities` 三项**默认全为 true**。于是一份恶意文档可以：
 *
 *  - 用 `file://` 实体**读本地文件**（凭据、密钥、配置）→ 实测已复现：
 *    `parseDocxBody` 会把哨兵文件的内容原样读进正文；
 *  - 用 `http://` 实体**发起 SSRF**（打内网）；
 *  - 用实体膨胀做 **DoS**。
 *
 * 触发路径从 MCP 入口可达：`stragent_import_file` 接受客户端给的本地文件路径，
 * `.docx` → `parseDocxBody` / `parseDocxTables`，`.xlsx` → `parseXlsx`。
 *
 * ## 防护口径（OWASP《XXE Prevention Cheat Sheet》§1.1–1.6 的纵深防御）
 *
 * ① **禁外部通用实体** —— 挡住本地文件读与 SSRF（§1.2）；
 * ② **禁外部参数实体** —— 挡住经参数实体的间接拉取（§1.3）；
 * ③ **禁外部 DTD 加载** —— 挡住「DTD 在外部」这一路（§1.4）；
 * ④ **禁 XInclude** —— 挡住 `xi:include` 引入外部文档（§1.4）；
 * ⑤ **JAXP 安全处理**（`FEATURE_SECURE_PROCESSING`）—— JDK 层的统一加固；
 * ⑥ **显式实体膨胀上限** —— 内部实体膨胀（billion laughs）的唯一兜底。
 *
 * **实测：没有单点承重**（逐条负控验证，见 D4 P-181）：
 *  - 全部防护关掉 → 回归用例**变红**（3 例失败）——证明用例确实抓得住漏洞；
 *  - 只留 ⑤（三个实体开关全关）→ 仍**挡住**；
 *  - 只留 ①②③（⑤ 关）→ 仍**挡住**；
 *  - ⑥ 放到极大 → 仍**挡住**（⑤ 的隐式上限兜底）。
 * 即 ①②③ 与 ⑤ 是**两条独立且冗余**的防线——这正是「纵深防御」的意义：
 * 任何单条配置被误删/被覆盖，漏洞都不会立刻回来。
 *
 * ## 关于「保留 DOCTYPE」（口径选择，非疏漏）
 *
 * 最严格的一档是 `disallow-doctype-decl=true`（任何 DTD 一律致命错误）。
 * 本仓选择**兼容档**：保留 DOCTYPE 本身，只禁**外部**实体与外部 DTD。
 *
 * 理由与代价：
 *  - **挡得住的是同一件事**：XXE 的危害来自「外部实体指向本地文件 / 内网地址」，
 *    这条路径在兼容档下**完全封死**（实测：外部实体不被解析，哨兵内容零泄漏）；
 *  - **保留的收益**：OOXML/ODF 生态里存在带自定义 DTD 的合法文档，
 *    兼容档不会把它们误伤（真实 docx/xlsx 回归见 D4 P-181）；
 *  - **保留的代价**：内部实体膨胀仍可能——故 ⑥ 的**显式**上限是这一档的必要配套，
 *    它把上限从「JDK 隐式默认值」变成**写在代码里、被测试钉住的常量**；
 *  - **残余风险**：DTD 语法的解析器差异类问题不在本档覆盖范围内——
 *    那是「禁一切 DOCTYPE」那一档的收益，二者不可兼得，本仓选兼容档并如实登记。
 *
 * ## 纪律
 *
 * **禁止在别处裸调 `SAXParserFactory` / `DocumentBuilderFactory`**——
 * `scripts/lint.py` 的「裸 XML 解析」规则（ERROR 级）会当场拦下。
 */
object SafeXml {

    /**
     * 实体展开上限（**内部**实体——billion laughs 那一路）。
     *
     * 显式常量，不依赖 JDK 隐式默认值：把上限变成**写在代码里、被测试钉住**的契约。
     */
    const val ENTITY_EXPANSION_LIMIT = 10_000

    private const val DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl"
    private const val EXTERNAL_GENERAL = "http://xml.org/sax/features/external-general-entities"
    private const val EXTERNAL_PARAMETER = "http://xml.org/sax/features/external-parameter-entities"
    private const val LOAD_EXTERNAL_DTD = "http://apache.org/xml/features/nonvalidating/load-external-dtd"
    private const val ENTITY_EXPANSION_LIMIT_PROPERTY =
        "http://www.oracle.com/xml/jaxp/properties/entityExpansionLimit"

    /**
     * 构造安全 SAX 解析器。
     *
     * 每次解析都新建一个（**不缓存**实例）——`SAXParser` 非线程安全，
     * 共享它会引入新的并发问题。
     */
    fun newSaxParser(): SAXParser {
        val factory = SAXParserFactory.newInstance()
        // **namespace-aware 必须保持关闭**（SAXParserFactory 的默认值就是 false）：
        // 仓内的解析器一律用 `qName` 判元素名（`w:tbl` / `w:tr` / `sheet` …），
        // 不走 namespace 语义；而开启 namespace-aware 会让解析器**额外校验前缀绑定**，
        // 于是「声明不全但实际合法」的 OOXML 文件会报
        // 「前缀 "r" 未绑定」（实测：Excel 工作簿的 `r:id` 属性）。
        // 那不是 XXE 防护需要的，是**画蛇添足带来的行为变更**——本轮实测踩过。
        factory.isNamespaceAware = false
        factory.isXIncludeAware = false
        // 兼容档：DOCTYPE 本身保留（理由见类 KDoc），禁的是**外部**实体与外部 DTD
        factory.setFeature(DISALLOW_DOCTYPE, false)
        factory.setFeature(EXTERNAL_GENERAL, false)
        factory.setFeature(EXTERNAL_PARAMETER, false)
        factory.setFeature(LOAD_EXTERNAL_DTD, false)
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        val parser = factory.newSAXParser()
        parser.setProperty(ENTITY_EXPANSION_LIMIT_PROPERTY, ENTITY_EXPANSION_LIMIT.toString())
        return parser
    }
}
