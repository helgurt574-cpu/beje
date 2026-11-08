package com.example.orato.desktop

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.log10

class DesktopSpeechRecognizer(
    private val onModelReady: (SpeechLanguage) -> Unit,
    private val onModelFailed: (SpeechLanguage, String) -> Unit,
    private val onLevelChanged: (Int) -> Unit,
    private val onPartialResult: (String) -> Unit,
    private val onFinalResult: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onListeningChanged: (Boolean) -> Unit
) : Closeable {
    @Volatile
    private var model: Model? = null

    @Volatile
    private var modelLanguage: SpeechLanguage? = null

    @Volatile
    private var microphone: TargetDataLine? = null

    @Volatile
    private var recognitionThread: Thread? = null

    private val listening = AtomicBoolean(false)
    private val loadGeneration = AtomicInteger(0)
    private var lastLevelDispatchAt = 0L

    val isModelReady: Boolean get() = model != null

    /** Modeli arka planda yükler; daha yeni bir çağrı eskisini geçersiz kılar. */
    fun loadModel(language: SpeechLanguage, dir: Path) {
        stopListening()
        val generation = loadGeneration.incrementAndGet()
        unloadModel()
        thread(name = "beje-model-loader", isDaemon = true) {
            try {
                val loaded = Model(Platform.nativePath(dir))
                synchronized(this) {
                    if (generation != loadGeneration.get()) {
                        loaded.close()
                        return@thread
                    }
                    model = loaded
                    modelLanguage = language
                }
                onModelReady(language)
            } catch (exception: Throwable) {
                StartupDiagnostics.logFailure("model.load", exception)
                if (generation == loadGeneration.get()) {
                    onModelFailed(language, StartupDiagnostics.rootCauseText(exception))
                }
            }
        }
    }

    @Synchronized
    fun unloadModel() {
        model?.close()
        model = null
        modelLanguage = null
    }

    fun startListening() {
        val loadedModel = model ?: run {
            onError(DesktopI18n.t("error.model_not_ready"))
            return
        }
        val language = modelLanguage ?: SpeechLanguage.TURKISH

        if (!listening.compareAndSet(false, true)) return

        val audioFormat = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)
        val dataLineInfo = DataLine.Info(TargetDataLine::class.java, audioFormat)
        val line = try {
            (AudioSystem.getLine(dataLineInfo) as TargetDataLine).also {
                it.open(audioFormat)
                it.start()
            }
        } catch (exception: Exception) {
            listening.set(false)
            StartupDiagnostics.logFailure("mic.open", exception)
            onError(DesktopI18n.t("error.mic"))
            return
        }

        microphone = line
        onListeningChanged(true)

        recognitionThread = thread(name = "beje-recognition", isDaemon = true) {
            Recognizer(loadedModel, SAMPLE_RATE.toFloat()).use { recognizer ->
                val buffer = ByteArray(BUFFER_SIZE)
                try {
                    while (listening.get() && !Thread.currentThread().isInterrupted) {
                        val readBytes = line.read(buffer, 0, buffer.size)
                        if (readBytes <= 0) {
                            if (listening.get()) onError(DesktopI18n.t("error.mic_read"))
                            break
                        }

                        dispatchLevel(calculateLevelPercent(buffer, readBytes))

                        val isFinal = recognizer.acceptWaveForm(buffer, readBytes)
                        val json = if (isFinal) recognizer.result else recognizer.partialResult
                        val text = extractText(json, isFinal, language)
                        if (isFinal) {
                            if (text.isNotBlank()) onFinalResult(text)
                            onPartialResult("")
                        } else if (text.isNotBlank()) {
                            onPartialResult(text)
                        }
                    }

                    // Durdurma anında tanıyıcıda kalan son parça da metne eklenir.
                    val finalText = extractText(recognizer.finalResult, true, language)
                    if (finalText.isNotBlank()) onFinalResult(finalText)
                } catch (exception: Exception) {
                    StartupDiagnostics.logFailure("recognition", exception)
                    if (listening.get()) onError(DesktopI18n.t("error.mic_read"))
                } finally {
                    stopLine(line)
                    microphone = null
                    listening.set(false)
                    onPartialResult("")
                    onListeningChanged(false)
                }
            }
        }
    }

    fun stopListening() {
        if (!listening.compareAndSet(true, false)) return
        microphone?.let(::stopLine)
    }

    fun isListening(): Boolean = listening.get()

    /**
     * Ses dosyasını yazıya döker. WAV/AIFF doğrudan okunur; diğer biçimler (MP3, M4A, OGG...)
     * sistemde ffmpeg varsa onunla çözülür. Arka plan iş parçacığında çağrılmalıdır.
     */
    @Throws(IOException::class)
    fun transcribeFile(
        file: File,
        onSegment: (String) -> Unit,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean
    ) {
        val loadedModel = model ?: throw IOException(DesktopI18n.t("error.model_not_ready"))
        val language = modelLanguage ?: SpeechLanguage.TURKISH
        val pcm = openPcmStream(file)
        try {
            Recognizer(loadedModel, SAMPLE_RATE.toFloat()).use { recognizer ->
                val buffer = ByteArray(BUFFER_SIZE)
                var readTotal = 0L
                var lastProgress = -1
                while (!isCancelled()) {
                    val read = pcm.stream.read(buffer)
                    if (read <= 0) break
                    readTotal += read
                    if (recognizer.acceptWaveForm(buffer, read)) {
                        extractText(recognizer.result, true, language).takeIf { it.isNotBlank() }?.let(onSegment)
                    }
                    if (pcm.totalBytes > 0) {
                        val progress = (readTotal * 100 / pcm.totalBytes).toInt().coerceIn(0, 100)
                        if (progress != lastProgress) {
                            lastProgress = progress
                            onProgress(progress)
                        }
                    }
                }
                if (!isCancelled()) {
                    extractText(recognizer.finalResult, true, language).takeIf { it.isNotBlank() }?.let(onSegment)
                    onProgress(100)
                }
            }
        } finally {
            pcm.close()
        }
    }

    private class PcmSource(val stream: InputStream, val totalBytes: Long, private val process: Process?) : Closeable {
        override fun close() {
            runCatching { stream.close() }
            process?.destroy()
        }
    }

    private fun openPcmStream(file: File): PcmSource {
        val target = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)
        val javaSound = runCatching {
            val source = AudioSystem.getAudioInputStream(file)
            val pcmSigned = if (source.format.encoding != AudioFormat.Encoding.PCM_SIGNED) {
                AudioSystem.getAudioInputStream(AudioFormat.Encoding.PCM_SIGNED, source)
            } else {
                source
            }
            val converted = AudioSystem.getAudioInputStream(target, pcmSigned)
            val seconds = if (source.frameLength > 0) source.frameLength / source.format.frameRate.toDouble() else 0.0
            PcmSource(converted, (seconds * SAMPLE_RATE * 2).toLong(), null)
        }.getOrNull()
        if (javaSound != null) return javaSound

        val ffmpeg = findFfmpeg() ?: throw IOException(DesktopI18n.t("error.audio_file"))
        val process = ProcessBuilder(
            ffmpeg, "-v", "quiet", "-i", file.absolutePath, "-ac", "1", "-ar", SAMPLE_RATE.toString(),
            "-f", "s16le", "-"
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val duration = probeDurationSeconds(file)
        return PcmSource(process.inputStream, (duration * SAMPLE_RATE * 2).toLong(), process)
    }

    private fun findFfmpeg(): String? {
        Platform.appHome()?.let { home ->
            val bundled = home.resolve(if (Platform.isWindows) "tools/ffmpeg.exe" else "tools/ffmpeg").toFile()
            if (bundled.canExecute()) return bundled.absolutePath
        }
        val name = if (Platform.isWindows) "ffmpeg.exe" else "ffmpeg"
        if (Platform.commandExists(name)) return name
        listOf("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg").firstOrNull { File(it).canExecute() }?.let { return it }
        return null
    }

    private fun probeDurationSeconds(file: File): Double = runCatching {
        val probe = findFfmpeg()?.replace(Regex("ffmpeg(\\.exe)?$"), "ffprobe$1") ?: return 0.0
        val process = ProcessBuilder(
            probe, "-v", "quiet", "-show_entries", "format=duration", "-of", "csv=p=0", file.absolutePath
        ).start()
        process.inputStream.bufferedReader().readText().trim().toDouble()
    }.getOrDefault(0.0)

    override fun close() {
        stopListening()
        loadGeneration.incrementAndGet()
        unloadModel()
    }

    private fun extractText(json: String?, isFinal: Boolean, language: SpeechLanguage): String {
        if (json.isNullOrBlank()) return ""
        val key = if (isFinal) "text" else "partial"
        return runCatching { JSONObject(json).optString(key, "") }.getOrDefault("").let(language::normalize)
    }

    private fun stopLine(line: TargetDataLine) {
        try {
            if (line.isRunning) line.stop()
        } catch (_: Exception) {
        } finally {
            line.close()
        }
    }

    private fun calculateLevelPercent(buffer: ByteArray, length: Int): Int {
        var sum = 0.0
        var sampleCount = 0
        var index = 0
        while (index + 1 < length) {
            val sample = ((buffer[index + 1].toInt() shl 8) or (buffer[index].toInt() and 0xFF)).toShort()
            sum += abs(sample.toInt()).toDouble()
            sampleCount++
            index += 2
        }
        if (sampleCount == 0) return 0
        val average = sum / sampleCount
        val decibels = 20 * log10((average / Short.MAX_VALUE).coerceAtLeast(1E-4))
        return (((decibels + 70) / 60) * 100).toInt().coerceIn(0, 100)
    }

    private fun dispatchLevel(level: Int) {
        val now = System.currentTimeMillis()
        if (now - lastLevelDispatchAt >= LEVEL_UPDATE_INTERVAL_MS) {
            lastLevelDispatchAt = now
            onLevelChanged(level)
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val BUFFER_SIZE = 8192
        const val LEVEL_UPDATE_INTERVAL_MS = 50L
    }
}
