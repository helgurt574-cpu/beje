package com.example.orato.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.WString
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

enum class OsFamily { WINDOWS, MAC, LINUX }

/** İşletim sistemine göre değişen yollar ve bilgiler. */
object Platform {
    val os: OsFamily = System.getProperty("os.name").lowercase(Locale.ROOT).let { name ->
        when {
            name.contains("win") -> OsFamily.WINDOWS
            name.contains("mac") || name.contains("darwin") -> OsFamily.MAC
            else -> OsFamily.LINUX
        }
    }

    val isWindows get() = os == OsFamily.WINDOWS
    val isMac get() = os == OsFamily.MAC
    val isLinux get() = os == OsFamily.LINUX

    /** Kullanıcı verileri (kilit dosyası, günlükler). */
    fun dataDir(): Path {
        val home = Path.of(System.getProperty("user.home"))
        return when (os) {
            OsFamily.WINDOWS -> System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                ?.let { Path.of(it, "Beje") } ?: home.resolve(".beje")
            OsFamily.MAC -> home.resolve("Library/Application Support/Beje")
            OsFamily.LINUX -> (System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
                ?.let(Path::of) ?: home.resolve(".local/share")).resolve("beje")
        }
    }

    /** Uygulamanın kurulu olduğu klasör (başlatıcı `beje.home` ile bildirir). */
    fun appHome(): Path? {
        System.getProperty("beje.home")?.takeIf { it.isNotBlank() }?.let { return Path.of(it).toAbsolutePath().normalize() }
        val codeSource = runCatching {
            Path.of(Platform::class.java.protectionDomain.codeSource.location.toURI()).toAbsolutePath().normalize()
        }.getOrNull() ?: return null
        // .../app/lib/desktop.jar -> kurulum kökü
        return if (codeSource.fileName?.toString()?.endsWith(".jar", true) == true) {
            codeSource.parent?.parent?.parent ?: codeSource.parent
        } else {
            codeSource
        }
    }

    /** Başlatıcının yolu (sistemle başlatma için). */
    fun launcherPath(): Path? =
        System.getProperty("beje.launcher")?.takeIf { it.isNotBlank() }?.let(Path::of)?.takeIf(Files::exists)

    fun commandExists(command: String): Boolean {
        val path = System.getenv("PATH") ?: return false
        return path.split(java.io.File.pathSeparator).any { dir ->
            dir.isNotBlank() && Files.isExecutable(Path.of(dir, command))
        }
    }

    /**
     * Yerel (C++) kütüphaneye verilecek dosya yolu. Windows'ta Vosk yolları ANSI olarak açar;
     * yolda Türkçe/Arapça karakter varsa (ör. kullanıcı adında) kısa 8.3 yolunu kullanır.
     */
    fun nativePath(path: Path): String {
        val text = path.toAbsolutePath().toString()
        if (!isWindows || text.all { it.code < 128 }) return text
        return runCatching {
            val buffer = CharArray(4096)
            val length = Kernel32Paths.INSTANCE.GetShortPathNameW(WString(text), buffer, buffer.size)
            if (length in 1 until buffer.size) String(buffer, 0, length) else text
        }.getOrDefault(text)
    }

    @Suppress("FunctionName")
    private interface Kernel32Paths : Library {
        fun GetShortPathNameW(longPath: WString, shortPath: CharArray, bufferLength: Int): Int

        companion object {
            val INSTANCE: Kernel32Paths = Native.load("kernel32", Kernel32Paths::class.java)
        }
    }

    val isWayland: Boolean
        get() = isLinux && (System.getenv("WAYLAND_DISPLAY")?.isNotBlank() == true ||
            System.getenv("XDG_SESSION_TYPE").equals("wayland", ignoreCase = true))
}
