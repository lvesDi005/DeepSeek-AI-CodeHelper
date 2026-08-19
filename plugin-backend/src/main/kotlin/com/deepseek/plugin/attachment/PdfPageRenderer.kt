package com.deepseek.plugin.attachment

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.File
import javax.imageio.ImageIO

class PdfPageRenderer(
    private val maxPages: Int = 10,
    private val dpi: Float = 120f
) {
    fun render(file: File, outputDirectory: File): List<File> {
        outputDirectory.mkdirs()
        return Loader.loadPDF(file).use { document ->
            if (document.isEncrypted) {
                throw DocumentExtractionException("Encrypted PDF is not supported: ${file.name}")
            }
            val renderer = PDFRenderer(document)
            (0 until minOf(document.numberOfPages, maxPages)).map { pageIndex ->
                val output = File(outputDirectory, "page-${pageIndex + 1}.png")
                val image = renderer.renderImageWithDPI(pageIndex, dpi, ImageType.RGB)
                ImageIO.write(image, "png", output)
                output
            }
        }
    }
}
