package com.deepseek.plugin.ui

import com.deepseek.plugin.i18n.I18nTopics
import com.deepseek.plugin.settings.DeepSeekSettings
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.Color

/**
 * 插件独立主题模式。
 */
enum class PluginThemeMode {
    /** 跟随 IDE 全局主题（默认） */
    FOLLOW,
    /** 固定深色（只影响插件 UI，不动 IDE 全局主题） */
    DARK,
    /** 固定浅色（只影响插件 UI，不动 IDE 全局主题） */
    LIGHT
}

/**
 * 插件独立主题色板。
 *
 * 插件的 UI 颜色统一通过 [color] 获取，由 [mode] 决定取浅色/深色：
 * - FOLLOW：委托给 [JBColor]（跟随 IDE 全局主题）；且**监听 IDE 主题变更**，
 *   IDE 切换主题时广播 [I18nTopics.THEME_CHANGED]，插件 UI 即时跟随刷新
 * - DARK/LIGHT：固定使用插件自己的配色，**不随 IDE 变化，也不影响 IDE 全局主题**
 *
 * 主题体系相对独立：既能跟随 IDE，也能独立设置。
 */
object PluginTheme {

    @Volatile
    private var listenerInitialized = false

    /** 当前主题模式（从持久化设置读取） */
    var mode: PluginThemeMode
        get() = when (DeepSeekSettings.instance.pluginTheme) {
            "dark" -> PluginThemeMode.DARK
            "light" -> PluginThemeMode.LIGHT
            else -> PluginThemeMode.FOLLOW
        }
        set(value) {
            DeepSeekSettings.instance.pluginTheme = when (value) {
                PluginThemeMode.DARK -> "dark"
                PluginThemeMode.LIGHT -> "light"
                PluginThemeMode.FOLLOW -> "follow"
            }
        }

    /** 是否当前处于深色配色。 */
    fun isDark(): Boolean = when (mode) {
        PluginThemeMode.DARK -> true
        PluginThemeMode.LIGHT -> false
        PluginThemeMode.FOLLOW -> com.intellij.ide.ui.LafManager.getInstance().currentUIThemeLookAndFeel.isDark
    }

    /**
     * 按当前主题模式返回颜色：浅色主题用 [light]，深色主题用 [dark]。
     * FOLLOW 模式下等价于 [JBColor]（浅色/深色随 IDE 切换）。
     */
    fun color(light: Color, dark: Color): Color = when (mode) {
        PluginThemeMode.DARK -> dark
        PluginThemeMode.LIGHT -> light
        PluginThemeMode.FOLLOW -> JBColor(light, dark)
    }

    /** int RGB 版本。 */
    fun color(lightRgb: Int, darkRgb: Int): Color = color(Color(lightRgb), Color(darkRgb))

    // ════════════════════════════════════════════════════════════════
    //  语义色板（遵循 UI 强制渲染规范：浅色/暗黑双主题）
    //  浅色：背景#FFFFFF 标题#111111 正文#262626 边框#D0D0D0
    //  深色：背景#2B2B2B 标题#F2F2F2 正文#E3E3E3 边框#555555
    // ════════════════════════════════════════════════════════════════

    /** 消息背景：浅色 #FFFFFF / 深色 #2B2B2B */
    fun background(): Color = color(0xFFFFFF, 0x2B2B2B)

    /** 边框：浅色 #D0D0D0 / 深色 #555555 */
    fun border(): Color = color(0xD0D0D0, 0x555555)

    /** 正文：浅色 #111111 / 深色 #F2F2F2（与标题同级，主次靠加粗区分，正文绝对清晰） */
    fun textPrimary(): Color = color(0x111111, 0xF2F2F2)

    /** 次级文字（正文标准对比度，不淡化）：同正文色 */
    fun textSecondary(): Color = color(0x111111, 0xF2F2F2)

    /** 标题（配合加粗使用）：浅色 #111111 / 深色 #F2F2F2 */
    fun textHeading(): Color = color(0x111111, 0xF2F2F2)

    /** 弱化文字（时间戳/版本号等非关键信息，仍保证可读）：浅色 #333333 / 深色 #CCCCCC */
    fun textMuted(): Color = color(0x333333, 0xCCCCCC)

    /** 链接色 */
    fun link(): Color = color(0x1A73E8, 0x64B5F6)

    /** 切换插件主题并广播刷新事件。 */
    fun setTheme(mode: PluginThemeMode) {
        if (this.mode == mode) return
        this.mode = mode
        broadcastThemeChanged()
    }

    /**
     * 监听 IDE 全局主题变更：FOLLOW 模式下跟随刷新插件 UI。
     * 幂等，应用启动时调用一次。
     */
    fun ensureIdeThemeListener() {
        if (listenerInitialized) return
        synchronized(this) {
            if (listenerInitialized) return
            listenerInitialized = true
        }
        try {
            ApplicationManager.getApplication().messageBus.connect().subscribe(
                LafManagerListener.TOPIC,
                LafManagerListener {
                    // FOLLOW 模式跟随 IDE；DARK/LIGHT 固定配色不响应
                    if (mode == PluginThemeMode.FOLLOW) {
                        broadcastThemeChanged()
                    }
                }
            )
        } catch (_: Exception) {
        }
    }

    private fun broadcastThemeChanged() {
        try {
            ApplicationManager.getApplication().messageBus
                .syncPublisher(I18nTopics.THEME_CHANGED)
                .themeChanged()
        } catch (_: Exception) {
        }
    }
}
