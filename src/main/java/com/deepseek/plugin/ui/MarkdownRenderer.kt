package com.deepseek.plugin.ui

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
        fontSize: Int = 13,
        fgColor: java.awt.Color = JBColor.foreground(),
        bgColor: java.awt.Color? = null,
        linkCallback: ((String) -> Unit)? = null
    ): JEditorPane {
        val html = toHtml(markdownText)
        val pane = JEditorPane("text/html", html).apply {
            isEditable = false
            isOpaque = bgColor != null
            background = bgColor ?: java.awt.Color(0, 0, 0, 0) // transparent
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            font = font.deriveFont(fontSize.toFloat())
            border = JBUI.Borders.empty()
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
     * Convert Markdown text to an HTML string suitable for embedding in a
     * JEditorPane. Produces a full <html><body>...</body></html> document.
     */
    fun toHtml(markdownText: String): String {
        val flavour = CommonMarkFlavourDescriptor()
        val parsedTree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdownText)
        val generator = HtmlGenerator(markdownText, parsedTree, flavour, false)
        return generator.generateHtml()
    }

    // ── Private helpers ──

    private fun createStyleSheet(fontSize: Int, fgColor: java.awt.Color): StyleSheet {
        val ss = StyleSheet()
        val hexFg = toHex(fgColor)
        val codeBg = toHex(JBColor(0xF0F0F0, 0x333333))
        val codeFg = toHex(JBColor(0x333333, 0xD4D4D4))
        val linkColor = toHex(JBColor(0x1A73E8, 0x64B5F6))

        ss.addRule("body { font-family: Segoe UI, Roboto, sans-serif; font-size: ${fontSize}pt; color: $hexFg; margin: 0; padding: 0; }")
        ss.addRule("p { margin: 0 0 6px 0; padding: 0; }")
        ss.addRule("h1, h2, h3, h4, h5, h6 { margin: 8px 0 4px 0; }")
        ss.addRule("ul, ol { margin: 2px 0 6px 0; padding-left: 20px; }")
        ss.addRule("li { margin: 1px 0; }")
        ss.addRule("a { color: $linkColor; text-decoration: underline; }")
        ss.addRule("code { font-family: JetBrains Mono, Monospaced, monospace; font-size: ${fontSize - 1}pt; background-color: $codeBg; color: $codeFg; padding: 1px 3px; }")
        ss.addRule("pre { background-color: $codeBg; color: $codeFg; padding: 8px; font-family: JetBrains Mono, Monospaced, monospace; font-size: ${fontSize - 1}pt; }")
        ss.addRule("blockquote { margin: 4px 0; padding: 2px 8px; border-left: 3px solid #CCCCCC; color: #666666; }")
        ss.addRule("hr { border: none; border-top: 1px solid #CCCCCC; margin: 8px 0; }")
        ss.addRule("strong { font-weight: bold; }")
        ss.addRule("em { font-style: italic; }")
        return ss
    }

    private fun toHex(color: java.awt.Color): String {
        return String.format("#%02x%02x%02x", color.red, color.green, color.blue)
    }
}
