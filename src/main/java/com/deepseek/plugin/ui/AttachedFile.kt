package com.deepseek.plugin.ui

import com.deepseek.plugin.api.AttachmentDescriptor
import com.deepseek.plugin.api.AttachmentKind
import com.deepseek.plugin.api.AttachmentStatus
import com.deepseek.plugin.attachment.AttachmentTypeDetector
import com.deepseek.plugin.attachment.DocumentExtractionService
import com.deepseek.plugin.attachment.PdfPageRenderer
import com.deepseek.plugin.i18n.I18n
import java.io.File
import java.nio.file.Files

/**
 * Represents a file attached to a chat message.
 */
class AttachedFile(
    val name: String,
    val absolutePath: String,
    val size: Long
) {
    private val baseDescriptor: AttachmentDescriptor = AttachmentTypeDetector.describe(File(absolutePath))

    @Volatile
    var status: AttachmentStatus = AttachmentStatus.PENDING
        private set

    @Volatile
    var statusMessage: String? = null
        private set

    val descriptor: AttachmentDescriptor
        get() = baseDescriptor.copy(status = status, statusMessage = statusMessage)

    val kind: AttachmentKind get() = baseDescriptor.kind
    val isImage: Boolean get() = kind == AttachmentKind.IMAGE

    var content: String? = null
        private set

    @Volatile
    var renderedImageFiles: List<File> = emptyList()
        private set

    private var temporaryDirectory: File? = null

    /** Human-readable file size string. */
    val sizeDisplay: String
        get() {
            return when {
                size < 1024 -> "$size B"
                size < 1024 * 1024 -> "%.1f KB".format(size / 1024.0)
                else -> "%.1f MB".format(size / (1024.0 * 1024.0))
            }
        }

    fun readContent(): String? {
        if (content != null) return content
        if (isImage) return null
        status = AttachmentStatus.PROCESSING
        return try {
            val result = extractionService.extract(descriptor)
            if (kind == AttachmentKind.PDF && result.content.isBlank()) {
                val directory = Files.createTempDirectory(TEMP_PREFIX).toFile()
                directory.deleteOnExit()
                temporaryDirectory = directory
                renderedImageFiles = pdfPageRenderer.render(File(absolutePath), directory)
                renderedImageFiles.forEach { it.deleteOnExit() }
                statusMessage = I18n.tr("attachment.pdf.scanned", renderedImageFiles.size)
            }
            status = if (result.truncated || result.warnings.isNotEmpty() || renderedImageFiles.isNotEmpty()) {
                AttachmentStatus.PARTIAL
            } else {
                AttachmentStatus.READY
            }
            if (renderedImageFiles.isEmpty()) {
                statusMessage = result.warnings.joinToString(" ").ifBlank { null }
            }
            result.content.also { content = it }
        } catch (e: Exception) {
            status = AttachmentStatus.FAILED
            statusMessage = e.message
            null
        }
    }

    fun cleanupTemporaryFiles() {
        renderedImageFiles.forEach { it.deleteOnExit() }
        temporaryDirectory?.deleteOnExit()
        renderedImageFiles = emptyList()
        temporaryDirectory = null
    }

    companion object {
        private const val TEMP_PREFIX = "deepseek-codehelper-pdf-"
        private val extractionService = DocumentExtractionService()
        private val pdfPageRenderer = PdfPageRenderer()
    }
}
