package com.deepseek.plugin.handler

import com.deepseek.plugin.chat.ChatPanel
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO

/**
 * 拦截编辑器粘贴事件，检测剪贴板是否包含图片。
 *
 * 执行顺序：
 * 1. 轻量检测是否有图片（仅 check flavor 列表，不读数据）
 * 2. 无图片 → 委托给 originalHandler，完全不影响原生粘贴
 * 3. 有图片 → 先委托 originalHandler 处理文本，再保存图片到聊天附件
 */
@Suppress("DEPRECATION")
class PasteImageHandler(private val originalHandler: EditorActionHandler?) : EditorActionHandler() {

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext) {
        // 轻量检测：是否含图片
        val hasImage = try {
            val sysClip = Toolkit.getDefaultToolkit().systemClipboard
            sysClip.getAvailableDataFlavors().any { DataFlavor.imageFlavor.equals(it) }
        } catch (_: Exception) {
            false
        }

        // 无图片 → 委托给原始处理器，零干扰
        if (!hasImage) {
            originalHandler?.execute(editor, caret, dataContext)
            return
        }

        // 有图片：先委托原始处理器处理文本粘贴
        originalHandler?.execute(editor, caret, dataContext)

        // 再保存图片到聊天附件
        try {
            val sysClip = Toolkit.getDefaultToolkit().systemClipboard
            val image = sysClip.getData(DataFlavor.imageFlavor) as? java.awt.Image ?: return
            val project = editor.project ?: ProjectManager.getInstance().defaultProject
            val savedFile = saveImageToProject(image, project)
            addAsChatAttachment(project, savedFile)
        } catch (_: Exception) {
            // 静默忽略
        }
    }

    @Throws(IOException::class)
    private fun saveImageToProject(image: java.awt.Image, project: Project): File {
        val projectPath = project.basePath ?: throw IOException("无法获取项目路径")
        val imagesDir = File(projectPath, "images")
        if (!imagesDir.exists()) imagesDir.mkdirs()
        val imageFile = File(imagesDir, "image_${System.currentTimeMillis()}.png")
        ImageIO.write(toBufferedImage(image), "png", imageFile)
        return imageFile
    }

    private fun toBufferedImage(image: java.awt.Image): BufferedImage {
        val w = image.getWidth(null).coerceAtLeast(1)
        val h = image.getHeight(null).coerceAtLeast(1)
        val bi = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = bi.createGraphics()
        g.drawImage(image, 0, 0, null)
        g.dispose()
        return bi
    }

    private fun addAsChatAttachment(project: Project, file: File) {
        val chatPanel = ChatPanel.currentInstance
        chatPanel?.addFileAttachmentFromExternal(file)
    }
}
