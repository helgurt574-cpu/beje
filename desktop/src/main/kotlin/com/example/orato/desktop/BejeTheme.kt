package com.example.orato.desktop

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.FlatLightLaf
import com.formdev.flatlaf.extras.FlatSVGIcon
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.awt.Color
import java.util.prefs.Preferences
import java.util.concurrent.TimeUnit
import javax.swing.UIManager

/** Android sürümüyle aynı marka renkleri (simgedeki kırmızı, güneş sarısı, yeşil). */
object BejeTheme {
    enum class Mode(val key: String) { SYSTEM("theme.system"), LIGHT("theme.light"), DARK("theme.dark") }

    private val prefs = Preferences.userRoot().node("beje")

    val brandRed = Color(0xE6, 0x39, 0x46)
    val brandRedDark = Color(0xB7, 0x21, 0x2F)
    val brandSun = Color(0xFF, 0xC8, 0x3D)

    var isDark = false
        private set

    fun mode(): Mode = Mode.entries.firstOrNull { it.name == prefs.get("theme", null) } ?: Mode.SYSTEM

    fun setMode(mode: Mode) {
        prefs.put("theme", mode.name)
        apply()
        FlatLaf.updateUI()
    }

    fun apply() {
        isDark = when (mode()) {
            Mode.LIGHT -> false
            Mode.DARK -> true
            Mode.SYSTEM -> systemPrefersDark()
        }
        val common = mapOf(
            "Component.arc" to "14",
            "Button.arc" to "999",
            "TextComponent.arc" to "14",
            "CheckBox.arc" to "6",
            "ProgressBar.arc" to "999",
            "ScrollBar.thumbArc" to "999",
            "ScrollBar.thumbInsets" to "2,2,2,2",
            "ScrollBar.width" to "10",
            "Component.focusWidth" to "1",
            "Button.innerFocusWidth" to "0",
            "PopupMenu.borderCornerRadius" to "12",
            "MenuItem.selectionArc" to "8",
            "TitlePane.unifiedBackground" to "true"
        )
        val palette = if (isDark) {
            mapOf("@background" to "#1A1110", "@foreground" to "#F1DEDC", "@accentColor" to "#FFB3AF")
        } else {
            mapOf("@background" to "#FFF8F6", "@foreground" to "#231918", "@accentColor" to "#B6202E")
        }
        FlatLaf.setGlobalExtraDefaults(common + palette)
        if (isDark) FlatDarkLaf.setup() else FlatLightLaf.setup()
    }

    // Yüzey renkleri (Android'deki Material 3 tonlarıyla aynı)
    val surface get() = if (isDark) Color(0x1A1110) else Color(0xFFF8F6)
    val card get() = if (isDark) Color(0x271D1C) else Color(0xFCEAE8)
    val cardBorder get() = if (isDark) Color(0x534341) else Color(0xEAD3D0)
    val onSurface get() = if (isDark) Color(0xF1DEDC) else Color(0x231918)
    val muted get() = if (isDark) Color(0xD8C2BF) else Color(0x534341)
    val tonal get() = if (isDark) Color(0x5C4300) else Color(0xFFDF9E)
    val onTonal get() = if (isDark) Color(0xFFDF9E) else Color(0x261A00)
    val activeOutline get() = if (isDark) Color(0xFFB3AF) else Color(0xB6202E)
    val success get() = if (isDark) Color(0x8CD893) else Color(0x1F6C33)

    fun icon(name: String, size: Int, color: () -> Color = { onSurface }): FlatSVGIcon =
        FlatSVGIcon("icons/ui/$name.svg", size, size).apply {
            colorFilter = FlatSVGIcon.ColorFilter { _ -> color() }
        }

    fun systemPrefersDark(): Boolean = runCatching {
        when (Platform.os) {
            OsFamily.WINDOWS -> Advapi32Util.registryGetIntValue(
                WinReg.HKEY_CURRENT_USER,
                "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                "AppsUseLightTheme"
            ) == 0
            OsFamily.MAC -> commandOutput(listOf("defaults", "read", "-g", "AppleInterfaceStyle")).contains("Dark", true)
            OsFamily.LINUX -> {
                val scheme = commandOutput(listOf("gsettings", "get", "org.gnome.desktop.interface", "color-scheme"))
                val gtk = commandOutput(listOf("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme"))
                scheme.contains("dark", true) || gtk.contains("dark", true)
            }
        }
    }.getOrDefault(false)

    private fun commandOutput(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor(3, TimeUnit.SECONDS)
        return text
    }

    fun uiColor(key: String, fallback: Color): Color = UIManager.getColor(key) ?: fallback
}
