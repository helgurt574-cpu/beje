package com.example.orato.desktop

import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.io.Closeable
import kotlin.concurrent.thread

class GlobalHotkeyService(
    private val onHotkeyPressed: () -> Unit,
    private val onRegistrationFailed: (() -> Unit)? = null
) : Closeable {

    @Volatile
    private var running = false

    @Volatile
    private var hotkeyThread: Thread? = null

    @Volatile
    private var messageThreadId: Int = 0

    fun start() {
        if (!isWindows() || running) {
            return
        }

        running = true
        hotkeyThread = thread(name = "orato-hotkey", isDaemon = true) {
            messageThreadId = Kernel32Bridge.currentThreadId()
            val registeredIds = registerHotkeys()
            if (registeredIds.isEmpty()) {
                running = false
                onRegistrationFailed?.invoke()
                return@thread
            }

            try {
                val message = WinUser.MSG()
                while (running && User32.INSTANCE.GetMessage(message, null, 0, 0) > 0) {
                    if (message.message == WinUser.WM_HOTKEY && message.wParam.toInt() in registeredIds) {
                        onHotkeyPressed()
                    }
                }
            } finally {
                registeredIds.forEach { hotkeyId ->
                    User32.INSTANCE.UnregisterHotKey(null, hotkeyId)
                }
            }
        }
    }

    override fun close() {
        running = false
        if (messageThreadId != 0) {
            User32.INSTANCE.PostThreadMessage(
                messageThreadId,
                WinUser.WM_QUIT,
                WinDef.WPARAM(0),
                WinDef.LPARAM(0)
            )
        }
    }

    private fun isWindows(): Boolean {
        return System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    }

    private fun registerHotkeys(): Set<Int> {
        val registered = linkedSetOf<Int>()
        if (User32.INSTANCE.RegisterHotKey(null, HOTKEY_ID_F8, 0, VK_F8)) {
            registered += HOTKEY_ID_F8
        }
        if (User32.INSTANCE.RegisterHotKey(null, HOTKEY_ID_LEGACY, MOD_CONTROL or MOD_ALT, VK_SPACE)) {
            registered += HOTKEY_ID_LEGACY
        }
        return registered
    }

    private companion object {
        const val HOTKEY_ID_F8 = 0x0A77
        const val HOTKEY_ID_LEGACY = 0x0A78
        const val MOD_ALT = 0x0001
        const val MOD_CONTROL = 0x0002
        const val VK_SPACE = 0x20
        const val VK_F8 = 0x77
    }
}
