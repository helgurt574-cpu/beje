# Bêje

Çevrimdışı dikte uygulaması. Konuşma cihazda, internetsiz yazıya dökülür (Türkçe, Farsça, Arapça).
Android, Windows, Linux ve macOS.

*Offline dictation app for Android, Windows, Linux and macOS (Turkish, Persian, Arabic).*

## İndir

Kurulum dosyaları: [Releases](../../releases) · Kılavuz: [`docs/kilavuz/Beje-Kullanim-Kilavuzu.pdf`](docs/kilavuz/Beje-Kullanim-Kilavuzu.pdf)

## Derleme

JDK 21 gerekir. Konuşma modelleri depoda yoktur; [Vosk](https://alphacephei.com/vosk/models) sayfasından
indirip `Models/tr`, `Models/fa`, `Models/ar` altına açın.

```bash
./gradlew :app:assembleRelease          # Android
./gradlew :desktop:packageAllDesktop    # Windows, Linux, macOS
```

## Lisans

[Apache 2.0](LICENSE)
