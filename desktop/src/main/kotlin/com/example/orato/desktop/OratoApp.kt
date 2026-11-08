package com.example.orato.desktop

import com.formdev.flatlaf.FlatClientProperties
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Image
import java.awt.Insets
import java.awt.Taskbar
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JCheckBoxMenuItem
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JProgressBar
import javax.swing.JRadioButtonMenuItem
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.concurrent.thread

fun main(args: Array<String>) {
    val command = when {
        "--quit" in args -> "quit"
        "--toggle" in args -> "toggle"
        else -> "show"
    }
    val startHidden = "--background" in args
    System.setProperty("apple.awt.application.name", OratoMetadata.APP_NAME)
    System.setProperty("flatlaf.menuBarEmbedded", "true")

    if (command == "quit") {
        // Kurulum/kaldırma sırasında çalışan Bêje'yi kapatmak için; çalışmıyorsa bir şey yapmaz.
        AppInstanceGuard.sendToRunningInstance("quit")
        return
    }
    if (!AppInstanceGuard.tryAcquire()) {
        // Zaten çalışıyorsa komutu ona ilet (pencereyi göster ya da dinlemeyi aç/kapat).
        if (AppInstanceGuard.sendToRunningInstance(command)) return
        SwingUtilities.invokeLater {
            BejeTheme.apply()
            JOptionPane.showMessageDialog(
                null, DesktopI18n.t("error.instance_running"), OratoMetadata.APP_NAME, JOptionPane.WARNING_MESSAGE
            )
        }
        return
    }

    Thread.setDefaultUncaughtExceptionHandler { _, error -> StartupDiagnostics.logFailure("uncaught", error) }
    SwingUtilities.invokeLater {
        BejeTheme.apply()
        BejeWindow(startHidden).start()
    }
}

private enum class ModelState { MISSING, LOADING, READY, FAILED }

private class BejeWindow(private val startHidden: Boolean) {
    private val frame = JFrame(OratoMetadata.APP_NAME)
    private val prefs: Preferences = Preferences.userRoot().node("beje")

    // Üst kısım
    private val titleLabel = JLabel(OratoMetadata.APP_NAME)
    private val taglineLabel = JLabel()
    private val languageButton = JButton()
    private val menuButton = JButton()
    private val statusLabel = JLabel(" ")
    private val statusAction = JButton()

    // Metin kartı
    private val transcriptTitle = JLabel()
    private val wordCountLabel = JLabel()
    private val clearButton = JButton()
    private val transcriptArea = JTextArea()
    private val transcriptScroll = JScrollPane(transcriptArea)
    private val fileProgress = JProgressBar(0, 100)

    // Alt kontroller
    private val micButton = MicButton { onMicClicked() }
    private val saveButton = JButton()
    private val copyButton = JButton()
    private val micHint = JLabel(" ", SwingConstants.CENTER)
    private val injectCheck = JCheckBox()
    private val hotkeyLabel = JLabel()
    private val shortcutHelpButton = JButton()

    private val textInjector = TextInjector.create()
    private val startupManager = StartupManager.create()
    private val formatter = DictationTextFormatter()
    private val exportService = DesktopExportService { message -> flash(message) }

    private var modelState = ModelState.MISSING
    private var modelError = ""
    private var listening = false
    private var transcribingFile = false
    @Volatile private var fileCancelled = false
    private var fileProgressPercent = 0
    private var finalTranscript = ""
    private var partialText = ""
    private var suppressSync = false
    private var flashTimer: Timer? = null
    private var trayInstalled = false
    private var languagePacksDialog: LanguagePacksDialog? = null

    private val recognizer = DesktopSpeechRecognizer(
        onModelReady = { language -> ui { if (language == ModelLibrary.current()) { modelState = ModelState.READY; updateUi() } } },
        onModelFailed = { language, message ->
            ui {
                if (language == ModelLibrary.current()) {
                    modelState = ModelState.FAILED
                    modelError = message
                    updateUi()
                    JOptionPane.showMessageDialog(
                        frame,
                        DesktopI18n.t("status.model_failed").format(message) + "\n\n" + StartupDiagnostics.logPath(),
                        OratoMetadata.APP_NAME,
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        },
        onLevelChanged = { level -> ui { micButton.setLevel(level / 100f) } },
        onPartialResult = { text -> ui { partialText = text; showTranscript() } },
        onFinalResult = { text -> ui { appendFinal(text, inject = true) } },
        onError = { message -> ui { showError(message) } },
        onListeningChanged = { value -> ui { onListeningChanged(value) } }
    )

    private val hotkeyService = GlobalHotkeyService(onHotkeyPressed = { ui { onMicClicked() } })

    private val trayService = SystemTrayService(
        onShowWindow = { ui { showWindow() } },
        onToggleListening = { ui { onMicClicked() } },
        onToggleStartup = { ui { setStartup(!startupManager.isEnabled()) } },
        onExit = { ui { shutdown() } }
    )

    fun start() {
        buildFrame()
        hotkeyService.start()
        trayInstalled = trayService.install()
        trayService.setStartupEnabled(startupManager.isEnabled())
        AppInstanceGuard.startCommandServer { command ->
            ui {
                when (command) {
                    "toggle" -> onMicClicked()
                    "quit" -> shutdown()
                    else -> showWindow()
                }
            }
        }
        refreshTexts()
        chooseInitialLanguage()
        loadCurrentModel()

        // Geliştirici: -Dbeje.snapshot=dosya.png ile pencereyi göstermeden görüntüsünü kaydeder.
        System.getProperty("beje.snapshot.audio")?.let { audio ->
            Timer(4000) { transcribeFile(File(audio)) }.apply { isRepeats = false; start() }
        }
        System.getProperty("beje.snapshot")?.let { path ->
            Timer(Integer.getInteger("beje.snapshot.delay", 4000)) { saveSnapshot(path) }.apply { isRepeats = false; start() }
            return
        }
        if (!startHidden || !trayInstalled) {
            frame.isVisible = true
            trayService.setWindowVisible(true)
        }
        if (modelState == ModelState.MISSING && !startHidden) {
            SwingUtilities.invokeLater { showLanguagePacks() }
        } else if (DesktopIntegration.appImage != null && !DesktopIntegration.isIntegrated() &&
            !prefs.getBoolean("integration_asked", false)
        ) {
            // AppImage ilk kez açıldığında uygulama menüsüne eklemeyi öner.
            prefs.putBoolean("integration_asked", true)
            SwingUtilities.invokeLater { askIntegration() }
        }
    }

    // region Arayüz kurulumu

    private fun buildFrame() {
        frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        frame.minimumSize = Dimension(560, 600)
        frame.size = Dimension(
            prefs.getInt("window_width", 780).coerceAtLeast(560),
            prefs.getInt("window_height", 760).coerceAtLeast(600)
        )
        loadAppIcons().let { icons ->
            frame.iconImages = icons
            if (Platform.isMac) runCatching { Taskbar.getTaskbar().iconImage = icons.last() }
        }
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(event: WindowEvent) = onCloseRequested()
        })
        frame.contentPane = buildContent()
        frame.setLocationRelativeTo(null)

        val f8 = KeyStroke.getKeyStroke(KeyEvent.VK_F8, 0)
        frame.rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(f8, "toggle-listening")
        frame.rootPane.actionMap.put("toggle-listening", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) = onMicClicked()
        })
    }

    private fun buildContent(): JComponent {
        val root = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: java.awt.Graphics) {
                g.color = BejeTheme.surface
                g.fillRect(0, 0, width, height)
            }
        }
        root.border = BorderFactory.createEmptyBorder(16, 20, 14, 20)
        root.add(buildHeader(), BorderLayout.NORTH)
        root.add(buildTranscriptCard(), BorderLayout.CENTER)
        root.add(buildControls(), BorderLayout.SOUTH)
        return root
    }

    private fun buildHeader(): JComponent {
        val logo = JLabel(loadLogo(44))
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 22f)
        taglineLabel.font = taglineLabel.font.deriveFont(12.5f)

        val titles = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(titleLabel)
            add(taglineLabel)
        }
        val brand = JPanel(FlowLayout(FlowLayout.LEADING, 12, 0)).apply {
            isOpaque = false
            add(logo)
            add(titles)
        }

        languageButton.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT)
        languageButton.horizontalTextPosition = SwingConstants.TRAILING
        languageButton.iconTextGap = 8
        languageButton.margin = Insets(6, 12, 6, 12)
        languageButton.addActionListener { showLanguageMenu() }

        menuButton.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON)
        menuButton.addActionListener { showMainMenu() }

        val actions = JPanel(FlowLayout(FlowLayout.TRAILING, 6, 0)).apply {
            isOpaque = false
            add(languageButton)
            add(menuButton)
        }

        statusAction.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS)
        statusAction.isVisible = false
        val statusRow = JPanel(FlowLayout(FlowLayout.TRAILING, 6, 0)).apply {
            isOpaque = false
            add(statusLabel)
            add(statusAction)
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(brand, BorderLayout.LINE_START)
            add(actions, BorderLayout.LINE_END)
            add(statusRow, BorderLayout.SOUTH)
            border = BorderFactory.createEmptyBorder(0, 0, 10, 0)
        }
    }

    private fun buildTranscriptCard(): JComponent {
        transcriptTitle.font = transcriptTitle.font.deriveFont(Font.BOLD, 14f)
        wordCountLabel.font = wordCountLabel.font.deriveFont(11.5f)

        clearButton.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON)
        clearButton.addActionListener { clearWithUndo() }

        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(14, 20, 4, 16)
            add(JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(transcriptTitle)
                add(wordCountLabel)
            }, BorderLayout.CENTER)
            add(clearButton, BorderLayout.LINE_END)
        }

        transcriptArea.lineWrap = true
        transcriptArea.wrapStyleWord = true
        transcriptArea.font = Font(Font.SANS_SERIF, Font.PLAIN, 19)
        transcriptArea.isOpaque = false
        transcriptArea.border = BorderFactory.createEmptyBorder(6, 20, 16, 20)
        transcriptArea.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, false)
        transcriptArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onTextEdited()
            override fun removeUpdate(e: DocumentEvent) = onTextEdited()
            override fun changedUpdate(e: DocumentEvent) = onTextEdited()
        })

        transcriptScroll.isOpaque = false
        transcriptScroll.viewport.isOpaque = false
        transcriptScroll.border = BorderFactory.createEmptyBorder()

        fileProgress.isVisible = false
        fileProgress.preferredSize = Dimension(10, 6)

        return RoundedPanel(BorderLayout(), arc = 32).apply {
            add(header, BorderLayout.NORTH)
            add(transcriptScroll, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                border = BorderFactory.createEmptyBorder(0, 20, 16, 20)
                add(fileProgress)
            }, BorderLayout.SOUTH)
        }
    }

    private fun buildControls(): JComponent {
        listOf(saveButton, copyButton).forEach { button ->
            button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT)
            button.putClientProperty(FlatClientProperties.STYLE, "arc: 999; borderWidth: 0; focusWidth: 0")
            button.preferredSize = Dimension(56, 56)
        }
        saveButton.addActionListener { exportService.exportTranscript(frame, currentText()) }
        copyButton.addActionListener { copyText() }

        micHint.font = micHint.font.deriveFont(Font.BOLD, 13f)

        val row = JPanel(GridBagLayout()).apply {
            isOpaque = false
            val gbc = GridBagConstraints().apply { gridy = 0; weightx = 1.0 }
            gbc.gridx = 0; add(saveButton, gbc)
            gbc.gridx = 1; gbc.weightx = 0.0; add(micButton, gbc)
            gbc.gridx = 2; gbc.weightx = 1.0; add(copyButton, gbc)
        }

        injectCheck.addActionListener { onInjectToggled() }
        injectCheck.isSelected = prefs.getBoolean("inject", Platform.isWindows)
        hotkeyLabel.font = hotkeyLabel.font.deriveFont(11.5f)
        shortcutHelpButton.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS)
        shortcutHelpButton.isVisible = !Platform.isWindows
        shortcutHelpButton.addActionListener { showShortcutHelp() }

        val footer = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(injectCheck, BorderLayout.LINE_START)
            add(JPanel(FlowLayout(FlowLayout.TRAILING, 4, 0)).apply {
                isOpaque = false
                add(hotkeyLabel)
                add(shortcutHelpButton)
            }, BorderLayout.LINE_END)
        }

        return JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(4, 0, 0, 0)
            row.alignmentX = Component.CENTER_ALIGNMENT
            micHint.alignmentX = Component.CENTER_ALIGNMENT
            footer.alignmentX = Component.CENTER_ALIGNMENT
            add(row)
            add(micHint)
            add(Box.createVerticalStrut(12))
            add(footer)
        }
    }

    // endregion

    // region Durum ve metinler

    private fun refreshTexts() {
        val t = DesktopI18n::t
        taglineLabel.text = t("app.tagline")
        languageButton.text = ModelLibrary.current().displayName
        languageButton.icon = BejeTheme.icon("translate", 18) { BejeTheme.activeOutline }
        menuButton.icon = BejeTheme.icon("more", 22) { BejeTheme.muted }
        menuButton.toolTipText = t("action.menu")
        transcriptTitle.text = t("label.transcript")
        clearButton.icon = BejeTheme.icon("delete", 20) { BejeTheme.muted }
        clearButton.toolTipText = t("action.clear")
        saveButton.icon = BejeTheme.icon("save", 24) { BejeTheme.onTonal }
        saveButton.toolTipText = t("action.save")
        copyButton.icon = BejeTheme.icon("copy", 22) { BejeTheme.onTonal }
        copyButton.toolTipText = t("action.copy")
        listOf(saveButton, copyButton).forEach { it.background = BejeTheme.tonal }
        injectCheck.text = t("action.inject")
        injectCheck.toolTipText = t("action.inject_tip")
        hotkeyLabel.text = if (Platform.isWindows) t("hint.hotkey_windows") else t("hint.hotkey_other")
        shortcutHelpButton.text = t("action.shortcut_help")
        statusAction.text = t("action.undo")
        transcriptArea.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, t("hint.transcript"))

        listOf(taglineLabel, wordCountLabel, hotkeyLabel, statusLabel, micHint).forEach { it.foreground = BejeTheme.muted }
        listOf(titleLabel, transcriptTitle).forEach { it.foreground = BejeTheme.onSurface }
        transcriptArea.foreground = BejeTheme.onSurface
        transcriptArea.caretColor = BejeTheme.onSurface

        frame.applyComponentOrientation(DesktopI18n.orientation())
        val alignX = if (DesktopI18n.language().rtl) Component.RIGHT_ALIGNMENT else Component.LEFT_ALIGNMENT
        listOf(titleLabel, taglineLabel, transcriptTitle, wordCountLabel).forEach { it.alignmentX = alignX }
        applyTranscriptOrientation()
        trayService.refreshTexts(listening)
        updateUi()
        frame.revalidate()
        frame.repaint()
    }

    private fun applyTranscriptOrientation() {
        val rtl = ModelLibrary.current() != SpeechLanguage.TURKISH
        transcriptArea.componentOrientation =
            if (rtl) java.awt.ComponentOrientation.RIGHT_TO_LEFT else java.awt.ComponentOrientation.LEFT_TO_RIGHT
    }

    private fun updateUi() {
        val t = DesktopI18n::t
        val busy = listening || transcribingFile
        val baseStatus = when {
            listening -> t("status.listening")
            transcribingFile -> t("status.transcribing").format(fileProgressPercent)
            modelState == ModelState.LOADING -> t("status.loading")
            modelState == ModelState.READY -> t("status.ready")
            modelState == ModelState.FAILED -> t("status.model_failed").format(modelError)
            else -> t("status.no_model")
        }
        if (flashTimer?.isRunning != true) {
            statusLabel.text = baseStatus
            statusLabel.foreground = if (listening) BejeTheme.brandRed else BejeTheme.muted
            statusAction.isVisible = false
        }

        micButton.active = busy
        micButton.dimmed = modelState != ModelState.READY && !busy
        micButton.isEnabled = modelState != ModelState.LOADING || busy
        micButton.toolTipText = if (busy) t("action.stop") else t("action.start")
        micHint.text = when {
            transcribingFile -> t("mic.hint_file")
            listening -> t("mic.hint_listening")
            modelState == ModelState.LOADING -> t("mic.hint_loading")
            modelState != ModelState.READY -> t("mic.hint_no_model")
            else -> t("mic.hint_idle")
        }
        fileProgress.isVisible = transcribingFile
        fileProgress.value = fileProgressPercent
        transcriptArea.isEditable = !busy
        languageButton.text = ModelLibrary.current().displayName
        trayService.setListening(listening)
        updateTextActions()
    }

    private fun updateTextActions() {
        val text = currentText()
        val hasText = text.isNotBlank()
        saveButton.isEnabled = hasText
        copyButton.isEnabled = hasText
        clearButton.isEnabled = hasText && !listening && !transcribingFile
        val words = if (hasText) text.trim().split(Regex("\\s+")).size else 0
        wordCountLabel.text = DesktopI18n.words(words)
    }

    /** Geçici bir mesaj gösterir; isteğe bağlı bir eylem düğmesiyle (ör. Geri al). */
    private fun flash(message: String, action: (() -> Unit)? = null) {
        ui {
            flashTimer?.stop()
            statusLabel.text = message
            statusLabel.foreground = BejeTheme.onSurface
            statusAction.actionListeners.forEach(statusAction::removeActionListener)
            statusAction.isVisible = action != null
            if (action != null) {
                statusAction.addActionListener {
                    action()
                    flashTimer?.stop()
                    updateUi()
                }
            }
            flashTimer = Timer(5000) { updateUi() }.apply { isRepeats = false; start() }
        }
    }

    private fun showError(message: String) {
        updateUi()
        JOptionPane.showMessageDialog(frame, message, OratoMetadata.APP_NAME, JOptionPane.ERROR_MESSAGE)
    }

    // endregion

    // region Model

    private fun chooseInitialLanguage() {
        val current = ModelLibrary.current()
        if (ModelLibrary.locate(current) != null) return
        SpeechLanguage.entries.firstOrNull { ModelLibrary.locate(it) != null }?.let(ModelLibrary::setCurrent)
    }

    private fun loadCurrentModel() {
        val language = ModelLibrary.current()
        val located = ModelLibrary.locate(language)
        if (located == null) {
            recognizer.unloadModel()
            modelState = ModelState.MISSING
        } else {
            modelState = ModelState.LOADING
            recognizer.loadModel(language, located.path)
        }
        applyTranscriptOrientation()
        updateUi()
        languagePacksDialog?.refresh()
    }

    fun selectLanguage(language: SpeechLanguage) {
        if (listening) recognizer.stopListening()
        fileCancelled = true
        ModelLibrary.setCurrent(language)
        loadCurrentModel()
    }

    private fun showLanguagePacks() {
        languagePacksDialog?.let { it.toFront(); return }
        val dialog = LanguagePacksDialog(
            owner = frame,
            isActive = { it == ModelLibrary.current() && modelState != ModelState.MISSING },
            onUse = { selectLanguage(it) },
            onChanged = { language -> if (language == ModelLibrary.current()) loadCurrentModel() }
        )
        languagePacksDialog = dialog
        dialog.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent?) { languagePacksDialog = null }
        })
        dialog.isVisible = true
    }

    private fun showLanguageMenu() {
        val menu = JPopupMenu()
        SpeechLanguage.entries.forEach { language ->
            val found = ModelLibrary.locate(language) != null
            val item = JRadioButtonMenuItem(language.displayName, language == ModelLibrary.current())
            item.isEnabled = found
            if (!found) item.text = "${language.displayName}  ·  ${DesktopI18n.t("pack.missing")}"
            item.addActionListener { selectLanguage(language) }
            menu.add(item)
        }
        menu.addSeparator()
        menu.add(JMenuItem(DesktopI18n.t("action.language_packs"), BejeTheme.icon("download", 16) { BejeTheme.muted }).apply {
            addActionListener { showLanguagePacks() }
        })
        menu.applyComponentOrientation(DesktopI18n.orientation())
        menu.show(languageButton, 0, languageButton.height + 4)
    }

    // endregion

    // region Dikte

    private fun onMicClicked() {
        when {
            transcribingFile -> fileCancelled = true
            listening -> recognizer.stopListening()
            modelState == ModelState.READY -> {
                finalTranscript = transcriptArea.text.trim()
                partialText = ""
                recognizer.startListening()
            }
            modelState != ModelState.LOADING -> showLanguagePacks()
        }
    }

    private fun onListeningChanged(value: Boolean) {
        listening = value
        if (!value) {
            micButton.setLevel(0f)
            partialText = ""
            showTranscript()
        }
        updateUi()
    }

    private fun appendFinal(raw: String, inject: Boolean) {
        val chunk = formatter.formatFinalChunk(finalTranscript, raw)
        if (chunk.isBlank()) return
        val textToAdd = if (finalTranscript.isBlank()) chunk.trimStart() else chunk
        finalTranscript += textToAdd
        partialText = ""
        showTranscript()
        if (inject && injectCheck.isSelected && !frame.isFocused) {
            thread(name = "beje-inject", isDaemon = true) { textInjector.inject(textToAdd) }
        }
    }

    private fun showTranscript() {
        val text = if (partialText.isBlank()) finalTranscript else {
            listOf(finalTranscript, partialText).filter { it.isNotBlank() }.joinToString(" ")
        }
        suppressSync = true
        transcriptArea.text = text
        suppressSync = false
        transcriptArea.caretPosition = transcriptArea.document.length
        updateTextActions()
    }

    private fun onTextEdited() {
        if (!suppressSync && !listening && !transcribingFile) {
            finalTranscript = transcriptArea.text
        }
        SwingUtilities.invokeLater { updateTextActions() }
    }

    private fun currentText(): String = transcriptArea.text.trim()

    private fun copyText() {
        val text = currentText()
        if (text.isBlank()) return
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        flash(DesktopI18n.t("status.copied"))
    }

    private fun clearWithUndo() {
        val previous = transcriptArea.text
        if (previous.isBlank()) return
        finalTranscript = ""
        partialText = ""
        showTranscript()
        flash(DesktopI18n.t("status.cleared")) {
            finalTranscript = previous
            showTranscript()
        }
    }

    private fun onInjectToggled() {
        if (injectCheck.isSelected && !textInjector.isSupported) {
            injectCheck.isSelected = false
            JOptionPane.showMessageDialog(frame, DesktopI18n.t("error.inject_unavailable"), OratoMetadata.APP_NAME, JOptionPane.INFORMATION_MESSAGE)
        } else if (injectCheck.isSelected && Platform.isMac) {
            JOptionPane.showMessageDialog(frame, DesktopI18n.t("info.mac_accessibility"), OratoMetadata.APP_NAME, JOptionPane.INFORMATION_MESSAGE)
        }
        prefs.putBoolean("inject", injectCheck.isSelected)
    }

    // endregion

    // region Ses dosyası

    private fun chooseAudioFile() {
        if (modelState != ModelState.READY) {
            showLanguagePacks()
            return
        }
        val chooser = JFileChooser(prefs.get("audio_dir", System.getProperty("user.home"))).apply {
            dialogTitle = DesktopI18n.t("dialog.audio_file")
            fileFilter = FileNameExtensionFilter("Audio", "wav", "mp3", "m4a", "ogg", "opus", "flac", "aac", "wma", "webm", "aiff", "au")
        }
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return
        prefs.put("audio_dir", chooser.selectedFile.parent ?: "")
        transcribeFile(chooser.selectedFile)
    }

    private fun transcribeFile(file: File) {
        if (listening) recognizer.stopListening()
        finalTranscript = transcriptArea.text.trim()
        transcribingFile = true
        fileCancelled = false
        fileProgressPercent = 0
        micButton.setLevel(0.25f)
        updateUi()
        thread(name = "beje-file", isDaemon = true) {
            val result = runCatching {
                recognizer.transcribeFile(
                    file,
                    onSegment = { segment -> ui { appendFinal(segment, inject = false) } },
                    onProgress = { progress -> ui { fileProgressPercent = progress; updateUi() } },
                    isCancelled = { fileCancelled }
                )
            }
            ui {
                val cancelled = fileCancelled
                transcribingFile = false
                fileCancelled = false
                micButton.setLevel(0f)
                updateUi()
                result.onSuccess {
                    flash(DesktopI18n.t(if (cancelled) "status.file_cancelled" else "status.file_done"))
                }.onFailure {
                    StartupDiagnostics.logFailure("file.transcribe", it)
                    showError(DesktopI18n.t("error.audio_file"))
                }
            }
        }
    }

    // endregion

    // region Menü ve diyaloglar

    private fun showMainMenu() {
        val t = DesktopI18n::t
        val menu = JPopupMenu()
        menu.add(JMenuItem(t("action.language_packs"), BejeTheme.icon("download", 16) { BejeTheme.muted }).apply {
            addActionListener { showLanguagePacks() }
        })
        menu.add(JMenuItem(t("action.transcribe_file"), BejeTheme.icon("audio_file", 16) { BejeTheme.muted }).apply {
            addActionListener { chooseAudioFile() }
        })
        menu.addSeparator()

        val languageMenu = JMenu(t("action.ui_language"))
        val languageGroup = ButtonGroup()
        DesktopLanguage.entries.forEach { language ->
            val item = JRadioButtonMenuItem(language.displayName, language == DesktopI18n.language())
            languageGroup.add(item)
            item.addActionListener {
                DesktopI18n.setLanguage(language)
                refreshTexts()
            }
            languageMenu.add(item)
        }
        menu.add(languageMenu)

        val themeMenu = JMenu(t("action.theme"))
        val themeGroup = ButtonGroup()
        BejeTheme.Mode.entries.forEach { mode ->
            val item = JRadioButtonMenuItem(t(mode.key), mode == BejeTheme.mode())
            themeGroup.add(item)
            item.addActionListener {
                BejeTheme.setMode(mode)
                refreshTexts()
            }
            themeMenu.add(item)
        }
        menu.add(themeMenu)

        if (DesktopIntegration.isAvailable) {
            val integrated = DesktopIntegration.isIntegrated()
            menu.add(JMenuItem(t(if (integrated) "action.unintegrate" else "action.integrate")).apply {
                addActionListener {
                    if (integrated) DesktopIntegration.remove() else integrateIntoMenu()
                }
            })
        }
        if (startupManager.isSupported) {
            menu.add(JCheckBoxMenuItem(t("action.startup"), startupManager.isEnabled()).apply {
                addActionListener { setStartup(isSelected) }
            })
        }
        menu.addSeparator()
        menu.add(JMenuItem(t("action.about"), BejeTheme.icon("info", 16) { BejeTheme.muted }).apply {
            addActionListener { showAbout() }
        })
        menu.applyComponentOrientation(DesktopI18n.orientation())
        menu.show(menuButton, menuButton.width - menu.preferredSize.width, menuButton.height + 4)
    }

    private fun askIntegration() {
        val options = arrayOf(DesktopI18n.t("action.integrate"), DesktopI18n.t("action.not_now"))
        val choice = JOptionPane.showOptionDialog(
            frame, DesktopI18n.t("msg.integrate_ask"), OratoMetadata.APP_NAME,
            JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, ImageIcon(loadLogo(48).image), options, options[0]
        )
        if (choice == 0) integrateIntoMenu()
    }

    private fun integrateIntoMenu() {
        if (DesktopIntegration.integrate()) {
            flash(DesktopI18n.t("msg.integrated"))
        } else {
            showError(DesktopI18n.t("status.error"))
        }
    }

    private fun setStartup(enabled: Boolean) {
        val ok = startupManager.setEnabled(enabled)
        trayService.setStartupEnabled(startupManager.isEnabled())
        if (!ok) showError(DesktopI18n.t("status.error"))
    }

    private fun showShortcutHelp() {
        val launcher = Platform.launcherPath()?.toString() ?: "beje"
        JOptionPane.showMessageDialog(
            frame,
            DesktopI18n.t("hint.hotkey_command").format("\"$launcher\""),
            DesktopI18n.t("action.shortcut_help"),
            JOptionPane.INFORMATION_MESSAGE
        )
    }

    private fun showAbout() {
        JOptionPane.showMessageDialog(
            frame,
            DesktopI18n.t("about.text").format(OratoMetadata.APP_VERSION),
            DesktopI18n.t("about.title"),
            JOptionPane.INFORMATION_MESSAGE,
            ImageIcon(loadLogo(64).image)
        )
    }

    // endregion

    // region Pencere

    private fun saveSnapshot(path: String) {
        System.getProperty("beje.snapshot.text")?.let { finalTranscript = it; showTranscript() }
        val target: java.awt.Container = if (System.getProperty("beje.snapshot.packs") != null) {
            LanguagePacksDialog(frame, { it == ModelLibrary.current() && modelState == ModelState.READY }, {}, {}).also {
                it.addNotify(); it.validate()
            }.contentPane
        } else {
            frame.addNotify(); frame.validate()
            frame.contentPane
        }
        val image = java.awt.image.BufferedImage(target.width, target.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        target.printAll(g)
        g.dispose()
        ImageIO.write(image, "png", File(path))
        shutdown()
    }

    private fun onCloseRequested() {
        prefs.putInt("window_width", frame.width)
        prefs.putInt("window_height", frame.height)
        if (!trayInstalled) {
            shutdown()
            return
        }
        if (!prefs.getBoolean("close_hint_shown", false)) {
            prefs.putBoolean("close_hint_shown", true)
            JOptionPane.showMessageDialog(frame, DesktopI18n.t("msg.close_to_tray"), DesktopI18n.t("dialog.close_behavior"), JOptionPane.INFORMATION_MESSAGE)
        }
        frame.isVisible = false
        trayService.setWindowVisible(false)
    }

    private fun showWindow() {
        frame.isVisible = true
        frame.extendedState = JFrame.NORMAL
        frame.toFront()
        frame.requestFocus()
        trayService.setWindowVisible(true)
    }

    private fun shutdown() {
        fileCancelled = true
        hotkeyService.close()
        recognizer.close()
        trayService.close()
        AppInstanceGuard.close()
        frame.dispose()
        System.exit(0)
    }

    private fun loadAppIcons(): List<Image> {
        val full = runCatching {
            BejeWindow::class.java.getResourceAsStream("/icons/beje_icon_full.png")?.use(ImageIO::read)
        }.getOrNull() ?: return listOf(OratoBranding.createAppIcon(128))
        return listOf(16, 24, 32, 48, 64, 128, 256).map { size -> full.getScaledInstance(size, size, Image.SCALE_SMOOTH) }
    }

    private fun loadLogo(size: Int): ImageIcon {
        val image = runCatching {
            BejeWindow::class.java.getResourceAsStream("/icons/beje_round.png")?.use(ImageIO::read)
        }.getOrNull() ?: return ImageIcon(OratoBranding.createAppIcon(size))
        return ImageIcon(image.getScaledInstance(size, size, Image.SCALE_SMOOTH))
    }

    // endregion
}

internal fun ui(block: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
}
