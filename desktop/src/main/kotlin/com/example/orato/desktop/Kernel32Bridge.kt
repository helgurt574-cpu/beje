package com.example.orato.desktop

import com.sun.jna.platform.win32.Kernel32

object Kernel32Bridge {
    fun currentThreadId(): Int {
        return Kernel32.INSTANCE.GetCurrentThreadId()
    }
}
