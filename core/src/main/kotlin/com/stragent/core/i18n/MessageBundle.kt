package com.stragent.core.i18n

import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle

/**
 * D15 T13: 国际化框架
 *
 * MessageBundle 从 classpath 加载 i18n/messages_{locale}.properties。
 * **默认输出 locale 钉死为 zh-CN**（T-198）：协议与文案输出是服务端行为，
 * 不随操作系统 locale 漂移——GitHub runner（en locale）上曾把 zh 文案退成英文、
 * 两处协议快照/字节闸断言当场炸出（公开 CI 首验实录）。可用 env `STRAGENT_LANG`
 * 显式覆盖（如 en）；缺失 key 时回退到 key 本身。
 */
interface MessageBundle {
    fun get(key: String, locale: Locale? = null): String

    companion object {
        fun create(): MessageBundle = PropertiesMessageBundle(Messages.defaultLocale())

        fun forLocale(locale: Locale): MessageBundle = PropertiesMessageBundle(locale)
    }
}

private const val BUNDLE_BASE = "i18n.messages"

private class PropertiesMessageBundle(private val defaultLocale: Locale) : MessageBundle {
    private val cache = mutableMapOf<String, ResourceBundle?>()

    private fun bundle(locale: Locale): ResourceBundle? = cache.getOrPut(locale.language) {
        try {
            ResourceBundle.getBundle(BUNDLE_BASE, locale)
        } catch (_: MissingResourceException) {
            null
        }
    }

    override fun get(key: String, locale: Locale?): String {
        val effective = locale ?: defaultLocale
        return try {
            bundle(effective)?.getString(key) ?: key
        } catch (_: MissingResourceException) {
            key
        }
    }
}

/** 便捷入口：Messages.get("error.rate_limit.exceeded") */
object Messages {
    private val bundle = MessageBundle.create()

    /** 输出 locale：env `STRAGENT_LANG` 优先，缺省钉死 zh-CN（服务端输出确定性，T-198） */
    fun defaultLocale(): Locale {
        val lang = com.stragent.core.config.Config.get("STRAGENT_LANG") ?: ""
        return if (lang.isBlank()) Locale.forLanguageTag("zh-CN") else Locale.forLanguageTag(lang)
    }

    fun get(key: String, locale: Locale? = null): String = bundle.get(key, locale)
}
