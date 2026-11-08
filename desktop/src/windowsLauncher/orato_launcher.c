/*
 * Bêje Windows başlatıcısı: yanındaki runtime\bin\javaw.exe ile uygulamayı başlatır.
 * Komut satırı argümanlarını (ör. --toggle, --background) uygulamaya iletir.
 */
#include <windows.h>
#include <shellapi.h>
#include <wchar.h>

#define MAX_CMD 32768

static void show_error(const wchar_t *message) {
    MessageBoxW(NULL, message, L"B\x00EAje", MB_ICONERROR | MB_OK);
}

/* GetCommandLineW() çıktısından programın kendi adını atlayıp kalan argümanları döndürür. */
static const wchar_t *skip_program_name(const wchar_t *cmd) {
    if (*cmd == L'"') {
        cmd++;
        while (*cmd && *cmd != L'"') cmd++;
        if (*cmd == L'"') cmd++;
    } else {
        while (*cmd && *cmd != L' ' && *cmd != L'\t') cmd++;
    }
    while (*cmd == L' ' || *cmd == L'\t') cmd++;
    return cmd;
}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE previous, PWSTR cmdLine, int showCmd) {
    (void) instance; (void) previous; (void) cmdLine; (void) showCmd;

    wchar_t exe_path[MAX_PATH];
    wchar_t exe_dir[MAX_PATH];
    wchar_t javaw_path[MAX_PATH];
    static wchar_t command[MAX_CMD];
    STARTUPINFOW startup_info;
    PROCESS_INFORMATION process_info;

    DWORD len = GetModuleFileNameW(NULL, exe_path, MAX_PATH);
    if (len == 0 || len >= MAX_PATH) {
        show_error(L"Uygulama dizini bulunamadi.");
        return 1;
    }
    wcscpy(exe_dir, exe_path);
    for (DWORD i = len; i > 0; i--) {
        if (exe_dir[i] == L'\\' || exe_dir[i] == L'/') { exe_dir[i] = L'\0'; break; }
    }

    /* Yolda Türkçe/Arapça karakter varsa (ör. C:\\Users\\efrın) kısa 8.3 biçimine çevir:
       Java ve Vosk bu tür yolları bazı Windows dil ayarlarında açamıyor. */
    {
        wchar_t short_path[MAX_PATH];
        DWORD n = GetShortPathNameW(exe_dir, short_path, MAX_PATH);
        if (n > 0 && n < MAX_PATH) wcscpy(exe_dir, short_path);
        n = GetShortPathNameW(exe_path, short_path, MAX_PATH);
        if (n > 0 && n < MAX_PATH) wcscpy(exe_path, short_path);
    }

    _snwprintf(javaw_path, MAX_PATH, L"%ls\\runtime\\bin\\javaw.exe", exe_dir);
    if (GetFileAttributesW(javaw_path) == INVALID_FILE_ATTRIBUTES) {
        show_error(L"Gomulu Java bulunamadi. Klasorun tamamini (runtime dahil) birlikte tasiyin.");
        return 1;
    }

    const wchar_t *args = skip_program_name(GetCommandLineW());
    if (_snwprintf(
            command, MAX_CMD,
            L"\"%ls\" -Dfile.encoding=UTF-8"
            L" \"-Dbeje.home=%ls\" \"-Dbeje.launcher=%ls\""
            L" \"-Dbeje.native=%ls\\app\\native\" \"-Djna.boot.library.path=%ls\\app\\native\""
            L" -cp \"%ls\\app\\lib\\*\""
            L" com.example.orato.desktop.OratoAppKt %ls",
            javaw_path, exe_dir, exe_path, exe_dir, exe_dir, exe_dir, args) < 0) {
        show_error(L"Baslatma komutu olusturulamadi.");
        return 1;
    }

    ZeroMemory(&startup_info, sizeof(startup_info));
    startup_info.cb = sizeof(startup_info);
    ZeroMemory(&process_info, sizeof(process_info));
    if (!CreateProcessW(NULL, command, NULL, NULL, FALSE, 0, NULL, exe_dir, &startup_info, &process_info)) {
        show_error(L"B\x00EAje baslatilamadi.");
        return 1;
    }
    CloseHandle(process_info.hThread);
    CloseHandle(process_info.hProcess);
    return 0;
}
