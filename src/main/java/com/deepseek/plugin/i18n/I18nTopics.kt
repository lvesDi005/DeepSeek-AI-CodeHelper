package com.deepseek.plugin.i18n

import com.intellij.util.messages.Topic

/**
 * 界面语言切换监听器。
 * 通过 MessageBus 广播，各 UI 面板订阅后即时刷新文案，无需重新打开设置。
 */
interface LanguageChangeListener {
    fun languageChanged()
}

/**
 * 内容字体大小切换监听器。
 * 聊天面板订阅后即时刷新已渲染消息的字体大小。
 */
interface ContentFontChangeListener {
    fun fontChanged()
}

/**
 * 插件主题切换监听器。
 * 各插件面板订阅后即时刷新自身配色（不改变 IDE 全局主题）。
 */
interface ThemeChangeListener {
    fun themeChanged()
}

/**
 * 全局界面刷新事件总线（IntelliJ MessageBus）。
 */
object I18nTopics {
    /** 语言切换事件 */
    val LANGUAGE_CHANGED: Topic<LanguageChangeListener> =
        Topic("DeepSeekAI.LanguageChanged", LanguageChangeListener::class.java)

    /** 内容字体大小切换事件 */
    val CONTENT_FONT_CHANGED: Topic<ContentFontChangeListener> =
        Topic("DeepSeekAI.ContentFontChanged", ContentFontChangeListener::class.java)

    /** 插件独立主题切换事件 */
    val THEME_CHANGED: Topic<ThemeChangeListener> =
        Topic("DeepSeekAI.ThemeChanged", ThemeChangeListener::class.java)
}
