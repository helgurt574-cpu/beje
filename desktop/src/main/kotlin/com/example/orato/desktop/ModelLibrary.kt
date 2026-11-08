package com.example.orato.desktop

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.prefs.Preferences
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/** Konuşma tanıma dilleri (Android sürümüyle aynı). */
enum class SpeechLanguage(
    val code: String,
    val displayName: String,
    val approxSizeMb: Int,
    val isLarge: Boolean = false
) {
    TURKISH("tr", "Türkçe", 57),
    FARSI("fa", "فارسی", 97),
    ARABIC("ar", "العربية", 670, isLarge = true);

    fun normalize(text: String): String = when (this) {
        TURKISH -> text
            .replace(Regex("\\bic\\b"), "üç")
            .replace(Regex("\\bIc\\b"), "Üç")
            .trim()
        ARABIC -> text
            .replace('ی', 'ي')
            .replace('ک', 'ك')
            .replace(Regex("[\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]"), "")
            .replace('ـ', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        FARSI -> text
            .replace('ي', 'ی')
            .replace('ك', 'ک')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    companion object {
        fun fromCode(code: String?): SpeechLanguage? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Dil modellerini bulur. Modeller kopyalanmaz; doğrudan bulundukları klasörden okunur.
 *
 * Arama sırası:
 *  1. Kullanıcının o dil için seçtiği klasör
 *  2. Paketin içine gömülü modeller ve uygulamanın yanındaki (veya üst klasörlerdeki) `BejeModels`
 *  3. Ev dizini ve İndirilenler'deki `BejeModels`
 */
object ModelLibrary {
    enum class Source { MANUAL, AUTO }

    data class Located(val path: Path, val source: Source)

    private const val PREF_SPEECH_MODEL = "speech_model"
    private const val PREF_MODEL_DIR_PREFIX = "model_dir_"
    private const val PREF_LEGACY_MODEL_ROOT = "model_root"
    private const val MAX_DEPTH = 3

    private val prefs: Preferences = Preferences.userRoot().node("beje")

    fun current(): SpeechLanguage =
        SpeechLanguage.fromCode(prefs.get(PREF_SPEECH_MODEL, null)) ?: SpeechLanguage.TURKISH

    fun setCurrent(language: SpeechLanguage) {
        prefs.put(PREF_SPEECH_MODEL, language.code)
    }

    fun locate(language: SpeechLanguage): Located? {
        prefs.get(PREF_MODEL_DIR_PREFIX + language.code, null)?.let { saved ->
            resolve(Path.of(saved), language)?.let { return Located(it, Source.MANUAL) }
        }
        prefs.get(PREF_LEGACY_MODEL_ROOT, null)?.let { saved ->
            resolve(Path.of(saved), language)?.let { return Located(it, Source.MANUAL) }
        }
        autoRoots().forEach { root ->
            resolve(root, language)?.let { return Located(it, Source.AUTO) }
        }
        return null
    }

    /** Seçilen klasörü doğrular ve o dil için kaydeder. */
    @Throws(IOException::class)
    fun importFolder(language: SpeechLanguage, folder: Path): Path {
        val modelDir = resolve(folder, language)
            ?: throw IOException(DesktopI18n.t("error.model_not_found"))
        prefs.put(PREF_MODEL_DIR_PREFIX + language.code, modelDir.toAbsolutePath().normalize().toString())
        return modelDir
    }

    /** Kullanıcının seçtiği klasörü unutur (dosyalara dokunmaz). */
    fun forget(language: SpeechLanguage) {
        prefs.remove(PREF_MODEL_DIR_PREFIX + language.code)
    }

    fun hasManualPath(language: SpeechLanguage): Boolean =
        prefs.get(PREF_MODEL_DIR_PREFIX + language.code, null) != null

    /**
     * Klasör modelin kendisi, `tr`/`fa`/`ar` dil klasörü ya da bunları içeren `BejeModels`
     * kökü olabilir.
     */
    fun resolve(folder: Path, language: SpeechLanguage): Path? {
        val root = folder.toAbsolutePath().normalize()
        if (!root.isDirectory()) return null
        if (isModelRoot(root)) {
            // Doğrudan bir model seçildiyse, başka dilin klasörü olmadığından emin ol.
            val parentName = root.parent?.name.orEmpty()
            val otherLanguage = SpeechLanguage.entries.any { it != language && (root.name == it.code || parentName == it.code) }
            return if (otherLanguage) null else root
        }
        val languageDir = root.resolve(language.code).takeIf { it.isDirectory() }
            ?: root.takeIf { it.name.equals(language.code, ignoreCase = true) }
            ?: return null
        return findModelRoot(languageDir, 0)
    }

    private fun autoRoots(): List<Path> {
        val roots = linkedSetOf<Path>()
        // Paketin içine gömülü modeller (AppImage, macOS .app; başlatıcı bildirir)
        System.getProperty("beje.models")?.takeIf { it.isNotBlank() }?.let { roots.add(Path.of(it)) }
        Platform.appHome()?.let { home ->
            generateSequence(home) { it.parent }.take(4).forEach { dir ->
                roots.add(dir.resolve("BejeModels"))
                roots.add(dir.resolve("models"))
            }
        }
        val userHome = Path.of(System.getProperty("user.home"))
        roots.add(userHome.resolve("BejeModels"))
        roots.add(userHome.resolve("Downloads/BejeModels"))
        roots.add(userHome.resolve("İndirilenler/BejeModels"))
        roots.add(userHome.resolve("Documents/BejeModels"))
        return roots.filter { it.isDirectory() }
    }

    fun findModelRoot(dir: Path, depth: Int): Path? {
        if (!dir.isDirectory()) return null
        if (isModelRoot(dir)) return dir
        if (depth >= MAX_DEPTH) return null
        return runCatching {
            Files.list(dir).use { children ->
                children.filter { it.isDirectory() && !it.name.startsWith(".") }
                    .sorted()
                    .toList()
            }
        }.getOrDefault(emptyList()).firstNotNullOfOrNull { findModelRoot(it, depth + 1) }
    }

    private fun isModelRoot(dir: Path): Boolean {
        val flat = Files.isRegularFile(dir.resolve("final.mdl")) &&
            (Files.exists(dir.resolve("mfcc.conf")) || Files.exists(dir.resolve("HCLr.fst")) ||
                Files.exists(dir.resolve("HCLG.fst")))
        if (flat) return true
        return Files.isRegularFile(dir.resolve("am/final.mdl")) && Files.isRegularFile(dir.resolve("conf/model.conf"))
    }
}
