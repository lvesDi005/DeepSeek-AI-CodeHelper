package com.deepseek.plugin.completion

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform

/**
 * Ghost Text 渲染器。
 *
 * 在编辑器光标位置以灰色半透明文本绘制补全建议。
 * 支持多行补全，第一行作为 inline inlay 紧跟在光标后，
 * 后续行作为 block inlay 显示在光标行下方。
 *
 * 使用 [EditorCustomElementRenderer] 接口控制绘制。
 */
class GhostTextRenderer(
    private val text: String,
    /** 补全文本中已被编辑器自身占用的宽度（像素），用于偏移 inline 元素 */
    private val offsetX: Int = 0
) : com.intellij.openapi.editor.EditorCustomElementRenderer {

    /** 行高缓存 */
    private var lineHeight: Int = -1

    /** 字体度量缓存 */
    private var fontMetrics: java.awt.FontMetrics? = null

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val metrics = getFontMetrics(editor)
        if (metrics == null) {
            // 兜底：每个字符近似 8px
            return text.length * 8 + offsetX
        }
        val firstLine = text.lines().firstOrNull() ?: text
        val width = metrics.stringWidth(firstLine) + offsetX + 4 // +4 留边距
        return maxOf(width, 10)
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val lineH = getLineHeight(editor)
        return if (lineH > 0) lineH else editor.lineHeight
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val editor = inlay.editor
        val metrics = getFontMetrics(editor) ?: return

        // 半透明灰色 — 经典 Ghost Text 风格
        g.color = JBColor(GHOST_COLOR_LIGHT, GHOST_COLOR_DARK)
        g.font = editor.colorsScheme.getFont(EditorFontType.PLAIN)

        val lines = text.lines()
        val lineH = getLineHeight(editor)

        // 绘制第一行（inline）
        var y = targetRegion.y + metrics.ascent
        g.drawString(lines.first(), targetRegion.x + offsetX, y)

        // 绘制后续行（block — 如果有多行）
        for (i in 1 until lines.size) {
            y += lineH
            g.drawString(lines[i], targetRegion.x, y)
        }
    }

    private fun getFontMetrics(editor: Editor): java.awt.FontMetrics? {
        if (fontMetrics == null) {
            try {
                val font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
                val ctx = FontRenderContext(AffineTransform(), true, true)
                val frc = editor.contentComponent.getFontMetrics(font)
                fontMetrics = editor.contentComponent.getFontMetrics(font)
            } catch (_: Exception) {
                return null
            }
        }
        return fontMetrics
    }

    private fun getLineHeight(editor: Editor): Int {
        if (lineHeight < 0) {
            lineHeight = editor.lineHeight
        }
        return lineHeight
    }

    companion object {
        private val GHOST_COLOR_LIGHT = java.awt.Color(128, 128, 128, 120)
        private val GHOST_COLOR_DARK = java.awt.Color(160, 160, 160, 100)
    }
}
