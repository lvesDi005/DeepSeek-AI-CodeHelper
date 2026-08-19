package com.deepseek.plugin.attachment

import com.deepseek.plugin.api.AttachmentKind
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFTextBox
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DocumentExtractionServiceTest {
    private val service = DocumentExtractionService()

    @Test
    fun `detects supported document types`() {
        assertEquals(AttachmentKind.PDF, descriptor(tempFile("sample", ".pdf")).kind)
        assertEquals(AttachmentKind.WORD, descriptor(tempFile("sample", ".docx")).kind)
        assertEquals(AttachmentKind.SPREADSHEET, descriptor(tempFile("sample", ".xlsx")).kind)
        assertEquals(AttachmentKind.PRESENTATION, descriptor(tempFile("sample", ".pptx")).kind)
    }

    @Test
    fun `extracts utf8 and gb18030 text`() {
        val utf8 = tempFile("utf8", ".txt").apply { writeText("hello 文档", Charsets.UTF_8) }
        val gb = tempFile("gb", ".txt").apply { writeBytes("中文内容".toByteArray(charset("GB18030"))) }
        val utf16 = tempFile("utf16", ".txt").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "宽字符".toByteArray(Charsets.UTF_16LE))
        }

        assertEquals("hello 文档", service.extract(descriptor(utf8)).content)
        assertEquals("中文内容", service.extract(descriptor(gb)).content)
        assertEquals("宽字符", service.extract(descriptor(utf16)).content)
    }

    @Test
    fun `extracts pdf text`() {
        val file = tempFile("sample", ".pdf")
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                stream.newLineAtOffset(72f, 700f)
                stream.showText("PDF attachment content")
                stream.endText()
            }
            document.save(file)
        }

        val result = service.extract(descriptor(file))
        assertTrue(result.content.contains("PDF attachment content"))
        assertEquals(1, result.pageCount)
    }

    @Test
    fun `extracts docx paragraphs and tables`() {
        val file = tempFile("sample", ".docx")
        XWPFDocument().use { document ->
            document.createParagraph().createRun().setText("Word attachment content")
            val table = document.createTable(1, 2)
            table.getRow(0).getCell(0).text = "left"
            table.getRow(0).getCell(1).text = "right"
            file.outputStream().use(document::write)
        }

        val content = service.extract(descriptor(file)).content
        assertTrue(content.contains("Word attachment content"))
        assertTrue(content.contains("left | right"))
    }

    @Test
    fun `extracts xlsx values and formulas`() {
        val file = tempFile("sample", ".xlsx")
        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet("Data")
            sheet.createRow(0).apply {
                createCell(0).setCellValue("count")
                createCell(1).setCellValue(2.0)
            }
            sheet.createRow(1).createCell(0).cellFormula = "B1*2"
            file.outputStream().use(workbook::write)
        }

        val content = service.extract(descriptor(file)).content
        assertTrue(content.contains("Worksheet: Data"))
        assertTrue(content.contains("count | 2"))
        assertTrue(content.contains("4"))
    }

    @Test
    fun `extracts pptx slide text`() {
        val file = tempFile("sample", ".pptx")
        XMLSlideShow().use { slideShow ->
            val slide = slideShow.createSlide()
            (slide.createTextBox() as XSLFTextBox).text = "Presentation attachment content"
            file.outputStream().use(slideShow::write)
        }

        val content = service.extract(descriptor(file)).content
        assertTrue(content.contains("Slide 1"))
        assertTrue(content.contains("Presentation attachment content"))
    }

    @Test
    fun `rejects unsupported and oversized files`() {
        val unsupported = tempFile("sample", ".doc")
        assertFailsWith<DocumentExtractionException> { service.extract(descriptor(unsupported)) }

        val tinyLimit = DocumentExtractionService(DocumentExtractionLimits(maxFileBytes = 1))
        val text = tempFile("large", ".txt").apply { writeText("too large") }
        assertFailsWith<DocumentExtractionException> { tinyLimit.extract(descriptor(text)) }
    }

    @Test
    fun `rejects renamed binary files`() {
        val fakePdf = tempFile("fake", ".pdf").apply { writeText("not a pdf") }
        val fakeDocx = tempFile("fake", ".docx").apply { writeText("not a zip") }
        val fakeText = tempFile("fake", ".txt").apply { writeBytes(ByteArray(100) { 0 }) }

        assertFailsWith<DocumentExtractionException> { service.extract(descriptor(fakePdf)) }
        assertFailsWith<DocumentExtractionException> { service.extract(descriptor(fakeDocx)) }
        assertFailsWith<DocumentExtractionException> { service.extract(descriptor(fakeText)) }
    }

    private fun descriptor(file: File) = AttachmentTypeDetector.describe(file)

    private fun tempFile(prefix: String, suffix: String): File =
        kotlin.io.path.createTempFile(prefix, suffix).toFile().apply { deleteOnExit() }
}
