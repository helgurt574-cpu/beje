package com.example.orato.desktop

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.concurrent.TimeUnit

/** Tanınan metni o an odakta olan başka bir uygulamaya yazar. */
interface TextInjector {
    val isSupported: Boolean

    /** @return metin gönderildiyse true */
    fun inject(text: String): Boolean

    companion object {
        fun create(): TextInjector = when (Platform.os) {
            OsFamily.WINDOWS -> WindowsTextInjector()
            OsFamily.MAC -> MacTextInjector()
            OsFamily.LINUX -> LinuxTextInjector()
        }
    }
}

/**
 * Linux: X11'de `xdotool`, Wayland'de `wtype` ya da `ydotool` ile yazar.
 * Bu araçlar yoksa özellik kullanılamaz (metin yine panoya kopyalanabilir).
 */
class LinuxTextInjector : TextInjector {
    private val command: List<String>? = when {
        Platform.isWayland && Platform.commandExists("wtype") -> listOf("wtype", "--")
        Platform.isWayland && Platform.commandExists("ydotool") -> listOf("ydotool", "type", "--")
        !Platform.isWayland && Platform.commandExists("xdotool") ->
            listOf("xdotool", "type", "--clearmodifiers", "--delay", "4", "--")
        else -> null
    }

    override val isSupported: Boolean get() = command != null

    override fun inject(text: String): Boolean {
        val base = command ?: return false
        if (text.isEmpty()) return false
        return runCommand(base + text)
    }
}

/**
 * macOS: metni panoya koyup ⌘V gönderir. İlk kullanımda Sistem Ayarları > Gizlilik ve
 * Güvenlik > Erişilebilirlik altında Bêje'ye izin verilmesi gerekir.
 */
class MacTextInjector : TextInjector {
    override val isSupported: Boolean get() = true

    override fun inject(text: String): Boolean {
        if (text.isEmpty()) return false
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val previous = runCatching { clipboard.getContents(null) }.getOrNull()
        clipboard.setContents(StringSelection(text), null)
        val ok = runCommand(
            listOf("osascript", "-e", "tell application \"System Events\" to keystroke \"v\" using command down")
        )
        if (previous != null) {
            Thread.sleep(250)
            runCatching { clipboard.setContents(previous, null) }
        }
        return ok
    }
}

internal fun runCommand(command: List<String>): Boolean = runCatching {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    process.inputStream.readAllBytes()
    process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
}.getOrDefault(false)
