package com.example.orato.desktop

import com.formdev.flatlaf.FlatClientProperties
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Frame
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.KeyStroke

/** Dil paketlerini (modelleri) gösterir; etkinleştirme ve klasörden ekleme. */
class LanguagePacksDialog(
    private val owner: Frame,
    private val isActive: (SpeechLanguage) -> Boolean,
    private val onUse: (SpeechLanguage) -> Unit,
    private val onChanged: (SpeechLanguage) -> Unit
) : JDialog(owner, DesktopI18n.t("packs.title"), false) {

    private val list = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        val title = JLabel(DesktopI18n.t("packs.title")).apply {
            font = font.deriveFont(Font.BOLD, 20f)
            foreground = BejeTheme.onSurface
        }
        val subtitle = JLabel("<html><div style='width:430px'>${DesktopI18n.t("packs.subtitle")}</div></html>").apply {
            foreground = BejeTheme.muted
            border = BorderFactory.createEmptyBorder(6, 0, 14, 0)
        }
        val close = JButton(DesktopI18n.t("action.close")).apply { addActionListener { dispose() } }

        contentPane = JPanel(BorderLayout()).apply {
            background = BejeTheme.surface
            border = BorderFactory.createEmptyBorder(20, 22, 16, 22)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(title, BorderLayout.NORTH)
                add(subtitle, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(list, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.TRAILING, 0, 0)).apply {
                isOpaque = false
                border = BorderFactory.createEmptyBorder(14, 0, 0, 0)
                add(close)
            }, BorderLayout.SOUTH)
        }
        rootPane.registerKeyboardAction({ dispose() }, KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW)
        refresh()
        pack()
        minimumSize = Dimension(560, size.height)
        setLocationRelativeTo(owner)
    }

    fun refresh() {
        list.removeAll()
        SpeechLanguage.entries.forEach { language ->
            list.add(buildRow(language))
            list.add(Box.createVerticalStrut(10))
        }
        applyComponentOrientation(DesktopI18n.orientation())
        list.revalidate()
        list.repaint()
    }

    private fun buildRow(language: SpeechLanguage): JComponent {
        val t = DesktopI18n::t
        val located = ModelLibrary.locate(language)
        val active = located != null && isActive(language)

        val detail = buildString {
            append(t("pack.size").format(language.approxSizeMb))
            if (language.isLarge) append(" · ").append(t("pack.large"))
            append(" · ")
            append(
                when {
                    active -> t("pack.in_use")
                    located == null -> t("pack.missing")
                    located.source == ModelLibrary.Source.AUTO -> t("pack.ready_auto")
                    else -> t("pack.ready_manual")
                }
            )
        }
        val name = JLabel(language.displayName).apply {
            font = font.deriveFont(Font.BOLD, 15f)
            foreground = BejeTheme.onSurface
        }
        val detailLabel = JLabel(detail).apply {
            foreground = if (located == null) BejeTheme.muted else BejeTheme.success
            toolTipText = located?.path?.toString()
        }
        val alignX = if (DesktopI18n.language().rtl) RIGHT_ALIGNMENT else LEFT_ALIGNMENT
        name.alignmentX = alignX
        detailLabel.alignmentX = alignX
        val texts = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(name)
            add(detailLabel)
        }

        val buttons = JPanel(FlowLayout(FlowLayout.TRAILING, 6, 0)).apply { isOpaque = false }
        if (located != null) {
            buttons.add(JButton(if (active) t("action.active") else t("action.use")).apply {
                isEnabled = !active
                if (active) icon = BejeTheme.icon("check", 16) { BejeTheme.success }
                putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT)
                addActionListener {
                    onUse(language)
                    refresh()
                }
            })
        }
        buttons.add(JButton(t("action.choose_folder"), BejeTheme.icon("folder", 16) { BejeTheme.onTonal }).apply {
            putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT)
            background = BejeTheme.tonal
            foreground = BejeTheme.onTonal
            addActionListener { chooseFolder(language) }
        })
        if (ModelLibrary.hasManualPath(language)) {
            buttons.add(JButton(BejeTheme.icon("close", 16) { BejeTheme.muted }).apply {
                toolTipText = t("action.forget")
                putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON)
                addActionListener {
                    ModelLibrary.forget(language)
                    onChanged(language)
                    refresh()
                }
            })
        }

        return RoundedPanel(
            BorderLayout(14, 0),
            arc = 24,
            fill = { if (active) BejeTheme.card else BejeTheme.surface },
            outline = { if (active) BejeTheme.activeOutline else BejeTheme.cardBorder },
            outlineWidth = { if (active) 2f else 1f }
        ).apply {
            border = BorderFactory.createEmptyBorder(12, 14, 12, 10)
            add(CodeBadge(language.code), BorderLayout.LINE_START)
            add(texts, BorderLayout.CENTER)
            add(buttons, BorderLayout.LINE_END)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    private fun chooseFolder(language: SpeechLanguage) {
        val start = ModelLibrary.locate(language)?.path?.parent?.toFile()
            ?: Platform.appHome()?.toFile()
        val chooser = JFileChooser(start).apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = DesktopI18n.t("dialog.model_folder").format(language.displayName)
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val folder: Path = chooser.selectedFile.toPath()
        runCatching { ModelLibrary.importFolder(language, folder) }
            .onSuccess {
                onChanged(language)
                if (!isActive(language)) onUse(language)
                refresh()
            }
            .onFailure {
                JOptionPane.showMessageDialog(this, it.localizedMessage, OratoMetadata.APP_NAME, JOptionPane.WARNING_MESSAGE)
            }
    }
}
