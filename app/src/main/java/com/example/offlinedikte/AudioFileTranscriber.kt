package com.example.offlinedikte

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Bir ses dosyasını (WAV, MP3, M4A, OGG, ...) cihaz üzerindeki çözücülerle PCM'e açar ve
 * Vosk ile yazıya döker. Uzun sürebileceği için arka plan iş parçacığında çağrılmalıdır.
 */
class AudioFileTranscriber(private val context: Context) {

    class UnsupportedAudioException(message: String) : IOException(message)

    /**
     * @param onSegment tamamlanan her cümle/parça için çağrılır
     * @param onProgress 0..100 arası ilerleme
     * @param isCancelled true döndüğünde işlem durur
     */
    fun transcribe(
        uri: Uri,
        model: Model,
        onSegment: (String) -> Unit,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var recognizer: Recognizer? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw UnsupportedAudioException("Ses izi bulunamadı")
            extractor.selectTrack(trackIndex)

            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(inputFormat, null, null, 0)
                start()
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var lastProgress = -1

            while (!outputDone && !isCancelled()) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                            format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outputIndex >= 0 -> {
                        if (bufferInfo.size > 0) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val pcm = toMono16(outputBuffer, channels, floatPcm)

                            val activeRecognizer = recognizer
                                ?: Recognizer(model, sampleRate.toFloat()).also { recognizer = it }
                            if (activeRecognizer.acceptWaveForm(pcm, pcm.size)) {
                                textOf(activeRecognizer.result)?.let(onSegment)
                            }

                            if (durationUs > 0) {
                                val progress = (bufferInfo.presentationTimeUs * 100 / durationUs).toInt().coerceIn(0, 100)
                                if (progress != lastProgress) {
                                    lastProgress = progress
                                    onProgress(progress)
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            if (!isCancelled()) {
                recognizer?.let { textOf(it.finalResult)?.let(onSegment) }
                onProgress(100)
            }
        } catch (exception: IOException) {
            throw exception
        } catch (exception: Exception) {
            throw UnsupportedAudioException(exception.message ?: exception.javaClass.simpleName)
        } finally {
            recognizer?.close()
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun textOf(json: String?): String? {
        if (json.isNullOrBlank()) return null
        return runCatching { JSONObject(json).optString("text") }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Çok kanallı 16-bit ya da float PCM'i tek kanallı 16-bit little-endian PCM'e çevirir. */
    private fun toMono16(buffer: ByteBuffer, channels: Int, floatPcm: Boolean): ByteArray {
        val source = buffer.slice().order(ByteOrder.nativeOrder())
        val channelCount = channels.coerceAtLeast(1)
        return if (floatPcm) {
            val floats = source.asFloatBuffer()
            val frames = floats.remaining() / channelCount
            val out = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (frame in 0 until frames) {
                var sum = 0f
                for (channel in 0 until channelCount) sum += floats.get(frame * channelCount + channel)
                val value = (sum / channelCount).coerceIn(-1f, 1f)
                out.putShort((value * Short.MAX_VALUE).toInt().toShort())
            }
            out.array()
        } else {
            val shorts = source.asShortBuffer()
            val frames = shorts.remaining() / channelCount
            val out = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (frame in 0 until frames) {
                var sum = 0
                for (channel in 0 until channelCount) sum += shorts.get(frame * channelCount + channel)
                out.putShort((sum / channelCount).toShort())
            }
            out.array()
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}
