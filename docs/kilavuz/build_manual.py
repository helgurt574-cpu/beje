#!/usr/bin/env python3
"""Bêje kullanım kılavuzunu üretir: HTML -> (LibreOffice) -> DOCX ve PDF.

Çalıştırma:  python3 docs/kilavuz/build_manual.py [çıktı_klasörü]
Görseller docs/kilavuz/img altındadır; HTML'e gömülür (base64).
"""
import base64
import shutil
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else HERE
VERSION = "1.1.0"
RED = "#B6202E"


def img(name, width_cm, caption=None):
    path = os.path.join(HERE, "img", name)
    mime = "image/png" if name.endswith(".png") else "image/jpeg"
    data = base64.b64encode(open(path, "rb").read()).decode()
    cap = f'<p class="caption">{caption}</p>' if caption else ""
    return (f'<p align="center"><img src="data:{mime};base64,{data}" '
            f'style="width:{width_cm}cm" width="{int(width_cm * 37.8)}"></p>{cap}')


def two_images(a, b, width_cm, cap_a, cap_b):
    def cell(name, cap):
        path = os.path.join(HERE, "img", name)
        mime = "image/png" if name.endswith(".png") else "image/jpeg"
        data = base64.b64encode(open(path, "rb").read()).decode()
        return (f'<td align="center" valign="top" width="50%"><img src="data:{mime};base64,{data}" '
                f'width="{int(width_cm * 37.8)}" style="width:{width_cm}cm"><p class="caption">{cap}</p></td>')
    return f'<table width="100%" cellpadding="4"><tr>{cell(a, cap_a)}{cell(b, cap_b)}</tr></table>'


def tip(text, kind="İpucu"):
    color = {"İpucu": "#FFF3D6", "Önemli": "#FFE1DE", "Not": "#E6F4E8"}[kind]
    return (f'<table width="100%" cellpadding="8" cellspacing="0" style="background:{color}">'
            f'<tr><td bgcolor="{color}"><b>{kind}:</b> {text}</td></tr></table><p></p>')


def table(header, rows, widths=None):
    ws = widths or [None] * len(header)
    head = "".join(f'<td bgcolor="{RED}"{f" width={w}%" if w else ""}><font color="#FFFFFF"><b>{h}</b></font></td>'
                   for h, w in zip(header, ws))
    body = ""
    for i, row in enumerate(rows):
        bg = "#FFF8F6" if i % 2 == 0 else "#FCEAE8"
        body += "<tr>" + "".join(f'<td bgcolor="{bg}" valign="top">{c}</td>' for c in row) + "</tr>"
    return f'<table width="100%" cellpadding="6" cellspacing="0" border="1" style="border-collapse:collapse">' \
           f'<tr>{head}</tr>{body}</table><p></p>'


def code(text):
    return f'<p class="code"><font face="DejaVu Sans Mono, Courier New">{text}</font></p>'


BREAK = '<p style="page-break-before: always"></p>'

html = f"""<!DOCTYPE html>
<html lang="tr"><head><meta charset="utf-8"><title>Bêje Kullanım Kılavuzu</title>
<style>
body {{ font-family: 'Liberation Sans', Arial, sans-serif; font-size: 11pt; color: #231918; }}
h1 {{ font-size: 22pt; color: {RED}; margin-top: 18pt; }}
h2 {{ font-size: 15pt; color: {RED}; margin-top: 14pt; }}
h3 {{ font-size: 12.5pt; color: #534341; margin-top: 10pt; }}
p {{ margin-top: 3pt; margin-bottom: 5pt; line-height: 130%; }}
li {{ margin-bottom: 3pt; }}
.caption {{ font-size: 9pt; color: #6b5a58; text-align: center; font-style: italic; }}
.code {{ font-size: 9.5pt; background: #F3ECEA; margin-left: 0.5cm; }}
.cover {{ text-align: center; }}
</style></head><body>

<div class="cover">
<p>&nbsp;</p><p>&nbsp;</p>
{img("logo.png", 5)}
<p align="center"><font size="7" color="{RED}"><b>Bêje</b></font></p>
<p align="center"><font size="5">Kullanım Kılavuzu</font></p>
<p align="center"><font size="4" color="#534341">Sürüm {VERSION} · Android · Windows · Linux · macOS</font></p>
<p align="center"><font size="3" color="#6b5a58">Kasım 2026</font></p>
<p>&nbsp;</p>
<p align="center">İnternetsiz çalışan dikte uygulaması.<br>Konuşmanız yalnızca kendi cihazınızda yazıya dökülür.</p>
</div>

{BREAK}
<h1>1. Bêje nedir?</h1>
<p><b>Bêje</b> (Kürtçe “söyle”) konuşmanızı yazıya döken bir dikte uygulamasıdır. Tüm işlem kendi telefonunuzda ya da
bilgisayarınızda yapılır: ses kaydedilmez, hiçbir veri internete gönderilmez. Bir kez kurulduktan sonra internet hiç gerekmez.</p>
{table(["", "Ayrıntı"], [
    ["Konuşma dilleri", "Türkçe, Farsça (فارسی), Arapça (العربية)"],
    ["Arayüz dilleri", "Türkçe, Kurmancî, العربية, فارسی, English"],
    ["Platformlar", "Android 7+, Windows 10/11 (64 bit), Linux (64 bit), macOS 11+ (Apple Silicon ve Intel)"],
    ["Kaydetme biçimleri", "TXT, PDF, Word (DOCX); Android'de ayrıca ODT, bilgisayarda Word 97-2003 (DOC)"],
    ["Ek özellikler", "Ses dosyasını yazıya dökme, diğer uygulamalara doğrudan yazma (bilgisayar), açık/koyu tema"],
], [30, 70])}

<h2>1.1 Dağıtım klasörünün içeriği</h2>
{table(["Klasör / dosya", "Ne için?"], [
    ["<b>Windows/</b>Beje-1.1.0-Windows-Setup.exe", "Windows 10 / 11 — kurulum sihirbazı (önerilen)"],
    ["<b>Windows/</b>Beje-1.1.0-Windows-x64-portable.zip", "Windows — kurulumsuz, taşınabilir sürüm"],
    ["<b>Linux/</b>beje_1.1.0_amd64.deb", "Ubuntu, Debian, Mint, Pardus, Zorin… — çift tıklayıp kurulur (önerilen)"],
    ["<b>Linux/</b>Beje-1.1.0-x86_64.AppImage", "Diğer tüm Linux dağıtımları — tek dosya"],
    ["<b>Linux/</b>Beje-1.1.0-Linux-x64.tar.gz", "Linux — klasör sürümü"],
    ["<b>macOS/</b>…AppleSilicon.pkg ve …Intel.pkg", "Mac — kurulum sihirbazı (işlemcinize göre)"],
    ["<b>Android/</b>Beje-1.1.0.apk ve <b>Android/BejeModels/</b>", "Android telefon ve tabletler (Play Store olmadan); dil paketleri ayrı klasörde"],
    ["<b>Beje-Kullanim-Kilavuzu</b>.pdf / .docx", "Bu kılavuz"],
    ["<b>KURULUM.txt</b>", "Kısa kurulum notları"],
], [44, 56])}
{tip("Bilgisayar sürümleri hem Java'yı hem de üç dil paketini (Türkçe, Farsça, Arapça) içinde taşır. Kurduktan sonra hiçbir şey kopyalamanız ya da indirmeniz gerekmez.", "Not")}

<h2>1.2 Dil paketleri</h2>
{table(["Paket", "Klasör", "Diskte", "Google Play'den indirme", "Not"], [
    ["Türkçe", "tr", "≈ 57 MB", "≈ 40 MB", "Hızlı, her cihazda çalışır"],
    ["Farsça", "fa", "≈ 97 MB", "≈ 56 MB", "Hızlı, her cihazda çalışır"],
    ["Arapça", "ar", "≈ 670 MB", "≈ 336 MB", "Büyük model; en az 4 GB belleği olan cihaz önerilir. Açılışı birkaç saniye sürer."],
], [13, 10, 12, 18, 47])}
<p>Bilgisayar sürümlerinde dil paketleri uygulamanın içindedir. Android'de ise APK küçük tutulduğu için dil paketleri
<b>Android/BejeModels</b> klasöründe ayrı gelir ve bir kez içe aktarılır (bölüm 2.3). Google Play sürümünde ise uygulamanın içinden indirilir (bölüm 2.1). Klasör adlarını değiştirmeyin.</p>

<h1>2. Android</h1>
<p>Android'de Bêje iki yoldan kurulur: <b>Google Play</b>'den (önerilen; dil paketleri uygulama içinden indirilir) ya da
internetsiz kurulum için <b>APK dosyasından</b> (dil paketleri klasörden eklenir).</p>

<h2>2.1 Google Play'den kurulum</h2>
<ol>
<li>Play Store'da <b>Bêje</b>'yi bulun ve <b>Yükle</b>'ye dokunun. Uygulama yaklaşık 5 MB'tır.</li>
<li>Bêje'yi açın. <b>Dil paketleri</b> ekranında kullanacağınız dilin yanındaki <b>İndir</b>'e dokunun.
İndirme yüzdesi satırda görünür; bitince dil kendiliğinden <b>Etkin</b> olur.</li>
<li>Bundan sonra internet gerekmez; Bêje uçak modunda da çalışır.</li>
</ol>
{img("android_play_packs.jpg", 5.4, "Google Play sürümünde dil paketleri: İndir, Etkin, Sil")}
<ul>
<li>Başka bir dil eklemek için aynı ekranda o dilin <b>İndir</b> düğmesine dokunun; dilleri <b>Kullan</b> ile değiştirin.</li>
<li>Bir paketi silip yer açmak için satırdaki <b>çöp kutusu</b> simgesine dokunun; istediğiniz zaman yeniden indirebilirsiniz.</li>
<li>Arapça paketi büyük olduğu için (≈ 336 MB) mobil veride Google Play onay isteyebilir ya da Wi-Fi'yi bekleyebilir;
satırdaki <b>Devam et</b> düğmesine dokunarak onaylayın.</li>
</ul>
{tip("Play sürümü ile APK sürümü aynı anda kurulamaz. APK ile kurduğunuz Bêje varsa, Play'den kurmadan önce onu kaldırın.", "Önemli")}

<h2>2.2 APK ile internetsiz kurulum</h2>
<ol>
<li><b>Android</b> klasöründeki <b>Beje-1.1.0.apk</b> dosyasını ve <b>BejeModels</b> klasörünü telefona kopyalayın (USB kablo, bellek kartı, Bluetooth…).
Önerilen yer: <i>Dahili depolama › Download › BejeModels</i>.</li>
<li>Telefonda APK dosyasına dokunun. “Bilinmeyen uygulamaları yükle” izni istenirse izin verin.</li>
<li>Kurulum bitince <b>Bêje</b>'yi açın. İlk açılışta <b>Dil paketleri</b> ekranı gelir.</li>
</ol>
<h2>2.3 APK sürümünde dil paketini ekleme</h2>
{two_images("android_packs.jpg", "android_main.jpg", 5.6, "Dil paketleri ekranı (Türkçe etkin)", "Ana ekran")}
<ol>
<li>Kullanacağınız dilin yanındaki <b>İçe aktar</b> düğmesine, ardından <b>Devam et</b>'e dokunun.</li>
<li>Açılan dosya seçicide <i>Download › BejeModels</i> klasörüne girin.</li>
<li>Alttaki <b>BU KLASÖRÜ KULLAN</b> düğmesine, sonra <b>İZİN VER</b>'e dokunun.</li>
<li>Birkaç saniye içinde dil <b>Etkin</b> olur ve üstte “Hazır · internet gerekmez” yazar.</li>
</ol>
{tip("Model uygulamanın içine kopyalanır. Ekledikten sonra Download/BejeModels klasörünü silerek yer açabilirsiniz.")}

<h2>2.4 Dikte</h2>
<ol>
<li>Ortadaki kırmızı <b>mikrofon</b> düğmesine dokunun. İlk seferde mikrofon izni istenir; izin verin.</li>
<li>Konuşun. Söyledikleriniz anında metin kutusuna yazılır; düğmenin çevresindeki sarı ışınlar ses seviyesini gösterir.</li>
<li>Bitirince düğmeye tekrar dokunun (kare simge). Son söylediğiniz kelimeler de metne eklenir.</li>
<li>Metni klavyeyle istediğiniz gibi düzeltebilirsiniz.</li>
</ol>
{table(["Düğme", "İşlev"], [
    ["Kaydet (sol alt)", "Metni TXT, PDF, ODT ya da Word (DOCX) olarak kaydeder"],
    ["Paylaş (sağ alt)", "Metni WhatsApp, e-posta, not uygulaması vb. ile paylaşır"],
    ["Kopyala (metin kartı)", "Metni panoya kopyalar"],
    ["Çöp kutusu (metin kartı)", "Metni temizler; alttaki <b>Geri al</b> ile geri getirilebilir"],
    ["Dil düğmesi (üstte)", "Konuşma dilini değiştirir / Dil paketleri ekranını açar"],
    ["Nota simgesi (üstte)", "Telefondaki bir ses kaydını (MP3, M4A, WAV…) yazıya döker"],
    ["Menü (sağ üstte üç nokta)", "Dil paketleri, Arayüz dili, Hakkında"],
], [32, 68])}
{tip("Başka bir uygulamada bir ses dosyasını <b>Paylaş › Bêje</b> ile gönderirseniz de yazıya dökülür.")}

<h2>2.5 Arayüz dili ve tema</h2>
<p><b>Menü</b> (üç nokta) › <b>Arayüz dili</b>'nden Türkçe, Kurmancî, العربية, فارسی ya da English seçilebilir. Arapça ve Farsça'da ekran
sağdan sola düzenlenir. Koyu tema telefonun ayarını kendiliğinden izler.</p>
{img("android_dark_ar.jpg", 4.6, "Koyu tema, Arapça arayüz")}

<h1>3. Windows</h1>
<h2>3.1 Kurulum</h2>
<ol>
<li><b>Beje-1.1.0-Windows-Setup.exe</b> dosyasına çift tıklayın.</li>
<li>“Windows bilgisayarınızı korudu” uyarısı çıkarsa <b>Ek bilgi › Yine de çalıştır</b>'a tıklayın
(uygulama dijital olarak imzalanmadığı için bu uyarı normaldir).</li>
<li>Sihirbazda <b>İleri › Yükle › Son</b>'a tıklayın. Yönetici şifresi istenmez; kurulum yalnızca sizin hesabınıza yapılır.</li>
<li>Bêje; <b>Başlat menüsüne</b> ve <b>masaüstüne</b> kısayol olarak eklenir. Dil paketleri de birlikte kurulur.</li>
</ol>
{tip("Yeni bir sürümü kurmak için yeni Setup dosyasını çalıştırmanız yeterli; açık olan Bêje'yi kurulum sihirbazı kendisi kapatır, ayarlarınız korunur.")}
{tip("Kaldırmak için: <b>Ayarlar › Uygulamalar › Yüklü uygulamalar › Bêje › Kaldır</b>.")}
{tip("Kurulum yapmak istemiyorsanız <b>Beje-1.1.0-Windows-x64-portable.zip</b>'i bir klasöre ayıklayıp içindeki <b>Beje.exe</b>'yi çalıştırabilirsiniz (ör. USB bellekten).", "Not")}
<h2>3.2 Kullanım</h2>
{img("d4.jpg", 13, "Bêje masaüstü (açık tema)")}
<ul>
<li><b>F8</b> ya da <b>Ctrl+Alt+Space</b>: Hangi programda olursanız olun dikteyi başlatır/durdurur.</li>
<li><b>Diğer uygulamaya yaz</b> kutusu işaretliyse, tanınan metin o an açık olan programa (Word, tarayıcı, e-posta…) da yazılır.
Örnek: Word'ü açın, F8'e basın, konuşun — metin doğrudan Word'e yazılır.</li>
<li>Pencereyi kapattığınızda Bêje saat yanındaki <b>sistem tepsisinde</b> çalışmaya devam eder. Tamamen kapatmak için tepsideki simgeye sağ tıklayıp <b>Çıkış</b>'ı seçin.</li>
<li><b>Menü</b> (üç nokta) › <b>Oturum açılışında başlat</b>: Bêje, Windows açılınca arka planda hazır bekler.</li>
</ul>
{tip("Mikrofon çalışmıyorsa: Ayarlar › Gizlilik ve güvenlik › Mikrofon › “Masaüstü uygulamalarının mikrofonunuza erişmesine izin ver” açık olmalı.", "Önemli")}

<h1>4. Linux</h1>
<p>Linux için üç seçenek vardır. Ubuntu, Debian, Linux Mint, Pardus, Zorin, elementary gibi dağıtımlarda <b>.deb</b> paketini kullanın;
diğer dağıtımlarda (Fedora, openSUSE, Arch…) tek dosyalık <b>AppImage</b>'ı.</p>

<h2>4.1 .deb paketi ile kurulum (Ubuntu, Debian, Mint…)</h2>
<ol>
<li><b>beje_1.1.0_amd64.deb</b> dosyasına çift tıklayın. Yazılım Merkezi / Uygulama Merkezi açılır; <b>Yükle</b>'ye tıklayıp şifrenizi girin.</li>
<li>Ya da terminalde (dosyanın bulunduğu klasörde):
{code("sudo apt install ./beje_1.1.0_amd64.deb")}
İnternet gerekmez: Java ve dil paketleri paketin içindedir, diğer gereksinimler her masaüstü sisteminde zaten kuruludur.</li>
<li>Bêje kendiliğinden uygulama menüsüne eklenir; <b>Uygulamaları göster</b> / <b>Menü</b>'de ya da aramada “Bêje” yazarak bulun.
Terminalden <b>beje</b> komutuyla da açılır.</li>
</ol>
{tip("Kaldırmak için Yazılım Merkezi'nde Bêje › Kaldır, ya da: <i>sudo apt remove beje</i>", "Not")}

<h2>4.2 AppImage (tüm dağıtımlar)</h2>
<ol>
<li><b>Beje-1.1.0-x86_64.AppImage</b> dosyasını kalıcı bir yere koyun. Önerilen:
{code("~/Uygulamalar/Beje-1.1.0-x86_64.AppImage")}
(<i>~</i> ev klasörünüzdür. “Uygulamalar” ya da “Applications” klasörü yoksa oluşturun.)</li>
<li>Dosyaya çalıştırma izni verin:
<ul><li><b>Dosyalar (GNOME):</b> sağ tık › <b>Özellikler</b> › <b>Programı çalıştırmaya izin ver</b> anahtarını açın.</li>
<li><b>Dolphin (KDE):</b> sağ tık › <b>Özellikler</b> › <b>İzinler</b> › <b>Çalıştırılabilir</b>.</li>
<li><b>Terminal:</b></li></ul>
{code("chmod +x ~/Uygulamalar/Beje-1.1.0-x86_64.AppImage")}</li>
<li>Dosyaya çift tıklayın. Dil paketleri AppImage'ın içindedir; başka bir şey gerekmez.</li>
</ol>

<h3>AppImage'ı uygulama menüsüne ekleme</h3>
<p>AppImage normalde yalnızca bir dosyadır; uygulama listesinde görünmez. Bêje bunu sizin için tek tıkla yapar:</p>
<p><b>Yöntem 1 — Bêje'nin içinden (önerilen)</b></p>
<ol>
<li>Bêje'yi AppImage'dan <b>ilk kez</b> açtığınızda “Bêje'yi uygulama menüsüne ekleyelim mi?” sorusu gelir. <b>Uygulama menüsüne ekle</b>'ye tıklayın.</li>
<li>Soruyu kapattıysanız: sağ üstteki <b>Menü</b> (üç nokta) › <b>Uygulama menüsüne ekle</b>.</li>
<li>Artık Bêje; GNOME'da <b>Uygulamaları göster</b>, KDE'de <b>Uygulama Başlatıcı</b>, Cinnamon/XFCE'de <b>Menü</b> içinde,
kendi simgesiyle görünür. Arama kutusuna “Bêje” ya da “dikte” yazarak da bulabilirsiniz.</li>
<li>Rıhtıma/görev çubuğuna sabitlemek için: Bêje açıkken simgesine sağ tıklayıp <b>Sık Kullanılanlara Ekle</b> (GNOME) / <b>Görev Yöneticisine Sabitle</b> (KDE).</li>
<li>Kaldırmak için: Menü › <b>Uygulama menüsünden kaldır</b>.</li>
</ol>
{tip("Bu işlem yönetici (sudo) izni istemez ve internete bağlanmaz; yalnızca ev klasörünüze bir kısayol (~/.local/share/applications/beje.desktop) ve simge yazar.", "Not")}
{tip("AppImage dosyasını başka bir klasöre taşırsanız menüdeki kısayol çalışmaz. Yeni yerinden bir kez açıp Menü › <b>Uygulama menüsüne ekle</b>'yi tekrar seçin.", "Önemli")}
<p><b>Yöntem 2 — Elle ekleme.</b> Şu içerikle <i>~/.local/share/applications/beje.desktop</i> dosyasını oluşturun (yolu kendi AppImage yolunuzla değiştirin):</p>
{code("[Desktop Entry]<br>Type=Application<br>Name=Bêje<br>Comment=Çevrimdışı dikte<br>Exec=/home/KULLANICI/Uygulamalar/Beje-1.1.0-x86_64.AppImage<br>Icon=beje<br>Terminal=false<br>Categories=Utility;Office;")}
<p><b>Yöntem 3 — AppImage yöneticileri.</b> <b>Gear Lever</b> (Flathub) ya da <b>AppImageLauncher</b> kuruluysa, AppImage'ı açtığınızda
“Entegre et” seçeneği sunarlar. Bu programların kurulumu internet gerektirir; Yöntem 1 gerektirmez.</p>

<h3>AppImage açılmazsa</h3>
{table(["Belirti", "Çözüm"], [
    ["Çift tıklayınca hiçbir şey olmuyor", "Çalıştırma izni verilmemiş — yukarıdaki 2. adım."],
    ["“FUSE” ile ilgili bir hata", "Bêje'nin AppImage'ı libfuse2 gerektirmez; yine de hata alırsanız terminalde: <br><i>./Beje-1.1.0-x86_64.AppImage --appimage-extract-and-run</i>"],
], [35, 65])}

<h2>4.3 Klasör sürümü (tar.gz)</h2>
{code("tar -xzf Beje-1.1.0-Linux-x64.tar.gz<br>./Beje/beje                # çalıştır<br>./Beje/install.sh           # uygulama menüsüne ekle<br>./Beje/uninstall.sh         # menüden kaldır")}

<h2>4.4 Her yerden dikte için klavye kısayolu</h2>
<p>Linux'ta güvenlik nedeniyle uygulamalar genel kısayol tanımlayamaz; pencere açıkken <b>F8</b> çalışır. Her yerden kullanmak için:</p>
<ol>
<li><b>Ayarlar › Klavye › Klavye Kısayolları › Özel Kısayollar</b> (GNOME) ya da <b>Sistem Ayarları › Kısayollar › Özel</b> (KDE).</li>
<li>Yeni kısayol ekleyin. Komut olarak şunu yazın:
{code("beje --toggle                                           (.deb ile kurduysanız)<br>/home/KULLANICI/Uygulamalar/Beje-1.1.0-x86_64.AppImage --toggle   (AppImage)")}</li>
<li>Bir tuş atayın (ör. <b>Super+D</b>). Bu tuş Bêje açıkken dikteyi başlatır/durdurur.</li>
</ol>
<h2>4.5 Diğer uygulamalara yazma</h2>
<p><b>Diğer uygulamaya yaz</b> özelliği için şu araçlardan biri kurulu olmalıdır: X11 oturumunda <b>xdotool</b>; Wayland oturumunda
<b>wtype</b> ya da <b>ydotool</b>. Kurulu değilse metni <b>Kopyala</b> düğmesiyle aktarabilirsiniz.</p>
{code("sudo apt install xdotool wtype      # Ubuntu / Debian / Mint")}

<h1>5. macOS</h1>
<h2>5.1 Kurulum</h2>
<ol>
<li>İşlemcinizi öğrenin: Apple menüsü › <b>Bu Mac Hakkında</b>. “Apple M1/M2/M3/M4” yazıyorsa
<b>Beje-1.1.0-macOS-AppleSilicon.pkg</b>, “Intel” yazıyorsa <b>Beje-1.1.0-macOS-Intel.pkg</b> dosyasını kullanın.</li>
<li>.pkg dosyasına çift tıklayın. Paket dijital olarak imzalı olmadığı için macOS önce bir uyarı gösterir:
<ul><li><b>macOS 15 ve üstü:</b> Uyarıyı kapatın, <b>Sistem Ayarları › Gizlilik ve Güvenlik</b>'i açın, aşağıdaki
“Beje-1.1.0-macOS… engellendi” satırında <b>Yine de Aç</b>'a tıklayıp şifrenizi girin.</li>
<li><b>macOS 14 ve öncesi:</b> .pkg dosyasına <b>sağ tıklayıp Aç</b> deyin, çıkan pencerede yine <b>Aç</b>'a tıklayın.</li></ul></li>
<li>Kurulum sihirbazı açılır: <b>Sürdür › Yükle</b>'ye tıklayın ve Mac şifrenizi girin.</li>
<li>“Kurulum başarıyla tamamlandı” yazınca <b>Kapat</b>'a tıklayın. Bêje <b>Uygulamalar</b> klasörüne kurulmuştur;
dil paketleri ve Java da içindedir.</li>
<li>Bêje'yi <b>Launchpad</b>'den ya da <b>Uygulamalar</b> klasöründen açın. Mikrofon izni sorulduğunda <b>İzin Ver</b> deyin.</li>
</ol>
{tip("Kurulumla gelen uygulama için ayrıca “Yine de Aç” onayı gerekmez; uyarı yalnızca .pkg dosyası için bir kez çıkar.", "Not")}
<h2>5.2 Kullanım notları</h2>
<ul>
<li><b>Diğer uygulamaya yaz</b> için: Sistem Ayarları › Gizlilik ve Güvenlik › <b>Erişilebilirlik</b> bölümünde Bêje'ye izin verin.</li>
<li>Kaldırmak için: Uygulamalar klasöründeki <b>Beje</b>'yi Çöp Sepeti'ne sürükleyin.</li>
</ul>
{tip("Pencere açıkken F8 dikteyi başlatır/durdurur. Bêje'yi Dock'ta tutmak için açıkken simgesine sağ tıklayıp Seçenekler › Dock'ta Tut deyin.")}

<h1>6. Bilgisayar sürümlerinde ortak özellikler</h1>
<h2>6.1 Dil paketleri penceresi</h2>
{img("d5.jpg", 12.5, "Menü › Dil paketleri")}
<ul>
<li><b>Kullan</b>: o dile geçer. <b>Etkin</b>: şu an kullanılan dil.</li>
<li>Üç dil paketi uygulamayla birlikte gelir ve “Hazır” görünür.</li>
<li><b>Klasör seç…</b>: İsterseniz başka bir Vosk modeli kullanmak için kendi model klasörünüzü gösterebilirsiniz.</li>
</ul>
<h2>6.2 Dikte komutları</h2>
<p>Bilgisayar sürümünde şu sözcükleri söylediğinizde noktalama işaretine dönüşür. Cümle başları da kendiliğinden büyük harfle yazılır.</p>
{table(["Türkçe", "Farsça", "Arapça", "Sonuç"], [
    ["virgül", "ویرگول", "فاصلة", "، / ,"],
    ["nokta", "نقطه", "نقطة", "."],
    ["soru işareti", "علامت سوال", "علامة استفهام", "? / ؟"],
    ["ünlem", "علامت تعجب", "علامة تعجب", "!"],
    ["iki nokta", "دو نقطه", "نقطتان", ":"],
    ["yeni satır", "خط جدید", "سطر جديد", "alt satır"],
    ["yeni paragraf", "پاراگراف جدید", "فقرة جديدة", "boş satır"],
    ["parantez aç / kapa", "پرانتز باز / بسته", "افتح / اغلق قوس", "( )"],
    ["tırnak aç / kapa", "گیومه باز / بسته", "افتح / اغلق اقتباس", "\" \""],
], [27, 25, 25, 23])}
<h2>6.3 Ses dosyasını yazıya dökme</h2>
<p>Menü › <b>Ses dosyasını yazıya dök…</b> ile bir kayıt seçin. WAV dosyaları her zaman desteklenir; MP3, M4A, OGG gibi biçimler için
bilgisayarda <b>ffmpeg</b> kurulu olmalıdır. İşlem sırasında mikrofon düğmesi durdurma düğmesine dönüşür.</p>
<h2>6.4 Kaydetme ve kopyalama</h2>
<p>Sol alttaki <b>Kaydet</b> düğmesi metni TXT, PDF, Word (DOCX) ya da Word 97-2003 (DOC) olarak kaydeder.
Sağ alttaki <b>Kopyala</b> düğmesi metni panoya alır.</p>
<h2>6.5 Arayüz dili ve tema</h2>
<p>Menü › <b>Arayüz dili</b> (Türkçe, Kurmancî, العربية, فارسی, English) ve Menü › <b>Tema</b> (Sistem, Açık, Koyu).
Farsça ve Arapça konuşma dillerinde metin sağdan sola yazılır.</p>
{img("d8.jpg", 12.5, "Koyu tema, Arapça arayüz, Farsça dikte")}

<h1>7. Sorun giderme</h1>
{table(["Sorun", "Çözüm"], [
    ["“Dil paketi gerekli” yazıyor (Android)", "Android/BejeModels klasörünü telefona kopyalayıp Dil paketleri ekranında İçe aktar ile ekleyin (bölüm 2.3)."],
    ["Mikrofon çalışmıyor", "İşletim sisteminin gizlilik ayarlarında mikrofon iznini açın; mikrofonun bağlı ve varsayılan giriş aygıtı olduğundan emin olun."],
    ["Tanıma doğruluğu düşük", "Mikrofona yakın, net ve normal hızda konuşun; ortam gürültüsünü azaltın; doğru konuşma dilinin seçili olduğunu kontrol edin."],
    ["Arapça model geç açılıyor", "Normal: model büyüktür (≈670 MB). Az belleği olan cihazlarda açılmayabilir."],
    ["Metin diğer uygulamaya yazılmıyor", "“Diğer uygulamaya yaz” işaretli olmalı ve hedef pencere odakta olmalı. Linux'ta xdotool/wtype, macOS'ta Erişilebilirlik izni gerekir."],
    ["Windows: “Bêje zaten çalışıyor”", "Bêje sistem tepsisinde açık. Saat yanındaki simgeye tıklayın."],
    ["Windows kurulumunda “Dosya yazmak için açılırken hata”", "Eski bir Bêje açık kalmış. Sistem tepsisindeki Bêje simgesine sağ tıklayıp <b>Çıkış</b> deyin, sonra <b>Yeniden Dene</b>'ye tıklayın."],
    ["Android: “Uygulama yüklenmedi” / paket çakışıyor", "APK ile kurulmuş Bêje ile Play sürümü birbirinin üzerine kurulamaz. Önce mevcut Bêje'yi kaldırın."],
    ["Android: dil paketi inmiyor", "İnternet bağlantınızı ve cihazdaki boş alanı kontrol edin; satırdaki <b>Tekrar dene</b>'ye dokunun. Arapça paketi için Wi-Fi önerilir."],
    ["“Model yüklenemedi” hatası", "Hata penceresinde yazan ayrıntıyı ve günlük dosyasını (aşağıdaki satır) bize iletin."],
    ["Hata ayrıntısı gerekiyor", "Günlük dosyası (beje.log) — Windows: %APPDATA%\\Beje\\logs, Linux: ~/.local/share/beje/logs, macOS: ~/Library/Application Support/Beje/logs"],
], [32, 68])}

<h1>8. Gizlilik</h1>
<p>Bêje konuşmanızı yalnızca cihazınızda işler. Ses kaydı saklanmaz, metniniz siz kaydetmedikçe hiçbir yere yazılmaz ve
uygulama hiçbir veriyi internete göndermez. İnternet yalnızca Google Play sürümünde dil paketlerini indirmek için kullanılır.</p>
<p>Gizlilik politikasının tamamı: <b>https://helgurt574-cpu.github.io/beje-privacy/</b> · İletişim: helgurt574@gmail.com</p>
<p><font size="2" color="#6b5a58">Açık kaynak bileşenler: Vosk ve Kaldi konuşma modelleri, JNA, FlatLaf, PDFBox, AndroidX ve Material Components
(Apache 2.0 lisansı).</font></p>
</body></html>
"""


def main():
    os.makedirs(OUT, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        src = os.path.join(tmp, "Beje-Kullanim-Kilavuzu.html")
        with open(src, "w", encoding="utf-8") as fh:
            fh.write(html)
        profile = "-env:UserInstallation=file://" + os.path.join(tmp, "lo")
        subprocess.run(["soffice", profile, "--headless", "--convert-to", "docx:MS Word 2007 XML",
                        "--outdir", tmp, src], check=True, capture_output=True)
        docx = os.path.join(tmp, "Beje-Kullanim-Kilavuzu.docx")
        subprocess.run(["soffice", profile, "--headless", "--convert-to", "pdf", "--outdir", tmp, docx],
                       check=True, capture_output=True)
        for name in ("Beje-Kullanim-Kilavuzu.docx", "Beje-Kullanim-Kilavuzu.pdf"):
            shutil.move(os.path.join(tmp, name), os.path.join(OUT, name))
            print("yazıldı:", os.path.join(OUT, name))


if __name__ == "__main__":
    main()
