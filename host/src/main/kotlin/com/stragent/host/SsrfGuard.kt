package com.stragent.host

import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

/**
 * T-149 · 出站 fetch 的 SSRF 防护（全仓唯一出站 URL 校验入口，仿 [com.stragent.core.security.SafeXml] 的单点先例）。
 *
 * ## 背景（D24 审查 P0-1，一手复核）
 *
 * 远程导入此前对调用方提供的任意 URL 无内网防护：`http` 明文可用、`Redirect.NORMAL` 跨源跟随、
 * 无回环/RFC1918/链路本地黑名单——MCP 调用方可借 `stragent_import_file` / `stragent_import_text`
 * 把宿主当跳板读内网服务与 `169.254.169.254` 云元数据端点。
 *
 * ## 防护口径（OWASP SSRF Prevention Cheat Sheet）
 *
 * 1. 解析 host 的**全部**地址逐个检查（解析即校验）；
 * 2. 拒绝：回环（127/8、::1）、RFC1918（10/8、172.16/12、192.168/16）、链路本地（169.254/16、fe80::/10）、
 *    IPv6 唯一本地（fc00::/7）、未指定地址（0.0.0.0）与 IPv6 内嵌 IPv4（::ffff:10.x 等）；
 * 3. 重定向逐跳复检（见 ObjectStorage 的手动重定向循环）。
 *
 * ## 可测性（仿 T-146 httpBind 注入先例）
 *
 * - `resolve` 可注入：测试固定解析结果，不需要真实 DNS；
 * - `allowLoopback` 仅放开回环——测试基础设施（HttpServer stub）起在回环上必须放行，
 *   但 RFC1918 / 链路本地 / ULA / 0.0.0.0 **仍然拒绝**（负控不受豁免影响）。
 *   生产 fetch 路径永远用默认 guard（allowLoopback=false），这不是生产开关。
 */
class SsrfGuard(
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    internal val allowLoopback: Boolean = false,
) {
    /** 允许的出站 scheme（与 ObjectStorage 的远程协议集合一致） */
    val allowedSchemes: Set<String> = setOf("http", "https")

    /**
     * 校验一个出站 URI：scheme 白名单 + host 解析校验。不合法即抛 `IllegalArgumentException`（zh-first）。
     *
     * @param redirectFrom 不为 null 表示这是重定向跳——错误信息写明跳转链，便于排查。
     */
    fun check(uri: URI, redirectFrom: URI? = null) {
        val scheme = uri.scheme?.lowercase()
        if (scheme == null || scheme !in allowedSchemes) {
            throw IllegalArgumentException(
                "远程导入只支持 http/https（收到「${uri.scheme ?: "未知"}」：" + uri + "）", // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            )
        }
        val host = uri.host
        if (host.isNullOrBlank()) {
            throw IllegalArgumentException("URL 缺少主机名：$uri") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        }
        checkHost(host, uri, redirectFrom)
    }

    private fun checkHost(host: String, uri: URI, redirectFrom: URI?) {
        val addresses = try {
            resolve(host)
        } catch (e: UnknownHostException) {
            throw IllegalArgumentException("无法解析主机「$host」：域名不存在或 DNS 不可用（$uri）") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        }
        val forbidden = addresses.filter { addr ->
            // allowLoopback 只豁免回环类地址；RFC1918 / 链路本地 / ULA / 0.0.0.0 仍按禁址处理
            if (allowLoopback && addr.isLoopbackAddress) false else isForbiddenAddress(addr)
        }
        if (forbidden.isNotEmpty()) {
            val from = redirectFrom?.let { "（重定向自 $it）" } ?: "" // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            throw IllegalArgumentException(
                "禁止访问内网/保留地址（$host 解析到 ${forbidden.first().hostAddress}）$from：" + // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                    "远程导入只能指向公网地址；内网材料请走本地路径导入（stragent_import_file 本地路径）。", // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            )
        }
    }

    companion object {
        /**
         * 判定单个地址是否被禁。内部可见供测试直接断言每个地址类的判定。
         *
         * 注意 `isSiteLocalAddress` 覆盖 IPv4 RFC1918（10/8、172.16/12、192.168/16）；
         * IPv6 唯一本地（fc00::/7）与 IPv6 内嵌 IPv4（::ffff:x.x.x.x）JDK 没有对应谓词，单独判。
         */
        internal fun isForbiddenAddress(addr: InetAddress): Boolean {
            if (addr.isLoopbackAddress || addr.isAnyLocalAddress ||
                addr.isLinkLocalAddress || addr.isSiteLocalAddress
            ) {
                return true
            }
            val bytes = addr.address
            // IPv6 唯一本地 fc00::/7：首字节低 7 位为 1111 110x
            if (bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC) return true
            // IPv6 内嵌 IPv4（::ffff:a.b.c.d）：按内嵌 IPv4 重新判定
            if (bytes.size == 16 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
                bytes[2] == 0.toByte() && bytes[3] == 0.toByte() && bytes[4] == 0.toByte() &&
                bytes[5] == 0xFF.toByte()
            ) {
                val v4 = InetAddress.getByAddress(bytes.copyOfRange(12, 16))
                return isForbiddenAddress(v4)
            }
            return false
        }
    }
}
