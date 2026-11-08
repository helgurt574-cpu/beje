package com.example.orato.desktop

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

class DesktopExportService(
    private val statusSink: (String) -> Unit
) {
    fun exportTranscript(parent: java.awt.Component?, transcript: String) {
        if (transcript.isBlank()) {
            statusSink(DesktopI18n.t("status.no_text"))
            return
        }

        val formats = ExportFormat.entries.toTypedArray()
        val labels = formats.map { DesktopI18n.t(it.labelKey) }.toTypedArray()
        val chosenIndex = javax.swing.JOptionPane.showOptionDialog(
            parent,
            DesktopI18n.t("dialog.export_pick"),
            DesktopI18n.t("dialog.export"),
            javax.swing.JOptionPane.DEFAULT_OPTION,
            javax.swing.JOptionPane.PLAIN_MESSAGE,
            null,
            labels,
            labels.firstOrNull()
        )

        if (chosenIndex !in formats.indices) {
            return
        }

        val format = formats[chosenIndex]
        val chooser = JFileChooser().apply {
            dialogTitle = DesktopI18n.t("dialog.save")
            selectedFile = File(createSuggestedFileName(format))
            fileFilter = FileNameExtensionFilter(DesktopI18n.t(format.labelKey), format.extension)
        }

        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return
        }

        val outputFile = ensureExtension(chooser.selectedFile, format.extension)
        runCatching {
            when (format) {
                ExportFormat.TXT -> writeTxt(outputFile, transcript)
                ExportFormat.PDF -> writePdf(outputFile, transcript)
                ExportFormat.DOC -> writeDoc(outputFile, transcript)
                ExportFormat.DOCX -> writeDocx(outputFile, transcript)
            }
        }.onSuccess {
            statusSink(DesktopI18n.t("status.saved").format(outputFile.name))
        }.onFailure { exception ->
            statusSink(
                DesktopI18n.t("status.save_fail").format(
                    exception.localizedMessage ?: exception.javaClass.simpleName
                )
            )
        }
    }

    private fun createSuggestedFileName(format: ExportFormat): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "beje_$timestamp.${format.extension}"
    }

    private fun ensureExtension(file: File, extension: String): File {
        return if (file.extension.equals(extension, true)) file else File(file.parentFile ?: File("."), "${file.name}.$extension")
    }

    private fun writeTxt(file: File, transcript: String) {
        file.writeText(transcript, Charsets.UTF_8)
    }

    private fun writePdf(file: File, transcript: String) {
        PDDocument().use { document ->
            val pageSize = PDRectangle.A4
            val pageWidth = pageSize.width.toInt()
            val pageHeight = pageSize.height.toInt()
            val margin = 40
            val font = Font("Serif", Font.PLAIN, 16)
            val lineHeight = 24
            val lines = wrapLinesForGraphics(transcript, font, pageWidth - (margin * 2))

            var lineIndex = 0
            val printableLines = if (lines.isEmpty()) listOf("") else lines
            while (lineIndex < printableLines.size) {
                val image = BufferedImage(pageWidth, pageHeight, BufferedImage.TYPE_INT_RGB)
                val graphics = image.createGraphics()
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, pageWidth, pageHeight)
                graphics.color = Color.BLACK
                graphics.font = font
                graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

                var y = margin + graphics.fontMetrics.ascent
                val maxY = pageHeight - margin
                while (lineIndex < printableLines.size && y <= maxY) {
                    graphics.drawString(printableLines[lineIndex], margin, y)
                    y += lineHeight
                    lineIndex++
                }
                graphics.dispose()

                val page = PDPage(pageSize)
                document.addPage(page)
                PDPageContentStream(document, page).use { content ->
                    val pdImage = LosslessFactory.createFromImage(document, image)
                    content.drawImage(pdImage, 0f, 0f, pageSize.width, pageSize.height)
                }
            }

            document.save(file)
        }
    }

    private fun writeDoc(file: File, transcript: String) {
        val html = """
            <html>
            <head>
              <meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
            </head>
            <body>
              ${transcript.split('\n').joinToString("<br>") { escapeHtml(it) }}
            </body>
            </html>
        """.trimIndent()
        file.writeText(html, Charsets.UTF_8)
    }

    private fun writeDocx(file: File, transcript: String) {
        ZipOutputStream(file.outputStream()).use { zip ->
            writeStoredEntry(zip, "[Content_Types].xml", createDocxContentTypes().toByteArray(StandardCharsets.UTF_8))
            writeZipEntry(zip, "_rels/.rels", createDocxRels().toByteArray(StandardCharsets.UTF_8))
            writeZipEntry(zip, "word/document.xml", createDocxDocument(transcript).toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun writeStoredEntry(zip: ZipOutputStream, name: String, data: ByteArray) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = data.size.toLong()
            compressedSize = data.size.toLong()
            crc = CRC32().apply { update(data) }.value
        }
        zip.putNextEntry(entry)
        zip.write(data)
        zip.closeEntry()
    }

    private fun writeZipEntry(zip: ZipOutputStream, name: String, data: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(data)
        zip.closeEntry()
    }

    private fun createDocxContentTypes(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
          <Default Extension="xml" ContentType="application/xml"/>
          <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
        </Types>
    """.trimIndent()

    private fun createDocxRels(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
        </Relationships>
    """.trimIndent()

    private fun createDocxDocument(transcript: String): String {
        val paragraphs = transcript
            .split(Regex("\\n+"))
            .ifEmpty { listOf("") }
            .joinToString("") { paragraph ->
                val safeText = if (paragraph.isBlank()) "" else escapeXml(paragraph)
                "<w:p><w:r><w:t xml:space=\"preserve\">$safeText</w:t></w:r></w:p>"
            }

        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                mc:Ignorable="w14 wp14">
              <w:body>
                $paragraphs
                <w:sectPr>
                  <w:pgSz w:w="11906" w:h="16838"/>
                  <w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="720" w:footer="720" w:gutter="0"/>
                </w:sectPr>
              </w:body>
            </w:document>
        """.trimIndent()
    }

    private fun wrapLinesForGraphics(text: String, font: Font, maxWidth: Int): List<String> {
        val result = mutableListOf<String>()
        val probeImage = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val graphics = probeImage.createGraphics()
        graphics.font = font
        val metrics = graphics.fontMetrics

        text.split('\n').forEach { paragraph ->
            val words = paragraph.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.isEmpty()) {
                result += ""
                return@forEach
            }

            var current = words.first()
            for (word in words.drop(1)) {
                val candidate = "$current $word"
                val width = metrics.stringWidth(candidate)
                if (width <= maxWidth) {
                    current = candidate
                } else {
                    result += current
                    current = word
                }
            }
            result += current
        }
        graphics.dispose()
        return result
    }

    private fun escapeXml(text: String): String = buildString {
        text.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(char)
            }
        }
    }

    private fun escapeHtml(text: String): String = buildString {
        text.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(char)
            }
        }
    }

    private enum class ExportFormat(val labelKey: String, val extension: String) {
        TXT("format.txt", "txt"),
        PDF("format.pdf", "pdf"),
        DOC("format.doc", "doc"),
        DOCX("format.docx", "docx")
    }
}
