package com.deepseek.plugin.attachment

import com.deepseek.plugin.api.AttachmentDescriptor
import com.deepseek.plugin.api.AttachmentKind
import com.deepseek.plugin.api.ExtractedDocument
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile

data class DocumentExtractionLimits(
    val maxFileBytes: Long = 25L * 1024 * 1024,
    val maxExtractedChars: Int = 200_000,
    val maxPdfPages: Int = 100,
    val maxSlides: Int = 200,
    val maxSheets: Int = 20,
    val maxRowsPerSheet: Int = 1_000,
    val maxColumnsPerSheet: Int = 50
)

class DocumentExtractionException(message: String, cause: Throwable? = null) : Exception(message, cause)

class DocumentExtractionService(
    private val limits: DocumentExtractionLimits = DocumentExtractionLimits()
) {
    fun extract(descriptor: AttachmentDescriptor): ExtractedDocument {
        val file = File(descriptor.absolutePath)
        validate(file, descriptor)
        return try {
            when (descriptor.kind) {
                AttachmentKind.TEXT -> extractText(file)
                AttachmentKind.PDF -> extractPdf(file)
                AttachmentKind.WORD -> extractWord(file)
                AttachmentKind.SPREADSHEET -> extractSpreadsheet(file)
                AttachmentKind.PRESENTATION -> extractPresentation(file)
                AttachmentKind.IMAGE -> ExtractedDocument("")
                AttachmentKind.UNSUPPORTED -> throw DocumentExtractionException(
                    "Unsupported attachment format: ${descriptor.name}"
                )
            }
        } catch (e: DocumentExtractionException) {
            throw e
        } catch (e: Exception) {
            throw DocumentExtractionException("Failed to parse ${descriptor.name}: ${e.message}", e)
        }
    }

    private fun validate(file: File, descriptor: AttachmentDescriptor) {
        if (!file.isFile) throw DocumentExtractionException("Attachment does not exist: ${descriptor.name}")
        if (descriptor.size > limits.maxFileBytes) {
            throw DocumentExtractionException(
                "Attachment exceeds ${limits.maxFileBytes / (1024 * 1024)} MB limit: ${descriptor.name}"
            )
        }
        if (descriptor.kind == AttachmentKind.UNSUPPORTED) {
            throw DocumentExtractionException("Unsupported attachment format: ${descriptor.name}")
        }
        validateSignature(file, descriptor)
    }

    private fun validateSignature(file: File, descriptor: AttachmentDescriptor) {
        when (descriptor.kind) {
            AttachmentKind.PDF -> {
                val header = file.inputStream().use { it.readNBytes(5) }
                if (!header.contentEquals("%PDF-".toByteArray(StandardCharsets.US_ASCII))) {
                    throw DocumentExtractionException("File is not a valid PDF: ${descriptor.name}")
                }
            }
            AttachmentKind.WORD,
            AttachmentKind.SPREADSHEET,
            AttachmentKind.PRESENTATION -> {
                val requiredEntry = when (descriptor.kind) {
                    AttachmentKind.WORD -> "word/document.xml"
                    AttachmentKind.SPREADSHEET -> "xl/workbook.xml"
                    else -> "ppt/presentation.xml"
                }
                try {
                    ZipFile(file).use { zip ->
                        if (zip.getEntry("[Content_Types].xml") == null || zip.getEntry(requiredEntry) == null) {
                            throw DocumentExtractionException("File does not match its Office format: ${descriptor.name}")
                        }
                    }
                } catch (e: DocumentExtractionException) {
                    throw e
                } catch (e: Exception) {
                    throw DocumentExtractionException("Invalid Office document: ${descriptor.name}", e)
                }
            }
            AttachmentKind.TEXT -> {
                val sample = file.inputStream().use { it.readNBytes(8_192) }
                val hasUtf16Bom = sample.size >= 2 && (
                    (sample[0] == 0xFF.toByte() && sample[1] == 0xFE.toByte()) ||
                        (sample[0] == 0xFE.toByte() && sample[1] == 0xFF.toByte())
                    )
                if (!hasUtf16Bom && sample.count { it == 0.toByte() } > sample.size / 20) {
                    throw DocumentExtractionException("File appears to be binary, not text: ${descriptor.name}")
                }
            }
            else -> Unit
        }
    }

    private fun extractText(file: File): ExtractedDocument {
        val bytes = file.readBytes()
        val decoded = decodeText(bytes)
        return limited(decoded)
    }

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }

        val utf8 = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            utf8.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            String(bytes, charset("GB18030"))
        }
    }

    private fun extractPdf(file: File): ExtractedDocument {
        Loader.loadPDF(file).use { document ->
            if (document.isEncrypted) {
                throw DocumentExtractionException("Encrypted PDF is not supported: ${file.name}")
            }
            val totalPages = document.numberOfPages
            val endPage = minOf(totalPages, limits.maxPdfPages)
            val stripper = PDFTextStripper().apply {
                startPage = 1
                this.endPage = endPage
                sortByPosition = true
            }
            val warnings = mutableListOf<String>()
            if (totalPages > endPage) warnings += "Only the first $endPage of $totalPages PDF pages were parsed."
            val content = stripper.getText(document)
            val result = limited(content, warnings)
            return result.copy(pageCount = totalPages, truncated = result.truncated || totalPages > endPage)
        }
    }

    private fun extractWord(file: File): ExtractedDocument {
        FileInputStream(file).use { input ->
            XWPFDocument(input).use { document ->
                val content = buildString {
                    document.paragraphs.forEach { paragraph ->
                        val text = paragraph.text.trimEnd()
                        if (text.isNotEmpty()) appendLine(text)
                    }
                    document.tables.forEachIndexed { tableIndex, table ->
                        appendLine()
                        appendLine("## Table ${tableIndex + 1}")
                        table.rows.forEach { row ->
                            appendLine(row.tableCells.joinToString(" | ") { escapeCell(it.text) })
                        }
                    }
                }
                return limited(content)
            }
        }
    }

    private fun extractSpreadsheet(file: File): ExtractedDocument {
        FileInputStream(file).use { input ->
            XSSFWorkbook(input).use { workbook ->
                val warnings = mutableListOf<String>()
                val sheetCount = minOf(workbook.numberOfSheets, limits.maxSheets)
                if (workbook.numberOfSheets > sheetCount) {
                    warnings += "Only the first $sheetCount of ${workbook.numberOfSheets} worksheets were parsed."
                }
                val formatter = org.apache.poi.ss.usermodel.DataFormatter()
                val evaluator = workbook.creationHelper.createFormulaEvaluator()
                val content = buildString {
                    for (sheetIndex in 0 until sheetCount) {
                        val sheet = workbook.getSheetAt(sheetIndex)
                        appendLine("## Worksheet: ${sheet.sheetName}")
                        val firstRow = sheet.firstRowNum.coerceAtLeast(0)
                        val lastRow = minOf(sheet.lastRowNum, firstRow + limits.maxRowsPerSheet - 1)
                        for (rowIndex in firstRow..lastRow) {
                            val row = sheet.getRow(rowIndex) ?: continue
                            val lastCell = minOf(row.lastCellNum.toInt().coerceAtLeast(0), limits.maxColumnsPerSheet)
                            val cells = (0 until lastCell).map { columnIndex ->
                                val cell = row.getCell(columnIndex)
                                val value = if (cell == null) "" else formatter.formatCellValue(cell, evaluator)
                                escapeCell(value)
                            }
                            appendLine("${rowIndex + 1}: ${cells.joinToString(" | ")}")
                        }
                        if (sheet.lastRowNum >= firstRow + limits.maxRowsPerSheet) {
                            warnings += "Worksheet ${sheet.sheetName} was limited to ${limits.maxRowsPerSheet} rows."
                        }
                        appendLine()
                    }
                }
                return limited(content, warnings)
            }
        }
    }

    private fun extractPresentation(file: File): ExtractedDocument {
        FileInputStream(file).use { input ->
            XMLSlideShow(input).use { slideShow ->
                val totalSlides = slideShow.slides.size
                val slideCount = minOf(totalSlides, limits.maxSlides)
                val warnings = mutableListOf<String>()
                if (totalSlides > slideCount) warnings += "Only the first $slideCount of $totalSlides slides were parsed."
                val content = buildString {
                    slideShow.slides.take(slideCount).forEachIndexed { index, slide ->
                        appendLine("## Slide ${index + 1}")
                        slide.shapes.forEach { shape ->
                            if (shape is org.apache.poi.xslf.usermodel.XSLFTextShape) {
                                val text = shape.text.trim()
                                if (text.isNotEmpty()) appendLine(text)
                            }
                        }
                        appendLine()
                    }
                }
                val result = limited(content, warnings)
                return result.copy(pageCount = totalSlides, truncated = result.truncated || totalSlides > slideCount)
            }
        }
    }

    private fun limited(content: String, initialWarnings: List<String> = emptyList()): ExtractedDocument {
        val normalized = content.replace("\u0000", "").trim()
        if (normalized.length <= limits.maxExtractedChars) {
            return ExtractedDocument(normalized, warnings = initialWarnings)
        }
        return ExtractedDocument(
            content = normalized.take(limits.maxExtractedChars),
            truncated = true,
            warnings = initialWarnings + "Extracted content was limited to ${limits.maxExtractedChars} characters."
        )
    }

    private fun escapeCell(value: String): String = value.replace("|", "\\|").replace("\r", " ").replace("\n", " ")
}
