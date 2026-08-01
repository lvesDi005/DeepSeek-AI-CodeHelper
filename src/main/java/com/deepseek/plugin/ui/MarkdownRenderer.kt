package com.deepseek.plugin.ui

import com.deepseek.plugin.settings.DeepSeekSettings
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import javax.swing.JEditorPane
import javax.swing.event.HyperlinkEvent
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.StyleSheet

/**
 * Converts Markdown text to a styled [JEditorPane] for rendering in chat bubbles.
 *
 * Uses IntelliJ's bundled `org.intellij.markdown` library (from lib-client.jar)
 * to parse CommonMark and generate HTML, then renders through Swing's HTML 3.2
 * engine with IDE-aware colours (light/dark theme).
 *
 * Code fences are NOT handled here — the caller should extract ``` blocks
 * via [CodeBlockCard.parseResponse] and pass only text segments.
 */
object MarkdownRenderer {

    /**
     * Regex 匹配候选的英文/代码术语。
     * 规则：由 [a-zA-Z_] 开头，包含字母/数字/点/下划线/尖括号/方括号/# 的 2-60 字符序列，
     * 之后由 [isTechnicalTerm] 过滤，只保留真正的代码术语格式。
     */
    private val REGEX_INLINE_CODE = Regex(
        "(?<=[\\u4e00-\\u9fff\\u3000-\\u303f\\uff00-\\uffef\\s，。、；：！？）()" +
        "\\-–—/:;,.?!\"'\\[{(]|^)" +
        "([a-zA-Z_][\\w.<>()\\[\\]#{}:]{1,60})" +
        "(?=[\\u4e00-\\u9fff\\u3000-\\u303f\\uff00-\\uffef\\s，。、；：！？）()" +
        "\\-–—/:;,.?!\"'\\]})]|$)"
    )

    /**
     * Create a [JEditorPane] that renders [markdownText] with IDE theme colours.
     *
     * @param markdownText  Markdown source (paragraphs, inline formatting, lists, headings)
     * @param fontSize      Font size in points (default 13)
     * @param fgColor       Text colour — defaults to [JBColor.foreground]
     * @param bgColor       Background colour — defaults to transparent/panel colour
     * @param linkCallback  Optional handler for link clicks (e.g. open in browser)
     */
    fun createPane(
        markdownText: String,
        fontSize: Int = DeepSeekSettings.instance.contentFontSize,
        fgColor: java.awt.Color = JBColor.foreground(),
        bgColor: java.awt.Color? = null,
        linkCallback: ((String) -> Unit)? = null
    ): JEditorPane {
        // 预处理：将代码术语自动包裹 backtick，使其在正文中更突出
        val highlighted = highlightInlineCode(markdownText)
        val html = toHtml(highlighted)
        val pane = JEditorPane("text/html", html).apply {
            isEditable = false
            isOpaque = bgColor != null
            // 显式设置前景色：确保 refreshFont 等读取 pane.foreground 时用插件主题色，
            // 避免 fallback 到 JBColor.foreground()（跟随 IDE，插件深色+IDE浅色时正文变深）
            foreground = fgColor
            background = bgColor ?: java.awt.Color(0, 0, 0, 0) // transparent
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            font = font.deriveFont(fontSize.toFloat())
            border = JBUI.Borders.empty()
            margin = JBUI.insets(0)
            // Apply custom styles
            (editorKit as? HTMLEditorKit)?.styleSheet = createStyleSheet(fontSize, fgColor)
        }

        // Handle link clicks
        if (linkCallback != null) {
            pane.addHyperlinkListener { e ->
                if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                    linkCallback(e.url?.toString() ?: e.description)
                }
            }
        }

        return pane
    }

    /**
     * 更新已渲染 [JEditorPane] 的字体大小。
     *
     * 仅设置 pane.font 是不够的 — HTML 内容由 [StyleSheet] 的 CSS 规则驱动
     * （`body { font-size: XXpt }`），CSS 优先级高于 JEditorPane 的 font 属性。
     * 因此需用新字号重建 StyleSheet，已生成内容才能即时变化。
     */
    fun refreshFont(pane: JEditorPane, fontSize: Int) {
        pane.font = pane.font.deriveFont(fontSize.toFloat())
        val kit = pane.editorKit as? HTMLEditorKit
        if (kit != null) {
            kit.styleSheet = createStyleSheet(fontSize, pane.foreground ?: PluginTheme.textPrimary())
            pane.revalidate()
            pane.repaint()
        }
    }

    /**
     * Convert Markdown text to an HTML string suitable for embedding in a
     * JEditorPane. Produces a full <html><body>...</body></html> document.
     */
    fun toHtml(markdownText: String): String {
        val flavour = CommonMarkFlavourDescriptor()
        @Suppress("DEPRECATION")
        val parsedTree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdownText)
        val generator = HtmlGenerator(markdownText, parsedTree, flavour, false)
        return generator.generateHtml()
    }

    /**
     * 将文本中的代码术语用 backtick 包裹，
     * 使它们在 Markdown 渲染时获得内联代码样式（等宽字体 + 底色），从而突出显示。
     *
     * 通过 [isTechnicalTerm] 按词形判断，只识别真正的代码术语（如 camelCase、
     * PascalCase、ALL_CAPS、含特殊字符的标识符），不处理普通英文单词。
     *
     * 例如：
     *  - 「使用 MessageBubble 显示」→「使用 `MessageBubble` 显示」
     *  - 「call getUser() to fetch」→「call `getUser()` to fetch」
     *  - 「the API returns JSON」→「the `API` returns `JSON`」
     *
     * 不会重复包裹已被 `` ` `` 包裹的内容。
     */
    fun highlightInlineCode(text: String): String {
        val sb = StringBuilder()
        var lastEnd = 0

        for (match in REGEX_INLINE_CODE.findAll(text)) {
            val start = match.range.first

            // 按词形过滤：只保留真正的代码术语（非普通英文单词）
            if (!isTechnicalTerm(match.value)) continue

            // 跳过已被 backtick 包裹的（前面有 ` 且后面有对应的 `）
            val alreadyWrapped = start > 0 && text[start - 1] == '`'

            if (!alreadyWrapped) {
                sb.append(text, lastEnd, start)
                sb.append('`')
                sb.append(match.value)
                sb.append('`')
                lastEnd = match.range.last + 1
            }
        }

        sb.append(text.substring(lastEnd))
        return sb.toString()
    }

    // ── Private helpers ──

    /**
     * 判断一个词是否为代码术语（而非普通英文单词）。
     *
     * 代码术语通常具有以下特征之一：
     *  - 包含非字母字符（点、下划线、尖括号、括号、数字等）：`List<String>`、`user_name`、`@Override`
     *  - 全大写且长度 ≥ 2：`API`、`JSON`、`MAX`（常量/缩写）
     *  - 非首字母有大写字母（camelCase / PascalCase）：`getUser`、`MessageBubble`、`JavaScript`
     */
    private fun isTechnicalTerm(word: String): Boolean {
        if (word.length < 2) return false

        // 包含非字母字符 → 代码标识符（下划线、点、括号、数字等）
        if (word.any { !it.isLetter() }) return true

        val hasLower = word.any { it.isLowerCase() }
        val hasUpper = word.any { it.isUpperCase() }

        // 全大写 → 常量/缩写（API, JSON, MAX）
        if (hasUpper && !hasLower) return true

        // 非首字母位置出现大写 → camelCase / PascalCase（getUser, MessageBubble, JavaScript）
        if (hasLower && word.substring(1).any { it.isUpperCase() }) return true

        return false
    }

    private fun createStyleSheet(fontSize: Int, fgColor: java.awt.Color): StyleSheet {
        val ss = StyleSheet()
        val hexFg = toHex(fgColor)
        val hexHeading = toHex(PluginTheme.textHeading())
        val hexBorder = toHex(PluginTheme.border())
        val codeBg = toHex(PluginTheme.color(0xE8E8E8, 0x33333A))
        val codeFg = toHex(PluginTheme.color(0x1976D2, 0x7CB7FF))
        val linkColor = toHex(PluginTheme.link())

        ss.addRule("body { font-family: sans-serif; font-size: ${fontSize}pt; color: $hexFg; margin: 0; padding: 0; text-align: left; }")
        ss.addRule("p { margin: 0 0 6px 0; padding: 0; text-align: left; }")
        // 次级标题：跟随主题高对比（#111111/#F2F2F2 加粗），禁止浅灰淡字
        ss.addRule("h1, h2, h3, h4, h5, h6 { margin: 10px 0 4px 0; margin-left: 0; padding-left: 0; font-weight: bold; color: $hexHeading; text-align: left; }")
        ss.addRule("ul, ol { margin: 2px 0 6px 0; padding-left: 22px; color: $hexFg; }")
        // 列表项显式继承正文高对比色：Swing HTML 渲染中 li 不总是继承 body 颜色，需显式指定，防止浅灰小字
        ss.addRule("li { margin: 2px 0; color: $hexFg; }")
        // 防御：即使内容含 small/sub/sup，也强制正文级字号与对比度，禁止缩小淡化
        ss.addRule("small, sub, sup { font-size: inherit; color: $hexFg; }")
        ss.addRule("a { color: $linkColor; text-decoration: underline; }")
        // 内联代码：等宽 + 底色 + 颜色有别于正文，使其从中文中脱颖而出
        ss.addRule("code { font-family: monospace; font-size: ${fontSize - 1}pt; background-color: $codeBg; color: $codeFg; padding: 1px 4px; }")
        ss.addRule("pre { background-color: transparent; padding: 0; margin: 0; }")
        // 引用块：正文标准对比度（不淡化），用左边框区分模块
        ss.addRule("blockquote { margin: 4px 0; padding: 2px 10px; border-left: 3px solid $hexBorder; color: $hexFg; }")
        ss.addRule("hr { border: none; border-top: 1px solid $hexBorder; margin: 10px 0; }")
        ss.addRule("strong { font-weight: bold; }")
        ss.addRule("em { font-style: italic; }")
        return ss
    }

    private fun toHex(color: java.awt.Color): String {
        return String.format("#%02x%02x%02x", color.red, color.green, color.blue)
    }
}
