package com.deepseek.plugin.api

enum class AttachmentKind {
    TEXT,
    IMAGE,
    PDF,
    WORD,
    SPREADSHEET,
    PRESENTATION,
    UNSUPPORTED
}

enum class AttachmentStatus {
    PENDING,
    PROCESSING,
    READY,
    PARTIAL,
    FAILED
}

data class AttachmentDescriptor(
    val name: String,
    val absolutePath: String,
    val size: Long,
    val mimeType: String,
    val kind: AttachmentKind,
    val status: AttachmentStatus = AttachmentStatus.PENDING,
    val statusMessage: String? = null
)

data class ExtractedDocument(
    val content: String,
    val pageCount: Int? = null,
    val truncated: Boolean = false,
    val warnings: List<String> = emptyList()
)
