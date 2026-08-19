package com.deepseek.plugin.attachment

import com.deepseek.plugin.api.AttachmentDescriptor
import com.deepseek.plugin.api.AttachmentKind
import java.io.File
import java.nio.file.Files

object AttachmentTypeDetector {
    private val textExtensions = setOf(
        "txt", "md", "markdown", "java", "kt", "kts", "xml", "json", "yaml", "yml",
        "properties", "sql", "gradle", "ts", "tsx", "js", "jsx", "css", "html", "htm",
        "py", "go", "rs", "rb", "c", "cc", "cpp", "h", "hpp", "sh", "ps1", "bat", "cmd",
        "toml", "ini", "csv", "tsv", "log"
    )
    private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")

    fun describe(file: File): AttachmentDescriptor {
        val extension = file.extension.lowercase()
        val kind = when {
            extension in textExtensions -> AttachmentKind.TEXT
            extension in imageExtensions -> AttachmentKind.IMAGE
            extension == "pdf" -> AttachmentKind.PDF
            extension == "docx" -> AttachmentKind.WORD
            extension == "xlsx" -> AttachmentKind.SPREADSHEET
            extension == "pptx" -> AttachmentKind.PRESENTATION
            else -> AttachmentKind.UNSUPPORTED
        }
        val mimeType = runCatching { Files.probeContentType(file.toPath()) }.getOrNull()
            ?: defaultMimeType(extension)
        return AttachmentDescriptor(
            name = file.name,
            absolutePath = file.absolutePath,
            size = file.length(),
            mimeType = mimeType,
            kind = kind
        )
    }

    private fun defaultMimeType(extension: String): String = when (extension) {
        "pdf" -> "application/pdf"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "webp" -> "image/webp"
        else -> "text/plain"
    }
}
