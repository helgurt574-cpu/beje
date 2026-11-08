package com.example.orato.desktop

import com.sun.jna.platform.win32.BaseTSD
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser

class WindowsTextInjector : TextInjector {
    override val isSupported: Boolean get() = isWindows()

    override fun inject(text: String): Boolean {
        if (!isWindows() || text.isEmpty()) return false
        if (!canInjectToForegroundWindow()) return false
        sendUnicodeText(text)
        return true
    }

    fun replaceLastText(previousText: String, newText: String) {
        if (!isWindows()) return
        if (!canInjectToForegroundWindow()) return

        if (previousText == newText) return

        val sharedPrefixLength = previousText.commonPrefixWith(newText).length
        val previousTail = previousText.substring(sharedPrefixLength)
        val newTail = newText.substring(sharedPrefixLength)

        if (previousTail.isNotEmpty()) {
            val backspaceCount = previousTail.length
            val backspaceInputs = createInputArray(backspaceCount * 2)
            var index = 0
            repeat(backspaceCount) {
                configureVirtualKeyInput(backspaceInputs[index++], VK_BACK, 0)
                configureVirtualKeyInput(backspaceInputs[index++], VK_BACK, KEYEVENTF_KEYUP)
            }
            sendInputs(backspaceInputs)
        }

        if (newTail.isNotEmpty()) {
            sendUnicodeText(newTail)
        }
    }

    private fun sendUnicodeText(text: String) {
        val inputs = createInputArray(text.length * 2)
        var index = 0
        for (i in text.indices) {
            val char = text[i]
            configureUnicodeInput(inputs[index++], char, 0)
            configureUnicodeInput(inputs[index++], char, KEYEVENTF_KEYUP)
        }
        sendInputs(inputs)
    }

    private fun sendInputs(inputs: Array<WinUser.INPUT>) {
        if (inputs.isEmpty()) return
        val sent = User32.INSTANCE.SendInput(WinDef.DWORD(inputs.size.toLong()), inputs, inputs[0].size())
        if (sent.toInt() != inputs.size) {
            System.err.println("Failed to send all inputs via SendInput.")
        }
    }

    private fun configureVirtualKeyInput(input: WinUser.INPUT, virtualKey: Int, flags: Int) {
        input.type = WinDef.DWORD(WinUser.INPUT.INPUT_KEYBOARD.toLong())
        input.input.setType("ki")
        input.input.ki = WinUser.KEYBDINPUT().apply {
            wVk = WinDef.WORD(virtualKey.toLong())
            wScan = WinDef.WORD(0)
            dwFlags = WinDef.DWORD(flags.toLong())
            time = WinDef.DWORD(0)
            dwExtraInfo = BaseTSD.ULONG_PTR(0L)
        }
    }

    private fun configureUnicodeInput(input: WinUser.INPUT, char: Char, extraFlags: Int) {
        input.type = WinDef.DWORD(WinUser.INPUT.INPUT_KEYBOARD.toLong())
        input.input.setType("ki")
        input.input.ki = WinUser.KEYBDINPUT().apply {
            wVk = WinDef.WORD(0)
            wScan = WinDef.WORD(char.code.toLong())
            dwFlags = WinDef.DWORD((KEYEVENTF_UNICODE or extraFlags).toLong())
            time = WinDef.DWORD(0)
            dwExtraInfo = BaseTSD.ULONG_PTR(0L)
        }
    }

    private fun isWindows(): Boolean {
        return System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    }

    private fun canInjectToForegroundWindow(): Boolean {
        val foregroundWindow = User32.INSTANCE.GetForegroundWindow() ?: return false
        val processId = com.sun.jna.ptr.IntByReference()
        User32.INSTANCE.GetWindowThreadProcessId(foregroundWindow, processId)
        return processId.value != Kernel32.INSTANCE.GetCurrentProcessId()
    }

    @Suppress("UNCHECKED_CAST")
    private fun createInputArray(size: Int): Array<WinUser.INPUT> {
        return WinUser.INPUT().toArray(size) as Array<WinUser.INPUT>
    }

    private companion object {
        const val KEYEVENTF_KEYUP = 0x0002
        const val KEYEVENTF_UNICODE = 0x0004
        const val VK_BACK = 0x08
    }
}
