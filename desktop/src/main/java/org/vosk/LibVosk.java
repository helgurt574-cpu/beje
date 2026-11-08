package org.vosk;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Bêje: Vosk'un özgün LibVosk sınıfının yerine geçer (özgünü derlemede jar'dan çıkarılır).
 *
 * <ul>
 *   <li>Yerel kütüphaneyi paketin içindeki hazır klasörden ("beje.native") tam yoluyla yükler;
 *       geçici klasöre dosya çıkarmaz (antivirüs, macOS kuralları, Türkçe karakterli yollar).</li>
 *   <li>Tanıma sonuçları (JSON) her platformda UTF-8 olarak okunur.</li>
 *   <li>Fonksiyonlar açılışta toptan değil, ilk kullanıldıklarında bağlanır (JNA arayüz eşlemesi).
 *       Vosk'un macOS kütüphanesinde {@code vosk_recognizer_set_grm} yoktur; özgün sınıf bu yüzden
 *       macOS'ta hiç yüklenemiyordu. Bêje bu fonksiyonu kullanmaz.</li>
 * </ul>
 */
public class LibVosk {

    /** Vosk C API'si. Yöntemler ilk çağrıldıklarında çözülür. */
    interface Api extends Library {
        void vosk_set_log_level(int level);

        Pointer vosk_model_new(String path);

        void vosk_model_free(Pointer model);

        Pointer vosk_spk_model_new(String path);

        void vosk_spk_model_free(Pointer model);

        Pointer vosk_recognizer_new(Model model, float sampleRate);

        Pointer vosk_recognizer_new_spk(Pointer model, float sampleRate, Pointer spkModel);

        Pointer vosk_recognizer_new_grm(Pointer model, float sampleRate, String grammar);

        void vosk_recognizer_set_max_alternatives(Pointer recognizer, int maxAlternatives);

        void vosk_recognizer_set_words(Pointer recognizer, boolean words);

        void vosk_recognizer_set_partial_words(Pointer recognizer, boolean partialWords);

        void vosk_recognizer_set_spk_model(Pointer recognizer, Pointer spkModel);

        boolean vosk_recognizer_accept_waveform(Pointer recognizer, byte[] data, int len);

        boolean vosk_recognizer_accept_waveform_s(Pointer recognizer, short[] data, int len);

        boolean vosk_recognizer_accept_waveform_f(Pointer recognizer, float[] data, int len);

        Pointer vosk_recognizer_result(Pointer recognizer);

        Pointer vosk_recognizer_final_result(Pointer recognizer);

        Pointer vosk_recognizer_partial_result(Pointer recognizer);

        void vosk_recognizer_set_grm(Pointer recognizer, String grammar);

        void vosk_recognizer_reset(Pointer recognizer);

        void vosk_recognizer_free(Pointer recognizer);
    }

    private static final Api API;

    static {
        try {
            API = load();
        } catch (IOException exception) {
            throw new UnsatisfiedLinkError("Vosk kütüphanesi yüklenemedi: " + exception);
        }
    }

    private static Api load() throws IOException {
        String dir = System.getProperty("beje.native");
        if (dir != null && !dir.isEmpty() && new File(dir).isDirectory()) {
            File base = new File(dir);
            String name;
            if (Platform.isWindows()) {
                // libvosk.dll'nin bağımlılıkları önce yüklenir ki Windows onları aramak zorunda kalmasın.
                for (String dependency : new String[]{"libwinpthread-1", "libgcc_s_seh-1", "libstdc++-6"}) {
                    File file = new File(base, dependency + ".dll");
                    if (file.isFile()) {
                        System.load(file.getAbsolutePath());
                    }
                }
                name = "libvosk.dll";
            } else if (Platform.isMac()) {
                name = "libvosk.dylib";
            } else {
                name = "libvosk.so";
            }
            File library = new File(base, name);
            if (!library.isFile()) {
                throw new IOException("Bulunamadı: " + library.getAbsolutePath());
            }
            return Native.load(library.getAbsolutePath(), Api.class);
        }

        // Geliştirme sırasında: jar içindeki kütüphane (Windows'ta bağımlılıklar geçici klasöre çıkarılır).
        if (Platform.isWindows()) {
            File tmpDir = Native.extractFromResourcePath("/win32-x86-64/empty", LibVosk.class.getClassLoader())
                .getParentFile();
            new File(tmpDir, tmpDir.getName() + ".x").createNewFile();
            unpackDll(tmpDir, "libwinpthread-1");
            unpackDll(tmpDir, "libgcc_s_seh-1");
            unpackDll(tmpDir, "libstdc++-6");
            return Native.load("libvosk", Api.class);
        }
        return Native.load("vosk", Api.class);
    }

    /**
     * Vosk sonuçları her zaman UTF-8'dir. JNA ise char* metinleri sistem kodlamasıyla okur
     * (Türkçe Windows'ta windows-1254), bu da "ç, ı, ş" gibi harfleri bozar; bu yüzden açıkça
     * UTF-8 okunur. Dosya yolları ise sistem kodlamasıyla gider (Vosk Windows'ta öyle açar).
     */
    private static String utf8(Pointer text) {
        return text == null ? null : text.getString(0, "UTF-8");
    }

    private static void unpackDll(File targetDir, String lib) throws IOException {
        try (InputStream source = LibVosk.class.getResourceAsStream("/win32-x86-64/" + lib + ".dll")) {
            if (source != null) {
                Files.copy(source, new File(targetDir, lib + ".dll").toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    public static void vosk_set_log_level(int level) {
        API.vosk_set_log_level(level);
    }

    public static Pointer vosk_model_new(String path) {
        return API.vosk_model_new(path);
    }

    public static void vosk_model_free(Pointer model) {
        API.vosk_model_free(model);
    }

    public static Pointer vosk_spk_model_new(String path) {
        return API.vosk_spk_model_new(path);
    }

    public static void vosk_spk_model_free(Pointer model) {
        API.vosk_spk_model_free(model);
    }

    public static Pointer vosk_recognizer_new(Model model, float sampleRate) {
        return API.vosk_recognizer_new(model, sampleRate);
    }

    public static Pointer vosk_recognizer_new_spk(Pointer model, float sampleRate, Pointer spkModel) {
        return API.vosk_recognizer_new_spk(model, sampleRate, spkModel);
    }

    public static Pointer vosk_recognizer_new_grm(Pointer model, float sampleRate, String grammar) {
        return API.vosk_recognizer_new_grm(model, sampleRate, grammar);
    }

    public static void vosk_recognizer_set_max_alternatives(Pointer recognizer, int maxAlternatives) {
        API.vosk_recognizer_set_max_alternatives(recognizer, maxAlternatives);
    }

    public static void vosk_recognizer_set_words(Pointer recognizer, boolean words) {
        API.vosk_recognizer_set_words(recognizer, words);
    }

    public static void vosk_recognizer_set_partial_words(Pointer recognizer, boolean partialWords) {
        API.vosk_recognizer_set_partial_words(recognizer, partialWords);
    }

    public static void vosk_recognizer_set_spk_model(Pointer recognizer, Pointer spkModel) {
        API.vosk_recognizer_set_spk_model(recognizer, spkModel);
    }

    public static boolean vosk_recognizer_accept_waveform(Pointer recognizer, byte[] data, int len) {
        return API.vosk_recognizer_accept_waveform(recognizer, data, len);
    }

    public static boolean vosk_recognizer_accept_waveform_s(Pointer recognizer, short[] data, int len) {
        return API.vosk_recognizer_accept_waveform_s(recognizer, data, len);
    }

    public static boolean vosk_recognizer_accept_waveform_f(Pointer recognizer, float[] data, int len) {
        return API.vosk_recognizer_accept_waveform_f(recognizer, data, len);
    }

    public static String vosk_recognizer_result(Pointer recognizer) {
        return utf8(API.vosk_recognizer_result(recognizer));
    }

    public static String vosk_recognizer_final_result(Pointer recognizer) {
        return utf8(API.vosk_recognizer_final_result(recognizer));
    }

    public static String vosk_recognizer_partial_result(Pointer recognizer) {
        return utf8(API.vosk_recognizer_partial_result(recognizer));
    }

    public static void vosk_recognizer_set_grm(Pointer recognizer, String grammar) {
        API.vosk_recognizer_set_grm(recognizer, grammar);
    }

    public static void vosk_recognizer_reset(Pointer recognizer) {
        API.vosk_recognizer_reset(recognizer);
    }

    public static void vosk_recognizer_free(Pointer recognizer) {
        API.vosk_recognizer_free(recognizer);
    }

    public static void setLogLevel(LogLevel loglevel) {
        vosk_set_log_level(loglevel.getValue());
    }
}
