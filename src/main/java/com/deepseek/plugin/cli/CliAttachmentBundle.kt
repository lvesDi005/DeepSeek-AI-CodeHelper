package com.deepseek.plugin.cli

import com.deepseek.plugin.api.AttachmentDescriptor
import com.deepseek.plugin.api.AttachmentKind
import com.deepseek.plugin.attachment.DocumentExtractionService
import com.deepseek.plugin.attachment.PdfPageRenderer
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Comparator

data class CliAttachmentEntry(
    val name: String,
    val kind: AttachmentKind,
    val stagedFile: File,
    val extractedFile: File? = null,
    val imageFiles: List<File> = emptyList(),
    val warning: String? = null
)

class CliAttachmentBundle private constructor(
    val rootDirectory: File,
    val entries: List<CliAttachmentEntry>
) : Closeable {
    val imageFiles: List<File> = entries.flatMap { it.imageFiles }

    fun promptSection(): String = buildString {
        if (entries.isEmpty()) return@buildString
        appendLine("## Attached files")
        appendLine("Inspect the listed files with your native file and vision tools before answering.")
        appendLine("For binary documents, prefer the extracted Markdown sidecar when present and consult the original when needed.")
        entries.forEach { entry ->
            append("- ${entry.kind}: `${entry.stagedFile.absolutePath}`")
            entry.extractedFile?.let { append("; extracted text: `${it.absolutePath}`") }
            if (entry.imageFiles.isNotEmpty() && entry.kind == AttachmentKind.PDF) {
                append("; rendered pages: ${entry.imageFiles.joinToString { "`${it.absolutePath}`" }}")
            }
            entry.warning?.let { append("; warning: $it") }
            appendLine()
        }
        appendLine()
    }

    override fun close() {
        val root = rootDirectory.toPath().toAbsolutePath().normalize()
        if (!root.fileName.toString().startsWith(TEMP_PREFIX)) return
        runCatching {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    companion object {
        private const val TEMP_PREFIX = "deepseek-codehelper-attachments-"
        private const val MAX_TOTAL_BYTES = 50L * 1024 * 1024

        fun prepare(
            attachments: List<AttachmentDescriptor>,
            extractionService: DocumentExtractionService = DocumentExtractionService(),
            pdfRenderer: PdfPageRenderer = PdfPageRenderer()
        ): CliAttachmentBundle {
            val totalBytes = attachments.sumOf { it.size }
            require(totalBytes <= MAX_TOTAL_BYTES) { "Attachments exceed the 50 MB total limit." }

            val root = Files.createTempDirectory(TEMP_PREFIX).toFile()
            try {
                val entries = attachments.mapIndexed { index, descriptor ->
                    val safeName = "${index + 1}-${sanitize(descriptor.name)}"
                    val staged = File(root, safeName)
                    Files.copy(
                        File(descriptor.absolutePath).toPath(),
                        staged.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                    when (descriptor.kind) {
                        AttachmentKind.IMAGE -> CliAttachmentEntry(
                            descriptor.name,
                            descriptor.kind,
                            staged,
                            imageFiles = listOf(staged)
                        )
                        AttachmentKind.UNSUPPORTED -> CliAttachmentEntry(
                            descriptor.name,
                            descriptor.kind,
                            staged,
                            warning = "Unsupported format; inspect the original file if possible."
                        )
                        else -> prepareDocumentEntry(descriptor, staged, root, extractionService, pdfRenderer)
                    }
                }
                return CliAttachmentBundle(root, entries)
            } catch (e: Exception) {
                CliAttachmentBundle(root, emptyList()).close()
                throw e
            }
        }

        private fun prepareDocumentEntry(
            descriptor: AttachmentDescriptor,
            staged: File,
            root: File,
            extractionService: DocumentExtractionService,
            pdfRenderer: PdfPageRenderer
        ): CliAttachmentEntry {
            val stagedDescriptor = descriptor.copy(absolutePath = staged.absolutePath)
            return try {
                val extracted = extractionService.extract(stagedDescriptor)
                val sidecar = extracted.content.takeIf { it.isNotBlank() }?.let { content ->
                    File(root, staged.name + ".extracted.md").apply {
                        writeText(buildString {
                            appendLine("# Extracted from ${descriptor.name}")
                            extracted.pageCount?.let { appendLine("Pages/slides: $it") }
                            extracted.warnings.forEach { appendLine("> Warning: $it") }
                            appendLine()
                            append(content)
                        }, Charsets.UTF_8)
                    }
                }
                val renderedPages = if (descriptor.kind == AttachmentKind.PDF && extracted.content.isBlank()) {
                    pdfRenderer.render(staged, File(root, staged.name + "-pages"))
                } else {
                    emptyList()
                }
                CliAttachmentEntry(
                    descriptor.name,
                    descriptor.kind,
                    staged,
                    sidecar,
                    renderedPages,
                    extracted.warnings.joinToString(" ").ifBlank { null }
                )
            } catch (e: Exception) {
                CliAttachmentEntry(
                    descriptor.name,
                    descriptor.kind,
                    staged,
                    warning = e.message ?: "Document extraction failed."
                )
            }
        }

        private fun sanitize(name: String): String =
            name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "attachment" }
    }
}
