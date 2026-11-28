package com.stragent.host

import com.stragent.data.abilityTools
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-058 · 新导入源端到端（JSONL / Parquet / 对象存储只读）。
 *
 * 证据分工：
 *  - JSONL：字段并集来自全量扫描、编码探测可解释、抽样行号可回链（`stragent_list_rows.sourceRow`）；
 *  - Parquet：schema 来自文件本身（DuckDB DESCRIBE）、按文件顺序确定性抽样、同文件重复导入同 datasetId；
 *  - 对象存储：https 直连（查询串回包隐去）、S3 匿名与签名两条路径（本地假 S3 端点）、
 *    离线/404/凭据不完整都有可解释错误；
 *  - SigV4 的「签名对不对」由独立的已知答案测试（`SigV4Test`）负责，这里只验证**带上了签名**。
 */
class NewFormatImportTest {

    /**
     * T-149：测试基础设施（StubServer）起在回环上，guard 需要回环豁免才能服务既有用例。
     * 豁免的只是回环——RFC1918 / 链路本地 / ULA / 0.0.0.0 仍然拒绝（负控不受影响）。
     * 用完置回 null，避免泄漏到其它测试类。
     */
    @BeforeTest
    fun allowLoopbackForStub() {
        ObjectStorage.testGuard = SsrfGuard(allowLoopback = true)
    }

    @AfterTest
    fun resetGuard() {
        ObjectStorage.testGuard = null
    }

    private fun tempHome(): File =
        File(System.getProperty("java.io.tmpdir"), "str-agent-newformat-" + System.nanoTime()).also { it.mkdirs() }

    private fun bootstrap(): StrAgentHostBootstrap =
        StrAgentHostBootstrap.create(tempHome(), llm = FakeLlmClient())

    private fun tool(b: StrAgentHostBootstrap, name: String) =
        (abilityTools(b.abilityDatasets) +
            pluginTools(b.plugin, b.abilityDatasets, b.memory, b.semantic) +
            listOf(importFileTool(b.abilityDatasets, b.sessionRegistry)))
            .first { it.name == name }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content ?: error("缺少字段 $key：$this")

    private fun JsonObject.int(key: String): Int = str(key).toInt()

    // ---------- JSONL ----------

    @Test
    fun `JSONL 大文件——确定性抽样、编码与 schema 可解释、明细可回链`() {
        val b = bootstrap()
        try {
            val file = File(b.home, "events.jsonl")
            file.bufferedWriter(Charsets.UTF_8).use { out ->
                for (i in 0 until 25_000) out.write("""{"id":$i,"bucket":"b${i % 3}","score":${i * 0.5}}""" + "\n")
            }
            val imported = tool(b, "stragent_import_file")
                .invoke(buildJsonObject { put("filePath", file.absolutePath) })

            assertEquals("jsonl", imported.str("sourceFormat"))
            assertEquals(25_000, imported.int("rows"), "rows 是源文件口径")
            assertEquals(8_334, imported.int("analyzedRows"), "按步长 3 抽 8334 行")
            assertTrue(imported.str("truncated").toBoolean())

            val encoding = imported["encoding"]!!.jsonObject
            assertEquals("UTF-8", encoding.str("name"))
            assertTrue(encoding.str("reason").contains("合法 UTF-8"), encoding.toString())

            val schema = imported["schema"]!!.jsonObject
            assertEquals("jsonl", schema.str("source"))
            assertTrue(schema.str("inferredBy").contains("全量扫描"), schema.toString())
            val schemaColumns = schema["columns"]!!.jsonArray
            assertEquals(listOf("id", "bucket", "score"), schemaColumns.map { it.jsonObject.str("name") })
            assertEquals(listOf("number", "text", "number"), schemaColumns.map { it.jsonObject.str("type") })

            val sampling = imported["sampling"]!!.jsonObject
            assertEquals(3, sampling.int("step"))
            assertEquals(25_000, sampling.int("totalRows"))

            // 明细回链：样本第 N 行 → 源文件第 (N−1)·3+1 个非空 JSON 行
            val listed = tool(b, "stragent_list_rows").invoke(
                buildJsonObject {
                    put("datasetId", imported.str("datasetId"))
                    put("offset", 0)
                    put("limit", 2)
                },
            )
            val items = listed["items"]!!.jsonArray
            assertEquals(1, items[0].jsonObject.int("sourceRow"))
            assertEquals("0", items[0].jsonObject["cells"]!!.jsonArray[0].jsonPrimitive.content)
            assertEquals(4, items[1].jsonObject.int("sourceRow"))
            assertEquals("3", items[1].jsonObject["cells"]!!.jsonArray[0].jsonPrimitive.content)
            assertEquals(
                sampling.str("fingerprint"),
                listed["sampling"]!!.jsonObject.str("fingerprint"),
                "明细与导入必须是同一份抽样",
            )

            // 幂等：同一文件重复导入返回同一 datasetId
            val again = tool(b, "stragent_import_file")
                .invoke(buildJsonObject { put("filePath", file.absolutePath) })
            assertEquals(imported.str("datasetId"), again.str("datasetId"))
        } finally {
            b.close()
        }
    }

    @Test
    fun `JSONL 坏行——报出第几个非空行，不静默跳过`() {
        val b = bootstrap()
        try {
            val file = File(b.home, "broken.jsonl")
            file.writeText("""{"ok":1}""" + "\n" + """{"ok":2}""" + "\n" + """{broken}""" + "\n")
            val error = assertFailsWith<IllegalArgumentException> {
                tool(b, "stragent_import_file").invoke(buildJsonObject { put("filePath", file.absolutePath) })
            }
            assertTrue(error.message!!.contains("第 3 个非空行"), error.message)
        } finally {
            b.close()
        }
    }

    @Test
    fun `JSONL GBK 编码——回包说明为何按 GB18030 解码`() {
        val b = bootstrap()
        try {
            val file = File(b.home, "gbk.jsonl")
            file.writeBytes(
                ("""{"姓名":"张三","城市":"上海"}""" + "\n" + """{"姓名":"李四","城市":"北京"}""" + "\n")
                    .toByteArray(charset("GB18030")),
            )
            val imported = tool(b, "stragent_import_file")
                .invoke(buildJsonObject { put("filePath", file.absolutePath) })
            assertEquals("GB18030", imported["encoding"]!!.jsonObject.str("name"))
            assertTrue(imported["encoding"]!!.jsonObject.str("reason").contains("不是合法 UTF-8"))
            assertEquals(listOf("姓名", "城市"), imported["columnList"]!!.jsonArray.map { it.jsonObject.str("name") })
        } finally {
            b.close()
        }
    }

    // ---------- Parquet ----------

    private fun writeParquet(file: File, rows: Int) {
        DriverManager.getConnection("jdbc:duckdb:").use { conn ->
            conn.createStatement().use { st ->
                val path = file.absolutePath.replace('\\', '/').replace("'", "''")
                st.execute(
                    "COPY (SELECT i::BIGINT AS id, 'n' || i AS name, (i * 1.5)::DOUBLE AS score, " +
                        "(i % 2 = 0) AS flag FROM range(0, $rows) t(i)) " +
                        "TO '$path' (FORMAT PARQUET)",
                )
            }
        }
    }

    @Test
    fun `Parquet——schema 来自文件本身、确定性抽样、重复导入同 datasetId`() {
        val b = bootstrap()
        try {
            val file = File(b.home, "metrics.parquet")
            writeParquet(file, rows = 25_000)
            val imported = tool(b, "stragent_import_file")
                .invoke(buildJsonObject { put("filePath", file.absolutePath) })

            assertEquals("parquet", imported.str("sourceFormat"))
            assertEquals(25_000, imported.int("rows"))
            assertEquals(8_334, imported.int("analyzedRows"))
            assertEquals("不适用：Parquet 是自描述二进制格式（schema 随文件走，无文本编码）", imported.str("encoding"))

            val schema = imported["schema"]!!.jsonObject
            assertEquals("parquet", schema.str("source"))
            assertTrue(schema.str("inferredBy").contains("DuckDB DESCRIBE"), schema.toString())
            val columns = schema["columns"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("id", "name", "score", "flag"), columns.map { it.str("name") })
            assertEquals(listOf("number", "text", "number", "text"), columns.map { it.str("type") })
            assertTrue(columns[0].str("parquetType").contains("BIGINT"), columns[0].toString())
            assertTrue(columns[1].str("parquetType").contains("VARCHAR"), columns[1].toString())

            val sampling = imported["sampling"]!!.jsonObject
            assertEquals(3, sampling.int("step"))
            assertEquals(25_000, sampling.int("totalRows"))
            val fingerprint = sampling.str("fingerprint")
            assertTrue(fingerprint.startsWith("sha256:"))

            // 明细回链：第 1 / 2 个样本行 → 源文件第 1 / 4 行，id 分别为 0 / 3
            val listed = tool(b, "stragent_list_rows").invoke(
                buildJsonObject {
                    put("datasetId", imported.str("datasetId"))
                    put("offset", 0)
                    put("limit", 2)
                },
            )
            val items = listed["items"]!!.jsonArray
            assertEquals(1, items[0].jsonObject.int("sourceRow"))
            assertEquals("0", items[0].jsonObject["cells"]!!.jsonArray[0].jsonPrimitive.content)
            assertEquals(4, items[1].jsonObject.int("sourceRow"))
            assertEquals("3", items[1].jsonObject["cells"]!!.jsonArray[0].jsonPrimitive.content)

            // 确定性：同一文件重复导入 → 同一 datasetId / 同一指纹 / 同一样本首行
            val again = tool(b, "stragent_import_file")
                .invoke(buildJsonObject { put("filePath", file.absolutePath) })
            assertEquals(imported.str("datasetId"), again.str("datasetId"))
            assertEquals(fingerprint, again["sampling"]!!.jsonObject.str("fingerprint"))
            val againListed = tool(b, "stragent_list_rows").invoke(
                buildJsonObject {
                    put("datasetId", again.str("datasetId"))
                    put("offset", 0)
                    put("limit", 2)
                },
            )
            assertEquals(
                items.map { it.jsonObject["cells"] },
                againListed["items"]!!.jsonArray.map { it.jsonObject["cells"] },
            )
        } finally {
            b.close()
        }
    }

    @Test
    fun `Parquet 假文件——错误可解释，不抛裸 SQL 异常`() {
        val b = bootstrap()
        try {
            val fake = File(b.home, "fake.parquet")
            fake.writeText("this is not parquet")
            val error = assertFailsWith<IllegalArgumentException> {
                tool(b, "stragent_import_file").invoke(buildJsonObject { put("filePath", fake.absolutePath) })
            }
            assertTrue(error.message!!.contains("Parquet 读取失败"), error.message)
            assertTrue(error.message!!.contains("可能原因"), error.message)
        } finally {
            b.close()
        }
    }

    // ---------- 对象存储 ----------

    private class StubServer(val rows: String) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val paths = ConcurrentLinkedQueue<String>()
        val queries = ConcurrentLinkedQueue<String>()
        val authorizations = ConcurrentLinkedQueue<String>()

        init {
            server.createContext("/") { exchange ->
                paths.add(exchange.requestURI.rawPath)
                queries.add(exchange.requestURI.rawQuery ?: "")
                exchange.requestHeaders.getFirst("Authorization")?.let { authorizations.add(it) }
                val body = rows.toByteArray(Charsets.UTF_8)
                when (exchange.requestURI.rawPath) {
                    "/data.csv", "/bucket/path/data.csv", "/data" -> {
                        exchange.sendResponseHeaders(200, body.size.toLong())
                        exchange.responseBody.use { it.write(body) }
                    }
                    "/truncated.csv" -> {
                        // 声明 1000 字节只写 16 字节：模拟传输中断的响应
                        exchange.sendResponseHeaders(200, 1000)
                        exchange.responseBody.use { it.write(body.copyOf(16)) }
                    }
                    else -> {
                        val message = "not found".toByteArray()
                        exchange.sendResponseHeaders(404, message.size.toLong())
                        exchange.responseBody.use { it.write(message) }
                    }
                }
            }
            server.start()
        }

        val base: String get() = "http://127.0.0.1:${server.address.port}"

        fun close() = server.stop(0)
    }

    @Test
    fun `https 直连——查询串进回包前被隐去，404 有明确解释`() {
        val stub = StubServer("地区,销售额\n华东,1000\n华北,300\n")
        val b = bootstrap()
        try {
            val imported = tool(b, "stragent_import_file").invoke(
                buildJsonObject { put("filePath", "${stub.base}/data.csv?token=secret-token") },
            )
            assertEquals("csv", imported.str("sourceFormat"))
            assertEquals(2, imported.int("rows"))
            val source = imported["source"]!!.jsonObject
            assertEquals("http", source.str("store"))
            assertEquals("${stub.base}/data.csv", source.str("uri"), "查询串可能含签名，回包必须隐去")
            assertFalse(source.str("uri").contains("secret-token"))
            assertTrue(source.str("notice").contains("临时文件"), source.toString())
            assertEquals("/data.csv", stub.paths.first())
            assertEquals("token=secret-token", stub.queries.first())

            val missing = assertFailsWith<IllegalArgumentException> {
                tool(b, "stragent_import_file").invoke(buildJsonObject { put("filePath", "${stub.base}/nope.csv") })
            }
            assertTrue(missing.message!!.contains("404"), missing.message)
        } finally {
            b.close()
            stub.close()
        }
    }

    @Test
    fun `S3 匿名 GET——不携带 Authorization 头`() {
        val stub = StubServer("id,v\n1,10\n2,20\n")
        val tempDir = Files.createTempDirectory("stragent-s3-anon-").toFile()
        try {
            val fetched = ObjectStorage.fetch(
                "s3://bucket/path/data.csv",
                tempDir,
                ObjectStorage.Settings(s3Endpoint = stub.base),
            )
            assertEquals("s3", fetched.store)
            assertEquals("s3://bucket/path/data.csv", fetched.sourceUri)
            assertEquals("data", fetched.suggestedName)
            assertEquals("csv", fetched.extension)
            assertTrue(fetched.file.isFile)
            assertEquals("/bucket/path/data.csv", stub.paths.first())
            assertTrue(stub.authorizations.isEmpty(), "无凭据时必须是匿名 GET：${stub.authorizations}")
        } finally {
            tempDir.deleteRecursively()
            stub.close()
        }
    }

    @Test
    fun `S3 凭据导入——请求带 SigV4 头，密钥不出现在头里`() {
        val stub = StubServer("id,v\n1,10\n2,20\n")
        val b = bootstrap()
        try {
            System.setProperty("str-agent.s3.endpoint", stub.base)
            System.setProperty("str-agent.s3.accessKey", "TESTACCESSKEY")
            System.setProperty("str-agent.s3.secretKey", "TESTSECRETKEY")
            System.setProperty("str-agent.s3.region", "us-east-1")

            val imported = tool(b, "stragent_import_file").invoke(
                buildJsonObject { put("filePath", "s3://bucket/path/data.csv") },
            )
            assertEquals("csv", imported.str("sourceFormat"))
            assertEquals(2, imported.int("rows"))
            assertEquals("s3", imported["source"]!!.jsonObject.str("store"))
            assertEquals("s3://bucket/path/data.csv", imported["source"]!!.jsonObject.str("uri"))

            val authorization = stub.authorizations.firstOrNull()
                ?: error("S3 请求必须带 Authorization 头")
            assertTrue(
                authorization.startsWith("AWS4-HMAC-SHA256 Credential=TESTACCESSKEY/"),
                authorization,
            )
            assertTrue(authorization.contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date"), authorization)
            assertFalse(authorization.contains("TESTSECRETKEY"), "密钥绝不进 Authorization 头：$authorization")
        } finally {
            System.clearProperty("str-agent.s3.endpoint")
            System.clearProperty("str-agent.s3.accessKey")
            System.clearProperty("str-agent.s3.secretKey")
            System.clearProperty("str-agent.s3.region")
            b.close()
            stub.close()
        }
    }

    @Test
    fun `S3 凭据不完整与离线——错误可解释`() {
        val tempDir = Files.createTempDirectory("stragent-s3-err-").toFile()
        try {
            val partial = assertFailsWith<IllegalArgumentException> {
                ObjectStorage.fetch(
                    "s3://bucket/data.csv",
                    tempDir,
                    ObjectStorage.Settings(accessKey = "only-access"),
                )
            }
            assertTrue(partial.message!!.contains("凭据不完整"), partial.message)

            val offline = assertFailsWith<IllegalArgumentException> {
                ObjectStorage.fetch("http://127.0.0.1:1/data.csv", tempDir)
            }
            assertTrue(offline.message!!.contains("连接/超时"), offline.message)

            val ftp = assertFailsWith<IllegalArgumentException> {
                ObjectStorage.fetch("ftp://example.com/data.csv", tempDir)
            }
            assertTrue(ftp.message!!.contains("不支持的远程协议"), ftp.message)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `下载中断——半截文件被删除，错误可解释`() {
        val stub = StubServer("id,v\n1,10\n")
        val tempDir = Files.createTempDirectory("stragent-truncated-").toFile()
        try {
            val error = assertFailsWith<IllegalArgumentException> {
                ObjectStorage.fetch("${stub.base}/truncated.csv", tempDir)
            }
            assertTrue(error.message!!.contains("下载中断"), error.message)
            assertEquals(
                emptyList(),
                tempDir.listFiles()!!.toList(),
                "半截文件不能留给调用方：$tempDir",
            )
        } finally {
            tempDir.deleteRecursively()
            stub.close()
        }
    }

    @Test
    fun `远程地址没有扩展名——明确要求带上扩展名`() {
        val stub = StubServer("id,v\n1,10\n")
        val b = bootstrap()
        try {
            val error = assertFailsWith<IllegalArgumentException> {
                tool(b, "stragent_import_file").invoke(buildJsonObject { put("filePath", "${stub.base}/data") })
            }
            assertTrue(error.message!!.contains("没有扩展名"), error.message)
        } finally {
            b.close()
            stub.close()
        }
    }

    @Test
    fun `负控——默认 guard 拒绝回环导入（T-149）`() {
        ObjectStorage.testGuard = null
        val tempDir = Files.createTempDirectory("stragent-ssrf-").toFile()
        try {
            val e = assertFailsWith<IllegalArgumentException> {
                ObjectStorage.fetch("http://127.0.0.1:1/data.csv", tempDir)
            }
            assertTrue(e.message!!.contains("禁止访问内网"), "错误信息要写明原因：${e.message}")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `负控——重定向到内网被逐跳复检拦截（T-149）`() {
        // 首跳在回环（豁免内放行），302 跳到 RFC1918 地址 → 第二跳 guard 拒绝
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/redirect") { ex ->
            ex.responseHeaders.add("Location", "http://10.255.255.1/data.csv")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.start()
        val tempDir = Files.createTempDirectory("stragent-ssrf-redir-").toFile()
        try {
            val e = assertFailsWith<IllegalArgumentException> {
                ObjectStorage.fetch("http://127.0.0.1:${server.address.port}/redirect", tempDir)
            }
            assertTrue(
                e.message!!.contains("禁止访问内网") && e.message!!.contains("重定向自"),
                "错误信息要写明跳转链：${e.message}",
            )
        } finally {
            server.stop(0)
            tempDir.deleteRecursively()
        }
    }
}
