package com.stragent.core.security

import com.stragent.core.ports.PrefsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-195 · PII Shield 核心单测——识别器正负控（校验和挡误伤）/ 往返保真 / 确定性 / opt-in 闸。
 */
class PiiShieldTest {

    private class MapPrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    @Test
    fun `识别器——四类 PII 各自命中并按序枚举`() {
        val m = PiiShield.Mapping()
        val masked = PiiShield.maskText(
            "客户张三 13812345678，邮箱 zhangsan@example.com，证件 11010519491231002X，卡号 4111111111111111。",
            m,
        )
        assertTrue(masked.contains("[[P1]]") && masked.contains("[[P4]]"), masked)
        assertTrue(!masked.contains("13812345678"), masked)
        assertTrue(!masked.contains("zhangsan@example.com"), masked)
        assertTrue(!masked.contains("11010519491231002X"), masked)
        assertTrue(!masked.contains("4111111111111111"), masked)
        assertEquals(4, m.entries().size)
    }

    @Test
    fun `往返——还原后逐字保真`() {
        val m = PiiShield.Mapping()
        val raw = "联系人 13998887766，邮件 a.b@test.org。"
        val masked = PiiShield.maskText(raw, m)
        assertTrue(masked != raw)
        assertEquals(raw, PiiShield.restore(masked, m))
    }

    @Test
    fun `确定性——同原文恒同占位符（跨消息共享映射）`() {
        val m = PiiShield.Mapping()
        val a = PiiShield.maskText("手机 13812345678", m)
        val b = PiiShield.maskText("再来一次 13812345678", m)
        assertTrue(a.contains("[[P1]]") && b.contains("[[P1]]"), "$a | $b")
        assertEquals(1, m.entries().size)
    }

    @Test
    fun `校验和负控——非法身份证与非 Luhn 长卡号不误伤`() {
        val m = PiiShield.Mapping()
        val masked = PiiShield.maskText("证件 110105194912310021，卡 1234567890123456。", m)
        assertTrue(!masked.contains("[[P"), "校验不过的数字串不应被脱敏：$masked")
        assertTrue(masked.contains("110105194912310021"))
        assertEquals(0, m.entries().size)
    }

    @Test
    fun `业务数字负控——订单号与金额不被误伤`() {
        val m = PiiShield.Mapping()
        val masked = PiiShield.maskText("订单 ORD-2025-000123，金额 1,234,567.89 元，编号 12345678901。", m)
        assertTrue(!masked.contains("[[P"), "业务数字不应误伤：$masked")
        assertEquals(0, m.entries().size)
    }

    @Test
    fun `重叠——email 内的数字子串不再当手机号`() {
        val m = PiiShield.Mapping()
        val masked = PiiShield.maskText("邮箱 x13812345678@test.org", m)
        assertEquals(1, m.entries().size, "只应有 email 一个占位符：${m.entries()}")
        assertTrue(masked.contains("[[P1]]@") || masked.contains("[[P1]]"), masked)
    }

    @Test
    fun `闸——默认关，prefs 开，env 路径由集成测试覆盖`() {
        val p = MapPrefs()
        assertTrue(!PiiShield.enabled(p))
        p.set("privacy.pii.shield", "true")
        assertTrue(PiiShield.enabled(p))
    }

    @Test
    fun `残留检出——LEFTOVER 能抓还原失败工件`() {
        assertTrue(PiiShield.LEFTOVER.containsMatchIn("结论见 [[P1]]。"))
        assertTrue(!PiiShield.LEFTOVER.containsMatchIn("没有占位符"))
    }

    @Test
    fun `T-203 密钥前缀——sk-ghp-AKIA-xoxb 假 token 命中`() {
        val m = PiiShield.Mapping()
        val masked = PiiShield.maskText(
            "密钥1 sk-a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6，" +
                "GitHub ghp_abcdefghijklmnopqrstuvwxyz0123456789abcd，" +
                "AWS AKIAABCDEFGHIJKLMNOP，Slack xoxb-123456-abcdef-ghijklmnop，" +
                "JWT eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ0ZXN0In0.x",
            m,
        )
        assertEquals(5, m.entries().size, "5 类前缀密钥各一占位符：${m.entries()}")
        assertTrue(!masked.contains("sk-a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6"), masked)
        assertTrue(!masked.contains("AKIAABCDEFGHIJKLMNOP"), masked)
    }

    @Test
    fun `T-203 熵回退——裸 hex 与 base64 密钥命中`() {
        val m = PiiShield.Mapping()
        val hexKey = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val b64Key = "dGhpcyBpcyBhIHNlY3JldCBrZXkgMTIzNDU2Nzg5"
        val masked = PiiShield.maskText("校验和 $hexKey 与 $b64Key", m)
        assertTrue(!masked.contains(hexKey), masked)
        assertTrue(!masked.contains(b64Key), masked)
        assertEquals(2, m.entries().size)
    }

    @Test
    fun `T-203 熵回退负控——文件名域名UUID业务串不误伤`() {
        val m = PiiShield.Mapping()
        val masked = PiiShield.maskText(
            "文件 stragent-report-2026-final.pdf 由 id 550e8400-e29b-41d4-a716-446655440000 标识，" +
                "域名 api.example.com，订单 ORD-2025-000123，金额 1,234,567.89 元，" +
                "配置项 user_profile_config_settings 为空。",
            m,
        )
        assertEquals(0, m.entries().size, "分隔符切开的低熵串不得命中熵层：$masked")
    }

    @Test
    fun `T-203 熵标定——纯数字长串熵低于阈值不命中`() {
        val m = PiiShield.Mapping()
        val masked = PiiShield.maskText("流水号 12345678901234567890123456789012 不脱敏", m)
        assertTrue(!masked.contains("[[P"), "纯数字串不应命中熵层：$masked")
        val hex = "e3b0c44298fc1c149afbf4c8996fb924"
        assertTrue(PiiShield.entropy(hex) >= 3.4, "hex 参考串熵应 ≥3.4：${PiiShield.entropy(hex)}")
    }
}
