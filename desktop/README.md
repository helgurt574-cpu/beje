# Orato Windows

Bu modül, Orato'nun Windows masaüstü sürümüdür. Uygulama tamamen offline çalışır ve konuşma modeli sürüme göre ayrı paketlenir:

- `Orato TR`: Türkçe konuşma modeli
- `Orato AR`: Arapça konuşma modeli
- `Orato FA`: Farsça konuşma modeli

Arayüz dili bunlardan bağımsızdır; uygulama içinden Türkçe, Kurmancî, Arapça veya Farsça seçilebilir.

## Özellikler

- Offline Türkçe konuşma tanıma
- Normal masaüstü GUI
- Aktif pencereye otomatik yazdırma
- `Ctrl + Alt + Space` ile başlat/durdur
- Sistem tray üzerinden arka planda çalışma
- Kullanıcı seviyesinde Windows açılışında başlatma
- Temel dikte komutları: `virgül`, `nokta`, `soru işareti`, `ünlem`, `iki nokta`, `noktalı virgül`, `yeni satır`, `yeni paragraf`, `tırnak aç`, `tırnak kapa`, `parantez aç`, `parantez kapa`
- Android sürümündeki model varlığını yeniden kullanma

## Geliştirme İçin Çalıştırma

```bash
./gradlew :desktop:run
```

Bu mod geliştirme içindir ve sistemde Java gerektirir.

## Son Kullanıcı İçin Paketleme

```bash
./gradlew :desktop:installDist
```

`installDist` çıktısı geliştirme/test içindir. Son kullanıcıya doğrudan bunu vermeyin; Java gerektirir.

Windows'ta Java kurulu olmayan kullanıcı için self-contained çıktı üretin.

Sadece Türkçe sürüm:

```bash
.\gradlew :desktop:packageWindowsPortableZip
```

Bu görev, `OratoTR.exe` ve gömülü Java runtime içeren Türkçe taşınabilir zip üretir.

Çıktı:

- `desktop\\build\\portable\\Orato-TR-portable-1.0.0.zip`

Üç konuşma sürümünü birden üretmek için:

```bash
.\gradlew :desktop:packageAllWindowsPortableZips
```

Çıktılar:

- `desktop\\build\\portable\\Orato-TR-portable-1.0.0.zip`
- `desktop\\build\\portable\\Orato-AR-portable-1.0.0.zip`
- `desktop\\build\\portable\\Orato-FA-portable-1.0.0.zip`

Zip açıldıktan sonra son kullanıcı doğrudan şunu çalıştırır:

- `Orato-TR\\OratoTR.exe`
- `Orato-AR\\OratoAR.exe`
- `Orato-FA\\OratoFA.exe`

Windows üzerinde `.exe` kurulum üretmek için:

```bash
.\gradlew :desktop:packageWindowsInstaller
```

Kurulum çıktısı `desktop\\build\\installer\\` altında oluşur. Sadece uygulama klasörü istenirse:

```bash
.\gradlew :desktop:packageWindowsApp
```

İsteğe bağlı Windows ikon dosyası için `desktop/src/main/resources/icons/orato.ico` yolu kullanılabilir. Dosya varsa `jpackage` bunu otomatik alır.

## Notlar

- Aktif pencereye yazdırma Windows'ta `SendInput` ile yapılır.
- Hedef alan bazı özel uygulamalarda veya yönetici yetkili pencerelerde kısıtlanabilir.
- Her sürüm, kendi modelini ilk açılışta ayrı klasöre çıkarır:
  - `Orato TR` -> `%APPDATA%\\OratoTR\\models\\...`
  - `Orato AR` -> `%APPDATA%\\OratoAR\\models\\...`
  - `Orato FA` -> `%APPDATA%\\OratoFA\\models\\...`
- Pencere kapatılırsa uygulama tray'e iner; tamamen çıkmak için tray menüsündeki `Çıkış` kullanılabilir.
- Windows açılışında başlatma ayarı kullanıcıya özeldir ve `HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run` altında tutulur.
- `packageWindowsApp`, `packageWindowsPortableZip` ve `packageWindowsInstaller` çıktıları son kullanıcı bilgisayarında ayrıca Java kurulumu gerektirmez.
