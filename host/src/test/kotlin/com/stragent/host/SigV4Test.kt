package com.stragent.host

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SigV4 签名的**已知答案**验证（T-058）—— 不是「自己签自己验」。
 *
 * 用例取自 AWS 官方文档 GET Object 示例（20130524）：给定的规范请求与密钥，
 * 期望签名 `f0e8…bdb41`。签名实现若改坏（漏头、排序错、HMAC 链错），这里会先红。
 *
 * 测试向量是 AWS 文档发布的**公开示例值**（非任何真实凭据），按夹具惯例放测试
 * 资源 `sigv4-known-answer.properties`（资源头注明出处），源码不出现凭据形状字面量。
 */
class SigV4Test {

    /** 从测试 classpath 读官方文档公开的示例密钥（缺失直接失败，不用默认值兜底） */
    private fun knownAnswerVectors(): Pair<String, String> {
        val props = Properties()
        javaClass.classLoader.getResourceAsStream("sigv4-known-answer.properties")!!.use {
            props.load(it)
        }
        return props.getProperty("accessKey") to props.getProperty("secretKey")
    }

    @Test
    fun `AWS 官方 GET Object 测试向量`() {
        val (accessKey, secretKey) = knownAnswerVectors()
        assertTrue(accessKey.isNotEmpty() && secretKey.isNotEmpty(), "测试资源里必须有完整的向量")
        val payload = ObjectStorage.SigV4.canonicalPayloadHashForTest()
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            payload,
            "空请求体的 SHA-256 应与 AWS 文档一致",
        )
        val headers = linkedMapOf(
            "host" to "examplebucket.s3.amazonaws.com",
            "range" to "bytes=0-9",
            "x-amz-content-sha256" to payload,
            "x-amz-date" to "20130524T000000Z",
        )
        val authorization = ObjectStorage.SigV4.authorization(
            method = "GET",
            canonicalUri = "/test.txt",
            canonicalQuery = "",
            headers = headers,
            payloadHash = payload,
            accessKey = accessKey,
            secretKey = secretKey,
            region = "us-east-1",
            service = "s3",
            amzDate = "20130524T000000Z",
        )
        assertTrue(
            authorization.startsWith(
                "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request",
            ),
            authorization,
        )
        assertTrue(
            authorization.contains("SignedHeaders=host;range;x-amz-content-sha256;x-amz-date"),
            "规范头必须按名字排序并全部签入：$authorization",
        )
        assertTrue(
            authorization.endsWith("Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"),
            "签名必须命中官方测试向量：$authorization",
        )
    }
}
