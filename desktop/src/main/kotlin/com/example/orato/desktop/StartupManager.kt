package com.example.orato.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Oturum açılışında Bêje'yi arka planda başlatma. */
interface StartupManager {
    val isSupported: Boolean
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean): Boolean

    companion object {
        fun create(): StartupManager = when (Platform.os) {
            OsFamily.WINDOWS -> WindowsStartupManager()
            OsFamily.MAC -> MacStartupManager()
            OsFamily.LINUX -> LinuxStartupManager()
        }
    }
}

/** Linux: ~/.config/autostart/beje.desktop */
class LinuxStartupManager : StartupManager {
    private val file: Path = (System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of)
        ?: Path.of(System.getProperty("user.home"), ".config")).resolve("autostart/beje.desktop")

    override val isSupported: Boolean get() = Platform.launcherPath() != null

    override fun isEnabled(): Boolean = file.exists()

    override fun setEnabled(enabled: Boolean): Boolean = runCatching {
        if (!enabled) {
            file.deleteIfExists()
            return true
        }
        val launcher = Platform.launcherPath() ?: return false
        file.parent.createDirectories()
        file.writeText(
            """
            [Desktop Entry]
            Type=Application
            Name=Bêje
            Exec="$launcher" --background
            Icon=beje
            X-GNOME-Autostart-enabled=true
            """.trimIndent() + "\n"
        )
        true
    }.getOrDefault(false)
}

/** macOS: ~/Library/LaunchAgents/org.beje.app.plist */
class MacStartupManager : StartupManager {
    private val file: Path = Path.of(System.getProperty("user.home"), "Library/LaunchAgents/org.beje.app.plist")

    private fun bundlePath(): Path? = System.getProperty("beje.bundle")?.takeIf { it.isNotBlank() }
        ?.let(Path::of)?.takeIf(Files::exists)

    override val isSupported: Boolean get() = bundlePath() != null

    override fun isEnabled(): Boolean = file.exists() &&
        runCatching { file.readText().contains(bundlePath().toString()) }.getOrDefault(false)

    override fun setEnabled(enabled: Boolean): Boolean = runCatching {
        if (!enabled) {
            file.deleteIfExists()
            return true
        }
        val bundle = bundlePath() ?: return false
        file.parent.createDirectories()
        file.writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
              <key>Label</key><string>org.beje.app</string>
              <key>ProgramArguments</key>
              <array>
                <string>/usr/bin/open</string>
                <string>-g</string>
                <string>$bundle</string>
                <string>--args</string>
                <string>--background</string>
              </array>
              <key>RunAtLoad</key><true/>
            </dict>
            </plist>
            """.trimIndent() + "\n"
        )
        true
    }.getOrDefault(false)
}
