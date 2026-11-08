package com.example.offlinedikte

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.annotation.StringRes
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Metni TXT, PDF, ODT ve DOCX biçimlerinde yazar. */
object TranscriptExporter {

    enum class Format(@StringRes val labelRes: Int, val extension: String, val mimeType: String) {
        TXT(R.string.format_txt, "txt", "text/plain"),
        PDF(R.string.format_pdf, "pdf", "application/pdf"),
        ODT(R.string.format_odt, "odt", ODT_MIME_TYPE),
        DOCX(R.string.format_docx, "docx", DOCX_MIME_TYPE)
    }

    fun suggestedFileName(format: Format): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "beje_$timestamp.${format.extension}"
    }

    fun write(format: Format, output: OutputStream, transcript: String) {
        when (format) {
            Format.TXT -> output.write(transcript.toByteArray(Charsets.UTF_8))
            Format.PDF -> writePdf(output, transcript)
            Format.ODT -> writeOdt(output, transcript)
            Format.DOCX -> writeDocx(output, transcript)
        }
    }

    private fun writePdf(output: OutputStream, transcript: String) {
        val document = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 48
        val textWidth = pageWidth - (margin * 2)
        val lineHeight = 22
        val maxLinesPerPage = ((pageHeight - (margin * 2)) / lineHeight).coerceAtLeast(1)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f }
        val lines = buildWrappedLines(transcript, paint, textWidth.toFloat()).ifEmpty { listOf(PdfLine("", false)) }
        var pageNumber = 1
        var lineIndex = 0

        while (lineIndex < lines.size) {
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
            val page = document.startPage(pageInfo)
            var y = margin + lineHeight
            val endIndex = minOf(lineIndex + maxLinesPerPage, lines.size)

            while (lineIndex < endIndex) {
                val line = lines[lineIndex]
                val x = if (line.rtl) {
                    (pageWidth - margin).toFloat() - paint.measureText(line.text)
                } else {
                    margin.toFloat()
                }
                page.canvas.drawText(line.text, x, y.toFloat(), paint)
                y += lineHeight
                lineIndex++
            }

            document.finishPage(page)
            pageNumber++
        }

        document.writeTo(output)
        document.close()
    }

    private fun writeOdt(output: OutputStream, transcript: String) {
        ZipOutputStream(output).use { zip ->
            writeStoredEntry(zip, "mimetype", ODT_MIME_TYPE.toByteArray(Charsets.UTF_8))
            writeZipEntry(zip, "META-INF/manifest.xml", odtManifest().toByteArray(Charsets.UTF_8))
            writeZipEntry(zip, "content.xml", odtContent(transcript).toByteArray(Charsets.UTF_8))
        }
    }

    private fun writeDocx(output: OutputStream, transcript: String) {
        ZipOutputStream(output).use { zip ->
            writeZipEntry(zip, "[Content_Types].xml", docxContentTypes().toByteArray(Charsets.UTF_8))
            writeZipEntry(zip, "_rels/.rels", docxRels().toByteArray(Charsets.UTF_8))
            writeZipEntry(zip, "word/document.xml", docxDocument(transcript).toByteArray(Charsets.UTF_8))
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

    private fun odtManifest(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.2">
          <manifest:file-entry manifest:full-path="/" manifest:media-type="$ODT_MIME_TYPE"/>
          <manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>
        </manifest:manifest>
    """.trimIndent()

    private fun odtContent(transcript: String): String {
        val paragraphs = transcript
            .split(Regex("\\n+"))
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("") }
            .joinToString("") { "<text:p>${escapeXml(it)}</text:p>" }

        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <office:document-content
                xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
                office:version="1.2">
              <office:body>
                <office:text>
                  $paragraphs
                </office:text>
              </office:body>
            </office:document-content>
        """.trimIndent()
    }

    private fun docxContentTypes(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
          <Default Extension="xml" ContentType="application/xml"/>
          <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
        </Types>
    """.trimIndent()

    private fun docxRels(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
        </Relationships>
    """.trimIndent()

    private fun docxDocument(transcript: String): String {
        val paragraphs = transcript
            .split(Regex("\\n+"))
            .ifEmpty { listOf("") }
            .joinToString("") { paragraph ->
                val rtl = if (isRtlParagraph(paragraph)) "<w:pPr><w:bidi/></w:pPr>" else ""
                val runProps = if (isRtlParagraph(paragraph)) "<w:rPr><w:rtl/></w:rPr>" else ""
                "<w:p>$rtl<w:r>$runProps<w:t xml:space=\"preserve\">${escapeXml(paragraph)}</w:t></w:r></w:p>"
            }

        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
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

    private fun buildWrappedLines(text: String, paint: Paint, maxWidth: Float): List<PdfLine> {
        val result = mutableListOf<PdfLine>()
        text.split('\n').forEach { paragraph ->
            val rtl = isRtlParagraph(paragraph)
            val words = paragraph.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.isEmpty()) {
                result += PdfLine("", rtl)
                return@forEach
            }

            var currentLine = words.first()
            for (word in words.drop(1)) {
                val candidate = "$currentLine $word"
                if (paint.measureText(candidate) <= maxWidth) {
                    currentLine = candidate
                } else {
                    result += PdfLine(currentLine, rtl)
                    currentLine = word
                }
            }
            result += PdfLine(currentLine, rtl)
        }
        return result
    }

    private fun isRtlParagraph(text: String): Boolean {
        return text.firstOrNull { !it.isWhitespace() }?.let { char ->
            val directionality = Character.getDirectionality(char)
            directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
        } ?: false
    }

    private data class PdfLine(val text: String, val rtl: Boolean)

    private const val ODT_MIME_TYPE = "application/vnd.oasis.opendocument.text"
    private const val DOCX_MIME_TYPE =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
}
