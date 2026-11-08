package com.example.orato.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object StartupDiagnostics {
    fun logFailure(stage: String, throwable: Throwable) {
        runCatching {
            val logPath = logFilePath()
            logPath.parent?.let(Files::createDirectories)
            val content = buildString {
                append('[')
                append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                append("] ")
                append(stage)
                append(" (")
                append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version"))
                append(", ").append(System.getProperty("os.arch"))
                append(", Java ").append(System.getProperty("java.version"))
                appendLine(")")
                // Hatanın içindeki asıl sebepler de yazılır (ör. kütüphane neden yüklenemedi).
                var current: Throwable? = throwable
                var depth = 0
                while (current != null && depth < 8) {
                    if (depth > 0) append("Caused by: ")
                    append(current::class.java.name)
                    appendLine(": " + (current.message ?: "(no message)"))
                    current.stackTrace.take(25).forEach { appendLine("    at $it") }
                    current = current.cause?.takeIf { it !== current }
                    depth++
                }
                appendLine()
            }
            Files.writeString(
                logPath,
                content,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND
            )
        }
    }

    /** Hatanın en içteki sebebini kısa bir metin olarak verir. */
    fun rootCauseText(throwable: Throwable): String {
        var root = throwable
        while (root.cause != null && root.cause !== root) root = root.cause!!
        val message = root.message?.takeIf { it.isNotBlank() } ?: throwable.message
        return listOfNotNull(root.javaClass.simpleName, message).joinToString(": ")
    }

    fun logPath(): Path = logFilePath()

    fun failureMessage(throwable: Throwable): String {
        val detail = throwable.localizedMessage ?: throwable::class.java.simpleName
        return "Uygulama baslatilamadi: $detail\n\nLog: ${logFilePath()}"
    }

    private fun logFilePath(): Path = Platform.dataDir().resolve("logs").resolve("beje.log")
}
