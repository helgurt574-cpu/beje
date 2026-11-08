package com.example.orato.desktop

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg

/** Windows: HKCU\...\Run kaydı (yönetici izni gerekmez). */
class WindowsStartupManager : StartupManager {
    override val isSupported: Boolean get() = Platform.isWindows && Platform.launcherPath() != null

    override fun isEnabled(): Boolean {
        if (!Platform.isWindows) return false
        return runCatching {
            Advapi32Util.registryValueExists(RUN_KEY_ROOT, RUN_KEY_PATH, VALUE_NAME) &&
                Advapi32Util.registryGetStringValue(RUN_KEY_ROOT, RUN_KEY_PATH, VALUE_NAME) == launchCommand()
        }.getOrDefault(false)
    }

    override fun setEnabled(enabled: Boolean): Boolean {
        if (!Platform.isWindows) return false
        return runCatching {
            if (enabled) {
                val command = launchCommand() ?: return false
                Advapi32Util.registrySetStringValue(RUN_KEY_ROOT, RUN_KEY_PATH, VALUE_NAME, command)
            } else if (Advapi32Util.registryValueExists(RUN_KEY_ROOT, RUN_KEY_PATH, VALUE_NAME)) {
                Advapi32Util.registryDeleteValue(RUN_KEY_ROOT, RUN_KEY_PATH, VALUE_NAME)
            }
            true
        }.getOrDefault(false)
    }

    private fun launchCommand(): String? =
        Platform.launcherPath()?.let { "\"${it.toAbsolutePath()}\" --background" }

    private companion object {
        val RUN_KEY_ROOT: WinReg.HKEY = WinReg.HKEY_CURRENT_USER
        const val RUN_KEY_PATH = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        const val VALUE_NAME = "Beje"
    }
}
