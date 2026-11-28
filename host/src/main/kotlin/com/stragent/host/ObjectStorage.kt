package com.stragent.host

import com.stragent.core.config.Config
import com.stragent.core.csv.CsvSampler
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 对象存储只读导入（T-058）—— `s3://` / `gs://` / `https://` 三种来源，先落到本机临时文件，
 * 再交给同一条本地导入链路（CSV/JSONL/Parquet 的抽样、指纹、行号回链一套口径）。
 *
 * 凭据与配置（**只看环境变量 / 系统属性，不写日志、不进回包**）：
 *  - S3：`AWS_ACCESS_KEY_ID` + `AWS_SECRET_ACCESS_KEY`（可选 `AWS_SESSION_TOKEN`）；
 *    都没有时走**匿名 GET**（公开对象）；端点 `STRAGENT_S3_ENDPOINT`（MinIO 等）、
 *    区域 `AWS_REGION` / `AWS_DEFAULT_REGION`（缺省 `us-east-1`）。
 *    系统属性镜像：`str-agent.s3.accessKey` / `str-agent.s3.secretKey` / `str-agent.s3.sessionToken`
 *    / `str-agent.s3.endpoint` / `str-agent.s3.region`（测试与内网排障用）。
 *  - GCS：**只支持公开对象（匿名 GET）**；私有桶请用预签名 `https://` URL——
 *    本任务不实现服务账号 OAuth 签名，回包里会说明这一边界。
 *  - `https://`：直接下载；回包里的 `sourceUri` 会**去掉查询串**（预签名参数可能含密钥）。
 *
 * 诚实边界：
 *  - 下载上限 256MB，与本地 CSV/Parquet 上限一致；超限的响应体边读边判，不会先落满磁盘；
 *  - 无网环境：连接失败会给出「下载失败（连接/超时）」的可解释错误，本地格式完全不走网络；
 *  - 只读：不提供上传/删除/列桶；签名只用于 GET。
 */
object ObjectStorage {

    /** 允许的远程协议 */
    private val REMOTE_SCHEMES = setOf("s3", "gs", "https", "http")

    /** 单对象上限（与本地抽样同一把尺） */
    const val MAX_OBJECT_BYTES: Long = CsvSampler.MAX_FILE_BYTES

    private const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    private const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"

    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            // T-149：不自动跟随重定向——由 sendFollowingRedirects 手动逐跳跟随并逐跳过 SSRF 校验
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    /**
     * 生产口径的出站校验（allowLoopback=false：回环/私网/链路本地全拒）。
     * `testGuard` 只被测试覆盖（见 [SsrfGuard] 的可测性说明），生产路径恒用默认。
     */
    private val defaultGuard = SsrfGuard()

    /** 测试注入点（仿 T-146 httpBind 先例）：设值后 fetch 的 http/https 路径用它；用完置回 null。 */
    internal var testGuard: SsrfGuard? = null

    /** 下载结果：临时文件 + 回包需要的来源信息 */
    data class Fetched(
        val file: File,
        val bytes: Long,
        /** 去掉查询串的可展示 URI（预签名参数不进日志/回包） */
        val sourceUri: String,
        /** s3 / gcs / https */
        val store: String,
        /** 建议的数据集名（URL 路径最后一段，去扩展名） */
        val suggestedName: String,
        /** 扩展名（小写，不含点）；识别不出时为空串 */
        val extension: String,
    )

    /**
     * 对象存储配置。默认从环境变量读（见 [fromEnvironment]）；测试与内网排障可显式传入，
     * 这样「匿名」与「带凭据」两条路径都能在无真实环境变量的机器上被确定性地验证。
     */
    data class Settings(
        val s3Endpoint: String? = null,
        val s3Region: String? = null,
        val accessKey: String? = null,
        val secretKey: String? = null,
        val sessionToken: String? = null,
    ) {
        companion object {
            /**
             * 系统属性优先（`-Dstr-agent.s3.*`，便于测试/内网临时覆盖），其次环境变量；
             * 都不设 = 匿名 GET。两者都只在进程内可见，不会写日志、不进回包。
             */
            fun fromEnvironment(): Settings = Settings(
                s3Endpoint = read("str-agent.s3.endpoint", "STRAGENT_S3_ENDPOINT"),
                s3Region = read("str-agent.s3.region", "AWS_REGION")
                    ?: read("str-agent.s3.region", "AWS_DEFAULT_REGION"),
                accessKey = read("str-agent.s3.accessKey", "AWS_ACCESS_KEY_ID"),
                secretKey = read("str-agent.s3.secretKey", "AWS_SECRET_ACCESS_KEY"),
                sessionToken = read("str-agent.s3.sessionToken", "AWS_SESSION_TOKEN"),
            )

            private fun read(property: String, env: String): String? =
                System.getProperty(property)?.takeIf { it.isNotBlank() }
                    ?: Config.get(env)?.takeIf { it.isNotBlank() }
        }
    }

    /** 是否是远程 URI（本地 Windows 路径里的 `C:\` 不会误判成 `c:` 协议）。 */
    fun isRemote(path: String): Boolean {
        val scheme = runCatching { URI(path).scheme?.lowercase() }.getOrNull() ?: return false
        if (scheme.length == 1) return false // Windows 盘符
        // 已知对象存储协议，或显式带 `://` 的地址（ftp:// 等交给 fetch 给出「不支持」的解释）
        return scheme in REMOTE_SCHEMES || path.contains("://")
    }

    /**
     * 把远程对象下载到调用方指定的临时目录。
     *
     * @param uri `s3://bucket/key` / `gs://bucket/key` / `https://…`
     * @param settings 凭据与端点（缺省从环境变量读）
     * @throws IllegalArgumentException 协议不支持、凭据/权限、404、超限等可解释错误
     */
    fun fetch(uri: String, tempDir: File, settings: Settings = Settings.fromEnvironment()): Fetched {
        val scheme = runCatching { URI(uri).scheme?.lowercase() }.getOrNull()
            ?: throw IllegalArgumentException("无法解析的地址：$uri") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        return when (scheme) {
            "s3" -> fetchS3(uri, tempDir, settings)
            "gs" -> fetchGcs(uri, tempDir)
            "https", "http" -> fetchHttp(uri, tempDir)
            else -> throw IllegalArgumentException(
                "不支持的远程协议「$scheme」：只支持 s3:// / gs:// / https://（对象存储只读导入）",
            )
        }
    }

    // ---------- S3 ----------

    private data class S3Target(val bucket: String, val key: String)

    private fun parseS3(uri: String): S3Target {
        val rest = uri.removePrefix("s3://").removePrefix("S3://")
        val slash = rest.indexOf('/')
        if (slash <= 0 || slash == rest.length - 1) {
            throw IllegalArgumentException("S3 地址不完整：应为 s3://bucket/key（收到：$uri）")
        }
        return S3Target(rest.substring(0, slash), rest.substring(slash + 1))
    }

    private fun fetchS3(uri: String, tempDir: File, settings: Settings): Fetched {
        val target = parseS3(uri)
        val region = settings.s3Region ?: "us-east-1"
        val endpoint = settings.s3Endpoint?.trimEnd('/') ?: "https://s3.$region.amazonaws.com"
        val endpointUri = runCatching { URI(endpoint) }.getOrElse {
            throw IllegalArgumentException("S3 端点配置无法解析（STRAGENT_S3_ENDPOINT）：$endpoint") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        }
        val encodedKey = target.key.split('/').joinToString("/") { encodeSegment(it) }
        val encodedBucket = encodeSegment(target.bucket)
        val url = "$endpoint/$encodedBucket/$encodedKey"
        val host = hostHeader(endpointUri)
        val canonicalPath = endpointUri.rawPath.orEmpty().trimEnd('/') + "/$encodedBucket/$encodedKey"

        val accessKey = settings.accessKey
        val secretKey = settings.secretKey
        val sessionToken = settings.sessionToken

        val headers = linkedMapOf(
            "host" to host,
            "x-amz-content-sha256" to UNSIGNED_PAYLOAD,
        )
        sessionToken?.let { headers["x-amz-security-token"] = it }

        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMinutes(2))
            .GET()
            .header("x-amz-content-sha256", UNSIGNED_PAYLOAD)
        sessionToken?.let { request.header("x-amz-security-token", it) }

        if (accessKey != null && secretKey != null) {
            val amzDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(ZoneOffset.UTC)
                .format(ZonedDateTime.now(ZoneOffset.UTC))
            headers["x-amz-date"] = amzDate
            val authorization = SigV4.authorization(
                method = "GET",
                canonicalUri = canonicalPath,
                canonicalQuery = "",
                headers = headers,
                payloadHash = UNSIGNED_PAYLOAD,
                accessKey = accessKey,
                secretKey = secretKey,
                region = region,
                service = "s3",
                amzDate = amzDate,
            )
            request.header("x-amz-date", amzDate)
            request.header("Authorization", authorization)
        } else if (accessKey != null || secretKey != null) {
            throw IllegalArgumentException(
                "S3 凭据不完整：AWS_ACCESS_KEY_ID 与 AWS_SECRET_ACCESS_KEY 必须同时设置（或都不设置走匿名 GET）", // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            )
        }

        val display = "s3://${target.bucket}/${target.key}"
        return download(
            request = request.build(),
            tempDir = tempDir,
            displayUri = display,
            store = "s3",
            fileName = target.key.substringAfterLast('/'),
            errorPrefix = "S3 下载失败", // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            guard = null,
            initialUri = URI.create(url),
            hint403 = "凭据被拒绝（HTTP 403）：检查 AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY，或对象是否公开可读", // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        )
    }

    // ---------- GCS ----------

    private fun fetchGcs(uri: String, tempDir: File): Fetched {
        val rest = uri.removePrefix("gs://").removePrefix("GS://")
        val slash = rest.indexOf('/')
        if (slash <= 0 || slash == rest.length - 1) {
            throw IllegalArgumentException("GCS 地址不完整：应为 gs://bucket/object（收到：$uri）")
        }
        val bucket = rest.substring(0, slash)
        val key = rest.substring(slash + 1)
        val url = "https://storage.googleapis.com/${encodeSegment(bucket)}/" +
            key.split('/').joinToString("/") { encodeSegment(it) }
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2)).GET()
        return download(
            request = request.build(),
            tempDir = tempDir,
            displayUri = "gs://$bucket/$key",
            store = "gcs",
            fileName = key.substringAfterLast('/'),
            errorPrefix = "GCS 下载失败", // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            guard = null,
            initialUri = URI.create(url),
            hint403 = "私有桶需要 OAuth/服务账号（本工具未实现）；请改用预签名 https:// URL",
        )
    }

    // ---------- HTTPS ----------

    private fun fetchHttp(uri: String, tempDir: File): Fetched {
        val parsed = runCatching { URI(uri) }.getOrElse {
            throw IllegalArgumentException("URL 无法解析：$uri") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        }
        if (parsed.host.isNullOrBlank()) throw IllegalArgumentException("URL 缺少主机名：$uri") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        val guard = testGuard ?: defaultGuard
        guard.check(parsed) // T-149：首跳校验（scheme 白名单 + host 解析拒绝内网/保留地址）
        val request = HttpRequest.newBuilder(parsed).timeout(Duration.ofMinutes(2)).GET().build()
        val display = buildString {
            append(parsed.scheme).append("://").append(parsed.rawAuthority).append(parsed.rawPath.orEmpty())
        }
        return download(
            request = request,
            tempDir = tempDir,
            displayUri = display,
            store = parsed.scheme.lowercase(),
            fileName = parsed.path.orEmpty().substringAfterLast('/'),
            errorPrefix = "下载失败", // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            hint403 = "服务器拒绝访问（HTTP 403）：链接可能已过期（预签名）或需要认证", // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            guard = guard,
            initialUri = parsed,
        )
    }

    // ---------- 公共下载 ----------

    /**
     * 发送请求并手动跟随重定向（≤5 跳，T-149）。
     *
     * guard != null（用户提供的 http/https URL）：**每一跳**都过 SSRF 校验——
     * 首跳合法不代表落地合法，重定向到内网地址是 SSRF 的经典路径。
     * guard == null（部署者配置的 s3/gs 端点，可信配置同 OAuth issuer）：只防协议降级
     * （https 起步的跳转不允许降到 http），不做主机校验——内网 MinIO 是合法部署形态。
     */
    private fun sendFollowingRedirects(
        request: HttpRequest,
        initialUri: URI,
        guard: SsrfGuard?,
        errorPrefix: String,
    ): HttpResponse<InputStream> {
        var current = request
        var currentUri = initialUri
        var hops = 0
        while (true) {
            val response = try {
                client.send(current, HttpResponse.BodyHandlers.ofInputStream())
            } catch (e: java.io.IOException) {
                throw IllegalArgumentException("$errorPrefix（连接/超时）：${e.message ?: e.javaClass.simpleName}") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalArgumentException("$errorPrefix：已被中断") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            }
            val status = response.statusCode()
            if (status in REDIRECT_STATUSES && response.headers().firstValue("location").isPresent) {
                response.body().use { it.readAllBytes() } // 排干 3xx 小响应体再跳
                hops++
                if (hops > MAX_REDIRECTS) {
                    throw IllegalArgumentException(
                        "$errorPrefix：重定向超过 ${MAX_REDIRECTS} 跳，已中止（${currentUri.host}）", // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                    )
                }
                val location = response.headers().firstValue("location").orElseThrow()
                val next = currentUri.resolve(location)
                if (guard != null) {
                    guard.check(next, redirectFrom = currentUri)
                } else {
                    val nextScheme = next.scheme?.lowercase()
                    if (nextScheme !in listOf("http", "https")) {
                        throw IllegalArgumentException(
                            "$errorPrefix：重定向跳到了不支持的协议「$nextScheme」（$currentUri）", // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                        )
                    }
                    if (initialUri.scheme?.lowercase() == "https" && nextScheme == "http") {
                        throw IllegalArgumentException(
                            "$errorPrefix：重定向把 https 降级为 http，已中止（$currentUri）", // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                        )
                    }
                }
                current = HttpRequest.newBuilder(next).timeout(Duration.ofMinutes(2)).GET().build()
                currentUri = next
                continue
            }
            return response
        }
    }

    /** 重定向状态码集合与跳数上限（T-149 手动重定向循环） */
    private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
    private const val MAX_REDIRECTS = 5

    private fun download(
        request: HttpRequest,
        tempDir: File,
        displayUri: String,
        store: String,
        fileName: String,
        errorPrefix: String,
        hint403: String,
        guard: SsrfGuard?,
        initialUri: URI,
    ): Fetched {
        val response = sendFollowingRedirects(request, initialUri, guard, errorPrefix)
        response.body().use { body ->
            val status = response.statusCode()
            if (status != 200) {
                when (status) {
                    401, 403 -> throw IllegalArgumentException("$errorPrefix：$hint403（$displayUri）")
                    404 -> throw IllegalArgumentException("$errorPrefix：对象不存在（HTTP 404）$displayUri") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                    else -> throw IllegalArgumentException("$errorPrefix：服务器返回 HTTP $status（$displayUri）") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                }
            }
            val declared = response.headers().firstValueAsLong("content-length").orElse(-1)
            if (declared > MAX_OBJECT_BYTES) {
                throw IllegalArgumentException(
                    "$errorPrefix：对象过大（${declared / 1024 / 1024}MB，上限 ${MAX_OBJECT_BYTES / 1024 / 1024}MB）$displayUri", // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                )
            }
            val safeName = sanitizeFileName(fileName.ifBlank { "object" })
            val target = File(tempDir, safeName)
            var written = 0L
            try {
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = body.read(buffer)
                        if (n < 0) break
                        written += n
                        if (written > MAX_OBJECT_BYTES) {
                            target.delete()
                            throw IllegalArgumentException(
                                "$errorPrefix：对象超过 ${MAX_OBJECT_BYTES / 1024 / 1024}MB 上限，已中止下载（$displayUri）", // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                            )
                        }
                        out.write(buffer, 0, n)
                    }
                }
            } catch (e: java.io.IOException) {
                // 读到一半断流：不完整文件必须删掉，否则调用方可能把半截当全量解析
                target.delete()
                throw IllegalArgumentException(
                    "$errorPrefix：下载中断（${e.message ?: e.javaClass.simpleName}），不完整文件已丢弃（$displayUri）", // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                )
            }
            if (declared >= 0 && written != declared) {
                // 服务端声明的长度没读满又没抛 IO 异常（不同实现的收尾差异）：同样按中断处理
                target.delete()
                throw IllegalArgumentException(
                    "$errorPrefix：下载中断（声明 $declared 字节，实际收到 $written 字节），不完整文件已丢弃（$displayUri）", // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
                )
            }
            if (written == 0L) {
                target.delete()
                throw IllegalArgumentException("$errorPrefix：下载到的是空文件（$displayUri）") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
            }
            val ext = safeName.substringAfterLast('.', "").lowercase()
            val suggested = safeName.removeSuffix(if (ext.isEmpty()) "" else ".$ext")
            return Fetched(
                file = target,
                bytes = written,
                sourceUri = displayUri,
                store = store,
                suggestedName = suggested,
                extension = ext,
            )
        }
    }

    private fun sanitizeFileName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = base.filter { it.isLetterOrDigit() || it in "._-" }
        return cleaned.ifBlank { "object" }
    }

    private fun hostHeader(uri: URI): String {
        val port = uri.port
        val defaultPort = when (uri.scheme?.lowercase()) {
            "http" -> 80
            "https" -> 443
            else -> -1
        }
        return if (port == -1 || port == defaultPort) uri.host else "${uri.host}:$port"
    }

    private fun encodeSegment(segment: String): String = buildString {
        segment.toByteArray(Charsets.UTF_8).forEach { byte ->
            val ch = byte.toInt() and 0xFF
            val c = ch.toChar()
            if (c.isLetterOrDigit() && ch < 128 || c == '-' || c == '_' || c == '.' || c == '~') {
                append(c)
            } else {
                append('%').append("%02X".format(ch))
            }
        }
    }

    /**
     * AWS Signature Version 4（只做 GET，签名头集合由调用方给定）。
     *
     * 独立成对象是为了能用 AWS 官方文档的测试向量做**已知答案**验证（见 `SigV4Test`），
     * 而不是「自己签名自己验」。
     */
    internal object SigV4 {

        fun authorization(
            method: String,
            canonicalUri: String,
            canonicalQuery: String,
            headers: Map<String, String>,
            payloadHash: String,
            accessKey: String,
            secretKey: String,
            region: String,
            service: String,
            amzDate: String,
        ): String {
            val normalized = headers.mapKeys { it.key.lowercase() }
                .mapValues { (_, value) -> value.trim().replace(Regex("\\s+"), " ") }
            val sortedNames = normalized.keys.sorted()
            val canonicalHeaders = buildString {
                sortedNames.forEach { name -> append(name).append(':').append(normalized[name]).append('\n') }
            }
            val signedHeaders = sortedNames.joinToString(";")
            val canonicalRequest = buildString {
                append(method).append('\n')
                append(canonicalUri).append('\n')
                append(canonicalQuery).append('\n')
                append(canonicalHeaders).append('\n')
                append(signedHeaders).append('\n')
                append(payloadHash)
            }
            val dateStamp = amzDate.substring(0, 8)
            val scope = "$dateStamp/$region/$service/aws4_request"
            val stringToSign = buildString {
                append("AWS4-HMAC-SHA256\n")
                append(amzDate).append('\n')
                append(scope).append('\n')
                append(sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8)))
            }
            val kDate = hmac(("AWS4" + secretKey).toByteArray(Charsets.UTF_8), dateStamp)
            val kRegion = hmac(kDate, region)
            val kService = hmac(kRegion, service)
            val kSigning = hmac(kService, "aws4_request")
            val signature = hmac(kSigning, stringToSign).joinToString("") { "%02x".format(it) }
            return "AWS4-HMAC-SHA256 Credential=$accessKey/$scope, " +
                "SignedHeaders=$signedHeaders, Signature=$signature"
        }

        /** 供测试验证「空补丁」路径与已知向量 */
        fun canonicalPayloadHashForTest(bytes: ByteArray = ByteArray(0)): String = sha256Hex(bytes)

        private fun hmac(key: ByteArray, data: String): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data.toByteArray(Charsets.UTF_8))
        }

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** 空请求体的 SHA-256（AWS 文档常量），留作调用方对照 */
        @Suppress("unused")
        val EMPTY_PAYLOAD_SHA256: String = EMPTY_SHA256
    }
}
