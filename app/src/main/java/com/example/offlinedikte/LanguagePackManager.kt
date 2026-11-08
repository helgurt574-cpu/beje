package com.example.offlinedikte

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.google.android.play.core.assetpacks.AssetPackException
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackErrorCode
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Konuşma modellerinin (dil paketlerinin) nerede olduğunu ve indirme durumunu yönetir.
 *
 * Model iki kaynaktan gelebilir:
 *  1. Google Play Asset Delivery ile indirilen isteğe bağlı paket (`model_tr`, `model_fa`, ...)
 *  2. Kullanıcının cihazdaki bir klasörden içe aktardığı model (Play dışı kurulumlar için)
 */
class LanguagePackManager(context: Context) {

    enum class Status {
        NOT_INSTALLED,
        PENDING,
        DOWNLOADING,
        INSTALLING,
        INSTALLED,
        WAITING_FOR_WIFI,
        NEEDS_CONFIRMATION,
        FAILED,
        IMPORTING
    }

    enum class Source { PLAY, IMPORTED }

    data class PackState(
        val status: Status = Status.NOT_INSTALLED,
        val progressPercent: Int = 0,
        val totalBytes: Long = 0,
        val source: Source? = null,
        val errorCode: Int = AssetPackErrorCode.NO_ERROR
    ) {
        val isInstalled get() = status == Status.INSTALLED
        val isBusy
            get() = status == Status.PENDING || status == Status.DOWNLOADING ||
                status == Status.INSTALLING || status == Status.IMPORTING
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val assetPackManager: AssetPackManager = AssetPackManagerFactory.getInstance(appContext)
    private val states = mutableMapOf<SpeechModelProfile, PackState>()

    /** null: henüz bilinmiyor, false: uygulama Play üzerinden kurulmamış (paket indirilemez). */
    var isPlayDeliveryAvailable: Boolean? = null
        private set

    var onStateChanged: ((SpeechModelProfile) -> Unit)? = null
    var onConfirmationRequired: ((SpeechModelProfile) -> Unit)? = null

    private val playListener = AssetPackStateUpdateListener { state ->
        val profile = SpeechModelProfile.fromPackName(state.name()) ?: return@AssetPackStateUpdateListener
        applyPlayState(profile, state)
    }

    init {
        SpeechModelProfile.entries.forEach { profile ->
            val imported = importedModelDir(profile)?.let(::findModelRoot) != null
            states[profile] = when {
                imported -> PackState(Status.INSTALLED, 100, source = Source.IMPORTED)
                modelDirectory(profile) != null -> PackState(Status.INSTALLED, 100, source = Source.PLAY)
                else -> PackState()
            }
        }
    }

    fun start() {
        assetPackManager.registerListener(playListener)
        refresh()
    }

    fun stop() {
        assetPackManager.unregisterListener(playListener)
    }

    fun state(profile: SpeechModelProfile): PackState = states[profile] ?: PackState()

    fun refresh() {
        assetPackManager.getPackStates(SpeechModelProfile.entries.map { it.packName })
            .addOnSuccessListener { packStates ->
                isPlayDeliveryAvailable = true
                packStates.packStates().values.forEach { state ->
                    SpeechModelProfile.fromPackName(state.name())?.let { applyPlayState(it, state) }
                }
            }
            .addOnFailureListener { error ->
                val code = (error as? AssetPackException)?.errorCode ?: AssetPackErrorCode.INTERNAL_ERROR
                Log.i(TAG, "Play Asset Delivery kullanılamıyor (kod $code)")
                isPlayDeliveryAvailable = !isPlayUnavailableError(code)
                SpeechModelProfile.entries.forEach { notifyChanged(it) }
            }
    }

    fun download(profile: SpeechModelProfile) {
        updateState(profile, state(profile).copy(status = Status.PENDING, progressPercent = 0))
        assetPackManager.fetch(listOf(profile.packName))
            .addOnFailureListener { error ->
                val code = (error as? AssetPackException)?.errorCode ?: AssetPackErrorCode.INTERNAL_ERROR
                Log.w(TAG, "Paket indirilemedi: ${profile.packName} (kod $code)", error)
                if (isPlayUnavailableError(code)) {
                    isPlayDeliveryAvailable = false
                }
                updateState(profile, PackState(Status.FAILED, errorCode = code))
            }
    }

    fun cancel(profile: SpeechModelProfile) {
        runCatching { assetPackManager.cancel(listOf(profile.packName)) }
        updateState(profile, PackState())
    }

    fun showConfirmation(activity: Activity) {
        assetPackManager.showConfirmationDialog(activity)
    }

    /** Hem Play paketini hem de içe aktarılmış kopyayı kaldırır. */
    fun remove(profile: SpeechModelProfile, onDone: () -> Unit) {
        importedModelDir(profile)?.deleteRecursively()
        if (assetPackManager.getPackLocation(profile.packName) == null) {
            updateState(profile, PackState())
            onDone()
            return
        }
        assetPackManager.removePack(profile.packName)
            .addOnCompleteListener {
                updateState(profile, PackState())
                onDone()
            }
    }

    /** Modelin kök klasörü (içinde final.mdl bulunan) ya da paket yüklü değilse null. */
    fun modelDirectory(profile: SpeechModelProfile): File? {
        val playDir = runCatching { assetPackManager.getPackLocation(profile.packName)?.assetsPath() }
            .getOrNull()
            ?.let(::File)
            ?.let(::findModelRoot)
        if (playDir != null) return playDir
        return importedModelDir(profile)?.let(::findModelRoot)
    }

    /**
     * Kullanıcının seçtiği klasördeki modeli uygulama alanına kopyalar. Seçilen klasör modelin
     * kendisi, `tr`/`fa` gibi dil klasörü ya da bunları içeren `BejeModels` kökü olabilir.
     */
    fun importFromTree(
        profile: SpeechModelProfile,
        treeUri: Uri,
        onResult: (Result<File>) -> Unit
    ) {
        updateState(profile, PackState(Status.IMPORTING))
        thread(start = true, name = "model-import-${profile.code}") {
            val result = runCatching {
                val tree = DocumentFile.fromTreeUri(appContext, treeUri)
                    ?: throw IOException(appContext.getString(R.string.model_not_found))
                val source = findDocumentModelRoot(tree, profile)
                    ?: throw IOException(appContext.getString(R.string.model_not_found))
                val target = File(appContext.filesDir, "$IMPORT_ROOT/${profile.code}")
                val staging = File(appContext.filesDir, "$IMPORT_ROOT/.${profile.code}.tmp")
                staging.deleteRecursively()
                if (!staging.mkdirs()) throw IOException("Klasör oluşturulamadı")
                try {
                    copyDocumentTree(source, staging)
                    File(staging, MODEL_READY_MARKER).writeText(profile.code)
                    target.deleteRecursively()
                    if (!staging.renameTo(target)) throw IOException("Model taşınamadı")
                } finally {
                    staging.deleteRecursively()
                }
                findModelRoot(target) ?: throw IOException(appContext.getString(R.string.model_not_found))
            }
            mainHandler.post {
                result.onSuccess {
                    updateState(profile, PackState(Status.INSTALLED, 100, source = Source.IMPORTED))
                }.onFailure {
                    Log.w(TAG, "Model içe aktarılamadı", it)
                    val fallback = if (modelDirectory(profile) != null) {
                        PackState(Status.INSTALLED, 100, source = Source.PLAY)
                    } else {
                        PackState()
                    }
                    updateState(profile, fallback)
                }
                onResult(result)
            }
        }
    }

    private fun applyPlayState(profile: SpeechModelProfile, state: AssetPackState) {
        val imported = importedModelDir(profile)?.let(::findModelRoot) != null
        val total = state.totalBytesToDownload()
        val newState = when (state.status()) {
            AssetPackStatus.COMPLETED -> PackState(Status.INSTALLED, 100, total, Source.PLAY)
            AssetPackStatus.PENDING -> PackState(Status.PENDING, 0, total)
            AssetPackStatus.DOWNLOADING -> PackState(
                Status.DOWNLOADING,
                if (total > 0) (state.bytesDownloaded() * 100 / total).toInt() else 0,
                total
            )
            AssetPackStatus.TRANSFERRING ->
                PackState(Status.INSTALLING, state.transferProgressPercentage(), total)
            AssetPackStatus.WAITING_FOR_WIFI -> PackState(Status.WAITING_FOR_WIFI, 0, total)
            AssetPackStatus.REQUIRES_USER_CONFIRMATION -> PackState(Status.NEEDS_CONFIRMATION, 0, total)
            AssetPackStatus.FAILED -> if (imported) {
                PackState(Status.INSTALLED, 100, total, Source.IMPORTED)
            } else {
                PackState(Status.FAILED, 0, total, errorCode = state.errorCode())
            }
            else -> if (imported) {
                PackState(Status.INSTALLED, 100, total, Source.IMPORTED)
            } else {
                PackState(Status.NOT_INSTALLED, 0, total)
            }
        }
        updateState(profile, newState)
        if (newState.status == Status.NEEDS_CONFIRMATION || newState.status == Status.WAITING_FOR_WIFI) {
            onConfirmationRequired?.invoke(profile)
        }
    }

    private fun updateState(profile: SpeechModelProfile, newState: PackState) {
        states[profile] = newState
        notifyChanged(profile)
    }

    private fun notifyChanged(profile: SpeechModelProfile) {
        onStateChanged?.invoke(profile)
    }

    private fun importedModelDir(profile: SpeechModelProfile): File? {
        val dir = File(appContext.filesDir, "$IMPORT_ROOT/${profile.code}")
        return if (File(dir, MODEL_READY_MARKER).exists()) dir else null
    }

    private fun isPlayUnavailableError(code: Int): Boolean = code in setOf(
        AssetPackErrorCode.API_NOT_AVAILABLE,
        AssetPackErrorCode.APP_UNAVAILABLE,
        AssetPackErrorCode.ACCESS_DENIED,
        AssetPackErrorCode.APP_NOT_OWNED,
        AssetPackErrorCode.UNRECOGNIZED_INSTALLATION,
        AssetPackErrorCode.PACK_UNAVAILABLE,
        AssetPackErrorCode.INVALID_REQUEST,
        PLAY_STORE_NOT_FOUND
    )

    private fun findDocumentModelRoot(tree: DocumentFile, profile: SpeechModelProfile): DocumentFile? {
        if (isDocumentModelRoot(tree)) return tree
        val languageDir = tree.findFile(profile.folderName)?.takeIf { it.isDirectory }
            ?: tree.takeIf { it.name.equals(profile.folderName, ignoreCase = true) }
            ?: tree
        return searchDocumentModelRoot(languageDir, depth = 0)
    }

    private fun searchDocumentModelRoot(dir: DocumentFile, depth: Int): DocumentFile? {
        if (isDocumentModelRoot(dir)) return dir
        if (depth >= MAX_SEARCH_DEPTH) return null
        return dir.listFiles()
            .filter { it.isDirectory }
            .firstNotNullOfOrNull { searchDocumentModelRoot(it, depth + 1) }
    }

    private fun isDocumentModelRoot(dir: DocumentFile): Boolean {
        if (!dir.isDirectory) return false
        val flat = dir.findFile("final.mdl") != null &&
            (dir.findFile("mfcc.conf") != null || dir.findFile("HCLr.fst") != null ||
                dir.findFile("HCLG.fst") != null)
        if (flat) return true
        return dir.findFile("am")?.findFile("final.mdl") != null &&
            dir.findFile("conf")?.findFile("model.conf") != null
    }

    @Throws(IOException::class)
    private fun copyDocumentTree(sourceDir: DocumentFile, targetDir: File) {
        sourceDir.listFiles().forEach { child ->
            val childName = child.name ?: return@forEach
            val outputFile = File(targetDir, childName)
            if (!outputFile.canonicalPath.startsWith(targetDir.canonicalPath + File.separator)) {
                throw IOException("Geçersiz dosya adı: $childName")
            }
            if (child.isDirectory) {
                if (!outputFile.exists() && !outputFile.mkdirs()) {
                    throw IOException("Klasör oluşturulamadı: $childName")
                }
                copyDocumentTree(child, outputFile)
            } else if (child.isFile) {
                appContext.contentResolver.openInputStream(child.uri)?.use { input ->
                    FileOutputStream(outputFile).use { output -> input.copyTo(output) }
                } ?: throw IOException("Dosya okunamadı: $childName")
            }
        }
    }

    companion object {
        private const val TAG = "LanguagePackManager"
        private const val IMPORT_ROOT = "speech-models"
        private const val MODEL_READY_MARKER = ".ready"
        private const val MAX_SEARCH_DEPTH = 3

        /** Cihazda Play Store yok (kütüphanenin 2.x sürümünde sabit olarak tanımlı değil). */
        private const val PLAY_STORE_NOT_FOUND = -11

        /** Klasördeki Vosk model kökünü (final.mdl içeren) bulur. */
        fun findModelRoot(dir: File, depth: Int = 0): File? {
            if (!dir.isDirectory) return null
            val flat = File(dir, "final.mdl").isFile
            val structured = File(dir, "am/final.mdl").isFile && File(dir, "conf/model.conf").isFile
            if (flat || structured) return dir
            if (depth >= MAX_SEARCH_DEPTH) return null
            return dir.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.sortedBy { it.name }
                ?.firstNotNullOfOrNull { findModelRoot(it, depth + 1) }
        }
    }
}
