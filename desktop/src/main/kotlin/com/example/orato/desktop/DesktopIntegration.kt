package com.example.orato.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Linux: Bêje'yi (AppImage ya da taşınabilir klasör) uygulama menüsüne ekler/kaldırır.
 * Yalnızca kullanıcının kendi klasörlerine yazar; yönetici izni gerekmez.
 */
object DesktopIntegration {
    private val dataHome: Path = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of)
        ?: Path.of(System.getProperty("user.home"), ".local/share")
    private val desktopFile: Path = dataHome.resolve("applications/beje.desktop")
    private val iconFile: Path = dataHome.resolve("icons/hicolor/256x256/apps/beje.png")

    /** AppImage içinden çalışıyorsa AppImage dosyasının yolu. */
    val appImage: Path? = System.getenv("APPIMAGE")?.takeIf { it.isNotBlank() }?.let(Path::of)

    private fun target(): Path? = appImage ?: Platform.launcherPath()

    /** .deb ile /opt altına kurulduysa menü girdisini paket zaten ekler. */
    val isAvailable: Boolean
        get() = Platform.isLinux && target() != null &&
            (appImage != null || Platform.launcherPath()?.startsWith("/opt/") != true)

    fun isIntegrated(): Boolean {
        val target = target() ?: return false
        return desktopFile.exists() && runCatching { desktopFile.readText().contains(target.toString()) }.getOrDefault(false)
    }

    fun integrate(): Boolean = runCatching {
        val target = target() ?: return false
        iconFile.parent.createDirectories()
        DesktopIntegration::class.java.getResourceAsStream("/icons/beje_round.png")?.use {
            Files.copy(it, iconFile, StandardCopyOption.REPLACE_EXISTING)
        }
        desktopFile.parent.createDirectories()
        desktopFile.writeText(
            """
            [Desktop Entry]
            Type=Application
            Name=Bêje
            GenericName=Dictation
            Comment=Çevrimdışı dikte / Offline dictation
            Exec="$target" %F
            Icon=beje
            Terminal=false
            Categories=Utility;Office;Accessibility;
            Keywords=dikte;dictation;speech;konuşma;
            StartupWMClass=com-example-orato-desktop-OratoAppKt
            """.trimIndent() + "\n"
        )
        runCommand(listOf("update-desktop-database", desktopFile.parent.toString()))
        true
    }.getOrDefault(false)

    fun remove(): Boolean = runCatching {
        desktopFile.deleteIfExists()
        iconFile.deleteIfExists()
        runCommand(listOf("update-desktop-database", desktopFile.parent.toString()))
        true
    }.getOrDefault(false)
}
