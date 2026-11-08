package com.example.offlinedikte

import org.vosk.Model
import java.io.File

/**
 * Yüklenen Vosk modelini süreç boyunca saklar; ekran döndürme veya arayüz dili değişikliği
 * gibi Activity yeniden oluşturmalarında büyük modelin tekrar yüklenmesini önler.
 */
object SpeechModelCache {
    private var cachedPath: String? = null
    private var cachedModel: Model? = null

    @Synchronized
    fun get(dir: File): Model? = if (cachedPath == dir.absolutePath) cachedModel else null

    /**
     * Model yükler (yavaş olabilir, ana iş parçacığında çağırmayın). Yüklemeler sıraya alınır ki
     * geç biten eski bir yükleme yeni modeli kapatmasın.
     */
    @Synchronized
    fun load(dir: File): Model {
        get(dir)?.let { return it }
        val model = Model(dir.absolutePath)
        cachedModel?.close()
        cachedModel = model
        cachedPath = dir.absolutePath
        return model
    }

    @Synchronized
    fun release() {
        cachedModel?.close()
        cachedModel = null
        cachedPath = null
    }
}
