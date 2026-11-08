package com.example.orato.desktop

import java.awt.Color
import java.awt.Image
import java.awt.CheckboxMenuItem
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon

class SystemTrayService(
    private val onShowWindow: () -> Unit,
    private val onToggleListening: () -> Unit,
    private val onToggleStartup: () -> Unit,
    private val onExit: () -> Unit
) : AutoCloseable {

    private var trayIcon: TrayIcon? = null
    private var openItem: MenuItem? = null
    private var toggleItem: MenuItem? = null
    private var startupItem: CheckboxMenuItem? = null
    private var exitItem: MenuItem? = null

    fun install(): Boolean {
        if (!SystemTray.isSupported() || trayIcon != null) {
            return false
        }

        return runCatching {
            val popup = PopupMenu()
            openItem = MenuItem(DesktopI18n.t("tray.open")).apply {
                addActionListener { onShowWindow() }
            }
            toggleItem = MenuItem(DesktopI18n.t("tray.start")).apply {
                addActionListener { onToggleListening() }
            }
            startupItem = CheckboxMenuItem(DesktopI18n.t("tray.startup"), false).apply {
                addItemListener { onToggleStartup() }
            }
            exitItem = MenuItem(DesktopI18n.t("tray.exit")).apply {
                addActionListener { onExit() }
            }

            openItem?.let(popup::add)
            toggleItem?.let(popup::add)
            startupItem?.let(popup::add)
            popup.addSeparator()
            exitItem?.let(popup::add)

            trayIcon = TrayIcon(createTrayImage(), OratoMetadata.APP_NAME, popup).apply {
                isImageAutoSize = true
                addActionListener { onShowWindow() }
            }

            SystemTray.getSystemTray().add(trayIcon)
            true
        }.getOrElse { exception ->
            StartupDiagnostics.logFailure("tray.install", exception)
            trayIcon = null
            false
        }
    }

    fun setListening(listening: Boolean) {
        toggleItem?.label = if (listening) DesktopI18n.t("tray.stop") else DesktopI18n.t("tray.start")
    }

    fun setWindowVisible(visible: Boolean) {
        trayIcon?.toolTip = if (visible) {
            OratoMetadata.APP_NAME
        } else {
            DesktopI18n.t("tray.bg")
        }
    }

    fun setStartupEnabled(enabled: Boolean) {
        startupItem?.state = enabled
    }

    fun refreshTexts(listening: Boolean) {
        openItem?.label = DesktopI18n.t("tray.open")
        startupItem?.label = DesktopI18n.t("tray.startup")
        exitItem?.label = DesktopI18n.t("tray.exit")
        setListening(listening)
    }

    fun showMessage(title: String, message: String) {
        trayIcon?.displayMessage(title, message, TrayIcon.MessageType.INFO)
    }

    override fun close() {
        trayIcon?.let { SystemTray.getSystemTray().remove(it) }
        trayIcon = null
    }

    private fun createTrayImage(): Image {
        return OratoBranding.createAppIcon(64)
    }
}
