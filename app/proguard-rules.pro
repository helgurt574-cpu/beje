# Vosk, JNA üzerinden yerel kütüphaneyi çağırır; yansıma ile erişilen sınıflar korunmalı.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
