package com.example.offlinedikte

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.play.core.assetpacks.model.AssetPackErrorCode
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.IOException
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var contentLayout: View
    private lateinit var controlsLayout: View
    private lateinit var toolbar: MaterialToolbar
    private lateinit var modelChip: Chip
    private lateinit var statusTextView: TextView
    private lateinit var wordCountTextView: TextView
    private lateinit var resultTextView: EditText
    private lateinit var fileProgress: LinearProgressIndicator
    private lateinit var toggleButton: FloatingActionButton
    private lateinit var micPulseView: MicPulseView
    private lateinit var micHintTextView: TextView
    private lateinit var copyButton: Button
    private lateinit var clearButton: Button
    private lateinit var exportButton: Button
    private lateinit var shareButton: Button

    private lateinit var packManager: LanguagePackManager
    private lateinit var selectedProfile: SpeechModelProfile

    private var model: Model? = null
    private var isModelLoading = false
    private var modelLoadGeneration = 0

    private var audioRecord: AudioRecord? = null
    private var recognitionThread: Thread? = null
    private var activeSessionId = 0

    @Volatile
    private var isListening = false

    @Volatile
    private var fileJobCancelled = false
    private var isTranscribingFile = false
    private var fileProgressPercent = 0
    private var pendingAudioUri: Uri? = null

    private var finalTranscript = ""
    private var suppressTranscriptSync = false
    private var pendingExportFormat: TranscriptExporter.Format? = null
    private var pendingImportProfile: SpeechModelProfile? = null

    private var packsSheet: BottomSheetDialog? = null
    private var packsSheetView: View? = null
    private val packRows = mutableMapOf<SpeechModelProfile, View>()
    private val lastPackStatus = mutableMapOf<SpeechModelProfile, LanguagePackManager.Status>()
    private var lastRequestedDownload: SpeechModelProfile? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening() else showErrorDialog(getString(R.string.error_permission_denied))
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val format = pendingExportFormat
            pendingExportFormat = null
            val uri = result.data?.data
            if (result.resultCode == Activity.RESULT_OK && format != null && uri != null) {
                exportTranscript(format, uri)
            }
        }

    private val modelFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val profile = pendingImportProfile
            pendingImportProfile = null
            if (uri != null && profile != null) importModel(profile, uri)
        }

    private val audioFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) startFileTranscription(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        migrateLegacyLanguagePreference()
        setContentView(R.layout.activity_main)
        bindViews()
        applyWindowInsets()

        packManager = LanguagePackManager(this).apply {
            onStateChanged = ::onPackStateChanged
            onConfirmationRequired = { showConfirmation(this@MainActivity) }
        }
        SpeechModelProfile.entries.forEach { lastPackStatus[it] = packManager.state(it).status }
        selectedProfile = SpeechModelProfile.fromCode(prefs().getString(PREF_SPEECH_MODEL, null))
            ?: defaultProfileForLocale()

        setupListeners()
        restoreUiState(savedInstanceState)
        packManager.start()
        loadSelectedModel()
        handleIncomingIntent(intent)

        if (savedInstanceState == null && SpeechModelProfile.entries.none { packManager.modelDirectory(it) != null }) {
            contentLayout.post {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) showLanguagePacks()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        packManager.refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_FINAL_TRANSCRIPT, finalTranscript)
        outState.putString(STATE_VISIBLE_TEXT, resultTextView.text?.toString().orEmpty())
    }

    override fun onDestroy() {
        stopListening()
        fileJobCancelled = true
        packManager.stop()
        packsSheet?.dismiss()
        if (isFinishing && !isChangingConfigurations) {
            SpeechModelCache.release()
        }
        super.onDestroy()
    }

    // region Kurulum

    private fun bindViews() {
        contentLayout = findViewById(R.id.contentLayout)
        controlsLayout = findViewById(R.id.controlsLayout)
        toolbar = findViewById(R.id.toolbar)
        modelChip = findViewById(R.id.modelChip)
        statusTextView = findViewById(R.id.statusTextView)
        wordCountTextView = findViewById(R.id.wordCountTextView)
        resultTextView = findViewById(R.id.resultTextView)
        fileProgress = findViewById(R.id.fileProgress)
        toggleButton = findViewById(R.id.toggleButton)
        micPulseView = findViewById(R.id.micPulseView)
        micHintTextView = findViewById(R.id.micHintTextView)
        copyButton = findViewById(R.id.copyButton)
        clearButton = findViewById(R.id.clearButton)
        exportButton = findViewById(R.id.exportButton)
        shareButton = findViewById(R.id.shareButton)
        micPulseView.innerRadius = resources.displayMetrics.density * 44
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(contentLayout) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = if (imeVisible) imeBottom else bars.bottom
            )
            // Klavye açıkken metne daha fazla yer kalsın diye alt kontroller gizlenir.
            controlsLayout.isVisible = !imeVisible
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setupListeners() {
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_transcribe_file -> {
                    if (!isDestroyed) audioFileLauncher.launch(arrayOf("audio/*"))
                    true
                }
                R.id.action_language_packs -> {
                    showLanguagePacks()
                    true
                }
                R.id.action_ui_language -> {
                    showUiLanguageDialog()
                    true
                }
                R.id.action_about -> {
                    showAboutDialog()
                    true
                }
                else -> false
            }
        }
        toolbar.setNavigationOnClickListener { showAboutDialog() }
        modelChip.setOnClickListener { showLanguagePacks() }
        modelChip.setOnCloseIconClickListener { showLanguagePacks() }
        toggleButton.setOnClickListener { onMicClicked() }
        exportButton.setOnClickListener { showExportOptions() }
        shareButton.setOnClickListener { shareTranscript() }
        copyButton.setOnClickListener { copyTranscript() }
        clearButton.setOnClickListener { clearTranscriptWithUndo() }
        resultTextView.doAfterTextChanged {
            if (!isListening && !isTranscribingFile && !suppressTranscriptSync) {
                finalTranscript = it?.toString()?.trim().orEmpty()
            }
            updateTranscriptActions()
        }
    }

    private fun defaultProfileForLocale(): SpeechModelProfile {
        val language = AppCompatDelegate.getApplicationLocales()[0]?.language
            ?: Locale.getDefault().language
        return when (language) {
            "fa" -> SpeechModelProfile.FARSI
            "ar" -> SpeechModelProfile.ARABIC
            else -> SpeechModelProfile.TURKISH
        }
    }

    /** Eski sürümde dil tercihi SharedPreferences'ta tutuluyordu; bir kez AppCompat'a taşınır. */
    private fun migrateLegacyLanguagePreference() {
        val legacy = prefs().getString(PREF_LEGACY_LANGUAGE, null) ?: return
        prefs().edit().remove(PREF_LEGACY_LANGUAGE).apply()
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(legacy))
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return
        intent.action = Intent.ACTION_MAIN
        startFileTranscription(uri)
    }

    // endregion

    // region Model yükleme

    private fun loadSelectedModel() {
        val profile = selectedProfile
        val generation = ++modelLoadGeneration
        val dir = packManager.modelDirectory(profile)
        if (dir == null) {
            model = null
            isModelLoading = false
            updateUi()
            return
        }

        SpeechModelCache.get(dir)?.let {
            model = it
            isModelLoading = false
            updateUi()
            onModelReady()
            return
        }

        model = null
        isModelLoading = true
        updateUi()
        thread(start = true, name = "vosk-model-loader") {
            val result = runCatching { SpeechModelCache.load(dir) }
            runOnUiThread {
                if (generation != modelLoadGeneration || isDestroyed) return@runOnUiThread
                isModelLoading = false
                result.onSuccess {
                    model = it
                    onModelReady()
                }.onFailure {
                    Log.e(TAG, "Model yüklenemedi", it)
                    model = null
                    showErrorDialog(getString(R.string.model_load_failed))
                }
                updateUi()
            }
        }
    }

    private fun onModelReady() {
        pendingAudioUri?.let {
            pendingAudioUri = null
            startFileTranscription(it)
        }
    }

    private fun selectProfile(profile: SpeechModelProfile) {
        if (profile == selectedProfile && (model != null || isModelLoading)) return
        stopListening()
        fileJobCancelled = true
        selectedProfile = profile
        prefs().edit().putString(PREF_SPEECH_MODEL, profile.code).apply()
        loadSelectedModel()
        renderPackRows()
    }

    // endregion

    // region Canlı dikte

    private fun onMicClicked() {
        when {
            isTranscribingFile -> fileJobCancelled = true
            isListening -> stopListening()
            model == null && !isModelLoading -> showLanguagePacks()
            model != null -> ensurePermissionAndStart()
        }
    }

    private fun ensurePermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListening() {
        val loadedModel = model ?: return
        if (isListening || isTranscribingFile) return

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            showErrorDialog(getString(R.string.error_mic))
            return
        }

        val localRecognizer = try {
            Recognizer(loadedModel, SAMPLE_RATE.toFloat())
        } catch (exception: Exception) {
            Log.e(TAG, "Recognizer oluşturulamadı", exception)
            showErrorDialog(getString(R.string.error_recognizer))
            return
        }

        val localRecorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBufferSize, SAMPLE_RATE)
            )
        } catch (exception: SecurityException) {
            localRecognizer.close()
            showErrorDialog(getString(R.string.error_permission_denied))
            return
        } catch (exception: Exception) {
            localRecognizer.close()
            Log.e(TAG, "AudioRecord oluşturulamadı", exception)
            showErrorDialog(getString(R.string.error_mic))
            return
        }

        if (localRecorder.state != AudioRecord.STATE_INITIALIZED) {
            localRecognizer.close()
            releaseRecorder(localRecorder)
            showErrorDialog(getString(R.string.error_mic))
            return
        }

        try {
            localRecorder.startRecording()
        } catch (exception: Exception) {
            Log.e(TAG, "Kayıt başlatılamadı", exception)
            localRecognizer.close()
            releaseRecorder(localRecorder)
            showErrorDialog(getString(R.string.error_mic))
            return
        }

        activeSessionId += 1
        val sessionId = activeSessionId
        val profile = selectedProfile
        audioRecord = localRecorder
        finalTranscript = resultTextView.text?.toString()?.trim().orEmpty()
        isListening = true
        hideKeyboard()
        moveCursorToEnd()
        updateUi()

        recognitionThread = thread(start = true, name = "vosk-recognition") {
            val buffer = ByteArray(max(minBufferSize, 4096))

            while (isListening && sessionId == activeSessionId && !Thread.currentThread().isInterrupted) {
                val readBytes = localRecorder.read(buffer, 0, buffer.size)
                if (readBytes <= 0) {
                    if (isListening && sessionId == activeSessionId) {
                        runOnUiThread { showErrorDialog(getString(R.string.error_mic_read)) }
                    }
                    break
                }

                val level = calculateLevelPercent(buffer, readBytes)
                runOnUiThread {
                    if (sessionId == activeSessionId) micPulseView.setLevel(level / 100f)
                }

                val isFinal = localRecognizer.acceptWaveForm(buffer, readBytes)
                val json = if (isFinal) localRecognizer.result else localRecognizer.partialResult
                updateTranscript(json, isFinal, sessionId, profile)
            }

            // Durdurma sonrası tanıyıcıda kalan son parça da metne eklenir.
            val finalJson = localRecognizer.finalResult
            if (!finalJson.isNullOrBlank()) {
                updateTranscript(finalJson, true, sessionId, profile, allowAfterStop = true)
            }

            localRecognizer.close()
            releaseRecorder(localRecorder)
            runOnUiThread {
                if (audioRecord === localRecorder) audioRecord = null
            }
        }
    }

    private fun stopListening() {
        if (!isListening) return
        isListening = false
        audioRecord?.let { recorder ->
            try {
                recorder.stop()
            } catch (_: IllegalStateException) {
            }
        }
        audioRecord = null
        recognitionThread = null
        micPulseView.setLevel(0f)
        moveCursorToEnd()
        updateUi()
    }

    private fun releaseRecorder(recorder: AudioRecord) {
        try {
            recorder.stop()
        } catch (_: IllegalStateException) {
        } catch (exception: Exception) {
            Log.w(TAG, "AudioRecord durdurulamadı", exception)
        }
        recorder.release()
    }

    private fun updateTranscript(
        json: String,
        isFinal: Boolean,
        sessionId: Int,
        profile: SpeechModelProfile,
        allowAfterStop: Boolean = false
    ) {
        val key = if (isFinal) "text" else "partial"
        val text = try {
            JSONObject(json).optString(key, "")
        } catch (_: Exception) {
            ""
        }.let(profile::normalize)

        if (text.isBlank()) return

        runOnUiThread {
            if (sessionId != activeSessionId) return@runOnUiThread
            if (!isListening && !allowAfterStop) return@runOnUiThread
            if (isFinal) mergeFinalTranscript(text, profile)
            val displayText = if (isFinal) {
                finalTranscript
            } else {
                listOf(finalTranscript, text).filter { it.isNotBlank() }.joinToString(" ").trim()
            }
            setTranscriptText(displayText)
        }
    }

    private fun mergeFinalTranscript(recognizedText: String, profile: SpeechModelProfile) {
        val normalizedExisting = comparableTokens(finalTranscript, profile)
        val rawWords = recognizedText.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (rawWords.isEmpty()) return

        val rawTokens = rawWords.map(::comparableToken)
        val incrementalWords = if (
            normalizedExisting.isNotEmpty() &&
            rawTokens.size >= normalizedExisting.size &&
            rawTokens.take(normalizedExisting.size) == normalizedExisting
        ) {
            rawWords.drop(normalizedExisting.size)
        } else {
            rawWords
        }

        val incrementalText = incrementalWords.joinToString(" ").trim()
        if (incrementalText.isBlank()) return
        appendToTranscript(incrementalText)
    }

    private fun appendToTranscript(text: String) {
        finalTranscript = buildString {
            append(finalTranscript.trim())
            if (isNotBlank()) append(' ')
            append(text.trim())
        }.trim()
    }

    private fun comparableTokens(text: String, profile: SpeechModelProfile): List<String> {
        return profile.normalize(text)
            .replace(Regex("[\\p{Punct}«»“”‘’،؛؟]+"), " ")
            .split(Regex("\\s+"))
            .map(::comparableToken)
            .filter { it.isNotBlank() }
    }

    private fun comparableToken(token: String): String {
        return token.trim().trim('.', ',', '!', '?', ';', ':', '،', '؛', '؟', '"', '\'', '(', ')', '[', ']', '{', '}')
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

    // endregion

    // region Ses dosyasından yazıya dökme

    private fun startFileTranscription(uri: Uri) {
        val loadedModel = model
        if (loadedModel == null) {
            pendingAudioUri = uri
            if (!isModelLoading) showLanguagePacks()
            return
        }
        if (isTranscribingFile) return
        stopListening()

        val profile = selectedProfile
        finalTranscript = resultTextView.text?.toString()?.trim().orEmpty()
        isTranscribingFile = true
        fileJobCancelled = false
        fileProgressPercent = 0
        micPulseView.setLevel(0.25f)
        updateUi()

        thread(start = true, name = "audio-file-transcriber") {
            val result = runCatching {
                AudioFileTranscriber(applicationContext).transcribe(
                    uri = uri,
                    model = loadedModel,
                    onSegment = { segment ->
                        val text = profile.normalize(segment)
                        runOnUiThread {
                            if (text.isNotBlank()) {
                                appendToTranscript(text)
                                setTranscriptText(finalTranscript)
                            }
                        }
                    },
                    onProgress = { progress ->
                        runOnUiThread {
                            fileProgressPercent = progress
                            updateUi()
                        }
                    },
                    isCancelled = { fileJobCancelled }
                )
            }
            runOnUiThread {
                val cancelled = fileJobCancelled
                isTranscribingFile = false
                fileJobCancelled = false
                micPulseView.setLevel(0f)
                updateUi()
                result.onSuccess {
                    showSnackbar(getString(if (cancelled) R.string.transcribe_cancelled else R.string.transcribe_done))
                }.onFailure {
                    Log.e(TAG, "Ses dosyası yazıya dökülemedi", it)
                    showErrorDialog(getString(R.string.error_audio_file))
                }
            }
        }
    }

    // endregion

    // region Arayüz durumu

    private fun updateUi() {
        if (!::selectedProfile.isInitialized) return
        val packState = packManager.state(selectedProfile)
        modelChip.text = getString(selectedProfile.labelRes)

        statusTextView.text = when {
            isListening -> getString(R.string.status_listening)
            isTranscribingFile -> getString(R.string.status_transcribing_file, fileProgressPercent)
            isModelLoading -> getString(R.string.status_model_loading)
            model != null -> getString(R.string.status_model_ready)
            packState.isBusy -> packStatusText(selectedProfile, packState)
            else -> getString(R.string.status_no_model)
        }
        statusTextView.setTextColor(
            if (isListening) {
                ContextCompat.getColor(this, R.color.brand_red)
            } else {
                MaterialColors.getColor(statusTextView, com.google.android.material.R.attr.colorOnSurfaceVariant)
            }
        )

        val busy = isListening || isTranscribingFile
        toggleButton.setImageResource(if (busy) R.drawable.ic_stop else R.drawable.ic_mic)
        toggleButton.contentDescription = getString(
            if (busy) R.string.action_stop_listening else R.string.action_start_listening
        )
        toggleButton.isEnabled = !isModelLoading || busy
        toggleButton.alpha = if (model == null && !busy) 0.55f else 1f
        toggleButton.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (busy) R.color.brand_red_dark else R.color.brand_red)
        )
        micPulseView.isActive = busy
        micHintTextView.text = getString(
            when {
                isTranscribingFile -> R.string.mic_hint_file
                isListening -> R.string.mic_hint_listening
                isModelLoading -> R.string.mic_hint_loading
                model == null -> R.string.mic_hint_no_model
                else -> R.string.mic_hint_idle
            }
        )

        fileProgress.isVisible = isTranscribingFile
        fileProgress.setProgressCompat(fileProgressPercent, true)
        resultTextView.hint = getString(if (isListening) R.string.hint_start_speaking else R.string.hint_transcript)
        resultTextView.isEnabled = !isTranscribingFile
        updateTranscriptActions()
    }

    private fun updateTranscriptActions() {
        val text = resultTextView.text?.toString().orEmpty()
        val hasText = text.isNotBlank()
        exportButton.isEnabled = hasText
        shareButton.isEnabled = hasText
        copyButton.isEnabled = hasText
        clearButton.isEnabled = hasText && !isListening && !isTranscribingFile
        val words = if (hasText) text.trim().split(Regex("\\s+")).size else 0
        wordCountTextView.text = resources.getQuantityString(R.plurals.word_count, words, words)
    }

    private fun setTranscriptText(text: String) {
        suppressTranscriptSync = true
        resultTextView.setText(text)
        suppressTranscriptSync = false
        moveCursorToEnd()
        updateTranscriptActions()
    }

    private fun moveCursorToEnd() {
        resultTextView.setSelection(resultTextView.text?.length ?: 0)
    }

    private fun hideKeyboard() {
        resultTextView.clearFocus()
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(resultTextView.windowToken, 0)
    }

    private fun restoreUiState(savedInstanceState: Bundle?) {
        val restoredFinal = savedInstanceState?.getString(STATE_FINAL_TRANSCRIPT).orEmpty().trim()
        val restoredVisible = savedInstanceState?.getString(STATE_VISIBLE_TEXT).orEmpty().trim()
        finalTranscript = restoredFinal
        val text = restoredVisible.ifBlank { restoredFinal }
        if (text.isNotBlank()) setTranscriptText(text)
        updateUi()
    }

    private fun showSnackbar(message: String, actionRes: Int? = null, action: (() -> Unit)? = null) {
        val snackbar = Snackbar.make(findViewById(R.id.rootLayout), message, Snackbar.LENGTH_LONG)
        if (controlsLayout.isVisible) snackbar.anchorView = controlsLayout
        if (actionRes != null && action != null) snackbar.setAction(actionRes) { action() }
        snackbar.show()
    }

    private fun showErrorDialog(message: String) {
        stopListening()
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.status_error)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // endregion

    // region Metin işlemleri

    private fun currentTranscript(): String = resultTextView.text?.toString()?.trim().orEmpty()

    private fun copyTranscript() {
        val text = currentTranscript()
        if (text.isBlank()) return
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        // Android 13+ panoya kopyalamayı sistem kendisi gösterir.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) showSnackbar(getString(R.string.copied))
    }

    private fun shareTranscript() {
        val text = currentTranscript()
        if (text.isBlank()) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, getString(R.string.action_share)))
    }

    private fun clearTranscriptWithUndo() {
        val previous = resultTextView.text?.toString().orEmpty()
        if (previous.isBlank()) return
        finalTranscript = ""
        setTranscriptText("")
        showSnackbar(getString(R.string.cleared), R.string.action_undo) {
            finalTranscript = previous.trim()
            setTranscriptText(previous)
        }
    }

    private fun showExportOptions() {
        if (currentTranscript().isBlank()) {
            showSnackbar(getString(R.string.nothing_to_save))
            return
        }
        val formats = TranscriptExporter.Format.entries
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_export_title)
            .setItems(formats.map { getString(it.labelRes) }.toTypedArray()) { _, which ->
                launchExport(formats[which])
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun launchExport(format: TranscriptExporter.Format) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = format.mimeType
            putExtra(Intent.EXTRA_TITLE, TranscriptExporter.suggestedFileName(format))
        }
        if (isDestroyed) return
        pendingExportFormat = format
        exportLauncher.launch(intent)
    }

    private fun exportTranscript(format: TranscriptExporter.Format, uri: Uri) {
        val transcript = currentTranscript()
        if (transcript.isBlank()) return
        thread(start = true, name = "export-${format.extension}") {
            val result = runCatching {
                contentResolver.openOutputStream(uri)?.use { output ->
                    TranscriptExporter.write(format, output, transcript)
                } ?: throw IOException("Çıktı akışı açılamadı")
            }
            runOnUiThread {
                result.onSuccess {
                    showSnackbar(getString(R.string.file_saved, format.extension.uppercase(Locale.ROOT)))
                }.onFailure {
                    Log.e(TAG, "Dışa aktarma başarısız", it)
                    showSnackbar(getString(R.string.file_failed))
                }
            }
        }
    }

    // endregion

    // region Dil paketleri

    private fun showLanguagePacks() {
        // Activity yeniden oluşturulurken (dil değişimi, döndürme) gecikmeli çağrı eski pencereye
        // bağlı bir sayfa açmasın; o sayfadaki düğmeler kayıtsız launcher'larla çökerdi.
        if (isFinishing || isDestroyed || isChangingConfigurations) return
        if (packsSheet?.isShowing == true) return
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_language_packs, null)
        val list = view.findViewById<LinearLayout>(R.id.packList)
        packRows.clear()
        SpeechModelProfile.entries.forEach { profile ->
            val row = layoutInflater.inflate(R.layout.item_language_pack, list, false)
            list.addView(row)
            packRows[profile] = row
        }
        view.findViewById<View>(R.id.importButton).setOnClickListener { chooseImportLanguage() }
        dialog.setContentView(view)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.setOnDismissListener {
            packsSheet = null
            packsSheetView = null
            packRows.clear()
        }
        packsSheet = dialog
        packsSheetView = view
        renderPackRows()
        dialog.show()
        packManager.refresh()
    }

    private fun renderPackRows() {
        val sheetView = packsSheetView ?: return
        val playAvailable = packManager.isPlayDeliveryAvailable
        sheetView.findViewById<View>(R.id.playUnavailableCard).isVisible = playAvailable == false
        sheetView.findViewById<TextView>(R.id.packsSubtitle).setText(
            if (playAvailable == false) R.string.packs_subtitle_offline else R.string.packs_subtitle
        )

        packRows.forEach { (profile, row) ->
            val state = packManager.state(profile)
            val isActive = profile == selectedProfile && state.isInstalled
            val card = row as MaterialCardView
            val badge = row.findViewById<TextView>(R.id.codeBadge)
            val action = row.findViewById<MaterialButton>(R.id.actionButton)
            val delete = row.findViewById<Button>(R.id.deleteButton)
            val progress = row.findViewById<LinearProgressIndicator>(R.id.packProgress)

            badge.text = profile.code
            row.findViewById<TextView>(R.id.nameTextView).text = getString(profile.labelRes)
            row.findViewById<TextView>(R.id.detailTextView).text = buildString {
                val bytes = if (state.totalBytes > 0) state.totalBytes else profile.approxSizeMb * 1_000_000L
                append(getString(R.string.pack_size, Formatter.formatShortFileSize(this@MainActivity, bytes)))
                if (profile.isLarge) append(" · ").append(getString(R.string.pack_large_note))
                append(" · ")
                append(
                    if (isActive) getString(R.string.pack_state_active)
                    else packStatusText(profile, state, playAvailable)
                )
            }

            card.isChecked = isActive
            card.strokeColor = MaterialColors.getColor(
                card,
                if (isActive) androidx.appcompat.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorOutlineVariant
            )
            card.strokeWidth = (resources.displayMetrics.density * if (isActive) 2 else 1).toInt()

            progress.isVisible = state.isBusy
            if (state.isBusy) {
                val indeterminate = state.status != LanguagePackManager.Status.DOWNLOADING || state.progressPercent == 0
                if (progress.isIndeterminate != indeterminate) {
                    progress.isVisible = false
                    progress.isIndeterminate = indeterminate
                    progress.isVisible = true
                }
                if (!indeterminate) progress.setProgressCompat(state.progressPercent, true)
            }

            delete.isVisible = state.isInstalled
            delete.setOnClickListener { confirmDeletePack(profile) }
            action.isEnabled = true
            action.icon = null
            card.setOnClickListener(null)

            when (state.status) {
                LanguagePackManager.Status.INSTALLED -> if (isActive) {
                    action.text = getString(R.string.action_active)
                    action.setIconResource(R.drawable.ic_check)
                    action.isEnabled = false
                } else {
                    action.text = getString(R.string.action_use)
                    action.setOnClickListener { selectProfile(profile) }
                    card.setOnClickListener { selectProfile(profile) }
                }
                LanguagePackManager.Status.PENDING,
                LanguagePackManager.Status.DOWNLOADING,
                LanguagePackManager.Status.INSTALLING -> {
                    action.text = getString(R.string.action_cancel)
                    action.setOnClickListener { packManager.cancel(profile) }
                }
                LanguagePackManager.Status.IMPORTING -> {
                    action.text = getString(R.string.pack_state_importing)
                    action.isEnabled = false
                }
                LanguagePackManager.Status.WAITING_FOR_WIFI,
                LanguagePackManager.Status.NEEDS_CONFIRMATION -> {
                    action.text = getString(R.string.action_continue)
                    action.setOnClickListener { packManager.showConfirmation(this) }
                }
                LanguagePackManager.Status.NOT_INSTALLED,
                LanguagePackManager.Status.FAILED -> if (playAvailable == false) {
                    action.text = getString(R.string.action_import)
                    action.setIconResource(R.drawable.ic_folder)
                    action.setOnClickListener { startImport(profile) }
                } else {
                    val failed = state.status == LanguagePackManager.Status.FAILED
                    action.text = getString(if (failed) R.string.action_retry else R.string.action_download)
                    action.setIconResource(R.drawable.ic_download)
                    action.setOnClickListener {
                        lastRequestedDownload = profile
                        packManager.download(profile)
                    }
                }
            }
        }
    }

    private fun packStatusText(
        profile: SpeechModelProfile,
        state: LanguagePackManager.PackState,
        playAvailable: Boolean? = packManager.isPlayDeliveryAvailable
    ): String = when (state.status) {
        LanguagePackManager.Status.NOT_INSTALLED -> getString(
            if (playAvailable == false) R.string.pack_state_unavailable else R.string.pack_state_not_installed
        )
        LanguagePackManager.Status.PENDING -> getString(R.string.pack_state_pending)
        LanguagePackManager.Status.DOWNLOADING -> getString(R.string.pack_state_downloading, state.progressPercent)
        LanguagePackManager.Status.INSTALLING -> getString(R.string.pack_state_installing)
        LanguagePackManager.Status.IMPORTING -> getString(R.string.pack_state_importing)
        LanguagePackManager.Status.INSTALLED -> getString(
            if (state.source == LanguagePackManager.Source.IMPORTED) R.string.pack_state_imported
            else R.string.pack_state_installed
        )
        LanguagePackManager.Status.WAITING_FOR_WIFI -> getString(R.string.pack_state_waiting_wifi)
        LanguagePackManager.Status.NEEDS_CONFIRMATION -> getString(R.string.pack_state_needs_confirmation)
        LanguagePackManager.Status.FAILED -> if (playAvailable == false) {
            getString(R.string.pack_state_unavailable)
        } else {
            getString(R.string.pack_state_failed)
        }
    }

    private fun onPackStateChanged(profile: SpeechModelProfile) {
        val state = packManager.state(profile)
        val previous = lastPackStatus[profile]
        lastPackStatus[profile] = state.status

        if (previous != state.status) {
            val wasBusy = previous == LanguagePackManager.Status.PENDING ||
                previous == LanguagePackManager.Status.DOWNLOADING ||
                previous == LanguagePackManager.Status.INSTALLING
            if (state.isInstalled && wasBusy) {
                showSnackbar(getString(R.string.pack_ready_snackbar, getString(profile.labelRes)))
                // Kullanıcının indirdiği paket, seçili dilin modeli yoksa otomatik etkinleşir.
                if (profile == lastRequestedDownload && model == null && profile != selectedProfile) {
                    selectProfile(profile)
                }
            }
            if (state.status == LanguagePackManager.Status.FAILED &&
                packManager.isPlayDeliveryAvailable != false
            ) {
                showSnackbar(packErrorMessage(state.errorCode))
            }
        }

        if (profile == selectedProfile && state.isInstalled && model == null && !isModelLoading) {
            loadSelectedModel()
        }
        renderPackRows()
        updateUi()
    }

    private fun packErrorMessage(code: Int): String = when (code) {
        AssetPackErrorCode.NETWORK_ERROR -> getString(R.string.pack_error_network)
        AssetPackErrorCode.INSUFFICIENT_STORAGE -> getString(R.string.pack_error_storage)
        else -> getString(R.string.pack_error_generic, code)
    }

    private fun confirmDeletePack(profile: SpeechModelProfile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.pack_delete_title, getString(profile.labelRes)))
            .setMessage(R.string.pack_delete_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ -> deletePack(profile) }
            .show()
    }

    private fun deletePack(profile: SpeechModelProfile) {
        if (profile == selectedProfile) {
            stopListening()
            fileJobCancelled = true
            modelLoadGeneration++
            model = null
            isModelLoading = false
            SpeechModelCache.release()
        }
        packManager.remove(profile) {
            if (profile == selectedProfile) loadSelectedModel()
            renderPackRows()
            updateUi()
        }
    }

    private fun chooseImportLanguage() {
        val profiles = SpeechModelProfile.entries
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_choose_language)
            .setItems(profiles.map { getString(it.labelRes) }.toTypedArray()) { _, which ->
                startImport(profiles[which])
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun startImport(profile: SpeechModelProfile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(profile.labelRes))
            .setMessage(getString(R.string.import_hint, profile.folderName))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_continue) { _, _ ->
                if (isDestroyed) return@setPositiveButton
                pendingImportProfile = profile
                modelFolderLauncher.launch(null)
            }
            .show()
    }

    private fun importModel(profile: SpeechModelProfile, treeUri: Uri) {
        if (profile == selectedProfile) {
            stopListening()
            modelLoadGeneration++
            model = null
            SpeechModelCache.release()
            updateUi()
        }
        packManager.importFromTree(profile, treeUri) { result ->
            result.onSuccess {
                showSnackbar(getString(R.string.pack_ready_snackbar, getString(profile.labelRes)))
                if (profile == selectedProfile) loadSelectedModel() else selectProfile(profile)
            }.onFailure {
                if (profile == selectedProfile) loadSelectedModel()
                showErrorDialog(getString(R.string.import_failed, it.localizedMessage ?: ""))
            }
        }
    }

    // endregion

    // region Diyaloglar

    private fun showUiLanguageDialog() {
        val tags = listOf("", "tr", "ku", "ar", "fa", "en")
        val labels = listOf(
            getString(R.string.lang_system),
            getString(R.string.lang_tr),
            getString(R.string.lang_ku),
            getString(R.string.lang_ar),
            getString(R.string.lang_fa),
            getString(R.string.lang_en)
        )
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-')
        val checked = tags.indexOf(current).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_ui_language)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                val locales = if (tags[which].isEmpty()) {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(tags[which])
                }
                AppCompatDelegate.setApplicationLocales(locales)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_logo)
            .setTitle(R.string.about_title)
            .setMessage(getString(R.string.about_message, BuildConfig.VERSION_NAME))
            .setPositiveButton(R.string.action_close, null)
            .show()
    }

    // endregion

    private fun prefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    companion object {
        private const val TAG = "MainActivity"
        private const val SAMPLE_RATE = 16_000
        private const val PREFS_NAME = "orato_prefs"
        private const val PREF_LEGACY_LANGUAGE = "ui_language"
        private const val PREF_SPEECH_MODEL = "speech_model"
        private const val STATE_FINAL_TRANSCRIPT = "state_final_transcript"
        private const val STATE_VISIBLE_TEXT = "state_visible_text"
    }
}
